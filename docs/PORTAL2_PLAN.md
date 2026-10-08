# Portal 2 port: plan

Date: 2026-10-08. Read-only: the repo at `fe16911` (Portal 1 at `stable-11`), the installed Portal 2
(PatchVersion 2.0.0.1; stamps engine.dll `6aa0745d`, server.dll `6aa0746a`, client.dll `6aa07473`, vphysics.dll
`6a4466dd`), `docs/PORTAL2_SCOPE.md`, the census files, and the web. Nothing was built or launched. Source tags:
**disk** (read from the install or repo), **web** (a page read today, named), **memory** (how Portal 2 plays; unverified).

## 1. Verdict

"We know how so much works and already have it working in Source" is half right. The *design* carries over whole:
Minecraft owns Steve, Portal's player is written where Steve is after Portal's own move, Portal teleports its own
player and Minecraft matches, movers place him relatively, Minecraft collides against a copy of the map. The Java
side (~9,000 lines) carries over nearly unchanged. The wrong half is "working in Source": the plugin's contact with
the engine is a table of vtable slots and struct offsets found by disassembling Portal 1's DLLs, and Portal 2 is
a different branch (Alien Swarm era: `VEngineServer022`, `VEngineClient015`, `VClient016`, `EngineTraceServer004`,
60 Hz server **disk**). Every slot is re-found. That is bounded work, and this time mostly looked up, because two
public sources cover this branch (section 2).

What is new is the game: five mechanics that each move the player (faith plates in 35 of 63 maps, funnels, light
bridges, three gels, lasers), 3.5x the maps, walkable displacement terrain in old Aperture, and a campaign with
far more scripting.

| Bucket | Portal 1 lines | What happens |
|---|---|---|
| Minecraft mod `src/` | ~9,000 | Reused. Adds a game profile (paths, map list, no fall damage), displacement collision, three mechanic simulations, new class lists. |
| Protocol | one header | Reused; a few new fields (paint power, beam membership, a cause-named impulse). |
| Plugin logic: reconciliation, crossings, health, entity stream, blocks, launcher, overlay | ~5,900 | Reused as code; every engine slot and layout re-found. |
| Render hooks `worldrender.cpp`, `camera.cpp` | ~1,600 | Reused; IVRenderView and client-mode slots re-found; same D3D9 design **disk**. |
| New mechanics, displacements, Portal 2 scripting | 0 | New. |

Sizes at the pace Portal 1 went (10-01 to 10-08: about eight working days to `stable-11`), single player:

| Work | Days | Depends on |
|---|---|---|
| Plugin on the new engine: loads, slots, layouts, blocks drawn, camera | 2-4 | `addons/*.vdf` autoload (10-minute test); SAR's table coverage; whether SP client prediction is on (phase 1) |
| Java profile + BSP v21 + static props v9 + displacements | 1-3 | displacement reader only |
| Walk, portals, lifts, doors, cubes, buttons, level changes | 2-4 | bone-follower doors, `prop_weighted_cube` |
| Faith plates | 1-2 | reading the launch Portal computes |
| Light bridges | 0.5-1 | entity stream already carrying them |
| Funnels | 2-4 | measuring Portal 2's in-beam movement |
| Gels | 2-4 | player paint-power netprop; wall bounces |
| Lasers, crushers, turrets | 0.5-1 | same damage path |
| Scripted sequences + 63-map walkthrough | 4-8 | Portal 1's was 2-4 days for 18 maps |
| **Single player** | **15-30** | |
| Co-op (section 6) | +5 to +15 | the prediction question |

So: not easier; two to three times Portal 1, with less uncertainty per item but more items.

## 2. What replaces the missing game source

