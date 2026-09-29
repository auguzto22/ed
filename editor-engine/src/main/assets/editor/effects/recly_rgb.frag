vec4 reclyEffect(vec2 uv) {
    vec2 d = (vec2(cos(p_angle),sin(p_angle)) * p_distance
        + vec2(p_horizontal_offset, p_vertical_offset)) / uResolution;
    vec4 c = texture2D(uTexSampler, uv);
    return vec4(texture2D(uTexSampler,clamp(uv+d,0.0,1.0)).r,c.g,
        texture2D(uTexSampler,clamp(uv-d,0.0,1.0)).b,c.a);
}
