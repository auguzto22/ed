vec4 reclyEffect(vec2 uv) {
    vec4 c = texture2D(uTexSampler,uv);
    float band = .65 + .35 * sin((uv.y*uResolution.y + uTime*p_speed) * 3.14159 / p_spacing);
    return vec4(c.rgb*band,c.a);
}
