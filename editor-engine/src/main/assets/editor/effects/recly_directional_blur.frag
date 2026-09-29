vec4 reclyEffect(vec2 uv) {
    vec2 d = vec2(cos(p_angle),sin(p_angle))*p_radius/uResolution;
    return (texture2D(uTexSampler,uv-d)+texture2D(uTexSampler,uv-d*.5)+texture2D(uTexSampler,uv)
        +texture2D(uTexSampler,uv+d*.5)+texture2D(uTexSampler,uv+d))*.2;
}
