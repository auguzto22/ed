//! Leitura e escrita de vídeo por meio do executável `ffmpeg` (quadros RGB24 por pipe).
//! Sem dependências nativas para compilar: basta o ffmpeg/ffprobe no PATH.

use crate::bail;
use crate::error::{Context, Result};
use crate::json::Json;
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

#[derive(Clone, Debug)]
pub struct VideoInfo {
    /// Dimensões exibidas (já com a rotação de metadados aplicada).
    pub width: usize,
    pub height: usize,
    pub fps: f64,
    pub duration: Option<f64>,
    pub frames: Option<u64>,
    pub has_audio: bool,
}

fn parse_rate(s: &str) -> Option<f64> {
    let mut it = s.split('/');
    let n: f64 = it.next()?.trim().parse().ok()?;
    let d: f64 = it.next().map(|d| d.trim().parse().ok()).unwrap_or(Some(1.0))?;
    (d > 0.0 && n > 0.0).then(|| n / d)
}

pub fn probe(path: &Path) -> Result<VideoInfo> {
    let out = Command::new("ffprobe")
        .args(["-v", "error", "-show_streams", "-show_format", "-of", "json"])
        .arg(path)
        .output()
        .context("não foi possível executar o ffprobe (ele está instalado?)")?;
    if !out.status.success() {
        bail!("ffprobe falhou: {}", String::from_utf8_lossy(&out.stderr).trim());
    }
    let json = Json::parse(&String::from_utf8_lossy(&out.stdout))?;
    let str_of = |j: Option<&Json>| match j {
        Some(Json::Str(s)) => Some(s.clone()),
        _ => None,
    };
    let num_of = |j: Option<&Json>| match j {
        Some(Json::Num(n)) => Some(*n),
        Some(Json::Str(s)) => s.parse::<f64>().ok(),
        _ => None,
    };
    let streams = json.get("streams").and_then(Json::as_arr).context("sem streams")?;
    let is = |s: &Json, kind: &str| str_of(s.get("codec_type")).as_deref() == Some(kind);
    let video = streams.iter().find(|s| is(s, "video")).context("o arquivo não tem vídeo")?;
    let has_audio = streams.iter().any(|s| is(s, "audio"));
    let mut width = num_of(video.get("width")).context("largura ausente")? as usize;
    let mut height = num_of(video.get("height")).context("altura ausente")? as usize;
    let mut rotation = num_of(video.get("tags").and_then(|t| t.get("rotate"))).unwrap_or(0.0) as i64;
    if let Some(list) = video.get("side_data_list").and_then(Json::as_arr) {
        for sd in list {
            if let Some(r) = num_of(sd.get("rotation")) {
                rotation = r as i64;
            }
        }
    }
    if rotation.rem_euclid(180) == 90 {
        std::mem::swap(&mut width, &mut height);
    }
    let fps = str_of(video.get("avg_frame_rate"))
        .and_then(|r| parse_rate(&r))
        .or_else(|| str_of(video.get("r_frame_rate")).and_then(|r| parse_rate(&r)))
        .unwrap_or(30.0);
    let duration = num_of(video.get("duration")).or_else(|| num_of(json.get("format").and_then(|f| f.get("duration"))));
    let frames = num_of(video.get("nb_frames")).map(|n| n as u64);
    Ok(VideoInfo { width, height, fps, duration, frames, has_audio })
}

/// Leitor de quadros RGB24.
pub struct FrameReader {
    child: Child,
    stdout: ChildStdout,
    pub width: usize,
    pub height: usize,
    pub fps: f64,
    start_us: i64,
    index: u64,
    max_frames: Option<u64>,
}

impl FrameReader {
    /// Abre `path`. `size` reescala os quadros (ex.: menor para rastrear mais rápido).
    /// `start`/`end` em segundos limitam o trecho.
    pub fn open(path: &Path, info: &VideoInfo, size: Option<(usize, usize)>, start: Option<f64>, end: Option<f64>) -> Result<Self> {
        let (width, height) = size.unwrap_or((info.width, info.height));
        let mut cmd = Command::new("ffmpeg");
        cmd.args(["-v", "error", "-nostdin"]);
        if let Some(s) = start.filter(|s| *s > 0.0) {
            cmd.args(["-ss", &format!("{s:.6}")]);
        }
        cmd.arg("-i").arg(path);
        let mut filters = Vec::new();
        if (width, height) != (info.width, info.height) {
            filters.push(format!("scale={width}:{height}:flags=area"));
        }
        if !filters.is_empty() {
            cmd.args(["-vf", &filters.join(",")]);
        }
        cmd.args(["-an", "-sn", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]);
        let mut child = cmd
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .context("não foi possível executar o ffmpeg (ele está instalado?)")?;
        let stdout = child.stdout.take().unwrap();
        let start_s = start.unwrap_or(0.0).max(0.0);
        let max_frames = end.map(|e| (((e - start_s).max(0.0)) * info.fps).ceil() as u64);
        Ok(Self { child, stdout, width, height, fps: info.fps, start_us: (start_s * 1e6).round() as i64, index: 0, max_frames })
    }

