#!/bin/bash
# shot.sh <name> : a screenshot taken by Portal itself (its `jpeg` command), copied to this pair's out folder as <name>.jpg.
# Works when the desktop can't be captured (a remote-desktop session freezes PrintWindow). PCI picks the pair.
. C:/tmp/portalcraft/run/devtests/env.sh
S="D:/SteamLibrary/steamapps/common/Portal/portal/screenshots"
N="pcshot${PCI}_$(date +%H%M%S)"     # a new name every time: nothing to remove first, and two pairs don't share one
(cd $TOOLS && python fake_mc.py --cmd="jpeg $N 88" >/dev/null)
for i in 1 2 3 4 5 6 7 8 9 10; do sleep 0.4; [ -s "$S/$N.jpg" ] && break; done
cp "$S/$N.jpg" "$OUT/$1.jpg" 2>/dev/null && echo "$OUT/$1.jpg"
