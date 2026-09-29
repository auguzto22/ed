attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
    gl_Position = aFramePosition;
    vUv = (aFramePosition.xy + 1.0) * 0.5;
}
