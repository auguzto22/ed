//! Paralelismo mínimo sobre `std::thread::scope` (sem dependências externas).
//! Trabalhos pequenos rodam na thread atual para não pagar o custo de criar threads.

use std::sync::OnceLock;

pub fn threads() -> usize {
    static N: OnceLock<usize> = OnceLock::new();
    *N.get_or_init(|| {
        std::env::var("RECLY_THREADS")
            .ok()
            .and_then(|v| v.parse().ok())
            .filter(|n: &usize| *n > 0)
            .unwrap_or_else(|| std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1))
            .min(64)
    })
}

/// Executa `f(índice_da_linha, linha)` para cada linha de `data` (linhas de `row_len` itens).
/// `cost` é uma estimativa do custo por item; abaixo de ~50 mil unidades roda em série.
pub fn rows_mut<T: Send>(data: &mut [T], row_len: usize, cost: usize, f: impl Fn(usize, &mut [T]) + Sync) {
    if row_len == 0 || data.is_empty() {
        return;
    }
    let rows = data.len() / row_len;
    let n = threads();
    if n <= 1 || rows < 2 || data.len() * cost.max(1) < 50_000 {
        for (y, row) in data.chunks_mut(row_len).enumerate() {
            f(y, row);
        }
        return;
    }
    let per = rows.div_ceil(n);
    std::thread::scope(|s| {
        for (k, block) in data.chunks_mut(per * row_len).enumerate() {
            let f = &f;
            s.spawn(move || {
                for (i, row) in block.chunks_mut(row_len).enumerate() {
                    f(k * per + i, row);
                }
            });
        }
    });
}

/// `(0..n).map(f).collect()` em paralelo, preservando a ordem.
pub fn map<R: Send>(n: usize, cost: usize, f: impl Fn(usize) -> R + Sync) -> Vec<R> {
    let t = threads();
    if t <= 1 || n < 2 || n * cost.max(1) < 50_000 {
        return (0..n).map(f).collect();
    }
    let per = n.div_ceil(t);
    let parts: Vec<Vec<R>> = std::thread::scope(|s| {
        let handles: Vec<_> = (0..n)
            .step_by(per)
            .map(|start| {
                let f = &f;
                s.spawn(move || (start..(start + per).min(n)).map(f).collect::<Vec<R>>())
            })
            .collect();
        handles.into_iter().map(|h| h.join().expect("thread de trabalho falhou")).collect()
    });
    parts.into_iter().flatten().collect()
}

/// Aplica `f` a cada item em paralelo (um item por thread no máximo `threads()` por vez).
pub fn each_mut<T: Send>(items: &mut [T], f: impl Fn(&mut T) + Sync) {
    let t = threads();
    if t <= 1 || items.len() < 2 {
        items.iter_mut().for_each(f);
        return;
    }
    let per = items.len().div_ceil(t);
    std::thread::scope(|s| {
        for block in items.chunks_mut(per) {
            let f = &f;
            s.spawn(move || block.iter_mut().for_each(f));
        }
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn map_preserves_order() {
        let v = super::map(10_000, 100, |i| i * 2);
        assert!(v.iter().enumerate().all(|(i, x)| *x == i * 2));
    }

    #[test]
    fn rows_cover_everything() {
        let mut data = vec![0usize; 1000 * 300];
        super::rows_mut(&mut data, 1000, 10, |y, row| row.iter_mut().for_each(|v| *v = y + 1));
        assert!(data.chunks(1000).enumerate().all(|(y, r)| r.iter().all(|v| *v == y + 1)));
    }
}
