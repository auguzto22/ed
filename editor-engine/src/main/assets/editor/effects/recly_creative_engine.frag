vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec2 center = vec2(0.5, 0.5);
    vec2 d = uv - center;
    
    if (p_mode < 0.5) {
        // Kaleidoscope: radial multi-segment symmetry
        float r = length(d);
        float a = atan(d.y, d.x);
        float tau = 6.2831853;
        float segmentAngle = tau / max(p_segments, 2.0);
        a = mod(a, segmentAngle);
        if (a > segmentAngle * 0.5) a = segmentAngle - a;
        a += p_angle;
        vec2 kUv = center + vec2(cos(a), sin(a)) * r;
        return texture2D(uTexSampler, clamp(kUv, 0.0, 1.0));
    } else if (p_mode < 1.5) {
        // Prism: multi-refractive chromatic facet dispersion
        vec2 dir = vec2(cos(p_angle), sin(p_angle)) * (p_amount * 0.035);
        float r = texture2D(uTexSampler, clamp(uv + dir, 0.0, 1.0)).r;
        float g = texture2D(uTexSampler, uv).g;
        float b = texture2D(uTexSampler, clamp(uv - dir, 0.0, 1.0)).b;
        return vec4(r, g, b, source.a);
    } else if (p_mode < 2.5) {
        // Glass Distortion: frosted / fluted ribbed glass normal displacement
        float fluting = sin(uv.x * p_segments * 10.0 + p_angle);
        vec2 glassDisp = vec2(fluting * 0.015 * p_amount, 0.0);
        return texture2D(uTexSampler, clamp(uv + glassDisp, 0.0, 1.0));
    } else if (p_mode < 3.5) {
        // Split Screen: 2x2 or tiled mosaic reflections
        vec2 grid = fract(uv * floor(p_segments));
        return texture2D(uTexSampler, grid);
    } else if (p_mode < 4.5) {
        // Double Exposure: ethereal internal luminance blending with transformed self
        vec2 offsetUv = fract(uv * 1.15 + vec2(0.05 * sin(uTime * 0.3), 0.05 * cos(uTime * 0.2)));
        vec4 ghost = texture2D(uTexSampler, offsetUv);
        vec3 screen = 1.0 - (1.0 - source.rgb) * (1.0 - ghost.rgb * p_amount);
        return vec4(mix(source.rgb, screen, p_amount * 0.8), source.a);
    } else if (p_mode < 5.5) {
        // Edge Detection: Sobel gradient magnitude filter
        vec2 stepSize = 1.5 / uResolution;
        vec3 h = -texture2D(uTexSampler, uv + vec2(-stepSize.x, -stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x, -stepSize.y)).rgb
                 - 2.0 * texture2D(uTexSampler, uv + vec2(-stepSize.x, 0.0)).rgb
                 + 2.0 * texture2D(uTexSampler, uv + vec2( stepSize.x, 0.0)).rgb
                 - texture2D(uTexSampler, uv + vec2(-stepSize.x,  stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x,  stepSize.y)).rgb;
        vec3 v = -texture2D(uTexSampler, uv + vec2(-stepSize.x, -stepSize.y)).rgb
                 - 2.0 * texture2D(uTexSampler, uv + vec2(0.0, -stepSize.y)).rgb
                 - texture2D(uTexSampler, uv + vec2( stepSize.x, -stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2(-stepSize.x,  stepSize.y)).rgb
                 + 2.0 * texture2D(uTexSampler, uv + vec2(0.0,  stepSize.y)).rgb
                 + texture2D(uTexSampler, uv + vec2( stepSize.x,  stepSize.y)).rgb;
        float edge = length(h) + length(v);
        return vec4(vec3(edge * p_amount * 2.0), source.a);
    } else {
        // Emboss / Relief: directional 3D relief bump map
        vec2 dir = vec2(cos(p_angle), sin(p_angle)) * (1.5 / uResolution);
        vec3 c1 = texture2D(uTexSampler, uv - dir).rgb;
        vec3 c2 = texture2D(uTexSampler, uv + dir).rgb;
        vec3 diff = (c1 - c2) * p_amount * 2.5 + vec3(0.5);
        return vec4(diff, source.a);
    }
}
