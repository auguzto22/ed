//! `recly-track`: rastreia alvos em vídeos e aplica máscaras que seguem o movimento.

use recly_tracker::error::{Context, Error, Result};
use recly_tracker::json::{self, FromJson, Json, ToJson};
use recly_tracker::mask::{MaskState, MaskType};
use recly_tracker::render::{apply_mask, draw_track_box, Effect};
use recly_tracker::synth::{evaluate, Scene, SceneKind};
use recly_tracker::video::{fit_width, probe, FrameReader, FrameWriter, VideoInfo};
use recly_tracker::{bail, FrameFeatures, PixelFormat, Tracker, TrackerConfig, TrackingRegion, TrackingTrack};
use std::path::{Path, PathBuf};
use std::time::Instant;

const HELP: &str = "\
recly-track — rastreador de movimento e máscaras em vídeo

USO
  recly-track run    <vídeo> --region cx,cy,w,h [opções] -o saída.mp4
  recly-track track  <vídeo> --region cx,cy,w,h [opções] -o tracks.json
  recly-track render <vídeo> --mask mascaras.json [--tracks tracks.json] [efeito] -o saída.mp4
  recly-track demo   [--scene tudo] -o demo.mp4

SELEÇÃO (pode repetir para rastrear vários alvos ao mesmo tempo)
  --region cx,cy,w,h      caixa normalizada (0..1): centro e tamanho
  --region-px x,y,w,h     caixa em pixels: canto superior esquerdo e tamanho

TRECHO E RASTREAMENTO
  --start S / --end S     trecho em segundos (o alvo é selecionado no quadro de --start)
  --track-width N         largura usada para rastrear (padrão 640; 0 = original)
  --raw                   não suaviza o track (sem o suavizador RTS)
  --no-scale              não estima mudança de escala
  --no-redetect           desliga a redetecção global depois de perder o alvo

MÁSCARA (run)
  --shape S               rectangle, circle, ellipse (padrão), heart, star, linear, mirror
  --mask-scale F          tamanho da máscara em relação à caixa rastreada (padrão 1.15)
  --feather F             suavização da borda, 0..0.5 (padrão 0.04)
  --invert                aplica o efeito fora da forma (ex.: borrar o fundo)
  --opacity F             0..1 (padrão 1)

EFEITO (run/render)
  --effect E              blur (padrão), pixelate, solid, cutout, highlight, outline
  --strength N            raio do desfoque / tamanho do bloco / espessura, em pixels
  --color #RRGGBB         cor de solid/highlight/outline/cutout
  --show-track            desenha a caixa rastreada e a confiança

SAÍDA
  -o, --output ARQ        arquivo de saída
  --save-tracks ARQ       (run) salva os tracks em JSON
  --save-masks ARQ        (run) salva as máscaras em JSON (formato do app)
  --preview ARQ           (track) vídeo de conferência com a caixa desenhada
";

#[derive(Default)]
struct Args {
    command: String,
    input: Option<PathBuf>,
    output: Option<PathBuf>,
    regions: Vec<String>,
    regions_px: Vec<String>,
    start: Option<f64>,
    end: Option<f64>,
    track_width: Option<usize>,
    raw: bool,
    no_scale: bool,
    no_redetect: bool,
    shape: Option<String>,
    mask_scale: Option<f32>,
    feather: Option<f32>,
    invert: bool,
    opacity: Option<f32>,
    effect: Option<String>,
    strength: Option<f32>,
    color: Option<String>,
    show_track: bool,
    save_tracks: Option<PathBuf>,
    save_masks: Option<PathBuf>,
    preview: Option<PathBuf>,
    mask: Option<PathBuf>,
    tracks: Option<PathBuf>,
    scene: Option<String>,
    width: Option<usize>,
    height: Option<usize>,
    frames: Option<usize>,
}

