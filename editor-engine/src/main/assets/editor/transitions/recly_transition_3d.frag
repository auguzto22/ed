vec4 reclyTransition(vec2 uv, float progress) {
    if (progress <= 0.0) return texture2D(uTexA, uv);
    if (progress >= 1.0) return texture2D(uTexB, uv);

    float mode = floor(p_mode + 0.5);
    float t = clamp(progress, 0.0, 1.0);
    float persp = (p_perspective > 0.1 ? p_perspective : 1.0) * 0.4;

    if (mode < 0.5) {
        // Flip Horizontal (Y-axis 3D perspective card flip)
        if (t < 0.5) {
            float rot = t * 3.14159265;
            float cosRot = cos(rot);
            float x = (uv.x - 0.5) / max(cosRot, 0.001);
            float y = (uv.y - 0.5) / (1.0 + x * sin(rot) * persp);
            vec2 p = vec2(x, y) + 0.5;
            if (p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0) {
                return texture2D(uTexA, p);
            }
            return texture2D(uTexB, uv);
        } else {
            float rot = (1.0 - t) * 3.14159265;
            float cosRot = cos(rot);
            float x = (uv.x - 0.5) / max(cosRot, 0.001);
            float y = (uv.y - 0.5) / (1.0 - x * sin(rot) * persp);
            vec2 p = vec2(x, y) + 0.5;
            if (p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0) {
                return texture2D(uTexB, p);
            }
            return texture2D(uTexA, uv);
        }
    } else if (mode < 1.5) {
        // Flip Vertical (X-axis 3D perspective card flip)
        if (t < 0.5) {
            float rot = t * 3.14159265;
            float cosRot = cos(rot);
            float y = (uv.y - 0.5) / max(cosRot, 0.001);
            float x = (uv.x - 0.5) / (1.0 + y * sin(rot) * persp);
            vec2 p = vec2(x, y) + 0.5;
            if (p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0) {
                return texture2D(uTexA, p);
            }
            return texture2D(uTexB, uv);
        } else {
            float rot = (1.0 - t) * 3.14159265;
            float cosRot = cos(rot);
            float y = (uv.y - 0.5) / max(cosRot, 0.001);
            float x = (uv.x - 0.5) / (1.0 - y * sin(rot) * persp);
            vec2 p = vec2(x, y) + 0.5;
            if (p.x >= 0.0 && p.x <= 1.0 && p.y >= 0.0 && p.y <= 1.0) {
                return texture2D(uTexB, p);
            }
            return texture2D(uTexA, uv);
        }
    } else if (mode < 3.5) {
        // Cube Left / Right: each source occupies one projected face. Never sample
        // clamped out-of-range UVs; doing so repeats a single edge across a face.
        float leftward = mode < 2.5 ? 1.0 : 0.0;
        float split = leftward > 0.5 ? 1.0 - t : t;
        bool firstFace = uv.x < split;
        float faceWidth = max(firstFace ? split : 1.0 - split, 0.001);
        float localX = firstFace ? uv.x / faceWidth : (uv.x - split) / faceWidth;
        float depth = (firstFace ? t : 1.0 - t) * persp;
        float edge = firstFace ? localX : 1.0 - localX;
        float verticalScale = 1.0 - depth * edge;
        vec2 faceUv = vec2(clamp(localX, 0.0, 1.0),
            clamp((uv.y - 0.5) / max(verticalScale, 0.6) + 0.5, 0.0, 1.0));
        bool sampleA = leftward > 0.5 ? firstFace : !firstFace;
        vec4 color = sampleA ? texture2D(uTexA, faceUv) : texture2D(uTexB, faceUv);
        float shade = 1.0 - depth * edge * 0.35;
        return color * vec4(vec3(shade), 1.0);
    } else if (mode < 4.5) {
        // Page Turn
        float curl = uv.x - (1.0 - t * 1.3);
        if (curl > 0.0) {
            float shadow = smoothstep(0.0, 0.2, curl);
            vec4 colB = texture2D(uTexB, uv);
            return colB * (0.6 + 0.4 * shadow);
        }
        return texture2D(uTexA, uv);
    } else {
        // Perspective Slide: the outgoing frame narrows while the incoming frame
        // expands from the right edge.
        float widthA = max(1.0 - t * 0.82, 0.18);
        float widthB = max(t, 0.001);
        float split = 1.0 - t;
        if (uv.x < split) {
            vec2 uvA = vec2(uv.x / max(split, 0.001) * widthA, uv.y);
            float shade = 1.0 - t * 0.35;
            return texture2D(uTexA, clamp(uvA, 0.0, 1.0)) * vec4(vec3(shade), 1.0);
        }
        vec2 uvB = vec2((uv.x - split) / widthB, uv.y);
        float shade = 0.65 + t * 0.35;
        return texture2D(uTexB, clamp(uvB, 0.0, 1.0)) * vec4(vec3(shade), 1.0);
    }
}
