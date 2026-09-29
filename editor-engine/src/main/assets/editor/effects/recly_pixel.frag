vec4 reclyEffect(vec2 uv) {
    vec2 grid = uResolution / p_size;
    return texture2D(uTexSampler, (floor(uv * grid) + .5) / grid);
}
