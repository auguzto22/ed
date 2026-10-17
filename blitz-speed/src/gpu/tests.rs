//! Testes do pipeline numa GPU virtual (Mesa llvmpipe, EGL "surfaceless").
//!
//! Rodam os shaders de verdade sobre cenas sintéticas com movimento conhecido e medem o
//! resultado contra a verdade: precisão do flow, quadro intermediário melhor que a mistura,
//! oclusões, detecção de corte, direção e comprimento do motion blur (ângulo do obturador) e
//! acumulação temporal. `cargo test --features gpu-tests`.

use super::gl::{self, GLint, GLuint};
use super::pipeline::{BlurParams, MotionPipeline, Synthesis};
use std::ffi::{c_char, c_int, c_void};

// ---- EGL mínimo ----------------------------------------------------------------------------

extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

type EglDisplay = *mut c_void;
type EglConfig = *mut c_void;
type EglContext = *mut c_void;
type EglSurface = *mut c_void;

struct Egl {
    display: EglDisplay,
    context: EglContext,
    surface: EglSurface,
    make_current: unsafe extern "C" fn(EglDisplay, EglSurface, EglSurface, EglContext) -> u32,
    destroy_context: unsafe extern "C" fn(EglDisplay, EglContext) -> u32,
    destroy_surface: unsafe extern "C" fn(EglDisplay, EglSurface) -> u32,
}

impl Egl {
    fn new() -> Egl {
        unsafe {
            std::env::set_var("EGL_PLATFORM", "surfaceless");
            let library = dlopen(c"libEGL.so.1".as_ptr(), 2);
            assert!(!library.is_null(), "libEGL.so.1 não encontrada");
            macro_rules! f {
                ($name:literal, $ty:ty) => {{
                    let pointer = dlsym(library, concat!($name, "\0").as_ptr() as *const c_char);
                    assert!(!pointer.is_null(), $name);
                    std::mem::transmute::<*mut c_void, $ty>(pointer)
                }};
            }
            let get_display = f!("eglGetDisplay", unsafe extern "C" fn(*mut c_void) -> EglDisplay);
            let initialize = f!("eglInitialize", unsafe extern "C" fn(EglDisplay, *mut i32, *mut i32) -> u32);
            let choose = f!("eglChooseConfig", unsafe extern "C" fn(EglDisplay, *const i32, *mut EglConfig, i32, *mut i32) -> u32);
            let create_context = f!("eglCreateContext", unsafe extern "C" fn(EglDisplay, EglConfig, EglContext, *const i32) -> EglContext);
            let create_pbuffer = f!("eglCreatePbufferSurface", unsafe extern "C" fn(EglDisplay, EglConfig, *const i32) -> EglSurface);
            let make_current = f!("eglMakeCurrent", unsafe extern "C" fn(EglDisplay, EglSurface, EglSurface, EglContext) -> u32);
            let destroy_context = f!("eglDestroyContext", unsafe extern "C" fn(EglDisplay, EglContext) -> u32);
            let destroy_surface = f!("eglDestroySurface", unsafe extern "C" fn(EglDisplay, EglSurface) -> u32);
            let display = get_display(std::ptr::null_mut());
            assert!(initialize(display, std::ptr::null_mut(), std::ptr::null_mut()) != 0);
            const SURFACE_TYPE: i32 = 0x3033;
            const PBUFFER_BIT: i32 = 0x0001;
            const RENDERABLE_TYPE: i32 = 0x3040;
            const ES2_BIT: i32 = 0x0004;
            const NONE: i32 = 0x3038;
            let attributes = [SURFACE_TYPE, PBUFFER_BIT, RENDERABLE_TYPE, ES2_BIT, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, NONE];
            let mut config: EglConfig = std::ptr::null_mut();
            let mut count = 0;
            assert!(choose(display, attributes.as_ptr(), &mut config, 1, &mut count) != 0 && count > 0);
            let context_attributes = [0x3098 /* CONTEXT_CLIENT_VERSION */, 2, NONE];
            let context = create_context(display, config, std::ptr::null_mut(), context_attributes.as_ptr());
            assert!(!context.is_null());
            let surface_attributes = [0x3057, 16, 0x3056, 16, NONE];
            let surface = create_pbuffer(display, config, surface_attributes.as_ptr());
            assert!(make_current(display, surface, surface, context) != 0);
            Egl { display, context, surface, make_current, destroy_context, destroy_surface }
        }
    }
}

