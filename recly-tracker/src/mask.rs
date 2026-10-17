//! Sistema de máscara: port fiel de `domain/LayerMask.kt`, `Easing`/`CubicBezier` e do contrato
//! GLSL `LayerMaskShader.reclyLayerMaskAlpha`, para que a mesma máscara produza o mesmo resultado
//! no app (GPU) e aqui (CPU).
//!
//! Espaço da máscara: camada não transformada, 0..1, com y crescendo para baixo.

use crate::track::{check, valid_id, TrackingTrack, ValidationError};

pub const MAX_MASK_PATH_POINTS: usize = 12;
pub const MAX_MASK_KEYFRAMES: usize = 200;

/// Formas suportadas. A ordem é a mesma do enum Kotlin (o ordinal vai para o shader e o codec).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MaskType {
    Rectangle,
    Circle,
    Ellipse,
    Linear,
    Mirror,
    Heart,
    Star,
    CustomPath,
}

impl MaskType {
    pub const ALL: [MaskType; 8] = [
        MaskType::Rectangle,
        MaskType::Circle,
        MaskType::Ellipse,
        MaskType::Linear,
        MaskType::Mirror,
        MaskType::Heart,
        MaskType::Star,
        MaskType::CustomPath,
    ];

    pub fn ordinal(self) -> i32 {
        Self::ALL.iter().position(|t| *t == self).unwrap() as i32
    }

    pub fn from_ordinal(i: i32) -> Option<Self> {
        Self::ALL.get(usize::try_from(i).ok()?).copied()
    }

    pub fn label(self) -> &'static str {
        match self {
            MaskType::Rectangle => "Retângulo",
            MaskType::Circle => "Círculo",
            MaskType::Ellipse => "Elipse",
            MaskType::Linear => "Linear",
            MaskType::Mirror => "Espelho",
            MaskType::Heart => "Coração",
            MaskType::Star => "Estrela",
            MaskType::CustomPath => "Caminho personalizado",
        }
    }
}

/// Curva Bézier cúbica de easing (mesmos padrões do Kotlin).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CubicBezier {
    pub x1: f32,
    pub y1: f32,
    pub x2: f32,
    pub y2: f32,
}

impl Default for CubicBezier {
    fn default() -> Self {
        Self { x1: 0.25, y1: 0.1, x2: 0.25, y2: 1.0 }
    }
}

impl CubicBezier {
    pub fn validate(&self) -> Result<(), ValidationError> {
        check(
            (0.0..=1.0).contains(&self.x1) && (0.0..=1.0).contains(&self.x2) && (-2.0..=3.0).contains(&self.y1) && (-2.0..=3.0).contains(&self.y2),
            "bezier inválida",
        )
    }

    pub fn apply(&self, value: f32) -> f32 {
        let x = value.clamp(0.0, 1.0);
        if x == 0.0 || x == 1.0 {
            return x;
        }
        let coordinate = |t: f32, a: f32, b: f32| {
            let u = 1.0 - t;
            3.0 * u * u * t * a + 3.0 * u * t * t * b + t * t * t
        };
        let (mut low, mut high) = (0.0f32, 1.0f32);
        for _ in 0..24 {
            let mid = (low + high) * 0.5;
            if coordinate(mid, self.x1, self.x2) < x {
                low = mid
            } else {
                high = mid
            }
        }
        coordinate((low + high) * 0.5, self.y1, self.y2)
    }
}

/// Curvas de easing (mesma ordem e fórmulas do enum `Easing` em `Studio.kt`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Easing {
    Linear,
    #[default]
    Smooth,
    EaseIn,
    EaseOut,
    Hold,
    EaseInOut,
    Bezier,
    Spring,
    Bounce,
    Elastic,
    Back,
}

