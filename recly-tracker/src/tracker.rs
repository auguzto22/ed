//! Rastreador de alvo único, robusto, sem rede e sem modelo.
//!
//! Em relação ao `TemplateMotionTracker` em Kotlin, que comparava luminância com erro absoluto
//! numa grade e só previa com a última velocidade, este rastreador:
//!
//! * usa **ZNCC multicanal** (luminância, gradiente e duas cores oponentes), com o peso de cada
//!   canal proporcional ao quanto ele de fato descreve o alvo; é invariante a brilho e contraste;
//! * amostra numa **pirâmide** com anti-aliasing, de modo que o custo não depende do tamanho do
//!   alvo, e calcula médias/variâncias das janelas com **imagens integrais**;
//! * prevê o movimento com um **filtro de Kalman** (x, y, log da escala) e dimensiona a janela de
//!   busca pela incerteza: pequena quando o movimento é previsível, grande depois de um tranco;
//! * busca coarse-to-fine com **refinamento subpixel** (parábola) e **estimativa de escala**;
//! * mede a confiança com o pico **e** com a razão pico/lóbulo lateral (PSR), aplica um leve
//!   prior de movimento contra distratores e faz *gating* de saltos improváveis;
//! * mantém três memórias de aparência: a **âncora** do primeiro quadro, um template **adaptativo**
//!   (só atualizado quando o patch ainda se parece com a âncora, o que evita deriva) e um
//!   **banco** de aparências distintas vistas com alta confiança;
//! * em **oclusão**, segue a trajetória prevista; se o alvo não volta, faz **redetecção global**
//!   no quadro inteiro com a âncora e o banco;
//! * ao final, um **suavizador RTS** usa o futuro para remover tremor e preencher as oclusões.

use crate::image::{FrameFeatures, SearchImage, CHANNELS};
use crate::kalman::{rts_smooth, State, Step};
use crate::track::{new_id, TrackingPoint, TrackingRegion, TrackingTrack, MAX_POINTS};
use crate::par;

/// Parâmetros do rastreador. Os padrões funcionam bem para a maioria dos vídeos.
#[derive(Clone, Debug)]
pub struct TrackerConfig {
    /// Lado maior do template em amostras (8..=64).
    pub template_size: usize,
    /// Raio mínimo de busca, em frações do tamanho do alvo.
    pub min_search: f32,
    /// Raio máximo de busca, em frações do tamanho do alvo.
    pub max_search: f32,
    /// Ativa a estimativa de escala (alvo se aproximando/afastando).
    pub estimate_scale: bool,
    /// Taxa de adaptação do template.
    pub adapt_rate: f32,
    /// Confiança abaixo da qual a medição é tratada como oclusão.
    pub min_confidence: f32,
    /// Ativa a redetecção global depois de alguns quadros perdido.
    pub redetect: bool,
    /// Pontuação mínima (ZNCC) para aceitar uma redetecção.
    pub redetect_threshold: f32,
    /// Peso do prior de movimento (0 desliga).
    pub motion_prior: f32,
    /// Ruído de aceleração da posição, em frações do tamanho do alvo por quadro².
    pub acceleration_noise: f32,
}

impl Default for TrackerConfig {
    fn default() -> Self {
        Self {
            template_size: 32,
            min_search: 0.35,
            max_search: 2.5,
            estimate_scale: true,
            adapt_rate: 0.08,
            min_confidence: 0.28,
            redetect: true,
            redetect_threshold: 0.58,
            motion_prior: 0.08,
            acceleration_noise: 0.06,
        }
    }
}

impl TrackerConfig {
    pub fn validate(&self) -> Result<(), String> {
        if !(8..=64).contains(&self.template_size) {
            return Err("template_size deve estar em 8..=64".into());
        }
        if !(0.05..=2.0).contains(&self.min_search) || self.max_search < self.min_search || self.max_search > 6.0 {
            return Err("raios de busca inválidos".into());
        }
        if !(0.0..=1.0).contains(&self.adapt_rate) || !(0.0..1.0).contains(&self.min_confidence) {
            return Err("taxas inválidas".into());
        }
        Ok(())
    }
}

/// Situação atual do alvo.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrackStatus {
    Tracking,
    Occluded,
    Lost,
}

/// Template multicanal: valores brutos (para adaptação) e normalizados (para ZNCC).
#[derive(Clone, Debug)]
struct Template {
    tw: usize,
    th: usize,
    raw: [Vec<f32>; CHANNELS],
    norm: [Vec<f32>; CHANNELS],
    weights: [f32; CHANNELS],
    /// Metades (esquerda, direita, superior, inferior) normalizadas separadamente, para
    /// reconhecer o alvo parcialmente encoberto.
    parts: Vec<Part>,
}

#[derive(Clone, Debug)]
struct Part {
    x0: usize,
    y0: usize,
    w: usize,
    h: usize,
    norm: [Vec<f32>; CHANNELS],
    weights: [f32; CHANNELS],
}

