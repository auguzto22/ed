// Máximo de velocidade da vizinhança (passe separável): o blur de um objeto rápido precisa
// alcançar o fundo parado ao redor dele.
uniform sampler2D uFlow;
uniform vec2 uStep;

void main() {
    vec2 best = unpackFlow(texture2D(uFlow, vUv));
    float bestLength = dot(best, best);
    for (int i = -4; i <= 4; i++) {
        vec2 f = unpackFlow(texture2D(uFlow, vUv + float(i) * uStep));
        float l = dot(f, f);
        if (l > bestLength) {
            best = f;
            bestLength = l;
        }
    }
    gl_FragColor = packFlow(best);
}
