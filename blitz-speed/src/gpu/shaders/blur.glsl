// Motion blur por vetores de movimento (reconstrução por amostras ao longo do movimento).
//
// A exposição é dividida em `uSamples` instantes, centrados no instante do quadro. Em cada
// instante a cor do pixel x é: a do conteúdo de y se o rastro de y alcança x e y é mais rápido
// (um objeto passando por cima do fundo), a de y se o rastro do próprio x alcança y (o objeto
// borrando ao longo de si mesmo), e a de x caso contrário. A média dos instantes é a fração
// real de tempo em que cada coisa cobriu o pixel: o fundo parado continua nítido e só o que
// se move é arrastado, na própria direção. `uScale` = exposição ÷ intervalo entre os quadros
// do par (já inclui a velocidade do clipe e a intensidade).
uniform sampler2D uColor;
uniform sampler2D uFlow;
uniform sampler2D uNeighborMax;
uniform vec2 uFlowSize;
uniform vec2 uOutSize;
uniform float uScale;
uniform float uSamples;

const int MAX_SAMPLES = 32;

vec2 velocity(sampler2D tex, vec2 uv) {
    return flowAt(tex, uv, uFlowSize) / uFlowSize * uScale;
}

// Nas amostras basta a velocidade do texel mais próximo (1 leitura em vez de 4).
vec2 velocityNearest(vec2 uv) {
    return unpackFlow(texture2D(uFlow, uv)) / uFlowSize * uScale;
}

void main() {
    vec4 center = texture2D(uColor, vUv);
    vec2 vx = velocity(uFlow, vUv);
    vec2 vn = velocity(uNeighborMax, vUv);
    float lx = length(vx * uOutSize);
    float ln = length(vn * uOutSize);
    if (ln < 0.75) {
        gl_FragColor = center;
        return;
    }
    vec2 direction = lx >= 0.5 * ln ? vx : vn;
    float span = length(direction * uOutSize);
    vec4 sum = vec4(0.0);
    float count = 0.0;
    for (int i = 0; i < MAX_SAMPLES; i++) {
        if (float(i) >= uSamples) {
            break;
        }
        float s = (float(i) + 0.5) / uSamples - 0.5;
        vec2 y = vUv + s * direction;
        float dist = abs(s) * span;
        float ly = length(velocityNearest(y) * uOutSize);
        vec4 cy = texture2D(uColor, y);
        float covers = clamp(0.5 * ly - dist + 0.5, 0.0, 1.0) * clamp(ly - lx, 0.0, 1.0);
        float own = clamp(0.5 * lx - dist + 0.5, 0.0, 1.0);
        sum += mix(center, cy, max(covers, own));
        count += 1.0;
    }
    gl_FragColor = sum / count;
}