impl Drop for Egl {
    fn drop(&mut self) {
        unsafe {
            (self.make_current)(self.display, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
            (self.destroy_surface)(self.display, self.surface);
            (self.destroy_context)(self.display, self.context);
        }
    }
}

// ---- cenas sintéticas -----------------------------------------------------------------------

const W: i32 = 320;
const H: i32 = 180;

/// Textura parecida com vídeo real: ruído fractal (4 oitavas, de 32 px a 4 px) com bordas
/// suaves e detalhe fino, mais uma rampa lenta de iluminação. Determinística por `seed`.
fn pattern(x: f32, y: f32, seed: f32) -> [u8; 3] {
    fn hash(ix: i32, iy: i32, seed: u32) -> f32 {
        let mut h = (ix as u32).wrapping_mul(374_761_393) ^ (iy as u32).wrapping_mul(668_265_263) ^ seed.wrapping_mul(2_246_822_519);
        h = (h ^ (h >> 13)).wrapping_mul(1_274_126_177);
        ((h ^ (h >> 16)) & 0xFFFF) as f32 / 65535.0
    }
    fn noise(x: f32, y: f32, seed: u32) -> f32 {
        let (x0, y0) = (x.floor(), y.floor());
        let (fx, fy) = (x - x0, y - y0);
        let (sx, sy) = (fx * fx * (3.0 - 2.0 * fx), fy * fy * (3.0 - 2.0 * fy));
        let (ix, iy) = (x0 as i32, y0 as i32);
        let a = hash(ix, iy, seed) + (hash(ix + 1, iy, seed) - hash(ix, iy, seed)) * sx;
        let b = hash(ix, iy + 1, seed) + (hash(ix + 1, iy + 1, seed) - hash(ix, iy + 1, seed)) * sx;
        a + (b - a) * sy
    }
    let s = (seed * 1000.0) as u32;
    let channel = |k: u32| {
        let mut v = 0.0;
        let mut amplitude = 0.5;
        for (octave, size) in [32.0f32, 16.0, 8.0, 4.0].iter().enumerate() {
            v += amplitude * noise(x / size, y / size, s + k * 31 + octave as u32 * 7);
            amplitude *= 0.55;
        }
        (v * 0.9 + 0.1 + 0.08 * ((x + y) * 0.01).sin()).clamp(0.0, 1.0)
    };
    [(channel(0) * 255.0) as u8, (channel(1) * 255.0) as u8, (channel(2) * 255.0) as u8]
}

/// Fundo + quadrado texturizado em (`sx`, `sy`); `shift` desloca a imagem toda.
fn scene(shift: (f32, f32), square: Option<(f32, f32)>) -> Vec<u8> {
    let mut pixels = vec![0u8; (W * H * 4) as usize];
    for y in 0..H {
        for x in 0..W {
            let (fx, fy) = (x as f32 - shift.0, y as f32 - shift.1);
            let mut c = pattern(fx, fy, 0.0);
            if let Some((sx, sy)) = square {
                let (u, v) = (x as f32 - sx, y as f32 - sy);
                if (0.0..48.0).contains(&u) && (0.0..48.0).contains(&v) {
                    c = pattern(u * 2.3, v * 2.1, 4.0);
                    c = [c[0] / 2 + 120, c[1] / 3 + 20, c[2] / 3 + 10];
                }
            }
            let i = ((y * W + x) * 4) as usize;
            pixels[i..i + 3].copy_from_slice(&c);
            pixels[i + 3] = 255;
        }
    }
    pixels
}

struct Tex {
    texture: GLuint,
    fbo: GLuint,
}

fn upload(pixels: &[u8]) -> Tex {
    let gl = gl::gl().unwrap();
    unsafe {
        let mut texture = 0;
        (gl.GenTextures)(1, &mut texture);
        (gl.BindTexture)(gl::TEXTURE_2D, texture);
        for (p, v) in [(gl::TEXTURE_MIN_FILTER, gl::LINEAR), (gl::TEXTURE_MAG_FILTER, gl::LINEAR), (gl::TEXTURE_WRAP_S, gl::CLAMP_TO_EDGE), (gl::TEXTURE_WRAP_T, gl::CLAMP_TO_EDGE)] {
            (gl.TexParameteri)(gl::TEXTURE_2D, p, v);
        }
        (gl.PixelStorei)(gl::UNPACK_ALIGNMENT, 1);
        (gl.TexImage2D)(gl::TEXTURE_2D, 0, gl::RGBA as GLint, W, H, 0, gl::RGBA, gl::UNSIGNED_BYTE,
            if pixels.is_empty() { std::ptr::null() } else { pixels.as_ptr() as *const c_void });
        let mut fbo = 0;
        (gl.GenFramebuffers)(1, &mut fbo);
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, fbo);
        (gl.FramebufferTexture2D)(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, texture, 0);
        assert_eq!((gl.CheckFramebufferStatus)(gl::FRAMEBUFFER), gl::FRAMEBUFFER_COMPLETE);
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, 0);
        Tex { texture, fbo }
    }
}

