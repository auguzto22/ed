// Mistura de quadros (frame blending) com controle de fantasmas.
//
// Mistura A e B pelo tempo. Onde os dois diferem muito (algo se moveu mais que o próprio
// tamanho) a mistura mostraria o objeto duas vezes; `uGhostControl` encurta a transição nesses
// pixels, puxando-os para o quadro mais próximo. 0 = mistura pura, 1 = máxima supressão.
uniform sampler2D uFrameA;
uniform sampler2D uFrameB;
uniform float uT;
uniform float uGhostControl;

void main() {
    vec4 a = texture2D(uFrameA, vUv);
    vec4 b = texture2D(uFrameB, vUv);
    float difference = dot(abs(a.rgb - b.rgb), vec3(0.3333));
    float snap = smoothstep(0.12, 0.4, difference) * uGhostControl;
    float t = mix(uT, step(0.5, uT), snap);
    gl_FragColor = mix(a, b, t);
}