fn parse_args() -> Result<Args> {
    let mut it = std::env::args().skip(1);
    let mut a = Args { command: it.next().unwrap_or_default(), ..Default::default() };
    fn val<T: std::str::FromStr>(it: &mut impl Iterator<Item = String>, flag: &str) -> Result<T> {
        let v = it.next().context(format!("{flag} precisa de um valor"))?;
        v.parse().map_err(|_| Error(format!("valor inválido para {flag}: {v}")))
    }
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "-o" | "--output" => a.output = Some(val(&mut it, &arg)?),
            "--region" => a.regions.push(val(&mut it, &arg)?),
            "--region-px" => a.regions_px.push(val(&mut it, &arg)?),
            "--start" => a.start = Some(val(&mut it, &arg)?),
            "--end" => a.end = Some(val(&mut it, &arg)?),
            "--track-width" => a.track_width = Some(val(&mut it, &arg)?),
            "--raw" => a.raw = true,
            "--no-scale" => a.no_scale = true,
            "--no-redetect" => a.no_redetect = true,
            "--shape" => a.shape = Some(val(&mut it, &arg)?),
            "--mask-scale" => a.mask_scale = Some(val(&mut it, &arg)?),
            "--feather" => a.feather = Some(val(&mut it, &arg)?),
            "--invert" => a.invert = true,
            "--opacity" => a.opacity = Some(val(&mut it, &arg)?),
            "--effect" => a.effect = Some(val(&mut it, &arg)?),
            "--strength" => a.strength = Some(val(&mut it, &arg)?),
            "--color" => a.color = Some(val(&mut it, &arg)?),
            "--show-track" => a.show_track = true,
            "--save-tracks" => a.save_tracks = Some(val(&mut it, &arg)?),
            "--save-masks" => a.save_masks = Some(val(&mut it, &arg)?),
            "--preview" => a.preview = Some(val(&mut it, &arg)?),
            "--mask" => a.mask = Some(val(&mut it, &arg)?),
            "--tracks" => a.tracks = Some(val(&mut it, &arg)?),
            "--scene" => a.scene = Some(val(&mut it, &arg)?),
            "--width" => a.width = Some(val(&mut it, &arg)?),
            "--height" => a.height = Some(val(&mut it, &arg)?),
            "--frames" => a.frames = Some(val(&mut it, &arg)?),
            "-h" | "--help" => a.command = "help".into(),
            s if s.starts_with('-') => bail!("opção desconhecida: {s} (use --help)"),
            s => {
                if a.input.is_some() {
                    bail!("argumento extra: {s}");
                }
                a.input = Some(s.into());
            }
        }
    }
    Ok(a)
}

fn floats(s: &str, n: usize, what: &str) -> Result<Vec<f32>> {
    let v: Vec<f32> = s.split(',').map(|x| x.trim().parse::<f32>()).collect::<std::result::Result<_, _>>().map_err(|_| Error(format!("{what} inválido: {s}")))?;
    if v.len() != n {
        bail!("{what} precisa de {n} números separados por vírgula: {s}");
    }
    Ok(v)
}

fn regions(a: &Args, info: &VideoInfo) -> Result<Vec<TrackingRegion>> {
    let mut out = Vec::new();
    for r in &a.regions {
        let v = floats(r, 4, "--region")?;
        out.push(TrackingRegion::new(v[0], v[1], v[2], v[3]).map_err(|e| Error(format!("--region {r}: {e}")))?);
    }
    for r in &a.regions_px {
        let v = floats(r, 4, "--region-px")?;
        out.push(TrackingRegion::from_pixels(v[0], v[1], v[2], v[3], info.width, info.height).map_err(|e| Error(format!("--region-px {r}: {e}")))?);
    }
    if out.is_empty() {
        bail!("informe ao menos um alvo com --region ou --region-px");
    }
    if out.len() > 8 {
        bail!("no máximo 8 alvos por vídeo (limite do projeto do app)");
    }
    Ok(out)
}

fn color(a: &Args, default: [u8; 3]) -> Result<[u8; 3]> {
    let Some(c) = &a.color else { return Ok(default) };
    let hex = c.trim_start_matches('#');
    if hex.len() != 6 {
        bail!("cor inválida: {c} (use #RRGGBB)");
    }
    let byte = |i: usize| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|_| Error(format!("cor inválida: {c}")));
    Ok([byte(0)?, byte(2)?, byte(4)?])
}

fn effect(a: &Args, height: usize) -> Result<Effect> {
    let h = height as f32;
    let strength = |default_fraction: f32| a.strength.unwrap_or(default_fraction * h).max(1.0);
    Ok(match a.effect.as_deref().unwrap_or("blur") {
        "blur" => Effect::Blur { radius: strength(0.025).round() as usize },
        "pixelate" => Effect::Pixelate { block: strength(0.035).round() as usize },
        "solid" => Effect::Solid { color: color(a, [0, 0, 0])? },
        "cutout" => Effect::Cutout { background: color(a, [0, 0, 0])? },
        "highlight" => Effect::Highlight { color: color(a, [255, 214, 0])?, amount: 0.6 },
        "outline" => Effect::Outline { color: color(a, [255, 214, 0])?, width: strength(0.006) },
        e => bail!("efeito desconhecido: {e}"),
    })
}

