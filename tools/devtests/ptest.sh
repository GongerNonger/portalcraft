#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# ptest.sh <name> <blue: x,y,z pitch,yaw> <orange: x,y,z pitch,yaw> <start x,y,z> <view pitch,yaw> <keys> <ms> [seconds]
# Shoots blue from one stand point and orange from another, then goes to start, looks, holds keys, and reports.
cd $TOOLS
SP=$OUT
OUT=$SP/$1.txt
SECS=${10:-6}
python fake_mc.py --keys=30 --ms=150 >/dev/null
python fake_mc.py --goto=$2 >/dev/null; sleep 1.5
python fake_mc.py --view=$3 >/dev/null; sleep 0.6
python fake_mc.py --cmd=+attack >/dev/null; sleep 0.3; python fake_mc.py --cmd=-attack >/dev/null; sleep 1.2
python fake_mc.py --goto=$4 >/dev/null; sleep 1.5
python fake_mc.py --view=$5 >/dev/null; sleep 0.6
python fake_mc.py --cmd=+attack2 >/dev/null; sleep 0.3; python fake_mc.py --cmd=-attack2 >/dev/null; sleep 1.5
python fake_mc.py --goto=$6 >/dev/null; sleep 1.5
python fake_mc.py --view=$7 >/dev/null; sleep 0.8
powershell -NoProfile -File capture-window.ps1 -Process hl2 -Out "$SP/$1_before.png" >/dev/null
start=$(wc -l < "$L"); mstart=$(wc -l < $M)
python fake_mc.py --trace=$((SECS*66)) >/dev/null
python fake_mc.py --keys=$8 --ms=$9 >/dev/null
sleep $SECS; sleep 1
powershell -NoProfile -File capture-window.ps1 -Process hl2 -Out "$SP/$1_after.png" >/dev/null
tail -n +$start "$L" | grep "^S \|Portal matched\|handing\|made it\|impulse\|health\|portal" > $OUT
tail -n +$mstart $M > $SP/$1.mc.txt
echo "[$1] matched: $(grep -c 'Portal matched' $OUT)  handed: $(grep -c handing $OUT)  forced: $(grep -c 'made it' $OUT)  deaths: $(grep -c 'health' $OUT)  mc went-through: $(grep -c 'went through' $SP/$1.mc.txt)  host moved: $(grep -c 'host moved' $SP/$1.mc.txt)  wrongly: $(grep -c 'moved wrongly' $SP/$1.mc.txt)"
grep -v "^S " $OUT | head -8 | cut -c1-170
grep "went through\|host moved" $SP/$1.mc.txt | head -6 | cut -c34-230
python - <<PYEOF
import re
rows=[l for l in open(r'$OUT') if l.startswith('S ')]
P=[]
for l in rows:
    m=re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l); z=float(re.search(r'out ([-\d.]+)',l).group(1))
    P.append((float(m.group(1)),float(m.group(2)),z))
jumps=[(i,tuple(round(P[i][k]-P[i-1][k]) for k in range(3))) for i in range(1,len(P)) if sum((P[i][k]-P[i-1][k])**2 for k in range(3))>10*10]
print('ticks',len(P),'jumps >10u:',jumps[:10])
if P: print('start',tuple(round(v) for v in P[0]),'end',tuple(round(v) for v in P[-1]))
PYEOF
grep "^trace:" "$L" | tail -1 | cut -c1-130
