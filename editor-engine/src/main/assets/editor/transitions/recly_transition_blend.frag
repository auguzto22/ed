// Dissolves and dips. Colours are mixed in approximately linear light, like a film dissolve, so
// mid-tones do not sag and a dip reads as exposure going down instead of a grey veil.

vec3 toLinear(vec3 c) { return c * c; }
vec3 toDisplay(vec3 c) { return sqrt(max(c, 0.0)); }

vec4 linearMix(vec4 a, vec4 b, float t) {
    return vec4(toDisplay(mix(toLinear(a.rgb), toLinear(b.rgb), t)), mix(a.a, b.a, t));
}

vec3 softClip(vec3 x, float knee) {
    vec3 over = max(x - knee, 0.0);
    return min(x, vec3(knee)) + (1.0 - knee) * (1.0 - exp(-over / (1.0 - knee)));
}

// Ramp towards a solid tone. Dark tones are reached in linear light, which reads as the exposure
// going down; light tones are mixed on screen values so colours wash out evenly without shifting.
vec4 dipTo(vec4 c, vec3 tone, float amount) {
    float k = clamp(amount, 0.0, 1.0);
    float s = k * k * (3.0 - 2.0 * k);
    vec3 rgb = dot(tone, vec3(0.333)) > 0.5
        ? mix(c.rgb, tone, s)
        : toDisplay(mix(toLinear(c.rgb), toLinear(tone), s));
    return vec4(rgb, mix(c.a, 1.0, k));
}

// Fades out, optionally holds the tone for [hold] of the duration, then fades in.
vec4 dipThrough(vec4 a, vec4 b, vec3 tone, float t, float hold) {
    float edge = 0.5 - hold * 0.5;
    if (t < edge) return dipTo(a, tone, t / edge);
    if (t > 1.0 - edge) return dipTo(b, tone, (1.0 - t) / edge);
    return vec4(tone, 1.0);
}

vec4 reclyTransition(vec2 uv, float progress) {
    vec4 a = texture2D(uTexA, uv);
    vec4 b = texture2D(uTexB, uv);
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);

    if (mode < 0.5) {
        // Cross dissolve. Softness shapes the curve so both clips spend less time half-visible.
        float s = clamp(p_softness, 0.0, 1.0);
        return mix(a, b, mix(t, t * t * (3.0 - 2.0 * t), s));
    }
    if (mode < 1.5) return dipThrough(a, b, vec3(0.0), t, 0.0);           // dip to black
    if (mode < 2.5) return dipThrough(a, b, vec3(1.0), t, 0.0);           // dip to white
    if (mode < 3.5) return dipThrough(a, b, vec3(0.0), t, 0.16);          // fade through black
    if (mode < 4.5) return dipThrough(a, b, vec3(1.0), t, 0.16);          // fade through white
    if (mode < 5.5) return linearMix(a, b, t);                             // fade (film dissolve)
    if (mode < 6.5) {
        // Smooth fade: smootherstep has zero velocity and acceleration at both ends.
        float s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        return linearMix(a, b, s);
    }
    if (mode < 7.5) return dipThrough(a, b, vec3(0.055, 0.06, 0.075), t, 0.08); // dip to charcoal

    // Soft cross fade: a film dissolve with a gentle exposure lift in the middle, so the overlap
    // glows a little instead of turning muddy.
    float s = clamp(p_softness, 0.0, 1.0);
    float w = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    vec3 lin = mix(toLinear(a.rgb), toLinear(b.rgb), w);
    float lift = sin(3.14159265 * t) * mix(0.15, 0.45, s);
    return vec4(toDisplay(softClip(lin * exp2(lift), 0.8)), mix(a.a, b.a, w));
}
