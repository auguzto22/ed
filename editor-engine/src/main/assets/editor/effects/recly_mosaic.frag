vec4 reclyEffect(vec2 uv) {
    vec2 pixel = vec2(p_size) / uResolution;
    vec2 row = vec2(0.0, mod(floor(uv.y / pixel.y), 2.0) * pixel.x * 0.5);
    vec2 cell = floor((uv + row) / pixel) * pixel + pixel * 0.5 - row;
    return texture2D(uTexSampler, clamp(cell, 0.0, 1.0));
}