impl Easing {
    pub const ALL: [Easing; 11] = [
        Easing::Linear,
        Easing::Smooth,
        Easing::EaseIn,
        Easing::EaseOut,
        Easing::Hold,
        Easing::EaseInOut,
        Easing::Bezier,
        Easing::Spring,
        Easing::Bounce,
        Easing::Elastic,
        Easing::Back,
    ];

    pub fn ordinal(self) -> i32 {
        Self::ALL.iter().position(|e| *e == self).unwrap() as i32
    }

    pub fn from_ordinal(i: i32) -> Option<Self> {
        Self::ALL.get(usize::try_from(i).ok()?).copied()
    }

    pub fn apply(self, value: f32, bezier: &CubicBezier) -> f32 {
        let t = value.clamp(0.0, 1.0);
        if t == 0.0 || t == 1.0 {
            return t;
        }
        match self {
            Easing::Linear => t,
            Easing::Smooth => t * t * (3.0 - 2.0 * t),
            Easing::EaseIn => t * t,
            Easing::EaseOut => 1.0 - (1.0 - t) * (1.0 - t),
            Easing::Hold => {
                if t >= 1.0 {
                    1.0
                } else {
                    0.0
                }
            }
            Easing::EaseInOut => {
                if t < 0.5 {
                    2.0 * t * t
                } else {
                    1.0 - (-2.0 * t + 2.0) * (-2.0 * t + 2.0) / 2.0
                }
            }
            Easing::Bezier => bezier.apply(t),
            Easing::Spring => {
                let response = |x: f64| 1.0 - (-7.0 * x).exp() * ((12.0 * x).cos() + 7.0 / 12.0 * (12.0 * x).sin());
                (response(t as f64) / response(1.0)) as f32
            }
            Easing::Bounce => {
                let (n, d) = (7.5625f32, 2.75f32);
                if t < 1.0 / d {
                    n * t * t
                } else if t < 2.0 / d {
                    let u = t - 1.5 / d;
                    n * u * u + 0.75
                } else if t < 2.5 / d {
                    let u = t - 2.25 / d;
                    n * u * u + 0.9375
                } else {
                    let u = t - 2.625 / d;
                    n * u * u + 0.984375
                }
            }
            Easing::Elastic => {
                let t = t as f64;
                (2f64.powf(-10.0 * t) * ((t * 10.0 - 0.75) * (2.0 * std::f64::consts::PI / 3.0)).sin() + 1.0) as f32
            }
            Easing::Back => {
                let u = t - 1.0;
                1.0 + 2.70158 * u * u * u + 1.70158 * u * u
            }
        }
    }
}

/// Ponto de controle normalizado de um caminho personalizado.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MaskPathPoint {
    pub x: f32,
    pub y: f32,
}

/// Valores de um keyframe de máscara.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MaskKeyframe {
    pub time_us: i64,
    pub center_x: f32,
    pub center_y: f32,
    pub width: f32,
    pub height: f32,
    pub rotation: f32,
    pub feather: f32,
    pub expansion: f32,
    pub easing: Easing,
    pub bezier: CubicBezier,
}

impl MaskKeyframe {
    pub fn from_state(time_us: i64, s: &MaskState, easing: Easing) -> Self {
        Self {
            time_us,
            center_x: s.center_x,
            center_y: s.center_y,
            width: s.width,
            height: s.height,
            rotation: s.rotation,
            feather: s.feather,
            expansion: s.expansion,
            easing,
            bezier: CubicBezier::default(),
        }
    }
}

/// Máscara serializável e independente de resolução (igual a `MaskState` do Kotlin).
#[derive(Debug, Clone, PartialEq)]
pub struct MaskState {
    pub mask_type: MaskType,
    pub center_x: f32,
    pub center_y: f32,
    pub width: f32,
    pub height: f32,
    pub rotation: f32,
    pub feather: f32,
    pub expansion: f32,
    pub opacity: f32,
    pub inverted: bool,
    pub custom_path: Vec<MaskPathPoint>,
    pub keyframes: Vec<MaskKeyframe>,
    pub tracking_track_id: Option<String>,
}

