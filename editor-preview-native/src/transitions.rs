//! Pure transition planning and package resolution shared by preview and export.
//! Times are integer microseconds. Planning never opens media or touches a GL context.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::OnceLock;

pub const MIN_DURATION: i64 = 100_000;
pub const MAX_DURATION: i64 = 5_000_000;
pub const CATALOG_JSON: &str =
    include_str!("../../editor-engine/src/main/assets/editor/transitions.json");
const HEADER: &str = include_str!("../../editor-engine/src/main/res/raw/transition_header.glsl");
const FOOTER: &str = include_str!("../../editor-engine/src/main/res/raw/transition_footer.glsl");
mod packages {
    include!("transition_packages.rs");
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Clip {
    pub id: String,
    pub duration_us: i64,
}
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Edit {
    pub id: String,
    pub left_clip_id: String,
    pub right_clip_id: String,
    pub duration_us: i64,
    pub alignment: i32,
}
#[derive(Deserialize)]
pub struct Timeline {
    pub clips: Vec<Clip>,
    pub transitions: Vec<Edit>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Window {
    pub id: String,
    pub left_index: usize,
    pub cut_us: i64,
    pub start_us: i64,
    pub end_us: i64,
}

pub fn split(duration: i64, alignment: i32) -> (i64, i64) {
    let d = duration.max(0);
    match alignment {
        1 => (0, d),
        2 => (d, 0),
        _ => (d / 2, d - d / 2),
    }
}
pub fn plan(timeline: &Timeline) -> Vec<Window> {
    let n = timeline.clips.len();
    if n < 2 {
        return Vec::new();
    }
    let ids: HashMap<_, _> = timeline
        .clips
        .iter()
        .enumerate()
        .map(|(i, c)| (c.id.as_str(), i))
        .collect();
    let mut cuts = vec![None; n];
    let mut before = vec![0_i64; n];
    let mut after = vec![0_i64; n];
    for edit in &timeline.transitions {
        let Some(&i) = ids.get(edit.left_clip_id.as_str()) else {
            continue;
        };
        if i + 1 >= n || timeline.clips[i + 1].id != edit.right_clip_id || cuts[i].is_some() {
            continue;
        }
        cuts[i] = Some(edit);
        (before[i], after[i]) = split(edit.duration_us.clamp(0, MAX_DURATION), edit.alignment);
    }
    for i in 0..n {
        let entering = if i > 0 { after[i - 1] } else { 0 };
        let available = timeline.clips[i].duration_us.max(0);
        let need = entering + before[i];
        if need > available {
            // Integer arithmetic avoids drifting adjacent windows by one frame.
            if i > 0 {
                after[i - 1] = (entering as i128 * available as i128 / need as i128) as i64;
            }
            before[i] = (before[i] as i128 * available as i128 / need as i128) as i64;
        }
    }
    let mut cut = 0_i64;
    let mut windows = Vec::new();
    for i in 0..n - 1 {
        cut = cut.saturating_add(timeline.clips[i].duration_us.max(0));
        if let Some(edit) = cuts[i] {
            if before[i] + after[i] > 0 {
                windows.push(Window {
                    id: edit.id.clone(),
                    left_index: i,
                    cut_us: cut,
                    start_us: cut - before[i],
                    end_us: cut.saturating_add(after[i]),
                });
            }
        }
    }
    windows
}
pub fn max_duration(t: &Timeline, edit: &Edit, ceiling: i64) -> i64 {
    let Some(i) = t.clips.iter().position(|c| c.id == edit.left_clip_id) else {
        return MIN_DURATION;
    };
    if i + 1 >= t.clips.len() || t.clips[i + 1].id != edit.right_clip_id {
        return MIN_DURATION;
    }
    let others = plan(&Timeline {
        clips: t.clips.clone(),
        transitions: t
            .transitions
            .iter()
            .filter(|x| {
                x.left_clip_id != edit.left_clip_id || x.right_clip_id != edit.right_clip_id
            })
            .cloned()
            .collect(),
    });
    let entering = others
        .iter()
        .find(|w| w.left_index + 1 == i)
        .map_or(0, |w| w.end_us - w.cut_us);
    let leaving = others
        .iter()
        .find(|w| w.left_index == i + 1)
        .map_or(0, |w| w.cut_us - w.start_us);
    let a = (t.clips[i].duration_us - entering).max(0);
    let b = (t.clips[i + 1].duration_us - leaving).max(0);
    let room = match edit.alignment {
        1 => b,
        2 => a,
        _ => a.min(b).saturating_mul(2),
    };
    room.min(t.clips[i].duration_us)
        .min(t.clips[i + 1].duration_us)
        .clamp(MIN_DURATION, ceiling.max(MIN_DURATION))
}

pub fn progress(value: f32, easing: i32, bezier: [f32; 4]) -> f32 {
    let t = if value.is_finite() {
        value.clamp(0., 1.)
    } else {
        0.
    };
    if t == 0. || t == 1. {
        return t;
    }
    let result = match easing {
        1 => t * t * (3. - 2. * t),
        2 => t * t,
        3 => 1. - (1. - t) * (1. - t),
        4 => 0.,
        5 => {
            if t < 0.5 {
                2. * t * t
            } else {
                1. - (-2. * t + 2.).powi(2) / 2.
            }
        }
        6 => {
            let coord = |u: f32, a: f32, b: f32| {
                3. * (1. - u).powi(2) * u * a + 3. * (1. - u) * u * u * b + u * u * u
            };
            let (mut low, mut high) = (0., 1.);
            for _ in 0..24 {
                let m = (low + high) * 0.5;
                if coord(m, bezier[0], bezier[2]) < t {
                    low = m
                } else {
                    high = m
                }
            }
            coord((low + high) * 0.5, bezier[1], bezier[3])
        }
        7 => {
            let r = |x: f32| 1. - (-7. * x).exp() * ((12. * x).cos() + 7. / 12. * (12. * x).sin());
            r(t) / r(1.)
        }
        8 => {
            let n = 7.5625;
            let d = 2.75;
            if t < 1. / d {
                n * t * t
            } else if t < 2. / d {
                n * (t - 1.5 / d).powi(2) + 0.75
            } else if t < 2.5 / d {
                n * (t - 2.25 / d).powi(2) + 0.9375
            } else {
                n * (t - 2.625 / d).powi(2) + 0.984375
            }
        }
        9 => {
            2_f32.powf(-10. * t) * ((t * 10. - 0.75) * (2. * std::f32::consts::PI / 3.)).sin() + 1.
        }
        10 => {
            let u = t - 1.;
            1. + 2.70158 * u * u * u + 1.70158 * u * u
        }
        _ => t,
    };
    result.clamp(0., 1.)
}
pub fn fade(input: i32, output: i32, fade: i64, duration: i64, local: i64) -> [f32; 2] {
    let d = fade.min(duration / 2).max(1) as f32;
    let entering = if input == 0 {
        1.
    } else {
        (local as f32 / d).clamp(0., 1.)
    };
    let leaving = if output == 0 {
        1.
    } else {
        ((duration - local) as f32 / d).clamp(0., 1.)
    };
    let amount = entering.min(leaving);
    let white = (input == 2 && entering <= leaving) || (output == 2 && leaving < entering);
    [amount, if white { 1. - amount } else { 0. }]
}

#[derive(Clone, Deserialize)]
pub struct Parameter {
    pub id: String,
    pub min: f32,
    pub max: f32,
    pub default: f32,
}
#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Definition {
    pub id: String,
    pub shader_file: String,
    pub parameters: Vec<Parameter>,
}
pub struct Package {
    pub definition: Definition,
    pub fragment: String,
    pub supplemental: BTreeSet<String>,
}
pub fn tokens(source: &str) -> Vec<String> {
    let bytes = source.as_bytes();
    let mut i = 0;
    let mut out = Vec::new();
    while i < bytes.len() {
        if bytes[i..].starts_with(b"//") {
            while i < bytes.len() && bytes[i] != b'\n' {
                i += 1;
            }
            continue;
        }
        if bytes[i..].starts_with(b"/*") {
            i += 2;
            while i + 1 < bytes.len() && !bytes[i..].starts_with(b"*/") {
                i += 1;
            }
            i = (i + 2).min(bytes.len());
            continue;
        }
        if bytes[i].is_ascii_alphabetic() || bytes[i] == b'_' {
            let start = i;
            i += 1;
            while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') {
                i += 1;
            }
            out.push(source[start..i].to_owned());
        } else {
            i += 1;
        }
    }
    out
}
fn uniforms(source: &str) -> Vec<String> {
    tokens(source)
        .windows(3)
        .filter(|s| s[0] == "uniform")
        .map(|s| s[2].clone())
        .collect()
}
pub fn validate(source: &str) -> Result<(), String> {
    let mut seen = BTreeSet::new();
    for name in uniforms(source) {
        if !seen.insert(name.clone()) {
            return Err(format!("Duplicate GLSL uniform: {name}"));
        }
    }
    Ok(())
}
pub fn body_params(source: &str) -> BTreeSet<String> {
    tokens(source)
        .into_iter()
        .filter_map(|s| s.strip_prefix("p_").map(str::to_owned))
        .collect()
}
pub fn assemble(
    header: &str,
    body: &str,
    footer: &str,
    definition: &Definition,
) -> Result<String, String> {
    validate(body)?;
    let declared: BTreeSet<_> = uniforms(body).into_iter().collect();
    let header = header
        .lines()
        .filter(|line| !uniforms(line).iter().any(|u| declared.contains(u)))
        .collect::<Vec<_>>()
        .join("\n");
    let ids: BTreeSet<_> = definition
        .parameters
        .iter()
        .map(|p| p.id.clone())
        .chain(body_params(body))
        .collect();
    let generated = ids
        .into_iter()
        .filter(|id| !declared.contains(&format!("p_{id}")))
        .map(|id| format!("uniform float p_{id};"))
        .collect::<Vec<_>>()
        .join("\n");
    let result = format!("{header}\n{generated}\n{body}\n{footer}");
    validate(&result)?;
    Ok(result)
}
pub fn supplemental_default(id: &str) -> f32 {
    match id {
        "perspective" | "intensity" | "strength" | "blur" => 1.,
        "radius" => 16.,
        "softness" => 0.1,
        _ => 0.,
    }
}
pub fn values(
    definition: &Definition,
    supplemental: &BTreeSet<String>,
    input: &BTreeMap<String, f32>,
) -> BTreeMap<String, f32> {
    let mut result = BTreeMap::new();
    for p in &definition.parameters {
        let v = if p.id == "mode" {
            p.default
        } else {
            input
                .get(&p.id)
                .copied()
                .filter(|v| v.is_finite())
                .unwrap_or(p.default)
        };
        result.insert(format!("p_{}", p.id), v.clamp(p.min, p.max));
    }
    for id in supplemental {
        result.insert(format!("p_{id}"), supplemental_default(id));
    }
    result
}
pub fn catalogue() -> Result<&'static HashMap<String, Package>, String> {
    static CATALOG: OnceLock<Result<HashMap<String, Package>, String>> = OnceLock::new();
    CATALOG
        .get_or_init(|| {
            let definitions: Vec<Definition> =
                serde_json::from_str(CATALOG_JSON).map_err(|e| e.to_string())?;
            let mut result = HashMap::new();
            for definition in definitions {
                if result.contains_key(&definition.id) {
                    return Err(format!("Duplicate transition {}", definition.id));
                }
                if definition.parameters.iter().any(|p| {
                    p.min > p.max
                        || !p.default.is_finite()
                        || p.default < p.min
                        || p.default > p.max
                }) {
                    return Err(format!("Invalid parameters {}", definition.id));
                }
                let body = packages::body(&definition.shader_file)
                    .ok_or_else(|| format!("Missing shader {}", definition.shader_file))?;
                let fragment = assemble(HEADER, body, FOOTER, &definition)?;
                let declared: BTreeSet<_> =
                    definition.parameters.iter().map(|p| p.id.clone()).collect();
                let supplemental = body_params(body).difference(&declared).cloned().collect();
                result.insert(
                    definition.id.clone(),
                    Package {
                        definition,
                        fragment,
                        supplemental,
                    },
                );
            }
            Ok(result)
        })
        .as_ref()
        .map_err(Clone::clone)
}
pub fn package(id: &str) -> Result<&'static Package, String> {
    catalogue()?
        .get(id)
        .ok_or_else(|| format!("Unknown transition: {id}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn every_package_resolves() {
        let c = catalogue().unwrap();
        assert_eq!(c.len(), 99);
        for p in c.values() {
            assert!(!p.fragment.contains("uniform float p_p_"));
            assert!(p.fragment.contains("uProgress <= 0.0"));
        }
    }
    #[test]
    fn adjacent_windows_do_not_overlap() {
        let t = Timeline {
            clips: vec![
                Clip {
                    id: "a".into(),
                    duration_us: 1_000_000,
                },
                Clip {
                    id: "b".into(),
                    duration_us: 100_000,
                },
                Clip {
                    id: "c".into(),
                    duration_us: 1_000_000,
                },
            ],
            transitions: vec![
                Edit {
                    id: "ab".into(),
                    left_clip_id: "a".into(),
                    right_clip_id: "b".into(),
                    duration_us: 1_000_000,
                    alignment: 0,
                },
                Edit {
                    id: "bc".into(),
                    left_clip_id: "b".into(),
                    right_clip_id: "c".into(),
                    duration_us: 1_000_000,
                    alignment: 0,
                },
            ],
        };
        let w = plan(&t);
        assert_eq!(w.len(), 2);
        assert!(w[0].end_us <= w[1].start_us);
        assert_eq!(w[0].cut_us, 1_000_000);
    }
    #[test]
    fn parameter_mode_is_not_overridden() {
        let p = package("cross_dissolve").unwrap();
        let v = values(
            &p.definition,
            &p.supplemental,
            &BTreeMap::from([("mode".into(), 4.), ("softness".into(), 99.)]),
        );
        assert_eq!(v["p_mode"], 0.);
        assert_eq!(v["p_softness"], 1.);
    }
    #[test]
    fn easing_has_exact_endpoints_and_stays_bounded() {
        for e in 0..11 {
            assert_eq!(progress(0., e, [0.25, 0.1, 0.25, 1.]), 0.);
            assert_eq!(progress(1., e, [0.25, 0.1, 0.25, 1.]), 1.);
            for i in 0..100 {
                assert!((0.0..=1.0).contains(&progress(i as f32 / 100., e, [0.25, 0.1, 0.25, 1.])));
            }
        }
    }
}
