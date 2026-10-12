//! Native preview compositor, frame pacing and PCM mixing. Android owns codec,
//! media surfaces, text rasterization and audio output.

#[cfg(target_os = "android")]
mod android;

#[cfg(target_os = "android")]
mod media;

mod transition_api;
#[cfg(target_os = "android")]
mod transition_gl_api;
mod transitions;