const BASE_WEIGHTS: [f32; CHANNELS] = [1.0, 0.8, 0.55, 0.55];
const RELEVANCE: [f32; CHANNELS] = [0.025, 0.03, 0.012, 0.012];

impl Template {
    fn from_raw(tw: usize, th: usize, raw: [Vec<f32>; CHANNELS]) -> Self {
        let mut t = Template { tw, th, norm: raw.clone(), raw, weights: [0.0; CHANNELS], parts: Vec::new() };
        t.prepare();
        t
    }

    fn sample(features: &FrameFeatures, cx: f32, cy: f32, step: f32, tw: usize, th: usize) -> Self {
        let x0 = cx - tw as f32 * step / 2.0;
        let y0 = cy - th as f32 * step / 2.0;
        let img = SearchImage::sample(features, x0, y0, step, tw, th);
        let raw: [Vec<f32>; CHANNELS] = std::array::from_fn(|c| img.channels[c].clone());
        Self::from_raw(tw, th, raw)
    }

    fn prepare(&mut self) {
        let n = (self.tw * self.th) as f32;
        for c in 0..CHANNELS {
            let mean = self.raw[c].iter().sum::<f32>() / n;
            let mut sq = 0.0f32;
            for (o, v) in self.norm[c].iter_mut().zip(&self.raw[c]) {
                *o = v - mean;
                sq += *o * *o;
            }
            let std = (sq / n).sqrt();
            let inv = if sq > 1e-12 { 1.0 / sq.sqrt() } else { 0.0 };
            self.norm[c].iter_mut().for_each(|v| *v *= inv);
            self.weights[c] = if inv == 0.0 { 0.0 } else { BASE_WEIGHTS[c] * std / (std + RELEVANCE[c]) };
        }
        if self.weights.iter().all(|w| *w < 1e-4) {
            // Alvo totalmente liso: ainda assim compare a luminância.
            self.weights[0] = 1e-3;
        }
        let (hw, hh) = (self.tw / 2, self.th / 2);
        let rects = [(0, 0, hw, self.th), (hw, 0, self.tw - hw, self.th), (0, 0, self.tw, hh), (0, hh, self.tw, self.th - hh)];
        self.parts = rects
            .iter()
            .map(|&(x0, y0, w, h)| {
                let n = (w * h) as f32;
                let mut weights = [0.0f32; CHANNELS];
                let norm = std::array::from_fn(|c| {
                    let mut v = Vec::with_capacity(w * h);
                    for y in y0..y0 + h {
                        v.extend_from_slice(&self.raw[c][y * self.tw + x0..][..w]);
                    }
                    let mean = v.iter().sum::<f32>() / n;
                    let mut sq = 0.0;
                    for x in &mut v {
                        *x -= mean;
                        sq += *x * *x;
                    }
                    let std = (sq / n).sqrt();
                    let inv = if sq > 1e-12 { 1.0 / sq.sqrt() } else { 0.0 };
                    v.iter_mut().for_each(|x| *x *= inv);
                    weights[c] = if inv == 0.0 { 0.0 } else { BASE_WEIGHTS[c] * std / (std + RELEVANCE[c]) };
                    v
                });
                Part { x0, y0, w, h, norm, weights }
            })
            .collect();
    }

    /// Melhor pontuação entre as metades do template (alvo parcialmente encoberto).
    fn part_score(&self, img: &SearchImage, sx: usize, sy: usize) -> f32 {
        self.parts
            .iter()
            .map(|p| zncc(img, sx + p.x0, sy + p.y0, p.w, p.h, &p.norm, &p.weights))
            .fold(f32::NEG_INFINITY, f32::max)
    }

    fn blend(&mut self, other: &Template, rate: f32) {
        for c in 0..CHANNELS {
            for (a, b) in self.raw[c].iter_mut().zip(&other.raw[c]) {
                *a = *a * (1.0 - rate) + *b * rate;
            }
        }
        self.prepare();
    }

    /// Similaridade ZNCC entre dois templates do mesmo tamanho.
    fn similarity(&self, other: &Template) -> f32 {
        let mut total = 0.0;
        let mut wsum = 0.0;
        for c in 0..CHANNELS {
            let w = self.weights[c].min(other.weights[c]);
            if w < 1e-3 {
                continue;
            }
            let dot: f32 = self.norm[c].iter().zip(&other.norm[c]).map(|(a, b)| a * b).sum();
            total += w * dot;
            wsum += w;
        }
        if wsum > 0.0 {
            total / wsum
        } else {
            0.0
        }
    }

