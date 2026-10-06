vec4 reclyTransition(vec2 uv, float progress) {
    vec4 ca = texture2D(uTexA, uv);
    vec4 cb = texture2D(uTexB, uv);
    if (progress <= 0.0) return ca;
    if (progress >= 1.0) return cb;

    float t = clamp(progress, 0.0, 1.0);
    float amount = sin(t * 3.14159265);

    if (p_mode < 0.5) {
        // Pixel Dissolve: blocky 8-bit dissolve
        float blocks = mix(80.0, 8.0, amount);
        vec2 blockUv = floor(uv * blocks) / blocks;
        float hash = fract(sin(dot(blockUv, vec2(12.9898, 78.233))) * 43758.5453);
        if (hash < t) {
            return texture2D(uTexB, blockUv);
        } else {
            return texture2D(uTexA, blockUv);
        }
    } else if (p_mode < 1.5) {
        // Scanline Wipe: retro arcade scanline reveal
        float scanlines = sin(uv.y * 300.0) * 0.5 + 0.5;
        float wipe = uv.x - t * 1.2 + 0.1;
        float mask = smoothstep(-0.05, 0.05, wipe);
        vec4 col = mix(cb, ca, mask);
        col.rgb *= (1.0 - scanlines * 0.15 * amount);
        return col;
    } else if (p_mode < 2.5) {
        // Energy Blast: expanding shockwave ring and flash
        vec2 center = vec2(0.5, 0.5);
        float dist = distance(uv, center);
        float ringPos = t * 1.2;
        float ring = smoothstep(0.08, 0.0, abs(dist - ringPos));
        vec4 blended = mix(ca, cb, smoothstep(0.4, 0.6, t));
        vec3 energyColor = vec3(0.1, 1.0, 0.8); // cyan neon energy
        blended.rgb += energyColor * ring * 2.0 * amount * p_intensity;
        return clamp(blended, 0.0, 1.0);
    } else if (p_mode < 3.5) {
        // Level Clear Wipe: diagonal high-energy wipe banner
        float diag = uv.x + uv.y * 0.5;
        float edge = t * 1.8 - 0.4;
        float mask = smoothstep(edge - 0.05, edge + 0.05, diag);
        vec4 col = mix(cb, ca, mask);
        float border = (1.0 - abs(mask - 0.5) * 2.0) * amount;
        col.rgb += vec3(1.0, 0.9, 0.2) * border * 0.8; // golden arcade edge
        return clamp(col, 0.0, 1.0);
    } else {
        // Cyber Grid: digital matrix grid blocks
        vec2 grid = floor(uv * 24.0) / 24.0;
        float order = (grid.x + grid.y) * 0.5;
        float noise = fract(sin(dot(grid, vec2(37.1, 61.7))) * 91827.3);
        float reveal = order * 0.6 + noise * 0.4;
        if (reveal < t) {
            return cb;
        } else {
            return ca;
        }
    }
}