pub const DEFAULT_CUSTOM_PATH: [MaskPathPoint; 6] = [
    MaskPathPoint { x: 0.18, y: 0.18 },
    MaskPathPoint { x: 0.82, y: 0.18 },
    MaskPathPoint { x: 0.9, y: 0.5 },
    MaskPathPoint { x: 0.72, y: 0.85 },
    MaskPathPoint { x: 0.28, y: 0.85 },
    MaskPathPoint { x: 0.1, y: 0.5 },
];

fn validate_geometry(cx: f32, cy: f32, w: f32, h: f32, rot: f32, feather: f32, expansion: f32) -> Result<(), ValidationError> {
    check([cx, cy, w, h, rot, feather, expansion].iter().all(|v| v.is_finite()), "valor não finito")?;
    check((0.0..=1.0).contains(&cx) && (0.0..=1.0).contains(&cy), "centro fora de 0..1")?;
    check((0.01..=2.0).contains(&w) && (0.01..=2.0).contains(&h), "tamanho fora de 0.01..2")?;
    check((-180.0..=180.0).contains(&rot), "rotação fora de -180..180")?;
    check((0.0..=0.5).contains(&feather), "feather fora de 0..0.5")?;
    check((-1.0..=1.0).contains(&expansion), "expansão fora de -1..1")
}

fn mix_angle(left: f32, right: f32, fraction: f32) -> f32 {
    let mut delta = (right - left) % 360.0;
    if delta > 180.0 {
        delta -= 360.0
    }
    if delta < -180.0 {
        delta += 360.0
    }
    let value = left + delta * fraction;
    if value > 180.0 {
        value - 360.0
    } else if value < -180.0 {
        value + 360.0
    } else {
        value
    }
}

impl MaskState {
    pub fn default_for(mask_type: MaskType) -> Self {
        Self {
            mask_type,
            center_x: 0.5,
            center_y: 0.5,
            width: 0.75,
            height: 0.75,
            rotation: 0.0,
            feather: 0.04,
            expansion: 0.0,
            opacity: 1.0,
            inverted: false,
            custom_path: if mask_type == MaskType::CustomPath { DEFAULT_CUSTOM_PATH.to_vec() } else { Vec::new() },
            keyframes: Vec::new(),
            tracking_track_id: None,
        }
    }

    pub fn validate(&self) -> Result<(), ValidationError> {
        validate_geometry(self.center_x, self.center_y, self.width, self.height, self.rotation, self.feather, self.expansion)?;
        check(self.opacity.is_finite() && (0.0..=1.0).contains(&self.opacity), "opacidade fora de 0..1")?;
        check(self.custom_path.len() <= MAX_MASK_PATH_POINTS, "caminho com pontos demais")?;
        check(
            self.custom_path.iter().all(|p| p.x.is_finite() && p.y.is_finite() && (0.0..=1.0).contains(&p.x) && (0.0..=1.0).contains(&p.y)),
            "ponto de caminho fora de 0..1",
        )?;
        check(self.keyframes.len() <= MAX_MASK_KEYFRAMES, "keyframes demais")?;
        check(self.keyframes.windows(2).all(|w| w[0].time_us < w[1].time_us), "keyframes fora de ordem")?;
        for k in &self.keyframes {
            check(k.time_us >= 0, "keyframe com tempo negativo")?;
            validate_geometry(k.center_x, k.center_y, k.width, k.height, k.rotation, k.feather, k.expansion)?;
            k.bezier.validate()?;
        }
        check(self.tracking_track_id.as_deref().is_none_or(valid_id), "id de track inválido")
    }