    /// Versão reduzida pela metade (para a redetecção global).
    fn half(&self) -> Template {
        let tw = (self.tw / 2).max(4);
        let th = (self.th / 2).max(4);
        let raw = std::array::from_fn(|c| {
            let src = &self.raw[c];
            let mut out = vec![0.0f32; tw * th];
            for y in 0..th {
                for x in 0..tw {
                    let mut s = 0.0;
                    for (dy, dx) in [(0, 0), (0, 1), (1, 0), (1, 1)] {
                        let sx = (2 * x + dx).min(self.tw - 1);
                        let sy = (2 * y + dy).min(self.th - 1);
                        s += src[sy * self.tw + sx];
                    }
                    out[y * tw + x] = s * 0.25;
                }
            }
            out
        });
        Template::from_raw(tw, th, raw)
    }

    /// ZNCC ponderado na janela com canto em `(sx, sy)` da imagem de busca.
    #[inline]
    fn score(&self, img: &SearchImage, sx: usize, sy: usize) -> f32 {
        zncc(img, sx, sy, self.tw, self.th, &self.norm, &self.weights)
    }
}

#[inline]
fn zncc(img: &SearchImage, sx: usize, sy: usize, tw: usize, th: usize, norm: &[Vec<f32>; CHANNELS], weights: &[f32; CHANNELS]) -> f32 {
    let n = (tw * th) as f64;
    let mut total = 0.0f32;
    let mut wsum = 0.0f32;
    for c in 0..CHANNELS {
        let w = weights[c];
        if w < 1e-3 {
            continue;
        }
        let tpl = &norm[c];
        let ch = &img.channels[c];
        let mut dot = 0.0f32;
        for y in 0..th {
            let row = &ch[(sy + y) * img.w + sx..][..tw];
            let trow = &tpl[y * tw..][..tw];
            dot += row.iter().zip(trow).map(|(a, b)| a * b).sum::<f32>();
        }
        let (s, q) = img.window_stats(c, sx, sy, tw, th);
        let var = q - s * s / n;
        let z = if var > 1e-7 * n { dot / var.sqrt() as f32 } else { 0.0 };
        total += w * z.clamp(-1.0, 1.0);
        wsum += w;
    }
    if wsum > 0.0 {
        total / wsum
    } else {
        0.0
    }
}

/// Resultado de uma busca local.
#[derive(Clone, Copy, Debug)]
struct Measurement {
    cx: f32,
    cy: f32,
    log_scale: f32,
    score: f32,
    psr: f32,
}

/// Configura e inicia sessões de rastreamento.
#[derive(Clone, Debug, Default)]
pub struct Tracker {
    pub config: TrackerConfig,
}

impl Tracker {
    pub fn new(config: TrackerConfig) -> Result<Self, String> {
        config.validate()?;
        Ok(Self { config })
    }

    /// Inicia uma sessão no quadro `features` com a seleção normalizada `region`.
    pub fn start(&self, features: &FrameFeatures, time_us: i64, region: TrackingRegion) -> Session {
        Session::new(self.config.clone(), features, time_us, region)
    }

    /// Rastreia vários alvos ao mesmo tempo sobre uma sequência de quadros já decodificados.
    pub fn track_all<'a, I>(&self, frames: I, regions: &[TrackingRegion]) -> Vec<TrackingTrack>
    where
        I: IntoIterator<Item = (i64, &'a FrameFeatures)>,
    {
        let mut iter = frames.into_iter();
        let Some((t0, first)) = iter.next() else { return Vec::new() };
        let mut sessions: Vec<Session> = regions.iter().map(|r| self.start(first, t0, *r)).collect();
        for (t, f) in iter {
            par::each_mut(&mut sessions, |s| {
                s.update(f, t);
            });
        }
        sessions.into_iter().map(|s| s.finish(None, true)).collect()
    }
}

/// Sessão incremental: os quadros são entregues à medida que são decodificados.
pub struct Session {
    cfg: TrackerConfig,
    ref_w: f32,
    ref_h: f32,
    /// Tamanho inicial do alvo em pixels de referência.
    w0: f32,
    h0: f32,
    tw: usize,
    th: usize,
    anchor: Template,
    adaptive: Template,
    bank: Vec<Template>,
    kx: State,
    ky: State,
    ks: State,
    steps: [Vec<Step>; 3],
    times: Vec<i64>,
    confidences: Vec<f32>,
    points: Vec<TrackingPoint>,
    previous_time: i64,
    nominal_interval: Option<i64>,
    lost: u32,
    status: TrackStatus,
    /// Inflação adaptativa do ruído de processo (1 = movimento previsível).
    q_boost: f64,
    /// Última posição medida com confiança (pixels de referência).
    last_good: (f32, f32),
}

const BANK_SIZE: usize = 8;

impl Session {
    fn new(cfg: TrackerConfig, features: &FrameFeatures, time_us: i64, region: TrackingRegion) -> Self {
        let ref_w = features.width as f32;
        let ref_h = features.height as f32;
        let w0 = (region.width * ref_w).max(4.0);
        let h0 = (region.height * ref_h).max(4.0);
        let t = cfg.template_size as f32;
        let (tw, th) = if w0 >= h0 {
            (cfg.template_size, ((t * h0 / w0).round() as usize).max(8))
        } else {
            (((t * w0 / h0).round() as usize).max(8), cfg.template_size)
        };
        // Dimensões pares para que a redução pela metade da redetecção seja exata.
        let (tw, th) = (tw + tw % 2, th + th % 2);
        let cx = region.center_x * ref_w;
        let cy = region.center_y * ref_h;
        let step = w0 / tw as f32;
        let anchor = Template::sample(features, cx, cy, step, tw, th);
        let size = w0.max(h0) as f64;
        let kx = State::new(cx as f64, 1.0, (0.5 * size).powi(2));
        let ky = State::new(cy as f64, 1.0, (0.5 * size).powi(2));
        let ks = State::new(0.0, 1e-6, 0.05f64.powi(2));
        let first = Step { predicted: kx, filtered: kx, dt: 1.0 };
        let mut s = Session {
            cfg,
            ref_w,
            ref_h,
            w0,
            h0,
            tw,
            th,
            adaptive: anchor.clone(),
            bank: vec![anchor.clone()],
            anchor,
            kx,
            ky,
            ks,
            steps: [vec![first], vec![Step { predicted: ky, filtered: ky, dt: 1.0 }], vec![Step { predicted: ks, filtered: ks, dt: 1.0 }]],
            times: vec![time_us],
            confidences: vec![1.0],
            points: Vec::with_capacity(512),
            previous_time: time_us,
            nominal_interval: None,
            lost: 0,
            status: TrackStatus::Tracking,
            q_boost: 1.0,
            last_good: (cx, cy),
        };
        let p = s.point(time_us, 1.0, s.kx.position() as f32, s.ky.position() as f32, 0.0);
        s.points.push(p);
        s
    }

