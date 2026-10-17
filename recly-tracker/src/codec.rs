//! Codec binário compatível com `TrackingCodec.kt` e `LayerMaskCodec.kt` (formato
//! `java.io.DataOutputStream`: big-endian, `writeUTF` em UTF-8 modificado). Permite trocar
//! tracks e máscaras com o app sem conversão.

use crate::mask::{CubicBezier, Easing, MaskKeyframe, MaskPathPoint, MaskState, MaskType, MAX_MASK_KEYFRAMES, MAX_MASK_PATH_POINTS};
use crate::track::{TrackingPoint, TrackingTrack, MAX_POINTS};
use crate::error::{Error, Result};
use crate::{bail, ensure};
use std::io::{Read, Write};

pub struct DataOut<W: Write>(pub W);

impl<W: Write> DataOut<W> {
    pub fn int(&mut self, v: i32) -> Result<()> {
        Ok(self.0.write_all(&v.to_be_bytes())?)
    }
    pub fn long(&mut self, v: i64) -> Result<()> {
        Ok(self.0.write_all(&v.to_be_bytes())?)
    }
    pub fn float(&mut self, v: f32) -> Result<()> {
        Ok(self.0.write_all(&v.to_bits().to_be_bytes())?)
    }
    pub fn boolean(&mut self, v: bool) -> Result<()> {
        Ok(self.0.write_all(&[v as u8])?)
    }
    /// `writeUTF`: comprimento u16 + UTF-8 modificado (NUL em 2 bytes, suplementares como surrogates).
    pub fn utf(&mut self, s: &str) -> Result<()> {
        let mut bytes = Vec::with_capacity(s.len());
        for unit in s.encode_utf16() {
            match unit {
                0x0001..=0x007F => bytes.push(unit as u8),
                0x0000 | 0x0080..=0x07FF => {
                    bytes.push(0xC0 | (unit >> 6) as u8);
                    bytes.push(0x80 | (unit & 0x3F) as u8);
                }
                _ => {
                    bytes.push(0xE0 | (unit >> 12) as u8);
                    bytes.push(0x80 | ((unit >> 6) & 0x3F) as u8);
                    bytes.push(0x80 | (unit & 0x3F) as u8);
                }
            }
        }
        ensure!(bytes.len() <= 65535, "texto longo demais para writeUTF");
        self.0.write_all(&(bytes.len() as u16).to_be_bytes())?;
        Ok(self.0.write_all(&bytes)?)
    }
}

pub struct DataIn<R: Read>(pub R);

impl<R: Read> DataIn<R> {
    fn bytes<const N: usize>(&mut self) -> Result<[u8; N]> {
        let mut b = [0u8; N];
        self.0.read_exact(&mut b)?;
        Ok(b)
    }
    pub fn int(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.bytes()?))
    }
    pub fn long(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(self.bytes()?))
    }
    pub fn float(&mut self) -> Result<f32> {
        Ok(f32::from_bits(u32::from_be_bytes(self.bytes()?)))
    }
    pub fn boolean(&mut self) -> Result<bool> {
        Ok(self.bytes::<1>()?[0] != 0)
    }
    pub fn utf(&mut self) -> Result<String> {
        let len = u16::from_be_bytes(self.bytes()?) as usize;
        let mut buf = vec![0u8; len];
        self.0.read_exact(&mut buf)?;
        let mut units = Vec::with_capacity(len);
        let mut i = 0;
        while i < len {
            let a = buf[i] as u16;
            if a < 0x80 {
                units.push(a);
                i += 1;
            } else if a & 0xE0 == 0xC0 && i + 1 < len {
                units.push(((a & 0x1F) << 6) | (buf[i + 1] as u16 & 0x3F));
                i += 2;
            } else if a & 0xF0 == 0xE0 && i + 2 < len {
                units.push(((a & 0x0F) << 12) | ((buf[i + 1] as u16 & 0x3F) << 6) | (buf[i + 2] as u16 & 0x3F));
                i += 3;
            } else {
                bail!("UTF modificado inválido");
            }
        }
        Ok(String::from_utf16(&units)?)
    }
    fn count(&mut self, max: usize) -> Result<usize> {
        let n = self.int()?;
        ensure!(n >= 0 && n as usize <= max, "Projeto incompativel");
        Ok(n as usize)
    }
}

/// Grava um track no mesmo layout de um item de `TrackingCodec.write`.
pub fn write_track<W: Write>(out: &mut DataOut<W>, track: &TrackingTrack) -> Result<()> {
    out.utf(&track.id)?;
    out.int(track.points.len() as i32)?;
    for p in &track.points {
        out.long(p.time_us)?;
        out.float(p.center_x)?;
        out.float(p.center_y)?;
        out.float(p.width)?;
        out.float(p.height)?;
        out.float(p.confidence)?;
    }
    Ok(())
}

pub fn read_track<R: Read>(input: &mut DataIn<R>) -> Result<TrackingTrack> {
    let id = input.utf()?;
    let n = input.count(MAX_POINTS)?;
    let mut points = Vec::with_capacity(n);
    for _ in 0..n {
        points.push(TrackingPoint {
            time_us: input.long()?,
            center_x: input.float()?,
            center_y: input.float()?,
            width: input.float()?,
            height: input.float()?,
            confidence: input.float()?,
        });
    }
    Ok(TrackingTrack::new(id, points)?)
}

