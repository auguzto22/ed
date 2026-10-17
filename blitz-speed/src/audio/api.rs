//! JNI do fluxo de áudio retemporizado (`NativeSpeed.audio*`). Um handle por sessão de áudio da
//! prévia; `audioRelease` quando a sessão fecha.

use super::RetimeStream;
use crate::jni::{self, guard, illegal, jboolean, jfloat, jfloatArray, jint, jlong, jobject, RawEnv};

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioCreate(_env: RawEnv, _c: jobject) -> jlong {
    jni::into_handle(RetimeStream::new())
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioInputNeeded(
    env: RawEnv, _c: jobject, handle: jlong, frames: jint, speed: jfloat, preserve_pitch: jboolean,
) -> jint {
    guard(env, 0, |_| {
        let stream = unsafe { jni::handle_mut::<RetimeStream>(handle)? };
        Ok(stream.input_needed(frames.max(0) as usize, speed as f64, preserve_pitch != 0).min(i32::MAX as usize) as jint)
    })
}

/// Empurra `frames` quadros estéreo de `input` e produz até completar `target` quadros.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioPush(
    env: RawEnv, _c: jobject, handle: jlong, input: jfloatArray, frames: jint, speed: jfloat, preserve_pitch: jboolean, target: jint,
) -> jint {
    guard(env, 0, |env| {
        if frames < 0 || !speed.is_finite() || speed <= 0.0 {
            return Err(illegal("parâmetros de áudio inválidos"));
        }
        let stream = unsafe { jni::handle_mut::<RetimeStream>(handle)? };
        let mut samples = vec![0f32; frames as usize * super::CHANNELS];
        env.read_floats(input, 0, &mut samples)?;
        Ok(stream.push(&samples, speed as f64, preserve_pitch != 0, target.max(0) as usize) as jint)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioPull(
    env: RawEnv, _c: jobject, handle: jlong, output: jfloatArray, frames: jint,
) -> jint {
    guard(env, 0, |env| {
        let stream = unsafe { jni::handle_mut::<RetimeStream>(handle)? };
        let mut samples = vec![0f32; frames.max(0) as usize * super::CHANNELS];
        let got = stream.pull(&mut samples, frames.max(0) as usize);
        env.write_floats(output, 0, &samples[..got * super::CHANNELS])?;
        Ok(got as jint)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioAvailable(env: RawEnv, _c: jobject, handle: jlong) -> jint {
    guard(env, 0, |_| Ok(unsafe { jni::handle_mut::<RetimeStream>(handle)? }.available() as jint))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioReset(env: RawEnv, _c: jobject, handle: jlong) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<RetimeStream>(handle)? }.reset();
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_audioRelease(_env: RawEnv, _c: jobject, handle: jlong) {
    unsafe { jni::drop_handle::<RetimeStream>(handle) }
}
