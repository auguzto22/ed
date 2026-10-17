//! Ensaio de ponta a ponta com vídeo de verdade (ignorado por padrão; precisa de arquivos).
//!
//! Reproduz o que a exportação faz com um clipe — mapa de tempo do motor, carimbos de tempo como
//! o Media3 os entrega depois do `SpeedProvider`, agendador da grade de saída, pipeline de GPU
//! (repetição, mistura ou optical flow, motion blur por vetores, acumulação) e áudio
//! retemporizado — sobre quadros decodificados de um arquivo real, e grava o resultado para ser
//! codificado e medido fora daqui (`tools/speed-e2e.sh`).
//!
//! Entrada em `$E2E_DIR`: `meta.txt` (`largura altura fps quadros`), `input.rgba` (quadros RGBA
//! crus, um atrás do outro) e, opcional, `audio.f32` (estéreo f32le 48 kHz).
//! Saída: `output.rgba`, `output.txt` (`quadros fps duração_µs`) e `audio_out.f32`.
//!
//! Parâmetros por variáveis de ambiente: `E2E_SPEED` (constante), `E2E_PRESET` (índice de
//! preset, sobrepõe a constante), `E2E_SYNTH` (0 repetir, 1 misturar, 2 optical flow),
//! `E2E_SHUTTER` (graus, 0 = sem blur), `E2E_OUT_FPS`, `E2E_PITCH` (1 mantém o tom).

use super::gl::{self, GLint, GLuint};
use super::pipeline::{BlurParams, MotionPipeline, Synthesis};
use super::tests::Egl;
use crate::audio::RetimeStream;
use crate::presets;
use crate::scheduler::{Action, Scheduler, SLOTS};
use crate::timemap::{self, ClipParams, TimeMap};
use std::ffi::c_void;
use std::io::Write;
use std::path::PathBuf;

struct Tex {
    texture: GLuint,
    fbo: GLuint,
}

fn texture(width: i32, height: i32) -> Tex {
    let gl = gl::gl().unwrap();
    unsafe {
        let mut texture = 0;
        (gl.GenTextures)(1, &mut texture);
        (gl.BindTexture)(gl::TEXTURE_2D, texture);
        for (p, v) in [(gl::TEXTURE_MIN_FILTER, gl::LINEAR), (gl::TEXTURE_MAG_FILTER, gl::LINEAR), (gl::TEXTURE_WRAP_S, gl::CLAMP_TO_EDGE), (gl::TEXTURE_WRAP_T, gl::CLAMP_TO_EDGE)] {
            (gl.TexParameteri)(gl::TEXTURE_2D, p, v);
        }
        (gl.TexImage2D)(gl::TEXTURE_2D, 0, gl::RGBA as GLint, width, height, 0, gl::RGBA, gl::UNSIGNED_BYTE, std::ptr::null());
        let mut fbo = 0;
        (gl.GenFramebuffers)(1, &mut fbo);
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, fbo);
        (gl.FramebufferTexture2D)(gl::FRAMEBUFFER, gl::COLOR_ATTACHMENT0, gl::TEXTURE_2D, texture, 0);
        assert_eq!((gl.CheckFramebufferStatus)(gl::FRAMEBUFFER), gl::FRAMEBUFFER_COMPLETE);
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, 0);
        Tex { texture, fbo }
    }
}

fn upload(target: &Tex, width: i32, height: i32, pixels: &[u8]) {
    let gl = gl::gl().unwrap();
    unsafe {
        (gl.BindTexture)(gl::TEXTURE_2D, target.texture);
        (gl.PixelStorei)(gl::UNPACK_ALIGNMENT, 1);
        (gl.TexImage2D)(gl::TEXTURE_2D, 0, gl::RGBA as GLint, width, height, 0, gl::RGBA, gl::UNSIGNED_BYTE, pixels.as_ptr() as *const c_void);
    }
}