/// Grava uma máscara opcional no layout de `LayerMaskCodec.writeMask` (schema ≥ 24).
pub fn write_mask<W: Write>(out: &mut DataOut<W>, mask: Option<&MaskState>) -> Result<()> {
    out.boolean(mask.is_some())?;
    let Some(m) = mask else { return Ok(()) };
    out.int(m.mask_type.ordinal())?;
    for v in [m.center_x, m.center_y, m.width, m.height, m.rotation, m.feather, m.expansion, m.opacity] {
        out.float(v)?;
    }
    out.boolean(m.inverted)?;
    out.boolean(m.tracking_track_id.is_some())?;
    if let Some(id) = &m.tracking_track_id {
        out.utf(id)?;
    }
    out.int(m.custom_path.len() as i32)?;
    for p in &m.custom_path {
        out.float(p.x)?;
        out.float(p.y)?;
    }
    out.int(m.keyframes.len() as i32)?;
    for k in &m.keyframes {
        out.long(k.time_us)?;
        for v in [k.center_x, k.center_y, k.width, k.height, k.rotation, k.feather, k.expansion] {
            out.float(v)?;
        }
        out.int(k.easing.ordinal())?;
        for v in [k.bezier.x1, k.bezier.y1, k.bezier.x2, k.bezier.y2] {
            out.float(v)?;
        }
    }
    Ok(())
}

pub fn read_mask<R: Read>(input: &mut DataIn<R>, has_tracking_id: bool) -> Result<Option<MaskState>> {
    if !input.boolean()? {
        return Ok(None);
    }
    let mask_type = MaskType::from_ordinal(input.int()?).ok_or_else(|| Error("tipo de máscara inválido".into()))?;
    let mut f = [0.0f32; 8];
    for v in &mut f {
        *v = input.float()?;
    }
    let inverted = input.boolean()?;
    let tracking_track_id = if has_tracking_id && input.boolean()? { Some(input.utf()?) } else { None };
    let n = input.count(MAX_MASK_PATH_POINTS)?;
    let mut custom_path = Vec::with_capacity(n);
    for _ in 0..n {
        custom_path.push(MaskPathPoint { x: input.float()?, y: input.float()? });
    }
    let n = input.count(MAX_MASK_KEYFRAMES)?;
    let mut keyframes = Vec::with_capacity(n);
    for _ in 0..n {
        let time_us = input.long()?;
        let mut g = [0.0f32; 7];
        for v in &mut g {
            *v = input.float()?;
        }
        let easing = Easing::from_ordinal(input.int()?).ok_or_else(|| Error("easing inválido".into()))?;
        let bezier = CubicBezier { x1: input.float()?, y1: input.float()?, x2: input.float()?, y2: input.float()? };
        keyframes.push(MaskKeyframe {
            time_us,
            center_x: g[0],
            center_y: g[1],
            width: g[2],
            height: g[3],
            rotation: g[4],
            feather: g[5],
            expansion: g[6],
            easing,
            bezier,
        });
    }
    let m = MaskState {
        mask_type,
        center_x: f[0],
        center_y: f[1],
        width: f[2],
        height: f[3],
        rotation: f[4],
        feather: f[5],
        expansion: f[6],
        opacity: f[7],
        inverted,
        custom_path,
        keyframes,
        tracking_track_id,
    };
    m.validate()?;
    Ok(Some(m))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_and_track_round_trip() {
        let p = |t| TrackingPoint { time_us: t, center_x: 0.5, center_y: 0.25, width: 0.1, height: 0.2, confidence: 0.9 };
        let track = TrackingTrack::new("abc-1", vec![p(0), p(33_333)]).unwrap();
        let mask = MaskState { tracking_track_id: Some("abc-1".into()), ..MaskState::default_for(MaskType::CustomPath) }
            .upsert_keyframe(100, None, 0);
        let mut buf = Vec::new();
        {
            let mut out = DataOut(&mut buf);
            write_track(&mut out, &track).unwrap();
            write_mask(&mut out, Some(&mask)).unwrap();
            write_mask(&mut out, None).unwrap();
        }
        let mut input = DataIn(&buf[..]);
        assert_eq!(read_track(&mut input).unwrap(), track);
        assert_eq!(read_mask(&mut input, true).unwrap(), Some(mask));
        assert_eq!(read_mask(&mut input, true).unwrap(), None);
    }

    #[test]
    fn utf_matches_java_layout() {
        let mut buf = Vec::new();
        DataOut(&mut buf).utf("ab").unwrap();
        assert_eq!(buf, [0, 2, b'a', b'b']);
        let mut buf = Vec::new();
        DataOut(&mut buf).utf("é\0").unwrap();
        assert_eq!(buf, [0, 4, 0xC3, 0xA9, 0xC0, 0x80]);
        assert_eq!(DataIn(&buf[..]).utf().unwrap(), "é\0");
    }
}
