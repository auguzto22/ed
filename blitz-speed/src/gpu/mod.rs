//! Pipeline de movimento em OpenGL ES 2 (optical flow, interpolação, motion blur, acumulação).

pub mod api;
pub mod gl;
pub mod pipeline;

#[cfg(all(test, feature = "gpu-tests"))]
mod tests;

#[cfg(all(test, feature = "gpu-tests"))]
mod e2e;
