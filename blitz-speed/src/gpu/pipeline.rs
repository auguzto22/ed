//! Pipeline de movimento na GPU: optical flow, quadros intermediários, motion blur e acumulação.
//!
//! Roda no contexto GL de quem chama (o renderizador da prévia ou o efeito do Media3), sobre
//! texturas 2D que ele já tem. Tudo acontece em texturas RGBA8 (o único formato renderizável
//! garantido no GLES 2); o flow é empacotado em 16 bits por componente.
//!
//! Etapas de [`MotionPipeline::analyze`] para um par de quadros (A, B):
//! 1. luminância de A e B na resolução do flow e pirâmide de `levels` níveis;
//! 2. Lucas–Kanade do nível mais grosso ao mais fino (`iterations` passos por nível), nos dois
//!    sentidos (A→B e B→A), com suavização bilateral no fim;
//! 3. confiança por pixel (consistência ida e volta + erro fotométrico);
//! 4. máximo de velocidade da vizinhança (para o blur alcançar o fundo);
//! 5. estatísticas pequenas lidas na CPU: confiança média e correlação da luminância, usadas
//!    para detectar corte de cena.
//!
//! O par é guardado em cache pela chave do chamador: na câmera lenta muitos quadros de saída
//! saem do mesmo par e o flow é calculado uma vez só.

use super::gl::{self, GLint, GLuint, Gl};
use std::ffi::{c_char, c_void, CString};

const VERTEX: &str = include_str!("shaders/vertex.glsl");
const COMMON: &str = include_str!("shaders/common.glsl");

static QUAD: [f32; 8] = [-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, 1.0];

/// Como produzir o quadro (equivale ao `policy::Synthesis`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Synthesis {
    Repeat,
    Blend,
    OpticalFlow,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BlurParams {
    /// Exposição ÷ intervalo entre os quadros do par, já com a intensidade.
    pub scale: f32,
    pub samples: i32,
}

/// Estatísticas de um par.
#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct PairStats {
    /// Confiança média do flow (0..1); 0 quando o flow não foi calculado.
    pub mean_confidence: f32,
    /// Correlação da luminância entre A e B no nível mais grosso (−1..1).
    pub correlation: f32,
    /// Corte de cena: nenhuma síntese entre esses dois quadros.
    pub cut: bool,
    pub has_flow: bool,
}

struct Program {
    id: GLuint,
    position: GLuint,
}

struct Programs {
    luma: Program,
    down: Program,
    lk: Program,
    smooth: Program,
    confidence: Program,
    neighbor: Program,
    interpolate: Program,
    blend: Program,
    copy: Program,
    blur: Program,
    reduce: Program,
    fill: Program,
}

#[derive(Clone, Copy)]
pub struct Target {
    pub texture: GLuint,
    pub fbo: GLuint,
    pub width: i32,
    pub height: i32,
}

pub struct MotionPipeline {
    gl: &'static Gl,
    programs: Programs,
    pub out_width: i32,
    pub out_height: i32,
    pub flow_width: i32,
    pub flow_height: i32,
    iterations: i32,
    luma_a: Vec<Target>,
    luma_b: Vec<Target>,
    /// Flow por nível, dois por sentido (vai e vem entre iterações).
    forward: Vec<[Target; 2]>,
    backward: Vec<[Target; 2]>,
    forward_final: Target,
    backward_final: Target,
    /// Flows com as oclusões preenchidas (usados para interpolar e para o blur).
    forward_filled: Target,
    backward_filled: Target,
    confidence: Target,
    neighbor_tmp: Target,
    /// Máximo de velocidade da vizinhança, para o flow de ida (A→B) e de volta (B→A).
    neighbor: Target,
    neighbor_back: Target,
    stats_target: Target,
    synth: Target,
    accumulator: Target,
    accumulated: u32,
    cached: Option<(i64, i64, bool)>,
    stats: PairStats,
    released: bool,
}

/// Salva e restaura o estado GL que os passes alteram, para não interferir em quem chamou.
struct StateGuard {
    gl: &'static Gl,
    framebuffer: GLint,
    viewport: [GLint; 4],
    program: GLint,
    blend: bool,
    scissor: bool,
    depth: bool,
    active: GLint,
    textures: [GLint; 6],
    array_buffer: GLint,
}

