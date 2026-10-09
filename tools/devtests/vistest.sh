#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# vistest.sh : does Portal actually draw Minecraft's blocks? Puts gold blocks in front of Steve in pc_test and counts
# gold pixels in a screenshot Portal takes itself. (The movement tests can't see a blank texture atlas: every block was
# invisible for two tagged builds and nothing failed.)
cd $TOOLS
s=$(wc -l < "$L")
python fake_mc.py --cmd="map pc_test" >/dev/null
for i in $(seq 1 30); do sleep 3; tail -n +$s "$L" | grep -aq "level start\]" && break; done; sleep 6
python fake_mc.py --goto=-100,100,2 >/dev/null; sleep 2
python fake_mc.py --mc "execute at @p run fill ~-12 ~-1 ~-12 ~12 ~4 ~12 air replace water" >/dev/null
python fake_mc.py --mc "execute at @p run fill ~4 ~ ~-1 ~4 ~1 ~1 minecraft:gold_block" >/dev/null
python fake_mc.py --view=5,0 >/dev/null; sleep 4
bash $D/shot.sh vistest >/dev/null
python - "$OUT/vistest.jpg" <<'PY'
import sys
from PIL import Image
im=Image.open(sys.argv[1]).convert('RGB'); px=im.load(); w,h=im.size
gold=sum(1 for x in range(0,w,4) for y in range(0,h,4) if px[x,y][0]-px[x,y][2]>40 and px[x,y][1]-px[x,y][2]>30 and px[x,y][0]>55)
share=gold*16.0/(w*h)
print(("PASS" if share>0.08 else "FAIL")+"  Minecraft's blocks are drawn in Portal  [gold over %.0f%% of the picture]"%(share*100))
PY
python fake_mc.py --mc "execute at @p run fill ~4 ~ ~-1 ~4 ~1 ~1 air" >/dev/null
