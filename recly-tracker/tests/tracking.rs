//! Testes de qualidade do rastreador sobre cenas sintéticas com verdade de referência.

use recly_tracker::image::{FrameFeatures, PixelFormat};
use recly_tracker::legacy::LegacyTracker;
use recly_tracker::synth::{evaluate, Scene, SceneKind};
use recly_tracker::{Tracker, TrackerConfig, TrackingRegion};

const W: usize = 320;
const H: usize = 180;
const N: usize = 120;

fn features(rgb: &[u8], w: usize, h: usize) -> FrameFeatures {
    FrameFeatures::from_pixels(rgb, w, h, w * 3, PixelFormat::Rgb24)
}

fn run_new(scene: &Scene) -> recly_tracker::synth::Score {
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let mut s = tracker.start(&features(&scene.render(0), W, H), 0, scene.initial_region());
    for i in 1..scene.frames {
        s.update(&features(&scene.render(i), W, H), scene.time_us(i));
    }
    evaluate(scene, &s.finish(None, true).points)
}

fn run_old(scene: &Scene) -> recly_tracker::synth::Score {
    let mut t = LegacyTracker::new(&scene.render(0), W, H, 0, scene.initial_region());
    for i in 1..scene.frames {
        t.next(&scene.render(i), W, H, scene.time_us(i));
    }
    evaluate(scene, &t.finish().points)
}

#[test]
fn every_scene_is_tracked_and_beats_the_old_tracker() {
    let mut new_total = 0.0;
    let mut old_total = 0.0;
    for kind in SceneKind::ALL {
        for seed in [3, 11] {
            let scene = Scene::new(kind, W, H, N, seed);
            let new = run_new(&scene);
            let old = run_old(&scene);
            println!("{:<18} seed {seed:>2}: novo {:.0}% ({:.1}px)  antigo {:.0}%", kind.name(), new.success * 100.0, new.center_error, old.success * 100.0);
            assert!(new.success >= 0.93, "{} (seed {seed}): só {:.0}% de acerto", kind.name(), new.success * 100.0);
            assert!(new.end_success >= 0.9, "{} (seed {seed}): não recuperou no fim", kind.name());
            new_total += new.success;
            old_total += old.success;
        }
    }
    assert!(new_total > old_total + 3.0, "novo {new_total} vs antigo {old_total}");
}

/// Fundo texturizado fixo e alvo com padrão próprio, colado onde for pedido.
fn frame_with_target(cx: usize, cy: usize, tw: usize, th: usize) -> Vec<u8> {
    let mut f = vec![0u8; W * H * 3];
    for y in 0..H {
        for x in 0..W {
            let i = (y * W + x) * 3;
            let n = ((x * 73856093) ^ (y * 19349663)) % 97;
            let smooth = ((x / 6 + y / 5) % 7) * 18;
            f[i] = (40 + smooth + n / 3) as u8;
            f[i + 1] = (60 + n) as u8;
            f[i + 2] = (90 + smooth / 2) as u8;
            let (dx, dy) = (x as isize - (cx - tw / 2) as isize, y as isize - (cy - th / 2) as isize);
            if dx >= 0 && dy >= 0 && (dx as usize) < tw && (dy as usize) < th {
                let checker = ((dx / 5 + dy / 5) % 2) as u8;
                f[i] = 210 - checker * 60;
                f[i + 1] = 40 + (dx as u8 % 9) * 12;
                f[i + 2] = 30 + checker * 120;
            }
        }
    }
    f
}

#[test]
fn redetects_after_target_teleports() {
    // Corte de cena: o alvo some de um canto e reaparece no outro, fora da janela de busca.
    let (tw, th) = (36, 28);
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let region = TrackingRegion::from_pixels(60.0 - 18.0, 50.0 - 14.0, tw as f32, th as f32, W, H).unwrap();
    let mut s = tracker.start(&features(&frame_with_target(60, 50, tw, th), W, H), 0, region);
    let mut recovered_at = None;
    for i in 1..40 {
        let (cx, cy) = if i < 10 { (60 + i, 50) } else { (260, 140) };
        let p = s.update(&features(&frame_with_target(cx, cy, tw, th), W, H), i as i64 * 33_333).unwrap();
        if i >= 10 && recovered_at.is_none() && (p.center_x * W as f32 - 260.0).abs() < 4.0 && (p.center_y * H as f32 - 140.0).abs() < 4.0 {
            recovered_at = Some(i);
        }
    }
    let at = recovered_at.expect("o alvo não foi redetectado");
    assert!(at <= 20, "redetectado só no quadro {at}");
}

#[test]
fn tracks_several_targets_at_once() {
    let scene_a = Scene::new(SceneKind::FastMotion, W, H, 60, 5);
    let frames: Vec<FrameFeatures> = (0..60).map(|i| features(&scene_a.render(i), W, H)).collect();
    let t = scene_a.truth(0);
    // Dois alvos: o objeto em movimento e um pedaço fixo do fundo.
    let regions = [scene_a.initial_region(), TrackingRegion::new(0.08, 0.1, 0.12, 0.15).unwrap()];
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let tracks = tracker.track_all(frames.iter().enumerate().map(|(i, f)| (scene_a.time_us(i), f)), &regions);
    assert_eq!(tracks.len(), 2);
    assert!(evaluate(&scene_a, &tracks[0].points).success > 0.95);
    let still = &tracks[1].points;
    let drift = still.iter().map(|p| (p.center_x - 0.08).abs().max((p.center_y - 0.1).abs())).fold(0.0f32, f32::max);
    assert!(drift < 0.01, "o alvo parado andou {drift}");
    assert!(t.w > 0.0);
}

#[test]
fn survives_decoder_size_change() {
    // O app pode receber quadros com outro tamanho no meio do vídeo: a posição normalizada se mantém.
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let region = TrackingRegion::from_pixels(100.0, 60.0, 36.0, 28.0, W, H).unwrap();
    let mut s = tracker.start(&features(&frame_with_target(118, 74, 36, 28), W, H), 0, region);
    for i in 1..5 {
        s.update(&features(&frame_with_target(118 + i, 74, 36, 28), W, H), i as i64 * 33_333);
    }
    // Mesmo conteúdo em resolução dobrada.
    let big = frame_with_target(122, 74, 36, 28);
    let mut up = vec![0u8; W * 2 * H * 2 * 3];
    for y in 0..H * 2 {
        for x in 0..W * 2 {
            let src = ((y / 2) * W + x / 2) * 3;
            let dst = (y * W * 2 + x) * 3;
            up[dst..dst + 3].copy_from_slice(&big[src..src + 3]);
        }
    }
    let p = s.update(&FrameFeatures::from_pixels(&up, W * 2, H * 2, W * 6, PixelFormat::Rgb24), 5 * 33_333).unwrap();
    assert!((p.center_x * W as f32 - 122.0).abs() < 2.0, "x = {}", p.center_x * W as f32);
    assert!((p.center_y * H as f32 - 74.0).abs() < 2.0);
}

#[test]
fn output_fits_app_limits() {
    let scene = Scene::new(SceneKind::FastMotion, 160, 90, 30, 1);
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let mut s = tracker.start(&features(&scene.render(0), 160, 90), 0, scene.initial_region());
    for i in 1..30 {
        s.update(&features(&scene.render(i), 160, 90), scene.time_us(i));
    }
    // O mesmo tempo repetido é ignorado, como no Kotlin.
    assert!(s.update(&features(&scene.render(29), 160, 90), scene.time_us(29)).is_none());
    let track = s.finish(Some("abc-1".into()), true);
    track.validate().expect("track compatível com TrackingTrack do app");
}