    pub fn status(&self) -> TrackStatus {
        self.status
    }

    /// Pontos online (filtrados, ainda não suavizados).
    pub fn points(&self) -> &[TrackingPoint] {
        &self.points
    }

    pub fn len(&self) -> usize {
        self.points.len()
    }

    pub fn is_empty(&self) -> bool {
        self.points.is_empty()
    }

    fn scale(&self) -> f32 {
        (self.ks.position() as f32).exp()
    }

    fn point(&self, time_us: i64, confidence: f32, cx: f32, cy: f32, log_scale: f32) -> TrackingPoint {
        let s = log_scale.exp();
        TrackingPoint {
            time_us,
            center_x: (cx / self.ref_w).clamp(0.0, 1.0),
            center_y: (cy / self.ref_h).clamp(0.0, 1.0),
            width: (self.w0 * s / self.ref_w).clamp(0.01, 1.0),
            height: (self.h0 * s / self.ref_h).clamp(0.01, 1.0),
            confidence: confidence.clamp(0.0, 1.0),
        }
    }

    /// Processa um novo quadro. Retorna `None` se o tempo não avançou.
    pub fn update(&mut self, features: &FrameFeatures, time_us: i64) -> Option<TrackingPoint> {
        if time_us <= self.previous_time {
            return None;
        }
        let interval = time_us - self.previous_time;
        let nominal = *self.nominal_interval.get_or_insert(interval);
        let dt = (interval as f64 / nominal as f64).clamp(0.25, 8.0);
        // Converte entre pixels de referência e pixels deste quadro (o decodificador pode mudar
        // o tamanho no meio do vídeo).
        let kxf = features.width as f32 / self.ref_w;
        let kyf = features.height as f32 / self.ref_h;

        let size = self.w0.max(self.h0) as f64 * self.scale() as f64;
        let q_pos = (self.cfg.acceleration_noise as f64 * size).powi(2) * self.q_boost;
        let px = self.kx.predict(dt, q_pos);
        let py = self.ky.predict(dt, q_pos);
        let ps = self.ks.predict(dt, 0.004f64.powi(2));

        let scale = (ps.position() as f32).exp();
        let step = self.w0 * scale / self.tw as f32;
        let sigma = px.position_sigma().max(py.position_sigma()) as f32;
        let t_max = self.tw.max(self.th) as f32;
        let base = (self.cfg.min_search * t_max).ceil();
        let cap = (self.cfg.max_search * t_max).ceil();
        let radius = (base + 2.5 * sigma / step).clamp(base, cap) as usize;

        let pred_cx = px.position() as f32;
        let pred_cy = py.position() as f32;
        let mut measurement = self.search(features, kxf, kyf, pred_cx, pred_cy, ps.position() as f32, step, radius);

        let recovering = self.lost > 0;
        let judge = |m: &Measurement, px: &State, py: &State| -> (bool, f32) {
            let conf = confidence(m.score, m.psr);
            let mut ok = conf >= self.cfg.min_confidence + if recovering { 0.08 } else { 0.0 };
            // Gating: um salto muito improvável só é aceito com confiança alta.
            let r = (0.3 * step as f64).powi(2);
            let d2 = px.mahalanobis2(m.cx as f64, r) + py.mahalanobis2(m.cy as f64, r);
            if d2 > 50.0 * self.q_boost && conf < 0.65 {
                ok = false;
            }
            // O patch medido ainda se parece com a âncora? Se não, provavelmente é um oclusor.
            if ok && conf < 0.55 {
                let patch = Template::sample(features, m.cx * kxf, m.cy * kyf, step * kxf, self.tw, self.th);
                if patch.similarity(&self.anchor).max(patch.similarity(&self.adaptive)) < 0.3 {
                    ok = false;
                }
            }
            (ok, conf)
        };
        let (mut accept, mut confidence) = measurement.as_ref().map_or((false, 0.0), |m| judge(m, &px, &py));

        // Busca de resgate: a previsão pode ter sido enganada por um tranco (câmera tremida,
        // mudança brusca de direção). Procura de novo em volta da última posição boa, com raio
        // máximo e sem prior de velocidade.
        if !accept {
            let (lx, ly) = (self.last_good.0, self.last_good.1);
            if let Some(m) = self.search(features, kxf, kyf, lx, ly, ps.position() as f32, step, cap as usize) {
                let anchor_x = State::new(lx as f64, px.p[0][0], 1.0);
                let anchor_y = State::new(ly as f64, py.p[0][0], 1.0);
                let (ok, conf) = judge(&m, &anchor_x, &anchor_y);
                if ok {
                    measurement = Some(m);
                    accept = true;
                    confidence = conf;
                }
            }
        }

        let mut redetected = false;
        if !accept && self.cfg.redetect && self.lost >= 3 && self.lost % 2 == 1 {
            if let Some(m) = self.redetect(features, kxf, kyf, ps.position() as f32, step) {
                measurement = Some(m);
                confidence = confidence_of_redetection(m.score);
                accept = true;
                redetected = true;
            }
        }

        let (fx, fy, fs);
        if let (true, Some(m)) = (accept, measurement) {
            let w = (confidence + 0.05).powi(2) as f64;
            let r_pos = (0.3 * step as f64).powi(2) / w;
            if redetected {
                // Reinicia o movimento no ponto reencontrado.
                fx = State::new(m.cx as f64, r_pos, (0.25 * size).powi(2));
                fy = State::new(m.cy as f64, r_pos, (0.25 * size).powi(2));
            } else {
                fx = px.update(m.cx as f64, r_pos);
                fy = py.update(m.cy as f64, r_pos);
            }
            fs = ps.update(m.log_scale as f64, 0.03f64.powi(2) / w);
            if !redetected {
                // NIS (inovação normalizada): se as medições surpreendem o filtro, ele passa a
                // confiar menos na velocidade e alarga a janela de busca.
                let nis = 0.5 * (px.mahalanobis2(m.cx as f64, r_pos) + py.mahalanobis2(m.cy as f64, r_pos));
                self.q_boost = (0.6 * self.q_boost + 0.4 * nis).clamp(1.0, 60.0);
            }
            self.last_good = (fx.position() as f32, fy.position() as f32);
            self.lost = 0;
            self.status = TrackStatus::Tracking;
            self.learn(features, kxf, kyf, fx.position() as f32, fy.position() as f32, (fs.position() as f32).exp(), confidence);
        } else {
            // Oclusão: segue a previsão com velocidade amortecida.
            let mut x = px;
            let mut y = py;
            // Movimento previsível (q_boost baixo): o alvo provavelmente continua andando atrás do
            // oclusor. Movimento errático: a velocidade pode ser o próprio erro, amortece forte.
            let erratic = self.q_boost > 4.0;
            let damp = match (erratic, self.lost == 0) {
                (true, true) => 0.5,
                (true, false) => 0.85,
                (false, _) => 0.98,
            };
            x.damp_velocity(damp);
            y.damp_velocity(damp);
            fx = x;
            fy = y;
            let mut s = ps;
            s.damp_velocity(0.5);
            fs = s;
            self.lost += 1;
            self.status = if self.lost > 20 { TrackStatus::Lost } else { TrackStatus::Occluded };
            confidence = confidence.min(self.cfg.min_confidence * 0.9);
        }

        // Mantém o centro dentro do quadro.
        let clamp_state = |mut s: State, max: f32| {
            s.x[0] = s.x[0].clamp(0.0, max as f64);
            s
        };
        self.kx = clamp_state(fx, self.ref_w);
        self.ky = clamp_state(fy, self.ref_h);
        self.ks = State { x: [fs.x[0].clamp(-2.5, 2.5), fs.x[1]], p: fs.p };
        self.steps[0].push(Step { predicted: px, filtered: self.kx, dt });
        self.steps[1].push(Step { predicted: py, filtered: self.ky, dt });
        self.steps[2].push(Step { predicted: ps, filtered: self.ks, dt });
        self.times.push(time_us);
        self.confidences.push(confidence);
        self.previous_time = time_us;
        let p = self.point(time_us, confidence, self.kx.position() as f32, self.ky.position() as f32, self.ks.position() as f32);
        self.points.push(p);
        Some(p)
    }

