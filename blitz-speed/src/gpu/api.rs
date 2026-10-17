//! Funções JNI do pipeline de movimento (`NativeSpeed.motion*`). Todas precisam ser chamadas na
//! thread que tem o contexto GL atual onde o pipeline foi criado.

use super::gl;
use super::pipeline::{BlurParams, MotionPipeline, Synthesis};
use crate::jni::{self, guard, illegal, jboolean, jfloat, jfloatArray, jint, jintArray, jlong, jobject, RawEnv};
use std::ptr::null_mut;

/// `[highp (0/1), tamanho máximo de textura]` do contexto atual.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionProbe(env: RawEnv, _c: jobject) -> jintArray {
    guard(env, null_mut(), |env| {
        let caps = gl::probe().map_err(jni::JniError::IllegalState)?;
        env.new_ints(&[caps.highp_fragment as i32, caps.max_texture_size])
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionCreate(
    env: RawEnv, _c: jobject, out_width: jint, out_height: jint, flow_width: jint, flow_height: jint, levels: jint, iterations: jint,
) -> jlong {
    guard(env, 0, |_| {
        MotionPipeline::new(out_width, out_height, flow_width, flow_height, levels, iterations)
            .map(jni::into_handle)
            .map_err(jni::JniError::IllegalState)
    })
}

/// `[confiança média, correlação, corte (0/1), tem flow (0/1)]`.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionAnalyze(
    env: RawEnv, _c: jobject, handle: jlong, texture_a: jint, texture_b: jint, key_a: jlong, key_b: jlong, flow: jboolean,
) -> jfloatArray {
    guard(env, null_mut(), |env| {
        let pipeline = unsafe { jni::handle_mut::<MotionPipeline>(handle)? };
        let stats = pipeline.analyze(texture_a as u32, texture_b as u32, (key_a, key_b), flow != 0);
        env.new_floats(&[stats.mean_confidence, stats.correlation, stats.cut as i32 as f32, stats.has_flow as i32 as f32])
    })
}

/// `synthesis`: 0 repetir, 1 misturar, 2 optical flow. `blurSamples` 0 = sem blur.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionRender(
    env: RawEnv, _c: jobject, handle: jlong, texture_a: jint, texture_b: jint, phase: jfloat, synthesis: jint, ghost_control: jfloat,
    blur_scale: jfloat, blur_samples: jint, fbo: jint, x: jint, y: jint, width: jint, height: jint,
) {
    guard(env, (), |_| {
        let pipeline = unsafe { jni::handle_mut::<MotionPipeline>(handle)? };
        let synthesis = match synthesis {
            0 => Synthesis::Repeat,
            1 => Synthesis::Blend,
            2 => Synthesis::OpticalFlow,
            _ => return Err(illegal("síntese desconhecida")),
        };
        let blur = (blur_samples > 0 && blur_scale > 0.0).then_some(BlurParams { scale: blur_scale, samples: blur_samples });
        pipeline.render(texture_a as u32, texture_b as u32, phase, synthesis, ghost_control, blur, fbo as u32, (x, y, width, height));
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionAccumulate(env: RawEnv, _c: jobject, handle: jlong, texture: jint, output_us: jlong) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<MotionPipeline>(handle)? }.accumulate(texture as u32, output_us);
        Ok(())
    })
}

/// Acumula a amostra A borrada pelo movimento até a amostra seguinte B (aceleração com blur por
/// vetores: as cópias viram um rastro contínuo). Sem flow confiável entra nítida.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionAccumulatePair(
    env: RawEnv, _c: jobject, handle: jlong, texture_a: jint, texture_b: jint, key_a: jlong, key_b: jlong, samples: jint, output_us: jlong,
) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<MotionPipeline>(handle)? }.accumulate_pair(texture_a as u32, texture_b as u32, (key_a, key_b), samples, output_us);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionEmitAccumulated(
    env: RawEnv, _c: jobject, handle: jlong, fbo: jint, x: jint, y: jint, width: jint, height: jint,
) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<MotionPipeline>(handle)? }.emit_accumulated(fbo as u32, (x, y, width, height));
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionCopy(
    env: RawEnv, _c: jobject, handle: jlong, texture: jint, fbo: jint, x: jint, y: jint, width: jint, height: jint,
) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<MotionPipeline>(handle)? }.copy(texture as u32, fbo as u32, (x, y, width, height));
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionInvalidate(env: RawEnv, _c: jobject, handle: jlong) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<MotionPipeline>(handle)? }.invalidate();
        Ok(())
    })
}

/// Libera os objetos GL (na thread GL) e o pipeline. O handle não pode ser usado depois.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionRelease(_env: RawEnv, _c: jobject, handle: jlong) {
    if handle == 0 {
        return;
    }
    unsafe {
        if let Ok(pipeline) = jni::handle_mut::<MotionPipeline>(handle) {
            pipeline.release();
        }
        jni::drop_handle::<MotionPipeline>(handle);
    }
}
