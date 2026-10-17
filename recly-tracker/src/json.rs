//! JSON mínimo (analisador + escritor) e a conversão dos tipos do crate.
//! Os nomes dos campos são os mesmos das classes Kotlin do app.

use crate::error::{Error, Result};
use crate::mask::{CubicBezier, Easing, MaskKeyframe, MaskPathPoint, MaskState, MaskType};
use crate::track::{TrackingPoint, TrackingRegion, TrackingTrack};
use std::fmt::Write as _;

#[derive(Clone, Debug, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    Num(f64),
    Str(String),
    Arr(Vec<Json>),
    Obj(Vec<(String, Json)>),
}

impl Json {
    pub fn parse(text: &str) -> Result<Json> {
        let mut p = Parser { s: text.as_bytes(), i: 0 };
        p.ws();
        let v = p.value(0)?;
        p.ws();
        if p.i != p.s.len() {
            return Err(p.err("conteúdo extra depois do JSON"));
        }
        Ok(v)
    }

    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Obj(fields) => fields.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    fn field(&self, key: &str) -> Result<&Json> {
        self.get(key).ok_or_else(|| Error(format!("campo \"{key}\" ausente")))
    }

    pub fn as_f64(&self) -> Option<f64> {
        match self {
            Json::Num(n) => Some(*n),
            _ => None,
        }
    }

    pub fn f32(&self, key: &str) -> Result<f32> {
        self.field(key)?.as_f64().map(|v| v as f32).ok_or_else(|| Error(format!("\"{key}\" deve ser número")))
    }

    pub fn i64(&self, key: &str) -> Result<i64> {
        let v = self.field(key)?.as_f64().ok_or_else(|| Error(format!("\"{key}\" deve ser número")))?;
        if v.fract() != 0.0 || v.abs() > 9.0e15 {
            return Err(Error(format!("\"{key}\" deve ser inteiro")));
        }
        Ok(v as i64)
    }

    pub fn bool(&self, key: &str) -> Result<bool> {
        match self.field(key)? {
            Json::Bool(b) => Ok(*b),
            _ => Err(Error(format!("\"{key}\" deve ser booleano"))),
        }
    }

    pub fn str(&self, key: &str) -> Result<&str> {
        match self.field(key)? {
            Json::Str(s) => Ok(s),
            _ => Err(Error(format!("\"{key}\" deve ser texto"))),
        }
    }

    pub fn arr(&self, key: &str) -> Result<&[Json]> {
        match self.field(key)? {
            Json::Arr(a) => Ok(a),
            _ => Err(Error(format!("\"{key}\" deve ser lista"))),
        }
    }

    pub fn as_arr(&self) -> Option<&[Json]> {
        match self {
            Json::Arr(a) => Some(a),
            _ => None,
        }
    }

    pub fn write(&self, out: &mut String, indent: Option<usize>) {
        match self {
            Json::Null => out.push_str("null"),
            Json::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
            Json::Num(n) => {
                if n.is_finite() {
                    if n.fract() == 0.0 && n.abs() < 1e15 {
                        let _ = write!(out, "{}", *n as i64);
                    } else {
                        // f32 imprime o menor decimal que volta ao mesmo f32.
                        let f = *n as f32;
                        if f as f64 == *n {
                            let _ = write!(out, "{f}");
                        } else {
                            let _ = write!(out, "{n}");
                        }
                    }
                } else {
                    out.push_str("null");
                }
            }
            Json::Str(s) => write_str(out, s),
            Json::Arr(items) => {
                out.push('[');
                for (i, v) in items.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    newline(out, indent.map(|d| d + 1));
                    v.write(out, indent.map(|d| d + 1));
                }
                if !items.is_empty() {
                    newline(out, indent);
                }
                out.push(']');
            }
            Json::Obj(fields) => {
                out.push('{');
                for (i, (k, v)) in fields.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    newline(out, indent.map(|d| d + 1));
                    write_str(out, k);
                    out.push(':');
                    if indent.is_some() {
                        out.push(' ');
                    }
                    v.write(out, indent.map(|d| d + 1));
                }
                if !fields.is_empty() {
                    newline(out, indent);
                }
                out.push('}');
            }
        }
    }

    pub fn to_compact(&self) -> String {
        let mut s = String::new();
        self.write(&mut s, None);
        s
    }

    pub fn to_pretty(&self) -> String {
        let mut s = String::new();
        self.write(&mut s, Some(0));
        s
    }
}

