//! Aplicação de máscaras em quadros RGB24 e sobreposições de depuração.
//!
//! * `Cutout` segue exatamente o contrato do app: a máscara define o alpha da camada e o que
//!   fica fora é substituído pelo fundo.
//! * Os efeitos (`Blur`, `Pixelate`, `Solid`, `Highlight`) são aplicados *dentro* da forma
//!   (ou fora, com `inverted`), com intensidade = cobertura × opacidade. Ex.: borrar um rosto
//!   rastreado, ou borrar o fundo e manter a pessoa nítida com a máscara invertida.

use crate::mask::MaskState;
use crate::track::TrackingPoint;
use crate::par;

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Effect {
    /// Recorte: fora da máscara vira `background`.
    Cutout { background: [u8; 3] },
    /// Desfoque gaussiano aproximado (três passadas de caixa), raio em pixels.
    Blur { radius: usize },
    /// Pixelização com blocos de `block` pixels.
    Pixelate { block: usize },
    /// Preenchimento com cor sólida.
    Solid { color: [u8; 3] },
    /// Realce: clareia e tinge a área.
    Highlight { color: [u8; 3], amount: f32 },
    /// Apenas o contorno da forma, com espessura em pixels.
    Outline { color: [u8; 3], width: f32 },
}

/// Aplica `mask` (já avaliada no tempo do quadro) em `frame` (RGB24, `w × h`).
pub fn apply_mask(frame: &mut [u8], w: usize, h: usize, mask: &MaskState, effect: &Effect) {
    assert_eq!(frame.len(), w * h * 3, "quadro com tamanho inesperado");
    if let Effect::Outline { color, width } = *effect {
        draw_mask_outline(frame, w, h, mask, color, width);
        return;
    }
    let coverage = mask.rasterize_shape(w, h, true);
    let opacity = mask.opacity.clamp(0.0, 1.0);
    match *effect {
        Effect::Cutout { background } => {
            par::rows_mut(frame, w * 3, 4, |y, row| {
                for x in 0..w {
                    let a = 1.0 + (coverage[y * w + x] - 1.0) * opacity;
                    for i in 0..3 {
                        row[x * 3 + i] = lerp_u8(background[i], row[x * 3 + i], a);
                    }
                }
            });
        }
        Effect::Solid { color } => blend_with(frame, &coverage, opacity, |_, _, _| color, w),
        Effect::Highlight { color, amount } => {
            let amount = amount.clamp(0.0, 1.0);
            let src = frame.to_vec();
            blend_with(
                frame,
                &coverage,
                opacity,
                |x, y, _| {
                    let p = &src[(y * w + x) * 3..][..3];
                    std::array::from_fn(|i| {
                        let lifted = p[i] as f32 + (255.0 - p[i] as f32) * 0.35 * amount;
                        (lifted * (1.0 - 0.5 * amount) + color[i] as f32 * 0.5 * amount).round().clamp(0.0, 255.0) as u8
                    })
                },
                w,
            );
        }
        Effect::Blur { radius } | Effect::Pixelate { block: radius } => {
            let Some((x0, y0, x1, y1)) = bbox(&coverage, w, h, 0.002) else { return };
            // Margem para que o desfoque perto da borda use pixels reais.
            let pad = if matches!(effect, Effect::Blur { .. }) { radius * 3 } else { 0 };
            let (rx0, ry0) = (x0.saturating_sub(pad), y0.saturating_sub(pad));
            let (rx1, ry1) = ((x1 + pad).min(w - 1), (y1 + pad).min(h - 1));
            let rw = rx1 - rx0 + 1;
            let rh = ry1 - ry0 + 1;
            let mut region = vec![0.0f32; rw * rh * 3];
            for y in 0..rh {
                for x in 0..rw * 3 {
                    region[y * rw * 3 + x] = frame[((ry0 + y) * w + rx0) * 3 + x] as f32;
                }
            }
            let processed = match *effect {
                Effect::Blur { radius } => {
                    let r = radius.max(1);
                    let mut img = region;
                    for _ in 0..3 {
                        img = box_blur(&img, rw, rh, r);
                    }
                    img
                }
                Effect::Pixelate { block } => pixelate(&region, rw, rh, block.max(2), rx0, ry0),
                _ => unreachable!(),
            };
            par::rows_mut(frame, w * 3, 6, |y, row| {
                if y < ry0 || y > ry1 {
                    return;
                }
                for x in rx0..=rx1 {
                    let a = coverage[y * w + x] * opacity;
                    if a <= 0.0 {
                        continue;
                    }
                    let src = &processed[((y - ry0) * rw + (x - rx0)) * 3..][..3];
                    for i in 0..3 {
                        let v = src[i].round().clamp(0.0, 255.0) as u8;
                        row[x * 3 + i] = lerp_u8(row[x * 3 + i], v, a);
                    }
                }
            });
        }
        Effect::Outline { .. } => unreachable!(),
    }
}

