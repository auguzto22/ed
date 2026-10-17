//! Áudio com velocidade variável para a prévia: estéreo intercalado em `f32`, 48 kHz.
//!
//! Dois modos, os dois contínuos (a velocidade pode mudar a cada bloco sem reiniciar nada, então
//! uma rampa não estala nem perde amostras):
//!
//! - [`Varispeed`]: o tom acompanha a velocidade (fita acelerada). Reamostragem por sinc
//!   janelado (Lanczos), com o corte reduzido quando acelera para não criar aliasing.
//! - [`Wsola`]: preserva o tom (time-stretching). Segmentos de 40 ms com sobreposição de 50 %,
//!   cada um buscado na entrada (±12 ms) onde melhor continua o anterior e somados com janela de
//!   Hann. O avanço na entrada por segmento é `velocidade × avanço na saída`, então o consumo da
//!   mídia acompanha exatamente a timeline.
//!
//! [`RetimeStream`] junta os dois com filas de entrada e saída: o chamador empurra mídia e puxa
//! exatamente o tamanho do bloco que precisa. Nunca há descarte nem preenchimento com silêncio
//! fora do fim do arquivo.

pub mod api;

use std::collections::VecDeque;
use std::f32::consts::PI;

pub const CHANNELS: usize = 2;

/// Reamostrador com o tom acompanhando a velocidade.
pub struct Varispeed {
    history: VecDeque<[f32; 2]>,
    /// Posição fracionária da próxima amostra de saída, relativa a `history[0]`.
    position: f64,
    /// Quadros já descartados do começo de `history` (para a posição absoluta na mídia).
    dropped: f64,
}

const LANCZOS_A: f64 = 4.0;

fn lanczos(x: f64, a: f64) -> f64 {
    if x.abs() < 1e-9 {
        return 1.0;
    }
    if x.abs() >= a {
        return 0.0;
    }
    let px = std::f64::consts::PI * x;
    a * px.sin() * (px / a).sin() / (px * px)
}

impl Default for Varispeed {
    fn default() -> Self {
        Self::new()
    }
}

impl Varispeed {
    pub fn new() -> Varispeed {
        // Começa com silêncio suficiente para o filtro olhar para trás.
        let mut history = VecDeque::new();
        for _ in 0..64 {
            history.push_back([0.0, 0.0]);
        }
        Varispeed { history, position: 64.0, dropped: -64.0 }
    }

    /// Posição na mídia (em quadros desde o primeiro empurrado) da próxima amostra de saída.
    pub fn media_position(&self) -> f64 {
        self.dropped + self.position
    }

    pub fn push(&mut self, input: &[f32]) {
        for frame in input.chunks_exact(CHANNELS) {
            self.history.push_back([frame[0], frame[1]]);
        }
    }

    /// Raio do filtro em amostras de entrada para esta velocidade.
    fn support(speed: f64) -> f64 {
        LANCZOS_A * speed.max(1.0)
    }

    /// Quadros de entrada ainda necessários para produzir `frames` quadros a `speed`.
    pub fn input_needed(&self, frames: usize, speed: f64) -> usize {
        let last = self.position + frames as f64 * speed + Self::support(speed) + 2.0;
        (last.ceil() as usize).saturating_sub(self.history.len())
    }

    /// Produz até `frames` quadros (menos se faltar entrada). Devolve quantos produziu.
    pub fn produce(&mut self, frames: usize, speed: f64, out: &mut Vec<f32>) -> usize {
        let speed = speed.clamp(0.05, 32.0);
        let support = Self::support(speed);
        let scale = 1.0 / speed.max(1.0); // corte do filtro relativo a Nyquist
        let mut produced = 0;
        while produced < frames {
            let center = self.position;
            if center + support + 1.0 >= self.history.len() as f64 {
                break;
            }
            let first = (center - support).floor().max(0.0) as usize;
            let last = (center + support).ceil() as usize;
            let (mut left, mut right, mut weights) = (0.0f64, 0.0f64, 0.0f64);
            for index in first..=last.min(self.history.len() - 1) {
                let w = lanczos((index as f64 - center) * scale, LANCZOS_A);
                let sample = self.history[index];
                left += w * sample[0] as f64;
                right += w * sample[1] as f64;
                weights += w;
            }
            let norm = if weights.abs() > 1e-9 { 1.0 / weights } else { 0.0 };
            out.push((left * norm) as f32);
            out.push((right * norm) as f32);
            produced += 1;
            self.position += speed;
        }
        // Descarta o que ficou para trás do filtro.
        let keep_from = (self.position - support - 2.0).floor().max(0.0) as usize;
        if keep_from > 0 {
            let removed = keep_from.min(self.history.len());
            self.history.drain(..removed);
            self.position -= removed as f64;
            self.dropped += removed as f64;
        }
        produced
    }
}