impl StateGuard {
    fn capture(gl: &'static Gl) -> StateGuard {
        unsafe {
            let mut guard = StateGuard {
                gl, framebuffer: 0, viewport: [0; 4], program: 0, blend: false, scissor: false, depth: false,
                active: 0, textures: [0; 6], array_buffer: 0,
            };
            (gl.GetIntegerv)(gl::FRAMEBUFFER_BINDING, &mut guard.framebuffer);
            (gl.GetIntegerv)(gl::VIEWPORT, guard.viewport.as_mut_ptr());
            (gl.GetIntegerv)(gl::CURRENT_PROGRAM, &mut guard.program);
            (gl.GetIntegerv)(gl::ACTIVE_TEXTURE, &mut guard.active);
            (gl.GetIntegerv)(gl::ARRAY_BUFFER_BINDING, &mut guard.array_buffer);
            guard.blend = (gl.IsEnabled)(gl::BLEND) != 0;
            guard.scissor = (gl.IsEnabled)(gl::SCISSOR_TEST) != 0;
            guard.depth = (gl.IsEnabled)(gl::DEPTH_TEST) != 0;
            for unit in 0..6 {
                (gl.ActiveTexture)(gl::TEXTURE0 + unit as u32);
                (gl.GetIntegerv)(gl::TEXTURE_BINDING_2D, &mut guard.textures[unit]);
            }
            (gl.Disable)(gl::BLEND);
            (gl.Disable)(gl::SCISSOR_TEST);
            (gl.Disable)(gl::DEPTH_TEST);
            (gl.BindBuffer)(gl::ARRAY_BUFFER, 0);
            guard
        }
    }
}

impl Drop for StateGuard {
    fn drop(&mut self) {
        let gl = self.gl;
        unsafe {
            for unit in 0..6 {
                (gl.ActiveTexture)(gl::TEXTURE0 + unit as u32);
                (gl.BindTexture)(gl::TEXTURE_2D, self.textures[unit] as GLuint);
            }
            (gl.ActiveTexture)(self.active as u32);
            (gl.BindFramebuffer)(gl::FRAMEBUFFER, self.framebuffer as GLuint);
            (gl.Viewport)(self.viewport[0], self.viewport[1], self.viewport[2], self.viewport[3]);
            (gl.UseProgram)(self.program as GLuint);
            (gl.BindBuffer)(gl::ARRAY_BUFFER, self.array_buffer as GLuint);
            if self.blend { (gl.Enable)(gl::BLEND) } else { (gl.Disable)(gl::BLEND) }
            if self.scissor { (gl.Enable)(gl::SCISSOR_TEST) }
            if self.depth { (gl.Enable)(gl::DEPTH_TEST) }
        }
    }
}

fn compile(gl: &Gl, kind: u32, source: &str) -> Result<GLuint, String> {
    unsafe {
        let shader = (gl.CreateShader)(kind);
        let text = CString::new(source).map_err(|_| "shader com NUL".to_string())?;
        let pointer = text.as_ptr();
        (gl.ShaderSource)(shader, 1, &pointer, std::ptr::null());
        (gl.CompileShader)(shader);
        let mut ok: GLint = 0;
        (gl.GetShaderiv)(shader, gl::COMPILE_STATUS, &mut ok);
        if ok == 0 {
            let log = gl::info_log(gl, shader, false);
            (gl.DeleteShader)(shader);
            return Err(log);
        }
        Ok(shader)
    }
}

fn program(gl: &Gl, name: &str, fragment: &str) -> Result<Program, String> {
    let vertex = compile(gl, gl::VERTEX_SHADER, VERTEX).map_err(|e| format!("vertex: {e}"))?;
    let source = format!("{COMMON}\n{fragment}");
    let fragment = compile(gl, gl::FRAGMENT_SHADER, &source).map_err(|e| format!("{name}: {e}"))?;
    unsafe {
        let id = (gl.CreateProgram)();
        (gl.AttachShader)(id, vertex);
        (gl.AttachShader)(id, fragment);
        (gl.LinkProgram)(id);
        (gl.DeleteShader)(vertex);
        (gl.DeleteShader)(fragment);
        let mut ok: GLint = 0;
        (gl.GetProgramiv)(id, gl::LINK_STATUS, &mut ok);
        if ok == 0 {
            let log = gl::info_log(gl, id, true);
            (gl.DeleteProgram)(id);
            return Err(format!("{name}: {log}"));
        }
        let location = (gl.GetAttribLocation)(id, c"aPosition".as_ptr());
        if location < 0 {
            (gl.DeleteProgram)(id);
            return Err(format!("{name}: sem aPosition"));
        }
        Ok(Program { id, position: location as GLuint })
    }
}

