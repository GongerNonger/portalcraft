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
   `Portal\portal\addons\`), then starts Portal and the dev Minecraft (void world `PortalCraft`).
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

Link: UDP on 127.0.0.1 ports 27515/27516, layout in `protocol/portalcraft_protocol.h`.
Logs: `Portal\portal\addons\portalcraft.log` and Minecraft's `run\logs\latest.log`.
Dev tools: `host\portal\tools\fake_mc.py` (stand-in for Minecraft, `--cmd` console commands,
`--keys` test input).

Known gaps: models aren't solid yet (chamber doors, some props, the elevator), moving brushes
(func_door, func_tracktrain) aren't followed, Minecraft's blocks, hand and HUD aren't drawn in
Portal yet, and the Minecraft hotbar doesn't gate Portal's gun.

## Host-game bridges

`PortalGunEvents.FIRE` lets another mod take over a shot. A bridge to a host game (Portal, HL2, ...)
returns `true` and makes the host fire its own real portal instead. See `docs/PORTAL_RTX_RESEARCH.md`
for the Portal / Portal with RTX plan.

Portals here are opaque ovals. Seeing through them is left to the host game, which already renders
real portals.
