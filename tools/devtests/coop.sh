#!/bin/bash
# coop.sh [map] : two players on this PC. Pair 1 hosts (its Minecraft opens its world on port 25590), pair 2 joins it
# as "Alex" (portalcraft_2.ini: PORTALCRAFT_JOIN=127.0.0.1:25590, a third checkout). Both load the same map.
# Needs about 10 GB of commit memory free: two Minecrafts ran this PC out of memory once.
D=C:/tmp/portalcraft/run/devtests
MAP=${1:-pc_test}
free=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory/1024)")
[ "$(tasklist | grep -ci hl2.exe)" = 0 ] && [ "$free" -lt 9000 ] && { echo "only $free MB of commit memory free: not starting two pairs (close something first)"; exit 1; }
PCROOT_2=C:/tmp/portalcraft/.claude/worktrees/agent-a9fe7c51784c95c11
export JAVA_HOME="$(cygpath -w "C:/tmp/portalcraft/$(cd /c/tmp/portalcraft && ls -d .jdk/jdk-25* | head -1)")"
(cd C:/tmp/portalcraft/.claude/worktrees/integration && ./gradlew build -q -x test --max-workers=1 2>&1 | grep -v "^Note")
(cd $PCROOT_2 && ./gradlew build -q -x test --max-workers=1 2>&1 | grep -v "^Note")
PCI=1 PORTALCRAFT_LAN_PORT=25590 bash $D/restart.sh map $MAP | tail -1
export PCI=1; . $D/env.sh
for i in $(seq 1 20); do grep -aq "open to friends" "$M" && break; sleep 3; done
grep -a "to friends" "$M" | tail -1 | cut -c12-120
PCI=2 PCROOT=$PCROOT_2 PORTALCRAFT_USERNAME=Alex PORTALCRAFT_JOIN=127.0.0.1:25590 bash $D/restart.sh map $MAP | tail -1
