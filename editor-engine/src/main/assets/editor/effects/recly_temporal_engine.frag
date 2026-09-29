vec4 reclyEffect(vec2 uv) {
    vec4 current = texture2D(uTexSampler, uv);
    vec4 h0 = texture2D(uHistory0, uv);
    vec4 h1 = texture2D(uHistory1, uv);
    vec4 h2 = texture2D(uHistory2, uv);
    
    if (p_mode < 0.5) {
        // Motion Trail: exponential decay over history
        float w0 = 0.50;
        float w1 = 0.25 * (1.0 - p_decay * 0.5);
        float w2 = 0.15 * (1.0 - p_decay * 0.8);
        float w3 = 0.10 * (1.0 - p_decay);
        vec3 trail = current.rgb * w0 + h0.rgb * w1 + h1.rgb * w2 + h2.rgb * w3;
        return vec4(mix(current.rgb, trail, p_amount), current.a);
    } else if (p_mode < 1.5) {
        // Ghost Trail: translucent trailing silhouettes
        vec3 ghost = mix(current.rgb, h1.rgb, 0.45 * p_amount);
        ghost = mix(ghost, h2.rgb, 0.25 * p_amount);
        return vec4(ghost, current.a);
    } else if (p_mode < 2.5) {
        // Frame Echo: distinct stepped snapshots
        vec3 echo = current.rgb * 0.5 + h1.rgb * 0.3 + h2.rgb * 0.2;
        return vec4(mix(current.rgb, echo, p_amount), current.a);
    } else if (p_mode < 3.5) {
        // RGB Trail: chromatic temporal separation (Red at t, Green at t-1, Blue at t-2)
        float r = current.r;
        float g = mix(current.g, h1.g, p_amount * 0.85);
        float b = mix(current.b, h2.b, p_amount * 0.85);
        return vec4(r, g, b, current.a);
    } else if (p_mode < 4.5) {
        // Light Trail: maximum luminance retention across history (light painting)
        vec3 maxLight = max(current.rgb, max(h0.rgb, max(h1.rgb, h2.rgb)));
        return vec4(mix(current.rgb, maxLight, p_amount), current.a);
    } else if (p_mode < 5.5) {
        // Long Exposure Trail: smooth additive accumulation
        vec3 accum = (current.rgb + h0.rgb + h1.rgb + h2.rgb) * 0.25;
        return vec4(mix(current.rgb, accum, p_amount), current.a);
    } else if (p_mode < 6.5) {
        // Clone Trail: stepped semi-opaque delayed clone
        vec3 clone = mix(current.rgb, h2.rgb, 0.5 * p_amount);
        return vec4(clone, current.a);
    } else if (p_mode < 7.5) {
        // Shadow Trail: darkened trailing silhouettes
        vec3 shadow = min(current.rgb, min(h0.rgb, h2.rgb) * (1.0 - p_decay * 0.4));
        return vec4(mix(current.rgb, shadow, p_amount), current.a);
    } else if (p_mode < 8.5) {
        // Smear Trail: directional velocity smear with past frames
        vec3 smear = (current.rgb * 2.0 + h0.rgb + h1.rgb) * 0.25;
        return vec4(mix(current.rgb, smear, p_amount), current.a);
    } else {
        // Afterimage: high-contrast inverted luminous ghost
        vec3 invPast = 1.0 - h2.rgb;
        vec3 composite = mix(current.rgb, invPast, p_amount * 0.4);
        return vec4(clamp(composite, 0.0, 1.0), current.a);
    }
}
