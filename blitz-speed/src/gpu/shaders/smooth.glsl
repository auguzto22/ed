// Suavização bilateral do flow guiada pela luminância: remove ruído sem atravessar bordas.
uniform sampler2D uFlow;
uniform sampler2D uLuma;
uniform vec2 uSize;

void main() {
    vec2 texel = 1.0 / uSize;
    float lc = texture2D(uLuma, vUv).r;
    vec2 fc = unpackFlow(texture2D(uFlow, vUv));
    vec2 sum = vec2(0.0);
    float weights = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2 uv = vUv + vec2(float(i), float(j)) * texel;
            vec2 f = unpackFlow(texture2D(uFlow, uv));
            float l = texture2D(uLuma, uv).r;
            vec2 df = f - fc;
            float w = exp(-float(i * i + j * j) / 4.0) * exp(-(l - lc) * (l - lc) / 0.004) * exp(-dot(df, df) / 6.0);
            sum += f * w;
            weights += w;
        }
    }
    gl_FragColor = packFlow(sum / weights);
}
