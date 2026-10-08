#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# elevtest.sh <map> <x,y,z> <nextmap> : stands Steve in a level's exit lift and waits for it to take
# him to the next level. Reports whether the next level loaded with him alive and on his feet.
cd $TOOLS
s=$(wc -l < "$L"); d0=$(grep -c 'Steve died' "$M")
python fake_mc.py --cmd="map $1" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start]" && break; done; sleep 4
bash $D/nofocus.sh; sleep 12
s2=$(wc -l < "$L")
python fake_mc.py --goto=$2 >/dev/null; sleep 2
echo "in the lift: $(grep "^trace:" "$L" | tail -1 | cut -c1-70)"
ok=0
for i in $(seq 1 40); do sleep 3; if tail -n +$s2 "$L" | grep -q "level start\]"; then ok=1; break; fi; done
sleep 14; bash $D/nofocus.sh
tail -n +$s2 "$L" > $OUT/elev_$1.txt
echo "next level loaded: $ok after $((i*3)) s   deaths: $(( $(grep -c 'Steve died' "$M") - d0 ))"
grep "^movers:\|handing teleport\|crossing net\|LevelInit\|map " $OUT/elev_$1.txt | cut -c1-150 | head -12
grep "level start on host" "$M" | tail -1 | cut -c40-200
grep "^trace:" "$L" | tail -1 | cut -c1-120
