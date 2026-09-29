package com.termex.replay15.editor.render

import com.termex.replay15.editor.domain.MaskState

/** Shared GLSL contract used by preview and text rendering. Export uses the same source in raw. */
internal object LayerMaskShader {
    const val PATH_POINT_COUNT = MaskState.MAX_MASK_PATH_POINTS

    const val UNIFORMS = """
        uniform vec4 uLayerMaskCenterSize;
        uniform vec4 uLayerMaskParams;
        uniform vec3 uLayerMaskMeta;
        uniform vec2 uLayerMaskPath0;
        uniform vec2 uLayerMaskPath1;
        uniform vec2 uLayerMaskPath2;
        uniform vec2 uLayerMaskPath3;
        uniform vec2 uLayerMaskPath4;
        uniform vec2 uLayerMaskPath5;
        uniform vec2 uLayerMaskPath6;
        uniform vec2 uLayerMaskPath7;
        uniform vec2 uLayerMaskPath8;
        uniform vec2 uLayerMaskPath9;
        uniform vec2 uLayerMaskPath10;
        uniform vec2 uLayerMaskPath11;
    """

    const val FUNCTIONS = """
        vec2 reclyMaskPoint(int index) {
            if (index == 0) return uLayerMaskPath0;
            if (index == 1) return uLayerMaskPath1;
            if (index == 2) return uLayerMaskPath2;
            if (index == 3) return uLayerMaskPath3;
            if (index == 4) return uLayerMaskPath4;
            if (index == 5) return uLayerMaskPath5;
            if (index == 6) return uLayerMaskPath6;
            if (index == 7) return uLayerMaskPath7;
            if (index == 8) return uLayerMaskPath8;
            if (index == 9) return uLayerMaskPath9;
            if (index == 10) return uLayerMaskPath10;
            return uLayerMaskPath11;
        }

        float reclySdBox(vec2 p, vec2 halfSize) {
            vec2 d = abs(p) - halfSize;
            return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
        }

        float reclySdHeart(vec2 p) {
            p.x = abs(p.x);
            if (p.x + p.y > 1.0) return length(p - vec2(0.25, 0.75)) - 0.353553;
            vec2 a = p - vec2(0.0, 1.0);
            vec2 b = p - 0.5 * max(p.x + p.y, 0.0);
            return sqrt(min(dot(a, a), dot(b, b))) * sign(p.x - p.y);
        }

        float reclySdStar(vec2 p) {
            float angle = atan(p.y, p.x);
            float ray = cos(angle * 5.0);
            float boundary = mix(0.43, 1.0, smoothstep(-0.35, 0.75, ray));
            return length(p) - boundary;
        }

        float reclySdPolygon(vec2 p, float count) {
            float distanceSquared = 100.0;
            float inside = 0.0;
            for (int i = 0; i < 12; i++) {
                float fi = float(i);
                if (fi < count) {
                    vec2 a = reclyMaskPoint(i);
                    vec2 b = (fi + 1.0 < count) ? reclyMaskPoint(i + 1) : reclyMaskPoint(0);
                    vec2 edge = b - a;
                    vec2 offset = p - a;
                    vec2 nearest = offset - edge * clamp(dot(offset, edge) / max(dot(edge, edge), 0.000001), 0.0, 1.0);
                    distanceSquared = min(distanceSquared, dot(nearest, nearest));
                    bool crosses = (a.y > p.y) != (b.y > p.y);
                    if (crosses && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                        inside = 1.0 - inside;
                    }
                }
            }
            return sqrt(distanceSquared) * (inside > 0.5 ? -1.0 : 1.0);
        }

        float reclyLayerMaskAlpha(vec2 uv) {
            if (uLayerMaskMeta.x < 0.5) return 1.0;
            float type = uLayerMaskMeta.x - 1.0;
            vec2 p = uv - uLayerMaskCenterSize.xy;
            float c = cos(uLayerMaskParams.x);
            float s = sin(uLayerMaskParams.x);
            p = mat2(c, -s, s, c) * p;
            vec2 halfSize = max(vec2(0.002), uLayerMaskCenterSize.zw * 0.5 + uLayerMaskParams.zz);
            float distanceToEdge;
            if (type < 0.5) {
                distanceToEdge = reclySdBox(p, halfSize);
            } else if (type < 1.5) {
                distanceToEdge = length(p) - min(halfSize.x, halfSize.y);
            } else if (type < 2.5) {
                distanceToEdge = (length(p / halfSize) - 1.0) * min(halfSize.x, halfSize.y);
            } else if (type < 3.5) {
                distanceToEdge = max(abs(p.x) - halfSize.x, p.y - halfSize.y);
            } else if (type < 4.5) {
                distanceToEdge = abs(p.x) - halfSize.x;
            } else if (type < 5.5) {
                vec2 normalized = vec2(p.x / halfSize.x, -p.y / halfSize.y);
                distanceToEdge = reclySdHeart(normalized) * min(halfSize.x, halfSize.y);
            } else if (type < 6.5) {
                distanceToEdge = reclySdStar(p / halfSize) * min(halfSize.x, halfSize.y);
            } else if (uLayerMaskMeta.z >= 3.0) {
                vec2 normalized = p / (halfSize * 2.0) + 0.5;
                distanceToEdge = reclySdPolygon(normalized, uLayerMaskMeta.z) * min(halfSize.x, halfSize.y) * 2.0;
            } else {
                distanceToEdge = reclySdBox(p, halfSize);
            }
            float feather = max(uLayerMaskParams.y, 0.0001);
            float shapeAlpha = 1.0 - smoothstep(-feather, feather, distanceToEdge);
            if (uLayerMaskMeta.y > 0.5) shapeAlpha = 1.0 - shapeAlpha;
            return mix(1.0, shapeAlpha, clamp(uLayerMaskParams.w, 0.0, 1.0));
        }
    """
}
