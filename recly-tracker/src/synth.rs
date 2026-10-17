//! Cenas sintéticas com verdade de referência, para testar e comparar rastreadores.
//! Cada cena isola uma dificuldade real: movimento rápido, luz, escala, oclusão, distrator,
//! ruído, câmera tremida — e uma combinação de todas.

use crate::track::{TrackingPoint, TrackingRegion};
use crate::par;
use std::f32::consts::TAU;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SceneKind {
    FastMotion,
    Lighting,
    Scale,
    Occlusion,
    Distractor,
    Noise,
    CameraShake,
    Combo,
}

impl SceneKind {
    pub const ALL: [SceneKind; 8] = [
        SceneKind::FastMotion,
        SceneKind::Lighting,
        SceneKind::Scale,
        SceneKind::Occlusion,
        SceneKind::Distractor,
        SceneKind::Noise,
        SceneKind::CameraShake,
        SceneKind::Combo,
    ];

    pub fn name(self) -> &'static str {
        match self {
            SceneKind::FastMotion => "movimento rápido",
            SceneKind::Lighting => "mudança de luz",
            SceneKind::Scale => "mudança de escala",
            SceneKind::Occlusion => "oclusão total",
            SceneKind::Distractor => "objeto idêntico",
            SceneKind::Noise => "ruído forte",
            SceneKind::CameraShake => "câmera tremida",
            SceneKind::Combo => "tudo junto",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        Some(match s {
            "fast" | "rapido" => SceneKind::FastMotion,
            "lighting" | "luz" => SceneKind::Lighting,
            "scale" | "escala" => SceneKind::Scale,
            "occlusion" | "oclusao" => SceneKind::Occlusion,
            "distractor" | "distrator" => SceneKind::Distractor,
            "noise" | "ruido" => SceneKind::Noise,
            "shake" | "tremida" => SceneKind::CameraShake,
            "combo" | "tudo" => SceneKind::Combo,
            _ => return None,
        })
    }
}

fn hash(ix: i32, iy: i32, seed: u32) -> f32 {
    let mut h = (ix as u32).wrapping_mul(0x8da6_b343) ^ (iy as u32).wrapping_mul(0xd816_3841) ^ seed.wrapping_mul(0xcb1a_b31f);
    h ^= h >> 13;
    h = h.wrapping_mul(0x5bd1_e995);
    h ^= h >> 15;
    (h & 0xffffff) as f32 / 0x1000000 as f32
}

fn value_noise(x: f32, y: f32, seed: u32) -> f32 {
    let (x0, y0) = (x.floor(), y.floor());
    let (fx, fy) = (x - x0, y - y0);
    let (sx, sy) = (fx * fx * (3.0 - 2.0 * fx), fy * fy * (3.0 - 2.0 * fy));
    let (ix, iy) = (x0 as i32, y0 as i32);
    let a = hash(ix, iy, seed);
    let b = hash(ix + 1, iy, seed);
    let c = hash(ix, iy + 1, seed);
    let d = hash(ix + 1, iy + 1, seed);
    let top = a + (b - a) * sx;
    let bottom = c + (d - c) * sx;
    top + (bottom - top) * sy
}

fn fbm(x: f32, y: f32, seed: u32) -> f32 {
    let mut sum = 0.0;
    let mut amp = 0.5;
    let mut freq = 1.0;
    for o in 0..4 {
        sum += amp * value_noise(x * freq, y * freq, seed.wrapping_add(o * 1013));
        amp *= 0.5;
        freq *= 2.1;
    }
    sum / 0.9375
}

struct Texture {
    w: usize,
    h: usize,
    rgb: Vec<[f32; 3]>,
}

impl Texture {
    fn generate(w: usize, h: usize, scale: f32, seed: u32, color: impl Fn(f32, f32, f32, f32, f32) -> [f32; 3] + Sync) -> Self {
        let rgb = par::map(w * h, 60, |i| {
            let (x, y) = ((i % w) as f32, (i / w) as f32);
            let n1 = fbm(x / scale, y / scale, seed);
            let n2 = fbm(x / scale, y / scale, seed + 7);
            let n3 = fbm(x / scale, y / scale, seed + 13);
            color(n1, n2, n3, x / w as f32, y / h as f32)
        });
        Self { w, h, rgb }
    }