    /// Busca local em torno da previsão. Coordenadas de entrada/saída em pixels de referência.
    #[allow(clippy::too_many_arguments)]
    fn search(
        &self,
        features: &FrameFeatures,
        kxf: f32,
        kyf: f32,
        pcx: f32,
        pcy: f32,
        log_scale: f32,
        step: f32,
        radius: usize,
    ) -> Option<Measurement> {
        let (tw, th) = (self.tw, self.th);
        let sw = tw + 2 * radius;
        let sh = th + 2 * radius;
        let fstep = step * kxf;
        let x0 = pcx * kxf - sw as f32 * fstep / 2.0;
        let y0 = pcy * kyf - sh as f32 * fstep / 2.0;
        let img = SearchImage::sample(features, x0, y0, fstep, sw, sh);
        let templates = [&self.adaptive, &self.anchor];
        let score_at = |sx: usize, sy: usize| -> f32 {
            let a = templates[0].score(&img, sx, sy);
            let b = templates[1].score(&img, sx, sy) * 0.98;
            a.max(b)
        };

        let stride = if radius > 18 { 2 } else { 1 };
        let n = 2 * radius / stride + 1;
        let cost = tw * th * CHANNELS * 2;
        let map: Vec<f32> = par::map(n * n, cost, |i| score_at((i % n) * stride, (i / n) * stride));

        let prior = |sx: f32, sy: f32| -> f32 {
            let dx = sx - radius as f32;
            let dy = sy - radius as f32;
            let norm = (radius as f32).max(4.0);
            self.cfg.motion_prior * (dx * dx + dy * dy) / (norm * norm)
        };

        // Melhores picos (com prior) no mapa grosso.
        let mut peaks: Vec<(f32, usize)> = map
            .iter()
            .enumerate()
            .map(|(i, &s)| (s - prior(((i % n) * stride) as f32, ((i / n) * stride) as f32), i))
            .collect();
        peaks.sort_unstable_by(|a, b| b.0.total_cmp(&a.0));
        peaks.truncate(3);

        // Refinamento no passo 1 em torno de cada pico.
        let mut best = (f32::NEG_INFINITY, 0usize, 0usize, 0.0f32);
        for &(_, i) in &peaks {
            let (bx, by) = ((i % n) * stride, (i / n) * stride);
            let r = stride as isize;
            for dy in -r..=r {
                for dx in -r..=r {
                    let sx = bx as isize + dx;
                    let sy = by as isize + dy;
                    if sx < 0 || sy < 0 || sx > 2 * radius as isize || sy > 2 * radius as isize {
                        continue;
                    }
                    let raw = score_at(sx as usize, sy as usize);
                    let adj = raw - prior(sx as f32, sy as f32);
                    if adj > best.0 {
                        best = (adj, sx as usize, sy as usize, raw);
                    }
                }
            }
        }
        if !best.0.is_finite() {
            return None;
        }
        let (_, mut bx, mut by, mut full_peak) = best;
        // Oclusão parcial: se uma metade casa muito melhor que o todo, a borda do oclusor pode ter
        // deslocado o pico. Refina a posição pela metade visível e confia nela (com desconto).
        let part_at = |x: usize, y: usize| self.adaptive.part_score(&img, x, y).max(self.anchor.part_score(&img, x, y) * 0.98);
        let mut part = part_at(bx, by);
        if 0.9 * part > full_peak + 0.05 {
            let r = (tw.min(th) / 4).max(2) as isize;
            let (ox, oy) = (bx, by);
            for dy in -r..=r {
                for dx in -r..=r {
                    let (x, y) = (ox as isize + dx, oy as isize + dy);
                    if x < 0 || y < 0 || x > 2 * radius as isize || y > 2 * radius as isize {
                        continue;
                    }
                    let v = part_at(x as usize, y as usize);
                    if v > part + 0.01 {
                        part = v;
                        bx = x as usize;
                        by = y as usize;
                    }
                }
            }
            full_peak = score_at(bx, by);
        }
        let peak = full_peak.max(0.9 * part);

        // PSR no mapa grosso, excluindo a vizinhança do pico.
        let exclusion = ((tw.min(th) as f32 / 5.0).ceil() as isize).max(2);
        let (mut sum, mut sq, mut count) = (0.0f64, 0.0f64, 0usize);
        for (i, &s) in map.iter().enumerate() {
            let x = ((i % n) * stride) as isize;
            let y = ((i / n) * stride) as isize;
            if (x - bx as isize).abs() <= exclusion && (y - by as isize).abs() <= exclusion {
                continue;
            }
            sum += s as f64;
            sq += (s * s) as f64;
            count += 1;
        }
        let psr = if count > 8 {
            let mean = sum / count as f64;
            let std = (sq / count as f64 - mean * mean).max(1e-8).sqrt();
            ((peak as f64 - mean) / std) as f32
        } else {
            10.0 * peak.max(0.0)
        };

        // Subpixel por parábola.
        let sub = |c: f32, l: Option<f32>, r: Option<f32>| -> f32 {
            match (l, r) {
                (Some(l), Some(r)) => {
                    let d = l - 2.0 * c + r;
                    if d < -1e-6 {
                        (0.5 * (l - r) / d).clamp(-0.5, 0.5)
                    } else {
                        0.0
                    }
                }
                _ => 0.0,
            }
        };
        let lim = 2 * radius;
        let left = (bx > 0).then(|| score_at(bx - 1, by));
        let right = (bx < lim).then(|| score_at(bx + 1, by));
        let up = (by > 0).then(|| score_at(bx, by - 1));
        let down = (by < lim).then(|| score_at(bx, by + 1));
        let ox = sub(full_peak, left, right);
        let oy = sub(full_peak, up, down);

        let mut cx = (x0 + (bx as f32 + ox + tw as f32 / 2.0) * fstep) / kxf;
        let mut cy = (y0 + (by as f32 + oy + th as f32 / 2.0) * fstep) / kyf;
        let mut ls = log_scale;
        let mut score = full_peak;

        if self.cfg.estimate_scale {
            for factor in [1.0 / 1.05f32, 1.05] {
                if let Some((s, x, y)) = self.local_best(features, kxf, kyf, cx, cy, step * factor, 2) {
                    // Pequena margem para não trocar de escala por ruído.
                    if s > score + 0.004 {
                        score = s;
                        cx = x;
                        cy = y;
                        ls = log_scale + factor.ln();
                    }
                }
            }
        }
        let score = score.max(peak);
        Some(Measurement { cx, cy, log_scale: ls, score, psr })
    }

