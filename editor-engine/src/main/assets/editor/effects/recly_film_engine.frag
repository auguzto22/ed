vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    float frame = floor(uTime * p_speed);
    
    // Hash function for procedural grain and film particles
    float grain = fract(sin(dot(uv * uResolution + frame * 37.1, vec2(12.9898, 78.233))) * 43758.5453);
    float flicker = 1.0 - (fract(sin(frame * 91.3) * 43758.5453) - 0.5) * 0.15 * p_amount;
    
    if (p_mode < 0.5) {
        // Old Film: Sepia tone + gate jitter + scratches + grain
        float sepiaR = dot(source.rgb, vec3(0.393, 0.769, 0.189));
        float sepiaG = dot(source.rgb, vec3(0.349, 0.686, 0.168));
        float sepiaB = dot(source.rgb, vec3(0.272, 0.534, 0.131));
        vec3 aged = mix(source.rgb, vec3(sepiaR, sepiaG, sepiaB), 0.85);
        
        // Procedural vertical scratch line
        float scratchPos = fract(sin(frame * 17.3) * 43758.5453);
        float scratch = step(abs(uv.x - scratchPos), 0.0015 * p_scratch_density) * 0.35 * p_amount;
        
        vec3 finalColor = clamp(aged * flicker + (grain - 0.5) * p_grain + scratch, 0.0, 1.0);
        return vec4(finalColor, source.a);
    } else if (p_mode < 1.5) {
        // Dust & Scratches: procedural dark hairs, dust specks, and vertical scratches
        float scratch1 = step(abs(uv.x - fract(sin(frame * 13.1) * 43758.5453)), 0.001) * 0.45;
        float scratch2 = step(abs(uv.x - fract(sin(frame * 29.7) * 43758.5453)), 0.0008) * 0.35;
        vec2 dustGrid = floor(uv * 18.0 + sin(frame));
        float dustRand = fract(sin(dot(dustGrid, vec2(127.1, 311.7))) * 43758.5453);
        float dust = step(0.985 - p_scratch_density * 0.01, dustRand) * 0.4;
        vec3 result = source.rgb - (scratch1 + scratch2 + dust) * p_amount + (grain - 0.5) * p_grain;
        return vec4(clamp(result, 0.0, 1.0), source.a);
    } else if (p_mode < 2.5) {
        // Film Burn: burning edge exposure with orange/amber glow
        float burnNoise = fract(sin(dot(uv * 4.0 + vec2(uTime * 0.3), vec2(41.2, 89.1))) * 43758.5453);
        float edgeDist = 1.0 - min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)) * 2.5;
        float burnMask = smoothstep(0.4, 0.95, edgeDist + burnNoise * 0.3) * p_amount;
        vec3 burnColor = vec3(1.0, 0.45, 0.1) * burnMask * 2.0;
        return vec4(clamp(source.rgb + burnColor, 0.0, 1.0), source.a);
    } else if (p_mode < 3.5) {
        // 8mm Film: heavy organic grain, warm palette, corner vignette, gate weave
        vec2 center = uv - 0.5;
        float vig = clamp(1.0 - dot(center, center) * 1.8, 0.0, 1.0);
        vec3 warm = source.rgb * vec3(1.12, 1.02, 0.88);
        warm = clamp(warm * flicker * vig + (grain - 0.5) * p_grain * 1.6, 0.0, 1.0);
        return vec4(warm, source.a);
    } else if (p_mode < 4.5) {
        // Sepia Film: classic duotone sepia curve + subtle grain
        float luma = dot(source.rgb, vec3(0.299, 0.587, 0.114));
        vec3 sepia = vec3(luma * 1.15, luma * 0.92, luma * 0.68) + (grain - 0.5) * p_grain * 0.5;
        return vec4(clamp(mix(source.rgb, sepia, p_amount), 0.0, 1.0), source.a);
    } else {
        // Retro Camcorder: subtle interlacing lines, slight green/blue tint, timestamp feel
        float scanline = 0.92 + 0.08 * sin(uv.y * uResolution.y * 3.14159);
        vec3 cam = source.rgb * vec3(0.96, 1.02, 0.94) * scanline + (grain - 0.5) * p_grain * 0.8;
        return vec4(clamp(cam, 0.0, 1.0), source.a);
    }
}
