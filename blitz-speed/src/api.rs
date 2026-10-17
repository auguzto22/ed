//! Funções JNI de `com.termex.replay15.editor.speed.NativeSpeed` (mapa de tempo, presets,
//! reajuste, política e agendador). As de áudio e GPU ficam em seus módulos.

use crate::curve::{CubicBezier, Easing, SpeedPoint};
use crate::jni::{self, guard, illegal, jboolean, jfloat, jfloatArray, jint, jintArray, jlong, jlongArray, jobject, jstring, Env, JniResult, RawEnv};
use crate::policy;
use crate::presets;
use crate::scheduler::{Action, Scheduler};
use crate::timemap::{self, ClipParams, TimeMap};
use crate::warp::TimeWarp;
use std::ptr::null_mut;

/// Pontos chegam do Kotlin em 4 `long` cada (mesmo empacotamento da tabela):
/// `[tempo, velocidade | easing << 32, x1 | y1 << 32, x2 | y2 << 32]`.
pub fn decode_points(packed: &[i64]) -> JniResult<Vec<SpeedPoint>> {
    if packed.len() % 4 != 0 {
        return Err(illegal("pontos mal formados"));
    }
    packed.chunks_exact(4).map(|chunk| {
        let bits = chunk[1] as u64;
        let easing = Easing::from_ordinal((bits >> 32) as i32).ok_or_else(|| illegal("easing não permitido numa curva de velocidade"))?;
        let x = chunk[2] as u64;
        let y = chunk[3] as u64;
        Ok(SpeedPoint {
            source_us: chunk[0],
            speed: f32::from_bits(bits as u32),
            easing,
            bezier: CubicBezier {
                x1: f32::from_bits(x as u32), y1: f32::from_bits((x >> 32) as u32),
                x2: f32::from_bits(y as u32), y2: f32::from_bits((y >> 32) as u32),
            },
        })
    }).collect()
}

pub fn encode_points(points: &[SpeedPoint]) -> Vec<i64> {
    let pack = |low: u32, high: u32| ((high as u64) << 32 | low as u64) as i64;
    points.iter().flat_map(|p| [
        p.source_us,
        pack(p.speed.to_bits(), p.easing as i32 as u32),
        pack(p.bezier.x1.to_bits(), p.bezier.y1.to_bits()),
        pack(p.bezier.x2.to_bits(), p.bezier.y2.to_bits()),
    ]).collect()
}

fn map_error(error: timemap::MapError) -> jni::JniError {
    illegal(match error {
        timemap::MapError::InvalidWindow => "Janela de corte inválida".to_string(),
        timemap::MapError::InvalidSpeed => "Velocidade fora da faixa de 0,1× a 16×".to_string(),
        timemap::MapError::Curve(e) => format!("Curva de velocidade inválida: {e:?}"),
        timemap::MapError::Corrupt => "Tabela de tempo corrompida".to_string(),
    })
}

