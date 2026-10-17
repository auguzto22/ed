//! Port fiel do `TemplateMotionTracker.kt` atual do app, usado apenas como referência para
//! comparar com o novo rastreador (`examples/benchmark.rs` e testes). Mesmos parâmetros, mesmas
//! constantes e mesma estratégia de busca.

use crate::track::{TrackingPoint, TrackingRegion, TrackingTrack};

const MIN_CONFIDENCE: f32 = 0.35;
const ADAPT_CONFIDENCE: f32 = 0.6;
const ADAPT_RATE: f32 = 0.12;

struct Plane {
    width: usize,
    height: usize,
    luma: Vec<f32>,
}

impl Plane {
    fn of(rgb: &[u8], w: usize, h: usize) -> Self {
        let luma = rgb
            .chunks_exact(3)
            .map(|p| (0.2126 * p[0] as f32 + 0.7152 * p[1] as f32 + 0.0722 * p[2] as f32) / 255.0)
            .collect();
        Self { width: w, height: h, luma }
    }

    fn at(&self, x: f32, y: f32) -> f32 {
        // Kotlin roundToInt: arredonda meio para cima.
        let ix = ((x + 0.5).floor() as isize).clamp(0, self.width as isize - 1) as usize;
        let iy = ((y + 0.5).floor() as isize).clamp(0, self.height as isize - 1) as usize;
        self.luma[iy * self.width + ix]
    }
}

pub struct LegacyTracker {
    template_size: usize,
    search_fraction: f32,
    points: Vec<TrackingPoint>,
    original: Vec<f32>,
    template: Vec<f32>,
    cx: f32,
    cy: f32,
    w: f32,
    h: f32,
    vx: f32,
    vy: f32,
    previous_time: i64,
    frame_w: usize,
    frame_h: usize,
    lost: u32,
}

impl LegacyTracker {
    /// Mesmos parâmetros usados em `TrackingTools.kt` (templateSize = 24, searchFraction = 0.22).
    pub fn new(rgb: &[u8], w: usize, h: usize, time_us: i64, region: TrackingRegion) -> Self {
        Self::with_params(rgb, w, h, time_us, region, 24, 0.22)
    }

    pub fn with_params(rgb: &[u8], fw: usize, fh: usize, time_us: i64, region: TrackingRegion, template_size: usize, search_fraction: f32) -> Self {
        let plane = Plane::of(rgb, fw, fh);
        let w = (region.width * fw as f32).clamp(4.0, fw as f32);
        let h = (region.height * fh as f32).clamp(4.0, fh as f32);
        let cx = region.center_x * fw as f32;
        let cy = region.center_y * fh as f32;
        let mut s = Self {
            template_size,
            search_fraction,
            points: Vec::new(),
            original: Vec::new(),
            template: Vec::new(),
            cx,
            cy,
            w,
            h,
            vx: 0.0,
            vy: 0.0,
            previous_time: time_us,
            frame_w: fw,
            frame_h: fh,
            lost: 0,
        };
        s.original = s.sample(&plane, cx, cy, w, h);
        s.template = s.original.clone();
        s.add(time_us, 1.0);
        s
    }

