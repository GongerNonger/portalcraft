# env.sh : sourced by every test script. PCI picks the Portal+Minecraft pair (0: the usual one; 1..9: a second
# pair started with hl2.exe -multirun -pcinstance N from the checkout in PCROOT_N, see restart.sh).
PCI=${PCI:-0}
D=C:/tmp/portalcraft/run/devtests
if [ "$PCI" -gt 0 ]; then
  PCROOT=${PCROOT:-C:/tmp/portalcraft/.claude/worktrees/integration}
  L="D:/SteamLibrary/steamapps/common/Portal/portal/addons/portalcraft_$PCI.log"
  M=$PCROOT/run_$PCI/logs/latest.log
  OUT=$D/out_$PCI
else
  PCROOT=C:/tmp/portalcraft
  L="D:/SteamLibrary/steamapps/common/Portal/portal/addons/portalcraft.log"
  M=$PCROOT/run/logs/latest.log
  OUT=$D/out
fi
TOOLS=$PCROOT/host/portal/tools
mkdir -p "$OUT"
# fake_mc.py talks to this pair (the scripts call it as "python fake_mc.py ..." from $TOOLS)
python() { if [ "$1" = fake_mc.py ] && [ "$PCI" -gt 0 ]; then shift; command python fake_mc.py --instance "$PCI" "$@"; else command python "$@"; fi; }
