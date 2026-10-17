#!/usr/bin/env bash
# Ensaio de ponta a ponta do motor de velocidade com vídeo e áudio de verdade.
#
# Gera uma cena com movimento real (câmera em panorâmica sobre textura + objeto que se move por
# cima, com oclusão) a 120 fps e um tom com bipes a cada segundo; entrega ao motor só 1 a cada 4
# quadros (30 fps) e roda os casos abaixo pelo mesmo caminho da exportação (mapa de tempo →
# agendador → GPU → áudio). Cada resultado é codificado em MP4 (H.264 + AAC), medido com ffprobe
# e conferido pela regra da exportação. Na câmera lenta 0,25× os quadros que o motor inventa
# são comparados com os quadros reais a 120 fps que ele nunca viu.
#
# Requer: ffmpeg/ffprobe, python3 + numpy, Mesa (EGL surfaceless) e cargo.
# Uso: tools/speed-e2e.sh [pasta de trabalho]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$(mktemp -d)}"
mkdir -p "$WORK"
W=480; H=270; SECONDS_LEN=2
cd "$ROOT/blitz-speed"
cargo test --offline --release --features gpu-tests --no-run -q 2>/dev/null

echo "== Fonte: ${W}x${H}, 120 fps, ${SECONDS_LEN}s, em $WORK"
ffmpeg -v error -y \
  -f lavfi -i "mandelbrot=size=1600x900:rate=120:start_scale=0.9:end_scale=0.6:bailout=10" \
  -f lavfi -i "testsrc2=size=150x150:rate=120" \
  -f lavfi -i "sine=frequency=440:beep_factor=3:sample_rate=48000" \
  -filter_complex "[0:v]crop=${W}:${H}:x='300+t*260':y='200+30*sin(t*2.2)',format=rgba[bg];[1:v]format=rgba[fg];[bg][fg]overlay=x='360-t*170':y='60+40*sin(t*3)':shortest=1,format=rgba[v]" \
  -map "[v]" -map 2:a -t $SECONDS_LEN -c:v ffv1 -c:a pcm_f32le "$WORK/source120.mkv"
ffmpeg -v error -y -i "$WORK/source120.mkv" -f rawvideo -pix_fmt rgba "$WORK/truth120.rgba"
ffmpeg -v error -y -i "$WORK/source120.mkv" -vf "select='not(mod(n\,4))'" -vsync 0 -f rawvideo -pix_fmt rgba "$WORK/input30.rgba"
ffmpeg -v error -y -i "$WORK/source120.mkv" -vn -ac 2 -ar 48000 -f f32le "$WORK/audio.f32"
FRAMES=$(( $(stat -c %s "$WORK/input30.rgba") / (W * H * 4) ))
# Fonte do Blitz: o arquivo de 30 fps (também em MP4 para conferir que decodifica).
ffmpeg -v error -y -f rawvideo -pix_fmt rgba -s ${W}x${H} -r 30 -i "$WORK/input30.rgba" -f f32le -ar 48000 -ac 2 -i "$WORK/audio.f32" \
  -c:v libx264 -pix_fmt yuv420p -crf 16 -c:a aac -shortest "$WORK/source30.mp4"

run_case() {
  local name=$1; shift
  local dir="$WORK/$name"; mkdir -p "$dir"
  echo "$W $H 30 $FRAMES" > "$dir/meta.txt"
  ln -sf "$WORK/input30.rgba" "$dir/input.rgba"
  ln -sf "$WORK/audio.f32" "$dir/audio.f32"
  env E2E_DIR="$dir" "$@" cargo test --offline --release --features gpu-tests -q gpu::e2e::render_real_video -- --ignored --nocapture 2>&1 \
    | grep -E "vídeo:|áudio:|panicked|FAILED" | sed "s/^/  [$name] /"
  read -r OUT_FRAMES OUT_FPS EXPECTED_US < "$dir/output.txt"
  ffmpeg -v error -y -f rawvideo -pix_fmt rgba -s ${W}x${H} -r "$OUT_FPS" -i "$dir/output.rgba" \
    -f f32le -ar 48000 -ac 2 -i "$dir/audio_out.f32" -c:v libx264 -pix_fmt yuv420p -crf 16 -c:a aac "$dir/$name.mp4"
  local video_us audio_us
  video_us=$(ffprobe -v error -select_streams v:0 -show_entries stream=duration -of csv=p=0 "$dir/$name.mp4" | awk '{printf "%d", $1*1000000}')
  audio_us=$(ffprobe -v error -select_streams a:0 -show_entries stream=duration -of csv=p=0 "$dir/$name.mp4" | awk '{printf "%d", $1*1000000}')
  echo "$EXPECTED_US $video_us $audio_us ${OUT_FPS%.*}" > "$dir/probe.txt"
  echo "  [$name] MP4: vídeo ${video_us} µs, áudio ${audio_us} µs, esperado ${EXPECTED_US} µs, ${OUT_FRAMES} quadros"
  E2E_DIR="$dir" cargo test --offline --release --features gpu-tests -q gpu::e2e::validate_real_export -- --ignored --nocapture 2>&1 \
    | grep -E "veredito|panicked" | sed "s/^/  [$name] /"
}

echo "== Câmera lenta 0,25× (comparada com os quadros reais a 120 fps)"
run_case slow_repeat E2E_SPEED=0.25 E2E_SYNTH=0
run_case slow_blend E2E_SPEED=0.25 E2E_SYNTH=1
run_case slow_flow E2E_SPEED=0.25 E2E_SYNTH=2
python3 "$ROOT/tools/speed_e2e_metrics.py" "$WORK" $W $H slow_repeat slow_blend slow_flow

echo "== Corte no meio do último quadro (0,25×): a grade para no fim do clipe"
run_case slow_trim E2E_SPEED=0.25 E2E_SYNTH=2 E2E_TRIM_US=25000

echo "== Rampa (preset Impacto), optical flow + motion blur 180°"
run_case ramp_flow_blur E2E_PRESET=8 E2E_SYNTH=2 E2E_SHUTTER=180
echo "== Avanço 4× com acumulação (obturador 360°): amostras nítidas × amostras cobrindo o intervalo"
run_case fast_plain E2E_SPEED=4 E2E_SYNTH=1 E2E_SHUTTER=360 E2E_FILL=0
run_case fast_accumulate E2E_SPEED=4 E2E_SYNTH=1 E2E_SHUTTER=360
python3 "$ROOT/tools/speed_e2e_metrics.py" --exposure "$WORK" $W $H 4 360 fast_plain fast_accumulate
echo "== 0,5× com tom acompanhando a velocidade"
run_case slow_pitch E2E_SPEED=0.5 E2E_SYNTH=2 E2E_PITCH=0
python3 "$ROOT/tools/speed_e2e_metrics.py" --sheets "$WORK" $W $H ramp_flow_blur fast_accumulate
echo "Pronto: $WORK"
