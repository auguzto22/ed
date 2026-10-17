// Reduz um quadro colorido à luminância na resolução do flow, com média 4×4 da área do texel
// de destino (evita serrilhado ao reduzir de 1080p para ~200 px).
uniform sampler2D uSource;
uniform vec2 uFootprint;

void main() {
    float sum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = (vec2(float(i), float(j)) + 0.5) * 0.25 - 0.5;
            sum += dot(texture2D(uSource, vUv + o * uFootprint).rgb, vec3(0.299, 0.587, 0.114));
        }
    }
    gl_FragColor = vec4(vec3(sum / 16.0), 1.0);
}