    pub fn next(&mut self, rgb: &[u8], fw: usize, fh: usize, time_us: i64) -> Option<TrackingPoint> {
        if time_us <= self.previous_time {
            return None;
        }
        let plane = Plane::of(rgb, fw, fh);
        let pred_x = self.cx + self.vx;
        let pred_y = self.cy + self.vy;
        let radius_x = (plane.width as f32 * self.search_fraction).max(3.0);
        let radius_y = (plane.height as f32 * self.search_fraction).max(3.0);
        let mut best = (pred_x, pred_y, 1.0f32, f32::INFINITY);
        let (w, h) = (self.w, self.h);
        let test = |x: f32, y: f32, scale: f32, best: &mut (f32, f32, f32, f32), this: &Self| {
            let e = this
                .difference(&plane, x, y, w * scale, h * scale, &this.template)
                .min(this.difference(&plane, x, y, w * scale, h * scale, &this.original) * 1.08);
            if e < best.3 {
                *best = (x, y, scale, e);
            }
        };
        let mut step = (radius_x.min(radius_y) / 6.0).max(2.0);
        let mut y = pred_y - radius_y;
        while y <= pred_y + radius_y {
            let mut x = pred_x - radius_x;
            while x <= pred_x + radius_x {
                test(x, y, 1.0, &mut best, self);
                x += step;
            }
            y += step;
        }
        test(self.cx, self.cy, 1.0, &mut best, self);
        for _ in 0..3 {
            step = (step / 2.5).max(0.5);
            let (bx, by, bs) = (best.0, best.1, best.2);
            for dy in -2..=2 {
                for dx in -2..=2 {
                    test(bx + dx as f32 * step, by + dy as f32 * step, bs, &mut best, self);
                }
            }
            for s in [0.94f32, 0.97, 1.03, 1.06] {
                test(bx, by, (bs * s).clamp(0.3, 3.0), &mut best, self);
            }
        }
        let confidence = (1.0 - best.3 * 2.2).clamp(0.0, 1.0);
        if confidence >= MIN_CONFIDENCE {
            let nw = (self.w * best.2).clamp(4.0, plane.width as f32);
            let nh = (self.h * best.2).clamp(4.0, plane.height as f32);
            self.vx = self.vx * 0.5 + (best.0 - self.cx) * 0.5;
            self.vy = self.vy * 0.5 + (best.1 - self.cy) * 0.5;
            self.cx = best.0.clamp(nw / 2.0, plane.width as f32 - nw / 2.0);
            self.cy = best.1.clamp(nh / 2.0, plane.height as f32 - nh / 2.0);
            self.w = nw;
            self.h = nh;
            self.lost = 0;
            if confidence >= ADAPT_CONFIDENCE {
                let patch = self.sample(&plane, self.cx, self.cy, self.w, self.h);
                for (t, p) in self.template.iter_mut().zip(patch) {
                    *t = *t * (1.0 - ADAPT_RATE) + p * ADAPT_RATE;
                }
            }
        } else {
            self.lost += 1;
            self.vx *= 0.5;
            self.vy *= 0.5;
        }
        self.previous_time = time_us;
        let c = if self.lost > 0 { confidence.min(MIN_CONFIDENCE) } else { confidence };
        Some(self.add(time_us, c))
    }

    pub fn finish(self) -> TrackingTrack {
        TrackingTrack { id: "legacy".into(), points: self.points }
    }

    fn add(&mut self, t: i64, confidence: f32) -> TrackingPoint {
        let p = TrackingPoint {
            time_us: t,
            center_x: (self.cx / self.frame_w as f32).clamp(0.0, 1.0),
            center_y: (self.cy / self.frame_h as f32).clamp(0.0, 1.0),
            width: (self.w / self.frame_w as f32).clamp(0.01, 1.0),
            height: (self.h / self.frame_h as f32).clamp(0.01, 1.0),
            confidence,
        };
        self.points.push(p);
        p
    }

    fn sample(&self, plane: &Plane, cx: f32, cy: f32, w: f32, h: f32) -> Vec<f32> {
        let n = self.template_size;
        let mut values = vec![0.0f32; n * n];
        let mut sum = 0.0;
        for y in 0..n {
            for x in 0..n {
                let v = plane.at(cx - w / 2.0 + (x as f32 + 0.5) * w / n as f32, cy - h / 2.0 + (y as f32 + 0.5) * h / n as f32);
                values[y * n + x] = v;
                sum += v;
            }
        }
        let mean = sum / values.len() as f32;
        let mut spread = 0.0;
        for v in &mut values {
            *v -= mean;
            spread += v.abs();
        }
        let scale = 1.0 / (spread / values.len() as f32).max(0.02);
        values.iter_mut().for_each(|v| *v *= scale);
        values
    }

    fn difference(&self, plane: &Plane, cx: f32, cy: f32, w: f32, h: f32, template: &[f32]) -> f32 {
        let n = self.template_size;
        let mut patch = vec![0.0f32; n * n];
        let mut sum = 0.0;
        for y in 0..n {
            for x in 0..n {
                let v = plane.at(cx - w / 2.0 + (x as f32 + 0.5) * w / n as f32, cy - h / 2.0 + (y as f32 + 0.5) * h / n as f32);
                patch[y * n + x] = v;
                sum += v;
            }
        }
        let count = (n * n) as f32;
        let mean = sum / count;
        let spread: f32 = patch.iter().map(|v| (v - mean).abs()).sum();
        let scale = 1.0 / (spread / count).max(0.02);
        let total: f32 = patch.iter().zip(template).map(|(p, t)| ((p - mean) * scale - t).abs()).sum();
        total / count / 2.0
    }
}
