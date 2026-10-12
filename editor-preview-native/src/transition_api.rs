use crate::transitions as t;
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jboolean, jfloat, jint, jlong, jstring},
};
use serde_json::{Value, json};
use std::collections::BTreeSet;

fn query(request: Value) -> Result<Value, String> {
    let parse = |name: &str| {
        request
            .get(name)
            .cloned()
            .ok_or_else(|| format!("Missing {name}"))
    };
    match request["op"].as_str().unwrap_or("") {
        "catalog" => {
            t::catalogue()?;
            serde_json::from_str(t::CATALOG_JSON).map_err(|e| e.to_string())
        }
        "plan" => {
            let timeline = serde_json::from_value(parse("timeline")?).map_err(|e| e.to_string())?;
            Ok(json!(t::plan(&timeline)))
        }
        "maximum" => {
            let timeline = serde_json::from_value(parse("timeline")?).map_err(|e| e.to_string())?;
            let edit = serde_json::from_value(parse("edit")?).map_err(|e| e.to_string())?;
            Ok(json!(t::max_duration(
                &timeline,
                &edit,
                request["ceiling"].as_i64().unwrap_or(t::MAX_DURATION)
            )))
        }
        "shader" => Ok(json!(
            t::package(request["id"].as_str().unwrap_or(""))?.fragment
        )),
        "uniforms" => {
            let p = t::package(request["id"].as_str().unwrap_or(""))?;
            let input = serde_json::from_value(parse("parameters")?).map_err(|e| e.to_string())?;
            Ok(json!(t::values(&p.definition, &p.supplemental, &input)))
        }
        "assemble" => {
            let def = serde_json::from_value(parse("definition")?).map_err(|e| e.to_string())?;
            Ok(json!(t::assemble(
                request["header"].as_str().unwrap_or(""),
                request["body"].as_str().unwrap_or(""),
                request["footer"].as_str().unwrap_or(""),
                &def
            )?))
        }
        "validate" => {
            t::validate(request["shader"].as_str().unwrap_or(""))?;
            Ok(json!(true))
        }
        "values" => {
            let def = serde_json::from_value(parse("definition")?).map_err(|e| e.to_string())?;
            let input = serde_json::from_value(parse("parameters")?).map_err(|e| e.to_string())?;
            let supplemental: BTreeSet<String> =
                serde_json::from_value(parse("supplemental")?).map_err(|e| e.to_string())?;
            Ok(json!(t::values(&def, &supplemental, &input)))
        }
        "split" => Ok(json!(t::split(
            request["duration"].as_i64().unwrap_or(0),
            request["alignment"].as_i64().unwrap_or(0) as i32
        ))),
        _ => Err("Unknown transition operation".into()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeQuery(
    mut env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    let result = (|| {
        let text: String = env.get_string(&request).map_err(|e| e.to_string())?.into();
        let input = serde_json::from_str(&text).map_err(|e| e.to_string())?;
        query(input)
    })();
    match result {
        Ok(value) => env
            .new_string(value.to_string())
            .map(|v| v.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            std::ptr::null_mut()
        }
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeProgress(
    _env: JNIEnv,
    _class: JClass,
    value: jfloat,
    easing: jint,
    x1: jfloat,
    y1: jfloat,
    x2: jfloat,
    y2: jfloat,
) -> jfloat {
    t::progress(value, easing, [x1, y1, x2, y2])
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeAudioGain(
    _env: JNIEnv,
    _class: JClass,
    value: jfloat,
    incoming: jboolean,
) -> jfloat {
    let p = value.clamp(0., 1.);
    if incoming != 0 {
        (p * std::f32::consts::FRAC_PI_2).sin()
    } else {
        (p * std::f32::consts::FRAC_PI_2).cos().max(0.)
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeHandleSource(
    _env: JNIEnv,
    _class: JClass,
    offset: jlong,
    duration: jlong,
    first: jlong,
    last: jlong,
    total: jlong,
    speed: jfloat,
    supported: jboolean,
    image: jboolean,
    bounded: jlong,
) -> jlong {
    if (0..duration).contains(&offset) || supported == 0 || image != 0 {
        return bounded;
    }
    let value = if offset < 0 {
        first.saturating_add((offset as f64 * speed as f64).round() as i64)
    } else {
        last.saturating_add(((offset - duration) as f64 * speed as f64).round() as i64)
    };
    value.clamp(0, (total - 1).max(0))
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_termex_replay15_editor_transitions_NativeTransitionBridge_nativeFade(
    env: JNIEnv,
    _class: JClass,
    input: jint,
    output: jint,
    fade: jlong,
    duration: jlong,
    local: jlong,
) -> jni::sys::jfloatArray {
    let v = t::fade(input, output, fade, duration, local);
    let result = env.new_float_array(2).and_then(|array| {
        env.set_float_array_region(&array, 0, &v)?;
        Ok(array)
    });
    match result {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
