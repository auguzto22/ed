// Uma iteração de Lucas–Kanade num nível da pirâmide.
//
// Para cada pixel, numa janela 5×5 com peso gaussiano: gradientes de A (diferença central sobre
// um recorte 7×7) e diferença temporal B(x + d + u) − A(x + d) com o flow atual u. Resolve o
// sistema 2×2 [ΣIx² ΣIxIy; ΣIxIy ΣIy²]·Δ = −[ΣIx·It; ΣIy·It] e soma Δ (limitado) ao flow.
// O flow está em pixels deste nível.
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uFlow;
uniform sampler2D uCoarse;
uniform float uInit;        // 0: zero, 1: uFlow, 2: nível mais grosso ampliado
uniform vec2 uSize;
uniform vec2 uCoarseSize;

void main() {
    vec2 texel = 1.0 / uSize;
    vec2 flow = vec2(0.0);
    if (uInit > 1.5) {
        flow = flowAt(uCoarse, vUv, uCoarseSize) * (uSize / uCoarseSize);
    } else if (uInit > 0.5) {
        flow = unpackFlow(texture2D(uFlow, vUv));
    }
    float a[49];
    for (int j = 0; j < 7; j++) {
        for (int i = 0; i < 7; i++) {
            a[j * 7 + i] = texture2D(uA, vUv + vec2(float(i - 3), float(j - 3)) * texel).r;
        }
    }
    float gxx = 0.0;
    float gxy = 0.0;
    float gyy = 0.0;
    float bx = 0.0;
    float by = 0.0;
    for (int j = 1; j < 6; j++) {
        for (int i = 1; i < 6; i++) {
            float ix = 0.5 * (a[j * 7 + i + 1] - a[j * 7 + i - 1]);
            float iy = 0.5 * (a[(j + 1) * 7 + i] - a[(j - 1) * 7 + i]);
            vec2 offset = vec2(float(i - 3), float(j - 3));
            float it = texture2D(uB, vUv + (offset + flow) * texel).r - a[j * 7 + i];
            float w = exp(-dot(offset, offset) / 4.5);
            gxx += w * ix * ix;
            gxy += w * ix * iy;
            gyy += w * iy * iy;
            bx += w * ix * it;
            by += w * iy * it;
        }
    }
    // Regularização: regiões lisas (sem gradiente) ficam com o flow do nível grosso.
    gxx += 5e-4;
    gyy += 5e-4;
    float det = gxx * gyy - gxy * gxy;
    vec2 delta = -vec2(gyy * bx - gxy * by, gxx * by - gxy * bx) / det;
    float len = length(delta);
    if (len > 1.5) {
        delta *= 1.5 / len;
    }
    gl_FragColor = packFlow(flow + delta);
}
