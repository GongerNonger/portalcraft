#!/bin/bash
# rec.sh start <name> | stop : record the Portal window to out/<name>.mp4 (ffmpeg gdigrab, 30 fps) while a test runs.
# rec.sh sheet <name> [every-seconds] [cols] : contact sheets out/<name>_sheet_N.png of its frames, to look through it.
D=C:/tmp/portalcraft/run/devtests
case "$1" in
start) rm -f "$D/out/$2.mkv"
  ffmpeg -hide_banner -loglevel error -f gdigrab -framerate 30 -i title="Portal - Direct3D 9" -c:v libx264 -preset ultrafast -crf 24 -g 30 -pix_fmt yuv420p -flush_packets 1 "$D/out/$2.mkv" </dev/null >/dev/null 2>&1 &
  echo $! > "$D/out/rec.pid"; sleep 1 ;;
stop) sleep 0.5; taskkill //F //IM ffmpeg.exe >/dev/null 2>&1; sleep 1 ;;  # (it can't be asked to stop from here; the file is flushed every packet, so nothing but the last moment is lost)
sheet) e=${3:-0.5}; c=${4:-5}; rm -f "$D/out/$2"_sheet_*.png
  ffmpeg -hide_banner -loglevel error -y -i "$D/out/$2.mkv" -vf "fps=1/$e,scale=384:-1,drawtext=text='%{pts\:hms}':x=4:y=4:fontsize=16:fontcolor=yellow:box=1:boxcolor=black@0.6,tile=${c}x5" "$D/out/$2_sheet_%02d.png"; ls "$D/out/" | grep "$2_sheet" ;;
esac