/// Time-stretching que preserva o tom (WSOLA).
pub struct Wsola {
    input: VecDeque<[f32; 2]>,
    /// Início, em `input`, do segmento sintetizado por último.
    last_segment: f64,
    /// Posição nominal (sem a busca) do próximo segmento, relativa a `input[0]`.
    next_nominal: f64,
    /// Metade final do segmento anterior, ainda a ser somada.
    tail: Vec<[f32; 2]>,
    window: Vec<f32>,
    started: bool,
    dropped: f64,
}

const SEGMENT: usize = 1920; // 40 ms a 48 kHz
const HOP: usize = SEGMENT / 2;
const SEARCH: usize = 576; // ±12 ms
const CORRELATION_STEP: usize = 4; // decimação da busca

impl Default for Wsola {
    fn default() -> Self {
        Self::new()
    }
}

impl Wsola {
    pub fn new() -> Wsola {
        let window = (0..SEGMENT).map(|i| 0.5 - 0.5 * (2.0 * PI * i as f32 / SEGMENT as f32).cos()).collect();
        Wsola { input: VecDeque::new(), last_segment: 0.0, next_nominal: 0.0, tail: vec![[0.0; 2]; HOP], window, started: false, dropped: 0.0 }
    }

    /// Posição nominal na mídia (quadros desde o primeiro empurrado) do próximo segmento.
    pub fn media_position(&self) -> f64 {
        self.dropped + self.next_nominal
    }

    pub fn push(&mut self, input: &[f32]) {
        for frame in input.chunks_exact(CHANNELS) {
            self.input.push_back([frame[0], frame[1]]);
        }
    }

    pub fn input_needed(&self, frames: usize, speed: f64) -> usize {
        let segments = frames.div_ceil(HOP) + 1;
        let furthest = self.next_nominal + segments as f64 * HOP as f64 * speed + (SEGMENT + SEARCH) as f64 + 2.0;
        (furthest.ceil() as usize).saturating_sub(self.input.len())
    }

    fn mono(&self, at: usize) -> f32 {
        let s = self.input[at];
        (s[0] + s[1]) * 0.5
    }

    /// Melhor início perto de `nominal`: o que mais se parece com a continuação natural do
    /// segmento anterior (`last + HOP`), por correlação normalizada.
    fn search(&self, nominal: usize) -> usize {
        if !self.started {
            return nominal;
        }
        let reference = (self.last_segment as usize) + HOP;
        let low = nominal.saturating_sub(SEARCH);
        let high = nominal + SEARCH;
        let mut best = nominal;
        let mut best_score = f32::NEG_INFINITY;
        let mut candidate = low;
        while candidate <= high {
            let (mut dot, mut energy) = (0.0f32, 1e-9f32);
            let mut k = 0;
            while k < HOP {
                let a = self.mono(reference + k);
                let b = self.mono(candidate + k);
                dot += a * b;
                energy += b * b;
                k += CORRELATION_STEP * 2;
            }
            let score = dot / energy.sqrt();
            if score > best_score {
                best_score = score;
                best = candidate;
            }
            candidate += CORRELATION_STEP;
        }
        best
    }

    pub fn produce(&mut self, frames: usize, speed: f64, out: &mut Vec<f32>) -> usize {
        let speed = speed.clamp(0.05, 32.0);
        let mut produced = 0;
        while produced + HOP <= frames {
            let nominal = self.next_nominal.round().max(0.0) as usize;
            let reference_end = self.last_segment as usize + HOP + HOP;
            let needed = (nominal + SEARCH + SEGMENT).max(reference_end);
            if needed >= self.input.len() {
                break;
            }
            let start = self.search(nominal);
            // Primeira metade do segmento + cauda do anterior = HOP quadros prontos.
            for i in 0..HOP {
                let s = self.input[start + i];
                let w = self.window[i];
                let t = self.tail[i];
                out.push(t[0] + s[0] * w);
                out.push(t[1] + s[1] * w);
            }
            for i in 0..HOP {
                let s = self.input[start + HOP + i];
                let w = self.window[HOP + i];
                self.tail[i] = [s[0] * w, s[1] * w];
            }
            produced += HOP;
            self.started = true;
            self.last_segment = start as f64;
            self.next_nominal += HOP as f64 * speed;
            // Descarta entrada que nenhuma busca futura alcança.
            let keep_from = (self.next_nominal as usize).saturating_sub(SEARCH + 1).min(self.last_segment as usize);
            if keep_from > 0 {
                self.input.drain(..keep_from);
                self.next_nominal -= keep_from as f64;
                self.last_segment -= keep_from as f64;
                self.dropped += keep_from as f64;
            }
        }
        produced
    }
}