    /// Estado avaliado no tempo `time_us`: keyframes com easing e, se houver, deslocamento
    /// relativo ao track (o primeiro ponto do track é a âncora).
    pub fn at(&self, time_us: i64, tracking: Option<&TrackingTrack>) -> MaskState {
        let t = time_us.max(0);
        let mut animated = self.clone();
        if !self.keyframes.is_empty() {
            let upper = self.keyframes.partition_point(|k| k.time_us <= t);
            let k = if upper == 0 {
                self.keyframes[0]
            } else if upper == self.keyframes.len() {
                *self.keyframes.last().unwrap()
            } else {
                let left = self.keyframes[upper - 1];
                let right = self.keyframes[upper];
                let span = (right.time_us - left.time_us).max(1);
                let f = left.easing.apply((time_us - left.time_us) as f32 / span as f32, &left.bezier);
                let mix = |a: f32, b: f32| a + (b - a) * f;
                MaskKeyframe {
                    time_us,
                    center_x: mix(left.center_x, right.center_x),
                    center_y: mix(left.center_y, right.center_y),
                    width: mix(left.width, right.width),
                    height: mix(left.height, right.height),
                    rotation: mix_angle(left.rotation, right.rotation, f),
                    feather: mix(left.feather, right.feather),
                    expansion: mix(left.expansion, right.expansion),
                    easing: left.easing,
                    bezier: left.bezier,
                }
            };
            animated.center_x = k.center_x;
            animated.center_y = k.center_y;
            animated.width = k.width;
            animated.height = k.height;
            animated.rotation = k.rotation;
            animated.feather = k.feather;
            animated.expansion = k.expansion;
            animated.keyframes = Vec::new();
        }
        let Some(track) = tracking else { return animated };
        let point = track.at(time_us);
        let anchor = track.points[0];
        let sx = point.width / anchor.width.max(0.001);
        let sy = point.height / anchor.height.max(0.001);
        animated.center_x = (animated.center_x + point.center_x - anchor.center_x).clamp(0.0, 1.0);
        animated.center_y = (animated.center_y + point.center_y - anchor.center_y).clamp(0.0, 1.0);
        animated.width = (animated.width * sx).clamp(0.01, 2.0);
        animated.height = (animated.height * sy).clamp(0.01, 2.0);
        animated
    }

    pub fn upsert_keyframe(&self, time_us: i64, values: Option<&MaskState>, tolerance_us: i64) -> MaskState {
        let target = time_us.max(0);
        let evaluated;
        let values = match values {
            Some(v) => v,
            None => {
                evaluated = self.at(target, None);
                &evaluated
            }
        };
        let replacement = MaskKeyframe::from_state(target, values, Easing::Smooth);
        let mut keyframes = self.keyframes.clone();
        if let Some(i) = keyframes.iter().position(|k| (k.time_us - target).abs() <= tolerance_us) {
            let old = keyframes[i];
            keyframes[i] = MaskKeyframe { easing: old.easing, bezier: old.bezier, ..replacement };
        } else {
            keyframes.push(replacement);
        }
        keyframes.sort_by_key(|k| k.time_us);
        MaskState { keyframes, ..self.clone() }
    }

    pub fn remove_keyframe(&self, time_us: i64, tolerance_us: i64) -> MaskState {
        let keyframes = self.keyframes.iter().copied().filter(|k| (k.time_us - time_us).abs() > tolerance_us).collect();
        MaskState { keyframes, ..self.clone() }
    }