    /// Melhor posição numa vizinhança pequena, com um passo de amostragem dado.
    #[allow(clippy::too_many_arguments)]
    fn local_best(&self, features: &FrameFeatures, kxf: f32, kyf: f32, cx: f32, cy: f32, step: f32, r: usize) -> Option<(f32, f32, f32)> {
        let (tw, th) = (self.tw, self.th);
        let sw = tw + 2 * r;
        let sh = th + 2 * r;
        let fstep = step * kxf;
        let x0 = cx * kxf - sw as f32 * fstep / 2.0;
        let y0 = cy * kyf - sh as f32 * fstep / 2.0;
        let img = SearchImage::sample(features, x0, y0, fstep, sw, sh);
        let mut best = (f32::NEG_INFINITY, 0, 0);
        for sy in 0..=2 * r {
            for sx in 0..=2 * r {
                let s = self.adaptive.score(&img, sx, sy).max(self.anchor.score(&img, sx, sy) * 0.98);
                if s > best.0 {
                    best = (s, sx, sy);
                }
            }
        }
        best.0.is_finite().then(|| {
            (
                best.0,
                (x0 + (best.1 as f32 + tw as f32 / 2.0) * fstep) / kxf,
                (y0 + (best.2 as f32 + th as f32 / 2.0) * fstep) / kyf,
            )
        })
    }

