//! Tipos de rastreamento compatíveis com `domain/Tracking.kt` e `TemplateMotionTracker.kt`.
//!
//! Coordenadas normalizadas ao quadro de origem não transformado, com y crescendo para baixo
//! (o mesmo "espaço de máscara" do app). Os nomes dos campos JSON são os mesmos do Kotlin.

use std::fmt;

pub const MAX_POINTS: usize = 2_000;

#[derive(Debug, Clone, PartialEq)]
pub struct ValidationError(pub String);

impl fmt::Display for ValidationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ValidationError {}

pub(crate) fn check(cond: bool, msg: &str) -> Result<(), ValidationError> {
    if cond {
        Ok(())
    } else {
        Err(ValidationError(msg.to_string()))
    }
}

pub(crate) fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 80 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}

/// Seleção normalizada feita no preview.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TrackingRegion {
    pub center_x: f32,
    pub center_y: f32,
    pub width: f32,
    pub height: f32,
}

impl TrackingRegion {
    pub fn new(center_x: f32, center_y: f32, width: f32, height: f32) -> Result<Self, ValidationError> {
        let r = Self { center_x, center_y, width, height };
        r.validate()?;
        Ok(r)
    }

    pub fn validate(&self) -> Result<(), ValidationError> {
        let v = [self.center_x, self.center_y, self.width, self.height];
        check(v.iter().all(|x| x.is_finite()), "região com valor não finito")?;
        check((0.0..=1.0).contains(&self.center_x) && (0.0..=1.0).contains(&self.center_y), "centro fora de 0..1")?;
        check((0.01..=1.0).contains(&self.width) && (0.01..=1.0).contains(&self.height), "tamanho fora de 0.01..1")
    }

    /// A partir de uma caixa em pixels (canto superior esquerdo + tamanho).
    pub fn from_pixels(x: f32, y: f32, w: f32, h: f32, frame_w: usize, frame_h: usize) -> Result<Self, ValidationError> {
        let fw = frame_w as f32;
        let fh = frame_h as f32;
        Self::new(
            ((x + w / 2.0) / fw).clamp(0.0, 1.0),
            ((y + h / 2.0) / fh).clamp(0.0, 1.0),
            (w / fw).clamp(0.01, 1.0),
            (h / fh).clamp(0.01, 1.0),
        )
    }
}

/// Uma observação normalizada produzida pelo rastreador.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TrackingPoint {
    pub time_us: i64,
    pub center_x: f32,
    pub center_y: f32,
    pub width: f32,
    pub height: f32,
    pub confidence: f32,
}

impl TrackingPoint {
    pub fn validate(&self) -> Result<(), ValidationError> {
        check(self.time_us >= 0, "tempo negativo")?;
        let v = [self.center_x, self.center_y, self.width, self.height, self.confidence];
        check(v.iter().all(|x| x.is_finite()), "ponto com valor não finito")?;
        check((0.0..=1.0).contains(&self.center_x) && (0.0..=1.0).contains(&self.center_y), "centro fora de 0..1")?;
        check((0.001..=1.0).contains(&self.width) && (0.001..=1.0).contains(&self.height), "tamanho fora de 0.001..1")?;
        check((0.0..=1.0).contains(&self.confidence), "confiança fora de 0..1")
    }
}

/// Track interno limitado (igual ao `TrackingTrack` do Kotlin).
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingTrack {
    pub id: String,
    pub points: Vec<TrackingPoint>,
}

impl TrackingTrack {
    pub fn new(id: impl Into<String>, points: Vec<TrackingPoint>) -> Result<Self, ValidationError> {
        let t = Self { id: id.into(), points };
        t.validate()?;
        Ok(t)
    }

    pub fn validate(&self) -> Result<(), ValidationError> {
        check(valid_id(&self.id), "id inválido")?;
        check(!self.points.is_empty() && self.points.len() <= MAX_POINTS, "número de pontos inválido")?;
        check(self.points.windows(2).all(|w| w[0].time_us < w[1].time_us), "tempos fora de ordem")?;
        self.points.iter().try_for_each(|p| p.validate())
    }

    /// Interpolação linear no tempo, presa nas extremidades.
    pub fn at(&self, time_us: i64) -> TrackingPoint {
        let first = self.points[0];
        let last = *self.points.last().unwrap();
        if time_us <= first.time_us {
            return first;
        }
        if time_us >= last.time_us {
            return last;
        }
        let index = self.points.partition_point(|p| p.time_us < time_us);
        let left = self.points[index - 1];
        let right = self.points[index];
        let f = ((time_us - left.time_us) as f32 / (right.time_us - left.time_us) as f32).clamp(0.0, 1.0);
        let mix = |a: f32, b: f32| a + (b - a) * f;
        TrackingPoint {
            time_us,
            center_x: mix(left.center_x, right.center_x),
            center_y: mix(left.center_y, right.center_y),
            width: mix(left.width, right.width),
            height: mix(left.height, right.height),
            confidence: mix(left.confidence, right.confidence),
        }
    }

