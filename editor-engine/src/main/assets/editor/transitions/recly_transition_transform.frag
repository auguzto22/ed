// Camera moves: slides, pushes, zooms and rotations. Geometry is computed in aspect-correct space
// so rotations never shear, motion blur follows the actual movement, and zooms fill the frame
// with mirrored edges instead of black bars.

const float PI = 3.14159265;

float aspectRatio() { return uResolution.x / max(uResolution.y, 1.0); }
vec2 pixel() { return 1.0 / max(uResolution, vec2(1.0)); }
float bell(float t) { return sin(PI * clamp(t, 0.0, 1.0)); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 mirrorUv(vec2 uv) { return 1.0 - abs(1.0 - mod(uv, 2.0)); }

// 1 inside the unit square; the edge is anti-aliased over one pixel centred on it.
float insideAA(vec2 uv) {
    vec2 e = 0.5 * pixel();
    vec2 lo = smoothstep(-e, e, uv);
    vec2 hi = smoothstep(-e, e, 1.0 - uv);
    return lo.x * lo.y * hi.x * hi.y;
}

// Average along [delta] (uv units) around uv; per-pixel jitter turns banding into fine grain.
vec4 motionBlur(sampler2D tex, vec2 uv, vec2 delta, bool mirrored) {
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 12; i++) {
        float k = (float(i) + 0.5 + jitter) / 12.0 - 0.5;
        vec2 q = uv + delta * k;
        sum += texture2D(tex, mirrored ? mirrorUv(q) : clamp(q, 0.0, 1.0));
    }
    return sum / 12.0;
}

// Zoom blur towards the centre plus an optional rotational component, both around 0.5.
vec4 radialBlur(sampler2D tex, vec2 uv, float zoom, float spin) {
    float a = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(a, 1.0);
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 18; i++) {
        float k = (float(i) + 0.5 + jitter) / 18.0 - 0.5;
        float s = 1.0 - zoom * k;
        float r = spin * k;
        vec2 q = mat2(cos(r), sin(r), -sin(r), cos(r)) * p * s;
        sum += texture2D(tex, mirrorUv(q / vec2(a, 1.0) + 0.5));
    }
    return sum / 18.0;
}

// Scale (around the centre) and rotation in aspect-correct space; >1 scale magnifies.
vec2 cameraUv(vec2 uv, float scale, float angle) {
    float a = aspectRatio();
    vec2 p = (uv - 0.5) * vec2(a, 1.0) / scale;
    p = mat2(cos(angle), sin(angle), -sin(angle), cos(angle)) * p;
    return p / vec2(a, 1.0) + 0.5;
}

vec2 directionFor(float m) {
    if (m < 0.5) return vec2(-1.0, 0.0);
    if (m < 1.5) return vec2(1.0, 0.0);
    if (m < 2.5) return vec2(0.0, 1.0);
    return vec2(0.0, -1.0);
}

// A continuous camera zoom across the cut: A is scaled by from^(2t), B arrives at 1 from to^(-1)
// and both share the same velocity, so the move reads as one push-in or pull-back.
vec4 zoomThrough(vec2 uv, float t, float factor, float blurAmount, float overlap) {
    float logF = log(factor);
    float speed = bell(t);
    float blur = blurAmount * speed * speed;
    vec4 colA = vec4(0.0);
    vec4 colB = vec4(0.0);
    float w = smoothstep(0.5 - overlap, 0.5 + overlap, t);
    if (w < 1.0) {
        float sA = exp(logF * 2.0 * t);
        vec2 q = cameraUv(uv, sA, 0.0);
        colA = blur > 0.002 ? radialBlur(uTexA, q, blur * sign(logF), 0.0) : texture2D(uTexA, mirrorUv(q));
    }
    if (w > 0.0) {
        float sB = exp(logF * (2.0 * t - 2.0));
        vec2 q = cameraUv(uv, sB, 0.0);
        colB = blur > 0.002 ? radialBlur(uTexB, q, blur * sign(logF), 0.0) : texture2D(uTexB, mirrorUv(q));
    }
    return mix(colA, colB, w);
}

