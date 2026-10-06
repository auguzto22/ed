// Motion blur driven by the real movement of the layer.
//
// p_dx / p_dy are the pixel displacement the layer covered during the exposure; p_amount
// scales it; p_samples widens the trail. The tap count is fixed so the loop stays valid on
// every GLSL ES 1.0 driver, and the samples parameter stretches the trail instead.
vec4 reclyEffect(vec2 uv) {
    const int TAPS = 8;
    vec2 direction = vec2(p_dx, p_dy) * p_amount;
    vec2 step = direction * (0.25 + p_samples * 0.15) / uResolution;

    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int i = 0; i < TAPS; i++) {
        float t = (float(i) + 0.5) / float(TAPS) - 0.5;
        // Triangular kernel: the middle of the exposure stays sharp, the edges smear.
        float w = 1.0 - abs(t) * 2.0;
        sum += texture2D(uTexSampler, uv + step * t) * w;
        weight += w;
    }
    return sum / max(weight, 0.0001);
}