fn read(target: &Tex) -> Vec<u8> {
    let gl = gl::gl().unwrap();
    let mut pixels = vec![0u8; (W * H * 4) as usize];
    unsafe {
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, target.fbo);
        (gl.PixelStorei)(gl::PACK_ALIGNMENT, 1);
        (gl.ReadPixels)(0, 0, W, H, gl::RGBA, gl::UNSIGNED_BYTE, pixels.as_mut_ptr() as *mut c_void);
        assert_eq!((gl.GetError)(), gl::NO_ERROR);
    }
    pixels
}

/// Erro médio absoluto (0..255) numa região, ignorando `margin` px nas bordas.
fn mae(a: &[u8], b: &[u8], margin: i32, region: Option<(i32, i32, i32, i32)>) -> f32 {
    let (x0, y0, x1, y1) = region.unwrap_or((margin, margin, W - margin, H - margin));
    let mut sum = 0f64;
    let mut n = 0f64;
    for y in y0..y1 {
        for x in x0..x1 {
            let i = ((y * W + x) * 4) as usize;
            for c in 0..3 {
                sum += (a[i + c] as f64 - b[i + c] as f64).abs();
            }
            n += 3.0;
        }
    }
    (sum / n) as f32
}

fn pipeline() -> MotionPipeline {
    MotionPipeline::new(W, H, 160, 90, 4, 4).expect("pipeline")
}

// ---- testes --------------------------------------------------------------------------------

#[test]
fn probe_reports_highp() {
    let _egl = Egl::new();
    let caps = gl::probe().unwrap();
    assert!(caps.highp_fragment, "{caps:?}");
    assert!(caps.max_texture_size >= 2048);
}

#[test]
fn flow_recovers_a_known_translation() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let a = upload(&scene((0.0, 0.0), None));
    let b = upload(&scene((8.0, -4.0), None));
    let stats = p.analyze(a.texture, b.texture, (0, 1), true);
    assert!(stats.has_flow && !stats.cut, "{stats:?}");
    assert!(stats.mean_confidence > 0.5, "{stats:?}");
    // Grade de flow com metade da resolução: (8, −4) px viram (4, −2). O eixo y das texturas
    // cresce para cima, então −4 px na imagem (para cima) é +2 no flow.
    let flow = p.read_forward_flow();
    let (mut sx, mut sy, mut n) = (0f32, 0f32, 0f32);
    for y in 10..80 {
        for x in 10..150 {
            let (fx, fy) = flow[(y * 160 + x) as usize];
            sx += fx;
            sy += fy;
            n += 1.0;
        }
    }
    let (mx, my) = (sx / n, sy / n);
    assert!((mx - 4.0).abs() < 0.3 && (my - -2.0).abs() < 0.3 || (my - 2.0).abs() < 0.3 && (mx - 4.0).abs() < 0.3, "flow médio ({mx}, {my})");
    p.release();
}

#[test]
fn optical_flow_beats_blending_on_a_moving_object() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let a = upload(&scene((0.0, 0.0), Some((60.0, 60.0))));
    let b = upload(&scene((0.0, 0.0), Some((84.0, 66.0))));
    let truth = scene((0.0, 0.0), Some((72.0, 63.0)));
    let out = upload(&[]);
    p.analyze(a.texture, b.texture, (0, 1), true);
    p.render(a.texture, b.texture, 0.5, Synthesis::OpticalFlow, 0.0, None, out.fbo, (0, 0, W, H));
    let flow = read(&out);
    p.render(a.texture, b.texture, 0.5, Synthesis::Blend, 0.0, None, out.fbo, (0, 0, W, H));
    let blend = read(&out);
    p.render(a.texture, b.texture, 0.5, Synthesis::Repeat, 0.0, None, out.fbo, (0, 0, W, H));
    let repeat = read(&out);
    let around = Some((40, 40, 160, 140));
    let (e_flow, e_blend, e_repeat) = (mae(&flow, &truth, 0, around), mae(&blend, &truth, 0, around), mae(&repeat, &truth, 0, around));
    eprintln!("erro em volta do objeto: flow {e_flow:.2}, mistura {e_blend:.2}, repetição {e_repeat:.2}");
    assert!(e_flow < e_blend * 0.5, "flow {e_flow} mistura {e_blend}");
    assert!(e_flow < e_repeat * 0.5, "flow {e_flow} repetição {e_repeat}");
    // O interior do objeto sai praticamente exato; o erro que sobra fica nas faixas de oclusão.
    assert!(mae(&flow, &truth, 0, Some((76, 67, 116, 107))) < 1.5);
    // O fundo parado longe do objeto fica intacto.
    let far = Some((200, 20, 300, 160));
    assert!(mae(&flow, &truth, 0, far) < 1.5);
    p.release();
}