| Source | What it gives |
|---|---|
| **SourceAutoRecord** (`p2sr/SourceAutoRecord`, MIT) **web**, fetched via `gh api` | A maintained offset table for Portal 2 build 10090, Windows and Linux: `src/Offsets/Portal 2 10090.hpp`. Confirmed: CGameMovement `ProcessMovement` slot 1, `mv` +8, `player` +4, `PlayerMove` 17, `AirMove` 25, `FinishGravity` 34, `CheckJumpButton` 36; CEngineClient `Con_IsVisible` 11, `GetViewAngles` 18, `SetViewAngles` 19, `IsPaused` 86, `GetLightForPoint` 1; CHLClient `HudProcessInput` 10, `IN_ActivateMouse` 15 / `Deactivate` 16; ClientModeShared `OverrideView` 18; CInput `ActivateMouse` 27. Shifts from Portal 1: `GetViewAngles` 19->18, `IN_ActivateMouse` 14->15, `OverrideView` 17->18, `IInput::ActivateMouse` 18->27. Its `Server.cpp` reads `m_vecAbsOrigin` and `m_PortalLocal` by send-table name, as our `findProp` does: that path is proven on this build. |
| **Alien Swarm SDK** (Valve, public; mirror `pancho7532/alienswarm-sdk`) **web** | Headers with exactly Portal 2's strings: `VEngineServer022`, `ServerGameDLL005`, `ServerGameClients004`, `VClient016`, `EngineTraceServer004`, `ISERVERPLUGINCALLBACKS003`. `IEngineTrace::TraceRay` is slot **5** (a `GetPointContents_WorldOnly` was inserted); `edict_t` has an int serial and no `freetime`, so the 20-byte stride is wrong (the self-check exists). Generic game code: `gamemovement.cpp`, `triggers.cpp`, `trains.cpp`, `func_movelinear.cpp`, `doors.cpp`, `physics_main.cpp`, `player.cpp`, `CMoveData`, `CTakeDamageInfo`. No portal, paint, catapult or beam code. Its client header is `VEngineClient013`; use SAR for client slots. `VSERVERTOOLS` version: read from server.dll. |
| **Portal-Base** (on disk) | The portal rules (centre 36/18, forced duck, exit speeds). Portal 2's `prop_portal` descends from it (**memory**); verify each rule once. |
| **Portal 2's binaries** **disk** | Class strings (`CTriggerCatapult`, `CTrigger_TractorBeam`, `CProjectedTractorBeamEntity`, `CPaintDatabase`, `CPortalPlayerLocalData::m_PaintedPowerTimer`), convar descriptions ("At what speed the player will auto-bounce when running over bounce paint", "For tweaking the max speed for speed paint"), catapult log lines ("adjusting velocity of ... so it will hit the target"). `cvarlist` prints defaults without cheats. The plugin can log every ServerClass send table at load: our own netprop dump, half a day. A 2018 Portal 2 datamap dump is listed on VDC's dump list **web** (the wiki blocked my fetcher). |
| Not usable | Portal 2: Community Edition / Strata (licensed Valve code, closed **web**). The 2023 "Teraleak" early Portal 2 builds (exist **web**; the repo is public MIT: no). |