fn shape(a: &Args) -> Result<MaskType> {
    Ok(match a.shape.as_deref().unwrap_or("ellipse") {
        "rectangle" | "retangulo" => MaskType::Rectangle,
        "circle" | "circulo" => MaskType::Circle,
        "ellipse" | "elipse" => MaskType::Ellipse,
        "linear" => MaskType::Linear,
        "mirror" | "espelho" => MaskType::Mirror,
        "heart" | "coracao" => MaskType::Heart,
        "star" | "estrela" => MaskType::Star,
        s => bail!("forma desconhecida: {s}"),
    })
}

fn tracker_config(a: &Args) -> TrackerConfig {
    TrackerConfig { estimate_scale: !a.no_scale, redetect: !a.no_redetect, ..TrackerConfig::default() }
}

struct Progress {
    label: &'static str,
    total: Option<u64>,
    start: Instant,
    last: Instant,
    count: u64,
}

impl Progress {
    fn new(label: &'static str, total: Option<u64>) -> Self {
        let now = Instant::now();
        Self { label, total, start: now, last: now, count: 0 }
    }

    fn tick(&mut self) {
        self.count += 1;
        if self.last.elapsed().as_secs_f32() >= 1.0 {
            self.last = Instant::now();
            self.print();
        }
    }

    fn print(&self) {
        let fps = self.count as f64 / self.start.elapsed().as_secs_f64().max(1e-6);
        match self.total {
            Some(t) => eprint!("\r{} {}/{} quadros ({:.0} quadros/s)   ", self.label, self.count, t, fps),
            None => eprint!("\r{} {} quadros ({:.0} quadros/s)   ", self.label, self.count, fps),
        }
    }

    fn done(&self) {
        self.print();
        eprintln!("— concluído em {:.1}s", self.start.elapsed().as_secs_f64());
    }
}

fn expected_frames(info: &VideoInfo, a: &Args) -> Option<u64> {
    let start = a.start.unwrap_or(0.0);
    let end = a.end.or(info.duration)?;
    Some(((end - start).max(0.0) * info.fps).round() as u64)
}

/// Passo 1: rastreia todos os alvos (numa resolução reduzida, por velocidade).
fn track_video(input: &Path, info: &VideoInfo, a: &Args, regions: &[TrackingRegion], preview: Option<&Path>) -> Result<Vec<TrackingTrack>> {
    let (tw, th) = fit_width(info.width, info.height, a.track_width.unwrap_or(640));
    let mut reader = FrameReader::open(input, info, Some((tw, th)), a.start, a.end)?;
    let mut writer = match preview {
        Some(p) => Some(FrameWriter::create(p, tw, th, info.fps, None)?),
        None => None,
    };
    let tracker = Tracker::new(tracker_config(a)).map_err(Error)?;
    let mut buf = Vec::new();
    let Some(t0) = reader.read(&mut buf)? else { bail!("o vídeo não tem quadros no trecho pedido") };
    let first = FrameFeatures::from_pixels(&buf, tw, th, tw * 3, PixelFormat::Rgb24);
    let mut sessions: Vec<_> = regions.iter().map(|r| tracker.start(&first, t0, *r)).collect();
    let mut progress = Progress::new("Rastreando", expected_frames(info, a));
    if let Some(w) = writer.as_mut() {
        for s in &sessions {
            draw_track_box(&mut buf, tw, th, &s.points()[0], 2);
        }
        w.write(&buf)?;
    }
    progress.tick();
    while let Some(t) = reader.read(&mut buf)? {
        let features = FrameFeatures::from_pixels(&buf, tw, th, tw * 3, PixelFormat::Rgb24);
        recly_tracker::par::each_mut(&mut sessions, |s| {
            s.update(&features, t);
        });
        if let Some(w) = writer.as_mut() {
            for s in &sessions {
                if let Some(p) = s.points().last() {
                    draw_track_box(&mut buf, tw, th, p, 2);
                }
            }
            w.write(&buf)?;
        }
        progress.tick();
    }
    progress.done();
    if let Some(w) = writer {
        w.finish()?;
    }
    Ok(sessions.into_iter().enumerate().map(|(i, s)| s.finish(Some(format!("track-{}", i + 1)), !a.raw)).collect())
}

