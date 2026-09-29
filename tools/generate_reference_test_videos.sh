#!/bin/sh
set -eu

OUT=${1:-reference-test-videos}
mkdir -p "$OUT"

# Small deterministic fixtures. They contain generated colors/tones only; no third-party assets.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=red:s=640x360:r=30:d=2" -f lavfi -i "color=c=blue:s=640x360:r=30:d=2" \
  -f lavfi -i "sine=frequency=120:sample_rate=48000:duration=4" \
  -filter_complex "[0:v][1:v]concat=n=2:v=1:a=0[v]" -map "[v]" -map 2:a -c:v libx264 -pix_fmt yuv420p -c:a aac "$OUT/hard-cut-30fps.mp4"

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=s=360x640:r=60:d=4" -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=4" \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=white@1:t=fill:enable='between(t,1.5,1.58)'" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac "$OUT/portrait-flash-60fps.mp4"

ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=s=640x360:r=30:d=5" \
  -vf "zoompan=z='1+0.15*max(0,1-abs(on-75)/9)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=640x360:fps=30" \
  -c:v libx264 -pix_fmt yuv420p "$OUT/punch-zoom.mp4"

# Negative control: continuous fast pan must not become a sequence of hard cuts.
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=s=800x360:r=30:d=5" \
  -vf "crop=640:360:x='160*t/5':y=0" -c:v libx264 -pix_fmt yuv420p "$OUT/continuous-pan-no-cut.mp4"

printf '%s\n' "Fixtures written to $OUT"
