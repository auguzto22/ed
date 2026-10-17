//! Curva de velocidade contínua e sua integral exata.
//!
//! Uma curva é uma lista de pontos ancorados no tempo da **mídia** (não da timeline), para que
//! cortes e splits preservem a rampa. Entre dois pontos a velocidade segue o easing do ponto da
//! esquerda; antes do primeiro e depois do último ponto ela é mantida.
//!
//! A relação entre o tempo da mídia `s` e o tempo da timeline `t` é
//!
//! ```text
//! dt = ds / v(s)        t(s) = ∫ ds / v(s)
//! ```
//!
//! ou seja, a duração de um trecho é uma integral, não um produto. Amostrar a velocidade no meio
//! do trecho (o que o editor fazia antes) é a regra do ponto médio e acumula erro nas rampas.
//! Aqui cada trecho é integrado exatamente: forma fechada para segmentos lineares e constantes,
//! Gauss–Legendre de 8 pontos para easing e Bézier (exato bem abaixo de 1 µs nos trechos de
//! ≥ 20 ms que o mapa de tempo usa).

/// Limites de velocidade aceitos pelo editor (iguais a `MIN_CLIP_SPEED`/`MAX_CLIP_SPEED`).
pub const MIN_SPEED: f32 = 0.1;
pub const MAX_SPEED: f32 = 16.0;
/// Máximo de pontos por curva (o formato de projeto guarda até 32).
pub const MAX_POINTS: usize = 32;

/// Formas de segmento permitidas. Os valores são os ordinais do enum `Easing` do Kotlin, para
/// que o formato salvo continue o mesmo; easings que ultrapassam o alvo (mola, quique,
/// elástico, antecipação) não são aceitos porque sairiam da faixa de velocidade.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum Easing {
    Linear = 0,
    Smooth = 1,
    EaseIn = 2,
    EaseOut = 3,
    Hold = 4,
    EaseInOut = 5,
    Bezier = 6,
}

impl Easing {
    pub fn from_ordinal(ordinal: i32) -> Option<Easing> {
        Some(match ordinal {
            0 => Easing::Linear,
            1 => Easing::Smooth,
            2 => Easing::EaseIn,
            3 => Easing::EaseOut,
            4 => Easing::Hold,
            5 => Easing::EaseInOut,
            6 => Easing::Bezier,
            _ => return None,
        })
    }

    /// Fração moldada em 0..1 (mesmas fórmulas do `Easing.apply` do Kotlin).
    pub fn apply(self, value: f64, bezier: &CubicBezier) -> f64 {
        let t = value.clamp(0.0, 1.0);
        if t == 0.0 || t == 1.0 {
            return t;
        }
        match self {
            Easing::Linear => t,
            Easing::Smooth => t * t * (3.0 - 2.0 * t),
            Easing::EaseIn => t * t,
            Easing::EaseOut => 1.0 - (1.0 - t) * (1.0 - t),
            Easing::Hold => 0.0,
            Easing::EaseInOut => {
                if t < 0.5 {
                    2.0 * t * t
                } else {
                    let u = -2.0 * t + 2.0;
                    1.0 - u * u / 2.0
                }
            }
            Easing::Bezier => bezier.apply(t),
        }
    }
}

/// Bézier cúbica de (0,0) a (1,1), como a do Kotlin (`CubicBezier`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CubicBezier {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

impl Default for CubicBezier {
    fn default() -> Self {
        CubicBezier { x1: 0.25, y1: 0.1, x2: 0.25, y2: 1.0 }
    }
}

impl CubicBezier {
    pub fn is_valid(&self) -> bool {
        let finite = self.x1.is_finite() && self.y1.is_finite() && self.x2.is_finite() && self.y2.is_finite();
        finite
            && (0.0..=1.0).contains(&self.x1)
            && (0.0..=1.0).contains(&self.x2)
            && (-2.0..=3.0).contains(&self.y1)
            && (-2.0..=3.0).contains(&self.y2)
    }

