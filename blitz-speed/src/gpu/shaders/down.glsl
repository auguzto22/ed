// Próximo nível da pirâmide: média 2×2 com quatro leituras bilineares (filtro tenda 4×4).
uniform sampler2D uSource;
uniform vec2 uSourceTexel;

void main() {
    float s = texture2D(uSource, vUv + vec2(-0.5, -0.5) * uSourceTexel).r
        + texture2D(uSource, vUv + vec2(0.5, -0.5) * uSourceTexel).r
        + texture2D(uSource, vUv + vec2(-0.5, 0.5) * uSourceTexel).r
        + texture2D(uSource, vUv + vec2(0.5, 0.5) * uSourceTexel).r;
    gl_FragColor = vec4(vec3(s * 0.25), 1.0);
}
