uniform sampler2D uSource;

void main() {
    gl_FragColor = texture2D(uSource, vUv);
}
