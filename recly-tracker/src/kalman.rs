//! Filtro de Kalman de velocidade constante e suavizador Rauch–Tung–Striebel.
//!
//! O estado do alvo (x, y, log da escala) é filtrado por três filtros independentes de
//! posição+velocidade. Online, o filtro prevê onde procurar e absorve medições ruidosas com
//! peso proporcional à confiança. Offline, o suavizador RTS usa o futuro para corrigir o
//! passado: elimina tremor e preenche oclusões com uma trajetória plausível.

/// Estado (posição, velocidade) com covariância 2×2.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct State {
    pub x: [f64; 2],
    pub p: [[f64; 2]; 2],
}

impl State {
    pub fn new(position: f64, position_var: f64, velocity_var: f64) -> Self {
        Self { x: [position, 0.0], p: [[position_var, 0.0], [0.0, velocity_var]] }
    }

    pub fn position(&self) -> f64 {
        self.x[0]
    }

    pub fn velocity(&self) -> f64 {
        self.x[1]
    }

    /// Desvio-padrão da posição.
    pub fn position_sigma(&self) -> f64 {
        self.p[0][0].max(0.0).sqrt()
    }

    /// Previsão com aceleração branca de intensidade `q` durante `dt`.
    pub fn predict(&self, dt: f64, q: f64) -> Self {
        let [p, v] = self.x;
        let x = [p + v * dt, v];
        let pm = self.p;
        // F P Fᵀ com F = [[1, dt], [0, 1]]
        let a = pm[0][0] + dt * (pm[1][0] + pm[0][1]) + dt * dt * pm[1][1];
        let b = pm[0][1] + dt * pm[1][1];
        let c = pm[1][0] + dt * pm[1][1];
        let d = pm[1][1];
        let dt2 = dt * dt;
        let q00 = q * dt2 * dt / 3.0;
        let q01 = q * dt2 / 2.0;
        let q11 = q * dt;
        Self { x, p: [[a + q00, b + q01], [c + q01, d + q11]] }
    }

    /// Atualização com medição de posição `z` e variância `r`.
    pub fn update(&self, z: f64, r: f64) -> Self {
        let s = self.p[0][0] + r;
        let k0 = self.p[0][0] / s;
        let k1 = self.p[1][0] / s;
        let innovation = z - self.x[0];
        let x = [self.x[0] + k0 * innovation, self.x[1] + k1 * innovation];
        let p = [
            [(1.0 - k0) * self.p[0][0], (1.0 - k0) * self.p[0][1]],
            [self.p[1][0] - k1 * self.p[0][0], self.p[1][1] - k1 * self.p[0][1]],
        ];
        Self { x, p }
    }

    /// Distância de Mahalanobis² de uma medição em relação à previsão.
    pub fn mahalanobis2(&self, z: f64, r: f64) -> f64 {
        let d = z - self.x[0];
        d * d / (self.p[0][0] + r)
    }

    pub fn damp_velocity(&mut self, factor: f64) {
        self.x[1] *= factor;
    }
}

/// Um passo registrado para o suavizador.
#[derive(Clone, Copy, Debug)]
pub struct Step {
    pub predicted: State,
    pub filtered: State,
    pub dt: f64,
}

/// Suavizador RTS sobre um histórico de passos de um filtro 1D.
/// Retorna os estados suavizados na mesma ordem.
pub fn rts_smooth(steps: &[Step]) -> Vec<State> {
    let n = steps.len();
    if n == 0 {
        return Vec::new();
    }
    let mut out: Vec<State> = steps.iter().map(|s| s.filtered).collect();
    for k in (0..n - 1).rev() {
        let f = steps[k].filtered;
        let pred = steps[k + 1].predicted;
        let dt = steps[k + 1].dt;
        // C = P_f Fᵀ P_pred⁻¹
        let pf = f.p;
        let pft = [
            [pf[0][0] + dt * pf[0][1], pf[0][1]],
            [pf[1][0] + dt * pf[1][1], pf[1][1]],
        ];
        let pp = pred.p;
        let det = pp[0][0] * pp[1][1] - pp[0][1] * pp[1][0];
        if det.abs() < 1e-18 {
            continue;
        }
        let inv = [[pp[1][1] / det, -pp[0][1] / det], [-pp[1][0] / det, pp[0][0] / det]];
        let c = mul(pft, inv);
        let next = out[k + 1];
        let dx = [next.x[0] - pred.x[0], next.x[1] - pred.x[1]];
        let x = [
            f.x[0] + c[0][0] * dx[0] + c[0][1] * dx[1],
            f.x[1] + c[1][0] * dx[0] + c[1][1] * dx[1],
        ];
        let dp = [
            [next.p[0][0] - pp[0][0], next.p[0][1] - pp[0][1]],
            [next.p[1][0] - pp[1][0], next.p[1][1] - pp[1][1]],
        ];
        let cdp = mul(c, dp);
        let ct = [[c[0][0], c[1][0]], [c[0][1], c[1][1]]];
        let corr = mul(cdp, ct);
        let p = [
            [pf[0][0] + corr[0][0], pf[0][1] + corr[0][1]],
            [pf[1][0] + corr[1][0], pf[1][1] + corr[1][1]],
        ];
        out[k] = State { x, p };
    }
    out
}

fn mul(a: [[f64; 2]; 2], b: [[f64; 2]; 2]) -> [[f64; 2]; 2] {
    [
        [a[0][0] * b[0][0] + a[0][1] * b[1][0], a[0][0] * b[0][1] + a[0][1] * b[1][1]],
        [a[1][0] * b[0][0] + a[1][1] * b[1][0], a[1][0] * b[0][1] + a[1][1] * b[1][1]],
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn filter_converges_on_constant_velocity() {
        let mut s = State::new(0.0, 100.0, 100.0);
        for k in 1..60 {
            s = s.predict(1.0, 0.01).update(3.0 * k as f64, 1.0);
        }
        assert!((s.velocity() - 3.0).abs() < 0.1, "v = {}", s.velocity());
    }

    #[test]
    fn smoother_reduces_noise() {
        let truth: Vec<f64> = (0..200).map(|k| 2.0 * k as f64).collect();
        let noise = |k: usize| (((k * 7919) % 101) as f64 / 101.0 - 0.5) * 6.0;
        let mut s = State::new(0.0, 10.0, 10.0);
        let mut steps = Vec::new();
        for (k, t) in truth.iter().enumerate() {
            let pred = if k == 0 { s } else { s.predict(1.0, 0.05) };
            s = pred.update(t + noise(k), 3.0);
            steps.push(Step { predicted: pred, filtered: s, dt: 1.0 });
        }
        let filtered_err: f64 = steps.iter().zip(&truth).map(|(s, t)| (s.filtered.x[0] - t).abs()).sum();
        let smoothed = rts_smooth(&steps);
        let smoothed_err: f64 = smoothed.iter().zip(&truth).map(|(s, t)| (s.x[0] - t).abs()).sum();
        assert!(smoothed_err < filtered_err * 0.8, "{smoothed_err} vs {filtered_err}");
    }
}
