#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# looptest.sh <name> [walk-ms] : floor->ceiling loop in testchmb_a_10, slow entry
cd $TOOLS
SP=$OUT
OUT=$SP/$1.txt
MS=${2:-700}
python fake_mc.py --keys=30 --ms=150
python fake_mc.py --goto=-1450,-2749,-383; sleep 1.5
python fake_mc.py --view=-89,90; sleep 0.6
python fake_mc.py --cmd=+attack; sleep 0.3; python fake_mc.py --cmd=-attack; sleep 1.2
python fake_mc.py --goto=-1450,-2789,-383; sleep 1.2
python fake_mc.py --view=60,90; sleep 0.6
python fake_mc.py --cmd=+attack2; sleep 0.3; python fake_mc.py --cmd=-attack2; sleep 1.5
python fake_mc.py --view=35,90; sleep 0.5
start=$(wc -l < "$L"); mstart=$(wc -l < $M)
python fake_mc.py --trace=2000
python fake_mc.py --keys=26 --ms=$MS
sleep 32
tail -n +$start "$L" | grep "^S \|Portal matched\|handing\|made it\|impulse\|health" > $OUT
echo "matched: $(grep -c 'Portal matched' $OUT)  handed: $(grep -c handing $OUT)  forced: $(grep -c 'made it' $OUT)  deaths: $(grep -c 'health' $OUT)"
grep "handing\|made it\|health" $OUT | head -6 | cut -c1-150
tail -n +$mstart $M > $SP/$1.mc.txt
echo "moved wrongly: $(grep -c 'moved wrongly' $SP/$1.mc.txt)  pushed: $(grep -c 'inside a prop' $SP/$1.mc.txt)  host moved: $(grep -c 'host moved' $SP/$1.mc.txt)"
python - <<PYEOF
import re
rows=[l for l in open(r'$OUT') if l.startswith('S ')]
z=[float(re.search(r'out ([-\d.]+)',l).group(1)) for l in rows]
print('ticks',len(z),'z range %.0f..%.0f'%(min(z),max(z)))
ups=[i for i in range(1,len(z)) if z[i]-z[i-1]>30]
downs=[(i,round(z[i]-z[i-1])) for i in range(1,len(z)) if z[i]-z[i-1]<-80]
print('teleports up:',len(ups),' sudden drops >80 units:',downs[:6])
gaps=[ups[i+1]-ups[i] for i in range(len(ups)-1)]
print('ticks between teleports: first',gaps[:8],'last',gaps[-8:])
PYEOF
grep "^trace:" "$L" | tail -1 | cut -c1-110