fn blend_with<F>(frame: &mut [u8], coverage: &[f32], opacity: f32, color_at: F, w: usize)
where
    F: Fn(usize, usize, &[u8]) -> [u8; 3] + Sync,
{
    par::rows_mut(frame, w * 3, 6, |y, row| {
        for x in 0..w {
            let a = coverage[y * w + x] * opacity;
            if a <= 0.0 {
                continue;
            }
            let px = &mut row[x * 3..x * 3 + 3];
            let c = color_at(x, y, px);
            for i in 0..3 {
                px[i] = lerp_u8(px[i], c[i], a);
            }
        }
    });
}

#[inline]
fn lerp_u8(a: u8, b: u8, t: f32) -> u8 {
    (a as f32 + (b as f32 - a as f32) * t.clamp(0.0, 1.0)).round() as u8
}

fn bbox(coverage: &[f32], w: usize, h: usize, threshold: f32) -> Option<(usize, usize, usize, usize)> {
    let rows: Vec<Option<(usize, usize)>> = coverage
        .chunks(w)
        .map(|row| {
            let first = row.iter().position(|&a| a > threshold)?;
            let last = row.iter().rposition(|&a| a > threshold)?;
            Some((first, last))
        })
        .collect();
    let mut out: Option<(usize, usize, usize, usize)> = None;
    for (y, r) in rows.iter().enumerate().take(h) {
        if let Some((a, b)) = r {
            out = Some(match out {
                None => (*a, y, *b, y),
                Some((x0, y0, x1, _)) => (x0.min(*a), y0, x1.max(*b), y),
            });
        }
    }
    out
}

/// Desfoque de caixa separável em RGB intercalado (bordas presas).
fn box_blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let mut tmp = vec![0.0f32; src.len()];
    let norm = 1.0 / (2 * r + 1) as f32;
    par::rows_mut(&mut tmp, w * 3, 2 * r + 4, |y, row| {
        let s = &src[y * w * 3..][..w * 3];
        for c in 0..3 {
            let at = |x: isize| s[(x.clamp(0, w as isize - 1) as usize) * 3 + c];
            let mut acc: f32 = (-(r as isize)..=r as isize).map(at).sum();
            for x in 0..w {
                row[x * 3 + c] = acc * norm;
                acc += at(x as isize + r as isize + 1) - at(x as isize - r as isize);
            }
        }
    });
    let mut out = vec![0.0f32; src.len()];
    let columns: Vec<Vec<f32>> = par::map(w * 3, h * 4, |xc| {
            let at = |y: isize| tmp[(y.clamp(0, h as isize - 1) as usize) * w * 3 + xc];
            let mut col = vec![0.0f32; h];
            let mut acc: f32 = (-(r as isize)..=r as isize).map(at).sum();
            for (y, v) in col.iter_mut().enumerate() {
                *v = acc * norm;
                acc += at(y as isize + r as isize + 1) - at(y as isize - r as isize);
            }
            col
        });
    for (xc, col) in columns.iter().enumerate() {
        for (y, v) in col.iter().enumerate() {
            out[y * w * 3 + xc] = *v;
        }
    }
    out
}

/// Pixelização alinhada à grade global (os blocos não "nadam" quando a região se move).
fn pixelate(src: &[f32], w: usize, h: usize, block: usize, ox: usize, oy: usize) -> Vec<f32> {
    let mut out = vec![0.0f32; src.len()];
    let bx0 = ox / block;
    let by0 = oy / block;
    let bx1 = (ox + w - 1) / block;
    let by1 = (oy + h - 1) / block;
    for by in by0..=by1 {
        for bx in bx0..=bx1 {
            let xs = (bx * block).max(ox) - ox;
            let ys = (by * block).max(oy) - oy;
            let xe = ((bx + 1) * block).min(ox + w) - ox;
            let ye = ((by + 1) * block).min(oy + h) - oy;
            let mut sum = [0.0f32; 3];
            let n = ((xe - xs) * (ye - ys)) as f32;
            for y in ys..ye {
                for x in xs..xe {
                    for c in 0..3 {
                        sum[c] += src[(y * w + x) * 3 + c];
                    }
                }
            }
            for y in ys..ye {
                for x in xs..xe {
                    for c in 0..3 {
                        out[(y * w + x) * 3 + c] = sum[c] / n;
                    }
                }
            }
        }
    }
    out
}

