#!/bin/bash
# restart.sh [save|map] [mapname] : graceful restart of this pair's Portal + Minecraft (PCI picks the pair, see env.sh).
#   PCEXTRA  extra hl2.exe launch options (e.g. -pcearlysolid)
#   PCDLL    the plugin build to install (pair 0 only; default: the main checkout's)
. C:/tmp/portalcraft/run/devtests/env.sh
cd $TOOLS
PIDF=$OUT/hl2.pid
ARG="+map ${2:-testchmb_a_10}"
# This pair's Portal by its command line (-pcinstance N, or none of that for pair 0): pid files went
# stale whenever a Portal was closed by hand, and the script then waited on, or started beside, the wrong one.
pids() { powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='hl2.exe'\" | Where-Object { if ($PCI -gt 0) { \$_.CommandLine -match '-pcinstance $PCI( |\$)' } else { \$_.CommandLine -notmatch '-pcinstance' } } | ForEach-Object { \$_.ProcessId }" 2>/dev/null | tr -d '
'; }
alive() { [ -n "$(pids)" ]; }
mcs() { powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'KnotClient' -and (\$_.CommandLine -match 'worktrees') -eq ($PCI -gt 0) }).Count" 2>/dev/null; }
if alive; then
  if [ "$1" = "save" ]; then python fake_mc.py --cmd="save pcrestart$PCI"; sleep 3; ARG="+load pcrestart$PCI"; fi
  python fake_mc.py --cmd="quit"
  for i in $(seq 1 30); do sleep 2; alive || { echo "portal gone"; break; }; done
  # (one that didn't answer, a second copy that never got the link port: asked to close like a window)
  for p in $(pids); do taskkill //PID $p >/dev/null 2>&1; done; alive && sleep 8
  # (a test pair's Portal that never linked with a Minecraft answers neither: ended outright. Never pair 0, which may be his own game.)
  [ "$PCI" -gt 0 ] && for p in $(pids); do taskkill //F //PID $p >/dev/null 2>&1; done
  for i in $(seq 1 45); do sleep 2; [ "$(mcs)" = "0" ] && { echo "minecraft gone"; break; }; done
  sleep 3
fi
INST="-multirun"; SIZE="-w 1600 -h 900"   # (-multirun: without it a second hl2.exe only wakes the first and exits)
if [ "$PCI" -gt 0 ]; then
  INST="-multirun -pcinstance $PCI"; SIZE="-w 1280 -h 720"   # (the plugin file is shared and in use by the other Portal: not copied)
else
  DLL=${PCDLL:-C:/tmp/portalcraft/host/portal/build/portalcraft.dll}
  if [ -f "$DLL" ] && [ "$(tasklist | grep -ci '^hl2.exe')" = 0 ]; then cp "$DLL" "D:/SteamLibrary/steamapps/common/Portal/portal/addons/portalcraft.dll"; else echo "plugin not copied (another Portal is running, or no build)"; fi
fi
powershell -NoProfile -Command "\$p='D:\SteamLibrary\steamapps\common\Portal'; (Start-Process -PassThru -FilePath \"\$p\hl2.exe\" -WorkingDirectory \$p -ArgumentList '-game portal -windowed -novid $SIZE -insecure -portalcraftdev -nomouse -condebug $INST $PCEXTRA $ARG').Id" > "$PIDF"
sleep 30
for i in $(seq 1 80); do sleep 5; if tail -30 "$L" | grep -aq "driving 1"; then echo "driving after $((30+i*5))s"; break; fi; done
sleep 8
bash $D/nofocus.sh
grep "^trace:" "$L" | tail -1 | cut -c1-110
