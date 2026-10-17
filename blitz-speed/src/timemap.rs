//! Mapa de tempo de um clipe: tempo da timeline ↔ tempo da mídia.
//!
//! A curva contínua é cortada em degraus numa grade fixa da mídia (e em todo ponto da curva).
//! Cada degrau toca na **média harmônica** da curva sobre ele, então sua duração é a integral
//! exata de `ds / v(s)`: toda fronteira de degrau cai sobre a curva verdadeira e a duração total
//! é exata, com qualquer número de mudanças de velocidade. O Media3 consome os mesmos degraus
//! (`ClipSpeedProvider`), e é isso que mantém prévia e exportação na mesma timeline.
//!
//! O mapa é serializado numa tabela de `i64` que o Kotlin guarda (memória da JVM, sem handle
//! nativo para liberar); toda consulta lê a tabela sem alocar.

use crate::curve::{self, CubicBezier, Easing, SpeedPoint};

pub const MAGIC: i64 = 0x4253_5432; // "BST2"
pub const HEADER: usize = 12;
const H_SOURCE: usize = 1;
const H_IN: usize = 2;
const H_OUT: usize = 3;
const H_REVERSE: usize = 4;
const H_SPEED: usize = 5;
const H_POINTS: usize = 6;
const H_FULL: usize = 7;
const H_TRIM: usize = 8;
const H_DURATION: usize = 9;
const STRIDE: usize = 4;

/// Um degrau de velocidade constante.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Segment {
    pub source_us: i64,
    pub end_us: i64,
    pub speed: f32,
    pub timeline_us: i64,
}

impl Segment {
    pub fn duration_us(&self) -> i64 {
        round_half_up((self.end_us - self.source_us) as f64 / self.speed as f64)
    }
}

/// `Math.round` do Java/Kotlin (`roundToLong`): metade arredonda para +∞.
pub fn round_half_up(value: f64) -> i64 {
    (value + 0.5).floor() as i64
}

#[derive(Clone, Debug, PartialEq)]
pub struct ClipParams {
    pub source_us: i64,
    pub in_us: i64,
    pub out_us: i64,
    pub speed: f32,
    pub reverse: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MapError {
    InvalidWindow,
    InvalidSpeed,
    Curve(curve::CurveError),
    Corrupt,
}

/// Passo da grade de degraus, idêntico ao do mapa antigo: ≥ 20 ms e no máximo ~2048 degraus.
pub fn step_us(source_us: i64) -> i64 {
    20_000i64.max(((source_us + 2047) / 2048 + 19_999) / 20_000 * 20_000)
}

/// Constrói a tabela de um clipe.
pub fn build(params: &ClipParams, points: &[SpeedPoint]) -> Result<Vec<i64>, MapError> {
    if params.source_us <= 0 || params.in_us < 0 || params.out_us > params.source_us || params.out_us <= params.in_us {
        return Err(MapError::InvalidWindow);
    }
    if !params.speed.is_finite() || params.speed < curve::MIN_SPEED || params.speed > curve::MAX_SPEED {
        return Err(MapError::InvalidSpeed);
    }
    curve::validate(points, params.source_us).map_err(MapError::Curve)?;

    let step_speed = |start: i64, end: i64| -> f32 {
        if points.is_empty() { params.speed } else { curve::effective_speed(points, start, end, params.speed) }
    };

    let mut boundaries: Vec<i64> = vec![0, params.source_us];
    if !points.is_empty() {
        let step = step_us(params.source_us);
        let mut at = step;
        while at < params.source_us {
            boundaries.push(at);
            at += step;
        }
        for point in points {
            if point.source_us > 0 && point.source_us < params.source_us {
                boundaries.push(point.source_us);
            }
        }
    }
    boundaries.sort_unstable();
    boundaries.dedup();

    let mut full = Vec::with_capacity(boundaries.len());
    let mut output = 0i64;
    for pair in boundaries.windows(2) {
        let segment = Segment { source_us: pair[0], end_us: pair[1], speed: step_speed(pair[0], pair[1]), timeline_us: output };
        output += segment.duration_us();
        full.push(segment);
    }
    let mut trimmed = Vec::new();
    let mut output = 0i64;
    for segment in &full {
        let start = params.in_us.max(segment.source_us);
        let end = params.out_us.min(segment.end_us);
        if end > start {
            // Um degrau cortado por trim ou split mantém a velocidade do degrau inteiro: assim as
            // duas metades de um split reproduzem exatamente o mapeamento do clipe original
            // (as fronteiras da grade continuam exatas; dentro de um degrau a diferença para a
            // curva contínua fica abaixo de 0,1 ms).
            let item = Segment { source_us: start, end_us: end, speed: segment.speed, timeline_us: output };
            output += item.duration_us();
            trimmed.push(item);
        }
    }
    let duration = trimmed.last().map(|s| s.timeline_us + s.duration_us()).unwrap_or(0);

    let mut table = vec![0i64; HEADER + STRIDE * (points.len() + full.len() + trimmed.len())];
    table[0] = MAGIC;
    table[H_SOURCE] = params.source_us;
    table[H_IN] = params.in_us;
    table[H_OUT] = params.out_us;
    table[H_REVERSE] = params.reverse as i64;
    table[H_SPEED] = params.speed.to_bits() as i64;
    table[H_POINTS] = points.len() as i64;
    table[H_FULL] = full.len() as i64;
    table[H_TRIM] = trimmed.len() as i64;
    table[H_DURATION] = duration;
    let mut cursor = HEADER;
    for point in points {
        table[cursor] = point.source_us;
        table[cursor + 1] = pack(point.speed.to_bits(), point.easing as i32 as u32);
        table[cursor + 2] = pack(point.bezier.x1.to_bits(), point.bezier.y1.to_bits());
        table[cursor + 3] = pack(point.bezier.x2.to_bits(), point.bezier.y2.to_bits());
        cursor += STRIDE;
    }
    for segment in full.iter().chain(trimmed.iter()) {
        table[cursor] = segment.source_us;
        table[cursor + 1] = segment.end_us;
        table[cursor + 2] = segment.timeline_us;
        table[cursor + 3] = segment.speed.to_bits() as i64;
        cursor += STRIDE;
    }
    Ok(table)
}

fn pack(low: u32, high: u32) -> i64 {
    ((high as u64) << 32 | low as u64) as i64
}

fn unpack(value: i64) -> (u32, u32) {
    let bits = value as u64;
    (bits as u32, (bits >> 32) as u32)
}

/// Consulta sobre uma tabela já construída, sem alocação.
#[derive(Clone, Copy)]
pub struct TimeMap<'a> {
    table: &'a [i64],
}

