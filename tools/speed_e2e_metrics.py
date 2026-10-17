#!/usr/bin/env python3
"""Métricas e folhas de contato do ensaio de ponta a ponta (tools/speed-e2e.sh).

Modo padrão: compara a saída de cada caso de câmera lenta 0,25× com os quadros reais a 120 fps.
O quadro de saída k corresponde ao quadro real k; os múltiplos de 4 são originais (iguais em
todos os modos), os demais foram criados pelo motor. Mede PSNR e erro médio nesses quadros
criados e grava uma folha comparando real × repetir × misturar × optical flow.

--sheets: folhas de contato de casos sem verdade (rampa, avanço com acumulação).
"""
import sys
import numpy as np
from PIL import Image


def frames(path, w, h):
    data = np.fromfile(path, dtype=np.uint8)
    return data.reshape(-1, h, w, 4)[..., :3]


def psnr(a, b):
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return 99.0 if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)


def sheet(rows, path, scale=1):
    """rows: lista de listas de imagens HxWx3 (mesmo tamanho)."""
    h, w = rows[0][0].shape[:2]
    cols = max(len(r) for r in rows)
    pad = 4
    canvas = np.zeros((len(rows) * (h + pad), cols * (w + pad), 3), np.uint8)
    for i, row in enumerate(rows):
        for j, img in enumerate(row):
            canvas[i * (h + pad):i * (h + pad) + h, j * (w + pad):j * (w + pad) + w] = img
    image = Image.fromarray(canvas)
    if scale != 1:
        image = image.resize((int(image.width * scale), int(image.height * scale)))
    image.save(path)


def exposure_truth(truth, k, speed, shutter, out_fps=30.0, truth_fps=120.0):
    """O que uma câmera de verdade gravaria: média de todos os quadros reais (120 fps) que caem
    na janela do obturador do quadro de saída k, a `speed` constante."""
    center = k / out_fps * speed * truth_fps
    half = shutter / 360.0 / out_fps / 2.0 * speed * truth_fps
    first, last = int(np.ceil(center - half)), int(np.ceil(center + half))
    picks = [min(max(i, 0), len(truth) - 1) for i in range(first, last)]
    return np.mean([truth[i].astype(np.float64) for i in picks], axis=0).round().astype(np.uint8)


def main():
    args = sys.argv[1:]
    if args and args[0] == "--exposure":
        work, w, h, speed, shutter, cases = args[1], int(args[2]), int(args[3]), float(args[4]), float(args[5]), args[6:]
        truth = frames(f"{work}/truth120.rgba", w, h)
        outputs = {case: frames(f"{work}/{case}/output.rgba", w, h) for case in cases}
        n = min(len(o) for o in outputs.values())
        # Ignora os quadros das pontas, onde a janela sai do arquivo.
        ks = range(1, n - 1)
        refs = {k: exposure_truth(truth, k, speed, shutter) for k in ks}
        for case, out in outputs.items():
            p = np.array([psnr(out[k], refs[k]) for k in ks])
            print(f"  [{case}] contra a exposição real ({shutter:.0f}° a {speed}×, média dos quadros a 120 fps): PSNR médio {p.mean():.2f} dB (pior {p.min():.2f})")
        k = n // 2
        sheet([[refs[k]] + [outputs[c][k] for c in cases]], f"{work}/exposure.png", 0.8)
        print(f"  folha (exposição real | {' | '.join(cases)}), quadro {k}: {work}/exposure.png")
        return
    sheets = args and args[0] == "--sheets"
    if sheets:
        args = args[1:]
    work, w, h, cases = args[0], int(args[1]), int(args[2]), args[3:]
    if sheets:
        for case in cases:
            out = frames(f"{work}/{case}/output.rgba", w, h)
            picks = np.linspace(0, len(out) - 1, 8).astype(int)
            sheet([[out[i] for i in picks[:4]], [out[i] for i in picks[4:]]], f"{work}/{case}/sheet.png", 0.6)
            print(f"  [{case}] {len(out)} quadros; folha: {work}/{case}/sheet.png (quadros {list(picks)})")
        return
    truth = frames(f"{work}/truth120.rgba", w, h)
    outputs = {case: frames(f"{work}/{case}/output.rgba", w, h) for case in cases}
    n = min(len(truth), *(len(o) for o in outputs.values()))
    created = [k for k in range(n) if k % 4 != 0]
    originals = [k for k in range(n) if k % 4 == 0]
    for case, out in outputs.items():
        p = np.array([psnr(out[k], truth[k]) for k in created])
        mae = np.array([np.mean(np.abs(out[k].astype(int) - truth[k].astype(int))) for k in created])
        exact = np.mean([psnr(out[k], truth[k]) for k in originals])
        by_phase = {ph: np.mean([psnr(out[k], truth[k]) for k in created if k % 4 == ph]) for ph in (1, 2, 3)}
        print(f"  [{case}] quadros criados: PSNR médio {p.mean():.2f} dB (pior {p.min():.2f}), erro médio {mae.mean():.2f}/255; "
              f"por fase 0,25/0,5/0,75: {by_phase[1]:.2f}/{by_phase[2]:.2f}/{by_phase[3]:.2f} dB; originais {exact:.1f} dB")
    k = created[len(created) // 2] // 4 * 4 + 2  # um quadro no meio do caminho (fase 0,5)
    rows = [[truth[k]] + [outputs[c][k] for c in cases]]
    # Recorte ampliado em volta do objeto que se move.
    diff = np.abs(truth[k].astype(int) - truth[k - 2].astype(int)).sum(axis=2)
    ys, xs = np.nonzero(diff > 60)
    if len(xs):
        cx, cy = int(np.median(xs)), int(np.median(ys))
        x0, y0 = max(0, min(w - 160, cx - 80)), max(0, min(h - 120, cy - 60))
        crops = [np.kron(img[y0:y0 + 120, x0:x0 + 160], np.ones((2, 2, 1), np.uint8))[:h, :w] for img in rows[0]]
        crops = [np.pad(c, ((0, h - c.shape[0]), (0, w - c.shape[1]), (0, 0))) for c in crops]
        rows.append(crops)
    sheet(rows, f"{work}/compare.png", 0.6)
    print(f"  folha (real | {' | '.join(cases)}), quadro {k}: {work}/compare.png")


if __name__ == "__main__":
    main()