fn level_sizes(width: i32, height: i32, levels: i32) -> Vec<(i32, i32)> {
    let mut sizes = vec![(width, height)];
    while (sizes.len() as i32) < levels {
        let (w, h) = *sizes.last().unwrap();
        if w <= 16 || h <= 16 {
            break;
        }
        sizes.push(((w + 1) / 2, (h + 1) / 2));
    }
    sizes
}

impl MotionPipeline {
    /// Cria o pipeline no contexto GL atual da thread.
    pub fn new(out_width: i32, out_height: i32, flow_width: i32, flow_height: i32, levels: i32, iterations: i32) -> Result<MotionPipeline, String> {
        if out_width < 2 || out_height < 2 || flow_width < 8 || flow_height < 8 || flow_width > out_width.max(8) * 2 || levels < 1 || iterations < 1 {
            return Err("tamanhos inválidos para o pipeline de movimento".to_string());
        }
        let gl = gl::gl()?;
        let programs = Programs {
            luma: program(gl, "luma", include_str!("shaders/luma.glsl"))?,
            down: program(gl, "down", include_str!("shaders/down.glsl"))?,
            lk: program(gl, "lk", include_str!("shaders/lk.glsl"))?,
            smooth: program(gl, "smooth", include_str!("shaders/smooth.glsl"))?,
            confidence: program(gl, "confidence", include_str!("shaders/confidence.glsl"))?,
            neighbor: program(gl, "neighbor", include_str!("shaders/neighbor_max.glsl"))?,
            interpolate: program(gl, "interpolate", include_str!("shaders/interpolate.glsl"))?,
            blend: program(gl, "blend", include_str!("shaders/blend.glsl"))?,
            copy: program(gl, "copy", include_str!("shaders/copy.glsl"))?,
            blur: program(gl, "blur", include_str!("shaders/blur.glsl"))?,
            reduce: program(gl, "reduce", include_str!("shaders/reduce.glsl"))?,
            fill: program(gl, "fill", include_str!("shaders/fill.glsl"))?,
        };
        let sizes = level_sizes(flow_width, flow_height, levels);
        let mut pipeline = MotionPipeline {
            gl, programs, out_width, out_height, flow_width, flow_height, iterations,
            luma_a: Vec::new(), luma_b: Vec::new(), forward: Vec::new(), backward: Vec::new(),
            forward_final: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            backward_final: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            forward_filled: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            backward_filled: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            confidence: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            neighbor_tmp: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            neighbor: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            neighbor_back: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            stats_target: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            synth: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            accumulator: Target { texture: 0, fbo: 0, width: 0, height: 0 },
            accumulated: 0, cached: None, stats: PairStats::default(), released: false,
        };
        if let Err(error) = pipeline.allocate(&sizes) {
            pipeline.release();
            return Err(error);
        }
        Ok(pipeline)
    }

    fn allocate(&mut self, sizes: &[(i32, i32)]) -> Result<(), String> {
        let (flow_width, flow_height) = (self.flow_width, self.flow_height);
        for &(w, h) in sizes {
            let a = self.target(w, h, gl::LINEAR)?;
            self.luma_a.push(a);
            let b = self.target(w, h, gl::LINEAR)?;
            self.luma_b.push(b);
            let f = [self.target(w, h, gl::NEAREST)?, self.target(w, h, gl::NEAREST)?];
            self.forward.push(f);
            let r = [self.target(w, h, gl::NEAREST)?, self.target(w, h, gl::NEAREST)?];
            self.backward.push(r);
        }
        self.forward_final = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.backward_final = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.forward_filled = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.backward_filled = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.confidence = self.target(flow_width, flow_height, gl::LINEAR)?;
        self.neighbor_tmp = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.neighbor = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.neighbor_back = self.target(flow_width, flow_height, gl::NEAREST)?;
        self.stats_target = self.target(16, 16, gl::NEAREST)?;
        // `synth` (só com blur) e `accumulator` (só na exportação) são criados no primeiro uso:
        // na prévia são duas texturas do tamanho do vídeo a menos por camada.
        Ok(())
    }

