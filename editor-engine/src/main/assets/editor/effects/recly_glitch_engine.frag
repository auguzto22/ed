vec4 reclyEffect(vec2 uv) {
    float frame = floor(uTime * p_speed);
    vec2 warped = uv;
    vec4 source = texture2D(uTexSampler, uv);
    
    if (p_mode < 0.5) {
        // VHS Glitch: tape head jitter, tracking noise bar, chromatic shift
        float bar = step(0.92, sin(uv.y * 3.0 - uTime * 4.0));
        float jitter = sin(uv.y * 40.0 + frame) * (p_distance / uResolution.x) * bar * p_amount;
        warped.x = clamp(warped.x + jitter, 0.0, 1.0);
        vec2 split = vec2(p_distance / uResolution.x * 0.5 * p_amount, 0.0);
        float r = texture2D(uTexSampler, clamp(warped + split, 0.0, 1.0)).r;
        float g = texture2D(uTexSampler, warped).g;
        float b = texture2D(uTexSampler, clamp(warped - split, 0.0, 1.0)).b;
        float scanline = 0.9 + 0.1 * sin(uv.y * uResolution.y * 3.14159);
        return vec4(vec3(r, g, b) * scanline, source.a);
    } else if (p_mode < 1.5) {
        // Datamosh Style: macroblock directional displacement
        vec2 block = floor(uv * p_slices);
        float randBlock = fract(sin(dot(block, vec2(12.9898, 78.233)) + frame * 0.3) * 43758.5453);
        if (randBlock < p_amount * 0.4) {
            vec2 motion = vec2((randBlock - 0.2) * 2.0, (fract(randBlock * 7.1) - 0.5)) * (p_distance / uResolution.x);
            warped = clamp(uv + motion, 0.0, 1.0);
        }
        return texture2D(uTexSampler, warped);
    } else if (p_mode < 2.5) {
        // TV Static: high frequency analog static noise + flicker
        float n = fract(sin(dot(uv * uResolution + frame * 19.1, vec2(12.9898, 78.233))) * 43758.5453);
        vec3 staticColor = mix(source.rgb, vec3(n), p_amount * 0.45);
        return vec4(staticColor, source.a);
    } else if (p_mode < 3.5) {
        // Pixel Sort Style: streak displacement driven by brightness threshold
        float lum = dot(source.rgb, vec3(0.299, 0.587, 0.114));
        if (lum > 0.45) {
            float streak = (lum - 0.45) * (p_distance / uResolution.y) * p_amount;
            warped.y = clamp(warped.y - streak, 0.0, 1.0);
        }
        return texture2D(uTexSampler, warped);
    } else if (p_mode < 4.5) {
        // Block Glitch: rectangular mosaic block displacement
        vec2 grid = floor(uv * vec2(p_slices, p_slices * 0.6));
        float randCell = fract(sin(dot(grid + frame, vec2(37.1, 91.7))) * 43758.5453);
        if (randCell > (1.0 - p_amount * 0.35)) {
            vec2 shift = vec2((randCell - 0.5) * 2.0 * (p_distance / uResolution.x), 0.0);
            warped = clamp(uv + shift, 0.0, 1.0);
        }
        return texture2D(uTexSampler, warped);
    } else {
        // Signal Error: rolling sync drift and horizontal tearing
        float roll = fract(uTime * 0.5 * p_amount);
        float tear = step(0.85, fract(uv.y * 5.0 + frame * 0.2)) * sin(uv.y * 30.0) * (p_distance / uResolution.x);
        warped.x = clamp(warped.x + tear, 0.0, 1.0);
        warped.y = fract(warped.y + roll * 0.2 * p_amount);
        return texture2D(uTexSampler, warped);
    }
}
