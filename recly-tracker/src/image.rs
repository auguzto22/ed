//! Planos de imagem em ponto flutuante, pirâmide multiescala e canais de aparência.
//!
//! Cada quadro é lido uma única vez e convertido em quatro canais por nível de pirâmide:
//! luminância, magnitude do gradiente (robusta a mudanças de exposição) e dois canais de
//! cor oponente (distinguem alvos com a mesma textura mas cores diferentes).

use crate::par;

/// Número de canais de aparência usados pelo rastreador.
pub const CHANNELS: usize = 4;

/// Plano monocromático `w × h` com valores em ponto flutuante.
#[derive(Clone, Debug)]
pub struct Plane {
    pub w: usize,
    pub h: usize,
    pub data: Vec<f32>,
}

impl Plane {
    pub fn new(w: usize, h: usize) -> Self {
        Self { w, h, data: vec![0.0; w * h] }
    }

    #[inline]
    pub fn get(&self, x: isize, y: isize) -> f32 {
        let x = x.clamp(0, self.w as isize - 1) as usize;
        let y = y.clamp(0, self.h as isize - 1) as usize;
        self.data[y * self.w + x]
    }

    /// Amostragem bilinear com coordenadas de centro de pixel (o pixel `i` cobre `[i, i+1)`).
    #[inline]
    pub fn bilinear(&self, x: f32, y: f32) -> f32 {
        let fx = x - 0.5;
        let fy = y - 0.5;
        let x0 = fx.floor();
        let y0 = fy.floor();
        let ax = fx - x0;
        let ay = fy - y0;
        let (x0, y0) = (x0 as isize, y0 as isize);
        let a = self.get(x0, y0);
        let b = self.get(x0 + 1, y0);
        let c = self.get(x0, y0 + 1);
        let d = self.get(x0 + 1, y0 + 1);
        let top = a + (b - a) * ax;
        let bottom = c + (d - c) * ax;
        top + (bottom - top) * ay
    }

    /// Reduz pela metade com média 2×2 (anti-aliasing antes de subamostrar).
    pub fn downsample(&self) -> Plane {
        let w = (self.w / 2).max(1);
        let h = (self.h / 2).max(1);
        let mut out = Plane::new(w, h);
        par::rows_mut(&mut out.data, w, 4, |y, row| {
            let y0 = (2 * y).min(self.h - 1);
            let y1 = (2 * y + 1).min(self.h - 1);
            for (x, v) in row.iter_mut().enumerate() {
                let x0 = (2 * x).min(self.w - 1);
                let x1 = (2 * x + 1).min(self.w - 1);
                *v = 0.25
                    * (self.data[y0 * self.w + x0]
                        + self.data[y0 * self.w + x1]
                        + self.data[y1 * self.w + x0]
                        + self.data[y1 * self.w + x1]);
            }
        });
        out
    }

    /// Suavização binomial separável [1 2 1]/4: remove ruído de compressão antes do gradiente.
    pub fn smooth(&self) -> Plane {
        let (w, h) = (self.w, self.h);
        let mut tmp = Plane::new(w, h);
        par::rows_mut(&mut tmp.data, w, 3, |y, row| {
            let src = &self.data[y * w..(y + 1) * w];
            for x in 0..w {
                let l = src[x.saturating_sub(1)];
                let r = src[(x + 1).min(w - 1)];
                row[x] = 0.25 * l + 0.5 * src[x] + 0.25 * r;
            }
        });
        let mut out = Plane::new(w, h);
        par::rows_mut(&mut out.data, w, 4, |y, row| {
            let up = &tmp.data[y.saturating_sub(1) * w..][..w];
            let mid = &tmp.data[y * w..][..w];
            let down = &tmp.data[(y + 1).min(h - 1) * w..][..w];
            for x in 0..w {
                row[x] = 0.25 * up[x] + 0.5 * mid[x] + 0.25 * down[x];
            }
        });
        out
    }

    /// Magnitude do gradiente (Sobel). Compressa com raiz para não ser dominada por bordas duras.
    pub fn gradient_magnitude(&self) -> Plane {
        let (w, h) = (self.w, self.h);
        let mut out = Plane::new(w, h);
        par::rows_mut(&mut out.data, w, 4, |y, row| {
            let yi = y as isize;
            for (x, v) in row.iter_mut().enumerate() {
                let xi = x as isize;
                let gx = (self.get(xi + 1, yi - 1) + 2.0 * self.get(xi + 1, yi) + self.get(xi + 1, yi + 1))
                    - (self.get(xi - 1, yi - 1) + 2.0 * self.get(xi - 1, yi) + self.get(xi - 1, yi + 1));
                let gy = (self.get(xi - 1, yi + 1) + 2.0 * self.get(xi, yi + 1) + self.get(xi + 1, yi + 1))
                    - (self.get(xi - 1, yi - 1) + 2.0 * self.get(xi, yi - 1) + self.get(xi + 1, yi - 1));
                *v = (gx * gx + gy * gy).sqrt().sqrt();
            }
        });
        out
    }
}