    fn ensure_synth(&mut self) -> bool {
        if self.synth.texture == 0 {
            match self.target(self.out_width, self.out_height, gl::LINEAR) {
                Ok(target) => self.synth = target,
                Err(_) => return false,
            }
        }
        true
    }

    fn ensure_accumulator(&mut self) -> bool {
        if self.accumulator.texture == 0 {
            match self.target(self.out_width, self.out_height, gl::LINEAR) {
                Ok(target) => self.accumulator = target,
                Err(_) => return false,
            }
        }
        true
    }

    fn target(&self, width: i32, height: i32, filter: GLint) -> Result<Target, String> {
        let gl = self.gl;
        unsafe {
            let mut texture: GLuint = 0;
            (gl.GenTextures)(1, &mut texture);
            (gl.BindTexture)(gl::TEXTURE_2D, texture);
            (gl.TexParameteri)(gl::TEXTURE_2D, gl::TEXTURE_MIN_FILTER, filter);
            (gl.TexParameteri)(gl::TEXTURE_2D, gl::TEXTURE_MAG_FILTER, filter);
            (gl.TexParameteri)(gl::TEXTURE_2D, gl::TEXTURE_WRAP_S, gl::CLAMP_TO_EDGE);
            (gl.TexParameteri)(gl::TEXTURE_2D, gl::TEXTURE_WRAP_T, gl::CLAMP_TO_EDGE);
            (gl.TexImage2D)(gl::TEXTURE_2D, 0, gl::RGBA as GLint, width, height, 0, gl::RGBA, gl::UNSIGNED_BYTE, std::ptr::null());
            let mut fbo: GLuint = 0;
            (gl.GenFramebuffers)(1, &mut fbo);
            (gl.BindFramebuffer)(gl::FRAMEBUFFER, fbo);
            (gl.FramebufferTexture2D)(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, texture, 0);
            let status = (gl.CheckFramebufferStatus)(gl::FRAMEBUFFER);
            (gl.BindFramebuffer)(gl::FRAMEBUFFER, 0);
            if status != gl::FRAMEBUFFER_COMPLETE {
                (gl.DeleteFramebuffers)(1, &fbo);
                (gl.DeleteTextures)(1, &texture);
                return Err(format!("framebuffer incompleto 0x{status:x}"));
            }
            Ok(Target { texture, fbo, width, height })
        }
    }

    fn all_targets(&self) -> Vec<Target> {
        let mut all: Vec<Target> = Vec::new();
        all.extend(self.luma_a.iter().copied());
        all.extend(self.luma_b.iter().copied());
        for pair in self.forward.iter().chain(self.backward.iter()) {
            all.extend(pair.iter().copied());
        }
        all.extend([self.forward_final, self.backward_final, self.forward_filled, self.backward_filled, self.confidence, self.neighbor_tmp, self.neighbor, self.neighbor_back,
            self.stats_target, self.synth, self.accumulator]);
        all.into_iter().filter(|t| t.texture != 0).collect()
    }

    /// Libera todos os objetos GL. Precisa do mesmo contexto atual.
    pub fn release(&mut self) {
        if self.released {
            return;
        }
        self.released = true;
        let gl = self.gl;
        unsafe {
            for target in self.all_targets() {
                (gl.DeleteFramebuffers)(1, &target.fbo);
                (gl.DeleteTextures)(1, &target.texture);
            }
            let p = &self.programs;
            for id in [p.luma.id, p.down.id, p.lk.id, p.smooth.id, p.confidence.id, p.neighbor.id, p.interpolate.id,
                p.blend.id, p.copy.id, p.blur.id, p.reduce.id, p.fill.id] {
                (gl.DeleteProgram)(id);
            }
        }
        self.luma_a.clear();
        self.luma_b.clear();
        self.forward.clear();
        self.backward.clear();
    }

    // ---- desenho -------------------------------------------------------------------------

    fn uniform(&self, program: &Program, name: &str) -> GLint {
        let name = CString::new(name).unwrap();
        unsafe { (self.gl.GetUniformLocation)(program.id, name.as_ptr() as *const c_char) }
    }

    fn set1f(&self, program: &Program, name: &str, value: f32) {
        let location = self.uniform(program, name);
        if location >= 0 { unsafe { (self.gl.Uniform1f)(location, value) } }
    }

