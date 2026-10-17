//! Decide quais quadros um clipe com velocidade alterada emite, na cadência de saída.
//!
//! Os quadros de entrada chegam em ordem, com carimbos de tempo da **timeline de saída** (a
//! velocidade já aplicada: 30 fps a 0,25× chegam a cada 133 ms; a 4×, a cada 8,3 ms). Os quadros
//! de saída ficam na grade global `round(k · 10⁶ / fps)`, para que todo clipe de toda faixa caia
//! nos mesmos instantes do encoder.
//!
//! Para cada instante `T` o obturador fica aberto em `[T − h, T + h)`, `h = exposição / 2`
//! (exposição = fração do obturador × intervalo do quadro; 0 sem motion blur). Então:
//!
//! - dois ou mais quadros de entrada dentro da janela → [`Action::EmitAccumulated`]: integração
//!   temporal real desses quadros (aceleração com blur);
//! - senão → [`Action::EmitSynthesized`] a partir do par que envolve `T`, com sua fase (câmera
//!   lenta, ou sem blur). O renderizador transforma isso em repetição, mistura ou optical flow e
//!   soma o blur por vetores escalado pela exposição.
//!
//! São três posições de histórico: quando uma janela fecha, o par que envolve o instante pode
//! ser os dois quadros anteriores ao que a fechou.

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Action {
    /// Copiar o quadro que chegou para a posição `slot` do histórico.
    Store { slot: usize, pts_us: i64 },
    /// Somar a posição `slot` ao acumulador do quadro de saída `output_us`.
    Accumulate { slot: usize, output_us: i64 },
    /// Emitir o acumulador (média de `frames` entradas) como o quadro `output_us`.
    EmitAccumulated { output_us: i64, frames: u32 },
    /// Emitir o quadro `output_us` entre `from_slot` (em `from_us`) e `to_slot` (em `to_us`) na
    /// fase `phase` (0..1). `from_slot == to_slot` é um quadro mantido.
    EmitSynthesized { output_us: i64, from_slot: usize, to_slot: usize, from_us: i64, to_us: i64, phase: f32, exposure_us: i64 },
}

pub const SLOTS: usize = 3;

#[derive(Clone, Copy, Debug)]
struct Frame {
    slot: usize,
    pts_us: i64,
}

pub struct Scheduler {
    interval_us: f64,
    pub exposure_us: i64,
    half_exposure_us: i64,
    history: Vec<Frame>,
    next_index: Option<i64>,
    accumulated: u32,
    accumulating_for: Option<i64>,
    last_input_us: Option<i64>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ScheduleError {
    InvalidSettings,
    NonIncreasingTimestamp,
}

impl Scheduler {
    pub fn new(output_fps: f32, exposure_fraction: f32) -> Result<Scheduler, ScheduleError> {
        if !output_fps.is_finite() || !(1.0..=1000.0).contains(&output_fps) || !exposure_fraction.is_finite() || !(0.0..=1.0).contains(&exposure_fraction) {
            return Err(ScheduleError::InvalidSettings);
        }
        let interval_us = 1_000_000.0 / output_fps as f64;
        let exposure_us = (interval_us * exposure_fraction as f64).round() as i64;
        Ok(Scheduler {
            interval_us,
            exposure_us,
            half_exposure_us: exposure_us / 2,
            history: Vec::with_capacity(SLOTS),
            next_index: None,
            accumulated: 0,
            accumulating_for: None,
            last_input_us: None,
        })
    }

    pub fn grid_time(&self, index: i64) -> i64 {
        (index as f64 * self.interval_us).round() as i64
    }

    fn first_index_at_or_after(&self, time_us: i64) -> i64 {
        let mut index = (time_us as f64 / self.interval_us).ceil() as i64;
        while self.grid_time(index) < time_us {
            index += 1;
        }
        while self.grid_time(index - 1) >= time_us {
            index -= 1;
        }
        index
    }

    /// Ações para um quadro que chega. Os carimbos devem crescer estritamente.
    pub fn on_input(&mut self, pts_us: i64, actions: &mut Vec<Action>) -> Result<(), ScheduleError> {
        if self.last_input_us.is_some_and(|last| pts_us <= last) {
            return Err(ScheduleError::NonIncreasingTimestamp);
        }
        self.last_input_us = Some(pts_us);
        if self.next_index.is_none() {
            self.next_index = Some(self.first_index_at_or_after(pts_us));
        }
        // Guardado primeiro: o quadro que fecha uma janela costuma ser o lado direito do par que
        // envolve o instante dela. O quadro descartado nunca é necessário.
        let slot = self.free_slot();
        if self.history.len() == SLOTS {
            self.history.remove(0);
        }
        self.history.push(Frame { slot, pts_us });
        actions.push(Action::Store { slot, pts_us });
        loop {
            let output_us = self.grid_time(self.next_index.unwrap());
            let closes = if self.exposure_us > 0 { pts_us >= output_us + self.half_exposure_us } else { pts_us >= output_us };
            if !closes {
                break;
            }
            self.emit(output_us, actions);
            self.next_index = self.next_index.map(|i| i + 1);
        }
        // Aceleração: quadros dentro do obturador aberto do instante pendente são integrados.
        let pending = self.grid_time(self.next_index.unwrap());
        if self.exposure_us > 0 && pts_us >= pending - self.half_exposure_us && pts_us < pending + self.half_exposure_us {
            if self.accumulating_for != Some(pending) {
                self.accumulating_for = Some(pending);
                self.accumulated = 0;
            }
            self.accumulated += 1;
            actions.push(Action::Accumulate { slot, output_us: pending });
        }
        Ok(())
    }