    fn sample(&self, x: f32, y: f32) -> [f32; 3] {
        let fx = (x - 0.5).clamp(0.0, (self.w - 1) as f32);
        let fy = (y - 0.5).clamp(0.0, (self.h - 1) as f32);
        let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.w - 1), (y0 + 1).min(self.h - 1));
        let (ax, ay) = (fx - x0 as f32, fy - y0 as f32);
        let p = |x, y| self.rgb[y * self.w + x];
        let (a, b, c, d) = (p(x0, y0), p(x1, y0), p(x0, y1), p(x1, y1));
        std::array::from_fn(|i| {
            let top = a[i] + (b[i] - a[i]) * ax;
            let bottom = c[i] + (d[i] - c[i]) * ax;
            top + (bottom - top) * ay
        })
    }
}

/// Caixa verdadeira em pixels.
#[derive(Clone, Copy, Debug)]
pub struct Truth {
    pub cx: f32,
    pub cy: f32,
    pub w: f32,
    pub h: f32,
    /// Fração visível do alvo (0 = totalmente oculto).
    pub visible: f32,
}

pub struct Scene {
    pub kind: SceneKind,
    pub width: usize,
    pub height: usize,
    pub frames: usize,
    pub fps: f64,
    margin: usize,
    background: Texture,
    target: Texture,
    occluder: Texture,
    seed: u32,
}

const MARGIN: usize = 96;

impl Scene {
    pub fn new(kind: SceneKind, width: usize, height: usize, frames: usize, seed: u32) -> Self {
        let background = Texture::generate(width + 2 * MARGIN, height + 2 * MARGIN, 18.0, seed, |a, b, c, _, _| {
            [0.25 + 0.5 * a, 0.3 + 0.45 * b, 0.35 + 0.45 * c]
        });
        let target = Texture::generate(96, 96, 9.0, seed + 101, |a, b, c, x, y| {
            let stripes = 0.5 + 0.5 * ((x * 3.0 + y * 2.0) * TAU).sin();
            [0.5 + 0.4 * a, 0.22 + 0.35 * stripes * b, 0.15 + 0.35 * c]
        });
        let occluder = Texture::generate(128, 256, 14.0, seed + 211, |a, b, c, _, _| [0.2 + 0.5 * a, 0.25 + 0.5 * b, 0.3 + 0.4 * c]);
        Self { kind, width, height, frames, fps: 30.0, margin: MARGIN, background, target, occluder, seed }
    }

    fn base_size(&self) -> (f32, f32) {
        let (w, h) = (self.width as f32, self.height as f32);
        match self.kind {
            SceneKind::Scale => (0.1 * w, 0.13 * h),
            _ => (0.13 * w, 0.17 * h),
        }
    }

    fn camera(&self, i: usize) -> (f32, f32) {
        match self.kind {
            SceneKind::CameraShake | SceneKind::Combo => {
                let amp = if self.kind == SceneKind::CameraShake { 14.0 } else { 6.0 };
                let jx = (hash(i as i32, 3, self.seed) - 0.5) * 2.0 * amp;
                let jy = (hash(i as i32, 5, self.seed) - 0.5) * 2.0 * amp;
                let drift = (i as f32 * 0.35).min(self.margin as f32 * 0.5);
                (jx + drift, jy)
            }
            _ => (0.0, 0.0),
        }
    }

    fn path(&self, i: usize) -> (f32, f32) {
        let t = i as f32 / self.frames.max(1) as f32;
        let (w, h) = (self.width as f32, self.height as f32);
        let (fx, fy) = match self.kind {
            SceneKind::FastMotion => (3.6, 2.7),
            SceneKind::Combo => (2.2, 1.7),
            SceneKind::Occlusion => (0.0, 0.0),
            _ => (1.3, 0.9),
        };
        if self.kind == SceneKind::Occlusion {
            // Movimento constante para a esquerda, passando por trás da barra.
            return (w * (0.78 - 0.5 * t), h * (0.5 + 0.1 * (t * TAU).sin()));
        }
        (w * (0.5 + 0.3 * (TAU * fx * t).sin()), h * (0.5 + 0.25 * (TAU * fy * t + 1.0).sin()))
    }