    fn set2f(&self, program: &Program, name: &str, a: f32, b: f32) {
        let location = self.uniform(program, name);
        if location >= 0 { unsafe { (self.gl.Uniform2f)(location, a, b) } }
    }

    fn texture(&self, program: &Program, name: &str, unit: u32, texture: GLuint) {
        let location = self.uniform(program, name);
        unsafe {
            (self.gl.ActiveTexture)(gl::TEXTURE0 + unit);
            (self.gl.BindTexture)(gl::TEXTURE_2D, texture);
            if location >= 0 { (self.gl.Uniform1i)(location, unit as GLint) }
        }
    }

    fn begin(&self, program: &Program, fbo: GLuint, viewport: (i32, i32, i32, i32)) {
        let gl = self.gl;
        unsafe {
            (gl.BindFramebuffer)(gl::FRAMEBUFFER, fbo);
            (gl.Viewport)(viewport.0, viewport.1, viewport.2, viewport.3);
            (gl.UseProgram)(program.id);
            (gl.EnableVertexAttribArray)(program.position);
            (gl.VertexAttribPointer)(program.position, 2, gl::FLOAT, 0, 0, QUAD.as_ptr() as *const c_void);
        }
    }

    fn draw(&self, program: &Program) {
        unsafe {
            (self.gl.DrawArrays)(gl::TRIANGLE_STRIP, 0, 4);
            (self.gl.DisableVertexAttribArray)(program.position);
        }
    }

    fn full(target: &Target) -> (i32, i32, i32, i32) {
        (0, 0, target.width, target.height)
    }

    // ---- análise do par --------------------------------------------------------------------

    fn build_pyramid(&self, source: GLuint, levels: &[Target]) {
        let p = &self.programs.luma;
        let first = levels[0];
        self.begin(p, first.fbo, Self::full(&first));
        self.texture(p, "uSource", 0, source);
        self.set2f(p, "uFootprint", 1.0 / first.width as f32, 1.0 / first.height as f32);
        self.draw(p);
        let d = &self.programs.down;
        for pair in levels.windows(2) {
            let (src, dst) = (pair[0], pair[1]);
            self.begin(d, dst.fbo, Self::full(&dst));
            self.texture(d, "uSource", 0, src.texture);
            self.set2f(d, "uSourceTexel", 1.0 / src.width as f32, 1.0 / src.height as f32);
            self.draw(d);
        }
    }

    /// Flow de `a` para `b`; devolve o alvo com o resultado do nível mais fino.
    fn estimate(&self, a: &[Target], b: &[Target], flows: &[[Target; 2]]) -> Target {
        let p = &self.programs.lk;
        let mut previous: Option<Target> = None; // resultado do nível mais grosso
        for level in (0..a.len()).rev() {
            let (la, lb) = (a[level], b[level]);
            let mut current = 0usize;
            for iteration in 0..self.iterations {
                let dst = flows[level][current];
                let src = flows[level][1 - current];
                self.begin(p, dst.fbo, Self::full(&dst));
                self.texture(p, "uA", 0, la.texture);
                self.texture(p, "uB", 1, lb.texture);
                self.texture(p, "uFlow", 2, src.texture);
                let coarse = previous.unwrap_or(src);
                self.texture(p, "uCoarse", 3, coarse.texture);
                let init = if iteration > 0 { 1.0 } else if previous.is_some() { 2.0 } else { 0.0 };
                self.set1f(p, "uInit", init);
                self.set2f(p, "uSize", la.width as f32, la.height as f32);
                self.set2f(p, "uCoarseSize", coarse.width as f32, coarse.height as f32);
                self.draw(p);
                current = 1 - current;
            }
            previous = Some(flows[level][1 - current]);
        }
        previous.expect("pelo menos um nível")
    }

    fn smooth(&self, flow: Target, luma: Target, dst: Target) {
        let p = &self.programs.smooth;
        self.begin(p, dst.fbo, Self::full(&dst));
        self.texture(p, "uFlow", 0, flow.texture);
        self.texture(p, "uLuma", 1, luma.texture);
        self.set2f(p, "uSize", dst.width as f32, dst.height as f32);
        self.draw(p);
    }

