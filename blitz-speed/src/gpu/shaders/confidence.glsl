// Confiança do flow por pixel, nas duas direções.
//
// Consistência ida e volta: um pixel de A levado a B pelo flow A→B e trazido de volta pelo flow
// B→A deve voltar ao lugar; onde não volta há oclusão (conteúdo que só existe num dos quadros)
// ou estimativa errada. Soma-se o erro fotométrico depois de alinhar. R = confiança de A,
// G = confiança de B.
uniform sampler2D uForward;
uniform sampler2D uBackward;
uniform sampler2D uLumaA;
uniform sampler2D uLumaB;
uniform vec2 uSize;

void main() {
    vec2 texel = 1.0 / uSize;
    vec2 fab = unpackFlow(texture2D(uForward, vUv));
    vec2 fba = unpackFlow(texture2D(uBackward, vUv));
    vec2 back = flowAt(uBackward, vUv + fab * texel, uSize);
    vec2 forth = flowAt(uForward, vUv + fba * texel, uSize);
    float ea = length(fab + back);
    float eb = length(fba + forth);
    float sa = 0.6 + 0.08 * length(fab);
    float sb = 0.6 + 0.08 * length(fba);
    float ca = exp(-ea * ea / (sa * sa));
    float cb = exp(-eb * eb / (sb * sb));
    float pa = abs(texture2D(uLumaA, vUv).r - texture2D(uLumaB, vUv + fab * texel).r);
    float pb = abs(texture2D(uLumaB, vUv).r - texture2D(uLumaA, vUv + fba * texel).r);
    ca *= 1.0 - smoothstep(0.05, 0.2, pa);
    cb *= 1.0 - smoothstep(0.05, 0.2, pb);
    gl_FragColor = vec4(ca, cb, 0.0, 1.0);
}
