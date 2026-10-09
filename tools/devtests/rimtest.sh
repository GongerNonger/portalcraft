#!/bin/bash
# rimtest.sh <name> : gold blocks on the floor of pc_test, photographed by Portal while Steve strafes past them and while he stands.
. C:/tmp/portalcraft/run/devtests/env.sh
cd $TOOLS
python fake_mc.py --goto=-100,100,2 >/dev/null; sleep 2
python fake_mc.py --mc "execute at @p run fill ~4 ~ ~-1 ~4 ~1 ~1 minecraft:gold_block" >/dev/null; sleep 3
python fake_mc.py --view=5,0 >/dev/null; sleep 1
bash $D/shot.sh $1_still >/dev/null
python fake_mc.py --keys=4,224 --ms=1600 >/dev/null; sleep 0.7
bash $D/shot.sh $1_moving
sleep 2
