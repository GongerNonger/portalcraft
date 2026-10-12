# Handoff: where PortalCraft stands (2026-10-11)

For the next session picking this up. Read this, then `docs/TESTING.md` for the test rig, then the
project memory (`project_minecraft_crossover.md`) for the long history.

## What Kaydn is doing

He wants to record and upload a Portal 1 let's-play as Steve. The game is playable start to finish
as far as tests go; the session of 10-10/10-11 was mostly polish for the video. He launches with
`play-portal.cmd` and nothing else.

## State of the code

- `main` is 37 commits ahead of `origin/main`, all local. **Nothing is pushed. Push only when he asks.**
- Last verified-by-suite tag is `stable-17`. Everything after it is cosmetic or additive and was
  checked by screenshot on the test pair, **not** by rerunning `suite.sh`. Rerun it before tagging.
- The release friends get is still **0.2.0**. Shipping this work as 0.3.0 auto-updates everyone and
  needs his go-ahead.
- The installed plugin (`Portal\portal\addons\portalcraft.dll`) is the current build. Older copies
  named `portalcraft.dll.inuse-*` and `.before-gun-pose` sit beside it (renamed out of the way while
  his game had the file open); they can be deleted once no Portal is running, with his OK.

## Added this session

| Thing | Where | Checked how |
|---|---|---|
| Gun worn on the arm in third person (drawn-bow pose, model along the forearm) | `GunArmPoseMixin`, gun models' `thirdperson_*` display | screenshots, side view through a portal |
| Third-person camera stays in his eyes when backed against a wall | `camera.cpp` `kHeadRoom` | screenshots |
| Green Rick-and-Morty portal skin (spiral, globs, green shots) | `green-portals.cmd on/closed/open/off`, `tools/green-portals/` | screenshots; **off** right now |
| Chamber 00 opening: Steve drops out of a green ceiling portal | `opening.cmd on/off`, `tools/intro/make_intro.py` | screenshots; **on** right now |
| Opening waits (paused) for Minecraft to link | `holdOpeningForMinecraft` in `plugin.cpp` | reproduced his title-screen new game on the test pair |
| Gun progression: none, blue-only at chamber 02, full gun at the upgrade | `GunGrant`, plugin `g_gunLevel` | loaded chambers 01/03/11 with an emptied inventory |
| "Portal CRAFT" on the title and pause menus | `overlay.cpp` `drawTitle`, plugin `gameMenuUp` | pause menu at 1280x720 only |
| Minecraft hand/HUD hidden under Portal's menu | `overlay.cpp` `g_menuUp` | screenshot |
| Mouse pointer drawn while a Minecraft screen is open | `overlay.cpp` `g_pointer` | **not tested** (needs a real mouse over the window) |
| Flames on a burning Steve or mob, smaller and self-lit | `EntityExporter.submitFlame` | screenshots |
| Glowing particles keep their brightness and light Portal's rooms | `EntityExporter` particle quads, `LightExporter.spark` | screenshots (end rod, flame, soul flame) |
| Custom skin chooser in the installer and a Start-menu shortcut | `tools/release/PortalCraft.ps1 -Skin`, `install.ps1` | window opens; picking and saving a file **not tested** |
| README: playing as your own skin | `README.md` | paths taken from code, release folder not tried |

## How the opening works (the part most likely to need touching)

- It is Valve's chamber 00 with its entity list replaced through a lump file
  (`maps/testchmb_a_00_l_0.lmp`), plus a pack in `portal/custom/pc-intro` (model, material, particles,
  sounds). `make_intro.py` writes both from this PC's Portal; `opening off` removes them.
- The ceiling "portal" is a `prop_dynamic` with the spiral material, not a real portal. The player
  starts in Valve's unlit side room at (-3930, 1184), a `point_viewcontrol` on the vault floor looks
  up, the prop and its particle ring appear, a `point_teleport` drops the player under it.
- Positions and timings are the constants at the top of `make_intro.py` (`PORTAL`, `DROP`, `CAMERA`,
  `AIM`, `APPEAR`, `FALL`, `GONE`, `RELEASE`).
- The plugin draws Steve under a scene camera only when the map contains the model
  `portalcraft/pc_portal` (`openingSceneHere`), so Valve's own cameras are unaffected.
- Sounds: recordings placed as `tools/intro/open.wav`, `pass.wav`, `close.wav` are used if present
  (they are, cut from a YouTube clip of the show's portal sound; git-ignored, kept off the public
  repo). Without them `sounds.py` synthesizes three. **The clip-to-role mapping was done without
  listening**: he said it sounded good, but A/B/C/D in `run/devtests/out/rm-clips` can be swapped.

## Open items, roughly in his order of interest

1. **Globs missing on game-spawned wall portals** in the green skin (he reported it; not looked at).
2. **Package the green skin as a standalone mod.** The generator builds from the user's own Portal,
   so nothing of Valve's ships; the show's audio must stay out.
3. **A second green portal at the end of the video** (same trick on the last map).
4. **Portal 2** (`docs/PORTAL2_PLAN.md`, `PORTAL2_SCOPE.md`). Open questions: co-op shape, shared or
   separate Minecraft world, same repo or a new folder (his Rule 7 says new projects get their own).
5. **Shared props for co-op** are written but never tested with two players.
6. Small things he may raise: particle-light brightness (`SPARK_GAIN` in `LightExporter`), flame
   size (`FLAME_SIZE` in `EntityExporter`), a distinct look for the blue-only gun, the opening's
   portal popping in and out with no grow/shrink, the "CRAFT" lettering being hand-drawn block
   letters rather than Minecraft's logo style.

## Not verified (say so if asked)

- The GLaDOS fight and credits as one played sequence.
- `suite.sh` after `stable-17`.
- Loading a save made in chamber 00 after the opening; the rest of chamber 00 beyond ten seconds.
- The release installer and co-op on a second PC, real accounts or the internet.

## Rules that bit this session

- **Never add a Claude co-author trailer to commits.** He is the author.
- **One test pair at a time unless about 10 GB of commit memory is free.** His PC often has Roblox
  Studio and other sessions eating it; check before launching, and never close his things.
- **Don't drive or restart his game while he is in it.** Test on pair 1
  (`.claude/worktrees/integration`, `PCI=1`); merge `main` into it first, since it runs its own Java.
- **A running Portal locks the plugin file.** Rename the old one aside and copy the new one in; his
  running game keeps the old build until he restarts.
- **`custom/` packs, lump files and the plugin are read when Portal starts.** Every change there
  needs a restart to show.
- **Bash heredocs on this PC eat backslashes.** `\n`, `\\` and regex escapes arrive mangled; write
  such files with the Write/Edit tools or build the characters in code.
- **Several `host/portal/src/*.cpp` files are stored with CRLF.** If a commit shows a whole file
  changed, re-add it (`git -c core.autocrlf=false add` for `plugin.cpp`; `git add --renormalize`
  put `overlay.cpp` back).
- **Screenshots:** Portal's own `jpeg` command misses Minecraft's hand and HUD (drawn at present
  time). `PrintWindow` on the window captures everything and works on his desktop now.
