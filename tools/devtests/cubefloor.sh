#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# cubefloor.sh [floorwall|floorceiling] : in pc_test, carry a cube down through a floor portal and out
# of a wall portal (or out of a ceiling portal, which drops him back onto the floor).
MODE=${1:-floorwall}
cd $TOOLS
shot(){ powershell -NoProfile -File capture-window.ps1 -Process hl2 -Out "$OUT/$1.png" >/dev/null; }
cmd(){ python fake_mc.py --cmd=+$1 >/dev/null; sleep 0.25; python fake_mc.py --cmd=-$1 >/dev/null; }
s=$(wc -l < "$L"); m0=$(wc -l < "$M"); d0=$(grep -ac 'Steve died' "$M")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start\]" && break; done; sleep 5
bash $D/nofocus.sh
python fake_mc.py --goto=85,0,2 >/dev/null; sleep 2
python fake_mc.py --view=14,270 >/dev/null; sleep 0.7; cmd attack; sleep 1.2          # blue on the floor ahead (towards -y)
if [ "$MODE" = floorceiling ]; then python fake_mc.py --view=-70,90 >/dev/null; else python fake_mc.py --view=5,180 >/dev/null; fi
sleep 0.7; cmd attack2; sleep 1.2                                                   # orange on the ceiling behind, or the x = -512 wall
python fake_mc.py --view=28,0 >/dev/null; sleep 0.8; cmd use; sleep 1.2
for y in 345 330 315 300 285 270; do python fake_mc.py --view=5,$y >/dev/null; sleep 0.12; done; sleep 0.6
echo "picked up: $(tail -n +$s "$L" | grep -a 'gun effect' | tail -1)"
python fake_mc.py --trace=500 >/dev/null
python fake_mc.py --keys=26 --ms=2200 >/dev/null; sleep 6
shot cubefloor_$MODE
tail -n +$s "$L" > $OUT/cubefloor_$MODE.txt; tail -n +$m0 "$M" > $OUT/cubefloor_${MODE}_mc.txt
echo "deaths: $(( $(grep -ac 'Steve died' "$M") - d0 ))"
grep -a "^portals:" $OUT/cubefloor_$MODE.txt | tail -2 | cut -c1-90
grep -a -v "^S \|^C \|^trace\|^perf\|^  \|^world\|^push\|^command\|^portals" $OUT/cubefloor_$MODE.txt | grep -a "gun effect\|matched\|crossing\|made it\|not handed\|handing" | cut -c1-140 | tail -8
grep -a "went through" $OUT/cubefloor_${MODE}_mc.txt | wc -l
grep -a "^trace:" "$L" | tail -1 | cut -c1-110
