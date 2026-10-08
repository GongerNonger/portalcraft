#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# glasstest.sh : in pc_test's glass cell. First walks at the breakable pane (it must stop him), then wakes the
# rocket turret outside, waits for it to shoot the pane out, and walks at it again (he must go through).
cd $TOOLS
s=$(wc -l < "$L"); d0=$(grep -ac 'Steve died' "$M")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start\]" && break; done; sleep 8
bash $D/nofocus.sh
python fake_mc.py --mc "effect give @p minecraft:resistance 60 4" >/dev/null
y(){ grep -a "^trace:" "$L" | tail -1 | sed -E 's/.*pos \((-?[0-9]+) (-?[0-9]+) .*/\2/' | head -1; }
python fake_mc.py --goto=-350,-470,2 >/dev/null; sleep 2; python fake_mc.py --view=0,90 >/dev/null; sleep 0.6
python fake_mc.py --keys=26 --ms=1500 >/dev/null; sleep 2.5
echo "before: walking north from y -470 he stops at y $(y) (the pane is at -400)"
python fake_mc.py --goto=-480,-480,2 >/dev/null; sleep 1.2                 # the corner that wakes the turret
python fake_mc.py --goto=-350,-470,2 >/dev/null; sleep 8                   # in sight of it, through the glass
python fake_mc.py --keys=26 --ms=1500 >/dev/null; sleep 2.5
echo "after the rocket: walking north from y -470 he reaches y $(y)"
echo "rockets seen by Minecraft: $(tail -n +1 "$M" | grep -ac 'rocket.mdl')   deaths: $(( $(grep -ac 'Steve died' "$M") - d0 ))"
