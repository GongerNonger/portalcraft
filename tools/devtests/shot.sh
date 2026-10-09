#!/bin/bash
# shot.sh <name> : a screenshot taken by Portal itself (its `jpeg` command), copied to this pair's out folder as <name>.jpg.
# Works when the desktop can't be captured (a remote-desktop session freezes PrintWindow).
. C:/tmp/portalcraft/run/devtests/env.sh
S="D:/SteamLibrary/steamapps/common/Portal/portal/screenshots"
rm -f "$S/pcshot.jpg"; (cd $TOOLS && python fake_mc.py --cmd="jpeg pcshot 88" >/dev/null)
for i in 1 2 3 4 5 6 7 8; do sleep 0.4; [ -s "$S/pcshot.jpg" ] && break; done
cp "$S/pcshot.jpg" "$OUT/$1.jpg" 2>/dev/null && echo "$OUT/$1.jpg"
