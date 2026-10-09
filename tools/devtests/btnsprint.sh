#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# btnsprint.sh [n] : the suite's "button sprint" walk in testchmb_a_02, n times (default 6): metres moved, shoves and
# height reversals each time. The suite passes it at moved >= 120, shoves <= 6, reversals <= 2.
N=${1:-6}
cd $TOOLS
s=$(wc -l < "$L")
python fake_mc.py --cmd="map testchmb_a_02" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start\]" && break; done; sleep 14
bash $D/nofocus.sh
python fake_mc.py --keys=30 --ms=150 >/dev/null
for k in $(seq 1 $N); do
  python fake_mc.py --goto=-560,189,2 >/dev/null; sleep 2.2; python fake_mc.py --view=10,0 >/dev/null; sleep 0.6
  s=$(wc -l < "$L"); python fake_mc.py --trace=200 >/dev/null; python fake_mc.py --keys=26,224 --ms=2200 >/dev/null; sleep 3.4
  tail -n +$s "$L" > $OUT/btnsprint_$k.txt
  python - $OUT/btnsprint_$k.txt <<'PY'
import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ') and 'xy (' in l and ' out ' in l and ' ack ' in l]
if not rows: print("no trace"); sys.exit()
P=[tuple(map(float,re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l).groups()))+(float(re.search(r'out ([-\d.]+)',l).group(1)),) for l in rows]
a0=int(re.search(r'ack (\d+)',rows[0]).group(1)); a1=int(re.search(r'ack (\d+)',rows[-1]).group(1))
st=sum(((P[i][0]-P[i-1][0])**2+(P[i][1]-P[i-1][1])**2)**.5 for i in range(1,len(P)))
dz=[P[i][2]-P[i-1][2] for i in range(1,len(P))]
rev=sum(1 for i in range(1,len(dz)) if dz[i]*dz[i-1]<0 and abs(dz[i])>0.3 and abs(dz[i-1])>0.3)
print("moved %d shoves %d reversals %d  height %.1f..%.1f"%(st,a1-a0,rev,min(p[2] for p in P),max(p[2] for p in P)))
PY
done
