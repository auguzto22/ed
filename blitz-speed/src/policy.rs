//! Escolhe como um clipe é renderizado: a partir das escolhas do usuário, do clipe e do aparelho.
//!
//! As regras são conservadoras de propósito: arrastar a agulha sempre repete quadros (latência
//! importa mais que suavidade com o dedo em movimento), um aparelho quente ou sem fôlego desce de
//! optical flow para mistura **na prévia**, e a exportação nunca desce por temperatura porque é
//! offline e o usuário espera qualidade. Toda descida traz um aviso para a interface.

/// Modo escolhido pelo usuário (ordinais do enum `SlowMotionMode` do Kotlin).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlowMotionMode { Auto = 0, Economy = 1, Blend = 2, OpticalFlow = 3 }

/// Fonte do motion blur (ordinais de `MotionBlurSource`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BlurSource { Auto = 0, MotionVectors = 1, LayerAnimation = 2 }

/// O que de fato renderiza o quadro, depois de resolver o automático.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Synthesis { Repeat = 0, Blend = 1, OpticalFlow = 2 }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Blur { None = 0, MotionVectors = 1, LayerAnimation = 2 }

/// O que está sendo renderizado.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Context { Scrub = 0, Playback = 1, Paused = 2, ExportEconomy = 3, ExportHigh = 4 }

impl SlowMotionMode {
    pub fn from_ordinal(value: i32) -> SlowMotionMode {
        match value { 1 => SlowMotionMode::Economy, 2 => SlowMotionMode::Blend, 3 => SlowMotionMode::OpticalFlow, _ => SlowMotionMode::Auto }
    }
}
impl BlurSource {
    pub fn from_ordinal(value: i32) -> BlurSource {
        match value { 1 => BlurSource::MotionVectors, 2 => BlurSource::LayerAnimation, _ => BlurSource::Auto }
    }
}
impl Context {
    pub fn from_ordinal(value: i32) -> Context {
        match value { 0 => Context::Scrub, 2 => Context::Paused, 3 => Context::ExportEconomy, 4 => Context::ExportHigh, _ => Context::Playback }
    }
    pub fn is_export(self) -> bool { matches!(self, Context::ExportEconomy | Context::ExportHigh) }
}

/// O que o aparelho aguenta agora. `thermal_status` segue `PowerManager.THERMAL_STATUS_*`
/// (0 nenhum … 6 desligamento); `preview_tier` é o nível do `PreviewPerformanceController` (0 … 5).
#[derive(Clone, Copy, Debug)]
pub struct Device {
    pub memory_class_mb: i32,
    pub low_ram: bool,
    pub highp_fragment: bool,
    pub max_texture_size: i32,
    pub thermal_status: i32,
    pub preview_tier: i32,
}

