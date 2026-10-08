#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# cubeportal.sh [fwd|back] : in pc_test (tools/testmap), carry a cube through a wall portal, walking
# forwards (cube ahead) or backwards (cube trailing, following him through). Reports whether the gun
# still holds it afterwards (gun effect state 2) and what the crossing logged.
MODE=${1:-fwd}
cd $TOOLS
shot(){ powershell -NoProfile -File capture-window.ps1 -Process hl2 -Out "$OUT/$1.png" >/dev/null; }
cmd(){ python fake_mc.py --cmd=+$1 >/dev/null; sleep 0.25; python fake_mc.py --cmd=-$1 >/dev/null; }
s=$(wc -l < "$L"); m0=$(wc -l < "$M"); d0=$(grep -c 'Steve died' "$M")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start]" && break; done; sleep 4
bash $D/nofocus.sh; sleep 6
python fake_mc.py --goto=85,0,2 >/dev/null; sleep 2
python fake_mc.py --view=5,270 >/dev/null; sleep 0.7; cmd attack; sleep 1.2     # blue on the y = -512 wall, straight ahead of the walk
python fake_mc.py --view=5,180 >/dev/null; sleep 0.7; cmd attack2; sleep 1.2    # orange on the x = -512 wall
python fake_mc.py --view=28,0 >/dev/null; sleep 0.8; cmd use; sleep 1.2
python fake_mc.py --view=5,270 >/dev/null; sleep 1.2
echo "picked up: $(tail -n +$s "$L" | grep 'gun effect' | tail -1)"
python fake_mc.py --trace=700 >/dev/null
if [ "$MODE" = back ]; then
  python fake_mc.py --keys=26 --ms=1900 >/dev/null; sleep 2.6              # towards blue, cube ahead
  for y in 255 240 225 210 195 180 165 150 135 120 105 90; do python fake_mc.py --view=5,$y >/dev/null; sleep 0.12; done; sleep 1   # turn his back to it, as a player turns: the cube swings round him
  shot cubeportal_${MODE}_before
  python fake_mc.py --keys=22 --ms=2600 >/dev/null; sleep 4                # backwards through
else
  shot cubeportal_${MODE}_before
  python fake_mc.py --keys=26 --ms=4200 >/dev/null; sleep 5.5
fi
shot cubeportal_${MODE}_after
tail -n +$s "$L" > $OUT/cubeportal_$MODE.txt; tail -n +$m0 "$M" > $OUT/cubeportal_${MODE}_mc.txt
echo "deaths: $(( $(grep -c 'Steve died' "$M") - d0 ))"
grep "^portals:" $OUT/cubeportal_$MODE.txt | tail -2
grep "gun effect\|Portal matched\|crossing net\|made it ourselves\|not handed\|handing teleport" $OUT/cubeportal_$MODE.txt | cut -c1-150 | tail -12
grep "went through\|inside a prop\|lifted" $OUT/cubeportal_${MODE}_mc.txt | cut -c40-200 | tail -6
grep "metal_box" $OUT/cubeportal_${MODE}_mc.txt | tail -3 | cut -c40-150
grep "^trace:" "$L" | tail -1 | cut -c1-110