fn with_map<R>(env: Env, table: jlongArray, body: impl FnOnce(TimeMap) -> R) -> JniResult<R> {
    env.with_longs(table, |slice| TimeMap::new(slice).map(body))?.map_err(map_error)
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_buildTimeMap(
    env: RawEnv, _class: jobject, source_us: jlong, in_us: jlong, out_us: jlong, speed: jfloat, reverse: jboolean, points: jlongArray,
) -> jlongArray {
    guard(env, null_mut(), |env| {
        let points = decode_points(&env.longs(points)?)?;
        let table = timemap::build(&ClipParams { source_us, in_us, out_us, speed, reverse: reverse != 0 }, &points).map_err(map_error)?;
        env.new_longs(&table)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_sourceAt(env: RawEnv, _c: jobject, table: jlongArray, t: jlong) -> jlong {
    guard(env, 0, |env| with_map(env, table, |m| m.source_at(t)))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_timelineAt(env: RawEnv, _c: jobject, table: jlongArray, s: jlong) -> jlong {
    guard(env, 0, |env| with_map(env, table, |m| m.timeline_at(s)))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_extendedSourceAt(env: RawEnv, _c: jobject, table: jlongArray, t: jlong) -> jlong {
    guard(env, 0, |env| with_map(env, table, |m| m.extended_source_at(t)))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_speedAtInput(env: RawEnv, _c: jobject, table: jlongArray, relative: jlong) -> jfloat {
    guard(env, 1.0, |env| with_map(env, table, |m| m.speed_at_input(relative)))
}

/// Próxima mudança de velocidade relativa ao início do trecho, ou -1 quando não há.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_nextInputChange(env: RawEnv, _c: jobject, table: jlongArray, relative: jlong) -> jlong {
    guard(env, -1, |env| with_map(env, table, |m| m.next_input_change(relative).unwrap_or(-1)))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_curveSpeedAtSource(env: RawEnv, _c: jobject, table: jlongArray, s: jlong) -> jfloat {
    guard(env, 1.0, |env| with_map(env, table, |m| m.curve_speed_at_source(s)))
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_curveSpeedAtTimeline(env: RawEnv, _c: jobject, table: jlongArray, t: jlong) -> jfloat {
    guard(env, 1.0, |env| with_map(env, table, |m| m.curve_speed_at_timeline(t)))
}

/// `[mais lenta, mais rápida]` dentro da janela visível.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_speedRange(env: RawEnv, _c: jobject, table: jlongArray) -> jfloatArray {
    guard(env, null_mut(), |env| {
        let (low, high) = with_map(env, table, |m| m.speed_range())?;
        env.new_floats(&[low, high])
    })
}

/// Velocidade contínua em `count` posições igualmente espaçadas de `[from, to]` da mídia, para
/// desenhar a curva com os mesmos valores que o motor usa.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_sampleCurve(
    env: RawEnv, _c: jobject, table: jlongArray, from: jlong, to: jlong, count: jint,
) -> jfloatArray {
    guard(env, null_mut(), |env| {
        if count < 2 || count > 4096 || to < from {
            return Err(illegal("amostragem inválida"));
        }
        let samples = with_map(env, table, |m| {
            (0..count).map(|i| m.curve_speed_at_source(from + ((to - from) as f64 * i as f64 / (count - 1) as f64).round() as i64)).collect::<Vec<_>>()
        })?;
        env.new_floats(&samples)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_presetCount(_env: RawEnv, _c: jobject) -> jint {
    presets::PRESETS.len() as jint
}

fn preset_text(env: RawEnv, index: jint, pick: fn(&presets::Preset) -> &'static str) -> jstring {
    guard(env, null_mut(), |env| {
        let preset = presets::PRESETS.get(index.max(0) as usize).ok_or_else(|| illegal("preset inexistente"))?;
        env.new_string(pick(preset))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_presetId(env: RawEnv, _c: jobject, index: jint) -> jstring {
    preset_text(env, index, |p| p.id)
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_presetLabel(env: RawEnv, _c: jobject, index: jint) -> jstring {
    preset_text(env, index, |p| p.label)
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_presetDescription(env: RawEnv, _c: jobject, index: jint) -> jstring {
    preset_text(env, index, |p| p.description)
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_buildPreset(
    env: RawEnv, _c: jobject, index: jint, in_us: jlong, out_us: jlong, intensity: jfloat,
) -> jlongArray {
    guard(env, null_mut(), |env| {
        let points = presets::build(index.max(0) as usize, in_us, out_us, intensity).ok_or_else(|| illegal("preset ou janela inválidos"))?;
        env.new_longs(&encode_points(&points))
    })
}

/// Aplica o reajuste a vários instantes de uma vez.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_warpMap(
    env: RawEnv, _c: jobject, before: jlongArray, after: jlongArray, old_start: jlong, new_start: jlong, ripple: jboolean, times: jlongArray,
) -> jlongArray {
    guard(env, null_mut(), |env| {
        let before = env.longs(before)?;
        let after = env.longs(after)?;
        let times = env.longs(times)?;
        let warp = TimeWarp {
            before: TimeMap::new(&before).map_err(map_error)?,
            after: TimeMap::new(&after).map_err(map_error)?,
            old_start_us: old_start,
            new_start_us: new_start,
            ripple: ripple != 0,
        };
        let mapped: Vec<i64> = times.iter().map(|&t| warp.map(t)).collect();
        env.new_longs(&mapped)
    })
}

/// Entradas: `ints = [modo, blurLigado, fonteBlur, amostras, contexto, memoriaMb, lowRam,
/// highp, maxTextura, termico, nivelPrevia, largura, altura, animacaoCamada, imagem]`,
/// `floats = [fpsFonte, maisLenta, maisRapida, obturador, intensidade, fpsSaida]`.
/// Saída: `[sintese, blur, ladoFlow, niveis, iteracoes, amostras, exposicao, intensidade, aviso]`.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_motionPlan(
    env: RawEnv, _c: jobject, ints: jintArray, floats: jfloatArray,
) -> jfloatArray {
    guard(env, null_mut(), |env| {
        let i = env.ints(ints)?;
        let f = env.floats(floats)?;
        if i.len() != 15 || f.len() != 6 {
            return Err(illegal("parâmetros de plano mal formados"));
        }
        let facts = policy::ClipFacts {
            source_fps: f[0], width: i[11], height: i[12], slowest_speed: f[1], fastest_speed: f[2],
            has_layer_animation: i[13] != 0, image: i[14] != 0,
        };
        let blur = policy::BlurConfig { enabled: i[1] != 0, shutter_angle: f[3], intensity: f[4], samples: i[3], source: policy::BlurSource::from_ordinal(i[2]) };
        let device = policy::Device {
            memory_class_mb: i[5], low_ram: i[6] != 0, highp_fragment: i[7] != 0, max_texture_size: i[8], thermal_status: i[9], preview_tier: i[10],
        };
        let plan = policy::plan(&facts, policy::SlowMotionMode::from_ordinal(i[0]), &blur, &device, policy::Context::from_ordinal(i[4]), f[5]);
        env.new_floats(&[
            plan.synthesis as i32 as f32, plan.blur as i32 as f32, plan.flow_short_side as f32, plan.flow_levels as f32,
            plan.flow_iterations as f32, plan.blur_samples as f32, plan.exposure_fraction, plan.blur_intensity, plan.notice as i32 as f32,
        ])
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_noticeText(env: RawEnv, _c: jobject, code: jint) -> jstring {
    guard(env, null_mut(), |env| {
        let notice = match code {
            1 => policy::Notice::FlowUnavailable, 2 => policy::Notice::FlowHot, 3 => policy::Notice::FlowStruggling,
            4 => policy::Notice::VectorsUnavailable, 5 => policy::Notice::NoMotionData, _ => policy::Notice::None,
        };
        env.new_string(notice.text())
    })
}

#[no_mangle]
/// `endUs` < 0 quando o fim do clipe não é conhecido.
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_schedulerCreate(env: RawEnv, _c: jobject, fps: jfloat, exposure: jfloat, end_us: jlong) -> jlong {
    guard(env, 0, |_| {
        let mut scheduler = Scheduler::new(fps, exposure).map_err(|_| illegal("cadência ou obturador inválidos"))?;
        scheduler.set_end((end_us >= 0).then_some(end_us));
        Ok(jni::into_handle(scheduler))
    })
}

/// Cada ação em 8 `long`: `[tipo, …]` — 0 guardar `[slot, pts]`, 1 acumular
/// `[slot, saída, próximo slot (−1 nenhum), deUs, paraUs]`,
/// 2 emitir acumulado `[saída, quadros]`, 3 emitir sintetizado
/// `[saída, de, para, deUs, paraUs, bitsFase, exposição]`.
fn encode_actions(actions: &[Action]) -> Vec<i64> {
    let mut out = Vec::with_capacity(actions.len() * 8);
    for action in actions {
        let row: [i64; 8] = match *action {
            Action::Store { slot, pts_us } => [0, slot as i64, pts_us, 0, 0, 0, 0, 0],
            Action::Accumulate { slot, output_us, next_slot, from_us, to_us } =>
                [1, slot as i64, output_us, next_slot.map_or(-1, |s| s as i64), from_us, to_us, 0, 0],
            Action::EmitAccumulated { output_us, frames } => [2, output_us, frames as i64, 0, 0, 0, 0, 0],
            Action::EmitSynthesized { output_us, from_slot, to_slot, from_us, to_us, phase, exposure_us } =>
                [3, output_us, from_slot as i64, to_slot as i64, from_us, to_us, phase.to_bits() as i64, exposure_us],
        };
        out.extend_from_slice(&row);
    }
    out
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_schedulerInput(env: RawEnv, _c: jobject, handle: jlong, pts: jlong) -> jlongArray {
    guard(env, null_mut(), |env| {
        let scheduler = unsafe { jni::handle_mut::<Scheduler>(handle)? };
        let mut actions = Vec::new();
        scheduler.on_input(pts, &mut actions).map_err(|_| illegal("carimbos de tempo devem crescer"))?;
        env.new_longs(&encode_actions(&actions))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_schedulerEnd(env: RawEnv, _c: jobject, handle: jlong) -> jlongArray {
    guard(env, null_mut(), |env| {
        let scheduler = unsafe { jni::handle_mut::<Scheduler>(handle)? };
        let mut actions = Vec::new();
        scheduler.on_end_of_stream(&mut actions);
        env.new_longs(&encode_actions(&actions))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_schedulerReset(env: RawEnv, _c: jobject, handle: jlong) {
    guard(env, (), |_| {
        unsafe { jni::handle_mut::<Scheduler>(handle)? }.reset();
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_schedulerRelease(_env: RawEnv, _c: jobject, handle: jlong) {
    unsafe { jni::drop_handle::<Scheduler>(handle) }
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_duration(env: RawEnv, _c: jobject, table: jlongArray) -> jlong {
    guard(env, 0, |env| with_map(env, table, |m| m.duration_us()))
}

/// Degraus da janela visível, 4 `long` cada: `[início, fim, timeline, bits da velocidade]`.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_segments(env: RawEnv, _c: jobject, table: jlongArray) -> jlongArray {
    guard(env, null_mut(), |env| {
        let rows = with_map(env, table, |m| {
            (0..m.trimmed_count()).flat_map(|i| {
                let s = m.trimmed(i);
                [s.source_us, s.end_us, s.timeline_us, s.speed.to_bits() as i64]
            }).collect::<Vec<i64>>()
        })?;
        env.new_longs(&rows)
    })
}

/// `[veredito, diferença em µs]` da conferência do arquivo exportado. `audio_us` < 0 = sem áudio.
#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_validateExport(
    env: RawEnv, _c: jobject, expected_us: jlong, video_us: jlong, audio_us: jlong, expect_audio: jboolean, fps: jint,
) -> jlongArray {
    guard(env, null_mut(), |env| {
        let (verdict, difference) = crate::validate::check(expected_us, video_us, (audio_us >= 0).then_some(audio_us), expect_audio != 0, fps);
        env.new_longs(&[verdict as i64, difference])
    })
}

#[no_mangle]
pub extern "system" fn Java_com_termex_replay15_editor_speed_NativeSpeed_exportVerdictText(env: RawEnv, _c: jobject, code: jint, difference: jlong) -> jstring {
    guard(env, null_mut(), |env| {
        let verdict = match code {
            1 => crate::validate::Verdict::VideoTooShort, 2 => crate::validate::Verdict::VideoTooLong,
            3 => crate::validate::Verdict::AudioMissing, 4 => crate::validate::Verdict::AudioOutOfSync, _ => crate::validate::Verdict::Ok,
        };
        env.new_string(&crate::validate::text(verdict, difference))
    })
}
