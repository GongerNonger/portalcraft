#!/bin/bash
. C:/tmp/portalcraft/run/devtests/env.sh
# suite.sh : the whole regression from one launch. Prints one PASS/FAIL line per check and a total.
#   Portals (testchmb_a_10): wall to wall, floor-to-wall fling, crouched floor entry, two infinite falls.
#   Movement (testchmb_a_02): level start, floor button four ways, cube: push, stand on, carry.
cd $TOOLS
PASS=0; FAIL=0
# One at a time: two of these driving Steve at once fight each other and every check fails.
LOCK=$OUT/suite.lock
if [ -f "$LOCK" ] && kill -0 "$(cat $LOCK)" 2>/dev/null; then echo "suite already running (pid $(cat $LOCK)): not starting another"; exit 1; fi
echo $$ > "$LOCK"; trap 'rm -f "$LOCK"' EXIT
ok()   { echo "PASS  $1"; PASS=$((PASS+1)); }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL+1)); }
# A check whose trace came back empty (the plugin never logged a tick: a dropped command) says nothing either way.
check(){ if [ "$2" = "1" ]; then ok "$1"; elif [ -f "$OUT/notrace" ]; then echo "SKIP  $1  [no trace recorded]"; else bad "$1  [$3]"; fi; rm -f "$OUT/notrace"; }
stat() { python - "$1" <<'EOF'
import re,sys
rows=[l for l in open(sys.argv[1],errors='ignore') if l.startswith('S ') and 'xy (' in l and ' out ' in l and ' ack ' in l]  # (whole lines only: the log is read while it is written)
if not rows: print("0 0 0 0 0 0"); sys.exit()
P=[tuple(map(float,re.search(r'xy \(([-\d.]+) ([-\d.]+)\)',l).groups()))+(float(re.search(r'out ([-\d.]+)',l).group(1)),) for l in rows]
a0=int(re.search(r'ack (\d+)',rows[0]).group(1)); a1=int(re.search(r'ack (\d+)',rows[-1]).group(1))
st=[((P[i][0]-P[i-1][0])**2+(P[i][1]-P[i-1][1])**2)**.5 for i in range(1,len(P))]
dz=[P[i][2]-P[i-1][2] for i in range(1,len(P))]
rev=sum(1 for i in range(1,len(dz)) if dz[i]*dz[i-1]<0 and abs(dz[i])>0.3 and abs(dz[i-1])>0.3)
jumps=sum(1 for i in range(1,len(P)) if sum((P[i][k]-P[i-1][k])**2 for k in range(3))>100)
print("%d %d %d %d %.1f %.1f"%(sum(st),a1-a0,rev,jumps,min(p[2] for p in P),max(p[2] for p in P)))
EOF
}
# at X Y : 1 if Steve is standing within 8 units of (X, Y), else 0 (the spot is taken: his own Minecraft blocks, a cube left there)
at() { grep "^trace:" "$L" | tail -1 | python -c "
import re,sys
m=re.search(r'pos \((-?\d+) (-?\d+) ', sys.stdin.read()); print(1 if m and abs(int(m.group(1))-($1))<=8 and abs(int(m.group(2))-($2))<=8 else 0)"; }
skip() { echo "SKIP  $1  [$2]"; }
trace() { rm -f $OUT/notrace; s=$(wc -l < "$L"); python fake_mc.py --trace=$2 >/dev/null; eval "$3"; tail -n +$s "$L" > $OUT/suite_$1.txt; grep -q "^S " $OUT/suite_$1.txt || touch $OUT/notrace; stat $OUT/suite_$1.txt; }

bash $D/restart.sh map testchmb_a_10 >/dev/null
nb=$(grep "block boxes" "$L" | tail -1 | grep -o "[0-9]* block boxes" | grep -o "^[0-9]*"); [ "${nb:-0}" -gt 0 ] && echo "NOTE  $nb of Steve's own blocks are in this chamber: a check that starts on or beside one will fail for that reason"
d0=$(grep -c 'Steve died' $M); z=$(grep "^trace:" "$L" | tail -1 | grep -c "ground 1")
check "a_10 level start: standing, alive" "$([ "$d0" = 0 ] && [ "$z" = 1 ] && echo 1)" "deaths $d0"
sleep 3
bash $D/ptest.sh suite_warm -1309,-2927,-383 5,180 -1309,-2927,-383 5,115 -1309,-2927,-383 5,180 26 2500 5 >/dev/null 2>&1
for t in "wall -1309,-2927,-383 5,180 -1309,-2927,-383 5,115 -1309,-2927,-383 5,180 26 2500 5" "fling -1309,-2927,-383 40,0 -1309,-2927,-383 40,270 -1400,-2908,-383 10,0 26,224 1500 6" "crouch -1309,-2927,-383 40,0 -1309,-2927,-383 40,270 -1400,-2908,-383 10,0 26,225 7000 9"; do
  set -- $t; n=$1; shift
  o=$(bash $D/ptest.sh suite_$n "$@" 2>&1)
  went=$(echo "$o" | grep -o "mc went-through: [0-9]*" | grep -o "[0-9]*$"); handed=$(echo "$o" | grep -o "handed: [0-9]*" | head -1 | grep -o "[0-9]*$"); dead=$(echo "$o" | grep -o "deaths: [0-9]*" | head -1 | grep -o "[0-9]*$")
  nj=$(echo "$o" | grep -o "jumps >10u: \[.*\]" | grep -o "([0-9]*, (" | wc -l)
  check "portal $n: one clean crossing" "$([ "${went:-0}" -ge 1 ] && [ "${dead:-1}" = 0 ] && [ "$nj" -le 1 ] && echo 1)" "went $went handed $handed deaths $dead jumps $nj"
done
for n in 1 2; do
  python fake_mc.py --goto=-1309,-2900,-383 >/dev/null; sleep 2.5; python fake_mc.py --view=5,115 >/dev/null; sleep 1
  python fake_mc.py --cmd=+attack2 >/dev/null; sleep 0.3; python fake_mc.py --cmd=-attack2 >/dev/null; sleep 2
  o=$(bash $D/looptest.sh suite_loop$n 1 2>&1)
  ups=$(echo "$o" | grep -o "teleports up: [0-9]*" | grep -o "[0-9]*$"); drops=$(echo "$o" | grep -c "sudden drops >80 units: \[\]"); dead=$(echo "$o" | grep -o "deaths: [0-9]*" | head -1 | grep -o "[0-9]*$")
  check "infinite fall $n: 30 s of crossings" "$([ "${ups:-0}" -ge 100 ] && [ "$drops" = 1 ] && [ "${dead:-1}" = 0 ] && echo 1)" "crossings $ups deaths $dead"
done
python fake_mc.py --goto=-1309,-2900,-383 >/dev/null; sleep 3
b=$(grep -c 'not handed' "$L"); check "no bounce-backs through portals" "$([ "$b" -le 3 ] && echo 1)" "$b"

python fake_mc.py --cmd="map testchmb_a_02" >/dev/null; sleep 45
d1=$(grep -c 'Steve died' $M); check "a_02 level start: alive" "$([ "$d1" = "$d0" ] && echo 1)" "deaths $((d1-d0))"
python fake_mc.py --keys=30 --ms=150 >/dev/null
for w in "across -560,189,2 10,0 26" "diagonal -498,236,2 10,-45 26" "side -451,100,2 10,90 26" "sprint -560,189,2 10,0 26,224"; do
  set -- $w
  python fake_mc.py --goto=$2 >/dev/null; sleep 2.2; python fake_mc.py --view=$3 >/dev/null; sleep 0.6
  r=($(trace btn_$1 200 "python fake_mc.py --keys=$4 --ms=2200 >/dev/null; sleep 3.4"))
  check "button $1: crosses, no wobble" "$([ "${r[0]}" -ge 120 ] && [ "${r[2]}" -le $([ "$1" = diagonal ] && echo 4 || echo 2) ] && [ "${r[1]}" -le 6 ] && echo 1)" "moved ${r[0]} shoves ${r[1]} reversals ${r[2]}"  # (the diagonal goes up the rim, down onto the pressed top, up the far rim and off: four turns of height are the button's shape)
done
python fake_mc.py --goto=880,778,452 >/dev/null; sleep 2.5; python fake_mc.py --view=15,0 >/dev/null; sleep 0.8
if [ "$(at 880 778)" = 1 ]; then
r=($(trace cube_push 230 "python fake_mc.py --keys=26 --ms=2200 >/dev/null; sleep 4"))
zr=$(python -c "print(1 if ${r[5]}-${r[4]} < 4 else 0)")
check "cube: walk into it, pushes without hopping" "$([ "${r[0]}" -ge 30 ] && [ "$zr" = 1 ] && echo 1)" "moved ${r[0]} z ${r[4]}..${r[5]} shoves ${r[1]}"
else skip "cube: walk into it, pushes without hopping" "the start spot is taken: $(grep '^trace:' "$L" | tail -1 | cut -c8-40)"; fi
python fake_mc.py --cmd="map testchmb_a_02" >/dev/null; sleep 45; python fake_mc.py --keys=30 --ms=150 >/dev/null
python fake_mc.py --goto=953,778,500 >/dev/null; sleep 1.5
r=($(trace cube_stand 350 "sleep 6"))
zr=$(python -c "print(1 if ${r[5]}-${r[4]} < 1.5 and ${r[4]} > 480 else 0)")
check "cube: stand on it for 5 s" "$([ "$zr" = 1 ] && [ "${r[1]}" -le 3 ] && echo 1)" "z ${r[4]}..${r[5]} shoves ${r[1]}"
# A fresh chamber for the carry: standing on the cube and stepping off it has nudged it out of reach.
python fake_mc.py --cmd="map testchmb_a_02" >/dev/null; sleep 45; python fake_mc.py --keys=30 --ms=150 >/dev/null
python fake_mc.py --goto=880,778,452 >/dev/null; sleep 2.5; python fake_mc.py --view=22,0 >/dev/null; sleep 1.0
if [ "$(at 880 778)" = 1 ]; then
python fake_mc.py --cmd=+use >/dev/null; sleep 0.2; python fake_mc.py --cmd=-use >/dev/null; sleep 1.2
held=$(grep "gun effect" "$L" | tail -1 | grep -c "> 2")
r=($(trace cube_carry 220 "python fake_mc.py --view=5,150 >/dev/null; sleep 1.2; python fake_mc.py --keys=26 --ms=1500 >/dev/null; sleep 2.6"))
check "cube: pick up and carry" "$([ "$held" = 1 ] && [ "${r[0]}" -ge 25 ] && [ "${r[1]}" -le 6 ] && echo 1)" "held $held moved ${r[0]} shoves ${r[1]}"
python fake_mc.py --cmd=+use >/dev/null; sleep 0.2; python fake_mc.py --cmd=-use >/dev/null; sleep 1
else skip "cube: pick up and carry" "the start spot is taken: $(grep '^trace:' "$L" | tail -1 | cut -c8-40)"; fi
d2=$(grep -c 'Steve died' $M); check "no deaths during the run" "$([ "$d2" = "$d0" ] && echo 1)" "$((d2-d0))"
echo "---- $PASS passed, $FAIL failed"
