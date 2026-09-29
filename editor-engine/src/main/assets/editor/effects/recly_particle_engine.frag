vec4 reclyEffect(vec2 uv) {
    vec4 source = texture2D(uTexSampler, uv);
    vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
    
    if (p_mode < 0.5) {
        // Smoke: procedural billowing vapor noise using trigonometric octaves
        vec2 p = uv * vec2(2.5, 4.0) - vec2(0.0, uTime * p_speed * 0.4);
        float n = sin(p.x * 2.0 + sin(p.y * 1.5)) * cos(p.y * 2.0 + sin(p.x * 1.5));
        float n2 = sin(p.x * 4.0 - p.y * 3.0) * cos(p.y * 4.0 + p.x * 3.0) * 0.5;
        float smoke = clamp((n + n2 + 0.5) * p_amount * 0.65, 0.0, 1.0);
        vec3 smokeColor = mix(source.rgb, vec3(0.85, 0.88, 0.9), smoke);
        return vec4(smokeColor, source.a);
    } else if (p_mode < 1.5) {
        // Fire: procedural upward flame tongues and embers
        vec2 p = uv * vec2(3.0, 5.0) - vec2(0.0, uTime * p_speed * 2.0);
        float flame = sin(p.x * 3.0 + sin(p.y * 2.0)) * (1.0 - uv.y) * 1.8;
        flame += sin(p.x * 7.0 - p.y * 5.0) * 0.4;
        float intensity = clamp(flame * p_amount, 0.0, 1.0);
        vec3 fireRgb = vec3(1.0, 0.4 * intensity, 0.05 * intensity * intensity) * intensity * 1.5;
        return vec4(source.rgb + fireRgb, source.a);
    } else if (p_mode < 2.5) {
        // Sparks: flying incandescent sparks drifting upward
        vec2 grid = floor(uv * vec2(40.0, 25.0) - vec2(0.0, uTime * p_speed * 12.0));
        float randCell = fract(sin(dot(grid, vec2(12.9898, 78.233))) * 43758.5453);
        float spark = step(0.965 - p_amount * 0.02, randCell);
        vec2 cellFrac = fract(uv * vec2(40.0, 25.0) - vec2(0.0, uTime * p_speed * 12.0)) - 0.5;
        float dist = length(cellFrac);
        float glow = spark * smoothstep(0.4 * p_size, 0.05, dist);
        vec3 sparkColor = vec3(1.0, 0.7, 0.2) * glow * 2.0;
        return vec4(source.rgb + sparkColor, source.a);
    } else if (p_mode < 3.5) {
        // Lightning: procedural branching electrical lightning bolts
        float strikeTime = floor(uTime * p_speed * 2.0);
        float trigger = step(0.82, fract(sin(strikeTime * 17.1) * 43758.5453));
        float boltX = 0.5 + 0.3 * (fract(sin(strikeTime * 31.7) * 43758.5453) - 0.5);
        float zigzag = sin(uv.y * 25.0 + strikeTime) * 0.04 + sin(uv.y * 60.0) * 0.015;
        float dist = abs(uv.x - (boltX + zigzag));
        float bolt = trigger * exp(-dist * 120.0 / max(p_size, 0.2)) * p_amount;
        vec3 boltColor = vec3(0.7, 0.85, 1.0) * bolt * 2.5;
        return vec4(source.rgb + boltColor, source.a);
    } else if (p_mode < 4.5) {
        // Rain: downward falling slanted streaks with motion blur
        vec2 rainUv = uv * vec2(45.0, 8.0) - vec2(uTime * p_speed * 2.0, uTime * p_speed * 18.0);
        vec2 cell = floor(rainUv);
        float randRain = fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
        float drop = step(0.94 - p_amount * 0.03, randRain);
        float streak = drop * (1.0 - fract(rainUv.y)) * 0.45 * p_size;
        return vec4(source.rgb + vec3(0.65, 0.75, 0.9) * streak, source.a);
    } else if (p_mode < 5.5) {
        // Snow: soft floating flakes with gentle wind sway
        float sway = sin(uTime * 1.5 + uv.y * 4.0) * 0.05;
        vec2 snowUv = (uv + vec2(sway, 0.0)) * vec2(25.0, 15.0) - vec2(0.0, uTime * p_speed * 2.5);
        vec2 cell = floor(snowUv);
        float randSnow = fract(sin(dot(cell, vec2(93.1, 157.3))) * 43758.5453);
        float flake = step(0.93 - p_amount * 0.04, randSnow);
        vec2 f = fract(snowUv) - 0.5;
        float d = length(f);
        float flakeGlow = flake * smoothstep(0.35 * p_size, 0.05, d) * 0.8;
        return vec4(source.rgb + vec3(flakeGlow), source.a);
    } else if (p_mode < 6.5) {
        // Dust Particles: floating bokeh orbs with soft Brownian drift
        vec2 dustUv = uv * vec2(15.0, 10.0) + vec2(sin(uTime * 0.5), cos(uTime * 0.4)) * 0.8;
        vec2 cell = floor(dustUv);
        float randDust = fract(sin(dot(cell, vec2(43.1, 71.9))) * 43758.5453);
        float dust = step(0.92 - p_amount * 0.05, randDust);
        vec2 f = fract(dustUv) - 0.5;
        float d = length(f);
        float orb = dust * smoothstep(0.45 * p_size, 0.01, d) * 0.35;
        return vec4(source.rgb + vec3(1.0, 0.95, 0.8) * orb, source.a);
    } else if (p_mode < 7.5) {
        // Sparkles: 4-pointed glinting cross stars
        vec2 sparkUv = uv * vec2(18.0, 12.0) + vec2(uTime * 0.1);
        vec2 cell = floor(sparkUv);
        float randSpark = fract(sin(dot(cell, vec2(17.3, 89.1))) * 43758.5453);
        float activeStar = step(0.94 - p_amount * 0.03, randSpark);
        vec2 f = abs(fract(sparkUv) - 0.5);
        float cross = (exp(-f.x * 25.0) * exp(-f.y * 3.0) + exp(-f.y * 25.0) * exp(-f.x * 3.0)) * activeStar * p_size;
        float twinkle = 0.5 + 0.5 * sin(uTime * 6.0 + randSpark * 6.28);
        return vec4(source.rgb + vec3(1.0, 0.9, 0.6) * cross * twinkle * 1.5, source.a);
    } else if (p_mode < 8.5) {
        // Energy Aura: pulsating high-energy plasma aura
        float auraNoise = sin(uv.x * 12.0 + uTime * p_speed * 4.0) * cos(uv.y * 12.0 - uTime * p_speed * 4.0);
        float pulse = 0.5 + 0.5 * sin(uTime * 5.0);
        vec3 aura = vec3(0.2, 0.7, 1.0) * abs(auraNoise) * pulse * p_amount * 0.5;
        return vec4(source.rgb + aura, source.a);
    } else {
        // Floating Bubbles: iridescent translucent spheres drifting with refraction
        vec2 bubbleUv = uv * vec2(12.0, 8.0) - vec2(sin(uTime * 0.8) * 0.5, uTime * p_speed * 1.8);
        vec2 cell = floor(bubbleUv);
        float randBubble = fract(sin(dot(cell, vec2(57.3, 113.1))) * 43758.5453);
        float hasBubble = step(0.91 - p_amount * 0.04, randBubble);
        vec2 f = fract(bubbleUv) - 0.5;
        float d = length(f);
        float ring = hasBubble * smoothstep(0.04, 0.0, abs(d - 0.3 * p_size));
        vec3 iridescence = vec3(0.5 + 0.5 * sin(d * 20.0), 0.5 + 0.5 * cos(d * 18.0), 0.8) * ring * 0.85;
        return vec4(source.rgb + iridescence, source.a);
    }
}
