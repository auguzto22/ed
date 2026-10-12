package com.termex.replay15.editor.backgroundremoval

/**
 * Shared GLSL for turning a low-resolution segmentation confidence map into a clean alpha matte.
 *
 * The models return a 256-ish px mask. Bilinear upscaling plus a 4-tap blur (the previous shader)
 * produced soft, blocky edges that ignored the real contour of the subject, leaving halos of
 * background around hair/shoulders and eating into the subject elsewhere. This is a joint
 * bilateral upsampling: each mask sample in a 5x5 neighbourhood (spaced by one mask texel) is
 * weighted by its distance and by how similar the full-resolution video colour at that position
 * is to the colour of the pixel being shaded, so the matte edge snaps to the image edge.
 * Preview (BackgroundRemovalGpuPass) and export (BackgroundRemovalExportEffect) use the same code.
 */
object BackgroundMaskShader {
    /**
     * Declares `float reclyRefinedMask(vec2 uv)` and `float reclyMatteAlpha(vec2 uv)`.
     * Requires uniforms: [videoSampler], [maskSampler], `uMaskTexel`, `uThreshold`, `uFeather`,
     * `uEdgeSmoothing`.
     */
    fun functions(videoSampler: String, maskSampler: String): String = """
        float reclyRefinedMask(vec2 uv) {
            vec3 reference = texture2D($videoSampler, uv).rgb;
            // Edge smoothing widens the colour tolerance (softer, more blended edges).
            float sigma = mix(0.06, 0.20, clamp(uEdgeSmoothing, 0.0, 1.0));
            float inv = 1.0 / (2.0 * sigma * sigma);
            float sum = 0.0;
            float weights = 0.0;
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    vec2 offset = vec2(float(x), float(y));
                    vec2 p = clamp(uv + offset * uMaskTexel, 0.0, 1.0);
                    float m = texture2D($maskSampler, p).r;
                    vec3 d = texture2D($videoSampler, p).rgb - reference;
                    float w = exp(-dot(offset, offset) * 0.22) * exp(-dot(d, d) * inv);
                    sum += m * w;
                    weights += w;
                }
            }
            return sum / max(weights, 0.0001);
        }
        float reclyMatteAlpha(vec2 uv) {
            float mask = reclyRefinedMask(uv);
            float feather = max(uFeather, 0.001);
            return smoothstep(uThreshold - feather, uThreshold + feather, mask);
        }
    """
}
