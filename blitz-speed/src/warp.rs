//! Reajuste do tempo do projeto quando a velocidade de um clipe muda.
//!
//! Textos, legendas (palavra por palavra), stickers, marcadores, camadas de ajuste, áudios
//! avulsos, clipes de sobreposição e a câmera 3D vivem no tempo do **projeto**. Quando um clipe
//! muda de duração, o que vem depois anda (ripple) e o que estava em cima dele é remapeado pelo
//! tempo da mídia — uma legenda continua na palavra de onde foi transcrita.
//!
//! `map(t)`: identidade antes do clipe; `antigo → mídia → novo` dentro dele; deslocamento
//! constante depois (só na faixa principal). É monótona, então a ordem dos itens se preserva.

use crate::timemap::TimeMap;

#[derive(Clone, Copy)]
pub struct TimeWarp<'a> {
    pub before: TimeMap<'a>,
    pub after: TimeMap<'a>,
    pub old_start_us: i64,
    pub new_start_us: i64,
    /// Itens depois do clipe acompanham o novo fim (faixa principal) ou ficam (sobreposição).
    pub ripple: bool,
}

impl<'a> TimeWarp<'a> {
    pub fn old_end_us(&self) -> i64 {
        self.old_start_us + self.before.duration_us()
    }

    pub fn new_end_us(&self) -> i64 {
        self.new_start_us + self.after.duration_us()
    }

    pub fn map(&self, time_us: i64) -> i64 {
        let old_end = self.old_end_us();
        let mapped = if time_us <= self.old_start_us {
            time_us
        } else if time_us >= old_end {
            if self.ripple { time_us + (self.new_end_us() - old_end) } else { time_us }
        } else {
            let source = self.before.source_at(time_us - self.old_start_us);
            self.new_start_us + self.after.timeline_at(source)
        };
        mapped.max(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::curve::SpeedPoint;
    use crate::timemap::{build, ClipParams};

    #[test]
    fn content_inside_the_clip_keeps_its_source_moment() {
        let before = build(&ClipParams { source_us: 10_000_000, in_us: 0, out_us: 10_000_000, speed: 1.0, reverse: false }, &[]).unwrap();
        let after = build(&ClipParams { source_us: 10_000_000, in_us: 0, out_us: 10_000_000, speed: 1.0, reverse: false },
            &[SpeedPoint::linear(0, 0.5), SpeedPoint::linear(10_000_000, 2.0)]).unwrap();
        let warp = TimeWarp { before: TimeMap::new(&before).unwrap(), after: TimeMap::new(&after).unwrap(), old_start_us: 3_000_000, new_start_us: 3_000_000, ripple: true };
        assert_eq!(warp.map(1_000_000), 1_000_000);
        for t in (3_000_000..13_000_000).step_by(250_000) {
            let source = warp.before.source_at(t - 3_000_000);
            let mapped = warp.map(t);
            assert!((warp.after.source_at(mapped - 3_000_000) - source).abs() <= 2, "t={t}");
        }
        let shift = warp.new_end_us() - warp.old_end_us();
        assert_eq!(warp.map(20_000_000), 20_000_000 + shift);
        let mut previous = 0;
        for t in (0..25_000_000).step_by(10_000) {
            let m = warp.map(t);
            assert!(m >= previous);
            previous = m;
        }
    }

    #[test]
    fn overlay_changes_do_not_ripple() {
        let before = build(&ClipParams { source_us: 4_000_000, in_us: 0, out_us: 4_000_000, speed: 1.0, reverse: false }, &[]).unwrap();
        let after = build(&ClipParams { source_us: 4_000_000, in_us: 0, out_us: 4_000_000, speed: 2.0, reverse: false }, &[]).unwrap();
        let warp = TimeWarp { before: TimeMap::new(&before).unwrap(), after: TimeMap::new(&after).unwrap(), old_start_us: 1_000_000, new_start_us: 1_000_000, ripple: false };
        assert_eq!(warp.map(9_000_000), 9_000_000);
        assert_eq!(warp.map(3_000_000), 2_000_000);
    }
}
