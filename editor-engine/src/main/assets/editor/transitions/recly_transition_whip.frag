vec4 motionSample(sampler2D tex, vec2 uv, vec2 dir, float amount) {
    vec4 sum = vec4(0.0);
    vec2 step = dir * amount;
    for (int i = 0; i < 7; i++) {
        float f = (float(i) - 3.0) / 3.0;
        sum += texture2D(tex, clamp(uv + step * f, 0.0, 1.0));
    }
    return sum / 7.0;
}

vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);

    vec2 dir = vec2(-1.0, 0.0); // Whip Left default
    if (mode > 0.5 && mode < 1.5) dir = vec2(1.0, 0.0);       // Whip Right
    else if (mode > 1.5 && mode < 2.5) dir = vec2(0.0, -1.0); // Whip Up
    else if (mode > 2.5 && mode < 3.5) dir = vec2(0.0, 1.0);  // Whip Down
    else if (mode > 3.5 && mode < 4.5) dir = vec2(-1.0, 0.5); // Motion Swipe (slanted)
    else if (mode > 4.5) dir = vec2(-1.0, 0.0);               // Fast Pan

    // Speed velocity curve: peaks sharply around center
    float speed = sin(t * 3.14159265);
    float blurAmount = speed * 0.08 * (p_blur > 0.1 ? p_blur : 1.0);

    vec2 offsetA = -dir * t;
    vec2 offsetB = dir * (1.0 - t);

    vec2 uvA = uv + offsetA;
    vec2 uvB = uv - offsetB;

    vec4 colA = motionSample(uTexA, uvA, dir, blurAmount);
    vec4 colB = motionSample(uTexB, uvB, dir, blurAmount);

    return mix(colA, colB, smoothstep(0.45, 0.55, t));
}
