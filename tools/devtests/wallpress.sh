#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# wallpress.sh : in pc_test, pick up a cube and walk it straight into a wall for 5 s. Reports the
# hand-overs and how far Steve moves about in the last 150 ticks, leaning on it (should be nothing).
cd $TOOLS
cmd(){ python fake_mc.py --cmd=+$1 >/dev/null; sleep 0.25; python fake_mc.py --cmd=-$1 >/dev/null; }
s0=$(wc -l < "$L")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s0 "$L" | grep -aq "level start]" && break; done; sleep 4
bash $D/nofocus.sh; sleep 6
python fake_mc.py --goto=85,0,2 >/dev/null; sleep 2; python fake_mc.py --view=28,0 >/dev/null; sleep 0.8
s=$(wc -l < "$L"); cmd use; sleep 1.2
for y in 345 330 315 300 285 270; do python fake_mc.py --view=5,$y >/dev/null; sleep 0.12; done; sleep 0.5
python fake_mc.py --trace=420 >/dev/null; python fake_mc.py --keys=26 --ms=5000 >/dev/null; sleep 6.2
tail -n +$s "$L" > $OUT/wallpress.txt
python - $OUT/wallpress.txt <<'PY'
import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ')]
q=[int(re.search(r'seq (\d+)',l).group(1)) for l in rows]
P=[tuple(map(float,re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l).groups()))+(float(re.search(r'out ([-\d.]+)',l).group(1)),) for l in rows]
k=[int(re.search(r'keys (\d+)',l).group(1)) if 'keys' in l else 1 for l in rows]
print("ticks",len(rows),"hand-overs",q[-1]-q[0],"from",P[0],"to",P[-1])
lean=P[200:320]
print("leaning on the wall (ticks 200-320): y %.1f..%.1f  z %.1f..%.1f"%(min(p[1] for p in lean),max(p[1] for p in lean),min(p[2] for p in lean),max(p[2] for p in lean)))
PY
grep -a "gun effect" $OUT/wallpress.txt | tail -2
