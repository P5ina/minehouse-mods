#!/usr/bin/env bash
# Encodes the menu intro for MineHouse Menu (see src/main/java/pro/minehouse/menu/intro/).
#   intro/encode.sh <video> <music> <out-dir> [version]
# Writes intro.mp4, intro.ogg and intro.json into <out-dir>; they go to config/minehouse-intro/.
#
# The in-game player decodes H.264 in pure Java on several threads, one GOP per thread,
# so the stream must have a fixed GOP (-g/-keyint_min, no scene-cut keyframes) and no
# B-frames (jcodec returns frames in decode order). BT.709 limited range, like Yuv.java.
set -euo pipefail
video=$1 music=$2 out=$3 version=${4:-$(date -u +%Y%m%d%H%M)}
fps=24 gop=12 w=1920 h=1080
mkdir -p "$out"
ffmpeg -v error -y -i "$video" -an \
  -vf "fps=$fps,scale=$w:$h:flags=lanczos:out_color_matrix=bt709:out_range=tv,format=yuv420p" \
  -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv \
  -c:v libx264 -profile:v high -preset slow -crf 20 -maxrate 10M -bufsize 20M \
  -g $gop -keyint_min $gop -sc_threshold 0 -bf 0 -movflags +faststart "$out/intro.mp4"
ffmpeg -v error -y -i "$music" -vn -ac 2 -ar 48000 -c:a libvorbis -q:a 6 "$out/intro.ogg"
frames=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$out/intro.mp4")
cat > "$out/intro.json" <<JSON
{"version":"$version","fps":$fps,"gop":$gop,"frames":$frames,"width":$w,"height":$h,"video":"intro.mp4","audio":"intro.ogg"}
JSON
ls -la "$out"
