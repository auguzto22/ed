//! Curvas prontas.
//!
//! Um preset é uma forma sobre a janela **cortada** do clipe: cada nó é uma fração dessa janela,
//! uma velocidade e o easing que sai dele. [`build`] coloca os nós no tempo da mídia, então um
//! preset aplicado a um clipe cortado cobre exatamente o que se vê, e o resultado é uma curva
//! comum, editável (o usuário personaliza depois no editor de curva).
//!
//! `intensity` escala a forma em log-velocidade em torno de 1×: 1 é o preset como desenhado, 0 é
//! 1× constante, acima de 1 exagera. Em log, 0,5× e 2× ficam simétricos.

use crate::curve::{Easing, SpeedPoint, MAX_SPEED, MIN_SPEED};

pub struct Knot {
    pub fraction: f32,
    pub speed: f32,
    pub easing: Easing,
}

const fn k(fraction: f32, speed: f32, easing: Easing) -> Knot {
    Knot { fraction, speed, easing }
}

pub struct Preset {
    pub id: &'static str,
    pub label: &'static str,
    pub description: &'static str,
    pub knots: &'static [Knot],
}

use Easing::{EaseIn as IN, EaseInOut as INOUT, EaseOut as OUT, Linear as LIN, Smooth as SMOOTH};

pub static PRESETS: [Preset; 10] = [
    Preset {
        id: "smooth_slow",
        label: "Lento suave",
        description: "Desacelera com suavidade até 0,5× e volta ao normal.",
        knots: &[k(0.0, 1.0, SMOOTH), k(0.25, 0.5, LIN), k(0.75, 0.5, SMOOTH), k(1.0, 1.0, LIN)],
    },
    Preset {
        id: "cinematic_slow",
        label: "Câmera lenta cinematográfica",
        description: "Mergulha em 0,25× no meio do clipe, com entrada e saída longas.",
        knots: &[k(0.0, 1.0, INOUT), k(0.3, 0.25, LIN), k(0.7, 0.25, INOUT), k(1.0, 1.0, LIN)],
    },
    Preset {
        id: "velocity",
        label: "Velocity",
        description: "Rápido, freada forte no meio e rápido de novo — o clássico de edit.",
        knots: &[k(0.0, 1.0, IN), k(0.2, 3.0, SMOOTH), k(0.45, 0.3, LIN), k(0.6, 0.3, SMOOTH), k(0.82, 3.0, OUT), k(1.0, 1.0, LIN)],
    },
    Preset {
        id: "action",
        label: "Ação",
        description: "Acelera até o impacto, quase congela e retoma.",
        knots: &[k(0.0, 1.5, IN), k(0.38, 4.0, OUT), k(0.5, 0.2, LIN), k(0.62, 0.2, SMOOTH), k(1.0, 1.5, LIN)],
    },
    Preset {
        id: "montage",
        label: "Montagem",
        description: "Ritmo acelerado com uma respiração lenta no centro.",
        knots: &[k(0.0, 2.0, LIN), k(0.4, 2.0, SMOOTH), k(0.5, 0.6, SMOOTH), k(0.6, 2.0, LIN), k(1.0, 2.0, LIN)],
    },
    Preset {
        id: "fast_forward",
        label: "Avanço rápido",
        description: "Sai do normal para 6× e desacelera no fim.",
        knots: &[k(0.0, 1.0, IN), k(0.15, 6.0, LIN), k(0.85, 6.0, OUT), k(1.0, 1.0, LIN)],
    },
    Preset {
        id: "ramp_up",
        label: "Acelerar",
        description: "Vai de 0,5× a 4× ao longo do clipe.",
        knots: &[k(0.0, 0.5, IN), k(1.0, 4.0, LIN)],
    },
    Preset {
        id: "ramp_down",
        label: "Desacelerar",
        description: "Vai de 4× a 0,5× ao longo do clipe.",
        knots: &[k(0.0, 4.0, OUT), k(1.0, 0.5, LIN)],
    },
    Preset {
        id: "bullet",
        label: "Impacto",
        description: "Normal, rajada rápida e câmera lenta no impacto.",
        knots: &[k(0.0, 1.0, SMOOTH), k(0.25, 4.0, OUT), k(0.5, 0.25, LIN), k(0.75, 0.5, SMOOTH), k(1.0, 1.0, LIN)],
    },
    Preset {
        id: "wave",
        label: "Onda",
        description: "Alterna entre lento e rápido.",
        knots: &[k(0.0, 1.0, SMOOTH), k(0.25, 0.4, SMOOTH), k(0.5, 2.0, SMOOTH), k(0.75, 0.4, SMOOTH), k(1.0, 1.0, LIN)],
    },
];

