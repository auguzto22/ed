//! GPU execution shared by the monitor and Media3 export adapters.
use super::*;

impl Renderer {
    /// The same A/B pass is used after preview treatments and after Media3 treatments.
    pub(super) unsafe fn draw_pair(
        &mut self,
        id: &str,
        a: glow::NativeTexture,
        b: glow::NativeTexture,
        fbo: Option<glow::NativeFramebuffer>,
        width: i32,
        height: i32,
        progress: f32,
        parameters: &HashMap<String, f32>,
    ) -> Result<(), String> {
        let package = crate::transitions::package(id)?;
        let key = format!("transition/{id}");
        if !self.programs.contains_key(&key) {
            self.programs
                .insert(key.clone(), compile(&self.gl, &package.fragment)?);
        }
        let program = self.programs[&key];
        self.gl.bind_framebuffer(glow::FRAMEBUFFER, fbo);
        self.gl.viewport(0, 0, width, height);
        self.gl.disable(glow::BLEND);
        self.gl.disable(glow::SCISSOR_TEST);
        self.gl.disable(glow::DEPTH_TEST);
        self.use_quad(program);
        uniform_mat(&self.gl, program, "uMvp", &IDENTITY);
        uniform_mat(&self.gl, program, "uTexMatrix", &IDENTITY);
        bind(&self.gl, program, "uTexA", a, 0);
        bind(&self.gl, program, "uTexB", b, 1);
        uniform_2(
            &self.gl,
            program,
            "uResolution",
            width as f32,
            height as f32,
        );
        uniform_1(&self.gl, program, "uProgress", progress.clamp(0., 1.));
        let values = crate::transitions::values(
            &package.definition,
            &package.supplemental,
            &parameters.iter().map(|(k, v)| (k.clone(), *v)).collect(),
        );
        for (name, value) in values {
            uniform_1(&self.gl, program, &name, value);
        }
        self.gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        Ok(())
    }

    /// Media3 owns the framebuffer and GL state around a native pass.
    pub(crate) unsafe fn export_pass<T>(
        &mut self,
        draw: impl FnOnce(&mut Self, Option<glow::NativeFramebuffer>) -> Result<T, String>,
    ) -> Result<T, String> {
        self.check_thread()?;
        let fbo = NonZeroU32::new(self.gl.get_parameter_i32(glow::FRAMEBUFFER_BINDING) as u32)
            .map(glow::NativeFramebuffer);
        let buffer = NonZeroU32::new(self.gl.get_parameter_i32(glow::ARRAY_BUFFER_BINDING) as u32)
            .map(glow::NativeBuffer);
        let mut viewport = [0; 4];
        self.gl
            .get_parameter_i32_slice(glow::VIEWPORT, &mut viewport);
        let blend = self.gl.is_enabled(glow::BLEND);
        let scissor = self.gl.is_enabled(glow::SCISSOR_TEST);
        let depth = self.gl.is_enabled(glow::DEPTH_TEST);
        self.gl.disable(glow::SCISSOR_TEST);
        self.gl.disable(glow::DEPTH_TEST);
        let result = draw(self, fbo);
        let error = self.gl.get_error();
        self.gl.bind_framebuffer(glow::FRAMEBUFFER, fbo);
        self.gl
            .viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        self.gl.bind_buffer(glow::ARRAY_BUFFER, buffer);
        if blend {
            self.gl.enable(glow::BLEND)
        } else {
            self.gl.disable(glow::BLEND)
        }
        for (capability, enabled) in [(glow::SCISSOR_TEST, scissor), (glow::DEPTH_TEST, depth)] {
            if enabled {
                self.gl.enable(capability);
            } else {
                self.gl.disable(capability);
            }
        }
        if error != glow::NO_ERROR {
            return Err(format!("Native transition GL error 0x{error:x}"));
        }
        result
    }