#[derive(Clone, Copy, Debug)]
pub struct ClipFacts {
    pub source_fps: f32,
    pub width: i32,
    pub height: i32,
    pub slowest_speed: f32,
    pub fastest_speed: f32,
    pub has_layer_animation: bool,
    pub image: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct BlurConfig {
    pub enabled: bool,
    pub shutter_angle: f32,
    pub intensity: f32,
    pub samples: i32,
    pub source: BlurSource,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Plan {
    pub synthesis: Synthesis,
    pub blur: Blur,
    /// Lado menor da grade de optical flow em pixels; 0 sem flow.
    pub flow_short_side: i32,
    pub flow_levels: i32,
    pub flow_iterations: i32,
    pub blur_samples: i32,
    pub exposure_fraction: f32,
    pub blur_intensity: f32,
    pub notice: Notice,
}

impl Plan {
    pub fn needs_flow(&self) -> bool {
        self.synthesis == Synthesis::OpticalFlow || self.blur == Blur::MotionVectors
    }
}

/// Avisos para a interface quando o plano não é o que o usuário escolheu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Notice { None = 0, FlowUnavailable = 1, FlowHot = 2, FlowStruggling = 3, VectorsUnavailable = 4, NoMotionData = 5 }

impl Notice {
    pub fn text(self) -> &'static str {
        match self {
            Notice::None => "",
            Notice::FlowUnavailable => "Optical flow indisponível neste aparelho: usando mistura de quadros.",
            Notice::FlowHot => "Aparelho aquecido: prévia com mistura de quadros (a exportação usa optical flow).",
            Notice::FlowStruggling => "Prévia simplificada para manter a fluidez (a exportação usa optical flow).",
            Notice::VectorsUnavailable => "Blur por vetores indisponível neste aparelho: usando a animação da camada.",
            Notice::NoMotionData => "Sem dados de movimento para o blur: anime a camada com keyframes ou use optical flow.",
        }
    }
}

const THERMAL_MODERATE: i32 = 2;

/// Fontes mais esparsas que a saída: aí a síntese ajuda.
/// Só câmera lenta de verdade ganha quadros novos: um clipe a 1× ou mais rápido mantém a cadência
/// original mesmo numa saída de fps maior (30 fps numa exportação a 60 fps não vira "efeito novela").
pub fn needs_synthesis(facts: &ClipFacts, output_fps: f32) -> bool {
    !facts.image && facts.slowest_speed < 0.999 && facts.slowest_speed * facts.source_fps < output_fps * 0.95
}

pub fn plan(facts: &ClipFacts, mode: SlowMotionMode, blur: &BlurConfig, device: &Device, context: Context, output_fps: f32) -> Plan {
    let export = context.is_export();
    let flow_capable = device.highp_fragment && device.max_texture_size >= 2048
        && (export || (!device.low_ram && device.memory_class_mb >= 192));
    let hot = !export && device.thermal_status >= THERMAL_MODERATE;
    let struggling = !export && device.preview_tier >= 4;
    let mut notice = Notice::None;

    let synthesis = if facts.image || !needs_synthesis(facts, output_fps) || context == Context::Scrub || mode == SlowMotionMode::Economy {
        Synthesis::Repeat
    } else if mode == SlowMotionMode::Blend {
        Synthesis::Blend
    } else if mode == SlowMotionMode::OpticalFlow {
        if !flow_capable {
            notice = Notice::FlowUnavailable;
            Synthesis::Blend
        } else if hot {
            notice = Notice::FlowHot;
            Synthesis::Blend
        } else if struggling {
            notice = Notice::FlowStruggling;
            Synthesis::Blend
        } else {
            Synthesis::OpticalFlow
        }
    } else if context == Context::ExportEconomy || !flow_capable || hot || struggling {
        Synthesis::Blend
    } else {
        Synthesis::OpticalFlow
    };

    let wants_blur = blur.enabled && blur.intensity > 0.0 && !facts.image;
    let layer_or_none = |notice: &mut Notice| {
        if facts.has_layer_animation { Blur::LayerAnimation } else {
            if *notice == Notice::None { *notice = Notice::NoMotionData; }
            Blur::None
        }
    };
    let blur_method = if !wants_blur || context == Context::Scrub {
        Blur::None
    } else if blur.source == BlurSource::LayerAnimation {
        layer_or_none(&mut notice)
    } else if !flow_capable || (hot && blur.source == BlurSource::Auto) {
        if blur.source == BlurSource::MotionVectors && !flow_capable && notice == Notice::None {
            notice = Notice::VectorsUnavailable;
        }
        layer_or_none(&mut notice)
    } else {
        Blur::MotionVectors
    };

    let uses_flow = synthesis == Synthesis::OpticalFlow || blur_method == Blur::MotionVectors;
    let source_short = facts.width.min(facts.height).max(16);
    let flow_short_side = if !uses_flow { 0 } else {
        match context {
            Context::ExportHigh => (source_short / 2).min(540),
            Context::ExportEconomy => (source_short / 3).min(270),
            _ => if device.preview_tier <= 1 && !hot { (source_short / 3).min(216) } else { (source_short / 4).min(160) },
        }.max(64)
    };
    let (levels, iterations) = if !uses_flow { (0, 0) } else {
        match context { Context::ExportHigh => (5, 5), Context::ExportEconomy => (4, 3), _ => (4, 3) }
    };
    let samples = if blur_method == Blur::None { 0 } else if export {
        if context == Context::ExportEconomy { blur.samples.min(8) } else { blur.samples }
    } else {
        blur.samples.min(if device.preview_tier <= 1 { 12 } else { 6 })
    };
    Plan {
        synthesis,
        blur: blur_method,
        flow_short_side,
        flow_levels: levels,
        flow_iterations: iterations,
        blur_samples: samples,
        exposure_fraction: if blur_method == Blur::None { 0.0 } else { blur.shutter_angle.clamp(1.0, 360.0) / 360.0 },
        blur_intensity: blur.intensity,
        notice,
    }
}

/// Tamanho da grade de flow, mantendo a proporção, em dimensões pares.
pub fn flow_size(width: i32, height: i32, short_side: i32) -> (i32, i32) {
    let scale = short_side as f32 / width.min(height).max(1) as f32;
    let even = |value: f32| (((value / 2.0).round() as i32) * 2).max(8);
    if scale >= 1.0 { (even(width as f32), even(height as f32)) } else { (even(width as f32 * scale), even(height as f32 * scale)) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts(slowest: f32) -> ClipFacts {
        ClipFacts { source_fps: 30.0, width: 1920, height: 1080, slowest_speed: slowest, fastest_speed: slowest, has_layer_animation: false, image: false }
    }
    fn device() -> Device {
        Device { memory_class_mb: 512, low_ram: false, highp_fragment: true, max_texture_size: 8192, thermal_status: 0, preview_tier: 0 }
    }
    fn blur(enabled: bool, source: BlurSource) -> BlurConfig {
        BlurConfig { enabled, shutter_angle: 180.0, intensity: 1.0, samples: 12, source }
    }

    #[test]
    fn scrubbing_always_repeats_frames() {
        let plan = plan(&facts(0.25), SlowMotionMode::OpticalFlow, &blur(true, BlurSource::Auto), &device(), Context::Scrub, 30.0);
        assert_eq!((plan.synthesis, plan.blur), (Synthesis::Repeat, Blur::None));
    }

    #[test]
    fn real_time_clips_need_no_synthesis() {
        let plan = plan(&facts(1.0), SlowMotionMode::OpticalFlow, &blur(false, BlurSource::Auto), &device(), Context::ExportHigh, 30.0);
        assert_eq!(plan.synthesis, Synthesis::Repeat);
        assert!(!plan.needs_flow());
        // 60 fps a 0,5× numa saída de 30 fps já tem quadros suficientes.
        let sixty = ClipFacts { source_fps: 60.0, ..facts(0.5) };
        assert!(!needs_synthesis(&sixty, 30.0));
        assert!(needs_synthesis(&sixty, 60.0));
        // 30 fps a 1× numa exportação a 60 fps: nada de interpolar um clipe que não foi desacelerado.
        assert!(!needs_synthesis(&facts(1.0), 60.0));
        assert!(!needs_synthesis(&ClipFacts { source_fps: 24.0, ..facts(1.0) }, 30.0));
        assert!(needs_synthesis(&ClipFacts { source_fps: 24.0, ..facts(0.8) }, 30.0));
    }

    #[test]
    fn weak_hot_or_struggling_devices_step_down_with_a_notice() {
        let weak = Device { low_ram: true, memory_class_mb: 128, ..device() };
        let p = plan(&facts(0.25), SlowMotionMode::OpticalFlow, &blur(false, BlurSource::Auto), &weak, Context::Playback, 30.0);
        assert_eq!((p.synthesis, p.notice), (Synthesis::Blend, Notice::FlowUnavailable));
        let hot = Device { thermal_status: 3, ..device() };
        let p = plan(&facts(0.25), SlowMotionMode::OpticalFlow, &blur(false, BlurSource::Auto), &hot, Context::Playback, 30.0);
        assert_eq!((p.synthesis, p.notice), (Synthesis::Blend, Notice::FlowHot));
        // A exportação ignora temperatura e nível da prévia.
        let p = plan(&facts(0.25), SlowMotionMode::OpticalFlow, &blur(false, BlurSource::Auto), &hot, Context::ExportHigh, 30.0);
        assert_eq!(p.synthesis, Synthesis::OpticalFlow);
        let no_highp = Device { highp_fragment: false, ..device() };
        let p = plan(&facts(0.25), SlowMotionMode::Auto, &blur(false, BlurSource::Auto), &no_highp, Context::ExportHigh, 30.0);
        assert_eq!(p.synthesis, Synthesis::Blend);
    }

    #[test]
    fn user_choices_are_respected() {
        let p = plan(&facts(0.25), SlowMotionMode::Economy, &blur(false, BlurSource::Auto), &device(), Context::ExportHigh, 30.0);
        assert_eq!(p.synthesis, Synthesis::Repeat);
        let p = plan(&facts(0.25), SlowMotionMode::Blend, &blur(false, BlurSource::Auto), &device(), Context::ExportHigh, 30.0);
        assert_eq!(p.synthesis, Synthesis::Blend);
        let p = plan(&facts(0.25), SlowMotionMode::Auto, &blur(false, BlurSource::Auto), &device(), Context::ExportEconomy, 30.0);
        assert_eq!(p.synthesis, Synthesis::Blend);
    }

    #[test]
    fn blur_falls_back_to_layer_animation_and_says_so() {
        let weak = Device { highp_fragment: false, ..device() };
        let animated = ClipFacts { has_layer_animation: true, ..facts(1.0) };
        let p = plan(&animated, SlowMotionMode::Auto, &blur(true, BlurSource::MotionVectors), &weak, Context::ExportHigh, 30.0);
        assert_eq!((p.blur, p.notice), (Blur::LayerAnimation, Notice::VectorsUnavailable));
        let p = plan(&facts(1.0), SlowMotionMode::Auto, &blur(true, BlurSource::Auto), &weak, Context::ExportHigh, 30.0);
        assert_eq!((p.blur, p.notice), (Blur::None, Notice::NoMotionData));
        let p = plan(&facts(1.0), SlowMotionMode::Auto, &blur(true, BlurSource::Auto), &device(), Context::Playback, 30.0);
        assert_eq!(p.blur, Blur::MotionVectors);
        assert!((p.exposure_fraction - 0.5).abs() < 1e-6);
        assert!(p.flow_short_side >= 64);
    }

    #[test]
    fn flow_grid_keeps_aspect_and_is_even() {
        assert_eq!(flow_size(1920, 1080, 216), (384, 216));
        assert_eq!(flow_size(1080, 1920, 160), (160, 284));
        assert_eq!(flow_size(100, 50, 216), (100, 50));
    }
}