    pub fn nearest(&self, time_us: i64) -> TrackingPoint {
        *self.points.iter().min_by_key(|p| (p.time_us - time_us).abs()).unwrap()
    }

    /// Reduz o track a no máximo `max` pontos, mantendo os extremos e a forma do movimento
    /// (Ramer–Douglas–Peucker no centro), para caber no limite de 2.000 pontos do projeto.
    pub fn decimate(&self, max: usize) -> TrackingTrack {
        if self.points.len() <= max || max < 2 {
            return self.clone();
        }
        let n = self.points.len();
        let mut keep = vec![false; n];
        keep[0] = true;
        keep[n - 1] = true;
        // Inclusão gulosa do ponto de maior erro de interpolação até atingir o limite.
        let mut kept = 2;
        while kept < max {
            let mut best = (0.0f32, usize::MAX);
            let mut left = 0;
            for i in 1..n {
                if keep[i] {
                    let (a, b) = (self.points[left], self.points[i]);
                    for j in left + 1..i {
                        let p = self.points[j];
                        let f = (p.time_us - a.time_us) as f32 / (b.time_us - a.time_us).max(1) as f32;
                        let ex = a.center_x + (b.center_x - a.center_x) * f - p.center_x;
                        let ey = a.center_y + (b.center_y - a.center_y) * f - p.center_y;
                        let ew = a.width + (b.width - a.width) * f - p.width;
                        let err = ex * ex + ey * ey + 0.25 * ew * ew;
                        if err > best.0 {
                            best = (err, j);
                        }
                    }
                    left = i;
                }
            }
            if best.1 == usize::MAX {
                break;
            }
            keep[best.1] = true;
            kept += 1;
        }
        let points = self.points.iter().zip(&keep).filter(|(_, k)| **k).map(|(p, _)| *p).collect();
        TrackingTrack { id: self.id.clone(), points }
    }
}

/// Gera um id no mesmo formato aceito pelo app.
pub fn new_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let mut x = nanos ^ n.wrapping_mul(0x9E37_79B9_7F4A_7C15);
    x ^= x >> 33;
    x = x.wrapping_mul(0xff51_afd7_ed55_8ccd);
    x ^= x >> 33;
    format!("trk-{:016x}", x)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(t: i64, x: f32, y: f32) -> TrackingPoint {
        TrackingPoint { time_us: t, center_x: x, center_y: y, width: 0.1, height: 0.1, confidence: 1.0 }
    }

    #[test]
    fn evaluates_position_and_nearest_like_kotlin() {
        let track = TrackingTrack::new("t1", vec![p(0, 0.2, 0.3), p(100_000, 0.4, 0.5), p(200_000, 0.6, 0.7)]).unwrap();
        assert!((track.at(0).center_x - 0.2).abs() < 1e-3);
        assert!((track.at(200_000).center_x - 0.6).abs() < 1e-3);
        let mid = track.at(50_000);
        assert!((mid.center_x - 0.3).abs() < 1e-3 && (mid.center_y - 0.4).abs() < 1e-3);
        assert_eq!(track.nearest(20_000).time_us, 0);
        assert_eq!(track.nearest(80_000).time_us, 100_000);
    }

    #[test]
    fn rejects_invalid_regions() {
        assert!(TrackingRegion::new(1.2, 0.5, 0.2, 0.2).is_err());
        assert!(TrackingRegion::new(0.5, 0.5, 0.0, 0.2).is_err());
        assert!(TrackingRegion::new(0.5, 0.5, 0.25, 0.25).is_ok());
    }

    #[test]
    fn decimation_keeps_ends_and_limit() {
        let pts: Vec<_> = (0..5000).map(|i| p(i * 33_333, 0.5 + 0.3 * ((i as f32) * 0.01).sin(), 0.5)).collect();
        let track = TrackingTrack { id: "a".into(), points: pts };
        let d = track.decimate(MAX_POINTS);
        assert_eq!(d.points.len(), MAX_POINTS);
        assert_eq!(d.points[0], track.points[0]);
        assert_eq!(d.points.last(), track.points.last());
        d.validate().unwrap();
    }

    #[test]
    fn ids_match_app_pattern() {
        assert!(valid_id(&new_id()));
    }
}