/// Formato de pixel de entrada.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PixelFormat {
    Rgb24,
    /// Bytes R,G,B,A em memória (o mesmo layout de um `Bitmap` ARGB_8888 do Android).
    Rgba32,
}

impl PixelFormat {
    pub fn bytes_per_pixel(self) -> usize {
        match self {
            PixelFormat::Rgb24 => 3,
            PixelFormat::Rgba32 => 4,
        }
    }
}

/// Um nível da pirâmide: quatro canais com o mesmo tamanho.
#[derive(Clone, Debug)]
pub struct Level {
    pub channels: [Plane; CHANNELS],
    /// Fator entre este nível e o quadro original (1, 2, 4, …).
    pub factor: f32,
}

impl Level {
    pub fn w(&self) -> usize {
        self.channels[0].w
    }
    pub fn h(&self) -> usize {
        self.channels[0].h
    }
}

/// Características de um quadro, calculadas uma vez e compartilhadas por todos os alvos.
#[derive(Clone, Debug)]
pub struct FrameFeatures {
    pub width: usize,
    pub height: usize,
    pub levels: Vec<Level>,
}

impl FrameFeatures {
    /// Constrói a pirâmide a partir de pixels brutos. `stride` é o número de bytes por linha.
    pub fn from_pixels(pixels: &[u8], width: usize, height: usize, stride: usize, format: PixelFormat) -> Self {
        assert!(width > 0 && height > 0, "quadro vazio");
        let bpp = format.bytes_per_pixel();
        assert!(stride >= width * bpp, "stride menor que a linha");
        assert!(pixels.len() >= stride * (height - 1) + width * bpp, "buffer de pixels curto");

        let rows = par::map(height, width * 4, |y| {
            let row = &pixels[y * stride..];
            let mut l = vec![0.0f32; width];
            let mut a = vec![0.0f32; width];
            let mut b = vec![0.0f32; width];
            for x in 0..width {
                let p = &row[x * bpp..];
                let r = p[0] as f32 / 255.0;
                let g = p[1] as f32 / 255.0;
                let bl = p[2] as f32 / 255.0;
                l[x] = 0.2126 * r + 0.7152 * g + 0.0722 * bl;
                // Espaço de cor oponente (vermelho-verde, amarelo-azul).
                a[x] = r - g;
                b[x] = 0.5 * (r + g) - bl;
            }
            (l, a, b)
        });
        let mut luma = Plane::new(width, height);
        let mut ca = Plane::new(width, height);
        let mut cb = Plane::new(width, height);
        for (y, (l, a, b)) in rows.into_iter().enumerate() {
            luma.data[y * width..(y + 1) * width].copy_from_slice(&l);
            ca.data[y * width..(y + 1) * width].copy_from_slice(&a);
            cb.data[y * width..(y + 1) * width].copy_from_slice(&b);
        }

        Self::from_planes(luma.smooth(), ca.smooth(), cb.smooth())
    }

    /// Constrói a pirâmide a partir de luminância e cor já calculadas (útil em testes).
    pub fn from_planes(luma: Plane, ca: Plane, cb: Plane) -> Self {
        let (width, height) = (luma.w, luma.h);
        let mut levels = Vec::new();
        let grad = luma.gradient_magnitude();
        levels.push(Level { channels: [luma, grad, ca, cb], factor: 1.0 });
        while levels.len() < 7 {
            let last = levels.last().unwrap();
            if last.w() < 24 || last.h() < 24 {
                break;
            }
            let luma = last.channels[0].downsample();
            let grad = luma.gradient_magnitude();
            let ca = last.channels[2].downsample();
            let cb = last.channels[3].downsample();
            let factor = last.factor * 2.0;
            levels.push(Level { channels: [luma, grad, ca, cb], factor });
        }
        Self { width, height, levels }
    }

