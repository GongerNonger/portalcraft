#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# walkbtn.sh : in testchmb_a_02, walk over the first floor button several ways and report speed and wobble
cd $TOOLS
walk() {
  a=$(grep "^trace:" "$L" | tail -1 | cut -c1-90); sleep 3; b=$(grep "^trace:" "$L" | tail -1 | cut -c1-90)
  if [ "$a" != "$b" ]; then echo "user active: skipping $1"; return; fi
  python fake_mc.py --goto=$2 >/dev/null; sleep 2.2; python fake_mc.py --view=$3 >/dev/null; sleep 0.6
  s=$(wc -l < "$L"); python fake_mc.py --trace=${5:-200} >/dev/null; python fake_mc.py --keys=$4 --ms=${6:-2200} >/dev/null; sleep 3.4
  tail -n +$s "$L" > $OUT/$1.txt
  python - <<EOF
import re
rows=[l for l in open(r'$OUT/$1.txt') if l.startswith('S ') and 'xy (' in l and ' out ' in l]
P=[tuple(map(float,re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l).groups()))+(float(re.search(r'out ([-\d.]+)',l).group(1)),) for l in rows]
a0=int(re.search(r'ack (\d+)',rows[0]).group(1)); a1=int(re.search(r'ack (\d+)',rows[-1]).group(1))
st=[((P[i][0]-P[i-1][0])**2+(P[i][1]-P[i-1][1])**2)**.5 for i in range(1,len(P))]
first=next((i for i,x in enumerate(st) if x>0.5),0); last=len(st)-next((i for i,x in enumerate(reversed(st)) if x>0.5),0)
mv=st[first:last]
dz=[P[i][2]-P[i-1][2] for i in range(1,len(P))]
rev=sum(1 for i in range(1,len(dz)) if dz[i]*dz[i-1]<0 and abs(dz[i])>0.3 and abs(dz[i-1])>0.3)
stalled=sum(1 for x in mv if x<0.8)
print('$1: (%.0f %.0f) -> (%.0f %.0f) moved %.0f; stalled ticks %d of %d; z %.1f..%.1f; shoves %d; height reversals %d'%(P[0][0],P[0][1],P[-1][0],P[-1][1],sum(st),stalled,len(mv),min(p[2] for p in P),max(p[2] for p in P),a1-a0,rev))
EOF
}
walk across -560,189,2 10,0 26
walk diagonal -498,236,2 10,-45 26
walk side -451,100,2 10,90 26
walk sprint -560,189,2 10,0 26,224
walk flat -900,192,2 10,0 26
echo "lifts $(grep -c lifted $M) deaths $(grep -c 'Steve died' $M)"