fn newline(out: &mut String, indent: Option<usize>) {
    if let Some(d) = indent {
        out.push('\n');
        for _ in 0..d {
            out.push_str("  ");
        }
    }
}

fn write_str(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

struct Parser<'a> {
    s: &'a [u8],
    i: usize,
}

impl Parser<'_> {
    fn err(&self, msg: &str) -> Error {
        Error(format!("JSON inválido na posição {}: {msg}", self.i))
    }

    fn ws(&mut self) {
        while self.i < self.s.len() && matches!(self.s[self.i], b' ' | b'\n' | b'\r' | b'\t') {
            self.i += 1;
        }
    }

    fn eat(&mut self, lit: &str) -> bool {
        if self.s[self.i..].starts_with(lit.as_bytes()) {
            self.i += lit.len();
            true
        } else {
            false
        }
    }

    fn value(&mut self, depth: usize) -> Result<Json> {
        if depth > 64 {
            return Err(self.err("aninhamento profundo demais"));
        }
        match self.s.get(self.i) {
            None => Err(self.err("fim inesperado")),
            Some(b'{') => {
                self.i += 1;
                let mut fields = Vec::new();
                self.ws();
                if self.eat("}") {
                    return Ok(Json::Obj(fields));
                }
                loop {
                    self.ws();
                    let k = self.string()?;
                    self.ws();
                    if !self.eat(":") {
                        return Err(self.err("esperado ':'"));
                    }
                    self.ws();
                    let v = self.value(depth + 1)?;
                    fields.push((k, v));
                    self.ws();
                    if self.eat(",") {
                        continue;
                    }
                    if self.eat("}") {
                        return Ok(Json::Obj(fields));
                    }
                    return Err(self.err("esperado ',' ou '}'"));
                }
            }
            Some(b'[') => {
                self.i += 1;
                let mut items = Vec::new();
                self.ws();
                if self.eat("]") {
                    return Ok(Json::Arr(items));
                }
                loop {
                    self.ws();
                    items.push(self.value(depth + 1)?);
                    self.ws();
                    if self.eat(",") {
                        continue;
                    }
                    if self.eat("]") {
                        return Ok(Json::Arr(items));
                    }
                    return Err(self.err("esperado ',' ou ']'"));
                }
            }
            Some(b'"') => Ok(Json::Str(self.string()?)),
            Some(b't') if self.eat("true") => Ok(Json::Bool(true)),
            Some(b'f') if self.eat("false") => Ok(Json::Bool(false)),
            Some(b'n') if self.eat("null") => Ok(Json::Null),
            Some(c) if *c == b'-' || c.is_ascii_digit() => {
                let start = self.i;
                while self.i < self.s.len() && matches!(self.s[self.i], b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9') {
                    self.i += 1;
                }
                let txt = std::str::from_utf8(&self.s[start..self.i]).map_err(|_| self.err("número"))?;
                txt.parse::<f64>().map(Json::Num).map_err(|_| self.err("número inválido"))
            }
            Some(_) => Err(self.err("valor inesperado")),
        }
    }

    fn string(&mut self) -> Result<String> {
        if !self.eat("\"") {
            return Err(self.err("esperado texto"));
        }
        let mut out = String::new();
        loop {
            let Some(&c) = self.s.get(self.i) else { return Err(self.err("texto sem fim")) };
            self.i += 1;
            match c {
                b'"' => return Ok(out),
                b'\\' => {
                    let Some(&e) = self.s.get(self.i) else { return Err(self.err("escape")) };
                    self.i += 1;
                    match e {
                        b'"' => out.push('"'),
                        b'\\' => out.push('\\'),
                        b'/' => out.push('/'),
                        b'b' => out.push('\u{8}'),
                        b'f' => out.push('\u{c}'),
                        b'n' => out.push('\n'),
                        b'r' => out.push('\r'),
                        b't' => out.push('\t'),
                        b'u' => {
                            let mut code = self.hex4()?;
                            if (0xD800..0xDC00).contains(&code) && self.eat("\\u") {
                                let low = self.hex4()?;
                                code = 0x10000 + ((code - 0xD800) << 10) + (low.wrapping_sub(0xDC00) & 0x3FF);
                            }
                            out.push(char::from_u32(code).unwrap_or('\u{FFFD}'));
                        }
                        _ => return Err(self.err("escape inválido")),
                    }
                }
                _ => {
                    // Copia a sequência UTF-8 completa.
                    let start = self.i - 1;
                    let len = match c {
                        0x00..=0x7F => 1,
                        0xC0..=0xDF => 2,
                        0xE0..=0xEF => 3,
                        _ => 4,
                    };
                    let end = (start + len).min(self.s.len());
                    out.push_str(std::str::from_utf8(&self.s[start..end]).map_err(|_| self.err("UTF-8 inválido"))?);
                    self.i = end;
                }
            }
        }
    }

    fn hex4(&mut self) -> Result<u32> {
        let h = self.s.get(self.i..self.i + 4).ok_or_else(|| self.err("\\u curto"))?;
        let v = u32::from_str_radix(std::str::from_utf8(h).map_err(|_| self.err("hex"))?, 16).map_err(|_| self.err("hex"))?;
        self.i += 4;
        Ok(v)
    }
}

