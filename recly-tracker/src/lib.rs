//! # recly-tracker
//!
//! Rastreamento de movimento e sistema de máscara do editor Recly, reescritos em Rust.
//!
//! * [`tracker`] — rastreador robusto (ZNCC multicanal em pirâmide, Kalman + RTS, escala,
//!   oclusão, redetecção global, multialvo).
//! * [`mask`] — máscaras com 8 formas, keyframes com easing e deslocamento por track, com a
//!   mesma matemática do shader GLSL do app.
//! * [`render`] — recorte, desfoque, pixelização, cor, realce e contorno sobre quadros RGB.
//! * [`video`] — leitura/escrita de vídeo via ffmpeg.
//! * [`codec`] — formato binário idêntico ao `DataOutputStream` do app.
//! * [`ffi`] — API C para o Android (NDK/JNI).

pub mod codec;
pub mod error;
pub mod ffi;
pub mod image;
pub mod json;
pub mod kalman;
pub mod legacy;
pub mod mask;
pub mod par;
pub mod render;
pub mod synth;
pub mod track;
pub mod tracker;
pub mod video;

pub use image::{FrameFeatures, PixelFormat};
pub use mask::{Easing, MaskState, MaskType};
pub use render::Effect;
pub use track::{TrackingPoint, TrackingRegion, TrackingTrack};
pub use tracker::{Session, TrackStatus, Tracker, TrackerConfig};
