#!/bin/bash
# reload1.sh [map] : pair 1 only is running: close it, install the main checkout's plugin build, start it again.
export PCI=1; . C:/tmp/portalcraft/run/devtests/env.sh
(cd $TOOLS && python fake_mc.py --cmd=quit >/dev/null)
for i in $(seq 1 25); do sleep 3; tasklist | grep -qi hl2 || break; done
tasklist | grep -qi hl2 && { echo "a Portal is still running: plugin not replaced"; exit 1; }
cp C:/tmp/portalcraft/host/portal/build/portalcraft.dll "D:/SteamLibrary/steamapps/common/Portal/portal/addons/portalcraft.dll"
for i in $(seq 1 40); do sleep 3; [ "$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | ? { \$_.CommandLine -match 'KnotClient' }).Count")" = 0 ] && break; done; sleep 3   # (its Minecraft saves and closes by itself: a Portal started before that finds it 'already running' and never links)
bash $D/restart.sh map ${1:-pc_test} | tail -1