/// Passo 2: aplica as máscaras (cada uma seguindo seu track) na resolução original.
fn render_video(input: &Path, info: &VideoInfo, a: &Args, masks: &[MaskState], tracks: &[TrackingTrack], out: &Path) -> Result<()> {
    let (w, h) = fit_width(info.width, info.height, 0);
    let fx = effect(a, h)?;
    for m in masks {
        if let Some(id) = &m.tracking_track_id {
            if !tracks.iter().any(|t| &t.id == id) {
                bail!("a máscara referencia o track \"{id}\", que não foi encontrado");
            }
        }
    }
    let mut reader = FrameReader::open(input, info, Some((w, h)), a.start, a.end)?;
    let audio = info.has_audio.then_some((input, a.start.unwrap_or(0.0), a.end));
    let mut writer = FrameWriter::create(out, w, h, info.fps, audio)?;
    let mut buf = Vec::new();
    let mut progress = Progress::new("Aplicando máscara", expected_frames(info, a));
    while let Some(t) = reader.read(&mut buf)? {
        for m in masks {
            let track = m.tracking_track_id.as_ref().and_then(|id| tracks.iter().find(|tr| &tr.id == id));
            let state = m.at(t, track);
            apply_mask(&mut buf, w, h, &state, &fx);
        }
        if a.show_track {
            for tr in tracks {
                draw_track_box(&mut buf, w, h, &tr.at(t), (h / 360).max(2));
            }
        }
        writer.write(&buf)?;
        progress.tick();
    }
    progress.done();
    writer.finish()
}

fn masks_for(a: &Args, regions: &[TrackingRegion], tracks: &[TrackingTrack]) -> Result<Vec<MaskState>> {
    let kind = shape(a)?;
    let scale = a.mask_scale.unwrap_or(1.15);
    regions
        .iter()
        .zip(tracks)
        .map(|(r, t)| {
            let m = MaskState {
                center_x: r.center_x,
                center_y: r.center_y,
                width: (r.width * scale).clamp(0.01, 2.0),
                height: (r.height * scale).clamp(0.01, 2.0),
                feather: a.feather.unwrap_or(0.04),
                opacity: a.opacity.unwrap_or(1.0),
                inverted: a.invert,
                tracking_track_id: Some(t.id.clone()),
                ..MaskState::default_for(kind)
            };
            m.validate()?;
            Ok(m)
        })
        .collect()
}

fn write_json(path: &Path, value: &impl ToJson) -> Result<()> {
    std::fs::write(path, json::to_string_pretty(value)).context(format!("não foi possível gravar {}", path.display()))
}

fn read_list<T: FromJson>(path: &Path) -> Result<Vec<T>> {
    let text = std::fs::read_to_string(path).context(format!("não foi possível ler {}", path.display()))?;
    let j = Json::parse(&text).context(path.display())?;
    match &j {
        Json::Arr(_) => Vec::<T>::from_json(&j),
        _ => Ok(vec![T::from_json(&j)?]),
    }
    .context(path.display())
}

fn summarize(tracks: &[TrackingTrack]) {
    for t in tracks {
        let n = t.points.len();
        let good = t.points.iter().filter(|p| p.confidence >= 0.4).count();
        let mean = t.points.iter().map(|p| p.confidence).sum::<f32>() / n.max(1) as f32;
        eprintln!("  {}: {} pontos, confiança média {:.0}%, {:.0}% dos quadros com o alvo visível", t.id, n, mean * 100.0, good as f32 / n.max(1) as f32 * 100.0);
    }
}