    /// Emite os instantes restantes até o último quadro, mantendo o último.
    pub fn on_end_of_stream(&mut self, actions: &mut Vec<Action>) {
        let (Some(last), Some(_)) = (self.last_input_us, self.next_index) else { return };
        while self.grid_time(self.next_index.unwrap()) <= last {
            let output_us = self.grid_time(self.next_index.unwrap());
            self.emit(output_us, actions);
            self.next_index = self.next_index.map(|i| i + 1);
        }
    }

    /// Esquece tudo, como depois de uma busca ou de um novo fluxo.
    pub fn reset(&mut self) {
        self.history.clear();
        self.next_index = None;
        self.accumulated = 0;
        self.accumulating_for = None;
        self.last_input_us = None;
    }

    fn emit(&mut self, output_us: i64, actions: &mut Vec<Action>) {
        if self.exposure_us > 0 && self.accumulating_for == Some(output_us) && self.accumulated >= 2 {
            actions.push(Action::EmitAccumulated { output_us, frames: self.accumulated });
            self.accumulating_for = None;
            self.accumulated = 0;
            return;
        }
        if self.accumulating_for == Some(output_us) {
            self.accumulating_for = None;
            self.accumulated = 0;
        }
        let before = self.history.iter().rev().find(|f| f.pts_us <= output_us).copied();
        let after = self.history.iter().find(|f| f.pts_us >= output_us).copied();
        let last = *self.history.last().expect("history is never empty when emitting");
        let (from, to) = match (before, after) {
            (Some(b), Some(a)) => (b, a),
            (Some(b), None) => (b, b), // fim do fluxo: mantém o último quadro
            (None, Some(a)) => (a, a),
            (None, None) => (last, last),
        };
        let phase = if to.pts_us == from.pts_us { 0.0 } else { (((output_us - from.pts_us) as f64) / ((to.pts_us - from.pts_us) as f64)).clamp(0.0, 1.0) as f32 };
        // Um quadro exatamente no instante não precisa de síntese.
        let (a, b, t) = if phase <= 0.0 { (from, from, 0.0) } else if phase >= 1.0 { (to, to, 0.0) } else { (from, to, phase) };
        actions.push(Action::EmitSynthesized {
            output_us, from_slot: a.slot, to_slot: b.slot, from_us: a.pts_us, to_us: b.pts_us, phase: t, exposure_us: self.exposure_us,
        });
    }

