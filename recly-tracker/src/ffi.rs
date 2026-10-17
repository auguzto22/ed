//! API C para integrar no app Android (NDK + JNI) ou em qualquer outra linguagem.
//! Cabeçalho correspondente: `include/recly_tracker.h`.
//!
//! Todas as funções validam ponteiros e tamanhos e nunca entram em pânico com entradas
//! inválidas: devolvem um código de erro.

use crate::image::{FrameFeatures, PixelFormat};
use crate::mask::MaskState;
use crate::track::{TrackingPoint, TrackingRegion, TrackingTrack};
use crate::tracker::{Session, Tracker, TrackerConfig};
use std::ffi::{c_char, CStr};

/// Ponto rastreado (mesmos campos de `TrackingPoint`).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct ReclyPoint {
    pub time_us: i64,
    pub center_x: f32,
    pub center_y: f32,
    pub width: f32,
    pub height: f32,
    pub confidence: f32,
}

impl From<TrackingPoint> for ReclyPoint {
    fn from(p: TrackingPoint) -> Self {
        Self { time_us: p.time_us, center_x: p.center_x, center_y: p.center_y, width: p.width, height: p.height, confidence: p.confidence }
    }
}

pub struct ReclyTracker {
    session: Session,
}

pub const RECLY_OK: i32 = 0;
pub const RECLY_SKIPPED: i32 = 1;
pub const RECLY_ERR_ARG: i32 = -1;
pub const RECLY_ERR_JSON: i32 = -2;

/// 0 = RGB24, 1 = RGBA8888 (layout em memória de um Bitmap ARGB_8888).
fn format_of(code: u32) -> Option<PixelFormat> {
    match code {
        0 => Some(PixelFormat::Rgb24),
        1 => Some(PixelFormat::Rgba32),
        _ => None,
    }
}

unsafe fn features(pixels: *const u8, width: u32, height: u32, stride: u32, format: u32) -> Option<FrameFeatures> {
    let fmt = format_of(format)?;
    let (w, h, s) = (width as usize, height as usize, stride as usize);
    if pixels.is_null() || w == 0 || h == 0 || s < w * fmt.bytes_per_pixel() {
        return None;
    }
    let len = s * (h - 1) + w * fmt.bytes_per_pixel();
    let slice = std::slice::from_raw_parts(pixels, len);
    Some(FrameFeatures::from_pixels(slice, w, h, s, fmt))
}

/// Inicia o rastreamento no primeiro quadro. Retorna NULL se os argumentos forem inválidos.
///
/// # Safety
/// `pixels` deve apontar para `stride * height` bytes válidos.
#[no_mangle]
pub unsafe extern "C" fn recly_tracker_new(
    pixels: *const u8,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    time_us: i64,
    center_x: f32,
    center_y: f32,
    region_width: f32,
    region_height: f32,
) -> *mut ReclyTracker {
    let Ok(region) = TrackingRegion::new(center_x, center_y, region_width, region_height) else { return std::ptr::null_mut() };
    if time_us < 0 {
        return std::ptr::null_mut();
    }
    let Some(f) = features(pixels, width, height, stride, format) else { return std::ptr::null_mut() };
    let tracker = Tracker { config: TrackerConfig::default() };
    Box::into_raw(Box::new(ReclyTracker { session: tracker.start(&f, time_us, region) }))
}

/// Entrega o próximo quadro. Retorna RECLY_OK e preenche `out` (pode ser NULL),
/// RECLY_SKIPPED se o tempo não avançou, ou um erro.
///
/// # Safety
/// `tracker` deve vir de `recly_tracker_new`; `pixels` deve ser válido como acima.
#[no_mangle]
pub unsafe extern "C" fn recly_tracker_push(
    tracker: *mut ReclyTracker,
    pixels: *const u8,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    time_us: i64,
    out: *mut ReclyPoint,
) -> i32 {
    let Some(t) = tracker.as_mut() else { return RECLY_ERR_ARG };
    let Some(f) = features(pixels, width, height, stride, format) else { return RECLY_ERR_ARG };
    match t.session.update(&f, time_us) {
        Some(p) => {
            if let Some(o) = out.as_mut() {
                *o = p.into();
            }
            RECLY_OK
        }
        None => RECLY_SKIPPED,
    }
}

/// Número de pontos que `recly_tracker_finish` vai produzir no máximo.
///
/// # Safety
/// `tracker` deve ser válido ou NULL.
#[no_mangle]
pub unsafe extern "C" fn recly_tracker_len(tracker: *const ReclyTracker) -> u32 {
    tracker.as_ref().map_or(0, |t| t.session.len() as u32)
}

/// Finaliza (suaviza se `smooth != 0`), copia até `capacity` pontos em `out` e libera o rastreador.
/// Retorna o número de pontos escritos.
///
/// # Safety
/// `tracker` deve vir de `recly_tracker_new` e não pode ser usado depois; `out` deve ter
/// espaço para `capacity` pontos.
#[no_mangle]
pub unsafe extern "C" fn recly_tracker_finish(tracker: *mut ReclyTracker, smooth: i32, out: *mut ReclyPoint, capacity: u32) -> u32 {
    if tracker.is_null() {
        return 0;
    }
    let t = Box::from_raw(tracker);
    let track = t.session.finish(Some("native".into()), smooth != 0);
    if out.is_null() {
        return 0;
    }
    let n = track.points.len().min(capacity as usize);
    let dst = std::slice::from_raw_parts_mut(out, n);
    for (d, p) in dst.iter_mut().zip(&track.points) {
        *d = (*p).into();
    }
    n as u32
}