    /// Redetecção global: procura a âncora e o banco de aparências no quadro inteiro.
    fn redetect(&self, features: &FrameFeatures, kxf: f32, kyf: f32, log_scale: f32, step: f32) -> Option<Measurement> {
        let coarse: Vec<Template> = self.bank.iter().chain([&self.adaptive]).map(Template::half).collect();
        let (ctw, cth) = (coarse[0].tw, coarse[0].th);
        let fw = features.width as f32;
        let fh = features.height as f32;
        // Passo grosso: duas vezes o passo do template, limitado para manter o custo baixo.
        let mut cstep = 2.0 * step * kxf;
        let max_cells = 300.0;
        cstep = cstep.max(fw / max_cells).max(fh / max_cells).max(1.0);
        let gw = (fw / cstep).ceil() as usize;
        let gh = (fh / cstep).ceil() as usize;
        if gw <= ctw || gh <= cth {
            return None;
        }
        let img = SearchImage::sample(features, 0.0, 0.0, cstep, gw, gh);
        let nx = gw - ctw + 1;
        let ny = gh - cth + 1;
        let map: Vec<f32> = par::map(nx * ny, ctw * cth * CHANNELS * coarse.len(), |i| {
            let (sx, sy) = (i % nx, i / nx);
            coarse.iter().map(|t| t.score(&img, sx, sy)).fold(f32::NEG_INFINITY, f32::max)
        });

        // Máximos locais (supressão de não-máximos) como candidatos.
        let nms = (ctw.min(cth) / 2).max(2) as isize;
        let mut candidates: Vec<(f32, usize, usize)> = Vec::new();
        for sy in 0..ny {
            for sx in 0..nx {
                let v = map[sy * nx + sx];
                if v < self.cfg.redetect_threshold - 0.15 {
                    continue;
                }
                let mut is_max = true;
                'n: for dy in -nms..=nms {
                    for dx in -nms..=nms {
                        let (x, y) = (sx as isize + dx, sy as isize + dy);
                        if (dx != 0 || dy != 0) && x >= 0 && y >= 0 && (x as usize) < nx && (y as usize) < ny && map[y as usize * nx + x as usize] > v {
                            is_max = false;
                            break 'n;
                        }
                    }
                }
                if is_max {
                    candidates.push((v, sx, sy));
                }
            }
        }
        candidates.sort_unstable_by(|a, b| b.0.total_cmp(&a.0));
        candidates.truncate(5);