    fn scale(&self, i: usize) -> f32 {
        let t = i as f32 / self.frames.max(1) as f32;
        match self.kind {
            SceneKind::Scale => 1.0 + 1.2 * (std::f32::consts::PI * t).sin(),
            SceneKind::Combo => 1.0 + 0.4 * (TAU * t).sin(),
            _ => 1.0,
        }
    }

    /// Barra oclusora: (x esquerda, largura) em pixels, se existir.
    fn occluder_at(&self, i: usize) -> Option<(f32, f32)> {
        let t = i as f32 / self.frames.max(1) as f32;
        let w = self.width as f32;
        match self.kind {
            SceneKind::Occlusion => Some((w * (-0.35 + 1.3 * t), w * 0.22)),
            SceneKind::Combo => Some((w * (1.1 - 1.4 * t), w * 0.16)),
            _ => None,
        }
    }

    fn distractor_at(&self, i: usize) -> Option<(f32, f32)> {
        if self.kind != SceneKind::Distractor {
            return None;
        }
        let t = i as f32 / self.frames.max(1) as f32;
        let (w, h) = (self.width as f32, self.height as f32);
        Some((w * (0.5 + 0.3 * (TAU * 1.3 * t + 2.2).sin()), h * (0.5 + 0.25 * (TAU * 0.9 * t + 1.0 + 1.6).sin())))
    }

    fn lighting(&self, i: usize, x: f32) -> (f32, f32) {
        let t = i as f32 / self.frames.max(1) as f32;
        match self.kind {
            SceneKind::Lighting => {
                let gain = 0.45 + 0.75 * (0.5 + 0.5 * (TAU * 1.5 * t).sin());
                let grad = 0.25 * (x / self.width as f32 - 0.5) * (TAU * t).cos();
                (gain + grad, 0.08 * (TAU * 0.7 * t).sin())
            }
            SceneKind::Combo => (0.7 + 0.35 * (TAU * t).sin(), 0.0),
            _ => (1.0, 0.0),
        }
    }

    pub fn truth(&self, i: usize) -> Truth {
        let (cam_x, cam_y) = self.camera(i);
        let (px, py) = self.path(i);
        let s = self.scale(i);
        let (bw, bh) = self.base_size();
        // A câmera move o fundo; o alvo se move junto com ele (está "no mundo").
        let (cx, cy, w, h) = (px - cam_x, py - cam_y, bw * s, bh * s);
        let mut visible = 1.0;
        if let Some((ox, ow)) = self.occluder_at(i) {
            let left = (cx - w / 2.0).max(ox);
            let right = (cx + w / 2.0).min(ox + ow);
            visible = 1.0 - ((right - left).max(0.0) / w);
        }
        Truth { cx, cy, w, h, visible }
    }

    pub fn initial_region(&self) -> TrackingRegion {
        let t = self.truth(0);
        TrackingRegion::new(t.cx / self.width as f32, t.cy / self.height as f32, t.w / self.width as f32, t.h / self.height as f32).unwrap()
    }

    pub fn time_us(&self, i: usize) -> i64 {
        (i as f64 * 1e6 / self.fps).round() as i64
    }