    /// Máximo da vizinhança, separável: horizontal e depois vertical.
    fn neighbor_max(&self, flow: Target, dst: Target) {
        let n = &self.programs.neighbor;
        self.begin(n, self.neighbor_tmp.fbo, Self::full(&self.neighbor_tmp));
        self.texture(n, "uFlow", 0, flow.texture);
        self.set2f(n, "uStep", 1.0 / self.flow_width as f32, 0.0);
        self.draw(n);
        self.begin(n, dst.fbo, Self::full(&dst));
        self.texture(n, "uFlow", 0, self.neighbor_tmp.texture);
        self.set2f(n, "uStep", 0.0, 1.0 / self.flow_height as f32);
        self.draw(n);
    }

    fn fill(&self, flow: Target, luma: Target, channel: f32, dst: Target) {
        let p = &self.programs.fill;
        self.begin(p, dst.fbo, Self::full(&dst));
        self.texture(p, "uFlow", 0, flow.texture);
        self.texture(p, "uConfidence", 1, self.confidence.texture);
        self.texture(p, "uLuma", 2, luma.texture);
        self.set1f(p, "uChannel", channel);
        self.set2f(p, "uSize", dst.width as f32, dst.height as f32);
        self.draw(p);
    }

    fn read_rgba(&self, target: Target) -> Vec<u8> {
        let gl = self.gl;
        let mut pixels = vec![0u8; (target.width * target.height * 4) as usize];
        unsafe {
            (gl.BindFramebuffer)(gl::FRAMEBUFFER, target.fbo);
            (gl.PixelStorei)(gl::PACK_ALIGNMENT, 1);
            (gl.ReadPixels)(0, 0, target.width, target.height, gl::RGBA, gl::UNSIGNED_BYTE, pixels.as_mut_ptr() as *mut c_void);
        }
        pixels
    }

    /// Reduz `source` para 16×16 e lê na CPU.
    fn reduce(&self, source: Target) -> Vec<u8> {
        let p = &self.programs.reduce;
        let dst = self.stats_target;
        self.begin(p, dst.fbo, Self::full(&dst));
        self.texture(p, "uSource", 0, source.texture);
        self.set2f(p, "uFootprint", 1.0 / dst.width as f32, 1.0 / dst.height as f32);
        self.draw(p);
        self.read_rgba(dst)
    }

    /// Prepara o par (A, B). `key` identifica o par para o cache (por exemplo os carimbos de
    /// tempo dos dois quadros). Com `flow` falso só mede a correlação (para a mistura).
    pub fn analyze(&mut self, a: GLuint, b: GLuint, key: (i64, i64), flow: bool) -> PairStats {
        if self.released {
            return PairStats::default();
        }
        if let Some((ka, kb, had_flow)) = self.cached {
            if (ka, kb) == key && (had_flow || !flow) {
                return self.stats;
            }
        }
        let _guard = StateGuard::capture(self.gl);
        let luma_a = self.luma_a.clone();
        let luma_b = self.luma_b.clone();
        self.build_pyramid(a, &luma_a);
        self.build_pyramid(b, &luma_b);
        let correlation = self.correlation(*luma_a.last().unwrap(), *luma_b.last().unwrap());
        let mut stats = PairStats { mean_confidence: 0.0, correlation, cut: false, has_flow: false };
        if flow {
            let forward = self.estimate(&luma_a, &luma_b, &self.forward);
            let backward = self.estimate(&luma_b, &luma_a, &self.backward);
            self.smooth(forward, luma_a[0], self.forward_final);
            self.smooth(backward, luma_b[0], self.backward_final);
            let p = &self.programs.confidence;
            let dst = self.confidence;
            self.begin(p, dst.fbo, Self::full(&dst));
            self.texture(p, "uForward", 0, self.forward_final.texture);
            self.texture(p, "uBackward", 1, self.backward_final.texture);
            self.texture(p, "uLumaA", 2, luma_a[0].texture);
            self.texture(p, "uLumaB", 3, luma_b[0].texture);
            self.set2f(p, "uSize", dst.width as f32, dst.height as f32);
            self.draw(p);
            self.fill(self.forward_final, luma_a[0], 0.0, self.forward_filled);
            self.fill(self.backward_final, luma_b[0], 1.0, self.backward_filled);
            self.neighbor_max(self.forward_filled, self.neighbor);
            self.neighbor_max(self.backward_filled, self.neighbor_back);
            let pixels = self.reduce(self.confidence);
            let count = (pixels.len() / 4) as f32;
            let sum: f32 = pixels.chunks_exact(4).map(|c| (c[0] as f32 + c[1] as f32) / (2.0 * 255.0)).sum();
            stats.mean_confidence = sum / count;
            stats.has_flow = true;
        }
        // Corte: a luminância dos dois quadros não tem relação e (havendo flow) nada se alinha.
        stats.cut = correlation < 0.35 && (!flow || stats.mean_confidence < 0.2);
        self.stats = stats;
        self.cached = Some((key.0, key.1, flow));
        stats
    }

