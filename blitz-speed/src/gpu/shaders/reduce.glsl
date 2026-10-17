// Média 4×4 da área do texel de destino (para ler estatísticas pequenas na CPU).
uniform sampler2D uSource;
uniform vec2 uFootprint;

void main() {
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) + 0.5) * 0.25 - 0.5;
            sum += texture2D(uSource, vUv + o * uFootprint);
        }
    }
    gl_FragColor = sum / 16.0;
}