fn read(target: &Tex, width: i32, height: i32, out: &mut Vec<u8>) {
    let gl = gl::gl().unwrap();
    out.resize((width * height * 4) as usize, 0);
    unsafe {
        (gl.BindFramebuffer)(gl::FRAMEBUFFER, target.fbo);
        (gl.PixelStorei)(gl::PACK_ALIGNMENT, 1);
        (gl.ReadPixels)(0, 0, width, height, gl::RGBA, gl::UNSIGNED_BYTE, out.as_mut_ptr() as *mut c_void);
        assert_eq!((gl.GetError)(), gl::NO_ERROR);
    }
}

fn env<T: std::str::FromStr>(name: &str, default: T) -> T {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

/// Igual ao `MotionRetimeProgram.flowSize` (Kotlin).
fn flow_size(width: i32, height: i32, short_side: i32) -> (i32, i32) {
    let scale = short_side as f32 / width.min(height).max(1) as f32;
    let even = |value: f32| ((value / 2.0) as i32 * 2).max(8);
    if scale >= 1.0 { (even(width as f32), even(height as f32)) } else { (even(width as f32 * scale), even(height as f32 * scale)) }
}

#[test]
#[ignore]
fn render_real_video() {
    let dir = PathBuf::from(std::env::var("E2E_DIR").expect("E2E_DIR"));
    let meta = std::fs::read_to_string(dir.join("meta.txt")).unwrap();
    let meta: Vec<f64> = meta.split_whitespace().map(|v| v.parse().unwrap()).collect();
    let (width, height, input_fps, count) = (meta[0] as i32, meta[1] as i32, meta[2], meta[3] as usize);
    let frames = std::fs::read(dir.join("input.rgba")).unwrap();
    let frame_bytes = (width * height * 4) as usize;
    assert_eq!(frames.len(), frame_bytes * count);

    let synthesis = match env("E2E_SYNTH", 2) { 0 => Synthesis::Repeat, 1 => Synthesis::Blend, _ => Synthesis::OpticalFlow };
    let shutter: f32 = env("E2E_SHUTTER", 0.0);
    let output_fps: f32 = env("E2E_OUT_FPS", 30.0);
    let speed: f32 = env("E2E_SPEED", 1.0);
    let preset: i64 = env("E2E_PRESET", -1);
    let preserve_pitch = env("E2E_PITCH", 1) == 1;
    // Aceleração com blur: cada amostra cobre o intervalo até a próxima (1) ou entra nítida (0).
    let fill_gaps = env("E2E_FILL", 1) == 1;
    let samples = 24;

    // Mapa de tempo do clipe inteiro (como o VideoClip do projeto).
    let source_us = (count as f64 * 1e6 / input_fps).round() as i64;
    // Corte no fim (`E2E_TRIM_US`), que pode cair no meio de um quadro, como na timeline.
    let out_us = source_us - env::<i64>("E2E_TRIM_US", 0);
    let points = if preset >= 0 { presets::build(preset as usize, 0, source_us, 1.0).expect("preset") } else { Vec::new() };
    let table = timemap::build(&ClipParams { source_us, in_us: 0, out_us, speed, reverse: false }, &points).unwrap();
    let map = TimeMap::new(&table).unwrap();

    let _egl = Egl::new();
    let exposure_fraction = if shutter > 0.0 { shutter / 360.0 } else { 0.0 };
    let mut scheduler = Scheduler::new(output_fps, exposure_fraction).unwrap();
    // Como na exportação: o clipe começa em 0 e termina na duração do mapa.
    scheduler.set_end(Some(map.duration_us()));
    // Exportação em alta qualidade: flow com metade do lado menor (até 540), 5 níveis, 5 iterações.
    let short = (width.min(height) / 2).min(540).max(64);
    let (flow_w, flow_h) = flow_size(width, height, short);
    let mut pipeline = MotionPipeline::new(width, height, flow_w, flow_h, 5, 5).unwrap();
    let input = texture(width, height);
    let history: Vec<Tex> = (0..SLOTS).map(|_| texture(width, height)).collect();
    let mut history_pts = [i64::MIN; SLOTS];
    let output = texture(width, height);
    let viewport = (0, 0, width, height);
    let vector_blur = shutter > 0.0;
    let mut writer = std::io::BufWriter::new(std::fs::File::create(dir.join("output.rgba")).unwrap());
    let mut pixels = Vec::new();
    let mut emitted = Vec::<i64>::new();
    let mut actions = Vec::new();
    let mut accumulated_frames = 0u32;
    let mut cuts = 0;

    let mut run = |actions: &mut Vec<Action>, pipeline: &mut MotionPipeline, history_pts: &mut [i64; SLOTS]| {
        for action in actions.drain(..) {
            match action {
                Action::Store { slot, pts_us } => {
                    pipeline.copy(input.texture, history[slot].fbo, viewport);
                    history_pts[slot] = pts_us;
                }
                Action::Accumulate { slot, output_us, next_slot, from_us, to_us } => match next_slot {
                    Some(next) if vector_blur && fill_gaps => pipeline.accumulate_pair(history[slot].texture, history[next].texture, (from_us, to_us), samples, output_us),
                    _ => pipeline.accumulate(history[slot].texture, output_us),
                },
                Action::EmitAccumulated { output_us, frames } => {
                    pipeline.emit_accumulated(output.fbo, viewport);
                    accumulated_frames += frames;
                    read(&output, width, height, &mut pixels);
                    writer.write_all(&pixels).unwrap();
                    emitted.push(output_us);
                }
                Action::EmitSynthesized { output_us, mut from_slot, to_slot, mut from_us, to_us, mut phase, exposure_us } => {
                    if from_slot == to_slot && vector_blur && exposure_us > 0 {
                        let previous = (0..SLOTS).filter(|&s| s != to_slot && history_pts[s] != i64::MIN && history_pts[s] < to_us)
                            .max_by_key(|&s| history_pts[s]);
                        if let Some(previous) = previous {
                            if to_us - history_pts[previous] <= 500_000 {
                                from_slot = previous;
                                from_us = history_pts[previous];
                                phase = 1.0;
                            }
                        }
                    }
                    if from_slot == to_slot {
                        pipeline.copy(history[to_slot].texture, output.fbo, viewport);
                    } else {
                        let needs_flow = synthesis == Synthesis::OpticalFlow || vector_blur;
                        let stats = pipeline.analyze(history[from_slot].texture, history[to_slot].texture, (from_us, to_us), needs_flow);
                        if stats.cut {
                            cuts += 1;
                        }
                        let blur = (vector_blur && exposure_us > 0)
                            .then(|| BlurParams { scale: exposure_us as f32 / (to_us - from_us) as f32, samples });
                        pipeline.render(history[from_slot].texture, history[to_slot].texture, phase, synthesis, 0.6, blur, output.fbo, viewport);
                    }
                    read(&output, width, height, &mut pixels);
                    writer.write_all(&pixels).unwrap();
                    emitted.push(output_us);
                }
            }
        }
    };

    for index in 0..count {
        // O Media3 entrega cada quadro com o instante de timeline do SpeedProvider.
        let source_pts = (index as f64 * 1e6 / input_fps).round() as i64;
        if source_pts >= out_us {
            break; // depois do corte: o ClippingMediaSource não entrega
        }
        let pts = map.timeline_at(source_pts);
        upload(&input, width, height, &frames[index * frame_bytes..(index + 1) * frame_bytes]);
        scheduler.on_input(pts, &mut actions).unwrap();
        run(&mut actions, &mut pipeline, &mut history_pts);
    }
    scheduler.on_end_of_stream(&mut actions);
    run(&mut actions, &mut pipeline, &mut history_pts);
    drop(run);
    writer.flush().unwrap();
    pipeline.release();

    // A grade de saída tem de ser contínua, sem buracos nem repetições, e ir até o fim do clipe
    // sem passar dele (o próximo clipe da sequência começa ali).
    for (k, pts) in emitted.iter().enumerate() {
        assert_eq!(*pts, (k as f64 * 1e6 / output_fps as f64).round() as i64, "grade quebrada no quadro {k}");
    }
    let end = map.duration_us() - crate::scheduler::END_MARGIN_US;
    let expected_frames = (0..).take_while(|&k: &i64| ((k as f64 * 1e6 / output_fps as f64).round() as i64) < end).count();
    assert_eq!(emitted.len(), expected_frames, "quadros até o fim do clipe");

    // Áudio retemporizado (o mesmo processador da prévia), guiado pela curva.
    let mut audio_frames_out = 0usize;
    if let Ok(bytes) = std::fs::read(dir.join("audio.f32")) {
        let samples_in: Vec<f32> = bytes.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect();
        let total_in = samples_in.len() / 2;
        let expected_out = (map.duration_us() as f64 * 48_000.0 / 1e6).round() as usize;
        let mut stream = RetimeStream::new();
        let mut cursor = 0usize;
        let mut out = Vec::<f32>::with_capacity(expected_out * 2);
        let mut block = vec![0f32; 2048];
        let mut finished = false;
        while audio_frames_out < expected_out {
            let timeline_us = (audio_frames_out as f64 * 1e6 / 48_000.0) as i64;
            let speed = map.curve_speed_at_timeline(timeline_us) as f64;
            let need = stream.input_needed(1024, speed, preserve_pitch);
            if need > 0 {
                if cursor >= total_in {
                    if finished {
                        break;
                    }
                    // Fim da mídia: solta o que ficou nos buffers (como a exportação faz).
                    stream.finish(speed);
                    finished = true;
                    continue;
                }
                let end = (cursor + need.max(256)).min(total_in);
                stream.push(&samples_in[cursor * 2..end * 2], speed, preserve_pitch, 1024);
                cursor = end;
            }
            let got = stream.pull(&mut block, 1024.min(expected_out - audio_frames_out));
            out.extend_from_slice(&block[..got * 2]);
            audio_frames_out += got;
            if got == 0 && (need == 0 || finished) {
                break;
            }
        }
        let bytes: Vec<u8> = out.iter().flat_map(|v| v.to_le_bytes()).collect();
        std::fs::write(dir.join("audio_out.f32"), bytes).unwrap();
        println!("áudio: {} quadros de saída (esperado {expected_out}), mídia consumida {cursor}/{total_in}", audio_frames_out);
    }

    std::fs::write(dir.join("output.txt"), format!("{} {} {}\n", emitted.len(), output_fps, map.duration_us())).unwrap();
    println!(
        "vídeo: {} quadros de entrada → {} de saída a {output_fps} fps, duração do mapa {} µs, {} quadros acumulados, {} cortes",
        count, emitted.len(), map.duration_us(), accumulated_frames, cuts
    );
}

/// Confere o MP4 codificado com a mesma regra da exportação (`$E2E_DIR/probe.txt`:
/// `esperado_µs vídeo_µs áudio_µs fps`, áudio < 0 quando não há).
#[test]
#[ignore]
fn validate_real_export() {
    let dir = PathBuf::from(std::env::var("E2E_DIR").expect("E2E_DIR"));
    let values: Vec<i64> = std::fs::read_to_string(dir.join("probe.txt")).unwrap().split_whitespace().map(|v| v.parse().unwrap()).collect();
    let audio = (values[2] >= 0).then_some(values[2]);
    let (verdict, difference) = crate::validate::check(values[0], values[1], audio, audio.is_some(), values[3] as i32);
    let text = crate::validate::text(verdict, difference);
    println!("veredito: {verdict:?} ({difference} µs) {text}");
    assert_eq!(verdict, crate::validate::Verdict::Ok, "{text}");
}