    /// y para um x em 0..1, por bissecção no parâmetro (x(t) é monótono para x1, x2 em 0..1).
    pub fn apply(&self, value: f64) -> f64 {
        let x = value.clamp(0.0, 1.0);
        if x == 0.0 || x == 1.0 {
            return x;
        }
        fn coordinate(t: f64, a: f64, b: f64) -> f64 {
            let u = 1.0 - t;
            3.0 * u * u * t * a + 3.0 * u * t * t * b + t * t * t
        }
        let (mut low, mut high) = (0.0f64, 1.0f64);
        for _ in 0..40 {
            let mid = (low + high) * 0.5;
            if coordinate(mid, self.x1 as f64, self.x2 as f64) < x {
                low = mid
            } else {
                high = mid
            }
        }
        coordinate((low + high) * 0.5, self.y1 as f64, self.y2 as f64)
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpeedPoint {
    pub source_us: i64,
    pub speed: f32,
    /// Forma do segmento que sai deste ponto.
    pub easing: Easing,
    pub bezier: CubicBezier,
}

impl SpeedPoint {
    pub fn linear(source_us: i64, speed: f32) -> SpeedPoint {
        SpeedPoint { source_us, speed, easing: Easing::Linear, bezier: CubicBezier::default() }
    }

    pub fn eased(source_us: i64, speed: f32, easing: Easing) -> SpeedPoint {
        SpeedPoint { source_us, speed, easing, bezier: CubicBezier::default() }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CurveError {
    TooManyPoints,
    NegativeTime,
    SpeedOutOfRange,
    NotIncreasing,
    InvalidBezier,
    BeyondSource,
}

/// Valida uma curva contra a duração total da mídia.
pub fn validate(points: &[SpeedPoint], source_us: i64) -> Result<(), CurveError> {
    if points.len() > MAX_POINTS {
        return Err(CurveError::TooManyPoints);
    }
    for (index, point) in points.iter().enumerate() {
        if point.source_us < 0 {
            return Err(CurveError::NegativeTime);
        }
        if point.source_us > source_us {
            return Err(CurveError::BeyondSource);
        }
        if !point.speed.is_finite() || point.speed < MIN_SPEED || point.speed > MAX_SPEED {
            return Err(CurveError::SpeedOutOfRange);
        }
        if point.easing == Easing::Bezier && !point.bezier.is_valid() {
            return Err(CurveError::InvalidBezier);
        }
        if index > 0 && points[index - 1].source_us >= point.source_us {
            return Err(CurveError::NotIncreasing);
        }
    }
    Ok(())
}

pub fn clamp_speed(speed: f64) -> f64 {
    speed.clamp(MIN_SPEED as f64, MAX_SPEED as f64)
}

/// Índice do ponto que inicia o segmento que contém `source_us`, ou `None` antes do primeiro.
pub fn segment_index(points: &[SpeedPoint], source_us: f64) -> Option<usize> {
    // Último ponto com source_us <= s.
    let count = points.partition_point(|p| (p.source_us as f64) <= source_us);
    count.checked_sub(1)
}

fn segment_speed(a: &SpeedPoint, b: &SpeedPoint, source_us: f64) -> f64 {
    let span = (b.source_us - a.source_us) as f64;
    if span <= 0.0 || a.easing == Easing::Hold {
        return a.speed as f64;
    }
    let fraction = ((source_us - a.source_us as f64) / span).clamp(0.0, 1.0);
    let shaped = a.easing.apply(fraction, &a.bezier);
    clamp_speed(a.speed as f64 + (b.speed as f64 - a.speed as f64) * shaped)
}

/// Velocidade da curva numa posição absoluta da mídia. Curva vazia = `constant`.
pub fn speed_at(points: &[SpeedPoint], source_us: f64, constant: f32) -> f64 {
    if points.is_empty() {
        return constant as f64;
    }
    match segment_index(points, source_us) {
        None => points[0].speed as f64,
        Some(index) if index + 1 >= points.len() => points[points.len() - 1].speed as f64,
        Some(index) => segment_speed(&points[index], &points[index + 1], source_us),
    }
}

/// Microssegundos de timeline para tocar a mídia `[from, to]` (`from <= to`).
pub fn timeline_duration(points: &[SpeedPoint], from: f64, to: f64, constant: f32) -> f64 {
    debug_assert!(to >= from);
    if to <= from {
        return 0.0;
    }
    if points.is_empty() {
        return (to - from) / constant as f64;
    }
    let mut total = 0.0;
    let mut cursor = from;
    let mut index = segment_index(points, cursor);
    while cursor < to {
        let next = match index {
            None => 0,
            Some(i) => i + 1,
        };
        let segment_end = if next < points.len() { points[next].source_us as f64 } else { f64::INFINITY };
        let piece_end = to.min(segment_end);
        if piece_end > cursor {
            total += piece(points, index, cursor, piece_end);
        }
        cursor = piece_end;
        index = Some(next);
    }
    total
}

fn piece(points: &[SpeedPoint], index: Option<usize>, from: f64, to: f64) -> f64 {
    let length = to - from;
    let index = match index {
        None => return length / points[0].speed as f64,
        Some(i) if i + 1 >= points.len() => return length / points[points.len() - 1].speed as f64,
        Some(i) => i,
    };
    let a = &points[index];
    let b = &points[index + 1];
    if a.easing == Easing::Hold || a.speed == b.speed {
        return length / a.speed as f64;
    }
    if a.easing == Easing::Linear {
        // v(s) = v0 + k (s − from). ∫ ds / v = ln(v1 / v0) / k; série quando k ≈ 0.
        let v0 = segment_speed(a, b, from);
        let v1 = segment_speed(a, b, to);
        let relative = (v1 - v0) / v0;
        if relative.abs() < 1e-6 {
            return length / ((v0 + v1) * 0.5);
        }
        let slope = (v1 - v0) / length;
        return (v1 / v0).ln() / slope;
    }
    let integrand = |s: f64| 1.0 / segment_speed(a, b, s);
    adaptive(&integrand, from, to, gauss_legendre(from, to, &integrand), 0)
}

/// Gauss–Legendre adaptativo: divide ao meio até as duas metades concordarem com o todo.
/// Nos degraus de 20 ms do mapa de tempo converge na primeira avaliação; em trechos longos de
/// curvas fortes (0,1× → 16×) subdivide só onde a curva muda depressa.
fn adaptive(integrand: &impl Fn(f64) -> f64, from: f64, to: f64, whole: f64, depth: u32) -> f64 {
    let middle = (from + to) * 0.5;
    let left = gauss_legendre(from, middle, integrand);
    let right = gauss_legendre(middle, to, integrand);
    let halves = left + right;
    if depth >= 24 || (halves - whole).abs() <= 1e-4 {
        return halves;
    }
    adaptive(integrand, from, middle, left, depth + 1) + adaptive(integrand, middle, to, right, depth + 1)
}

const GL_NODES: [f64; 8] = [
    -0.960_289_856_497_536_3,
    -0.796_666_477_413_626_7,
    -0.525_532_409_916_329_0,
    -0.183_434_642_495_649_8,
    0.183_434_642_495_649_8,
    0.525_532_409_916_329_0,
    0.796_666_477_413_626_7,
    0.960_289_856_497_536_3,
];
const GL_WEIGHTS: [f64; 8] = [
    0.101_228_536_290_376_3,
    0.222_381_034_453_374_5,
    0.313_706_645_877_887_3,
    0.362_683_783_378_362_0,
    0.362_683_783_378_362_0,
    0.313_706_645_877_887_3,
    0.222_381_034_453_374_5,
    0.101_228_536_290_376_3,
];

fn gauss_legendre(from: f64, to: f64, integrand: &impl Fn(f64) -> f64) -> f64 {
    let half = (to - from) * 0.5;
    let center = from + half;
    let mut sum = 0.0;
    for i in 0..8 {
        sum += GL_WEIGHTS[i] * integrand(center + half * GL_NODES[i]);
    }
    sum * half
}

/// Velocidade constante que toca `[from, to]` exatamente no tempo integrado: a média harmônica
/// da curva no trecho. Cada degrau do cronograma compartilhado com o Media3 usa este valor, então
/// as fronteiras dos degraus caem sobre a curva exata.
pub fn effective_speed(points: &[SpeedPoint], from: i64, to: i64, constant: f32) -> f32 {
    debug_assert!(to > from);
    if points.is_empty() {
        return constant;
    }
    let duration = timeline_duration(points, from as f64, to as f64, constant);
    clamp_speed((to - from) as f64 / duration) as f32
}

/// Menor e maior velocidade instantânea da curva em `[from, to]`.
///
/// Os easings permitidos são monótonos entre dois pontos, exceto a Bézier com controles fora de
/// 0..1, então os extremos estão nos pontos e nas bordas; segmentos Bézier também são amostrados.
pub fn speed_range(points: &[SpeedPoint], from: i64, to: i64, constant: f32) -> (f32, f32) {
    if points.is_empty() {
        return (constant, constant);
    }
    let mut low = f64::INFINITY;
    let mut high = f64::NEG_INFINITY;
    let mut visit = |s: f64| {
        let v = speed_at(points, s, constant);
        low = low.min(v);
        high = high.max(v);
    };
    visit(from as f64);
    visit(to as f64);
    for (index, point) in points.iter().enumerate() {
        if point.source_us > from && point.source_us < to {
            visit(point.source_us as f64);
        }
        if point.easing == Easing::Bezier && index + 1 < points.len() {
            let start = point.source_us.max(from) as f64;
            let end = points[index + 1].source_us.min(to) as f64;
            if end > start {
                for step in 1..64 {
                    visit(start + (end - start) * step as f64 / 64.0);
                }
            }
        }
    }
    (low as f32, high as f32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn numeric(points: &[SpeedPoint], from: f64, to: f64) -> f64 {
        // Simpson composto com passo fino: referência independente das formas fechadas.
        let n = 200_000;
        let h = (to - from) / n as f64;
        let f = |s: f64| 1.0 / speed_at(points, s, 1.0);
        let mut sum = f(from) + f(to);
        for i in 1..n {
            let x = from + i as f64 * h;
            sum += if i % 2 == 1 { 4.0 * f(x) } else { 2.0 * f(x) };
        }
        sum * h / 3.0
    }

    #[test]
    fn linear_ramp_matches_closed_form() {
        let points = [SpeedPoint::linear(0, 0.25), SpeedPoint::linear(4_000_000, 4.0)];
        let exact = 4_000_000.0 * (4.0f64 / 0.25).ln() / (4.0 - 0.25);
        let got = timeline_duration(&points, 0.0, 4_000_000.0, 1.0);
        assert!((got - exact).abs() < 1e-3, "{got} vs {exact}");
    }

    #[test]
    fn every_easing_integrates_like_a_fine_reference() {
        for easing in [Easing::Linear, Easing::Smooth, Easing::EaseIn, Easing::EaseOut, Easing::EaseInOut, Easing::Bezier, Easing::Hold] {
            let mut a = SpeedPoint::eased(1_000_000, 0.2, easing);
            a.bezier = CubicBezier { x1: 0.1, y1: 0.8, x2: 0.3, y2: 1.0 };
            let points = [a, SpeedPoint::linear(3_000_000, 6.0), SpeedPoint::linear(5_000_000, 0.5)];
            for (from, to) in [(0.0, 6_000_000.0), (1_234_567.0, 1_254_567.0), (2_900_000.0, 3_100_000.0)] {
                let got = timeline_duration(&points, from, to, 1.0);
                // Hold tem degrau: a referência por Simpson tem erro de O(h) no salto.
                let tolerance = if easing == Easing::Hold { 50.0 } else { 0.5 };
                let reference = numeric(&points, from, to);
                assert!((got - reference).abs() < tolerance, "{easing:?} [{from},{to}] {got} vs {reference}");
            }
        }
    }

    #[test]
    fn duration_is_additive() {
        let points = [SpeedPoint::eased(0, 0.3, Easing::Smooth), SpeedPoint::eased(2_000_000, 3.0, Easing::EaseOut), SpeedPoint::linear(4_000_000, 1.0)];
        let whole = timeline_duration(&points, 100.0, 3_900_000.0, 1.0);
        let parts = timeline_duration(&points, 100.0, 1_777_777.0, 1.0) + timeline_duration(&points, 1_777_777.0, 3_900_000.0, 1.0);
        assert!((whole - parts).abs() < 1e-6);
    }

    #[test]
    fn harmonic_mean_reproduces_the_integral() {
        let points = [SpeedPoint::linear(0, 0.1), SpeedPoint::linear(1_000_000, 16.0)];
        let speed = effective_speed(&points, 0, 1_000_000, 1.0) as f64;
        let integral = timeline_duration(&points, 0.0, 1_000_000.0, 1.0);
        assert!((1_000_000.0 / speed - integral).abs() / integral < 1e-6);
    }

    #[test]
    fn validation_rejects_bad_curves() {
        assert_eq!(validate(&[SpeedPoint::linear(0, f32::NAN)], 10), Err(CurveError::SpeedOutOfRange));
        assert_eq!(validate(&[SpeedPoint::linear(0, 20.0)], 10), Err(CurveError::SpeedOutOfRange));
        assert_eq!(validate(&[SpeedPoint::linear(5, 1.0), SpeedPoint::linear(5, 1.0)], 10), Err(CurveError::NotIncreasing));
        assert_eq!(validate(&[SpeedPoint::linear(11, 1.0)], 10), Err(CurveError::BeyondSource));
        let mut bad = SpeedPoint::eased(0, 1.0, Easing::Bezier);
        bad.bezier.x1 = 1.5;
        assert_eq!(validate(&[bad], 10), Err(CurveError::InvalidBezier));
        assert!(validate(&[SpeedPoint::linear(0, 0.1), SpeedPoint::linear(10, 16.0)], 10).is_ok());
        assert_eq!(Easing::from_ordinal(7), None); // mola
    }

    #[test]
    fn range_finds_extremes_inside_a_window() {
        let points = [SpeedPoint::linear(0, 1.0), SpeedPoint::linear(1_000, 0.2), SpeedPoint::linear(2_000, 5.0)];
        assert_eq!(speed_range(&points, 0, 2_000, 1.0), (0.2, 5.0));
        let (low, high) = speed_range(&points, 1_500, 2_000, 1.0);
        assert!((low - 2.6).abs() < 1e-4 && high == 5.0);
    }
}