pub fn scaled(speed: f32, intensity: f32) -> f32 {
    ((speed as f64).ln() * intensity as f64).exp().clamp(MIN_SPEED as f64, MAX_SPEED as f64) as f32
}

/// Coloca o preset `index` na janela `[in_us, out_us]` da mídia.
pub fn build(index: usize, in_us: i64, out_us: i64, intensity: f32) -> Option<Vec<SpeedPoint>> {
    let preset = PRESETS.get(index)?;
    if out_us <= in_us || !intensity.is_finite() || !(0.0..=2.0).contains(&intensity) {
        return None;
    }
    let span = (out_us - in_us) as f64;
    let mut points: Vec<SpeedPoint> = Vec::with_capacity(preset.knots.len());
    for knot in preset.knots {
        let at = (in_us + (span * knot.fraction as f64).round() as i64).clamp(in_us, out_us);
        // Janelas muito curtas podem juntar dois nós no mesmo µs: fica o primeiro.
        if points.last().is_some_and(|p| p.source_us >= at) {
            continue;
        }
        points.push(SpeedPoint::eased(at, scaled(knot.speed, intensity), knot.easing));
    }
    Some(points)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::curve;
    use crate::timemap::{self, ClipParams, TimeMap};

    #[test]
    fn every_preset_builds_a_valid_curve_inside_the_window() {
        for (index, preset) in PRESETS.iter().enumerate() {
            assert!(preset.knots.len() >= 2 && preset.knots[0].fraction == 0.0 && preset.knots.last().unwrap().fraction == 1.0);
            assert!(preset.knots.windows(2).all(|w| w[0].fraction < w[1].fraction), "{}", preset.id);
            let points = build(index, 1_000_000, 7_000_000, 1.0).unwrap();
            assert!(curve::validate(&points, 8_000_000).is_ok(), "{}", preset.id);
            assert_eq!(points.first().unwrap().source_us, 1_000_000);
            assert_eq!(points.last().unwrap().source_us, 7_000_000);
            let table = timemap::build(&ClipParams { source_us: 8_000_000, in_us: 1_000_000, out_us: 7_000_000, speed: 1.0, reverse: false }, &points).unwrap();
            assert!(TimeMap::new(&table).unwrap().duration_us() > 0);
        }
    }

    #[test]
    fn zero_intensity_is_plain_real_time() {
        let points = build(1, 0, 4_000_000, 0.0).unwrap();
        assert!(points.iter().all(|p| p.speed == 1.0));
        let table = timemap::build(&ClipParams { source_us: 4_000_000, in_us: 0, out_us: 4_000_000, speed: 1.0, reverse: false }, &points).unwrap();
        assert!((TimeMap::new(&table).unwrap().duration_us() - 4_000_000).abs() <= 2);
    }

    #[test]
    fn intensity_is_symmetric_in_log_space() {
        assert!((scaled(0.5, 1.0) * scaled(2.0, 1.0) - 1.0).abs() < 1e-6);
        assert!((scaled(0.25, 0.5) - 0.5).abs() < 1e-6);
        assert_eq!(scaled(0.1, 2.0), MIN_SPEED);
    }

    #[test]
    fn slow_presets_lengthen_and_fast_presets_shorten() {
        let length = |index: usize| {
            let points = build(index, 0, 6_000_000, 1.0).unwrap();
            let table = timemap::build(&ClipParams { source_us: 6_000_000, in_us: 0, out_us: 6_000_000, speed: 1.0, reverse: false }, &points).unwrap();
            TimeMap::new(&table).unwrap().duration_us()
        };
        assert!(length(0) > 6_000_000 && length(1) > length(0));
        assert!(length(5) < 6_000_000 / 2);
    }

    #[test]
    fn rejects_bad_requests() {
        assert!(build(99, 0, 10, 1.0).is_none());
        assert!(build(0, 10, 10, 1.0).is_none());
        assert!(build(0, 0, 10, 3.0).is_none());
    }
}
