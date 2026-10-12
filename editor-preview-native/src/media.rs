use jni::JNIEnv;
use std::collections::HashSet;

use jni::objects::{JClass, JFloatArray, JIntArray, JLongArray, ReleaseMode};
use jni::sys::{jboolean, jfloat, jint, jintArray, jlong};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeFrameDecision(
    _env: JNIEnv,
    _class: JClass,
    video_pts_us: jlong,
    project_time_us: jlong,
    late_drop_threshold_us: jlong,
    early_tolerance_us: jlong,
) -> jlong {
    let delta = video_pts_us.saturating_sub(project_time_us);
    if delta < -late_drop_threshold_us {
        -delta.saturating_abs()
    } else if delta > early_tolerance_us {
        delta
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeMixSource(
    mut env: JNIEnv,
    _class: JClass,
    output: JFloatArray,
    input: JFloatArray,
    frames: jint,
    gain: jfloat,
) {
    if frames <= 0 || !gain.is_finite() || gain <= 0.0 {
        return;
    }
    let samples = (frames as usize).saturating_mul(2);
    let output_len = match env.get_array_length(&output) {
        Ok(len) => len as usize,
        Err(_) => return,
    };
    let input_len = match env.get_array_length(&input) {
        Ok(len) => len as usize,
        Err(_) => return,
    };
    if samples > output_len || samples > input_len {
        return;
    }
    // The Kotlin mixer owns two distinct arrays for the duration of this call.
    let Ok(mut mixed) = (unsafe { env.get_array_elements(&output, ReleaseMode::CopyBack) }) else {
        return;
    };
    let Ok(source) = (unsafe { env.get_array_elements(&input, ReleaseMode::NoCopyBack) }) else {
        return;
    };
    for index in 0..samples {
        mixed[index] += source[index] * gain;
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeLimitPcm(
    mut env: JNIEnv,
    _class: JClass,
    output: JFloatArray,
    sample_count: jint,
) {
    if sample_count <= 0 {
        return;
    }
    let count = sample_count as usize;
    if !env
        .get_array_length(&output)
        .is_ok_and(|len| count <= len as usize)
    {
        return;
    }
    let Ok(mut mixed) = (unsafe { env.get_array_elements(&output, ReleaseMode::CopyBack) }) else {
        return;
    };
    for sample in &mut mixed[..count] {
        if sample.abs() > 1.0 {
            *sample = sample.tanh();
        }
    }
}

fn int_result(env: &mut JNIEnv, values: &[jint]) -> jintArray {
    let Ok(result) = env.new_int_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&result, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    result.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeSelectActive(
    mut env: JNIEnv,
    _class: JClass,
    starts: JLongArray,
    ends: JLongArray,
    time_us: jlong,
) -> jintArray {
    let Ok(count) = env.get_array_length(&starts) else {
        return std::ptr::null_mut();
    };
    if env.get_array_length(&ends).ok() != Some(count) {
        return std::ptr::null_mut();
    }
    let mut first = vec![0; count as usize];
    let mut last = vec![0; count as usize];
    if env.get_long_array_region(&starts, 0, &mut first).is_err()
        || env.get_long_array_region(&ends, 0, &mut last).is_err()
    {
        return std::ptr::null_mut();
    }
    let indices: Vec<jint> = first
        .iter()
        .zip(&last)
        .enumerate()
        .filter(|(_, (start, end))| **start <= time_us && time_us < **end)
        .map(|(index, _)| index as jint)
        .collect();
    int_result(&mut env, &indices)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeSelectUpcoming(
    mut env: JNIEnv,
    _class: JClass,
    starts: JLongArray,
    tracks: JIntArray,
    time_us: jlong,
    end_us: jlong,
) -> jintArray {
    let Ok(count) = env.get_array_length(&starts) else {
        return std::ptr::null_mut();
    };
    if env.get_array_length(&tracks).ok() != Some(count) {
        return std::ptr::null_mut();
    }
    let mut first = vec![0; count as usize];
    let mut tracks_by_index = vec![0; count as usize];
    if env.get_long_array_region(&starts, 0, &mut first).is_err()
        || env
            .get_int_array_region(&tracks, 0, &mut tracks_by_index)
            .is_err()
    {
        return std::ptr::null_mut();
    }
    let mut indices: Vec<jint> = first
        .iter()
        .enumerate()
        .filter(|(_, start)| **start > time_us && **start <= end_us)
        .map(|(index, _)| index as jint)
        .collect();
    indices.sort_by_key(|index| first[*index as usize]);
    let mut seen_tracks = HashSet::new();
    indices.retain(|index| seen_tracks.insert(tracks_by_index[*index as usize]));
    int_result(&mut env, &indices)
}

#[derive(Clone, Copy)]
struct Segment {
    source: i64,
    end: i64,
    timeline: i64,
    speed: f64,
}

fn read_segments(
    env: &mut JNIEnv,
    bounds: &JLongArray,
    speeds: &JFloatArray,
) -> Option<Vec<Segment>> {
    let count = env.get_array_length(speeds).ok()? as usize;
    if env.get_array_length(bounds).ok()? as usize != count * 3 {
        return None;
    }
    let mut values = vec![0; count * 3];
    let mut rates = vec![0.; count];
    env.get_long_array_region(bounds, 0, &mut values).ok()?;
    env.get_float_array_region(speeds, 0, &mut rates).ok()?;
    Some(
        (0..count)
            .filter_map(|index| {
                let speed = rates[index] as f64;
                (speed > 0. && speed.is_finite()).then_some(Segment {
                    source: values[index * 3],
                    end: values[index * 3 + 1],
                    timeline: values[index * 3 + 2],
                    speed,
                })
            })
            .collect(),
    )
}

fn source_at(
    segments: &[Segment],
    duration: i64,
    in_us: i64,
    out_us: i64,
    reverse: bool,
    timeline_us: i64,
) -> i64 {
    if timeline_us <= 0 || segments.is_empty() {
        return if reverse { out_us } else { in_us };
    }
    if timeline_us >= duration {
        return if reverse { in_us } else { out_us };
    }
    let index = segments
        .partition_point(|segment| segment.timeline <= timeline_us)
        .saturating_sub(1);
    let segment = segments[index];
    let forward = (segment.source as f64 + (timeline_us - segment.timeline) as f64 * segment.speed)
        .round() as i64;
    let forward = forward.clamp(segment.source, segment.end);
    if reverse {
        (segment.source + segment.end - forward).clamp(segment.source, segment.end)
    } else {
        forward
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeClipSourceAt(
    mut env: JNIEnv,
    _class: JClass,
    bounds: JLongArray,
    speeds: JFloatArray,
    duration_us: jlong,
    in_us: jlong,
    out_us: jlong,
    source_us: jlong,
    speed: jfloat,
    reverse: jboolean,
    image: jboolean,
    has_speed_curve: jboolean,
    offset_us: jlong,
) -> jlong {
    let Some(segments) = read_segments(&mut env, &bounds, &speeds) else {
        return in_us;
    };
    let inside = offset_us.clamp(0, duration_us.saturating_sub(1).max(0));
    let bounded = source_at(&segments, duration_us, in_us, out_us, reverse != 0, inside)
        .clamp(in_us.min(out_us), in_us.max(out_us));
    if offset_us >= 0 && offset_us < duration_us {
        return bounded;
    }
    if image != 0 || reverse != 0 || has_speed_curve != 0 {
        return bounded;
    }
    let last_frame = source_us.saturating_sub(1).max(0);
    if offset_us < 0 {
        in_us
            .saturating_add((offset_us as f64 * speed as f64 + 0.5).floor() as i64)
            .clamp(0, last_frame)
    } else {
        out_us
            .saturating_add(((offset_us - duration_us) as f64 * speed as f64).round() as i64)
            .clamp(0, last_frame)
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeClipTimelineAt(
    mut env: JNIEnv,
    _class: JClass,
    bounds: JLongArray,
    speeds: JFloatArray,
    duration_us: jlong,
    in_us: jlong,
    out_us: jlong,
    reverse: jboolean,
    source_time_us: jlong,
) -> jlong {
    let Some(segments) = read_segments(&mut env, &bounds, &speeds) else {
        return 0;
    };
    if segments.is_empty() {
        return 0;
    }
    let source = source_time_us.clamp(in_us, out_us);
    let index = segments
        .partition_point(|segment| segment.source <= source)
        .saturating_sub(1);
    let segment = segments[index];
    let adjusted = if reverse != 0 {
        (segment.source + segment.end - source).clamp(segment.source, segment.end)
    } else {
        source
    };
    (segment.timeline as f64 + (adjusted - segment.source) as f64 / segment.speed)
        .round()
        .clamp(0., duration_us as f64) as i64
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_preview_engine_NativePreviewBridge_nativeClipSpeedAt(
    mut env: JNIEnv,
    _class: JClass,
    bounds: JLongArray,
    speeds: JFloatArray,
    in_us: jlong,
    out_us: jlong,
    relative_source_us: jlong,
) -> jfloat {
    let Some(segments) = read_segments(&mut env, &bounds, &speeds) else {
        return 1.;
    };
    if segments.is_empty() {
        return 1.;
    }
    let source = in_us.saturating_add(relative_source_us.max(0)).min(out_us);
    let index = segments
        .partition_point(|segment| segment.source <= source)
        .saturating_sub(1);
    segments[index].speed as jfloat
}