    /// Distância com sinal até a borda da forma (negativa dentro), nas unidades do shader
    /// (y de 0..1 cobre a altura da camada). Ignora inversão e opacidade.
    pub fn signed_distance(&self, u: f32, v: f32, aspect: f32) -> f32 {
        let aspect = aspect.max(0.01);
        let short_side = aspect.min(1.0);
        let mut px = (u - self.center_x) * aspect;
        let mut py = v - self.center_y;
        let r = self.rotation.to_radians();
        let (s, c) = r.sin_cos();
        // mat2(c, -s, s, c) * p (GLSL, colunas)
        let (rx, ry) = (c * px + s * py, -s * px + c * py);
        px = rx;
        py = ry;
        let hx = (self.width * aspect * 0.5 + self.expansion * short_side).max(0.002);
        let hy = (self.height * 0.5 + self.expansion * short_side).max(0.002);
        match self.mask_type {
            MaskType::Rectangle => sd_box(px, py, hx, hy),
            MaskType::Circle => (px * px + py * py).sqrt() - hx.min(hy),
            MaskType::Ellipse => (((px / hx).powi(2) + (py / hy).powi(2)).sqrt() - 1.0) * hx.min(hy),
            MaskType::Linear => (px.abs() - hx).max(py - hy),
            MaskType::Mirror => px.abs() - hx,
            MaskType::Heart => {
                let nx = 0.604 * px / hx;
                let ny = 1.104 * (0.5 - 0.5 * py / hy);
                sd_heart(nx, ny) * (hx / 0.604).min(hy * 2.0 / 1.104)
            }
            MaskType::Star => sd_star(px / hx, py / hy) * hx.min(hy),
            MaskType::CustomPath => {
                let n = self.custom_path.len().min(MAX_MASK_PATH_POINTS);
                if n >= 3 {
                    let nx = px / (hx * 2.0) + 0.5;
                    let ny = py / (hy * 2.0) + 0.5;
                    sd_polygon(nx, ny, &self.custom_path[..n]) * hx.min(hy) * 2.0
                } else {
                    sd_box(px, py, hx, hy)
                }
            }
        }
    }

    /// Cobertura da forma em 0..1 com feather e inversão, sem opacidade.
    /// `min_feather` (unidades do shader) suaviza a borda mesmo com feather 0 (anti-aliasing).
    pub fn shape_alpha(&self, u: f32, v: f32, aspect: f32, min_feather: f32) -> f32 {
        let short_side = aspect.max(0.01).min(1.0);
        let feather = (self.feather * short_side).max(0.0001).max(min_feather);
        let d = self.signed_distance(u, v, aspect);
        let a = 1.0 - smoothstep(-feather, feather, d);
        if self.inverted {
            1.0 - a
        } else {
            a
        }
    }

    /// Alpha da camada exatamente como `reclyLayerMaskAlpha` (GLSL): 1 = camada visível.
    pub fn layer_alpha(&self, u: f32, v: f32, aspect: f32) -> f32 {
        let shape = self.shape_alpha(u, v, aspect, 0.0);
        1.0 + (shape - 1.0) * self.opacity.clamp(0.0, 1.0)
    }

    /// Rasteriza a cobertura da forma (com inversão, sem opacidade) para `w × h`.
    /// `aa` ativa anti-aliasing de meio pixel.
    pub fn rasterize_shape(&self, w: usize, h: usize, aa: bool) -> Vec<f32> {
        let aspect = w as f32 / h as f32;
        let min_feather = if aa { 0.75 / h as f32 } else { 0.0 };
        let mut out = vec![0.0f32; w * h];
        crate::par::rows_mut(&mut out, w, 40, |y, row| {
            let v = (y as f32 + 0.5) / h as f32;
            for (x, a) in row.iter_mut().enumerate() {
                *a = self.shape_alpha((x as f32 + 0.5) / w as f32, v, aspect, min_feather);
            }
        });
        out
    }
}

#[inline]
fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[inline]
fn glsl_sign(x: f32) -> f32 {
    if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        0.0
    }
}

fn sd_box(px: f32, py: f32, hx: f32, hy: f32) -> f32 {
    let dx = px.abs() - hx;
    let dy = py.abs() - hy;
    let ox = dx.max(0.0);
    let oy = dy.max(0.0);
    (ox * ox + oy * oy).sqrt() + dx.max(dy).min(0.0)
}