#[test]
fn global_motion_is_interpolated_accurately() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let a = upload(&scene((0.0, 0.0), None));
    let b = upload(&scene((10.0, 6.0), None));
    let out = upload(&[]);
    p.analyze(a.texture, b.texture, (0, 1), true);
    for (t, shift) in [(0.25f32, (2.5f32, 1.5f32)), (0.5, (5.0, 3.0)), (0.75, (7.5, 4.5))] {
        p.render(a.texture, b.texture, t, Synthesis::OpticalFlow, 0.0, None, out.fbo, (0, 0, W, H));
        let got = read(&out);
        let truth = scene(shift, None);
        let e = mae(&got, &truth, 16, None);
        let e_blend = {
            p.render(a.texture, b.texture, t, Synthesis::Blend, 0.0, None, out.fbo, (0, 0, W, H));
            mae(&read(&out), &truth, 16, None)
        };
        eprintln!("t={t}: flow {e:.2} mistura {e_blend:.2}");
        assert!(e < 3.0 && e < e_blend * 0.4, "t={t}: flow {e} mistura {e_blend}");
    }
    p.release();
}

#[test]
fn identical_frames_pass_through_unchanged() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let pixels = scene((0.0, 0.0), Some((100.0, 50.0)));
    let a = upload(&pixels);
    let b = upload(&pixels);
    let out = upload(&[]);
    p.analyze(a.texture, b.texture, (0, 1), true);
    p.render(a.texture, b.texture, 0.37, Synthesis::OpticalFlow, 0.0, Some(BlurParams { scale: 0.5, samples: 12 }), out.fbo, (0, 0, W, H));
    assert!(mae(&read(&out), &pixels, 0, None) < 0.6);
    p.release();
}

#[test]
fn scene_cuts_are_detected_and_never_blended() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let a = upload(&scene((0.0, 0.0), None));
    // Outra cena: padrão diferente, invertido.
    let other: Vec<u8> = scene((37.0, 91.0), None).chunks_exact(4).flat_map(|c| [255 - c[1], 255 - c[2], 255 - c[0], 255]).collect();
    let b = upload(&other);
    let out = upload(&[]);
    let stats = p.analyze(a.texture, b.texture, (0, 1), true);
    assert!(stats.cut, "{stats:?}");
    p.render(a.texture, b.texture, 0.3, Synthesis::OpticalFlow, 0.0, None, out.fbo, (0, 0, W, H));
    assert!(mae(&read(&out), &scene((0.0, 0.0), None), 0, None) < 0.6, "antes do meio mostra A inteiro");
    p.render(a.texture, b.texture, 0.6, Synthesis::Blend, 0.0, None, out.fbo, (0, 0, W, H));
    assert!(mae(&read(&out), &other, 0, None) < 0.6, "depois do meio mostra B inteiro, sem fusão");
    p.release();
}

#[test]
fn ghost_control_pulls_large_differences_to_the_nearest_frame() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let a_pixels = scene((0.0, 0.0), Some((40.0, 60.0)));
    let b_pixels = scene((0.0, 0.0), Some((200.0, 60.0)));
    let a = upload(&a_pixels);
    let b = upload(&b_pixels);
    let out = upload(&[]);
    p.analyze(a.texture, b.texture, (0, 1), false);
    p.render(a.texture, b.texture, 0.3, Synthesis::Blend, 0.0, None, out.fbo, (0, 0, W, H));
    let plain = read(&out);
    p.render(a.texture, b.texture, 0.3, Synthesis::Blend, 1.0, None, out.fbo, (0, 0, W, H));
    let controlled = read(&out);
    let region = Some((40, 60, 88, 108)); // onde o objeto estava em A
    assert!(mae(&controlled, &a_pixels, 0, region) < mae(&plain, &a_pixels, 0, region) * 0.5);
    p.release();
}

/// Largura média do rastro horizontal do quadrado: colunas cuja cor mudou em relação à cena.
fn smear_width(blurred: &[u8], sharp: &[u8], row: i32) -> i32 {
    (0..W).filter(|&x| {
        let i = ((row * W + x) * 4) as usize;
        (0..3).map(|c| (blurred[i + c] as i32 - sharp[i + c] as i32).abs()).sum::<i32>() > 12
    }).count() as i32
}