    /// Melhor nível para amostrar com `pixel_size` pixels do original por amostra:
    /// o mais grosso cujo fator não ultrapassa o tamanho da amostra (evita aliasing).
    pub fn level_for(&self, pixel_size: f32) -> &Level {
        let mut best = &self.levels[0];
        for level in &self.levels {
            if level.factor <= pixel_size.max(1.0) {
                best = level;
            }
        }
        best
    }
}

/// Imagem de busca: uma janela reamostrada de todos os canais numa grade regular, com imagens
/// integrais de soma e soma dos quadrados para calcular média e variância de qualquer janela em O(1).
pub struct SearchImage {
    pub w: usize,
    pub h: usize,
    pub channels: Vec<Vec<f32>>,
    sums: Vec<Vec<f64>>,
    sq_sums: Vec<Vec<f64>>,
}

impl SearchImage {
    /// Reamostra a região com canto superior esquerdo `(x0, y0)` (pixels do original),
    /// com `step` pixels do original por amostra.
    pub fn sample(features: &FrameFeatures, x0: f32, y0: f32, step: f32, w: usize, h: usize) -> Self {
        let level = features.level_for(step);
        let inv = 1.0 / level.factor;
        let channels: Vec<Vec<f32>> = (0..CHANNELS)
            .map(|c| {
                let plane = &level.channels[c];
                let mut out = vec![0.0f32; w * h];
                for y in 0..h {
                    let sy = (y0 + (y as f32 + 0.5) * step) * inv;
                    for x in 0..w {
                        let sx = (x0 + (x as f32 + 0.5) * step) * inv;
                        out[y * w + x] = plane.bilinear(sx, sy);
                    }
                }
                out
            })
            .collect();
        let mut sums = Vec::with_capacity(CHANNELS);
        let mut sq_sums = Vec::with_capacity(CHANNELS);
        for ch in &channels {
            let (s, q) = integral(ch, w, h);
            sums.push(s);
            sq_sums.push(q);
        }
        Self { w, h, channels, sums, sq_sums }
    }

    /// Soma e soma dos quadrados do canal `c` na janela `[x, x+tw) × [y, y+th)`.
    #[inline]
    pub fn window_stats(&self, c: usize, x: usize, y: usize, tw: usize, th: usize) -> (f64, f64) {
        let stride = self.w + 1;
        let s = &self.sums[c];
        let q = &self.sq_sums[c];
        let (a, b, cc, d) = (y * stride + x, y * stride + x + tw, (y + th) * stride + x, (y + th) * stride + x + tw);
        (s[d] - s[b] - s[cc] + s[a], q[d] - q[b] - q[cc] + q[a])
    }
}

fn integral(data: &[f32], w: usize, h: usize) -> (Vec<f64>, Vec<f64>) {
    let stride = w + 1;
    let mut s = vec![0.0f64; stride * (h + 1)];
    let mut q = vec![0.0f64; stride * (h + 1)];
    for y in 0..h {
        let mut row_s = 0.0f64;
        let mut row_q = 0.0f64;
        for x in 0..w {
            let v = data[y * w + x] as f64;
            row_s += v;
            row_q += v * v;
            s[(y + 1) * stride + x + 1] = s[y * stride + x + 1] + row_s;
            q[(y + 1) * stride + x + 1] = q[y * stride + x + 1] + row_q;
        }
    }
    (s, q)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bilinear_matches_pixel_centers() {
        let mut p = Plane::new(2, 1);
        p.data = vec![0.0, 1.0];
        assert!((p.bilinear(0.5, 0.5) - 0.0).abs() < 1e-6);
        assert!((p.bilinear(1.5, 0.5) - 1.0).abs() < 1e-6);
        assert!((p.bilinear(1.0, 0.5) - 0.5).abs() < 1e-6);
    }

    #[test]
    fn window_stats_match_brute_force() {
        let w = 7;
        let h = 5;
        let data: Vec<f32> = (0..w * h).map(|i| ((i * 37) % 11) as f32 * 0.1).collect();
        let (s, q) = integral(&data, w, h);
        let img = SearchImage { w, h, channels: vec![data.clone()], sums: vec![s], sq_sums: vec![q] };
        let (sum, sq) = img.window_stats(0, 2, 1, 3, 3);
        let mut bs = 0.0f64;
        let mut bq = 0.0f64;
        for y in 1..4 {
            for x in 2..5 {
                let v = data[y * w + x] as f64;
                bs += v;
                bq += v * v;
            }
        }
        assert!((sum - bs).abs() < 1e-9 && (sq - bq).abs() < 1e-9);
    }
}