fn sd_heart(px: f32, py: f32) -> f32 {
    let px = px.abs();
    if px + py > 1.0 {
        let (dx, dy) = (px - 0.25, py - 0.75);
        return (dx * dx + dy * dy).sqrt() - 0.353553;
    }
    let (ax, ay) = (px, py - 1.0);
    let m = 0.5 * (px + py).max(0.0);
    let (bx, by) = (px - m, py - m);
    (ax * ax + ay * ay).min(bx * bx + by * by).sqrt() * glsl_sign(px - py)
}

fn sd_star(px: f32, py: f32) -> f32 {
    let angle = px.atan2(-py);
    let ray = (angle * 5.0).cos();
    let boundary = 0.43 + (1.0 - 0.43) * smoothstep(-0.35, 0.75, ray);
    (px * px + py * py).sqrt() - boundary
}

fn sd_polygon(px: f32, py: f32, points: &[MaskPathPoint]) -> f32 {
    let mut distance_sq = 100.0f32;
    let mut inside = false;
    let n = points.len();
    for i in 0..n {
        let a = points[i];
        let b = points[(i + 1) % n];
        let (ex, ey) = (b.x - a.x, b.y - a.y);
        let (ox, oy) = (px - a.x, py - a.y);
        let t = ((ox * ex + oy * ey) / (ex * ex + ey * ey).max(0.000001)).clamp(0.0, 1.0);
        let (nx, ny) = (ox - ex * t, oy - ey * t);
        distance_sq = distance_sq.min(nx * nx + ny * ny);
        let crosses = (a.y > py) != (b.y > py);
        if crosses && px < (b.x - a.x) * (py - a.y) / (b.y - a.y) + a.x {
            inside = !inside;
        }
    }
    distance_sq.sqrt() * if inside { -1.0 } else { 1.0 }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::track::TrackingPoint;

    fn mask(t: MaskType) -> MaskState {
        MaskState { feather: 0.0, ..MaskState::default_for(t) }
    }

    #[test]
    fn every_shape_is_opaque_at_center_and_transparent_at_corner() {
        for t in MaskType::ALL {
            let m = mask(t);
            let center_v = if t == MaskType::Heart { 0.55 } else { 0.5 };
            assert!(m.layer_alpha(0.5, center_v, 16.0 / 9.0) > 0.99, "{t:?} centro");
            assert!(m.layer_alpha(0.01, 0.99, 16.0 / 9.0) < 0.01 || t == MaskType::Mirror, "{t:?} canto");
        }
    }

    #[test]
    fn inversion_and_opacity_follow_shader_contract() {
        let mut m = mask(MaskType::Ellipse);
        m.inverted = true;
        assert!(m.layer_alpha(0.5, 0.5, 1.0) < 0.01);
        assert!(m.layer_alpha(0.01, 0.01, 1.0) > 0.99);
        m.opacity = 0.0;
        assert!((m.layer_alpha(0.5, 0.5, 1.0) - 1.0).abs() < 1e-6, "opacidade 0 desliga a máscara");
        m.opacity = 0.5;
        assert!((m.layer_alpha(0.5, 0.5, 1.0) - 0.5).abs() < 1e-3);
    }

    #[test]
    fn circle_stays_round_on_wide_layers() {
        let m = mask(MaskType::Circle);
        let aspect = 16.0 / 9.0;
        // Raio = min(meias-dimensões) = 0.375 da altura. Em pixels: dx = 0.375/aspect em u.
        let r_u = 0.375 / aspect;
        assert!(m.layer_alpha(0.5 + r_u * 0.95, 0.5, aspect) > 0.5);
        assert!(m.layer_alpha(0.5 + r_u * 1.05, 0.5, aspect) < 0.5);
        assert!(m.layer_alpha(0.5, 0.5 + 0.375 * 0.95, aspect) > 0.5);
        assert!(m.layer_alpha(0.5, 0.5 + 0.375 * 1.05, aspect) < 0.5);
    }

    #[test]
    fn rotation_by_ninety_swaps_rectangle_axes_on_square_layer() {
        let mut m = mask(MaskType::Rectangle);
        m.width = 0.8;
        m.height = 0.2;
        assert!(m.layer_alpha(0.85, 0.5, 1.0) > 0.5);
        m.rotation = 90.0;
        assert!(m.layer_alpha(0.85, 0.5, 1.0) < 0.5);
        assert!(m.layer_alpha(0.5, 0.85, 1.0) > 0.5);
    }

    #[test]
    fn star_points_up() {
        let m = mask(MaskType::Star);
        assert!(m.layer_alpha(0.5, 0.5 - 0.375 * 0.9, 1.0) > 0.5, "ponta para cima");
        assert!(m.layer_alpha(0.5, 0.5 + 0.375 * 0.9, 1.0) < 0.5, "vale embaixo");
    }

    #[test]
    fn custom_path_uses_polygon() {
        let mut m = mask(MaskType::CustomPath);
        m.custom_path = vec![MaskPathPoint { x: 0.0, y: 0.0 }, MaskPathPoint { x: 1.0, y: 0.0 }, MaskPathPoint { x: 0.0, y: 1.0 }];
        m.width = 1.0;
        m.height = 1.0;
        assert!(m.layer_alpha(0.25, 0.25, 1.0) > 0.99);
        assert!(m.layer_alpha(0.8, 0.8, 1.0) < 0.01);
    }

    #[test]
    fn keyframes_interpolate_with_easing_and_angle_wrap() {
        let base = MaskState::default_for(MaskType::Rectangle);
        let a = MaskState { center_x: 0.2, rotation: 170.0, ..base.clone() };
        let b = MaskState { center_x: 0.8, rotation: -170.0, ..base.clone() };
        let m = base.upsert_keyframe(0, Some(&a), 25_000).upsert_keyframe(1_000_000, Some(&b), 25_000);
        let mid = m.at(500_000, None);
        assert!((mid.center_x - 0.5).abs() < 1e-4, "smooth em 0.5 = 0.5");
        assert!(mid.rotation.abs() > 179.0, "gira pelo caminho curto: {}", mid.rotation);
        let q = m.at(250_000, None);
        assert!((q.center_x - (0.2 + 0.6 * 0.15625)).abs() < 1e-4);
        assert_eq!(m.remove_keyframe(1_000_000, 25_000).keyframes.len(), 1);
    }

    #[test]
    fn tracking_moves_mask_relative_to_anchor() {
        let base = MaskState { center_x: 0.3, center_y: 0.4, width: 0.2, height: 0.2, ..MaskState::default_for(MaskType::Ellipse) };
        let p = |t, x, w| TrackingPoint { time_us: t, center_x: x, center_y: 0.5, width: w, height: w, confidence: 1.0 };
        let track = TrackingTrack::new("t", vec![p(0, 0.5, 0.1), p(1_000_000, 0.7, 0.2)]).unwrap();
        let m = base.at(1_000_000, Some(&track));
        assert!((m.center_x - 0.5).abs() < 1e-5);
        assert!((m.center_y - 0.4).abs() < 1e-5);
        assert!((m.width - 0.4).abs() < 1e-5);
    }

    #[test]
    fn easing_curves_match_kotlin_values() {
        let b = CubicBezier::default();
        assert!((Easing::Smooth.apply(0.25, &b) - 0.15625).abs() < 1e-6);
        assert!((Easing::EaseInOut.apply(0.75, &b) - 0.875).abs() < 1e-6);
        assert!((Easing::Bounce.apply(0.5, &b) - 0.765625).abs() < 1e-5);
        assert!((Easing::Spring.apply(1.0, &b) - 1.0).abs() < 1e-6);
        assert_eq!(Easing::Hold.apply(0.99, &b), 0.0);
    }

}