        // Verificação em resolução cheia com todas as memórias de aparência.
        let mut best: Option<Measurement> = None;
        for (_, sx, sy) in candidates {
            let ccx = (sx as f32 + ctw as f32 / 2.0) * cstep / kxf;
            let ccy = (sy as f32 + cth as f32 / 2.0) * cstep / kyf;
            let r = ((cstep / (step * kxf)).ceil() as usize + 1).min(6);
            let (tw, th) = (self.tw, self.th);
            let sw = tw + 2 * r;
            let sh = th + 2 * r;
            let fstep = step * kxf;
            let x0 = ccx * kxf - sw as f32 * fstep / 2.0;
            let y0 = ccy * kyf - sh as f32 * fstep / 2.0;
            let img = SearchImage::sample(features, x0, y0, fstep, sw, sh);
            for y in 0..=2 * r {
                for x in 0..=2 * r {
                    let s = self
                        .bank
                        .iter()
                        .chain([&self.adaptive])
                        .map(|t| t.score(&img, x, y))
                        .fold(f32::NEG_INFINITY, f32::max);
                    if s >= self.cfg.redetect_threshold && best.is_none_or(|b| s > b.score) {
                        best = Some(Measurement {
                            cx: (x0 + (x as f32 + tw as f32 / 2.0) * fstep) / kxf,
                            cy: (y0 + (y as f32 + th as f32 / 2.0) * fstep) / kyf,
                            log_scale,
                            score: s,
                            psr: 10.0,
                        });
                    }
                }
            }
        }
        best
    }

    /// Atualiza as memórias de aparência quando a medição é confiável.
    #[allow(clippy::too_many_arguments)]
    fn learn(&mut self, features: &FrameFeatures, kxf: f32, kyf: f32, cx: f32, cy: f32, scale: f32, confidence: f32) {
        if confidence < 0.5 {
            return;
        }
        let step = self.w0 * scale / self.tw as f32;
        let patch = Template::sample(features, cx * kxf, cy * kyf, step * kxf, self.tw, self.th);
        let to_anchor = patch.similarity(&self.anchor);
        if to_anchor < 0.35 {
            // Pode ser um oclusor parecido com o alvo: não aprende.
            return;
        }
        let rate = self.cfg.adapt_rate * ((confidence - 0.5) / 0.5).clamp(0.2, 1.0);
        self.adaptive.blend(&patch, rate);
        if confidence > 0.7 {
            let novelty = self.bank.iter().map(|t| patch.similarity(t)).fold(f32::NEG_INFINITY, f32::max);
            if novelty < 0.82 {
                if self.bank.len() >= BANK_SIZE {
                    // Mantém a âncora (índice 0) e descarta a aparência mais antiga.
                    self.bank.remove(1);
                }
                self.bank.push(patch);
            }
        }
    }

    /// Finaliza o track. Com `smooth`, aplica o suavizador RTS (recomendado para exportar).
    pub fn finish(self, id: Option<String>, smooth: bool) -> TrackingTrack {
        let id = id.unwrap_or_else(new_id);
        let points: Vec<TrackingPoint> = if smooth && self.times.len() > 2 {
            let sx = rts_smooth(&self.steps[0]);
            let sy = rts_smooth(&self.steps[1]);
            let ss = rts_smooth(&self.steps[2]);
            (0..self.times.len())
                .map(|i| self.point(self.times[i], self.confidences[i], sx[i].position() as f32, sy[i].position() as f32, ss[i].position() as f32))
                .collect()
        } else {
            self.points.clone()
        };
        TrackingTrack { id, points }.decimate(MAX_POINTS)
    }
}

/// Confiança em 0..1 a partir do pico ZNCC e da razão pico/lóbulo lateral.
pub fn confidence(score: f32, psr: f32) -> f32 {
    let c_peak = ((score - 0.25) / 0.55).clamp(0.0, 1.0);
    let c_psr = ((psr - 2.0) / 6.0).clamp(0.0, 1.0);
    c_peak * (0.35 + 0.65 * c_psr)
}

fn confidence_of_redetection(score: f32) -> f32 {
    ((score - 0.25) / 0.55).clamp(0.0, 1.0) * 0.8
}
