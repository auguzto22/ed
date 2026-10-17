use recly_tracker::synth::{iou, Scene, SceneKind};
use recly_tracker::{FrameFeatures, PixelFormat, Tracker, TrackerConfig};
fn main() {
    let kind = SceneKind::parse(&std::env::args().nth(1).unwrap_or("shake".into())).unwrap();
    let (w, h, n) = (480, 270, 180);
    let scene = Scene::new(kind, w, h, n, 7);
    let tracker = Tracker::new(TrackerConfig::default()).unwrap();
    let f0 = FrameFeatures::from_pixels(&scene.render(0), w, h, w * 3, PixelFormat::Rgb24);
    let mut s = tracker.start(&f0, 0, scene.initial_region());
    for i in 1..n {
        let f = FrameFeatures::from_pixels(&scene.render(i), w, h, w * 3, PixelFormat::Rgb24);
        let p = s.update(&f, scene.time_us(i)).unwrap();
        let t = scene.truth(i);
        let v = iou(&t, &p, w, h);
        if v < 0.6 || i % 30 == 0 {
            println!("{i:3} iou {v:.2} conf {:.2} pred ({:.0},{:.0}) truth ({:.0},{:.0}) vis {:.1} {:?}", p.confidence, p.center_x*w as f32, p.center_y*h as f32, t.cx, t.cy, t.visible, s.status());
        }
    }
}
