void main() {
    vec4 original = texture2D(uTexSampler, vUv);
    vec4 effected = reclyEffect(vUv);
    vec3 blended = effected.rgb;
    if (uBlendMode > 0.5 && uBlendMode < 1.5) blended = original.rgb + effected.rgb;
    else if (uBlendMode < 2.5 && uBlendMode > 1.5) blended = original.rgb * effected.rgb;
    else if (uBlendMode < 3.5 && uBlendMode > 2.5) blended = 1.0 - (1.0 - original.rgb) * (1.0 - effected.rgb);
    else if (uBlendMode < 4.5 && uBlendMode > 3.5) blended = mix(2.0 * original.rgb * effected.rgb,
        1.0 - 2.0 * (1.0 - original.rgb) * (1.0 - effected.rgb), step(0.5, original.rgb));
    else if (uBlendMode < 5.5 && uBlendMode > 4.5) blended = max(original.rgb, effected.rgb);
    else if (uBlendMode < 6.5 && uBlendMode > 5.5) blended = min(original.rgb, effected.rgb);
    else if (uBlendMode < 7.5 && uBlendMode > 6.5) blended = abs(original.rgb - effected.rgb);
    else if (uBlendMode < 8.5 && uBlendMode > 7.5) {
        // Soft-light approximation that stays well behaved at both extremes.
        vec3 soft = 2.0 * original.rgb * effected.rgb + original.rgb * original.rgb * (1.0 - 2.0 * effected.rgb);
        vec3 softHighlight = sqrt(original.rgb) * (2.0 * effected.rgb - 1.0) + 2.0 * original.rgb * (1.0 - effected.rgb);
        blended = mix(soft, softHighlight, step(0.5, effected.rgb));
    }
    else if (uBlendMode > 8.5) blended = mix(2.0 * original.rgb * effected.rgb,
        1.0 - 2.0 * (1.0 - original.rgb) * (1.0 - effected.rgb), step(0.5, effected.rgb));
    effected = vec4(clamp(blended, 0.0, 1.0), effected.a);

    float maskAmount = 1.0;
    if (uEffectMask.x > 0.5) {
        vec2 q = vUv - 0.5 - uEffectMaskTransform.xy;
        float c = cos(uEffectMaskTransform.z); float s = sin(uEffectMaskTransform.z);
        q = mat2(c, -s, s, c) * q;
        q.x /= uEffectMaskAspect;
        float distanceToEdge;
        if (uEffectMask.x < 1.5) distanceToEdge = max(abs(q.x), abs(q.y)) - uEffectMaskTransform.w * 0.5;
        else if (uEffectMask.x < 2.5) distanceToEdge = length(q) - uEffectMaskTransform.w * 0.5;
        else if (uEffectMask.x < 3.5) distanceToEdge = q.y - uEffectMaskTransform.w * 0.5;
        else distanceToEdge = length(q) - uEffectMaskTransform.w * 0.5;
        maskAmount = 1.0 - smoothstep(-uEffectMask.y, uEffectMask.y, distanceToEdge);
        if (uEffectMask.z > 0.5) maskAmount = 1.0 - maskAmount;
        maskAmount *= uEffectMask.w;
    }
    gl_FragColor = mix(original, effected, uIntensity * maskAmount);
}