pub trait ToJson {
    fn to_json(&self) -> Json;
}

pub trait FromJson: Sized {
    fn from_json(j: &Json) -> Result<Self>;
}

fn obj(fields: Vec<(&str, Json)>) -> Json {
    Json::Obj(fields.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
}

fn num(v: f32) -> Json {
    Json::Num(v as f64)
}

impl<T: ToJson> ToJson for Vec<T> {
    fn to_json(&self) -> Json {
        Json::Arr(self.iter().map(ToJson::to_json).collect())
    }
}

impl<T: FromJson> FromJson for Vec<T> {
    fn from_json(j: &Json) -> Result<Self> {
        j.as_arr().ok_or_else(|| Error("esperada uma lista".into()))?.iter().map(T::from_json).collect()
    }
}

impl ToJson for TrackingRegion {
    fn to_json(&self) -> Json {
        obj(vec![("centerX", num(self.center_x)), ("centerY", num(self.center_y)), ("width", num(self.width)), ("height", num(self.height))])
    }
}

impl FromJson for TrackingRegion {
    fn from_json(j: &Json) -> Result<Self> {
        Ok(TrackingRegion::new(j.f32("centerX")?, j.f32("centerY")?, j.f32("width")?, j.f32("height")?)?)
    }
}

impl ToJson for TrackingPoint {
    fn to_json(&self) -> Json {
        obj(vec![
            ("timeUs", Json::Num(self.time_us as f64)),
            ("centerX", num(self.center_x)),
            ("centerY", num(self.center_y)),
            ("width", num(self.width)),
            ("height", num(self.height)),
            ("confidence", num(self.confidence)),
        ])
    }
}

impl FromJson for TrackingPoint {
    fn from_json(j: &Json) -> Result<Self> {
        Ok(TrackingPoint {
            time_us: j.i64("timeUs")?,
            center_x: j.f32("centerX")?,
            center_y: j.f32("centerY")?,
            width: j.f32("width")?,
            height: j.f32("height")?,
            confidence: j.f32("confidence")?,
        })
    }
}

impl ToJson for TrackingTrack {
    fn to_json(&self) -> Json {
        obj(vec![("id", Json::Str(self.id.clone())), ("points", self.points.to_json())])
    }
}

impl FromJson for TrackingTrack {
    fn from_json(j: &Json) -> Result<Self> {
        let points = Vec::<TrackingPoint>::from_json(j.get("points").ok_or_else(|| Error("campo \"points\" ausente".into()))?)?;
        Ok(TrackingTrack::new(j.str("id")?, points)?)
    }
}

fn enum_name(debug: String) -> String {
    // CamelCase → SCREAMING_SNAKE_CASE (igual aos nomes do enum Kotlin).
    let mut out = String::new();
    for (i, c) in debug.chars().enumerate() {
        if c.is_uppercase() && i > 0 {
            out.push('_');
        }
        out.push(c.to_ascii_uppercase());
    }
    out
}

impl ToJson for MaskType {
    fn to_json(&self) -> Json {
        Json::Str(enum_name(format!("{self:?}")))
    }
}

impl FromJson for MaskType {
    fn from_json(j: &Json) -> Result<Self> {
        let Json::Str(s) = j else { return Err(Error("tipo de máscara deve ser texto".into())) };
        MaskType::ALL.into_iter().find(|t| enum_name(format!("{t:?}")) == *s).ok_or_else(|| Error(format!("tipo de máscara desconhecido: {s}")))
    }
}

impl ToJson for Easing {
    fn to_json(&self) -> Json {
        Json::Str(enum_name(format!("{self:?}")))
    }
}

impl FromJson for Easing {
    fn from_json(j: &Json) -> Result<Self> {
        let Json::Str(s) = j else { return Err(Error("easing deve ser texto".into())) };
        Easing::ALL.into_iter().find(|e| enum_name(format!("{e:?}")) == *s).ok_or_else(|| Error(format!("easing desconhecido: {s}")))
    }
}

impl ToJson for CubicBezier {
    fn to_json(&self) -> Json {
        obj(vec![("x1", num(self.x1)), ("y1", num(self.y1)), ("x2", num(self.x2)), ("y2", num(self.y2))])
    }
}

impl FromJson for CubicBezier {
    fn from_json(j: &Json) -> Result<Self> {
        Ok(CubicBezier { x1: j.f32("x1")?, y1: j.f32("y1")?, x2: j.f32("x2")?, y2: j.f32("y2")? })
    }
}

impl ToJson for MaskPathPoint {
    fn to_json(&self) -> Json {
        obj(vec![("x", num(self.x)), ("y", num(self.y))])
    }
}

impl FromJson for MaskPathPoint {
    fn from_json(j: &Json) -> Result<Self> {
        Ok(MaskPathPoint { x: j.f32("x")?, y: j.f32("y")? })
    }
}

impl ToJson for MaskKeyframe {
    fn to_json(&self) -> Json {
        obj(vec![
            ("timeUs", Json::Num(self.time_us as f64)),
            ("centerX", num(self.center_x)),
            ("centerY", num(self.center_y)),
            ("width", num(self.width)),
            ("height", num(self.height)),
            ("rotation", num(self.rotation)),
            ("feather", num(self.feather)),
            ("expansion", num(self.expansion)),
            ("easing", self.easing.to_json()),
            ("bezier", self.bezier.to_json()),
        ])
    }
}

impl FromJson for MaskKeyframe {
    fn from_json(j: &Json) -> Result<Self> {
        Ok(MaskKeyframe {
            time_us: j.i64("timeUs")?,
            center_x: j.f32("centerX")?,
            center_y: j.f32("centerY")?,
            width: j.f32("width")?,
            height: j.f32("height")?,
            rotation: j.f32("rotation")?,
            feather: j.f32("feather")?,
            expansion: j.f32("expansion")?,
            easing: j.get("easing").map(Easing::from_json).transpose()?.unwrap_or_default(),
            bezier: j.get("bezier").map(CubicBezier::from_json).transpose()?.unwrap_or_default(),
        })
    }
}

impl ToJson for MaskState {
    fn to_json(&self) -> Json {
        let mut fields = vec![
            ("type", self.mask_type.to_json()),
            ("centerX", num(self.center_x)),
            ("centerY", num(self.center_y)),
            ("width", num(self.width)),
            ("height", num(self.height)),
            ("rotation", num(self.rotation)),
            ("feather", num(self.feather)),
            ("expansion", num(self.expansion)),
            ("opacity", num(self.opacity)),
            ("inverted", Json::Bool(self.inverted)),
            ("customPath", self.custom_path.to_json()),
            ("keyframes", self.keyframes.to_json()),
        ];
        if let Some(id) = &self.tracking_track_id {
            fields.push(("trackingTrackId", Json::Str(id.clone())));
        }
        obj(fields)
    }
}

impl FromJson for MaskState {
    fn from_json(j: &Json) -> Result<Self> {
        let mask_type = MaskType::from_json(j.get("type").ok_or_else(|| Error("campo \"type\" ausente".into()))?)?;
        let base = MaskState::default_for(mask_type);
        let opt_f32 = |k: &str, d: f32| -> Result<f32> {
            if j.get(k).is_some() {
                j.f32(k)
            } else {
                Ok(d)
            }
        };
        let m = MaskState {
            mask_type,
            center_x: opt_f32("centerX", base.center_x)?,
            center_y: opt_f32("centerY", base.center_y)?,
            width: opt_f32("width", base.width)?,
            height: opt_f32("height", base.height)?,
            rotation: opt_f32("rotation", base.rotation)?,
            feather: opt_f32("feather", base.feather)?,
            expansion: opt_f32("expansion", base.expansion)?,
            opacity: opt_f32("opacity", base.opacity)?,
            inverted: if j.get("inverted").is_some() { j.bool("inverted")? } else { false },
            custom_path: match j.get("customPath") {
                Some(v) => Vec::from_json(v)?,
                None => base.custom_path,
            },
            keyframes: match j.get("keyframes") {
                Some(v) => Vec::from_json(v)?,
                None => Vec::new(),
            },
            tracking_track_id: match j.get("trackingTrackId") {
                None | Some(Json::Null) => None,
                Some(_) => Some(j.str("trackingTrackId")?.to_string()),
            },
        };
        m.validate()?;
        Ok(m)
    }
}

/// Converte para texto JSON formatado.
pub fn to_string_pretty<T: ToJson + ?Sized>(v: &T) -> String {
    v.to_json().to_pretty()
}

pub fn to_string<T: ToJson + ?Sized>(v: &T) -> String {
    v.to_json().to_compact()
}

pub fn from_str<T: FromJson>(s: &str) -> Result<T> {
    T::from_json(&Json::parse(s)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_prints() {
        let j = Json::parse(r#" {"a": [1, 2.5, -3e2, true, null], "b": "x\"é\n", "c": {}} "#).unwrap();
        assert_eq!(j.get("a").unwrap().as_arr().unwrap()[2], Json::Num(-300.0));
        assert_eq!(j.str("b").unwrap(), "x\"é\n");
        assert_eq!(Json::parse(&j.to_compact()).unwrap(), j);
        assert_eq!(Json::parse(&j.to_pretty()).unwrap(), j);
        assert!(Json::parse("{\"a\":1,}").is_err());
        assert!(Json::parse("[1] x").is_err());
    }

    #[test]
    fn floats_round_trip_exactly() {
        for v in [0.1f32, 1.0 / 3.0, 0.75, 1e-4, 0.123_456_79] {
            let s = Json::Num(v as f64).to_compact();
            assert_eq!(s.parse::<f32>().unwrap(), v, "{s}");
        }
    }

    #[test]
    fn mask_uses_kotlin_names_and_round_trips() {
        let m = MaskState { tracking_track_id: Some("abc".into()), ..MaskState::default_for(MaskType::CustomPath) }.upsert_keyframe(10, None, 0);
        let s = to_string(&m);
        assert!(s.contains("\"type\":\"CUSTOM_PATH\"") && s.contains("\"trackingTrackId\":\"abc\"") && s.contains("\"easing\":\"SMOOTH\""));
        assert_eq!(from_str::<MaskState>(&s).unwrap(), m);
        let minimal: MaskState = from_str(r#"{"type":"ELLIPSE","width":0.3}"#).unwrap();
        assert_eq!(minimal.width, 0.3);
        assert_eq!(minimal.feather, 0.04);
        assert_eq!(enum_name("EaseInOut".into()), "EASE_IN_OUT");
    }

    #[test]
    fn track_round_trips() {
        let p = TrackingPoint { time_us: 33_333, center_x: 0.25, center_y: 0.5, width: 0.1, height: 0.2, confidence: 0.875 };
        let t = TrackingTrack::new("t-1", vec![p]).unwrap();
        assert_eq!(from_str::<TrackingTrack>(&to_string_pretty(&t)).unwrap(), t);
    }
}
