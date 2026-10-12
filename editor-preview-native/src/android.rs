#![allow(unsafe_op_in_unsafe_fn)]

use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_void};
use std::num::NonZeroU32;
use std::sync::OnceLock;
use std::thread::ThreadId;

use glow::HasContext;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use serde::Deserialize;

mod transition_gpu;

const VERTEX: &str = "attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMvp; uniform mat4 uTexMatrix; varying vec2 vUv; void main(){ gl_Position=uMvp*vec4(aPosition,0.,1.); vUv=(uTexMatrix*vec4(aUv,0.,1.)).xy; }";
const COPY: &str = "precision mediump float; uniform sampler2D uTexture; varying vec2 vUv; void main(){gl_FragColor=texture2D(uTexture,vUv);}";
const LAYER: &str = include_str!("../shaders/layer.frag");
const IDENTITY: [f32; 16] = [
    1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1., 0., 0., 0., 0., 1.,
];
const FLIP_Y: [f32; 16] = [
    1., 0., 0., 0., 0., -1., 0., 0., 0., 0., 1., 0., 0., 1., 0., 1.,
];

#[link(name = "EGL")]
unsafe extern "C" {
    fn eglGetProcAddress(name: *const c_char) -> *const c_void;
}
#[link(name = "dl")]
unsafe extern "C" {
    fn dlopen(name: *const c_char, flags: i32) -> *mut c_void;
    fn dlsym(handle: *mut c_void, name: *const c_char) -> *const c_void;
}

