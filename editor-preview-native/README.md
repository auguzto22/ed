# Recly native preview and transitions

`recly_preview` is the Rust preview engine loaded by `NativePreviewBridge`. It owns the GLES2 compositor, reusable effect and transition targets, clip selection and time mapping, frame pacing, and PCM accumulation. The Android adapter keeps `MediaCodec`, `SurfaceTexture`, EGL lifecycle, image/text/Lottie rasterization, background removal inference, and `AudioTrack` output.

The Gradle task `:editor-engine:buildRustPreview` builds the library for `arm64-v8a` and `x86_64` and copies both `.so` files into the library's ignored `jniLibs` directory before Android compilation. It requires Rust, `cargo-ndk`, the `aarch64-linux-android` and `x86_64-linux-android` targets, and Android NDK 26.3.11579264.

Each renderer handle belongs to its creating GL thread: `PreviewRender` for the monitor, or the Media3 GL executor for export. Stateless timeline and PCM JNI functions may run on decoder or audio threads. Kotlin passes an immutable JSON frame packet to the native renderer; shader programs and FBO targets stay cached across frames. Image textures are bounded by a 64 MiB cache in the Android adapter, while inactive effect and mask targets are evicted by Rust.

`transitions.rs` resolves transition windows, easing, audio crossfades and package parameters without Android or GL dependencies. The 99 transition definitions and 16 shader bodies are embedded at build time. `android/transition_gpu.rs` implements the A/B pass used by both the preview and Media3 export adapters. Kotlin retains document serialization, UI and Android lifecycle integration. See `../docs/transition-format.md` for the boundaries and compatibility details.
