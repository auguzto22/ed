use crate::android::{Renderer, with_renderer};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jfloat, jint, jlong},
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeAbandon(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let mut renderer = Box::from_raw(handle as *mut Renderer);
            renderer.delete_gl_on_drop = false;
            drop(renderer);
        }
    }
}

fn run<T>(
    env: &mut JNIEnv,
    handle: jlong,
    f: impl FnOnce(&mut Renderer) -> Result<T, String>,
) -> Option<T> {
    match with_renderer(handle, f) {
        Ok(value) => Some(value),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            None
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeCapture(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    texture: jint,
    time: jlong,
    width: jint,
    height: jint,
) {
    run(&mut env, handle, |r| unsafe {
        r.export_pass(|r, fbo| r.export_capture(texture as u32, time, width, height, fbo))
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeCopy(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    texture: jint,
    width: jint,
    height: jint,
    hold: jboolean,
) {
    run(&mut env, handle, |r| unsafe {
        r.export_pass(|r, fbo| r.export_copy(texture as u32, width, height, hold != 0, fbo))
    });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeDraw(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    texture: jint,
    time: jlong,
    width: jint,
    height: jint,
    id: JString,
    progress: jfloat,
    params: JString,
) -> jboolean {
    let result = (|| {
        let id: String = env.get_string(&id).map_err(|e| e.to_string())?.into();
        let text: String = env.get_string(&params).map_err(|e| e.to_string())?.into();
        let parameters = serde_json::from_str(&text).map_err(|e| e.to_string())?;
        with_renderer(handle, |r| unsafe {
            r.export_pass(|r, fbo| {
                r.export_transition(
                    texture as u32,
                    time,
                    width,
                    height,
                    &id,
                    progress,
                    &parameters,
                    fbo,
                )
            })
        })
    })();
    match result {
        Ok(drawn) => drawn as u8,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
            0
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeApplyFade(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    texture: jint,
    width: jint,
    height: jint,
    input: jint,
    output: jint,
    fade: jlong,
    duration: jlong,
    local: jlong,
) {
    run(&mut env, handle, |r| unsafe {
        r.export_pass(|r, fbo| {
            r.export_fade(
                texture as u32,
                width,
                height,
                crate::transitions::fade(input, output, fade, duration, local),
                fbo,
            )
        })
    });
}
