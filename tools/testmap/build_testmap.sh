#!/bin/bash
# Compiles pc_test.vmf with Portal's own tools and installs it as portal/maps/pc_test.bsp.
P="${PORTAL_DIR:-D:/SteamLibrary/steamapps/common/Portal}"
cd "$(dirname "$0")" && python make_testmap.py || exit 1
W="$(cygpath -w "$PWD/pc_test")"; G="$(cygpath -w "$P/portal")"
"$P/bin/vbsp.exe" -game "$G" "$W" | tail -3 && "$P/bin/vvis.exe" -fast -game "$G" "$W" | tail -1 && "$P/bin/vrad.exe" -fast -game "$G" "$W" | tail -1
[ -f pc_test.bsp ] && cp pc_test.bsp "$P/portal/maps/pc_test.bsp" && echo "installed pc_test.bsp"