    fn free_slot(&self) -> usize {
        if self.history.len() < SLOTS {
            return (0..SLOTS).find(|slot| self.history.iter().all(|f| f.slot != *slot)).unwrap();
        }
        self.history[0].slot
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(fps: f32, exposure: f32, inputs: &[i64]) -> Vec<Action> {
        let mut scheduler = Scheduler::new(fps, exposure).unwrap();
        let mut actions = Vec::new();
        for &pts in inputs {
            scheduler.on_input(pts, &mut actions).unwrap();
        }
        scheduler.on_end_of_stream(&mut actions);
        actions
    }

    fn outputs(actions: &[Action]) -> Vec<i64> {
        actions.iter().filter_map(|a| match a {
            Action::EmitAccumulated { output_us, .. } | Action::EmitSynthesized { output_us, .. } => Some(*output_us),
            _ => None,
        }).collect()
    }

    /// Simula o histórico para checar que toda posição citada ainda guarda o quadro certo.
    fn check_slots(actions: &[Action]) {
        let mut slots = [i64::MIN; SLOTS];
        for action in actions {
            match *action {
                Action::Store { slot, pts_us } => slots[slot] = pts_us,
                Action::Accumulate { slot, .. } => assert_ne!(slots[slot], i64::MIN),
                Action::EmitSynthesized { from_slot, to_slot, from_us, to_us, .. } => {
                    assert_eq!(slots[from_slot], from_us);
                    assert_eq!(slots[to_slot], to_us);
                }
                Action::EmitAccumulated { .. } => {}
            }
        }
    }

    #[test]
    fn quarter_speed_slow_motion_fills_the_grid_with_phases() {
        // 30 fps a 0,25×: um quadro a cada 133 333 µs.
        let inputs: Vec<i64> = (0..10).map(|i| (i as f64 * 133_333.33).round() as i64).collect();
        let actions = run(30.0, 0.0, &inputs);
        check_slots(&actions);
        let out = outputs(&actions);
        assert_eq!(out.len(), 37); // 0 … 1,2 s a cada 33,3 ms
        for (k, &t) in out.iter().enumerate() {
            assert_eq!(t, (k as f64 * 1_000_000.0 / 30.0).round() as i64);
        }
        let phases: Vec<f32> = actions.iter().filter_map(|a| match a { Action::EmitSynthesized { phase, .. } => Some(*phase), _ => None }).take(5).collect();
        let expected = [0.0, 0.25, 0.5, 0.75, 0.0];
        for (p, e) in phases.iter().zip(expected) {
            assert!((p - e).abs() < 1e-3, "{phases:?}");
        }
    }

    #[test]
    fn fast_motion_with_blur_integrates_frames_inside_the_shutter() {
        // 4× de 30 fps = 120 fps; obturador de 180° a 30 fps = 16,7 ms → 2 quadros por saída.
        let inputs: Vec<i64> = (0..40).map(|i| (i as f64 * 8_333.33).round() as i64).collect();
        let actions = run(30.0, 0.5, &inputs);
        check_slots(&actions);
        let accumulated: Vec<u32> = actions.iter().filter_map(|a| match a { Action::EmitAccumulated { frames, .. } => Some(*frames), _ => None }).collect();
        // Com carimbos reais (arredondados) cada janela de 16,7 ms pega 1 a 3 quadros de 8,3 ms;
        // a maioria das saídas é integrada de verdade.
        assert!(accumulated.len() >= 5, "{} acumuladas", accumulated.len());
        assert!(accumulated.iter().all(|&n| (2..=3).contains(&n)));
        let out = outputs(&actions);
        assert!(out.windows(2).all(|w| w[1] > w[0]));
    }

    #[test]
    fn fast_motion_without_blur_samples_the_grid_without_synthesis() {
        let inputs: Vec<i64> = (0..40).map(|i| (i as f64 * 8_333.33).round() as i64).collect();
        let actions = run(30.0, 0.0, &inputs);
        check_slots(&actions);
        for action in &actions {
            if let Action::EmitSynthesized { phase, output_us, from_us, to_us, .. } = action {
                assert!(*from_us <= *output_us && *output_us <= *to_us.max(from_us));
                assert!(*phase >= 0.0 && *phase < 1.0);
            }
        }
        assert_eq!(outputs(&actions).len(), 10);
    }

    #[test]
    fn end_of_stream_holds_the_last_frame_and_never_overshoots() {
        let actions = run(30.0, 0.0, &[0, 500_000]);
        let out = outputs(&actions);
        assert_eq!(*out.last().unwrap(), 500_000);
        assert!(out.iter().all(|&t| t <= 500_000));
    }

    #[test]
    fn timestamps_must_increase_and_reset_starts_over() {
        let mut scheduler = Scheduler::new(30.0, 0.0).unwrap();
        let mut actions = Vec::new();
        scheduler.on_input(1000, &mut actions).unwrap();
        assert_eq!(scheduler.on_input(1000, &mut actions), Err(ScheduleError::NonIncreasingTimestamp));
        scheduler.reset();
        assert!(scheduler.on_input(10, &mut actions).is_ok());
        assert!(Scheduler::new(0.0, 0.0).is_err());
        assert!(Scheduler::new(30.0, 1.5).is_err());
    }

    #[test]
    fn grid_is_absolute_for_clips_that_start_mid_sequence() {
        // Um clipe que começa em 1,01 s cai na mesma grade do encoder.
        let actions = run(30.0, 0.0, &[1_010_000, 1_143_333, 1_276_667]);
        let out = outputs(&actions);
        assert_eq!(out[0], 1_033_333);
        for t in out {
            let k = (t as f64 * 30.0 / 1_000_000.0).round();
            assert_eq!(t, (k * 1_000_000.0 / 30.0).round() as i64);
        }
    }

    #[test]
    fn mixed_ramp_alternates_between_synthesis_and_accumulation() {
        // Primeiro lento (133 ms entre quadros), depois rápido (8 ms).
        let mut inputs: Vec<i64> = (0..5).map(|i| i * 133_333).collect();
        let start = *inputs.last().unwrap();
        inputs.extend((1..40).map(|i| start + i * 8_333));
        let actions = run(30.0, 1.0, &inputs);
        check_slots(&actions);
        assert!(actions.iter().any(|a| matches!(a, Action::EmitSynthesized { phase, .. } if *phase > 0.0)));
        assert!(actions.iter().any(|a| matches!(a, Action::EmitAccumulated { .. })));
        let out = outputs(&actions);
        assert!(out.windows(2).all(|w| w[1] > w[0]));
    }
}
