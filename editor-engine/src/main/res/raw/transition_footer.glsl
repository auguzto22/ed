void main() {
    // The first and last frames of every transition are exactly the outgoing and incoming shots.
    if (uProgress <= 0.0) {
        gl_FragColor = texture2D(uTexA, vUv);
    } else if (uProgress >= 1.0) {
        gl_FragColor = texture2D(uTexB, vUv);
    } else {
        gl_FragColor = reclyTransition(vUv, uProgress);
    }
}