fn demo(a: &Args) -> Result<()> {
    let kind = match a.scene.as_deref() {
        Some(s) => SceneKind::parse(s).context(format!("cena desconhecida: {s} (rapido, luz, escala, oclusao, distrator, ruido, tremida, tudo)"))?,
        None => SceneKind::Combo,
    };
    let (w, h, n) = (a.width.unwrap_or(640) & !1, a.height.unwrap_or(360) & !1, a.frames.unwrap_or(240));
    let out = a.output.clone().unwrap_or_else(|| "demo.mp4".into());
    eprintln!("Gerando cena sintética \"{}\" ({w}x{h}, {n} quadros)…", kind.name());
    let scene = Scene::new(kind, w, h, n, 7);
    let tracker = Tracker::new(tracker_config(a)).map_err(Error)?;
    let mut session = None;
    let mut frames = Vec::with_capacity(n);
    for i in 0..n {
        let f = scene.render(i);
        let feats = FrameFeatures::from_pixels(&f, w, h, w * 3, PixelFormat::Rgb24);
        match session.as_mut() {
            None => session = Some(tracker.start(&feats, scene.time_us(i), scene.initial_region())),
            Some(s) => {
                s.update(&feats, scene.time_us(i));
            }
        }
        frames.push(f);
    }
    let track = session.unwrap().finish(Some("demo".into()), !a.raw);
    let score = evaluate(&scene, &track.points);
    eprintln!("Acerto (IoU ≥ 0.5): {:.0}%  erro médio do centro: {:.1} px", score.success * 100.0, score.center_error);
    let r = scene.initial_region();
    let mask = MaskState {
        center_x: r.center_x,
        center_y: r.center_y,
        width: r.width * a.mask_scale.unwrap_or(1.15),
        height: r.height * a.mask_scale.unwrap_or(1.15),
        tracking_track_id: Some("demo".into()),
        feather: a.feather.unwrap_or(0.04),
        inverted: a.invert,
        ..MaskState::default_for(shape(a)?)
    };
    let fx = if a.effect.is_some() { effect(a, h)? } else { Effect::Pixelate { block: (h / 30).max(4) } };
    let mut writer = FrameWriter::create(&out, w, h, scene.fps, None)?;
    for (i, mut f) in frames.into_iter().enumerate() {
        let t = scene.time_us(i);
        apply_mask(&mut f, w, h, &mask.at(t, Some(&track)), &fx);
        draw_track_box(&mut f, w, h, &track.at(t), 2);
        writer.write(&f)?;
    }
    writer.finish()?;
    eprintln!("Vídeo salvo em {}", out.display());
    Ok(())
}

fn run() -> Result<()> {
    let a = parse_args()?;
    match a.command.as_str() {
        "" | "help" | "-h" | "--help" => {
            print!("{HELP}");
            return Ok(());
        }
        "demo" => return demo(&a),
        "track" | "run" | "render" => {}
        c => bail!("comando desconhecido: {c} (use --help)"),
    }
    let input = a.input.clone().context("informe o vídeo de entrada")?;
    let info = probe(&input)?;
    eprintln!("{}: {}x{}, {:.2} fps{}", input.display(), info.width, info.height, info.fps, info.duration.map(|d| format!(", {d:.1}s")).unwrap_or_default());
    let output = a.output.clone().context("informe a saída com -o")?;

    match a.command.as_str() {
        "track" => {
            let regions = regions(&a, &info)?;
            let tracks = track_video(&input, &info, &a, &regions, a.preview.as_deref())?;
            summarize(&tracks);
            write_json(&output, &tracks)?;
            eprintln!("Tracks salvos em {}", output.display());
        }
        "run" => {
            let regions = regions(&a, &info)?;
            effect(&a, info.height)?;
            let tracks = track_video(&input, &info, &a, &regions, None)?;
            summarize(&tracks);
            let masks = masks_for(&a, &regions, &tracks)?;
            if let Some(p) = &a.save_tracks {
                write_json(p, &tracks)?;
            }
            if let Some(p) = &a.save_masks {
                write_json(p, &masks)?;
            }
            render_video(&input, &info, &a, &masks, &tracks, &output)?;
            eprintln!("Vídeo salvo em {}", output.display());
        }
        "render" => {
            let masks: Vec<MaskState> = read_list(&a.mask.clone().context("informe --mask")?)?;
            let tracks: Vec<TrackingTrack> = match &a.tracks {
                Some(p) => read_list(p)?,
                None => Vec::new(),
            };
            render_video(&input, &info, &a, &masks, &tracks, &output)?;
            eprintln!("Vídeo salvo em {}", output.display());
        }
        _ => unreachable!(),
    }
    Ok(())
}

fn main() {
    if let Err(e) = run() {
        eprintln!("\nerro: {e}");
        std::process::exit(1);
    }
}
