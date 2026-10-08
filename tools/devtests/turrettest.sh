#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# turrettest.sh : in pc_test, steps into the turret's alley (it looks along +x from -440,440), takes
# its fire for 2 s, then sprints into it to knock it over, and tries to pick it up.
cd $TOOLS
shot(){ powershell -NoProfile -File capture-window.ps1 -Process hl2 -Out "$OUT/$1.png" >/dev/null; }
cmd(){ python fake_mc.py --cmd=+$1 >/dev/null; sleep 0.25; python fake_mc.py --cmd=-$1 >/dev/null; }
s=$(wc -l < "$L"); m0=$(wc -l < "$M"); d0=$(grep -ac 'Steve died' "$M")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start\]" && break; done; sleep 5
bash $D/nofocus.sh
python fake_mc.py --mc "effect give @p minecraft:instant_health 1 5" >/dev/null
python fake_mc.py --goto=-480,480,2 >/dev/null; sleep 2; python fake_mc.py --view=10,315 >/dev/null; sleep 1   # behind it, out of its sight
shot turret_behind
echo "standing behind it: $(grep -a "^trace:" "$L" | tail -1 | cut -c1-60)"
python fake_mc.py --trace=300 >/dev/null
python fake_mc.py --keys=26,224 --ms=900 >/dev/null; sleep 3.5
shot turret_after
tail -n +$s "$L" > $OUT/turret.txt; tail -n +$m0 "$M" > $OUT/turret_mc.txt
echo "deaths: $(( $(grep -ac 'Steve died' "$M") - d0 ))"
grep -a "^health:" $OUT/turret.txt | sed 's/[0-9.]\+/N/g' | sort | uniq -c | head -4 | cut -c1-110
grep -a "turret\|tipped\|club" $OUT/turret.txt | grep -av "^S \|^trace" | cut -c1-140 | head -6
grep -a "turret" $OUT/turret_mc.txt | cut -c40-190 | tail -4
python fake_mc.py --view=40,315 >/dev/null; sleep 0.8; cmd use; sleep 1.2
echo "pick up: $(tail -n +$s "$L" | grep -a 'gun effect' | tail -1)"
grep -a "^trace:" "$L" | tail -1 | cut -c1-110