/// Desenha o contorno da forma da máscara (largura em pixels), anti-aliasado.
pub fn draw_mask_outline(frame: &mut [u8], w: usize, h: usize, mask: &MaskState, color: [u8; 3], width: f32) {
    let aspect = w as f32 / h as f32;
    let px = 1.0 / h as f32;
    let half = width.max(1.0) * 0.5 * px;
    par::rows_mut(frame, w * 3, 6, |y, row| {
        let v = (y as f32 + 0.5) / h as f32;
        for x in 0..w {
            let d = mask.signed_distance((x as f32 + 0.5) / w as f32, v, aspect).abs();
            let a = (1.0 - (d - half) / px).clamp(0.0, 1.0);
            if a > 0.0 {
                for i in 0..3 {
                    row[x * 3 + i] = lerp_u8(row[x * 3 + i], color[i], a);
                }
            }
        }
    });
}

/// Desenha a caixa de um ponto rastreado. Cor: verde (confiante) → vermelho (oclusão).
pub fn draw_track_box(frame: &mut [u8], w: usize, h: usize, p: &TrackingPoint, thickness: usize) {
    let c = p.confidence.clamp(0.0, 1.0);
    let color = [((1.0 - c) * 255.0) as u8, (c * 220.0 + 35.0) as u8, 40];
    let x0 = ((p.center_x - p.width / 2.0) * w as f32).round() as isize;
    let x1 = ((p.center_x + p.width / 2.0) * w as f32).round() as isize;
    let y0 = ((p.center_y - p.height / 2.0) * h as f32).round() as isize;
    let y1 = ((p.center_y + p.height / 2.0) * h as f32).round() as isize;
    let t = thickness.max(1) as isize;
    let mut put = |x: isize, y: isize| {
        if x >= 0 && y >= 0 && (x as usize) < w && (y as usize) < h {
            let i = (y as usize * w + x as usize) * 3;
            frame[i..i + 3].copy_from_slice(&color);
        }
    };
    for k in 0..t {
        for x in x0..=x1 {
            put(x, y0 + k);
            put(x, y1 - k);
        }
        for y in y0..=y1 {
            put(x0 + k, y);
            put(x1 - k, y);
        }
    }
    // Cruz no centro e barra de confiança.
    let cx = (p.center_x * w as f32) as isize;
    let cy = (p.center_y * h as f32) as isize;
    for d in -4..=4 {
        put(cx + d, cy);
        put(cx, cy + d);
    }
    let bar = ((x1 - x0) as f32 * c) as isize;
    for k in 0..t.max(2) {
        for x in x0..x0 + bar {
            put(x, y0 - 3 - k);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mask::MaskType;

    fn frame(w: usize, h: usize) -> Vec<u8> {
        (0..w * h * 3).map(|i| ((i * 31) % 251) as u8).collect()
    }

    #[test]
    fn cutout_keeps_inside_and_replaces_outside() {
        let (w, h) = (64, 48);
        let mut f = frame(w, h);
        let orig = f.clone();
        let m = MaskState { feather: 0.0, width: 0.5, height: 0.5, ..MaskState::default_for(MaskType::Rectangle) };
        apply_mask(&mut f, w, h, &m, &Effect::Cutout { background: [0, 255, 0] });
        let c = (24 * w + 32) * 3;
        assert_eq!(&f[c..c + 3], &orig[c..c + 3]);
        assert_eq!(&f[0..3], &[0, 255, 0]);
    }

    #[test]
    fn blur_only_touches_the_shape() {
        let (w, h) = (80, 60);
        let mut f = frame(w, h);
        let orig = f.clone();
        let m = MaskState { feather: 0.0, width: 0.3, height: 0.3, ..MaskState::default_for(MaskType::Ellipse) };
        apply_mask(&mut f, w, h, &m, &Effect::Blur { radius: 4 });
        assert_eq!(&f[0..3], &orig[0..3], "fora da máscara intacto");
        let c = (30 * w + 40) * 3;
        assert_ne!(&f[c..c + 3], &orig[c..c + 3], "centro desfocado");
    }

    #[test]
    fn inverted_pixelate_affects_background() {
        let (w, h) = (80, 60);
        let mut f = frame(w, h);
        let orig = f.clone();
        let m = MaskState { feather: 0.0, inverted: true, width: 0.3, height: 0.3, ..MaskState::default_for(MaskType::Rectangle) };
        apply_mask(&mut f, w, h, &m, &Effect::Pixelate { block: 8 });
        let c = (30 * w + 40) * 3;
        assert_eq!(&f[c..c + 3], &orig[c..c + 3]);
        assert_ne!(&f[3..6], &orig[3..6]);
    }
}