    pub(crate) unsafe fn export_copy(
        &mut self,
        input: u32,
        width: i32,
        height: i32,
        hold: bool,
        fbo: Option<glow::NativeFramebuffer>,
    ) -> Result<(), String> {
        let mut texture = raw_texture(input).ok_or("Missing input texture")?;
        if hold {
            if self
                .targets
                .get("hold")
                .is_some_and(|t| t.width != width || t.height != height)
            {
                self.held = false;
            }
            let (cached, target) = self.target("hold", width, height)?;
            if !self.held {
                self.draw_copy(
                    texture,
                    Some(target),
                    width,
                    height,
                    &IDENTITY,
                    false,
                    false,
                )?;
                self.held = true;
            }
            texture = cached;
        }
        self.draw_copy(texture, fbo, width, height, &IDENTITY, false, false)
    }
    pub(crate) unsafe fn export_capture(
        &mut self,
        input: u32,
        time: i64,
        width: i32,
        height: i32,
        fbo: Option<glow::NativeFramebuffer>,
    ) -> Result<(), String> {
        if self
            .export_frames
            .iter()
            .any(|(_, t)| t.width != width || t.height != height)
        {
            for (_, t) in self.export_frames.drain(..) {
                self.gl.delete_framebuffer(t.framebuffer);
                self.gl.delete_texture(t.texture);
            }
        }
        let frame_bytes = (width.max(1) as usize)
            .saturating_mul(height.max(1) as usize)
            .saturating_mul(4);
        let capacity = (64 * 1024 * 1024 / frame_bytes).clamp(1, 8);
        let index = if let Some(i) = self.export_frames.iter().position(|(t, _)| *t == time) {
            i
        } else if self.export_frames.len() < capacity {
            self.export_frames
                .push((time, make_target(&self.gl, width, height)?));
            self.export_frames.len() - 1
        } else {
            self.export_frames
                .iter()
                .enumerate()
                .min_by_key(|(_, entry)| entry.0)
                .map(|(i, _)| i)
                .unwrap()
        };
        self.export_frames[index].0 = time;
        self.draw_copy(
            raw_texture(input).ok_or("Missing input texture")?,
            Some(self.export_frames[index].1.framebuffer),
            width,
            height,
            &IDENTITY,
            false,
            false,
        )?;
        self.export_copy(input, width, height, false, fbo)
    }
    pub(crate) unsafe fn export_transition(
        &mut self,
        input: u32,
        time: i64,
        width: i32,
        height: i32,
        id: &str,
        progress: f32,
        parameters: &HashMap<String, f32>,
        fbo: Option<glow::NativeFramebuffer>,
    ) -> Result<bool, String> {
        let source = self
            .export_frames
            .iter()
            .filter(|(t, _)| t.abs_diff(time) <= 100_000)
            .min_by_key(|(t, _)| t.abs_diff(time))
            .map(|(_, t)| t.texture);
        if let Some(a) = source {
            self.draw_pair(
                id,
                a,
                raw_texture(input).ok_or("Missing incoming texture")?,
                fbo,
                width,
                height,
                progress,
                parameters,
            )?;
            Ok(true)
        } else {
            self.export_copy(input, width, height, false, fbo)?;
            Ok(false)
        }
    }
    pub(crate) unsafe fn export_fade(
        &mut self,
        input: u32,
        width: i32,
        height: i32,
        shade: [f32; 2],
        fbo: Option<glow::NativeFramebuffer>,
    ) -> Result<(), String> {
        let key = "transition/edge-fade";
        if !self.programs.contains_key(key) {
            self.programs.insert(key.into(),compile(&self.gl,"precision highp float; uniform sampler2D uTexture; uniform vec2 uFade; varying vec2 vUv; void main(){vec4 c=texture2D(uTexture,vUv);gl_FragColor=vec4(c.rgb*uFade.x+uFade.y,c.a);}")?);
        }
        let p = self.programs[key];
        self.gl.bind_framebuffer(glow::FRAMEBUFFER, fbo);
        self.gl.viewport(0, 0, width, height);
        self.gl.disable(glow::BLEND);
        self.use_quad(p);
        uniform_mat(&self.gl, p, "uMvp", &IDENTITY);
        uniform_mat(&self.gl, p, "uTexMatrix", &IDENTITY);
        uniform_2(&self.gl, p, "uFade", shade[0], shade[1]);
        bind(
            &self.gl,
            p,
            "uTexture",
            raw_texture(input).ok_or("Missing input texture")?,
            0,
        );
        self.gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
        Ok(())
    }
}
