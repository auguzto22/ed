vec4 reclyEffect(vec2 uv) {
    vec2 d = p_radius / uResolution;
    vec4 s = texture2D(uTexSampler, uv);
    vec3 c = s.rgb;
    c += texture2D(uTexSampler, uv + d * vec2(1.0, 0.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-1.0, 0.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.0, 1.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.0, -1.0)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.7, 0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-0.7, 0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(0.7, -0.7)).rgb;
    c += texture2D(uTexSampler, uv + d * vec2(-0.7, -0.7)).rgb;
    c /= 9.0;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += c * smoothstep(0.55, 1.0, l) * p_bright;
    return vec4(mix(s.rgb, clamp(c, 0.0, 1.0), p_amount), s.a);
}