#[test]
fn motion_blur_follows_the_motion_and_scales_with_the_shutter() {
    let _egl = Egl::new();
    let mut p = pipeline();
    // Quadrado andando 24 px para a direita entre os quadros; fundo parado.
    let a_pixels = scene((0.0, 0.0), Some((100.0, 66.0)));
    let a = upload(&a_pixels);
    let b = upload(&scene((0.0, 0.0), Some((124.0, 66.0))));
    let out = upload(&[]);
    p.analyze(a.texture, b.texture, (0, 1), true);
    let mut widths = Vec::new();
    for shutter in [90f32, 180.0, 360.0] {
        // Câmera a 1×: exposição/intervalo = ângulo/360.
        p.render(a.texture, b.texture, 0.0, Synthesis::Repeat, 0.0, Some(BlurParams { scale: shutter / 360.0, samples: 24 }), out.fbo, (0, 0, W, H));
        let blurred = read(&out);
        let row = 90;
        widths.push(smear_width(&blurred, &a_pixels, row));
        // O fundo longe do quadrado continua nítido.
        assert!(mae(&blurred, &a_pixels, 0, Some((220, 10, 310, 170))) < 1.0, "fundo borrado com {shutter}°");
        // Na vertical (sem movimento vertical) as bordas de cima e de baixo não se espalham.
        assert!(mae(&blurred, &a_pixels, 0, Some((112, 20, 136, 56))) < 2.0, "rastro vertical com {shutter}°");
    }
    eprintln!("largura do rastro por obturador 90/180/360: {widths:?}");
    assert!(widths[0] < widths[1] && widths[1] < widths[2], "{widths:?}");
    // 360° espalha por volta de um deslocamento inteiro (24 px) além do próprio objeto.
    assert!(widths[2] >= 30, "{widths:?}");
    p.release();
}

#[test]
fn accumulation_averages_frames() {
    let _egl = Egl::new();
    let mut p = pipeline();
    let black: Vec<u8> = (0..W * H).flat_map(|_| [0u8, 0, 0, 255]).collect();
    let white: Vec<u8> = (0..W * H).flat_map(|_| [255u8, 255, 255, 255]).collect();
    let gray: Vec<u8> = (0..W * H).flat_map(|_| [128u8, 64, 32, 255]).collect();
    let frames = [upload(&black), upload(&white), upload(&gray)];
    let out = upload(&[]);
    for frame in &frames {
        p.accumulate(frame.texture);
    }
    p.emit_accumulated(out.fbo, (0, 0, W, H));
    let pixels = read(&out);
    let expected = [(0 + 255 + 128) / 3, (0 + 255 + 64) / 3, (0 + 255 + 32) / 3];
    for c in 0..3 {
        assert!((pixels[c] as i32 - expected[c]).abs() <= 2, "{:?} vs {expected:?}", &pixels[..3]);
    }
    p.release();
}

#[test]
fn caller_gl_state_is_restored() {
    let _egl = Egl::new();
    let gl = gl::gl().unwrap();
    let mut p = pipeline();
    let a = upload(&scene((0.0, 0.0), None));
    let b = upload(&scene((3.0, 0.0), None));
    let out = upload(&[]);
    unsafe {
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, out.fbo);
        (gl.Viewport)(3, 4, 50, 60);
        (gl.Enable)(gl::BLEND);
        (gl.ActiveTexture)(gl::TEXTURE0 + 2);
    }
    p.analyze(a.texture, b.texture, (0, 1), true);
    p.render(a.texture, b.texture, 0.5, Synthesis::OpticalFlow, 0.0, None, 0, (0, 0, W, H));
    unsafe {
        let mut value: GLint = 0;
        (gl.GetIntegerv)(gl::FRAMEBUFFER_BINDING, &mut value);
        assert_eq!(value as GLuint, out.fbo);
        let mut viewport = [0; 4];
        (gl.GetIntegerv)(gl::VIEWPORT, viewport.as_mut_ptr());
        assert_eq!(viewport, [3, 4, 50, 60]);
        assert!((gl.IsEnabled)(gl::BLEND) != 0);
        (gl.GetIntegerv)(gl::ACTIVE_TEXTURE, &mut value);
        assert_eq!(value as u32, gl::TEXTURE0 + 2);
        assert_eq!((gl.GetError)(), gl::NO_ERROR);
    }
    p.release();
}

