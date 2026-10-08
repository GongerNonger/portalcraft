#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# sweep.sh [map ...] : loads every level in turn on a running pair and watches each start for 25 s.
# One line per level: did Steve live, is he on his feet (or riding the lift down), how far Portal and
# Minecraft sit apart, and how many forced crossings / bounce-backs / resyncs the start logged.
cd $TOOLS
MAPS=${@:-testchmb_a_00 testchmb_a_01 testchmb_a_02 testchmb_a_03 testchmb_a_04 testchmb_a_05 testchmb_a_06 testchmb_a_07 testchmb_a_08 testchmb_a_09 testchmb_a_10 testchmb_a_11 testchmb_a_13 testchmb_a_14 testchmb_a_15 escape_00 escape_01 escape_02}
PASS=0; FAIL=0
for m in $MAPS; do
  s=$(wc -l < "$L"); d0=$(grep -c 'Steve died' "$M")
  python fake_mc.py --cmd="map $m" >/dev/null
  for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start]" && break; done; sleep 4
  bash $D/nofocus.sh; sleep 25
  tail -n +$s "$L" > $OUT/sweep_$m.txt
  d1=$(grep -c 'Steve died' "$M")
  r=$(python - $OUT/sweep_$m.txt <<'EOF'
import re,sys
L=[l for l in open(sys.argv[1],errors='ignore')]
T=[l for l in L if l.startswith('trace:') and 'driving 1' in l]
forced=sum('forced' in l for l in L); bounce=sum('not handed' in l for l in L); resync=sum('resync' in l for l in L)
if not T: print("0 notrace 0 0 %d %d %d"%(forced,bounce,resync)); sys.exit()
def P(l):
    a=re.findall(r'pos \((-?\d+) (-?\d+) (-?\d+)\)',l); return [tuple(map(int,x)) for x in a]
last=T[-1]; p=P(last)
apart=max(abs(p[0][k]-p[1][k]) for k in range(3)) if len(p)>1 else -1
zs=[P(l)[0][2] for l in T[-6:]]
still=max(zs)-min(zs)
ground='ground 1' in last
print("%d %s %d %d %d %d %d"%(1,'ground' if ground else 'air',apart,still,forced,bounce,resync))
EOF
)
  set -- $r
  good=0; [ "$d1" = "$d0" ] && [ "$1" = 1 ] && [ "$3" -le 24 ] && { [ "$2" = ground ] || [ "$4" -le 4 ]; } && good=1
  if [ $good = 1 ]; then PASS=$((PASS+1)); echo "PASS  $m  $2 apart $3 forced $5 bounce $6 resync $7"; else FAIL=$((FAIL+1)); echo "FAIL  $m  deaths $((d1-d0)) $2 apart $3 zdrift $4 forced $5 bounce $6 resync $7"; fi
done
echo "sweep: $PASS pass, $FAIL fail"
