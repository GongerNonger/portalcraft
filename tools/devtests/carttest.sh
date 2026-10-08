#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# carttest.sh [map x y z] : rides a sideways rail cart (default: testchmb_a_04's, which runs x 72..590 at y -60).
# Drops Steve onto the cart's path until he lands on the cart, stands still, jumps twice in place, then
# walks a step. Reports as lifttest.sh does: his place on the cart should not move while he stands.
MAP=${1:-testchmb_a_04}; X=${2:-330}; Y=${3:--60}; Z=${4:-70}
cd $TOOLS
s=$(wc -l < "$L"); d0=$(grep -c 'Steve died' "$M"); m0=$(wc -l < "$M")
python fake_mc.py --cmd="map $MAP" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start]" && break; done; sleep 4
bash $D/nofocus.sh; sleep 12
[ -n "$PRE" ] && { python fake_mc.py --goto=$PRE >/dev/null; sleep 1.2; }
on=0
for i in $(seq 1 25); do
  python fake_mc.py --goto=$X,$Y,$Z >/dev/null; sleep 1.4
  t=$(grep "^trace:" "$L" | tail -1)
  if echo "$t" | grep -q "ground 1" && tail -n +$s "$L" | grep -q "^movers: .* carries Steve"; then on=1; echo "on the cart after $i drops: $(echo "$t" | cut -c1-60)"; break; fi
done
[ $on = 0 ] && echo "never landed on the cart: $(grep "^trace:" "$L" | tail -1 | cut -c1-90)"
python fake_mc.py --trace=900 >/dev/null
sleep 5
for j in 1 2; do python fake_mc.py --keys=44 --ms=120 >/dev/null; sleep 1.6; done
python fake_mc.py --keys=26 --ms=200 >/dev/null; sleep 3
tail -n +$s "$L" > $OUT/cart.txt; tail -n +$m0 "$M" > $OUT/cart_mc.txt
echo "deaths: $(( $(grep -c 'Steve died' "$M") - d0 ))"
echo "hand-overs: $(grep -c 'handing teleport' $OUT/cart.txt) (each drop is one)   forced/bounce: $(grep -c 'crossing net' $OUT/cart.txt)"
grep "^movers:" $OUT/cart.txt | cut -c1-170 | head -8
python - $OUT/cart.txt <<'EOF'
import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ')]
rel=[tuple(map(float,m.groups())) for l in rows for m in [re.search(r'mover (\d+) rel \((-?[\d.]+) (-?[\d.]+) (-?[\d.]+)\)',l)] if m and int(m.group(1))]
xy=[tuple(map(float,m.groups())) for l in rows for m in [re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l)] if m]
print("S rows %d, on a mover %d"%(len(rows),len(rel)))
if rows: print("first:",rows[0].strip()[:240])
if xy: print("world xy from (%.1f %.1f) to (%.1f %.1f)"%(xy[0]+xy[-1]))
if rel:
    st=rel[:min(len(rel),300)]
    for k,n in ((1,'x'),(2,'y'),(3,'z')):
        v=[r[k] for r in st]; w=[r[k] for r in rel]
        print("rel %s standing still: %.2f..%.2f (spread %.2f); whole ride %.2f..%.2f"%(n,min(v),max(v),max(v)-min(v),min(w),max(w)))
    steps=[max(abs(rel[i][k]-rel[i-1][k]) for k in (1,2)) for i in range(1,len(rel))]
    print("largest sideways step of his place on the cart in one tick: %.2f; ticks over 1 unit: %d"%(max(steps),sum(s>1 for s in steps)))
EOF
