precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform float uLutSize;
uniform float uLutStrength;
uniform vec2 uTexel;
uniform vec3 uLight;
uniform vec3 uDetail;
uniform vec4 uCurve;
uniform float uCurveEnd;
uniform vec4 uChannel0;
uniform vec4 uChannel1;
uniform vec4 uChannel2;
uniform vec3 uChannelEnds;
uniform vec3 uHsl0;
uniform vec3 uHsl1;
uniform vec3 uHsl2;
uniform vec3 uHsl3;
uniform vec3 uHsl4;
uniform vec3 uHsl5;
uniform vec3 uHsl6;
uniform vec3 uHsl7;
uniform vec4 uMask;
uniform vec4 uMaskTransform;
uniform float uMaskOpacity;
uniform vec4 uChroma;
uniform vec3 uKeyEdge;
uniform vec3 uBackground;
uniform float uOpacity;
uniform float uPreserveAlpha;
uniform float uTime;
uniform vec4 uFilterAdjust;
uniform vec4 uFilterColor;
uniform float uFilterStrength;
uniform float uFilterMode;
uniform vec4 uClipAdjust;
uniform vec4 uClipColor;
varying vec2 vUv;

const float hslEpsilon = 1e-10;

vec3 rgbToHcv(vec3 rgb) {
    vec4 p = (rgb.g < rgb.b) ? vec4(rgb.bg, -1.0, 2.0 / 3.0) : vec4(rgb.gb, 0.0, -1.0 / 3.0);
    vec4 q = (rgb.r < p.x) ? vec4(p.xyw, rgb.r) : vec4(rgb.r, p.yzx);
    float c = q.x - min(q.w, q.y);
    float h = abs((q.w - q.y) / (6.0 * c + hslEpsilon) + q.z);
    return vec3(h, c, q.x);
}

vec3 rgbToHsl(vec3 rgb) {
    vec3 hcv = rgbToHcv(rgb);
    float l = hcv.z - hcv.y * 0.5;
    float s = hcv.y / (1.0 - abs(l * 2.0 - 1.0) + hslEpsilon);
    return vec3(hcv.x, s, l);
}