    fn correlation(&self, a: Target, b: Target) -> f32 {
        let pa = self.read_rgba(a);
        let pb = self.read_rgba(b);
        let n = (pa.len() / 4) as f64;
        let (mut sa, mut sb, mut saa, mut sbb, mut sab) = (0f64, 0f64, 0f64, 0f64, 0f64);
        for (x, y) in pa.chunks_exact(4).zip(pb.chunks_exact(4)) {
            let (u, v) = (x[0] as f64, y[0] as f64);
            sa += u; sb += v; saa += u * u; sbb += v * v; sab += u * v;
        }
        let cov = sab / n - (sa / n) * (sb / n);
        let va = saa / n - (sa / n).powi(2);
        let vb = sbb / n - (sb / n).powi(2);
        if va < 1.0 && vb < 1.0 {
            // Dois quadros lisos: correlação indefinida; compara o brilho médio.
            return if ((sa - sb) / n).abs() < 12.0 { 1.0 } else { 0.0 };
        }
        if va < 1e-6 || vb < 1e-6 {
            return 0.0;
        }
        (cov / (va.sqrt() * vb.sqrt())) as f32
    }

    pub fn stats(&self) -> PairStats {
        self.stats
    }

    // ---- quadros de saída ------------------------------------------------------------------

    /// Desenha o quadro de fase `t` entre `a` e `b` em `dst_fbo` (viewport dada), com blur
    /// opcional. Para `OpticalFlow` e para o blur o par já deve ter passado por [`analyze`]
    /// com flow; senão a síntese cai para mistura e o blur é omitido.
    #[allow(clippy::too_many_arguments)]
    pub fn render(&mut self, a: GLuint, b: GLuint, t: f32, synthesis: Synthesis, ghost_control: f32, blur: Option<BlurParams>,
        dst_fbo: GLuint, viewport: (i32, i32, i32, i32)) {
        if self.released {
            return;
        }
        let _guard = StateGuard::capture(self.gl);
        let t = t.clamp(0.0, 1.0);
        let stats = self.stats;
        let wants_blur = blur.is_some_and(|params| stats.has_flow && !stats.cut && params.samples > 0 && params.scale > 0.0);
        let blur = if wants_blur && self.ensure_synth() { blur } else { None };
        let synthesis = match synthesis {
            _ if stats.cut => Synthesis::Repeat,
            Synthesis::OpticalFlow if !stats.has_flow => Synthesis::Blend,
            other => other,
        };
        let (target_fbo, target_viewport) = if blur.is_some() { (self.synth.fbo, Self::full(&self.synth)) } else { (dst_fbo, viewport) };
        match synthesis {
            Synthesis::Repeat => {
                let p = &self.programs.copy;
                self.begin(p, target_fbo, target_viewport);
                self.texture(p, "uSource", 0, if t < 0.5 { a } else { b });
                self.draw(p);
            }
            Synthesis::Blend => {
                let p = &self.programs.blend;
                self.begin(p, target_fbo, target_viewport);
                self.texture(p, "uFrameA", 0, a);
                self.texture(p, "uFrameB", 1, b);
                self.set1f(p, "uT", t);
                self.set1f(p, "uGhostControl", ghost_control.clamp(0.0, 1.0));
                self.draw(p);
            }
            Synthesis::OpticalFlow => {
                let p = &self.programs.interpolate;
                self.begin(p, target_fbo, target_viewport);
                self.texture(p, "uFrameA", 0, a);
                self.texture(p, "uFrameB", 1, b);
                self.texture(p, "uForward", 2, self.forward_filled.texture);
                self.texture(p, "uBackward", 3, self.backward_filled.texture);
                self.texture(p, "uConfidence", 4, self.confidence.texture);
                self.set2f(p, "uFlowSize", self.flow_width as f32, self.flow_height as f32);
                self.set1f(p, "uT", t);
                self.draw(p);
            }
        }
        if let Some(params) = blur {
            // A velocidade do instante vem do quadro mais próximo: o flow de ida descreve os
            // pixels de A, o de volta os de B. O rastro é simétrico, então o sinal não importa.
            let (flow, neighbor) = if t < 0.5 { (self.forward_filled, self.neighbor) } else { (self.backward_filled, self.neighbor_back) };
            let p = &self.programs.blur;
            self.begin(p, dst_fbo, viewport);
            self.texture(p, "uColor", 0, self.synth.texture);
            self.texture(p, "uFlow", 1, flow.texture);
            self.texture(p, "uNeighborMax", 2, neighbor.texture);
            self.set2f(p, "uFlowSize", self.flow_width as f32, self.flow_height as f32);
            self.set2f(p, "uOutSize", self.out_width as f32, self.out_height as f32);
            self.set1f(p, "uScale", params.scale);
            self.set1f(p, "uSamples", params.samples.clamp(1, 32) as f32);
            self.draw(p);
        }
    }

