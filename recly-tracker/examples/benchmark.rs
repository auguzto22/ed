//! Compara o rastreador novo com o port fiel do `TemplateMotionTracker` atual do app.
//!
//!     cargo run --release --example benchmark [-- largura altura quadros]

use recly_tracker::legacy::LegacyTracker;
use recly_tracker::synth::{evaluate, Scene, SceneKind, Score};
use recly_tracker::{FrameFeatures, PixelFormat, Tracker, TrackerConfig};
use std::time::Instant;

fn main() {
    let args: Vec<usize> = std::env::args().skip(1).filter_map(|a| a.parse().ok()).collect();
    let (w, h, n) = (*args.first().unwrap_or(&480), *args.get(1).unwrap_or(&270), *args.get(2).unwrap_or(&180));
    println!("Cenas sintéticas {w}x{h}, {n} quadros cada. Sucesso = IoU ≥ 0.5 (quadros com alvo visível).\n");
    println!("{:<20} | {:>22} | {:>22} | {:>9}", "cena", "antigo (Kotlin)", "novo (Rust)", "fim novo");
    println!("{:<20} | {:>9} {:>12} | {:>9} {:>12} | {:>9}", "", "sucesso", "erro (px)", "sucesso", "erro (px)", "sucesso");
    println!("{}", "-".repeat(84));
    let (mut sum_old, mut sum_new) = (Score::default(), Score::default());
    let (mut t_old, mut t_new) = (0.0, 0.0);
    for kind in SceneKind::ALL {
        let scene = Scene::new(kind, w, h, n, 7);
        let frames: Vec<Vec<u8>> = (0..n).map(|i| scene.render(i)).collect();
        let region = scene.initial_region();

        let start = Instant::now();
        let mut legacy = LegacyTracker::new(&frames[0], w, h, 0, region);
        for (i, f) in frames.iter().enumerate().skip(1) {
            legacy.next(f, w, h, scene.time_us(i));
        }
        t_old += start.elapsed().as_secs_f64();
        let old = evaluate(&scene, &legacy.finish().points);

        let start = Instant::now();
        let tracker = Tracker::new(TrackerConfig::default()).unwrap();
        let f0 = FrameFeatures::from_pixels(&frames[0], w, h, w * 3, PixelFormat::Rgb24);
        let mut session = tracker.start(&f0, 0, region);
        for (i, f) in frames.iter().enumerate().skip(1) {
            let feats = FrameFeatures::from_pixels(f, w, h, w * 3, PixelFormat::Rgb24);
            session.update(&feats, scene.time_us(i));
        }
        let track = session.finish(None, true);
        t_new += start.elapsed().as_secs_f64();
        let new = evaluate(&scene, &track.points);

        println!(
            "{:<20} | {:>8.0}% {:>12.1} | {:>8.0}% {:>12.1} | {:>8.0}%",
            kind.name(),
            old.success * 100.0,
            old.center_error,
            new.success * 100.0,
            new.center_error,
            new.end_success * 100.0
        );
        sum_old.success += old.success;
        sum_old.center_error += old.center_error;
        sum_new.success += new.success;
        sum_new.center_error += new.center_error;
    }
    let k = SceneKind::ALL.len() as f32;
    println!("{}", "-".repeat(84));
    println!(
        "{:<20} | {:>8.0}% {:>12.1} | {:>8.0}% {:>12.1} |",
        "média",
        sum_old.success / k * 100.0,
        sum_old.center_error / k,
        sum_new.success / k * 100.0,
        sum_new.center_error / k
    );
    let frames = (n * SceneKind::ALL.len()) as f64;
    println!("\nVelocidade: antigo {:.1} ms/quadro, novo {:.1} ms/quadro", t_old * 1e3 / frames, t_new * 1e3 / frames);
}
