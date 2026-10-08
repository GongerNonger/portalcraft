#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# cubetest.sh <name> : in testchmb_a_02, carry the cube into a wall, then drop it and push it
cd $TOOLS
SP=$OUT
report() {
python - <<EOF
import re
rows=[l for l in open(r'$1') if l.startswith('S ')]
zs=[float(re.search(r'out ([-\d.]+)',l).group(1)) for l in rows]
mz=[float(re.search(r'mc z ([-\d.]+)',l).group(1)) for l in rows]
P=[tuple(map(float,re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l).groups())) for l in rows]
steps=[((P[i][0]-P[i-1][0])**2+(P[i][1]-P[i-1][1])**2)**.5 for i in range(1,len(P))]
a0=int(re.search(r'ack (\d+)',rows[0]).group(1)); a1=int(re.search(r'ack (\d+)',rows[-1]).group(1))
print('$2: z %.1f..%.1f  mc z %.1f..%.1f  shoves %d  biggest step %.1f  start (%.0f %.0f) end (%.0f %.0f)'%(min(zs),max(zs),min(mz),max(mz),a1-a0,max(steps),P[0][0],P[0][1],P[-1][0],P[-1][1]))
EOF
}
python fake_mc.py --keys=30 --ms=150 >/dev/null
python fake_mc.py --goto=880,778,452 >/dev/null; sleep 2
python fake_mc.py --view=22,0 >/dev/null; sleep 1
python fake_mc.py --cmd=+use >/dev/null; sleep 0.2; python fake_mc.py --cmd=-use >/dev/null; sleep 1.2
python fake_mc.py --view=5,150 >/dev/null; sleep 1.2
s=$(wc -l < "$L"); python fake_mc.py --trace=260 >/dev/null; python fake_mc.py --keys=26 --ms=2500 >/dev/null; sleep 4.5
tail -n +$s "$L" > $SP/$1_carry.txt; report $SP/$1_carry.txt carry-into-wall
python fake_mc.py --view=22,0 >/dev/null; sleep 1.0
python fake_mc.py --cmd=+use >/dev/null; sleep 0.2; python fake_mc.py --cmd=-use >/dev/null; sleep 2
python fake_mc.py --goto=880,778,452 >/dev/null; sleep 2
s=$(wc -l < "$L"); python fake_mc.py --trace=260 >/dev/null; python fake_mc.py --keys=26,224 --ms=2500 >/dev/null; sleep 4.5
tail -n +$s "$L" > $SP/$1_push.txt; report $SP/$1_push.txt sprint-into-cube
echo "lifts $(grep -c 'lifted' $M)  prop pushes $(grep -c 'inside a prop' $M)  deaths $(grep -c 'Steve died' $M)"
grep "gun effect" "$L" | tail -2 | tr '\n' ' '; echo
