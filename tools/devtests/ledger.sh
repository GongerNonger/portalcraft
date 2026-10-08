#!/bin/bash
# ledger.sh <replay-file> : a recorded play of one level, as that level's test.
#   Loads the recording's map fresh on this pair (PCI picks it), plays the recording back, and reports
#   deaths, hard hand-overs, forced crossings, how far the replay parted from the recording, and where Steve ended.
# ledger.sh --list : the recordings Portal has left (addons\replay-auto-<map>-<time>.txt, one per level played in dev mode).
. C:/tmp/portalcraft/run/devtests/env.sh
A="D:/SteamLibrary/steamapps/common/Portal/portal/addons"
if [ "$1" = "--list" ]; then
  for f in $(ls -t "$A"/replay-auto*.txt 2>/dev/null); do
    echo "$(basename $f)  map $(sed -n 2p $f | cut -d' ' -f2)  $(( $(sed -n 3p $f | cut -d' ' -f2) / 67 )) s"
  done; exit 0
fi
F="$1"; [ -f "$F" ] || F="$A/$1"; [ -f "$F" ] || { echo "no such recording: $1"; exit 1; }
MAP=$(sed -n 2p "$F" | cut -d' ' -f2)
cd $TOOLS
a=$(grep "^trace:" "$L" | tail -1 | cut -c1-60); sleep 5; b=$(grep "^trace:" "$L" | tail -1 | cut -c1-60)
[ "$a" = "$b" ] || { echo "someone is playing this pair: not driving it"; exit 1; }
python fake_mc.py --cmd="map $MAP" >/dev/null; sleep 50; bash $D/nofocus.sh
ls=$(wc -l < "$L"); ms=$(wc -l < "$M")
python fake_mc.py --replay "$F" --replay-check > "$OUT/ledger_$MAP.txt" 2>&1
tail -n +$ls "$L" > "$OUT/ledger_$MAP.plugin.txt"; tail -n +$ms "$M" > "$OUT/ledger_$MAP.mc.txt"
echo "== $MAP  ($(basename $F))"
grep "apart at the start\|first over\|portal gun shots\|replayed [0-9]* ticks" "$OUT/ledger_$MAP.txt" | sed 's/^/  /'
echo "  deaths $(grep -c 'Steve died' $OUT/ledger_$MAP.mc.txt)  hard hand-overs $(grep -c 'handing teleport' $OUT/ledger_$MAP.plugin.txt)  forced crossings $(grep -c 'made it ourselves' $OUT/ledger_$MAP.plugin.txt)  taken back $(grep -c 'not handed over' $OUT/ledger_$MAP.plugin.txt)  level changes $(grep -c 'LevelInit' $OUT/ledger_$MAP.plugin.txt)"
echo "  ended: $(grep '^trace:' "$L" | tail -1 | cut -c8-60)"