    pub fn frame_bytes(&self) -> usize {
        self.width * self.height * 3
    }

    /// Lê o próximo quadro em `buf`. Retorna o tempo em µs, ou `None` no fim.
    pub fn read(&mut self, buf: &mut Vec<u8>) -> Result<Option<i64>> {
        if self.max_frames.is_some_and(|m| self.index >= m) {
            return Ok(None);
        }
        buf.resize(self.frame_bytes(), 0);
        let mut filled = 0;
        while filled < buf.len() {
            let n = self.stdout.read(&mut buf[filled..])?;
            if n == 0 {
                if filled == 0 {
                    return Ok(None);
                }
                bail!("quadro incompleto no fim do vídeo");
            }
            filled += n;
        }
        let t = self.start_us + ((self.index as f64) * 1e6 / self.fps).round() as i64;
        self.index += 1;
        Ok(Some(t))
    }
}

impl Drop for FrameReader {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Codificador de quadros RGB24 em MP4 (H.264 quando disponível), opcionalmente copiando o áudio.
pub struct FrameWriter {
    child: Child,
    stdin: Option<ChildStdin>,
    frame_bytes: usize,
}

fn has_encoder(name: &str) -> bool {
    Command::new("ffmpeg")
        .args(["-hide_banner", "-encoders"])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).lines().any(|l| l.split_whitespace().nth(1) == Some(name)))
        .unwrap_or(false)
}

impl FrameWriter {
    pub fn create(out: &Path, width: usize, height: usize, fps: f64, audio_from: Option<(&Path, f64, Option<f64>)>) -> Result<Self> {
        if !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            bail!("dimensões de saída precisam ser pares ({width}x{height})");
        }
        let mut cmd = Command::new("ffmpeg");
        cmd.args(["-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24"])
            .args(["-s", &format!("{width}x{height}"), "-framerate", &format!("{fps}"), "-i", "-"]);
        if let Some((src, start, end)) = audio_from {
            if start > 0.0 {
                cmd.args(["-ss", &format!("{start:.6}")]);
            }
            if let Some(e) = end {
                cmd.args(["-t", &format!("{:.6}", (e - start).max(0.0))]);
            }
            cmd.arg("-i").arg(src).args(["-map", "0:v:0", "-map", "1:a:0?", "-c:a", "aac", "-b:a", "192k", "-shortest"]);
        }
        if has_encoder("libx264") {
            cmd.args(["-c:v", "libx264", "-preset", "medium", "-crf", "18"]);
        } else {
            cmd.args(["-c:v", "mpeg4", "-q:v", "2"]);
        }
        cmd.args(["-pix_fmt", "yuv420p", "-movflags", "+faststart"]).arg(out);
        let mut child = cmd.stdin(Stdio::piped()).stderr(Stdio::inherit()).spawn().context("não foi possível executar o ffmpeg")?;
        let stdin = child.stdin.take();
        Ok(Self { child, stdin, frame_bytes: width * height * 3 })
    }

    pub fn write(&mut self, frame: &[u8]) -> Result<()> {
        if frame.len() != self.frame_bytes {
            bail!("quadro com {} bytes, esperado {}", frame.len(), self.frame_bytes);
        }
        self.stdin.as_mut().unwrap().write_all(frame).context("o ffmpeg encerrou durante a codificação")
    }

    pub fn finish(mut self) -> Result<()> {
        drop(self.stdin.take());
        let status = self.child.wait()?;
        if !status.success() {
            bail!("a codificação com ffmpeg falhou ({status})");
        }
        Ok(())
    }
}

/// Tamanho par que cabe em `max_width` mantendo a proporção.
pub fn fit_width(width: usize, height: usize, max_width: usize) -> (usize, usize) {
    if max_width == 0 || width <= max_width {
        return (width & !1, height & !1);
    }
    let h = ((height as f64 * max_width as f64 / width as f64) / 2.0).round() as usize * 2;
    (max_width & !1, h.max(2))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_frame_rates() {
        assert_eq!(parse_rate("30/1"), Some(30.0));
        assert!((parse_rate("30000/1001").unwrap() - 29.97).abs() < 0.01);
        assert_eq!(parse_rate("0/0"), None);
    }

    #[test]
    fn fits_even_sizes() {
        assert_eq!(fit_width(1920, 1080, 640), (640, 360));
        assert_eq!(fit_width(1081, 1921, 0), (1080, 1920));
    }
}