    /// Integração temporal real (aceleração com blur): soma `source` à média corrente.
    pub fn accumulate(&mut self, source: GLuint) {
        if self.released {
            return;
        }
        let _guard = StateGuard::capture(self.gl);
        if !self.ensure_accumulator() {
            return;
        }
        let gl = self.gl;
        let p = &self.programs.copy;
        self.begin(p, self.accumulator.fbo, Self::full(&self.accumulator));
        self.texture(p, "uSource", 0, source);
        unsafe {
            if self.accumulated > 0 {
                // média corrente: acc ← acc·(1 − 1/k) + novo·(1/k)
                let weight = 1.0 / (self.accumulated + 1) as f32;
                (gl.Enable)(gl::BLEND);
                (gl.BlendColor)(0.0, 0.0, 0.0, weight);
                (gl.BlendFunc)(gl::CONSTANT_ALPHA, gl::ONE_MINUS_CONSTANT_ALPHA);
            }
        }
        self.draw(p);
        self.accumulated += 1;
    }

    /// Copia a média acumulada para o destino e zera o acumulador.
    pub fn emit_accumulated(&mut self, dst_fbo: GLuint, viewport: (i32, i32, i32, i32)) {
        if self.released || self.accumulator.texture == 0 {
            return;
        }
        let _guard = StateGuard::capture(self.gl);
        let p = &self.programs.copy;
        self.begin(p, dst_fbo, viewport);
        self.texture(p, "uSource", 0, self.accumulator.texture);
        self.draw(p);
        self.accumulated = 0;
    }

    /// Copia uma textura (repetição simples) para o destino.
    pub fn copy(&mut self, source: GLuint, dst_fbo: GLuint, viewport: (i32, i32, i32, i32)) {
        if self.released {
            return;
        }
        let _guard = StateGuard::capture(self.gl);
        let p = &self.programs.copy;
        self.begin(p, dst_fbo, viewport);
        self.texture(p, "uSource", 0, source);
        self.draw(p);
    }

    /// Esquece o par em cache (depois de uma busca, ou quando as texturas mudam de conteúdo).
    pub fn invalidate(&mut self) {
        self.cached = None;
        self.stats = PairStats::default();
        self.accumulated = 0;
    }

    /// Para testes e diagnóstico: o flow A→B do nível mais fino, em pixels do flow.
    pub fn read_forward_flow(&self) -> Vec<(f32, f32)> {
        let _guard = StateGuard::capture(self.gl);
        let pixels = self.read_rgba(self.forward_final);
        pixels.chunks_exact(4).map(|c| {
            let x = (c[0] as f32 * 256.0 + c[1] as f32) / 65535.0;
            let y = (c[2] as f32 * 256.0 + c[3] as f32) / 65535.0;
            ((x - 0.5) * 256.0, (y - 0.5) * 256.0)
        }).collect()
    }

    pub fn read_confidence(&self) -> Vec<(f32, f32)> {
        let _guard = StateGuard::capture(self.gl);
        let pixels = self.read_rgba(self.confidence);
        pixels.chunks_exact(4).map(|c| (c[0] as f32 / 255.0, c[1] as f32 / 255.0)).collect()
    }
}

