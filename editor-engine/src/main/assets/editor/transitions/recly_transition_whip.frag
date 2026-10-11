// Whip pans. The picture accelerates out, the cut happens at peak speed under heavy motion blur,
// and the next shot decelerates into place, so the move reads as a single camera whip. Blur
// length follows the actual velocity and the picture repeats along the move like a film strip
// (upright copies, never flipped), so no gap ever opens.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 wrapUv(vec2 uv) { return fract(uv); }

vec4 streak(sampler2D tex, vec2 uv, vec2 delta) {
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int i = 0; i < 16; i++) {
        float k = (float(i) + 0.5 + jitter) / 16.0 - 0.5;
        float w = 1.0 - abs(k);
        sum += texture2D(tex, wrapUv(uv + delta * k)) * w;
        weight += w;
    }
    return sum / weight;
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);
    float asp = aspectRatio();
    float strength = clamp(p_blur, 0.1, 2.5);

    // One frame of travel in the direction the picture moves (uv units).
    vec2 span = vec2(-1.0, 0.0);
    if (mode > 0.5 && mode < 1.5) span = vec2(1.0, 0.0);
    else if (mode > 1.5 && mode < 2.5) span = vec2(0.0, 1.0);
    else if (mode > 2.5 && mode < 3.5) span = vec2(0.0, -1.0);
    else if (mode > 3.5 && mode < 4.5) {
        vec2 d = normalize(vec2(-1.0, 0.45));
        span = vec2(d.x / asp, d.y) * 1.2;
    }

    if (mode > 4.5) {
        // Fast pan: both shots on one strip, constant speed, long shutter.
        vec2 d = vec2(-1.0, 0.0);
        vec2 blur = d * 0.22 * strength * sin(PI * t);
        vec2 uvA = uv - d * t;
        vec2 uvB = uv + d * (1.0 - t);
        bool inB = uvB.x >= 0.0 && uvB.x <= 1.0;
        return inB ? streak(uTexB, uvB, blur) : streak(uTexA, uvA, blur);
    }

    // Offset accelerates as (2t)^2 out of A and decelerates the same way into B.
    float h = 2.0 * t;
    float offsetA = h * h;
    float offsetB = (2.0 - h) * (2.0 - h);
    float velocity = 4.0 * (t < 0.5 ? h : 2.0 - h);
    vec2 blur = span * velocity * 0.06 * strength;
    float w = smoothstep(0.47, 0.53, t);
    vec4 colA = w < 1.0 ? streak(uTexA, uv - span * offsetA, blur) : vec4(0.0);
    vec4 colB = w > 0.0 ? streak(uTexB, uv + span * offsetB, blur) : vec4(0.0);
    return mix(colA, colB, w);
}