**Plugin loading.** engine.dll contains `addons/*.vdf`, `plugin_load`, `-insecure` **disk**. Speedrunners load SAR
with `plugin_load sar` from the console, DLL in `Portal 2\` or `Portal 2\portal2\`, no `-insecure` mentioned
(`wiki.portal2.sr/Plugins` **web**). The Feb 2025 `plugin_load` change was the SDK 2013 branch (Portal 1). Whether
`portal2\addons\portalcraft.vdf` autoloads is the first test; `plugin_load` in `autoexec.cfg` is the fallback.

## 3. Repository strategy

Same repo, `host/portal2/`, sharing `src/` and `protocol/`. A fork duplicates the mod and every protocol change.
Start `host/portal2/src` as a copy of `host/portal/src` so Portal 1 stays frozen at `stable-11` while slots are
found; the Java side gets a `HostGame` profile (game dir, VPK, map order for `MapRegions`, fall damage, class
lists). Once Portal 2 runs, pull the unchanged files (`hooks.h`, `instance.h`, `launcher`, `overlay`, `raybox.h`,
link, health, crossings, entity stream) into `host/common/`, keeping per-game `sdk.h` slot tables and mechanic
code apart. One `kCheckedBuilds` per game.

Loader facts **disk**: `pak01_dir.vpk` is VPK v1 (`Vpk.java` reads v1/v2); all 106 maps are BSP v21, no LZMA in any
lump; `sprp` v9 with 72-byte entries, which `StaticProps.java`'s default branch already derives (first 32 bytes
unchanged); `dprp` v4 empty; v21's `dbrushside_t` splits `bevel`/`thin`, harmless since `BspMap` reads only
`planenum`. Displacements: 29 of 106 maps, cosmetic in a1/a2 (1-15 each) but 137-201 per map in `sp_a3_01`,
`sp_a3_03`, `sp_a3_portal_intro`, `sp_a3_transition01`, `sp_a3_end`, 43 in `sp_a4_finale4`: old Aperture's ground.
Portal 1 had none. A reader for lumps 26/29/33 into columns, like sloped brushes, is required by chapter 6.

## 4. Phases

Lessons applied: census first (done); own chamber compiled with the game's compilers at known coordinates
(`bin/vbsp|vvis|vrad.exe` and `portal2.fgd` installed **disk**; extend `tools/testmap/make_testmap.py` with one
catapult, funnel, bridge, gel floor, `linked_portal_door`); let the game teleport its own player; place relative to
movers; hand over only named causes; one test pair while memory is tight.

| # | Phase | Reused / new | Main risk | Done when |
|---|---|---|---|---|
| 0 | Plugin loads | all / interface strings (`VEngineServer022`, `VModelInfoServer002`, `VClient016`), stamps | `addons/*.vdf` ignored | log shows `Load:` with all interfaces non-null in `sp_a1_intro1` |
| 1 | Hooks found | `hookSlot`, self-checks / slot table from SAR + AS SDK (trace 5, edict stride, `CMoveData`, `CTakeDamageInfo`, `ICollideable`); IVRenderView013 and client-mode slots by disassembly | **client prediction**: Portal 1 SP forced `cl_predict 0`; Portal 2's client.dll has `cl_predict_catapults`, `cl_predict_portal_placement` **disk**, so SP may predict, and a predicting client shows a correction every tick the server moves its player: jitter by construction | "layout OK", both `GameMovement001` hooks, trace hook stops a shot. Experiment: `cl_predict` in the console, is `0` accepted? If not, the dormant client `ProcessMovement` hook must feed Steve's position to the prediction run |
| 2 | Steve walks in `sp_a1_intro1` | BSP, props, stream, overlay, world draw, camera / profile, `MapRegions` for 105 maps, fall damage off (long-fall boots: none at all **web**), 60 Hz constants | the wake-up room is a mover with a `point_viewproxy` camera (section 5) | walk the moving room; blocks drawn; F5 |
| 3 | Portals | `PlayerCrossings`, holes, match / netprops re-found; snapping native | `linked_portal_door` (6: `sp_a1_wakeup`, `sp_a4_finale2`; 64x64 and 64x128 **disk**) needs a per-portal hole size, not the 64x108 constant | suite portal checks in the own chamber; through a `linked_portal_door` |
| 4 | Movers, doors, level changes | `MoverRide` (`func_movelinear` is already a mover class) / `prop_testchamber_door` (98) and panel arms are animated models: if they collide via `phys_bone_follower` the stream must admit that class; `point_changelevel` + `trigger_transition` | bone followers | lift `intro1`->`intro2`; a chamber door; `sweep.sh` loads all 63 |
| 5 | Cubes, buttons, turrets | `clampToProps`, plates, `kEntityLoose` / classes `prop_weighted_cube`, `prop_monster_box`, `npc_personality_core`, `prop_paint_bomb`, `prop_exploding_futbol` | none new | `cubetest` in `sp_a2_fizzler_intro` |
| 6 | Displacements | column cutting / lump reader | cave slopes | `sp_a3_01` floor holds |
| 7 | Faith plates | `PortalAir` / cause-named impulse (4.1) | reading the launch | own chamber lands within 16 u of `launchTarget`; `sp_a2_catapult_intro` |
| 8 | Light bridges | entity stream OBB boxes / maybe nothing | a runtime collide with no `.phy` falls to the bounds box: fine for a slab | `sp_a2_bridge_intro`: stand, walk under, switch off |
| 9 | Funnels | zero-g path / beam cylinders + membership (4.2) | lateral control fidelity, polarity flips | own chamber; `sp_a4_jump_polarity` |
| 10 | Gels | `HostScale` / paint power in `HostState` (4.3) | wall bounces; gel never paints Steve's blocks | `sp_a3_speed_ramp`, `sp_a3_crazy_box` |
| 11 | Lasers, crushers | damage refund; blocks stop lasers via the trace hook / class lists | none | `sp_a2_laser_intro`; `sp_a4_finale1` |
| 12 | Scripted + walkthrough | scripted mode, replay auto-dumps / section 5 | the two unfrozen cameras | every `sp_` replay passes on a clean world |

### 4.1 Faith plates (`trigger_catapult`: 116 SP, 69 co-op)

**disk** (FGD, server.dll): a brush trigger that, on touch, solves a launch so the player reaches `launchTarget` at
`playerSpeed` (or along `launchDirection`), optionally exact, optionally only if the entry speed is within
`lowerThreshold..upperThreshold`, suppresses air control for `AirCtrlSupressionTime` (default a quarter second),
and writes the velocity once; predicted on the client. Campaign `playerSpeed` 200-710.

Owner: **Portal computes, Minecraft flies.** Portal's player stands where Steve is, so its trigger fires with
Steve's entry velocity (threshold checks free) and Portal does the solve against its own targets. Portal's player
cannot fly (overwritten next tick), so the plugin hands the velocity Portal just wrote to Minecraft as a one-shot
impulse: the `kMoveImpulse` path removed in `stable-9`, back *cause-named*, only while the hull overlaps an enabled
`trigger_catapult` (or on `OnCatapulted`, hooked via the entity's inputs as SAR does). Minecraft flies under
`PortalAir` (same gravity, no drag) with air control off for the quarter second, so the arc lands where Portal's
would; nothing to tune. Settling experiment: log velocity before/after `ProcessMovement` on
`sp_a2_catapult_intro`'s `player_catapult`.

### 4.2 Funnels (`prop_tractor_beam` -> `projected_tractor_beam_entity` + `trigger_tractorbeam`)

A projected entity chain (one per portal hop), `linearForce` 240-250 **disk**, reversible; inside, the player is
weightless, carried along the axis, may drift sideways and leave any time, cannot move against the flow (VDC,
Portal wiki **web**). server.dll has `ExitTractorBeamThink`.

Owner: **Minecraft simulates from streamed state; Portal says who is inside.** Stream each beam as a cylinder
(origin, axis, length from bounds, force sign); the plugin also sends whether Portal's player is in a beam (a
`CPortal_Player` netprop, name from the dump; fallback: hull inside a `trigger_tractorbeam`). Inside, Minecraft
zeroes gravity, sets along-axis speed to the force and a measured lateral speed; on exit, keeps velocity. Letting
Portal "own" the ride would mean handing back every tick's delta: the magnitude-classifier trap the audit named.
Open: lateral speed and whether Portal re-centres on the axis; measure once with the trace tool.

### 4.3 Gels (`info_paint_sprayer`: 131 in 12 SP maps)

Bounce (launch on landing, reflect off painted walls, crouch cancels), speed (800 running, 267 crouched, VDC
**web**; acceleration convars **disk**), conversion (portals on paint: native). The player's active power and timer
are networked **disk**.

Owner: **Portal detects, Minecraft moves.** The plugin sends the active power in `HostState`. Speed -> movement
attribute and lower friction; bounce -> on landing or a painted wall hit, set the vertical/reflected speed to the
convar's value (read once); sneaking cancels. Portal's own launch is overwritten and never seen. Limit: gel cannot
land on Steve's blocks.

## 5. The scripted campaign

Portal 1's rule ("`FL_FROZEN`/`FL_ATCONTROLS`/`m_hVehicle` -> the game has the player, Minecraft follows, no input")
covers most of it. From the census **disk**:

| Sequence | Entities | Input? | Treatment |
|---|---|---|---|
| Wake-up, `sp_a1_intro1` | `point_viewproxy` on the moving room; camera flags 32 (non-solid, **not** frozen); 17 choreo scenes | walk, jump ("say apple": `logic_playerproxy`) | Mover ride with Portal's camera on a proxy, not scripted mode; the eye/F5 hooks stand down while the view is off the eye (they check) |
| Wheatley carries the room, `sp_a1_wakeup` | tracktrains, `hack_player_teleport` x3, cameras flags 12 and 4 (frozen) | little | hard teleports + frozen scenes: handled kinds |
| Chamber shuffle, `sp_a2_intro` | camera flags 36 (frozen) | no | scripted mode |
| Tube rides, `sp_a2_bts5`, `bts6` | viewproxy flags 36, `podtrain_player` | no | scripted mode |
| Core transfer, `sp_a2_core` -> fall | button, lift `exit_elevator_train`; `sp_a3_00` fall; `sp_a3_01` camera 28 | press the button | play, lift ride, plain fall (no damage), frozen |
| Boss and moon, `sp_a4_finale4` | bombs, cores, gel; `ending_vehicle` (`prop_vehicle_choreo_generic`), camera flags 8 | yes until the moon shot | carry cores (loose class), native shot, then `m_hVehicle` -> scripted, as the `late-game` branch does |

Gap: two SP cameras carry no freeze flag (`intro1` 32, `finale4` 8). If Portal 2 networks `m_hViewEntity` (Portal 1
did not) use it; else key on the vehicle and the viewproxy entity.

## 6. Co-op

42 maps, two players with two portals each, `info_coop_spawn` per team, `trigger_playerteam`,
`point_viewcontrol_multiplayer` **disk**.

| Shape | What it takes | Size |
|---|---|---|
| **Host is Steve, friend a stock P-body** | Friend joins the host's listen server with plain Portal 2; the plugin already drives edict 1. The friend sees a stock model for Steve and *invisible walls* at his blocks (server-side boxes), so shipping needs a client DLL on the friend's PC drawing a mesh streamed over LAN. | +5 days |
| **Two Steves, networked** | Both run Portal 2 + Minecraft; Minecraft clients share one LAN world (free); the host plugin writes both players from two `McState` streams; the friend's client predicts and is corrected every tick: rubber-banding unless prediction can be off or driven from Minecraft client-side. | +10-15 days, gated on phase 1 |
| **Two Steves, split screen** | `ss_map mp_coop_start` puts both in one process on one PC (VDC **web**): no networking, no prediction; two Minecraft clients (`-pcinstance` ports exist); two viewports for the draw. | +8-12 days; a test rig, poor for a remote friend |

Recommendation: add a player index to the protocol now (cheap), build nothing until SP is beatable, then shape 1.
Day-1 scoping: `ss_map mp_coop_start`, check the log shows two player edicts.

## 7. First three days

1. Rename interface strings, add the stamps, install `portal2\addons\portalcraft.vdf`, launch `sp_a1_intro1`:
   autoload or `plugin_load` fallback. Add a `dumpnetprops` dev command. Read `cl_predict`; try `cl_predict 0`.
   `ss_map mp_coop_start` once.
2. Slot table from SAR's `Portal 2 10090.hpp` and the Alien Swarm headers; re-derive `IVRenderView013` and
   client-mode slots from the DLLs; reach "layout OK", both movement hooks, the trace hook, blocks drawn. Extend
   `make_testmap.py` with catapult, funnel, bridge, gel floor; compile with Portal 2's `vbsp`.
3. Java game profile (paths, VPK, map list, regions, fall damage off); BSP v21 confirmed; Steve stands and walks in
   the wake-up room as it moves; first Portal 2 entries in `suite.sh`.

## 8. Open questions

1. Co-op shape: host-only Steve (days) or two Steves (weeks)? Decides the player index now and whether the
   prediction experiment is a note or a gate.
2. One Minecraft world for both games (Portal 2 regions after Portal 1's) or a separate world? Decides
   `MapRegions` and the seed world.
3. Keep Portal 1 frozen and accept a copied plugin tree for the first weeks, or one plugin source from day one?