    /// Quadro RGB24.
    pub fn render(&self, i: usize) -> Vec<u8> {
        let (w, h) = (self.width, self.height);
        let truth = self.truth(i);
        let (cam_x, cam_y) = self.camera(i);
        let occ = self.occluder_at(i);
        let distractor = self.distractor_at(i).map(|(dx, dy)| (dx - cam_x, dy - cam_y));
        let noise_amp = match self.kind {
            SceneKind::Noise => 0.16,
            SceneKind::Combo => 0.07,
            _ => 0.015,
        };
        let m = self.margin as f32;
        let mut out = vec![0u8; w * h * 3];
        par::rows_mut(&mut out, w * 3, 20, |y, row| {
            for x in 0..w {
                let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
                let mut c = self.background.sample(fx + m + cam_x, fy + m + cam_y);
                let paste = |cx: f32, cy: f32, bw: f32, bh: f32, c: &mut [f32; 3]| {
                    let u = (fx - (cx - bw / 2.0)) / bw;
                    let v = (fy - (cy - bh / 2.0)) / bh;
                    if (0.0..1.0).contains(&u) && (0.0..1.0).contains(&v) {
                        *c = self.target.sample(u * self.target.w as f32, v * self.target.h as f32);
                    }
                };
                if let Some((dx, dy)) = distractor {
                    paste(dx, dy, truth.w, truth.h, &mut c);
                }
                paste(truth.cx, truth.cy, truth.w, truth.h, &mut c);
                if let Some((ox, ow)) = occ {
                    if fx >= ox && fx < ox + ow {
                        c = self.occluder.sample((fx - ox) / ow * self.occluder.w as f32, fy / h as f32 * self.occluder.h as f32);
                    }
                }
                let (gain, offset) = self.lighting(i, fx);
                for k in 0..3 {
                    let n = (hash(x as i32 + (i as i32) * 7919, y as i32 * 3 + k as i32, self.seed + 999) - 0.5) * 2.0 * noise_amp;
                    let v = c[k] * gain + offset + n;
                    row[x * 3 + k] = (v.clamp(0.0, 1.0) * 255.0).round() as u8;
                }
            }
        });
        out
    }
}

/// Métricas de um rastreamento contra a verdade.
#[derive(Clone, Copy, Debug, Default)]
pub struct Score {
    /// Fração de quadros (com alvo ao menos 40% visível) com IoU ≥ 0.5.
    pub success: f32,
    pub mean_iou: f32,
    /// Erro médio do centro em pixels.
    pub center_error: f32,
    /// Fração de quadros no fim do vídeo (último 20%) com IoU ≥ 0.5: mede recuperação.
    pub end_success: f32,
}

pub fn iou(t: &Truth, p: &TrackingPoint, w: usize, h: usize) -> f32 {
    let (pw, ph) = (p.width * w as f32, p.height * h as f32);
    let (pcx, pcy) = (p.center_x * w as f32, p.center_y * h as f32);
    let ix = ((t.cx + t.w / 2.0).min(pcx + pw / 2.0) - (t.cx - t.w / 2.0).max(pcx - pw / 2.0)).max(0.0);
    let iy = ((t.cy + t.h / 2.0).min(pcy + ph / 2.0) - (t.cy - t.h / 2.0).max(pcy - ph / 2.0)).max(0.0);
    let inter = ix * iy;
    inter / (t.w * t.h + pw * ph - inter).max(1e-6)
}

pub fn evaluate(scene: &Scene, points: &[TrackingPoint]) -> Score {
    let mut ok = 0usize;
    let mut counted = 0usize;
    let mut iou_sum = 0.0;
    let mut err_sum = 0.0;
    let mut end_ok = 0usize;
    let mut end_n = 0usize;
    let tail = scene.frames * 4 / 5;
    for (i, p) in points.iter().enumerate().take(scene.frames) {
        let t = scene.truth(i);
        if t.visible < 0.4 {
            continue;
        }
        let v = iou(&t, p, scene.width, scene.height);
        counted += 1;
        iou_sum += v;
        err_sum += ((p.center_x * scene.width as f32 - t.cx).powi(2) + (p.center_y * scene.height as f32 - t.cy).powi(2)).sqrt();
        if v >= 0.5 {
            ok += 1;
        }
        if i >= tail {
            end_n += 1;
            if v >= 0.5 {
                end_ok += 1;
            }
        }
    }
    let n = counted.max(1) as f32;
    Score {
        success: ok as f32 / n,
        mean_iou: iou_sum / n,
        center_error: err_sum / n,
        end_success: end_ok as f32 / end_n.max(1) as f32,
    }
}