vec3 hueToRgb(float hue) {
    float r = abs(hue * 6.0 - 3.0) - 1.0;
    float g = 2.0 - abs(hue * 6.0 - 2.0);
    float b = 2.0 - abs(hue * 6.0 - 4.0);
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

vec3 hslToRgb(vec3 hsl) {
    vec3 rgb = hueToRgb(hsl.x);
    float c = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
    return (rgb - 0.5) * c + hsl.z;
}

// Mirrors Media3's Brightness -> Contrast -> HslAdjustment -> RgbAdjustment
// chain. Keeping the chain in this resident preview shader makes every value a
// uniform while export retains the stock Media3 implementations.
vec3 applyClipAdjustments(vec3 rgb) {
    rgb += uClipAdjust.x;
    float contrastFactor = (1.0 + uClipAdjust.y) / (1.0001 - uClipAdjust.y);
    rgb = (rgb - 0.5) * contrastFactor + 0.5;
    vec3 hsl = rgbToHsl(rgb);
    hsl.x = mod(hsl.x + uClipColor.x, 1.0);
    hsl.y = clamp(hsl.y + uClipAdjust.z, 0.0, 1.0);
    hsl.z = clamp(hsl.z + uClipAdjust.w, 0.0, 1.0);
    return hslToRgb(hsl) * uClipColor.yzw;
}

vec3 applyFilter(vec3 source) {
    if (uFilterStrength <= 0.0) return source;
    vec3 filtered = source;
    if (uFilterMode > 1.5) {
        filtered = 1.0 - filtered;
    } else {
        filtered += uFilterAdjust.x;
        filtered = (filtered - 0.5) * (1.0 + uFilterAdjust.y) + 0.5;
        float gray = dot(filtered, vec3(0.2126, 0.7152, 0.0722));
        filtered = mix(vec3(gray), filtered, max(0.0, 1.0 + uFilterAdjust.z));
        float c = cos(uFilterColor.x); float s = sin(uFilterColor.x);
        vec3 axis = normalize(vec3(1.0));
        filtered = filtered * c + cross(axis, filtered) * s + axis * dot(axis, filtered) * (1.0 - c);
        filtered = filtered * uFilterColor.yzw + uFilterAdjust.w;
    }
    return mix(source, clamp(filtered, 0.0, 1.0), uFilterStrength);
}

float curve(float v, vec4 points, float end) {
    float t = clamp(v, 0.0, 1.0) * 4.0;
    if (t < 1.0) return mix(points.x, points.y, t);
    if (t < 2.0) return mix(points.y, points.z, t - 1.0);
    if (t < 3.0) return mix(points.z, points.w, t - 2.0);
    return mix(points.w, end, t - 3.0);
}

float hueWeight(float hue, float center) {
    float d = abs(hue - center);
    return 1.0 - smoothstep(0.0, 0.12, min(d, 1.0 - d));
}
vec3 adjustHsl(vec3 rgb) {
    float hi = max(rgb.r, max(rgb.g, rgb.b));
    float lo = min(rgb.r, min(rgb.g, rgb.b));
    float delta = hi - lo;
    if (delta < 0.00001) return rgb;
    float light = (hi + lo) * 0.5;
    float saturation = delta / max(0.00001, 1.0 - abs(2.0 * light - 1.0));
    float hue;
    if (hi == rgb.r) hue = mod((rgb.g - rgb.b) / delta, 6.0) / 6.0;
    else if (hi == rgb.g) hue = ((rgb.b - rgb.r) / delta + 2.0) / 6.0;
    else hue = ((rgb.r - rgb.g) / delta + 4.0) / 6.0;
    float w0 = hueWeight(hue, 0.0); float w1 = hueWeight(hue, 1.0 / 12.0);
    float w2 = hueWeight(hue, 1.0 / 6.0); float w3 = hueWeight(hue, 1.0 / 3.0);
    float w4 = hueWeight(hue, 0.5); float w5 = hueWeight(hue, 2.0 / 3.0);
    float w6 = hueWeight(hue, 0.75); float w7 = hueWeight(hue, 5.0 / 6.0);
    vec3 adjustment = (w0*uHsl0 + w1*uHsl1 + w2*uHsl2 + w3*uHsl3 + w4*uHsl4 + w5*uHsl5 + w6*uHsl6 + w7*uHsl7)
        / max(0.0001, w0+w1+w2+w3+w4+w5+w6+w7);
    hue = fract(hue + adjustment.x);
    saturation = clamp(saturation * (1.0 + adjustment.y), 0.0, 1.0);
    light = clamp(light + adjustment.z * 0.5, 0.0, 1.0);
    float chroma = (1.0 - abs(2.0 * light - 1.0)) * saturation;
    vec3 base = clamp(abs(mod(hue * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return (base - 0.5) * chroma + light;
}

vec3 lookup(vec3 rgb) {
    vec3 pos = clamp(rgb, 0.0, 1.0) * (uLutSize - 1.0);
    float slice = min(floor(pos.r), uLutSize - 2.0);
    vec2 lower = vec2((pos.b + 0.5) / uLutSize, (slice * uLutSize + pos.g + 0.5) / (uLutSize * uLutSize));
    vec3 a = texture2D(uLut, lower).rgb;
    vec3 b = texture2D(uLut, lower + vec2(0.0, 1.0 / uLutSize)).rgb;
    return mix(a, b, pos.r - slice);
}

void main() {
    vec4 source = texture2D(uTexSampler, vUv);
    vec3 rgb = applyClipAdjustments(source.rgb);
    vec3 nearPixels = applyClipAdjustments(texture2D(uTexSampler, vUv + vec2(uTexel.x, 0.0)).rgb)
        + applyClipAdjustments(texture2D(uTexSampler, vUv - vec2(uTexel.x, 0.0)).rgb)
        + applyClipAdjustments(texture2D(uTexSampler, vUv + vec2(0.0, uTexel.y)).rgb)
        + applyClipAdjustments(texture2D(uTexSampler, vUv - vec2(0.0, uTexel.y)).rgb);
    rgb += (rgb - nearPixels * 0.25) * uDetail.z * 1.5;
    rgb *= exp2(uLight.x);
    // Media3 effects receive linear RGB. Tone controls operate in display space.
    rgb = pow(max(rgb, vec3(0.0)), vec3(1.0 / 2.2));
    rgb = applyFilter(rgb);
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb += uLight.y * 0.35 * pow(1.0 - clamp(luma, 0.0, 1.0), 2.0);
    rgb += uLight.z * 0.35 * pow(clamp(luma, 0.0, 1.0), 2.0);
    if (uLutSize > 1.0) rgb = mix(rgb, lookup(rgb), uLutStrength);
    rgb = adjustHsl(clamp(rgb, 0.0, 1.0));
    rgb = vec3(curve(rgb.r, uCurve, uCurveEnd), curve(rgb.g, uCurve, uCurveEnd), curve(rgb.b, uCurve, uCurveEnd));
    rgb = vec3(curve(rgb.r, uChannel0, uChannelEnds.r), curve(rgb.g, uChannel1, uChannelEnds.g), curve(rgb.b, uChannel2, uChannelEnds.b));
    float radius = length((vUv - 0.5) * 1.414);
    rgb *= 1.0 - uDetail.x * smoothstep(0.28, 0.95, radius);
    float noise = fract(sin(dot(vUv + mod(uTime, 97.0), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    rgb += noise * uDetail.y * 0.16;
    float alpha = source.a * uOpacity;
    if (uMask.x > 0.5) {
        float distanceToEdge;
        vec2 q = vUv - 0.5 - uMaskTransform.xy;
        float c = cos(uMaskTransform.z); float s = sin(uMaskTransform.z);
        q = mat2(c, -s, s, c) * q;
        q.x /= uMaskTransform.w;
        if (uMask.x < 1.5) distanceToEdge = length(q * vec2(uTexel.y / uTexel.x, 1.0)) - uMask.y * 0.5;
        else if (uMask.x < 2.5) distanceToEdge = max(abs(q.x), abs(q.y)) - uMask.y * 0.5;
        else if (uMask.x < 3.5) distanceToEdge = abs(q.y) - uMask.y;
        else if (uMask.x < 4.5) distanceToEdge = q.y - (uMask.y - 0.5);
        else if (uMask.x < 5.5) distanceToEdge = uMask.y * 0.25 - abs(q.x);
        else if (uMask.x < 6.5) distanceToEdge = abs(q.x) - uMask.y * 0.25;
        else if (uMask.x < 7.5) distanceToEdge = length(q) - uMask.y * 0.5;
        else distanceToEdge = (abs(q.x) + abs(q.y) - uMask.y * 0.5) * 0.7071;
        float maskAlpha = 1.0 - smoothstep(-uMask.z, uMask.z, distanceToEdge);
        alpha *= mix(1.0, mix(maskAlpha, 1.0 - maskAlpha, uMask.w), uMaskOpacity);
    }
    if (uChroma.w > 0.0) {
        vec3 original = pow(max(source.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float threshold = max(0.0, uChroma.w + uKeyEdge.z);
        float matte = smoothstep(threshold, threshold + uKeyEdge.x, distance(original, uChroma.xyz));
        alpha *= matte;
        vec3 dominance = max(uChroma.xyz - max(uChroma.yzx, uChroma.zxy), vec3(0.0));
        dominance /= max(0.0001, max(dominance.r, max(dominance.g, dominance.b)));
        rgb = mix(rgb, min(rgb, vec3(dot(rgb, vec3(0.2126, 0.7152, 0.0722)))), dominance * uKeyEdge.y * (1.0 - matte));
    }
    rgb = pow(clamp(rgb, 0.0, 1.0), vec3(2.2));
    alpha = clamp(alpha, 0.0, 1.0);
    gl_FragColor = uPreserveAlpha > 0.5 ? vec4(rgb, alpha) : vec4(mix(uBackground, rgb, alpha), 1.0);
}
