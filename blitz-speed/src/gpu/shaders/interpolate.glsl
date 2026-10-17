// Quadro intermediário por optical flow (warping para trás com busca de ponto fixo).
//
// Para o pixel x no instante t procura-se, em A, o ponto p cujo movimento o leva a x:
// p + t·F(A→B)(p) = x. A busca começa em x − t·F(x) e repete p ← x − t·F(p); o mesmo em B com
// (1 − t) e F(B→A). Onde um dos quadros não tem nenhum ponto que chegue a x (região descoberta
// ou que vai ser coberta pelo objeto) a equação não fecha e esse quadro perde o peso: é isso que
// evita arrastar o fundo junto com o objeto nas bordas do movimento. Os flows já vêm com as
// oclusões preenchidas pelo movimento do fundo vizinho (fill.glsl).
//
// Pesos: tempo × fechamento × (0,25 + 0,75 × confiança ida e volta). Onde nenhum candidato
// fecha, o pixel vira uma mistura simples dos dois quadros — uma dissolução curta e suave em
// vez de um objeto que salta de lugar.
uniform sampler2D uFrameA;
uniform sampler2D uFrameB;
uniform sampler2D uForward;
uniform sampler2D uBackward;
uniform sampler2D uConfidence;
uniform vec2 uFlowSize;
uniform float uT;

vec2 forwardAt(vec2 uv) { return flowAt(uForward, uv, uFlowSize) / uFlowSize; }
vec2 backwardAt(vec2 uv) { return flowAt(uBackward, uv, uFlowSize) / uFlowSize; }

void main() {
    float s = 1.0 - uT;
    vec2 pa = vUv - uT * forwardAt(vUv);
    pa = vUv - uT * forwardAt(pa);
    pa = vUv - uT * forwardAt(pa);
    vec2 pb = vUv - s * backwardAt(vUv);
    pb = vUv - s * backwardAt(pb);
    pb = vUv - s * backwardAt(pb);
    vec2 fa = forwardAt(pa);
    vec2 fb = backwardAt(pb);
    float ea = length((pa + uT * fa - vUv) * uFlowSize);
    float eb = length((pb + s * fb - vUv) * uFlowSize);
    float sa = 0.35 + 0.05 * length(fa * uFlowSize);
    float sb = 0.35 + 0.05 * length(fb * uFlowSize);
    float closeA = exp(-ea * ea / (sa * sa));
    float closeB = exp(-eb * eb / (sb * sb));
    float ka = closeA * (0.25 + 0.75 * texture2D(uConfidence, pa).r);
    float kb = closeB * (0.25 + 0.75 * texture2D(uConfidence, pb).g);
    vec4 ca = texture2D(uFrameA, pa);
    vec4 cb = texture2D(uFrameB, pb);
    float wa = s * (ka + 0.001);
    float wb = uT * (kb + 0.001);
    vec4 warped = (ca * wa + cb * wb) / (wa + wb);
    vec4 dissolve = mix(texture2D(uFrameA, vUv), texture2D(uFrameB, vUv), uT);
    float trust = smoothstep(0.05, 0.3, max(ka, kb));
    gl_FragColor = mix(dissolve, warped, trust);
}
