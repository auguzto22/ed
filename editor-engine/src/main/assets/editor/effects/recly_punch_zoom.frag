vec4 reclyEffect(vec2 uv) {
    float attack = smoothstep(0.0, p_attack, uProgress);
    float release = 1.0 - smoothstep(p_attack, min(1.0, p_attack + p_release), uProgress);
    float zoom = 1.0 + p_amount * attack * release;
    vec2 warped = (uv - 0.5) / zoom + 0.5;
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