fn gl_address(name: &str) -> *const c_void {
    static GLES_LIBRARY: OnceLock<usize> = OnceLock::new();
    let Ok(name) = CString::new(name) else {
        return std::ptr::null();
    };
    unsafe {
        let symbol = eglGetProcAddress(name.as_ptr());
        if !symbol.is_null() {
            return symbol;
        }
        let library = *GLES_LIBRARY.get_or_init(|| {
            let name = c"libGLESv2.so";
            dlopen(name.as_ptr(), 2) as usize
        }) as *mut c_void;
        if library.is_null() {
            std::ptr::null()
        } else {
            dlsym(library, name.as_ptr())
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Frame {
    width: i32,
    height: i32,
    background: [f32; 4],
    fit: bool,
    layers: Vec<Layer>,
    transition: Option<Transition>,
    sticker_texture: u32,
    text_texture: u32,
    camera: Option<[f32; 16]>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Layer {
    key: String,
    texture: u32,
    width: i32,
    height: i32,
    source_width: i32,
    source_height: i32,
    zoom: f32,
    x: f32,
    y: f32,
    rotation: f32,
    fixed_rotation: i32,
    flip: bool,
    opacity: f32,
    crop: [f32; 4],
    adjust: [f32; 4],
    color: [f32; 4],
    preset: [f32; 4],
    grade: [f32; 4],
    chroma: [f32; 4],
    key_edge: [f32; 3],
    effects: Vec<Effect>,
    mask: Option<Mask>,
    #[serde(default)]
    edge_fade: [i64; 5],
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Effect {
    program: String,
    intensity: f32,
    blend_mode: f32,
    mask: [f32; 4],
    mask_transform: [f32; 4],
    mask_aspect: f32,
    time: f32,
    params: HashMap<String, f32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Mask {
    center_size: [f32; 4],
    params: [f32; 4],
    meta: [f32; 3],
    path: Vec<[f32; 2]>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Transition {
    left: String,
    right: String,
    program: String,
    progress: f32,
    #[serde(default)]
    debug_mode: u8,
    params: HashMap<String, f32>,
}

struct Target {
    width: i32,
    height: i32,
    texture: glow::NativeTexture,
    framebuffer: glow::NativeFramebuffer,
}

pub(crate) struct Renderer {
    gl: glow::Context,
    owner: ThreadId,
    quad: glow::NativeBuffer,
    layer: glow::NativeProgram,
    copy: glow::NativeProgram,
    programs: HashMap<String, glow::NativeProgram>,
    targets: HashMap<String, Target>,
    scene: Option<Target>,
    export_frames: Vec<(i64, Target)>,
    held: bool,
    pub(crate) delete_gl_on_drop: bool,
}

impl Renderer {
    unsafe fn new() -> Result<Self, String> {
        let gl = glow::Context::from_loader_function(gl_address);
        let quad = gl.create_buffer()?;
        gl.bind_buffer(glow::ARRAY_BUFFER, Some(quad));
        let vertices: [f32; 16] = [
            -1., -1., 0., 0., 1., -1., 1., 0., -1., 1., 0., 1., 1., 1., 1., 1.,
        ];
        let bytes = std::slice::from_raw_parts(vertices.as_ptr() as *const u8, vertices.len() * 4);
        gl.buffer_data_u8_slice(glow::ARRAY_BUFFER, bytes, glow::STATIC_DRAW);
        let layer = match compile(&gl, LAYER) {
            Ok(program) => program,
            Err(error) => {
                gl.delete_buffer(quad);
                return Err(error);
            }
        };
        let copy = match compile(&gl, COPY) {
            Ok(program) => program,
            Err(error) => {
                gl.delete_program(layer);
                gl.delete_buffer(quad);
                return Err(error);
            }
        };
        gl.bind_buffer(glow::ARRAY_BUFFER, None);
        Ok(Self {
            layer,
            copy,
            gl,
            owner: std::thread::current().id(),
            quad,
            programs: HashMap::new(),
            targets: HashMap::new(),
            scene: None,
            export_frames: Vec::new(),
            held: false,
            delete_gl_on_drop: true,
        })
    }

    fn check_thread(&self) -> Result<(), String> {
        if std::thread::current().id() != self.owner {
            return Err("native preview called outside the EGL render thread".into());
        }
        Ok(())
    }

    unsafe fn register(&mut self, key: &str, fragment: &str) -> Result<(), String> {
        self.check_thread()?;
        if self.programs.contains_key(key) {
            return Ok(());
        }
        self.programs
            .insert(key.to_owned(), compile(&self.gl, fragment)?);
        Ok(())
    }

    unsafe fn target(
        &mut self,
        key: &str,
        width: i32,
        height: i32,
    ) -> Result<(glow::NativeTexture, glow::NativeFramebuffer), String> {
        if self
            .targets
            .get(key)
            .is_some_and(|it| it.width != width || it.height != height)
        {
            let old = self.targets.remove(key).unwrap();
            self.gl.delete_framebuffer(old.framebuffer);
            self.gl.delete_texture(old.texture);
        }
        if !self.targets.contains_key(key) {
            self.targets
                .insert(key.to_owned(), make_target(&self.gl, width, height)?);
        }
        let target = &self.targets[key];
        Ok((target.texture, target.framebuffer))
    }

    unsafe fn render(&mut self, frame: &Frame) -> Result<(), String> {
        self.check_thread()?;
        if frame.width < 1 || frame.height < 1 {
            return Err("invalid preview size".into());
        }
        self.evict_inactive_targets(frame);
        let has_camera = frame.camera.is_some();
        if has_camera
            && self
                .scene
                .as_ref()
                .is_none_or(|it| it.width != frame.width || it.height != frame.height)
        {
            if let Some(old) = self.scene.take() {
                self.gl.delete_framebuffer(old.framebuffer);
                self.gl.delete_texture(old.texture);
            }
            self.scene = Some(make_target(&self.gl, frame.width, frame.height)?);
        }
        let scene_fbo = if has_camera {
            self.scene.as_ref().map(|it| it.framebuffer)
        } else {
            None
        };
        self.gl.bind_framebuffer(glow::FRAMEBUFFER, scene_fbo);
        self.gl.viewport(0, 0, frame.width, frame.height);
        self.gl.disable(glow::SCISSOR_TEST);
        self.gl.disable(glow::DEPTH_TEST);
        self.gl.clear_color(
            frame.background[0],
            frame.background[1],
            frame.background[2],
            frame.background[3],
        );
        self.gl.clear(glow::COLOR_BUFFER_BIT);

        let transitioned = if let Some(transition) = &frame.transition {
            self.draw_transition(frame, transition)?
        } else {
            None
        };
        let fallback = if transitioned.is_none() {
            frame.transition.as_ref().and_then(|transition| {
                frame
                    .layers
                    .iter()
                    .find(|layer| {
                        (layer.key == transition.left || layer.key == transition.right)
                            && layer.texture != 0
                    })
                    .map(|layer| layer.key.as_str())
            })
        } else {
            None
        };
        for layer in &frame.layers {
            if let Some(transition) = &frame.transition {
                if layer.key == transition.left || layer.key == transition.right {
                    if let Some(texture) = transitioned {
                        if layer.key == transition.right {
                            self.draw_copy(
                                texture,
                                scene_fbo,
                                frame.width,
                                frame.height,
                                &IDENTITY,
                                true,
                                false,
                            )?;
                        }
                        continue;
                    }
                    if Some(layer.key.as_str()) != fallback {
                        continue;
                    }
                }
            }
            if let Some(texture) = raw_texture(layer.texture) {
                let treated = self.treat(layer, texture)?;
                self.draw_layer(frame, layer, treated, scene_fbo)?;
            }
        }
        if let Some(texture) = raw_texture(frame.sticker_texture) {
            self.draw_copy(
                texture,
                scene_fbo,
                frame.width,
                frame.height,
                &IDENTITY,
                true,
                true,
            )?;
        }
        if let Some(texture) = raw_texture(frame.text_texture) {
            self.draw_copy(
                texture,
                scene_fbo,
                frame.width,
                frame.height,
                &IDENTITY,
                true,
                true,
            )?;
        }
        if let Some(matrix) = &frame.camera {
            if let Some(scene) = self.scene.as_ref() {
                self.gl.bind_framebuffer(glow::FRAMEBUFFER, None);
                self.gl.viewport(0, 0, frame.width, frame.height);
                self.gl.clear_color(
                    frame.background[0],
                    frame.background[1],
                    frame.background[2],
                    frame.background[3],
                );
                self.gl.clear(glow::COLOR_BUFFER_BIT);
                self.draw_copy(
                    scene.texture,
                    None,
                    frame.width,
                    frame.height,
                    matrix,
                    false,
                    false,
                )?;
            }
        }
        self.gl.flush();
        Ok(())
    }

    unsafe fn draw_transition(
        &mut self,
        frame: &Frame,
        transition: &Transition,
    ) -> Result<Option<glow::NativeTexture>, String> {
        let Some(left) = frame
            .layers
            .iter()
            .find(|it| it.key == transition.left && it.texture != 0)
        else {
            return Ok(None);
        };
        let Some(right) = frame
            .layers
            .iter()
            .find(|it| it.key == transition.right && it.texture != 0)
        else {
            return Ok(None);
        };
        let mut textures = Vec::with_capacity(2);
        for (index, layer) in [left, right].into_iter().enumerate() {
            let source = self.treat(layer, raw_texture(layer.texture).unwrap())?;
            let (texture, fbo) = self.target(
                &format!("transition-input-{index}"),
                frame.width,
                frame.height,
            )?;
            self.gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fbo));
            self.gl.viewport(0, 0, frame.width, frame.height);
            self.gl.clear_color(0., 0., 0., 0.);
            self.gl.clear(glow::COLOR_BUFFER_BIT);
            self.draw_layer(frame, layer, source, Some(fbo))?;
            textures.push(texture);
        }
        if transition.debug_mode == 1 || transition.debug_mode == 2 {
            return Ok(Some(textures[(transition.debug_mode - 1) as usize]));
        }
        let (output, fbo) = self.target("transition-output", frame.width, frame.height)?;
        self.draw_pair(
            &transition.program,
            textures[0],
            textures[1],
            Some(fbo),
            frame.width,
            frame.height,
            transition.progress,
            &transition.params,
        )?;
        Ok(Some(output))
    }

    unsafe fn treat(
        &mut self,
        layer: &Layer,
        mut source: glow::NativeTexture,
    ) -> Result<glow::NativeTexture, String> {
        let mut pass = 0usize;
        for effect in layer.effects.iter().filter(|it| it.intensity > 0.) {
            let Some(program) = self.programs.get(&effect.program).copied() else {
                continue;
            };
            let (output, fbo) = self.target(
                &format!("effect-{}-{}", layer.key, pass % 2),
                layer.width,
                layer.height,
            )?;
            self.gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fbo));
            self.gl.viewport(0, 0, layer.width, layer.height);
            self.gl.disable(glow::BLEND);
            self.use_quad(program);
            uniform_mat(&self.gl, program, "uMvp", &IDENTITY);
            uniform_mat(&self.gl, program, "uTexMatrix", &IDENTITY);
            for (unit, name) in ["uTexSampler", "uHistory0", "uHistory1", "uHistory2"]
                .into_iter()
                .enumerate()
            {
                bind(&self.gl, program, name, source, unit as u32);
            }
            uniform_2(
                &self.gl,
                program,
                "uResolution",
                layer.width as f32,
                layer.height as f32,
            );
            uniform_1(&self.gl, program, "uTime", effect.time);
            uniform_1(&self.gl, program, "uProgress", 0.);
            uniform_1(&self.gl, program, "uIntensity", effect.intensity);
            uniform_1(&self.gl, program, "uBlendMode", effect.blend_mode);
            uniform_4(&self.gl, program, "uEffectMask", &effect.mask);
            uniform_4(
                &self.gl,
                program,
                "uEffectMaskTransform",
                &effect.mask_transform,
            );
            uniform_1(&self.gl, program, "uEffectMaskAspect", effect.mask_aspect);
            for (name, value) in &effect.params {
                uniform_1(&self.gl, program, name, *value);
            }
            self.gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            source = output;
            pass += 1;
        }
        if let Some(mask) = &layer.mask
            && mask.params[3] > 0.
        {
            if let Some(program) = self.programs.get("__layer_mask").copied() {
                let (output, fbo) =
                    self.target(&format!("mask-{}", layer.key), layer.width, layer.height)?;
                self.gl.bind_framebuffer(glow::FRAMEBUFFER, Some(fbo));
                self.gl.viewport(0, 0, layer.width, layer.height);
                self.gl.disable(glow::BLEND);
                self.use_quad(program);
                uniform_mat(&self.gl, program, "uMvp", &IDENTITY);
                uniform_mat(&self.gl, program, "uTexMatrix", &IDENTITY);
                bind(&self.gl, program, "uTexture", source, 0);
                uniform_4(&self.gl, program, "uLayerMaskCenterSize", &mask.center_size);
                uniform_4(&self.gl, program, "uLayerMaskParams", &mask.params);
                uniform_3(&self.gl, program, "uLayerMaskMeta", &mask.meta);
                for (index, point) in mask.path.iter().enumerate() {
                    uniform_2(
                        &self.gl,
                        program,
                        &format!("uLayerMaskPath{index}"),
                        point[0],
                        point[1],
                    );
                }
                self.gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                source = output;
            }
        }
        Ok(source)
    }

    unsafe fn draw_layer(
        &self,
        frame: &Frame,
        layer: &Layer,
        texture: glow::NativeTexture,
        fbo: Option<glow::NativeFramebuffer>,
    ) -> Result<(), String> {
        let gl = &self.gl;
        gl.bind_framebuffer(glow::FRAMEBUFFER, fbo);
        gl.viewport(0, 0, frame.width, frame.height);
        gl.enable(glow::BLEND);
        gl.blend_func_separate(
            glow::SRC_ALPHA,
            glow::ONE_MINUS_SRC_ALPHA,
            glow::ONE,
            glow::ONE_MINUS_SRC_ALPHA,
        );
        let program = self.layer;
        self.use_quad(program);
        let crop_w = (layer.crop[2] - layer.crop[0]).max(0.05);
        let crop_h = (layer.crop[3] - layer.crop[1]).max(0.05);
        let rotated = (layer.fixed_rotation / 90) % 2 != 0;
        let aspect = if rotated {
            layer.source_height as f32 * crop_h / (layer.source_width as f32 * crop_w).max(0.01)
        } else {
            layer.source_width as f32 * crop_w / (layer.source_height as f32 * crop_h).max(0.01)
        };
        let output_aspect = frame.width as f32 / frame.height as f32;
        let (mut sx, mut sy) = (1., 1.);
        if frame.fit {
            if aspect > output_aspect {
                sy = output_aspect / aspect;
            } else {
                sx = aspect / output_aspect;
            }
        } else if aspect > output_aspect {
            sx = aspect / output_aspect;
        } else {
            sy = output_aspect / aspect;
        }
        let angle = (layer.rotation + layer.fixed_rotation as f32).to_radians();
        let (sin, cos) = angle.sin_cos();
        let xs = sx * layer.zoom * if layer.flip { -1. } else { 1. };
        let ys = sy * layer.zoom;
        let matrix = [
            xs * cos,
            xs * sin,
            0.,
            0.,
            -ys * sin,
            ys * cos,
            0.,
            0.,
            0.,
            0.,
            1.,
            0.,
            layer.x * 2.,
            -layer.y * 2.,
            0.,
            1.,
        ];
        uniform_mat(gl, program, "uMvp", &matrix);
        uniform_mat(gl, program, "uTexMatrix", &IDENTITY);
        bind(gl, program, "uTexture", texture, 0);
        uniform_4(gl, program, "uCrop", &layer.crop);
        uniform_4(gl, program, "uAdjust", &layer.adjust);
        uniform_4(gl, program, "uColor", &layer.color);
        uniform_4(gl, program, "uPreset", &layer.preset);
        uniform_4(gl, program, "uGrade", &layer.grade);
        uniform_4(gl, program, "uChroma", &layer.chroma);
        uniform_3(gl, program, "uKeyEdge", &layer.key_edge);
        uniform_1(gl, program, "uOpacity", layer.opacity.clamp(0., 1.));
        let f = layer.edge_fade;
        let shade = crate::transitions::fade(f[0] as i32, f[1] as i32, f[2], f[3], f[4]);
        uniform_2(gl, program, "uFade", shade[0], shade[1]);
        gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        Ok(())
    }

    unsafe fn draw_copy(
        &self,
        texture: glow::NativeTexture,
        fbo: Option<glow::NativeFramebuffer>,
        width: i32,
        height: i32,
        matrix: &[f32; 16],
        blend: bool,
        flip_y: bool,
    ) -> Result<(), String> {
        let gl = &self.gl;
        gl.bind_framebuffer(glow::FRAMEBUFFER, fbo);
        gl.viewport(0, 0, width, height);
        if blend {
            gl.enable(glow::BLEND);
            gl.blend_func(glow::ONE, glow::ONE_MINUS_SRC_ALPHA);
        } else {
            gl.disable(glow::BLEND);
        }
        self.use_quad(self.copy);
        uniform_mat(gl, self.copy, "uMvp", matrix);
        uniform_mat(
            gl,
            self.copy,
            "uTexMatrix",
            if flip_y { &FLIP_Y } else { &IDENTITY },
        );
        bind(gl, self.copy, "uTexture", texture, 0);
        gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        Ok(())
    }

    unsafe fn use_quad(&self, program: glow::NativeProgram) {
        self.gl.use_program(Some(program));
        self.gl.bind_buffer(glow::ARRAY_BUFFER, Some(self.quad));
        if let Some(index) = self.gl.get_attrib_location(program, "aPosition") {
            self.gl.enable_vertex_attrib_array(index);
            self.gl
                .vertex_attrib_pointer_f32(index, 2, glow::FLOAT, false, 16, 0);
        }
        if let Some(index) = self.gl.get_attrib_location(program, "aUv") {
            self.gl.enable_vertex_attrib_array(index);
            self.gl
                .vertex_attrib_pointer_f32(index, 2, glow::FLOAT, false, 16, 8);
        }
    }

    unsafe fn remove_clip(&mut self, key: &str) {
        let effect_prefix = format!("effect-{key}-");
        let mask_key = format!("mask-{key}");
        let stale: Vec<_> = self
            .targets
            .keys()
            .filter(|target| target.starts_with(&effect_prefix) || **target == mask_key)
            .cloned()
            .collect();
        for name in stale {
            if let Some(target) = self.targets.remove(&name) {
                self.gl.delete_framebuffer(target.framebuffer);
                self.gl.delete_texture(target.texture);
            }
        }
    }

    unsafe fn evict_inactive_targets(&mut self, frame: &Frame) {
        let mut live = std::collections::HashSet::new();
        for layer in &frame.layers {
            live.insert(format!("mask-{}", layer.key));
            live.insert(format!("effect-{}-0", layer.key));
            live.insert(format!("effect-{}-1", layer.key));
        }
        let stale: Vec<_> = self
            .targets
            .keys()
            .filter(|key| {
                (key.starts_with("effect-") || key.starts_with("mask-")) && !live.contains(*key)
            })
            .cloned()
            .collect();
        for key in stale {
            if let Some(target) = self.targets.remove(&key) {
                self.gl.delete_framebuffer(target.framebuffer);
                self.gl.delete_texture(target.texture);
            }
        }
    }
}

impl Drop for Renderer {
    fn drop(&mut self) {
        if !self.delete_gl_on_drop {
            return;
        }
        if self.check_thread().is_err() {
            return;
        }
        unsafe {
            for (_, target) in self.targets.drain() {
                self.gl.delete_framebuffer(target.framebuffer);
                self.gl.delete_texture(target.texture);
            }
            if let Some(target) = self.scene.take() {
                self.gl.delete_framebuffer(target.framebuffer);
                self.gl.delete_texture(target.texture);
            }
            for (_, program) in self.programs.drain() {
                self.gl.delete_program(program);
            }
            self.gl.delete_program(self.layer);
            self.gl.delete_program(self.copy);
            for (_, target) in self.export_frames.drain(..) {
                self.gl.delete_framebuffer(target.framebuffer);
                self.gl.delete_texture(target.texture);
            }
            self.gl.delete_buffer(self.quad);
        }
    }
}

unsafe fn compile(gl: &glow::Context, fragment: &str) -> Result<glow::NativeProgram, String> {
    let shaders = [
        (glow::VERTEX_SHADER, VERTEX),
        (glow::FRAGMENT_SHADER, fragment),
    ];
    let program = gl.create_program()?;
    let mut compiled = Vec::new();
    for (kind, source) in shaders {
        let shader = gl.create_shader(kind)?;
        gl.shader_source(shader, source);
        gl.compile_shader(shader);
        if !gl.get_shader_compile_status(shader) {
            let log = gl.get_shader_info_log(shader);
            gl.delete_shader(shader);
            for shader in compiled {
                gl.delete_shader(shader);
            }
            gl.delete_program(program);
            return Err(log);
        }
        gl.attach_shader(program, shader);
        compiled.push(shader);
    }
    gl.bind_attrib_location(program, 0, "aPosition");
    gl.bind_attrib_location(program, 1, "aUv");
    gl.link_program(program);
    for shader in compiled {
        gl.detach_shader(program, shader);
        gl.delete_shader(shader);
    }
    if !gl.get_program_link_status(program) {
        let log = gl.get_program_info_log(program);
        gl.delete_program(program);
        return Err(log);
    }
    Ok(program)
}

unsafe fn make_target(gl: &glow::Context, width: i32, height: i32) -> Result<Target, String> {
    let texture = gl.create_texture()?;
    gl.bind_texture(glow::TEXTURE_2D, Some(texture));
    for (name, value) in [
        (glow::TEXTURE_MIN_FILTER, glow::LINEAR),
        (glow::TEXTURE_MAG_FILTER, glow::LINEAR),
        (glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE),
        (glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE),
    ] {
        gl.tex_parameter_i32(glow::TEXTURE_2D, name, value as i32);
    }
    gl.tex_image_2d(
        glow::TEXTURE_2D,
        0,
        glow::RGBA as i32,
        width,
        height,
        0,
        glow::RGBA,
        glow::UNSIGNED_BYTE,
        glow::PixelUnpackData::Slice(None),
    );
    let framebuffer = gl.create_framebuffer()?;
    gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
    gl.framebuffer_texture_2d(
        glow::FRAMEBUFFER,
        glow::COLOR_ATTACHMENT0,
        glow::TEXTURE_2D,
        Some(texture),
        0,
    );
    if gl.check_framebuffer_status(glow::FRAMEBUFFER) != glow::FRAMEBUFFER_COMPLETE {
        gl.delete_framebuffer(framebuffer);
        gl.delete_texture(texture);
        return Err(format!("preview framebuffer incomplete: {width}x{height}"));
    }
    Ok(Target {
        width,
        height,
        texture,
        framebuffer,
    })
}

fn raw_texture(id: u32) -> Option<glow::NativeTexture> {
    NonZeroU32::new(id).map(glow::NativeTexture)
}

unsafe fn uniform_1(gl: &glow::Context, program: glow::NativeProgram, name: &str, value: f32) {
    gl.uniform_1_f32(gl.get_uniform_location(program, name).as_ref(), value);
}
unsafe fn uniform_2(gl: &glow::Context, program: glow::NativeProgram, name: &str, x: f32, y: f32) {
    gl.uniform_2_f32(gl.get_uniform_location(program, name).as_ref(), x, y);
}
unsafe fn uniform_3(gl: &glow::Context, program: glow::NativeProgram, name: &str, v: &[f32; 3]) {
    gl.uniform_3_f32_slice(gl.get_uniform_location(program, name).as_ref(), v);
}
unsafe fn uniform_4(gl: &glow::Context, program: glow::NativeProgram, name: &str, v: &[f32; 4]) {
    gl.uniform_4_f32_slice(gl.get_uniform_location(program, name).as_ref(), v);
}
unsafe fn uniform_mat(
    gl: &glow::Context,
    program: glow::NativeProgram,
    name: &str,
    matrix: &[f32; 16],
) {
    gl.uniform_matrix_4_f32_slice(
        gl.get_uniform_location(program, name).as_ref(),
        false,
        matrix,
    );
}
unsafe fn bind(
    gl: &glow::Context,
    program: glow::NativeProgram,
    name: &str,
    texture: glow::NativeTexture,
    unit: u32,
) {
    gl.active_texture(glow::TEXTURE0 + unit);
    gl.bind_texture(glow::TEXTURE_2D, Some(texture));
    gl.uniform_1_i32(gl.get_uniform_location(program, name).as_ref(), unit as i32);
}

pub(crate) fn with_renderer<T>(
    handle: jlong,
    f: impl FnOnce(&mut Renderer) -> Result<T, String>,
) -> Result<T, String> {
    if handle == 0 {
        return Err("native preview is closed".into());
    }
    // The Kotlin bridge owns this pointer and calls every entry on its EGL thread.
    let renderer = unsafe { &mut *(handle as *mut Renderer) };
    renderer.check_thread()?;
    f(renderer)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    std::panic::catch_unwind(|| unsafe {
        match Renderer::new() {
            Ok(renderer) => Box::into_raw(Box::new(renderer)) as jlong,
            Err(error) => {
                android_log(&error);
                0
            }
        }
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Renderer));
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeRegisterProgram(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
    fragment: JString,
) -> jboolean {
    let result = (|| {
        let key: String = env.get_string(&key).map_err(|it| it.to_string())?.into();
        let fragment: String = env
            .get_string(&fragment)
            .map_err(|it| it.to_string())?
            .into();
        with_renderer(handle, |renderer| unsafe {
            renderer.register(&key, &fragment)
        })
    })();
    match result {
        Ok(()) => 1,
        Err(error) => {
            android_log(&error);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeRender(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) -> jboolean {
    let result = (|| {
        let json: String = env.get_string(&json).map_err(|it| it.to_string())?.into();
        let frame: Frame = serde_json::from_str(&json).map_err(|it| it.to_string())?;
        with_renderer(handle, |renderer| unsafe { renderer.render(&frame) })
    })();
    match result {
        Ok(()) => 1,
        Err(error) => {
            android_log(&error);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeRemoveClip(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JString,
) -> jint {
    let result = (|| {
        let key: String = env.get_string(&key).map_err(|it| it.to_string())?.into();
        with_renderer(handle, |renderer| {
            unsafe { renderer.remove_clip(&key) };
            Ok(())
        })
    })();
    match result {
        Ok(()) => 1,
        Err(error) => {
            android_log(&error);
            0
        }
    }
}

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: i32, tag: *const c_char, message: *const c_char) -> i32;
}
fn android_log(message: &str) {
    let tag = CStr::from_bytes_with_nul(b"ReclyPreviewRust\0").unwrap();
    if let Ok(message) = CString::new(message) {
        unsafe {
            __android_log_write(6, tag.as_ptr(), message.as_ptr());
        }
    }
}
