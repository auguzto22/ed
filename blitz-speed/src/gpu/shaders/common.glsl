// Prelúdio de todos os fragment shaders do motor de movimento (GLSL ES 1.00).
//
// O flow é guardado em texturas RGBA8 comuns (as únicas garantidas no GLES 2): cada componente
// ocupa dois canais (16 bits, ±128 px com passo de 1/256 px). Por isso essas texturas usam
// filtro NEAREST e a interpolação bilinear é feita aqui, depois de desempacotar.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUv;
const float FLOW_RANGE = 128.0;

vec4 packFlow(vec2 flow) {
    vec2 u = clamp(flow / (2.0 * FLOW_RANGE) + 0.5, 0.0, 1.0);
    vec2 v = floor(u * 65535.0 + 0.5);
    vec2 hi = floor(v / 256.0);
    vec2 lo = v - hi * 256.0;
    return vec4(hi.x, lo.x, hi.y, lo.y) / 255.0;
}

vec2 unpackFlow(vec4 c) {
    vec4 b = floor(c * 255.0 + 0.5);
    vec2 v = vec2(b.x * 256.0 + b.y, b.z * 256.0 + b.w);
    return (v / 65535.0 - 0.5) * 2.0 * FLOW_RANGE;
}

// Bilinear de uma textura de flow empacotada (NEAREST) de tamanho `size`, em `uv`.
vec2 flowAt(sampler2D flow, vec2 uv, vec2 size) {
    vec2 p = uv * size - 0.5;
    vec2 f = fract(p);
    vec2 texel = 1.0 / size;
    vec2 base = (floor(p) + 0.5) * texel;
    vec2 a = unpackFlow(texture2D(flow, base));
    vec2 b = unpackFlow(texture2D(flow, base + vec2(texel.x, 0.0)));
    vec2 c = unpackFlow(texture2D(flow, base + vec2(0.0, texel.y)));
    vec2 d = unpackFlow(texture2D(flow, base + texel));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
