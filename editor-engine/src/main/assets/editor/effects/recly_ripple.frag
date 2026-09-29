vec4 reclyEffect(vec2 uv) {
    // Measure in square visual units so rings remain circular on portrait and landscape frames.
    vec2 delta = (uv - 0.5) * vec2(uResolution.x / uResolution.y, 1.0);
    float distanceFromCenter = length(delta);
    float wave = sin(distanceFromCenter * p_frequency - uTime * p_speed);
    vec2 direction = delta / max(distanceFromCenter, 0.001);
    vec2 warped = uv + direction * wave * p_amount / uResolution;
    return texture2D(uTexSampler, clamp(warped, 0.0, 1.0));
}