impl<'a> TimeMap<'a> {
    pub fn new(table: &'a [i64]) -> Result<TimeMap<'a>, MapError> {
        if table.len() < HEADER || table[0] != MAGIC {
            return Err(MapError::Corrupt);
        }
        let points = table[H_POINTS];
        let full = table[H_FULL];
        let trim = table[H_TRIM];
        if points < 0 || full < 1 || trim < 1 || points as usize > curve::MAX_POINTS {
            return Err(MapError::Corrupt);
        }
        let expected = HEADER + STRIDE * (points + full + trim) as usize;
        if table.len() != expected {
            return Err(MapError::Corrupt);
        }
        Ok(TimeMap { table })
    }

    pub fn source_us(&self) -> i64 { self.table[H_SOURCE] }
    pub fn in_us(&self) -> i64 { self.table[H_IN] }
    pub fn out_us(&self) -> i64 { self.table[H_OUT] }
    pub fn reverse(&self) -> bool { self.table[H_REVERSE] != 0 }
    pub fn constant_speed(&self) -> f32 { f32::from_bits(self.table[H_SPEED] as u32) }
    pub fn duration_us(&self) -> i64 { self.table[H_DURATION] }
    pub fn point_count(&self) -> usize { self.table[H_POINTS] as usize }
    pub fn full_count(&self) -> usize { self.table[H_FULL] as usize }
    pub fn trimmed_count(&self) -> usize { self.table[H_TRIM] as usize }

    pub fn point(&self, index: usize) -> SpeedPoint {
        let base = HEADER + STRIDE * index;
        let (speed, easing) = unpack(self.table[base + 1]);
        let (x1, y1) = unpack(self.table[base + 2]);
        let (x2, y2) = unpack(self.table[base + 3]);
        SpeedPoint {
            source_us: self.table[base],
            speed: f32::from_bits(speed),
            easing: Easing::from_ordinal(easing as i32).unwrap_or(Easing::Linear),
            bezier: CubicBezier { x1: f32::from_bits(x1), y1: f32::from_bits(y1), x2: f32::from_bits(x2), y2: f32::from_bits(y2) },
        }
    }

    /// Os pontos da curva, copiados para a pilha (no máximo 32).
    pub fn points(&self) -> ([SpeedPoint; curve::MAX_POINTS], usize) {
        let mut out = [SpeedPoint::linear(0, 1.0); curve::MAX_POINTS];
        let count = self.point_count();
        for (index, slot) in out.iter_mut().enumerate().take(count) {
            *slot = self.point(index);
        }
        (out, count)
    }

    fn segment_at(&self, base: usize, index: usize) -> Segment {
        let at = base + STRIDE * index;
        Segment {
            source_us: self.table[at],
            end_us: self.table[at + 1],
            timeline_us: self.table[at + 2],
            speed: f32::from_bits(self.table[at + 3] as u32),
        }
    }

    pub fn full(&self, index: usize) -> Segment {
        self.segment_at(HEADER + STRIDE * self.point_count(), index)
    }

    pub fn trimmed(&self, index: usize) -> Segment {
        self.segment_at(HEADER + STRIDE * (self.point_count() + self.full_count()), index)
    }

    /// Último índice `i` com `key(i) <= value` (0 se nenhum).
    fn last_le(count: usize, value: i64, key: impl Fn(usize) -> i64) -> usize {
        let (mut low, mut high) = (0usize, count);
        while low < high {
            let mid = (low + high) / 2;
            if key(mid) <= value { low = mid + 1 } else { high = mid }
        }
        low.saturating_sub(1)
    }

    fn forward_source_at(&self, timeline_us: i64) -> i64 {
        if timeline_us <= 0 {
            return self.in_us();
        }
        if timeline_us >= self.duration_us() {
            return self.out_us();
        }
        let index = Self::last_le(self.trimmed_count(), timeline_us, |i| self.trimmed(i).timeline_us);
        let item = self.trimmed(index);
        (item.source_us + round_half_up((timeline_us - item.timeline_us) as f64 * item.speed as f64)).clamp(item.source_us, item.end_us)
    }

    /// Tempo da mídia mostrado no instante `timeline_us` do clipe. A reversão percorre a mesma
    /// tabela a partir do fim, então duração, cortes e rampas mantêm a forma.
    pub fn source_at(&self, timeline_us: i64) -> i64 {
        let source = if self.reverse() {
            self.forward_source_at(self.duration_us() - timeline_us)
        } else {
            self.forward_source_at(timeline_us)
        };
        source.clamp(self.in_us().min(self.out_us()), self.in_us().max(self.out_us()))
    }

    fn forward_timeline_at(&self, source_us: i64) -> i64 {
        let source = source_us.clamp(self.in_us(), self.out_us());
        let index = Self::last_le(self.trimmed_count(), source, |i| self.trimmed(i).source_us);
        let item = self.trimmed(index);
        (item.timeline_us + round_half_up((source - item.source_us) as f64 / item.speed as f64)).clamp(0, self.duration_us())
    }

    pub fn timeline_at(&self, source_us: i64) -> i64 {
        let forward = self.forward_timeline_at(source_us);
        if self.reverse() { (self.duration_us() - forward).clamp(0, self.duration_us()) } else { forward }
    }

    /// Alças de trim podem recuperar mídia fora da janela visível.
    pub fn extended_source_at(&self, timeline_us: i64) -> i64 {
        if (0..=self.duration_us()).contains(&timeline_us) {
            return self.source_at(timeline_us);
        }
        let full_count = self.full_count();
        let absolute = |source: i64| -> i64 {
            let item = self.full(Self::last_le(full_count, source, |i| self.full(i).source_us));
            item.timeline_us + round_half_up((source - item.source_us) as f64 / item.speed as f64)
        };
        let target = if timeline_us < 0 {
            absolute(self.in_us()) + timeline_us
        } else {
            absolute(self.out_us()) + timeline_us - self.duration_us()
        };
        let first = self.full(0);
        let item = if first.timeline_us <= target {
            self.full(Self::last_le(full_count, target, |i| self.full(i).timeline_us))
        } else {
            first
        };
        (item.source_us + round_half_up((target - item.timeline_us) as f64 * item.speed as f64)).clamp(0, self.source_us())
    }

    fn segment_at_input(&self, relative_source_us: i64) -> Segment {
        let source = (self.in_us() + relative_source_us.max(0)).min(self.out_us());
        let index = Self::last_le(self.trimmed_count(), source, |i| self.trimmed(i).source_us);
        self.trimmed(index.min(self.trimmed_count() - 1))
    }

    /// Velocidade do degrau na posição relativa ao início do trecho (convenção do Media3).
    pub fn speed_at_input(&self, relative_source_us: i64) -> f32 {
        self.segment_at_input(relative_source_us).speed
    }

    pub fn next_input_change(&self, relative_source_us: i64) -> Option<i64> {
        let end = self.segment_at_input(relative_source_us).end_us;
        if end < self.out_us() { Some(end - self.in_us()) } else { None }
    }

    /// Valor contínuo da curva (não do degrau) numa posição absoluta da mídia.
    pub fn curve_speed_at_source(&self, source_us: i64) -> f32 {
        let (points, count) = self.points();
        curve::speed_at(&points[..count], source_us as f64, self.constant_speed()) as f32
    }

    /// Velocidade percebida num instante da timeline do clipe.
    pub fn curve_speed_at_timeline(&self, timeline_us: i64) -> f32 {
        self.curve_speed_at_source(self.source_at(timeline_us.clamp(0, self.duration_us())))
    }

    /// Menor e maior velocidade instantânea dentro da janela visível.
    pub fn speed_range(&self) -> (f32, f32) {
        let (points, count) = self.points();
        curve::speed_range(&points[..count], self.in_us(), self.out_us(), self.constant_speed())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::curve::SpeedPoint as P;

    const SECOND: i64 = 1_000_000;

    fn ramp() -> Vec<P> {
        vec![P::linear(0, 0.25), P::linear(4 * SECOND, 4.0), P::linear(10 * SECOND, 0.5)]
    }

    fn map(params: ClipParams, points: &[P]) -> Vec<i64> {
        build(&params, points).expect("valid")
    }

    fn params(in_us: i64, out_us: i64, speed: f32, reverse: bool) -> ClipParams {
        ClipParams { source_us: 10 * SECOND, in_us, out_us, speed, reverse }
    }

    #[test]
    fn constant_speeds_preserve_legacy_timing() {
        for speed in [0.1f32, 0.3, 0.7, 1.0, 1.3, 2.7, 16.0] {
            let table = map(params(123_456, 8_234_567, speed, false), &[]);
            let m = TimeMap::new(&table).unwrap();
            assert_eq!(round_half_up((8_234_567 - 123_456) as f64 / speed as f64), m.duration_us());
            let mut t = 0;
            while t <= m.duration_us() {
                assert_eq!((123_456 + round_half_up(t as f64 * speed as f64)).min(8_234_567), m.source_at(t));
                t += 100_000;
            }
        }
    }

    #[test]
    fn ramp_is_monotonic_and_invertible() {
        let table = map(params(231_123, 9_734_532, 1.0, false), &ramp());
        let m = TimeMap::new(&table).unwrap();
        let mut previous = -1;
        let mut t = 0;
        while t <= m.duration_us() {
            let s = m.source_at(t);
            assert!(s >= previous);
            previous = s;
            assert!((t - m.timeline_at(s)).abs() <= 3, "t={t}");
            t += 1000;
        }
        assert_eq!(m.out_us(), m.source_at(m.duration_us()));
        assert_eq!(m.in_us(), m.source_at(-1));
    }

    #[test]
    fn total_duration_equals_the_exact_integral_with_many_changes() {
        let points = vec![
            P::eased(0, 1.0, Easing::Smooth), P::eased(SECOND, 0.1, Easing::EaseOut), P::eased(2 * SECOND, 8.0, Easing::Bezier),
            P::eased(3 * SECOND, 0.5, Easing::Hold), P::eased(5 * SECOND, 3.0, Easing::EaseInOut), P::linear(7 * SECOND, 0.2),
            P::linear(9 * SECOND, 16.0),
        ];
        for (in_us, out_us) in [(0, 10 * SECOND), (777_777, 8_888_888)] {
            let table = map(params(in_us, out_us, 1.0, false), &points);
            let m = TimeMap::new(&table).unwrap();
            let exact = curve::timeline_duration(&points, in_us as f64, out_us as f64, 1.0);
            // Cada degrau arredonda sua duração para 1 µs; um degrau cortado pelo trim mantém a
            // velocidade do degrau inteiro (até ~0,1 ms de diferença em cada ponta).
            let rounding = m.trimmed_count() as f64 * 0.5 + 1.0;
            let trimmed_edges = if in_us == 0 && out_us == 10 * SECOND { 0.0 } else { 200.0 };
            let tolerance = rounding + trimmed_edges;
            assert!((m.duration_us() as f64 - exact).abs() <= tolerance, "{} vs {exact}", m.duration_us());
            // As fronteiras internas dos degraus caem na curva exata (medidas a partir da primeira).
            let first = m.trimmed(1.min(m.trimmed_count() - 1));
            for i in 1..m.trimmed_count() {
                let s = m.trimmed(i);
                let expected = curve::timeline_duration(&points, first.source_us as f64, s.source_us as f64, 1.0);
                assert!(((s.timeline_us - first.timeline_us) as f64 - expected).abs() <= rounding, "segment {i}");
            }
        }
    }

    #[test]
    fn media3_schedule_matches_the_timeline() {
        let table = map(params(231_123, 9_734_532, 1.0, false), &ramp());
        let m = TimeMap::new(&table).unwrap();
        let (mut input, mut output) = (0i64, 0i64);
        while input < m.out_us() - m.in_us() {
            let speed = m.speed_at_input(input);
            let end = m.next_input_change(input).unwrap_or(m.out_us() - m.in_us());
            assert!(end > input);
            assert!((m.timeline_at(m.in_us() + input) - output).abs() <= 1);
            output += round_half_up((end - input) as f64 / speed as f64);
            input = end;
        }
        assert_eq!(m.duration_us(), output);
    }

    #[test]
    fn split_halves_add_up_and_keep_the_mapping() {
        let points = vec![P::eased(0, 0.25, Easing::Smooth), P::eased(4 * SECOND, 4.0, Easing::EaseIn), P::linear(10 * SECOND, 0.5)];
        let whole_table = map(params(0, 10 * SECOND, 1.0, false), &points);
        let whole = TimeMap::new(&whole_table).unwrap();
        let cut = whole.source_at(1_234_567);
        let a_table = map(params(0, cut, 1.0, false), &points);
        let b_table = map(params(cut, 10 * SECOND, 1.0, false), &points);
        let (a, b) = (TimeMap::new(&a_table).unwrap(), TimeMap::new(&b_table).unwrap());
        assert!((a.duration_us() + b.duration_us() - whole.duration_us()).abs() <= 2);
        let mut t = 0;
        while t <= b.duration_us() {
            let diff = (whole.source_at(a.duration_us() + t) - b.source_at(t)).abs();
            assert!(diff <= 4, "t={t} diff={diff} a={} whole={} b={} cut={cut}", a.duration_us(), whole.duration_us(), b.duration_us());
            t += 100_000;
        }
    }

    #[test]
    fn reversal_mirrors_forward_playback() {
        let forward_table = map(params(SECOND, 9 * SECOND, 0.75, false), &[]);
        let reversed_table = map(params(SECOND, 9 * SECOND, 0.75, true), &[]);
        let (f, r) = (TimeMap::new(&forward_table).unwrap(), TimeMap::new(&reversed_table).unwrap());
        assert_eq!(f.duration_us(), r.duration_us());
        assert_eq!(r.out_us(), r.source_at(0));
        let mut t = 0;
        while t <= r.duration_us() {
            assert!((f.source_at(r.duration_us() - t) - r.source_at(t)).abs() <= 1);
            t += 50_000;
        }
    }

    #[test]
    fn trim_handles_recover_source_outside_the_window() {
        let table = map(params(2 * SECOND, 6 * SECOND, 1.0, false), &ramp());
        let m = TimeMap::new(&table).unwrap();
        assert_eq!(0, m.extended_source_at(-100 * SECOND));
        assert_eq!(10 * SECOND, m.extended_source_at(100 * SECOND));
        assert!(m.extended_source_at(-SECOND) < m.in_us());
        assert!(m.extended_source_at(m.duration_us() + SECOND) > m.out_us());
    }

    #[test]
    fn very_long_clip_has_a_bounded_schedule() {
        let long = 6 * 3600 * SECOND;
        let p = ClipParams { source_us: long, in_us: 0, out_us: long, speed: 1.0, reverse: false };
        let table = map(p, &[P::linear(0, 1.0), P::linear(long, 16.0)]);
        let m = TimeMap::new(&table).unwrap();
        assert!(m.trimmed_count() <= 2080);
        assert!(m.duration_us() > 0);
    }

    #[test]
    fn invalid_input_is_rejected_and_corrupt_tables_are_detected() {
        assert_eq!(build(&params(5, 5, 1.0, false), &[]), Err(MapError::InvalidWindow));
        assert_eq!(build(&params(0, 5, 0.05, false), &[]), Err(MapError::InvalidSpeed));
        let mut table = map(params(0, SECOND, 1.0, false), &[]);
        table.pop();
        assert!(TimeMap::new(&table).is_err());
    }

    #[test]
    fn curve_values_are_continuous_not_stepped() {
        let table = map(params(0, 10 * SECOND, 1.0, false), &ramp());
        let m = TimeMap::new(&table).unwrap();
        assert!((m.curve_speed_at_source(2 * SECOND) - (0.25 + 3.75 * 0.5)).abs() < 1e-5);
        let (low, high) = m.speed_range();
        assert_eq!((low, high), (0.25, 4.0));
    }
}
