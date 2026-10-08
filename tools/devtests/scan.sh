#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# scan.sh <map> [wait-seconds] : load a map (when the game is idle) and list its solid entities and which of them move
cd $TOOLS
ok=0
for i in $(seq 1 40); do a=$(grep "^trace:" "$L" | tail -1 | cut -c1-60); sleep 6; b=$(grep "^trace:" "$L" | tail -1 | cut -c1-60); [ "$a" = "$b" ] && { ok=1; break; }; done
[ $ok = 0 ] && { echo "user active: not driving"; exit 1; }
ms=$(wc -l < $M)
python fake_mc.py --cmd="map $1" >/dev/null
sleep ${2:-60}
grep "^trace:" "$L" | tail -1 | cut -c1-110
echo "deaths $(tail -n +$ms $M | grep -c 'Steve died')"
tail -n +$ms $M > $OUT/scan_$1.mc.txt
python - <<EOF
import re,collections
first={}; moved=collections.defaultdict(list)
for l in open(r'$OUT/scan_$1.mc.txt',errors='ignore'):
    m=re.search(r'entity #(\d+) (\S+) at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\) \((\d+) brushes, bounds (.*)\)',l)
    if m: first[m.group(1)]=(m.group(2),tuple(round(float(x)) for x in m.groups()[2:5]),m.group(7)[:90])
    m=re.search(r'entity #(\d+) (\S+) moved to \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)',l)
    if m: moved[m.group(1)].append(tuple(round(float(x)) for x in m.groups()[2:]))
print(len(first),'solid entities; moving:')
for k,v in moved.items(): print(' #'+k, first.get(k,('?',))[0], 'from', first.get(k,('?','?'))[1], '->', v[0], '...', v[-1], '(%d moves)'%len(v))
names=collections.Counter(v[0] for v in first.values())
print(' '.join('%s x%d'%(n.split('/')[-1],c) for n,c in names.most_common(16)))
EOF