/// Fluxo retemporizado com filas: empurra mídia, puxa blocos exatos da timeline.
pub struct RetimeStream {
    varispeed: Varispeed,
    wsola: Wsola,
    output: VecDeque<f32>,
    preserve_pitch: bool,
    scratch: Vec<f32>,
    /// Mídia consumida (quadros) e timeline produzida (quadros), para medir a sincronia.
    pub consumed_frames: f64,
    pub produced_frames: u64,
}

impl Default for RetimeStream {
    fn default() -> Self {
        Self::new()
    }
}

impl RetimeStream {
    pub fn new() -> RetimeStream {
        RetimeStream {
            varispeed: Varispeed::new(), wsola: Wsola::new(), output: VecDeque::new(), preserve_pitch: true,
            scratch: Vec::new(), consumed_frames: 0.0, produced_frames: 0,
        }
    }

    /// Esquece tudo (busca).
    pub fn reset(&mut self) {
        *self = RetimeStream::new();
    }

    pub fn available(&self) -> usize {
        self.output.len() / CHANNELS
    }

    /// Quadros de mídia a empurrar antes de pedir `frames` quadros a `speed`.
    pub fn input_needed(&self, frames: usize, speed: f64, preserve_pitch: bool) -> usize {
        let missing = frames.saturating_sub(self.available());
        if missing == 0 {
            return 0;
        }
        if preserve_pitch { self.wsola.input_needed(missing, speed) } else { self.varispeed.input_needed(missing, speed) }
    }

    /// Empurra mídia e produz o que der a `speed`. Trocar de modo no meio esvazia o modo antigo.
    /// Posição na mídia (quadros) que a timeline já cobriu, incluindo o que está na fila de saída.
    pub fn media_position(&self) -> f64 {
        if self.preserve_pitch { self.wsola.media_position() } else { self.varispeed.media_position() }
    }

    /// Devolve quantos quadros de timeline foram produzidos.
    pub fn push(&mut self, input: &[f32], speed: f64, preserve_pitch: bool, target_frames: usize) -> usize {
        if preserve_pitch != self.preserve_pitch {
            self.preserve_pitch = preserve_pitch;
            self.varispeed = Varispeed::new();
            self.wsola = Wsola::new();
        }
        if preserve_pitch { self.wsola.push(input) } else { self.varispeed.push(input) }
        self.consumed_frames += (input.len() / CHANNELS) as f64;
        let wanted = target_frames.saturating_sub(self.available());
        self.scratch.clear();
        let made = if preserve_pitch {
            // WSOLA produz em passos de HOP; pede pelo menos um.
            self.wsola.produce(wanted.max(HOP).div_ceil(HOP) * HOP, speed, &mut self.scratch)
        } else {
            self.varispeed.produce(wanted, speed, &mut self.scratch)
        };
        self.produced_frames += made as u64;
        self.output.extend(self.scratch.iter().copied());
        made
    }

    /// Fim da mídia: empurra silêncio até a timeline cobrir tudo o que foi recebido. Sem isso o
    /// fim do áudio ficaria preso nos buffers (o WSOLA guarda ~50 ms de mídia à frente, que a
    /// 0,25× são 200 ms de timeline). Devolve os quadros de timeline produzidos.
    pub fn finish(&mut self, speed: f64) -> usize {
        let target = self.consumed_frames;
        let zeros = [0f32; 512 * CHANNELS];
        let mut total = 0;
        for _ in 0..512 {
            if self.media_position() >= target {
                break;
            }
            self.scratch.clear();
            let made = if self.preserve_pitch {
                self.wsola.push(&zeros);
                self.wsola.produce(HOP, speed, &mut self.scratch)
            } else {
                self.varispeed.push(&zeros);
                self.varispeed.produce(256, speed, &mut self.scratch)
            };
            self.produced_frames += made as u64;
            self.output.extend(self.scratch.iter().copied());
            total += made;
        }
        total
    }

