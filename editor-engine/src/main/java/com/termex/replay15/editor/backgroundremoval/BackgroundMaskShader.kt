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
     * How many mask texels one pixel of the final image covers: the mask is cut at the source
     * resolution and the frame is then fitted (or filled) into [targetWidth] x [targetHeight].
     * Unknown sizes give 1 (no widening).
     */
    fun edgeScale(maskWidth: Int, maskHeight: Int, sourceWidth: Int, sourceHeight: Int,
        targetWidth: Int, targetHeight: Int, fill: Boolean = false): Float {
        if (listOf(maskWidth, maskHeight, sourceWidth, sourceHeight, targetWidth, targetHeight).any { it <= 0 }) return 1f
        val sx = targetWidth.toFloat() / sourceWidth; val sy = targetHeight.toFloat() / sourceHeight
        val fit = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        val maskPerSource = maxOf(maskWidth.toFloat() / sourceWidth, maskHeight.toFloat() / sourceHeight)
        return (maskPerSource / fit).coerceIn(1f, 8f)
    }

    /**
     * Declares `float reclyRefinedMask(vec2 uv)`, `float reclyMatteAlpha(vec2 uv)` and
     * `vec4 reclyCutout(vec2 uv, vec4 source)` (alpha + edge colour decontamination).
     * Requires uniforms: [videoSampler], [maskSampler], `uMaskTexel`, `uThreshold`, `uFeather`,
     * `uEdgeSmoothing`, `uEdgeScale` (mask texels per final output pixel, see [edgeScale]).
     */
    fun functions(videoSampler: String, maskSampler: String): String = """
        // Refined mask confidence at uv. Also returns, in foreground, the colour of the nearby
        // pixels the model is sure belong to the subject (weight = mask^4), used to repaint edge
        // pixels that still carry the background's colour (light halos around dark hair).
        float reclyRefine(vec2 uv, out vec3 foreground) {
            vec3 reference = texture2D($videoSampler, uv).rgb;
            // Edge smoothing widens the colour tolerance (softer, more blended edges).
            float sigma = mix(0.06, 0.20, clamp(uEdgeSmoothing, 0.0, 1.0));
            float inv = 1.0 / (2.0 * sigma * sigma);
            float sum = 0.0;
            float weights = 0.0;
            vec3 colour = vec3(0.0);
            float colourWeights = 0.0;
            for (int y = -2; y <= 2; y++) {
                for (int x = -2; x <= 2; x++) {
                    vec2 offset = vec2(float(x), float(y));
                    vec2 p = clamp(uv + offset * uMaskTexel, 0.0, 1.0);
                    float m = texture2D($maskSampler, p).r;
                    vec3 c = texture2D($videoSampler, p).rgb;
                    vec3 d = c - reference;
                    float spatial = exp(-dot(offset, offset) * 0.22);
                    float w = spatial * exp(-dot(d, d) * inv);
                    sum += m * w;
                    weights += w;
                    float m2 = m * m;
                    float cw = spatial * m2 * m2;
                    colour += c * cw;
                    colourWeights += cw;
                }
            }
            foreground = colourWeights > 0.0001 ? colour / colourWeights : reference;
            return sum / max(weights, 0.0001);
        }
        float reclyRefinedMask(vec2 uv) {
            vec3 ignored;
            return reclyRefine(uv, ignored);
        }
        // Anti-aliasing for full-resolution (photo) masks. The cut is made at the photo's
        // resolution and then scaled down (often 2-3x, without mipmaps) to the canvas, so a sharp
        // matte came out as a 1-pixel on/off edge with visible stair steps and ripples. Here the
        // alpha is averaged over 4 points spread across one pixel of the FINAL image
        // (uEdgeScale = mask texels per output pixel), which is the coverage a mipmap would give.
        // Low-resolution video masks (<= 512 px) keep the single sample.
        float reclyCoverage(vec2 uv, float refined, float feather) {
            float lo = uThreshold - feather;
            float hi = uThreshold + feather;
            float hiRes = step(640.0, max(1.0 / uMaskTexel.x, 1.0 / uMaskTexel.y));
            if (hiRes < 0.5) return smoothstep(lo, hi, refined);
            vec2 o = uMaskTexel * clamp(uEdgeScale, 1.0, 8.0) * 0.375;
            float base = texture2D($maskSampler, uv).r;
            float a = smoothstep(lo, hi, refined + texture2D($maskSampler, clamp(uv + vec2(-o.x, -o.y), 0.0, 1.0)).r - base);
            a += smoothstep(lo, hi, refined + texture2D($maskSampler, clamp(uv + vec2(o.x, -o.y), 0.0, 1.0)).r - base);
            a += smoothstep(lo, hi, refined + texture2D($maskSampler, clamp(uv + vec2(-o.x, o.y), 0.0, 1.0)).r - base);
            a += smoothstep(lo, hi, refined + texture2D($maskSampler, clamp(uv + vec2(o.x, o.y), 0.0, 1.0)).r - base);
            return a * 0.25;
        }
        float reclyMatteAlpha(vec2 uv) {
            return reclyCoverage(uv, reclyRefinedMask(uv), max(uFeather, 0.001));
        }
        // Premultiply-free cut-out: alpha from the refined mask, and edge pixels (confidence
        // between the threshold and ~0.4 above it) pulled toward the subject's own colour so the
        // background does not bleed through as a halo. Fully confident pixels are untouched.
        vec4 reclyCutout(vec2 uv, vec4 source) {
            vec3 foreground;
            float mask = reclyRefine(uv, foreground);
            float feather = max(uFeather, 0.001);
            float alpha = reclyCoverage(uv, mask, feather);
            // edge1 must stay above edge0 (GLSL leaves smoothstep undefined otherwise), also for
            // projects saved with the old raw slider at 100 % (threshold 1.0).
            float keep = smoothstep(uThreshold, max(min(uThreshold + 0.4, 1.0), uThreshold + 0.001), mask);
            return vec4(mix(foreground, source.rgb, keep), source.a * alpha);
        }
    """
}
