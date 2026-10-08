# Portal 2: what is on disk (scoped 2026-10-08)

Facts read from the installed game (`D:\SteamLibrary\steamapps\common\Portal 2`, 12 GB), with `tools/map_census.py`
and a string scan of its binaries. Nothing here was run in the game. The full per-class tables are
`docs/portal2_census_sp.txt` and `docs/portal2_census_coop.txt`.

## The game

| | Portal (what PortalCraft runs on) | Portal 2 |
|---|---|---|
| Binaries | 32-bit, `hl2.exe` | 32-bit, `portal2.exe` (`engine.dll`, `server.dll`, `client.dll` all machine 0x14c) |
| Maps | 18 single player, BSP v20 | 63 single player + 42 co-op + 11 DLC, BSP v21, loose files in `portal2/maps` |
| Map tools | `hammer.exe`, `vbsp`, `vvis`, `vrad` in `bin` | `vbsp`, `vvis`, `vrad` in `bin`; Hammer comes with the free Portal 2 Authoring Tools (not installed); `sdk_content` is present |
| Renderer | Direct3D 9 | Direct3D 9 (`shaderapidx9.dll`), with a Vulkan path shipped beside it (`shaderapivk.dll`, `dxvk_d3d9.dll`) |
| Server plugins | `ISERVERPLUGINCALLBACKS003` | `ISERVERPLUGINCALLBACKS003` (001-003 all present in `engine.dll`) |
| Game source | Portal mod base on SDK 2013 (public) | none public |

Interface versions found in the binaries (ours in brackets where they differ): `VEngineServer022`, `VEngineClient015`
[013], `EngineTraceServer004` / `EngineTraceClient004` [003], `VEngineRenderView013`, `GameMovement001`,
`PlayerInfoManager002`, `ServerGameDLL005`, `ServerGameClients004`, `VClient016`, `VMaterialSystem080`, `VPhysics031`.
The names the plugin asks for mostly exist; the slots and struct layouts behind them have to be found again.

## Single player: what the 63 maps are made of

Things Steve rides (parent counts show what the visible model sits on):

| Class | Count | Maps | Notes |
|---|---|---|---|
| `prop_dynamic` | 5729 | 62 | 1043 parented to `func_tracktrain`, 229 to `func_movelinear`, 161 to `func_door` |
| `func_tracktrain` | 195 | 61 | every map: lifts, trains, moving panels |
| `func_movelinear` | 119 | 28 | new for us (Portal has none): pistons, panels, platforms |
| `func_door` / `func_door_rotating` | 195 / 152 | 32 / 41 | |
| `prop_testchamber_door` | 98 | 42 | new class |
| `func_rotating` | 16 | 11 | |

Things he carries or pushes: `prop_weighted_cube` 55 (32 maps; a new class, not `prop_physics`), `prop_physics` 125,
`prop_physics_override` 120, `prop_monster_box` 22, `npc_portal_turret_floor` 77, `func_physbox` 81, `prop_paint_bomb` 8,
`prop_exploding_futbol` 7, `npc_personality_core` 21.

Things that act on the player:

| Class | Count | Maps | What it is |
|---|---|---|---|
| `trigger_catapult` | 116 | 35 | aerial faith plates: launch the player on a set arc |
| `trigger_push` | 73 | 32 | air currents (Portal: 30) |
| `trigger_teleport` | 43 | 40 | 37 ride a `func_tracktrain`: the lift between maps |
| `trigger_hurt` | 89 | 29 | |
| `prop_tractor_beam` | 13 | 12 | excursion funnels: carry the player along a beam |
| `prop_wall_projector` | 9 | 7 | hard light bridges: a solid surface that switches on and off |
| `info_paint_sprayer` | 131 | 12 | gels: bounce, speed, portal-able surfaces |
| `env_portal_laser` / `prop_laser_catcher` / `prop_laser_relay` | 20 / 22 / 9 | 17 / 15 / 3 | lasers (hurt the player, power things) |
| `linked_portal_door` | 6 | 2 | fixed portals in doorways |

Scripting: `logic_script` 201 (VScript, every map), `logic_choreographed_scene` 33 (10 maps), `point_viewcontrol` 6
(5 maps), `point_teleport` 128 (61 maps), `env_fade` 117, `point_changelevel` 62, `npc_wheatley_boss` 15,
`prop_vehicle_choreo_generic` 1. Level changes are by `point_changelevel` and `trigger_transition`, one per map,
not Portal's `trigger_changelevel` lifts.

## Co-op: the 42 maps

`trigger_playerteam` 645, `logic_coop_manager` 291, `info_coop_spawn` 127, `point_viewcontrol_multiplayer` 68,
`trigger_catapult` 69, `func_movelinear` 75, `prop_weighted_cube` 43, `prop_wall_projector` 22, `prop_tractor_beam` 12,
`info_paint_sprayer` 27. Two players, each with two portals: four portals in a map.

## What this says before any code

- The riding model (place the player relative to what he stands on) covers `func_tracktrain`, `func_movelinear`,
  doors and their parented models as it stands, once those classes are admitted.
- Loose props need three new classes at least (`prop_weighted_cube`, `prop_monster_box`, `npc_personality_core`).
- Five mechanics have no counterpart in Portal and each touches Steve's movement: faith plates, funnels, light
  bridges, gels, lasers. Faith plates are in 35 of 63 maps, so they are not optional.
- The plugin side cannot lean on public game source this time.