    /// Tira até `frames` quadros (menos se não houver). Devolve quantos.
    pub fn pull(&mut self, out: &mut [f32], frames: usize) -> usize {
        let count = frames.min(self.available()).min(out.len() / CHANNELS);
        for (slot, value) in out.iter_mut().zip(self.output.drain(..count * CHANNELS)) {
            *slot = value;
        }
        count
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: f32 = 48_000.0;

    fn sine(frequency: f32, frames: usize) -> Vec<f32> {
        (0..frames).flat_map(|i| {
            let v = (2.0 * PI * frequency * i as f32 / RATE).sin() * 0.5;
            [v, v]
        }).collect()
    }

    /// Frequência dominante por cruzamentos de zero (canal esquerdo), ignorando as bordas.
    fn frequency(samples: &[f32]) -> f32 {
        let left: Vec<f32> = samples.chunks_exact(2).map(|f| f[0]).collect();
        let body = &left[left.len() / 10..left.len() * 9 / 10];
        let crossings = body.windows(2).filter(|w| w[0] <= 0.0 && w[1] > 0.0).count();
        crossings as f32 / (body.len() as f32 / RATE)
    }

    /// Toca `blocks` blocos de 1024 quadros; devolve a saída e a posição de mídia esperada
    /// (∫ velocidade sobre a timeline produzida).
    fn run_tracked(stream: &mut RetimeStream, source: &[f32], speed: impl Fn(usize) -> f64, preserve: bool, blocks: usize) -> (Vec<f32>, f64) {
        let mut cursor = 0usize;
        let mut out = Vec::new();
        let mut expected = 0.0;
        let mut block = vec![0f32; 1024 * 2];
        for b in 0..blocks {
            let s = speed(b);
            let need = stream.input_needed(1024, s, preserve);
            let end = (cursor + need * 2).min(source.len());
            expected += s * stream.push(&source[cursor..end], s, preserve, 1024) as f64;
            cursor = end;
            let got = stream.pull(&mut block, 1024);
            assert_eq!(got, 1024, "bloco {b} incompleto (velocidade {s})");
            out.extend_from_slice(&block);
        }
        (out, expected)
    }

    fn run(stream: &mut RetimeStream, source: &[f32], speed: impl Fn(usize) -> f64, preserve: bool, blocks: usize) -> Vec<f32> {
        run_tracked(stream, source, speed, preserve, blocks).0
    }

    #[test]
    fn varispeed_moves_pitch_with_speed() {
        let source = sine(440.0, 48_000 * 20);
        for speed in [0.5, 2.0, 4.0] {
            let mut stream = RetimeStream::new();
            let out = run(&mut stream, &source, |_| speed, false, 60);
            let f = frequency(&out);
            assert!((f / (440.0 * speed as f32) - 1.0).abs() < 0.02, "velocidade {speed}: {f} Hz");
        }
    }

    #[test]
    fn wsola_keeps_pitch_at_every_speed() {
        let source = sine(440.0, 48_000 * 40);
        for speed in [0.1, 0.25, 0.5, 2.0, 4.0, 8.0] {
            let mut stream = RetimeStream::new();
            let out = run(&mut stream, &source, |_| speed, true, 60);
            let f = frequency(&out);
            assert!((f / 440.0 - 1.0).abs() < 0.03, "velocidade {speed}: {f} Hz");
            assert!(out.iter().all(|v| v.is_finite() && v.abs() <= 1.0));
        }
    }

    #[test]
    fn consumption_tracks_the_timeline_during_a_ramp() {
        // Rampa de 0,25× a 4× em 200 blocos (~4,3 s): a mídia consumida deve seguir ∫ velocidade.
        let source = sine(330.0, 48_000 * 60);
        for preserve in [true, false] {
            let mut stream = RetimeStream::new();
            let speed = |b: usize| 0.25 + 3.75 * b as f64 / 199.0;
            let (_, expected) = run_tracked(&mut stream, &source, speed, preserve, 200);
            // A posição na mídia segue a integral da velocidade sem acumular erro (a busca do
            // WSOLA desloca cada segmento só ±12 ms em torno dessa posição, sem deriva).
            let drift = stream.media_position() - expected;
            assert!(drift.abs() < 4.0, "preserva={preserve}: {drift} quadros de deriva");
        }
    }

    #[test]
    fn speed_changes_do_not_click() {
        // Senoide contínua com a velocidade mudando a cada bloco: sem saltos maiores que a
        // inclinação máxima da senoide mais folga (um estalo é um salto quase de pico a pico).
        let source = sine(220.0, 48_000 * 30);
        for preserve in [true, false] {
            let mut stream = RetimeStream::new();
            let out = run(&mut stream, &source, |b| if b % 2 == 0 { 0.6 } else { 1.7 }, preserve, 80);
            let left: Vec<f32> = out.chunks_exact(2).map(|f| f[0]).collect();
            let max_jump = left.windows(2).skip(4096).map(|w| (w[1] - w[0]).abs()).fold(0f32, f32::max);
            assert!(max_jump < 0.12, "preserva={preserve}: salto {max_jump}");
        }
    }

    #[test]
    fn reset_starts_clean() {
        let mut stream = RetimeStream::new();
        stream.push(&sine(440.0, 10_000), 1.0, true, 1024);
        stream.reset();
        assert_eq!(stream.available(), 0);
        assert_eq!(stream.consumed_frames, 0.0);
    }
}
