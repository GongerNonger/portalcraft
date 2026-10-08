#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# lifttest.sh : the lift ride in testchmb_a_03 (stand at 704,0,100; it leaves 5 s after the touch).
# Stands still for the first stretch, then jumps three times, then walks about on it.
# Reports: deaths, hand-overs (teleports), what the plugin said about the mover, how steady Steve's
# place on it was while standing still, and where each jump came down.
cd $TOOLS
s=$(wc -l < "$L"); d0=$(grep -c 'Steve died' "$M"); m0=$(wc -l < "$M")
python fake_mc.py --cmd="map testchmb_a_03" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start]" && break; done; sleep 4
bash $D/nofocus.sh; sleep 10
python fake_mc.py --goto=704,0,100 >/dev/null; sleep 2
python fake_mc.py --trace=1400 >/dev/null
sleep 9                                   # the lift leaves; standing still on it
for j in 1 2 3; do python fake_mc.py --keys=44 --ms=120 >/dev/null; sleep 1.6; done
python fake_mc.py --keys=26 --ms=350 >/dev/null; sleep 1.2
python fake_mc.py --keys=22 --ms=350 >/dev/null; sleep 4
tail -n +$s "$L" > $OUT/lift.txt; tail -n +$m0 "$M" > $OUT/lift_mc.txt
echo "deaths: $(( $(grep -c 'Steve died' "$M") - d0 ))"
echo "hand-overs: $(grep -c 'handing teleport\|handed' $OUT/lift.txt)   forced/bounce: $(grep -c 'crossing net' $OUT/lift.txt)"
grep "^movers:" $OUT/lift.txt | sed 's/[0-9.-]\+/N/g' | sort | uniq -c | sort -rn | head -8
grep "^movers:" $OUT/lift.txt | head -6 | cut -c1-200
grep -i "mover\|is off\|level start on" $OUT/lift_mc.txt | cut -c40-240 | head -12
python - $OUT/lift.txt <<'EOF'
import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ')]
rel=[(i,tuple(map(float,m.groups()))) for i,l in enumerate(rows) for m in [re.search(r'mover (\d+) rel \((-?[\d.]+) (-?[\d.]+) (-?[\d.]+)\)',l)] if m]
print("S rows %d, with a mover %d"%(len(rows),len(rel)))
if rows: print("first:",rows[0].strip()[:230]); print("last: ",rows[-1].strip()[:230])
if rel:
    z=[r[1][3] for r in rel]
    still=z[:min(len(z),400)]
    print("standing still: rel z min %.2f max %.2f (spread %.2f)"%(min(still),max(still),max(still)-min(still)))
    print("whole ride: rel z min %.2f max %.2f"%(min(z),max(z)))
    xy=[(r[1][1],r[1][2]) for r in rel]
    print("rel xy at start (%.1f %.1f), at end (%.1f %.1f)"%(xy[0]+xy[-1]))
P=[tuple(map(float,m.groups())) for l in rows for m in [re.search(r'xy \(([-\d.]+) ([-\d.]+)\).* out ([-\d.]+)',l)] if m]
if P:
    big=[i for i in range(1,len(P)) if abs(P[i][2]-P[i-1][2])>12]
    print("height: from %.1f to %.1f; steps of more than 12 units in a tick: %d"%(P[0][2],P[-1][2],len(big)))
EOF
