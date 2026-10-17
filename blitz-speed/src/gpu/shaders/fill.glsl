// Preenche o flow onde ele não é confiável (oclusões) com o movimento de vizinhos confiáveis e
// parecidos: um pixel do fundo que vai ser coberto pelo objeto não tem par no outro quadro,
// mas se parece com o fundo ao lado dele, que tem — então herda o movimento do fundo, não o do
// objeto. Pesos: confiança² × semelhança de luminância × distância.
uniform sampler2D uFlow;
uniform sampler2D uConfidence;
uniform sampler2D uLuma;
uniform float uChannel;     // 0: confiança em R (A→B), 1: em G (B→A)
uniform vec2 uSize;

float confidenceAt(vec2 uv) {
    vec4 c = texture2D(uConfidence, uv);
    return uChannel < 0.5 ? c.r : c.g;
}

void main() {
    vec2 texel = 1.0 / uSize;
    vec2 own = unpackFlow(texture2D(uFlow, vUv));
    float c0 = confidenceAt(vUv);
    if (c0 > 0.6) {
        gl_FragColor = packFlow(own);
        return;
    }
    float l0 = texture2D(uLuma, vUv).r;
    vec2 sum = own * c0 * c0;
    float weights = c0 * c0;
    for (int j = -4; j <= 4; j++) {
        for (int i = -4; i <= 4; i++) {
            vec2 offset = vec2(float(i), float(j)) * 2.0;
            vec2 uv = vUv + offset * texel;
            float c = confidenceAt(uv);
            float l = texture2D(uLuma, uv).r;
            float w = c * c * exp(-(l - l0) * (l - l0) / 0.003) * exp(-dot(offset, offset) / 72.0);
            sum += unpackFlow(texture2D(uFlow, uv)) * w;
            weights += w;
        }
    }
    gl_FragColor = packFlow(weights > 1e-4 ? sum / weights : own);
}