// Rotation carried across the cut: A turns through the first half of [turns] and B finishes it.
// [zoomIn] adds a continuous push-in (A grows, B arrives smaller); [zoomDip] pulls the camera back
// at the cut so the spin reads as a whirl.
vec4 spinThrough(vec2 uv, float t, float turns, float zoomDip, float zoomIn) {
    float angle = turns * 2.0 * PI;
    float speed = bell(t);
    float dip = 1.0 - zoomDip * speed;
    float logZ = log(1.0 + zoomIn);
    float w = smoothstep(0.42, 0.58, t);
    float spinBlur = angle * 0.09 * speed * speed;
    float zoomBlur = zoomIn * 0.35 * speed * speed;
    vec4 colA = vec4(0.0);
    vec4 colB = vec4(0.0);
    if (w < 1.0) colA = radialBlur(uTexA, cameraUv(uv, dip * exp(logZ * 2.0 * t), angle * t), zoomBlur, spinBlur);
    if (w > 0.0) colB = radialBlur(uTexB, cameraUv(uv, dip * exp(logZ * (2.0 * t - 2.0)), angle * (t - 1.0)), zoomBlur, spinBlur);
    return mix(colA, colB, w);
}

vec4 reclyTransition(vec2 uv, float progress) {
    float t = clamp(progress, 0.0, 1.0);
    float mode = floor(p_mode + 0.5);

    // SLIDE 0..3: the incoming clip slides over the outgoing one, casting a soft shadow on it.
    if (mode < 3.5) {
        vec2 dir = directionFor(mode);
        vec2 offset = -dir * (1.0 - t);
        vec2 uvB = uv - offset;
        float coverB = insideAA(uvB);
        vec2 blurDelta = dir * 0.06 * bell(t);
        vec4 colB = motionBlur(uTexB, uvB, blurDelta, false);
        vec4 colA = texture2D(uTexA, uv);
        // B casts a soft shadow on A just ahead of its leading edge.
        float dist = length(uvB - clamp(uvB, 0.0, 1.0));
        float shadow = 0.4 * exp(-dist * 24.0) * (1.0 - coverB) * smoothstep(0.0, 0.05, t) * smoothstep(0.0, 0.05, 1.0 - t);
        colA.rgb *= 1.0 - shadow;
        return mix(colA, colB, coverB);
    }

    // PUSH 4..7: the incoming clip pushes the outgoing one out; both carry motion blur.
    if (mode < 7.5) {
        vec2 dir = directionFor(mode - 4.0);
        vec2 uvA = uv - dir * t;
        vec2 uvB = uv + dir * (1.0 - t);
        vec2 blurDelta = dir * 0.1 * bell(t);
        float coverB = insideAA(uvB);
        vec4 colB = coverB > 0.0 ? motionBlur(uTexB, uvB, blurDelta, false) : vec4(0.0);
        vec4 colA = coverB < 1.0 ? motionBlur(uTexA, uvA, blurDelta, false) * insideAA(uvA) : vec4(0.0);
        return mix(colA, colB, coverB);
    }

    if (mode < 8.5) return zoomThrough(uv, t, 1.7, 0.22, 0.07);     // zoom in
    if (mode < 9.5) return zoomThrough(uv, t, 1.0 / 1.7, 0.22, 0.07); // zoom out
    if (mode < 10.5) return zoomThrough(uv, t, 3.2, 0.42, 0.06);    // zoom through
    if (mode < 11.5) {
        // Punch zoom: a short, hard push with a fast cut and little blur.
        float k = t * t * (3.0 - 2.0 * t);
        return zoomThrough(uv, k, 1.35, 0.08, 0.025);
    }
    if (mode < 12.5) return zoomThrough(uv, t, 1.18, 0.05, 0.22);   // smooth zoom

    if (mode < 13.5) return spinThrough(uv, t, 0.125, 0.0, 0.6);    // zoom rotate
    if (mode < 14.5) return spinThrough(uv, t, 0.5, 0.0, 0.0);      // spin
    if (mode < 15.5) return spinThrough(uv, t, 1.0, 0.3, 0.0);      // spin zoom

    // ROTATE PUSH 16: A and B sit side by side on a strip that pans left while the camera tilts
    // and pushes in just enough to keep the frame covered.
    float a = aspectRatio();
    float speed = bell(t);
    float tilt = 0.17 * speed;
    float cover = cos(tilt) + a * sin(tilt);
    vec2 p = (uv - 0.5) * vec2(a, 1.0) / cover;
    p = mat2(cos(tilt), sin(tilt), -sin(tilt), cos(tilt)) * p;
    float jitter = hash12(gl_FragCoord.xy) - 0.5;
    float blur = a * 0.12 * speed;
    vec4 sum = vec4(0.0);
    for (int i = 0; i < 12; i++) {
        float x = p.x + a * t + blur * ((float(i) + 0.5 + jitter) / 12.0 - 0.5);
        vec2 q = vec2(x, p.y);
        if (x < a * 0.5) {
            sum += texture2D(uTexA, mirrorUv(q / vec2(a, 1.0) + 0.5));
        } else {
            sum += texture2D(uTexB, mirrorUv((q - vec2(a, 0.0)) / vec2(a, 1.0) + 0.5));
        }
    }
    return sum / 12.0;
}
