# PortalCraft

A portal gun for Minecraft 26.3 (Fabric). Fire two linked portals onto any solid surface and walk, fall
or fling through them, with momentum carried through.

| Input | Does |
|---|---|
| Left click | Fire the blue (primary) portal |
| Right click | Fire the orange (secondary) portal |

- Portals are 1×2 blocks and need two solid faces in a row with open space in front.
- Each player has their own pair. Firing a colour again moves that portal.
- Breaking a block behind a portal fizzles it.
- Items, mobs and arrows go through too.
- Get it from the Tools & Utilities creative tab, or craft it:

```
 . E A      E = ender pearl   A = amethyst shard
 . G E      G = gold ingot    O = obsidian
 O . .
```

## Setting up a new PC

You need Windows, Git, and Steam Portal if you want the Portal side. Nothing is installed
system-wide.

```bat
git clone https://github.com/GongerNonger/portalcraft.git
cd portalcraft
play-portal.cmd
```

The first run of `tools\setup.ps1` does three things:
- downloads a portable JDK 25 into `.jdk\`;
- creates the void world in `run\saves\PortalCraft`;
- finds Portal through Steam's library list and installs the plugin into `portal\addons\`. It
  builds the plugin if Visual Studio 2022 Build Tools are present, and otherwise uses
  `host\portal\prebuilt\portalcraft.dll`.

## Building

```bat
gradle build               :: jar in build\libs (gradle.cmd = gradlew with the portable JDK)
gradle runClient           :: dev Minecraft with the mod, offline as "Steve"
host\portal\build.cmd      :: the Portal plugin (needs VS 2022 Build Tools, x86)
```
`tools\make_textures.py` regenerates the art (`uv run --no-project --with pillow python tools/make_textures.py`).

## Playing inside Portal (milestone 1)

Steve's Minecraft movement inside real Steam Portal. A hidden-in-plain-sight Minecraft runs the
physics; Portal draws everything and keeps its own portal gun, portals and teleports.

1. `play-portal.cmd` builds and installs the Portal plugin the first time (into
   `Portal\portal\addons\`), then starts Portal. The plugin starts Minecraft itself, hidden
   (void world `PortalCraft`, about a minute; Portal's top-left corner says how it's going), and
   Minecraft saves and quits when Portal closes. What it starts is in
   `Portal\portal\addons\portalcraft.ini`; `start_with_portal=0` there to start Minecraft yourself.
2. Click into the Portal window. WASD/space/shift/ctrl go to Minecraft; the mouse is Portal's.
3. Walk into a pair of open portals: Portal teleports you, Minecraft follows with your momentum.

How it fits together:

| | Portal (`host/portal`, 32-bit server plugin) | Minecraft (`dev.portalcraft.host`) |
|---|---|---|
| Position | follows Minecraft every movement tick (`GameMovement001` hook) | runs the physics |
| Walls | — | reads the map's `.bsp` brushes as collision; linked portals cut holes |
| Look | the mouse, natively | copies Portal's view angles |
| Portals | fires and renders its real ones; teleports the player | follows each teleport (seq/ack) |
| Keys | reads WASD etc. while its window has focus | replays them as its own input |
| Clicks | fires its gun while Steve holds the portal gun | otherwise attacks/places/picks; aims at the map's walls too, and a block placed against a wall goes in the cell in front of it (to the nearer half-cell, since walls don't sit on the block grid) |
| Body | hides Chell (render mode) | in third person (F5) sends Steve, posed, with his skin and what he holds; Portal draws him at its player's feet |
| F5 | moves its camera behind/in front of the player (client-mode `OverrideView` hook, no cheats), stopped by its walls | cycles the view as usual; sends how far its own camera gets, so the host's stops at placed blocks too |

Link: UDP on 127.0.0.1 ports 27515/27516, layout in `protocol/portalcraft_protocol.h`.
Logs: `Portal\portal\addons\portalcraft.log` and Minecraft's `run\logs\latest.log`.
Dev tools: `host\portal\tools\fake_mc.py` (stand-in for Minecraft, `--cmd` console commands,
`--keys` test input).

Camera smoothness: Portal's single-player client doesn't predict the player (`cl_predict` is
forced to 0), so its camera follows the server through network interpolation. At Portal's
defaults (20 updates/s, 100 ms delay) that beats against Minecraft's 20 Hz ticks and the view
bobs. `play-portal.cmd` launches Portal with `+cl_updaterate 66 +cl_cmdrate 66 +cl_interp 0
+cl_interp_ratio 1` (one update per server tick, ~15 ms delay), which removes the bob.

Known gaps: moving and dynamic models (chamber doors, the elevator, cubes) aren't solid,
moving brushes (func_door, func_tracktrain) aren't followed, Minecraft's placed blocks aren't
drawn in Portal yet, and Minecraft screens (inventory) get no mouse. Steve isn't drawn in the
views through portals yet (Chell is hidden there, so you see no one), and F5 also takes a Portal
screenshot unless you unbind it in Portal (`unbind F5`).

## Host-game bridges

`PortalGunEvents.FIRE` lets another mod take over a shot. A bridge to a host game (Portal, HL2, ...)
returns `true` and makes the host fire its own real portal instead. See `docs/PORTAL_RTX_RESEARCH.md`
for the Portal / Portal with RTX plan.

Portals here are opaque ovals. Seeing through them is left to the host game, which already renders
real portals.