/// Libera um rastreador sem finalizar.
///
/// # Safety
/// `tracker` deve vir de `recly_tracker_new` ou ser NULL.
#[no_mangle]
pub unsafe extern "C" fn recly_tracker_free(tracker: *mut ReclyTracker) {
    if !tracker.is_null() {
        drop(Box::from_raw(tracker));
    }
}

/// Rasteriza o alpha da camada (0..255) de uma máscara em JSON, avaliada em `time_us`.
/// `track_json` é opcional (NULL) e, se dado, move a máscara pelo track.
///
/// # Safety
/// Strings C válidas terminadas em NUL; `out` com `width * height` bytes.
#[no_mangle]
pub unsafe extern "C" fn recly_mask_rasterize(
    mask_json: *const c_char,
    track_json: *const c_char,
    time_us: i64,
    width: u32,
    height: u32,
    out: *mut u8,
) -> i32 {
    if mask_json.is_null() || out.is_null() || width == 0 || height == 0 {
        return RECLY_ERR_ARG;
    }
    let Ok(mask_str) = CStr::from_ptr(mask_json).to_str() else { return RECLY_ERR_JSON };
    let Ok(mask) = crate::json::from_str::<MaskState>(mask_str) else { return RECLY_ERR_JSON };
    if mask.validate().is_err() {
        return RECLY_ERR_JSON;
    }
    let track = if track_json.is_null() {
        None
    } else {
        let Ok(s) = CStr::from_ptr(track_json).to_str() else { return RECLY_ERR_JSON };
        match crate::json::from_str::<TrackingTrack>(s) {
            Ok(t) if t.validate().is_ok() => Some(t),
            _ => return RECLY_ERR_JSON,
        }
    };
    let state = mask.at(time_us, track.as_ref());
    let (w, h) = (width as usize, height as usize);
    let coverage = state.rasterize_shape(w, h, true);
    let dst = std::slice::from_raw_parts_mut(out, w * h);
    let opacity = state.opacity.clamp(0.0, 1.0);
    for (d, c) in dst.iter_mut().zip(coverage) {
        *d = ((1.0 + (c - 1.0) * opacity) * 255.0).round() as u8;
    }
    RECLY_OK
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bad_arguments_without_crashing() {
        unsafe {
            assert!(recly_tracker_new(std::ptr::null(), 10, 10, 30, 0, 0, 0.5, 0.5, 0.2, 0.2).is_null());
            let px = vec![0u8; 10 * 10 * 4];
            assert!(recly_tracker_new(px.as_ptr(), 10, 10, 20, 1, 0, 0.5, 0.5, 0.2, 0.2).is_null(), "stride curto");
            assert!(recly_tracker_new(px.as_ptr(), 10, 10, 40, 7, 0, 0.5, 0.5, 0.2, 0.2).is_null(), "formato");
            assert_eq!(recly_tracker_push(std::ptr::null_mut(), px.as_ptr(), 10, 10, 40, 1, 1, std::ptr::null_mut()), RECLY_ERR_ARG);
            assert_eq!(recly_tracker_finish(std::ptr::null_mut(), 1, std::ptr::null_mut(), 0), 0);
        }
    }

    #[test]
    fn full_cycle_over_rgba() {
        let (w, h) = (64u32, 48u32);
        let frame = |shift: usize| -> Vec<u8> {
            let mut v = vec![0u8; (w * h * 4) as usize];
            for y in 0..h as usize {
                for x in 0..w as usize {
                    let i = (y * w as usize + x) * 4;
                    let inside = (x + 64 - shift) % 64 >= 20 && (x + 64 - shift) % 64 < 36 && (16..32).contains(&y);
                    let tex = ((x * 7 + y * 13) % 17) as u8 * 8;
                    v[i] = if inside { 200 } else { tex };
                    v[i + 1] = if inside { (x * 9 % 50) as u8 } else { tex / 2 };
                    v[i + 2] = 60;
                    v[i + 3] = 255;
                }
            }
            v
        };
        unsafe {
            let f0 = frame(0);
            let t = recly_tracker_new(f0.as_ptr(), w, h, w * 4, 1, 0, 28.0 / 64.0, 24.0 / 48.0, 16.0 / 64.0, 16.0 / 48.0);
            assert!(!t.is_null());
            let mut p = ReclyPoint::default();
            for k in 1..6 {
                let f = frame(k * 2);
                assert_eq!(recly_tracker_push(t, f.as_ptr(), w, h, w * 4, 1, k as i64 * 33_333, &mut p), RECLY_OK);
            }
            assert!((p.center_x * 64.0 - 38.0).abs() < 2.0, "x = {}", p.center_x * 64.0);
            let mut out = vec![ReclyPoint::default(); 16];
            assert_eq!(recly_tracker_finish(t, 1, out.as_mut_ptr(), 16), 6);
        }
    }

    #[test]
    fn rasterizes_json_mask() {
        let json = std::ffi::CString::new(crate::json::to_string(&MaskState::default_for(crate::mask::MaskType::Ellipse))).unwrap();
        let mut out = vec![0u8; 20 * 10];
        unsafe {
            assert_eq!(recly_mask_rasterize(json.as_ptr(), std::ptr::null(), 0, 20, 10, out.as_mut_ptr()), RECLY_OK);
        }
        assert_eq!(out[5 * 20 + 10], 255);
        assert_eq!(out[0], 0);
    }
}
