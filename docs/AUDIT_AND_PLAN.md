# PortalCraft: audit and plan to "done"

Date: 2026-10-02. Read-only audit of the repo at commit `32a9f7c` (main). Nothing was run; Portal and
Minecraft were not launched. Evidence is cited as `file:line`. Confidence tags: **H** read directly
in code or logs, **M** inferred, **L** educated guess.

Scope of what was read: `README.md`, `THIRD-PARTY-NOTICES.md`, `docs/*.md`, `protocol/portalcraft_protocol.h`,
all of `host/portal/src`, all of `src/`, the tests, `tools/`, `host/portal/tools/`, the last Minecraft
run log (`run/logs/latest.log`), the last plugin log (`Portal/portal/addons/portalcraft.log`), the git
history, and the SkyCraft clone for comparison.

---

## 0. Summary

The project is in better shape than its age (two days of commits) suggests. The hard problems are
solved and verified live: Minecraft physics on Portal brushes, static props and live entities;
portal teleports with momentum; the hand/HUD overlay; the in-scene block pass with correct depth.
The code is small (about 7.7k lines including tests), consistently written, and the dangerous parts
(engine vtable patches, raw offsets) are guarded by runtime self-checks that log `layout OK` /
`LAYOUT MISMATCH`.

What is not done, in order of how much it blocks the goal (viral let's plays with friends):

| Gap | Why it matters for video | Milestone |
|---|---|---|
| Block rendering and placement not yet verified live; blocks never show through portals; blocks are unlit (full-bright with AO only) | The signature shot is "Minecraft blocks inside a test chamber, seen through a portal" | M0, M2 |
| Only placed blocks exist in Portal. Items, mobs, other players, particles, break cracks, the block outline are invisible | No creepers, no TNT, no friends on screen | M3 |
| Input is incomplete: no mouse wheel, no text input, no `/` key, Esc never reaches Minecraft, no cursor for inventories | Can't open a chest, type a command, or scroll the hotbar on camera | M1 |
| Minecraft blocks are air to Portal's physics | A cube falls through your bridge; turrets shoot through your wall | M4 |
| Every Portal map shares one Minecraft coordinate space | Blocks from chamber 00 float in chamber 01 | M5 |
| Nothing is multiplayer-aware in host mode | "With friends" | M6 |
| Runs only from a Gradle dev client with hardcoded paths | Friends can't install it | M7 |

Three things should change **before** more is built on them (details in section 7): the keyboard
polling input model, the D3D device discovery scan, and per-frame `DrawPrimitiveUP` of the whole
world mesh. Two safety holes should be closed now: the plugin does not force `mat_queue_mode 0` and
will race D3D9 from two threads if the launch flag is dropped, and the plugin never unhooks on
`Unload`.

---

## 1. Architecture as built

| Channel | Direction | Rate | Code |
|---|---|---|---|
| `HostState` (228 B UDP, 127.0.0.1:27516) | Portal -> MC | every server frame (66 Hz) | `plugin.cpp:661-699`, `Proto.java:52-83` |
| `McState` (44 B UDP, :27515) | MC -> Portal | every render frame (<=144 fps) | `HostDriver.java:144-159`, `plugin.cpp:174-180` |
| `HostEntities` (<=13.8 KB UDP) | Portal -> MC | ~16 Hz | `plugin.cpp:564-631`, `HostLink.java:60-66` |
| Dev packets `PCC1` `PCK1` `PCT1` (to Portal), `PCD1` (to MC) | anyone local | ad hoc | `plugin.cpp:181-198`, `HostLink.java:55-59` |
| Overlay mapping `Local\PortalCraft_Overlay_v1` (42.2 MB) | MC -> Portal | every MC frame | `OverlayLink.java`, `overlay.cpp:103-233` |
| World mapping `Local\PortalCraft_World_v1` (25.0 MB) | MC -> Portal | on change (<=4/s in bulk) | `WorldLink.java`, `worldrender.cpp:94-206` |

Hooks inside Portal (all vtable patches, no byte patterns):

| Hook | Where | Runtime check |
|---|---|---|
| `IGameMovement::ProcessMovement` slot 1, server and client | `plugin.cpp:740-743`, `:414-423` | `CMoveData` origin vs `IPlayerInfo` origin, `plugin.cpp:309-317` (H, log line 16 "layout OK") |
| `IDirect3DDevice9::Present/PresentEx/Reset/ResetEx` on the shared d3d9 vtable | `overlay.cpp:274-297` | first-draw log only |
| `IVRenderView::SceneEnd` slot 9 | `worldrender.cpp:260-266` | "N SceneEnd calls this frame" log, `worldrender.cpp:222-228` |
| `ICollideable` slots 3/4/9/10/11/13/14/16 (reads, not hooks) | `plugin.cpp:494` | player's origin + solid type, `plugin.cpp:513-533` (H, log line 14) |
| `prop_portal` fields by SendTable name | `plugin.cpp:115-128` | offset-free by design |

Minecraft side: `HostDriver.tick` (20 Hz client tick) applies the host camera, keys, teleports and
entities; `HostDriver.frame` (every render frame) reports position and runs `WorldExporter`.
`HostCollision` injects the map as `VoxelShape`s through `BlockCollisionsMixin` and `ClipContextMixin`.
Everything client-side runs on the render thread (Minecraft's client tick is on it); only
`HostCollision` is touched from the integrated-server thread and it uses `ConcurrentHashMap`s and
volatiles (`HostCollision.java:43-53`).

---

## 2. Audit: correctness and robustness

Severity: **Crash** (takes Portal down), **Bug** (wrong behaviour), **Perf**, **Debt**.

### 2.1 Plugin hooks

| # | Sev | Finding | Evidence | Fix |
|---|---|---|---|---|
| P1 | **Crash** (latent) | The plugin issues D3D9 calls from `SceneEnd` and `Present` and assumes they run on the thread that owns the device. That holds only because `play-portal.cmd` passes `+mat_queue_mode 0`. With queued rendering (Portal's default is -1 = auto, which picks 2 on multicore), `SceneEnd` runs on the main thread while the material system issues D3D from a worker: two threads on a non-`D3DCREATE_MULTITHREADED` device, and the block draw would also land before the queued scene (wrong depth). Nothing in the plugin checks or forces the cvar. | `play-portal.cmd:11`, `worldrender.cpp:208-218`, `overlay.cpp:235-245` | At `Load`/first `GameFrame`, force `mat_queue_mode 0` through `ServerCommand` (or `ICvar`), and read it back; if it isn't 0, disable both render hooks and log. (M that Portal's queue mode behaves as SDK 2013.) |
| P2 | **Crash** (on unload/exit) | `Unload` only logs. The four d3d9 vtable slots, the `SceneEnd` slot and both `ProcessMovement` slots keep pointing into the DLL after it is unmapped. `plugin_unload`, or engine teardown order at exit, then calls freed code. | `plugin.cpp:747-749`, `overlay.cpp:260-272`, `worldrender.cpp:260-266` | Restore every patched slot in `Unload` (keep the originals you already store), and additionally pin the module (`GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_PIN, ...)`) so an unload can never unmap it. |
| P3 | **Crash** (on Portal update) | `WorldToScreenMatrix` is read as `const float*` from VEngineClient013 slot 36 and 16 floats are dereferenced with no validation. If a Portal update shifts the slot, the returned value is whatever that function returns, and `m[c*4+r]` faults inside the render path. Same class of risk for slots 11/19/84 (`Con_IsVisible`, `GetViewAngles`, `IsPaused`) and `IVRenderView` slot 9, none of which has a self-check like the two that exist. | `worldrender.cpp:42-44`, `:105`, `:140-144`; `sdk.h:100-104` | (a) SEH-guard the matrix read and sanity-check it (finite, row 3 looks like a perspective row); (b) add a build fingerprint gate: at `Load`, read `steam.inf` `PatchVersion` (currently 1745010 per `docs/PORTAL_RTX_RESEARCH.md:15`) and the PE `TimeDateStamp`/`SizeOfImage` of `engine.dll`, `client.dll`, `server.dll`; on an unknown build run in "link-only" mode with no hooks and a clear log line. This is the pattern the head-tracking mod uses (`docs/PORTAL_RTX_RESEARCH.md:99-106`). |
| P4 | **Bug** (M) | The block pass draws only in the first `SceneEnd` of a frame and assumes that is the main view. In Source, portal views are rendered recursively inside the main view's draw, so with two portals open the first `SceneEnd` may be a portal view (whose camera `WorldToScreenMatrix` does not describe). The live verification ("test cube hidden by the floor") was done with `fake_world.py` and, from the log, with "1 SceneEnd calls this frame" i.e. no portals open. | `worldrender.cpp:208-218`; plugin log lines 20-22, 38-39 | Verify with both portals open and the cube in view. The real fix is per-view camera capture (section 7, item 2): hook `IVRenderView::ViewSetup3D` (interface slot, pattern-free) to record each view's `CViewSetup`, then draw in every view with its own matrix. That is also what makes blocks visible *through* portals. |
| P5 | **Debt/Crash-adjacent** | `findGameDevice` scans every writable section of `shaderapidx9.dll`, and one level of heap objects behind it, calling `QueryInterface` on any pointer whose first word lies inside `d3d9.dll`. The SEH guard catches access violations but not a real d3d9 function entered with a wrong `this` that writes before it faults. It worked here, but it is unnecessary: `hkPresent` receives Portal's real device as its first argument. | `overlay.cpp:363-406`, `:235-239` | Capture `g_gameDevice` from the first `Present` whose back buffer is larger than the 64x64 probe (`overlay.cpp:107-112` already fetches the back buffer). Delete the scan. `worldrender` then has a device one frame later, which is fine. |
| P6 | **Perf** | `DrawPrimitiveUP` with up to 196,608 vertices x 24 B = 4.7 MB per call, twice (solid + translucent), every frame. UP draws copy through the runtime's internal buffer each call; across the Remix bridge (M9) that becomes 4.7 MB of IPC per frame. | `worldrender.cpp:187-198` | Keep a `D3DPOOL_DEFAULT` dynamic vertex buffer per slot, upload only when `meshSeq` changes, draw with `DrawPrimitive`. Recreate on `Reset` (the hook exists, `overlay.cpp:247-255`). |
| P7 | **Perf** | Two full `D3DSBT_ALL` state blocks captured and applied every frame (overlay + world). | `overlay.cpp:159-163, 231`, `worldrender.cpp:130-134, 200` | Acceptable now; switch to `D3DSBT_PIXELSTATE`/explicit saves later. |
| P8 | **Bug** (M) | Keys are polled with `GetAsyncKeyState` once per server frame. A tap shorter than one frame can be missed, key repeat for typing doesn't exist, and the mouse wheel cannot be read this way at all. The map has no `/`, backspace, escape, arrows or punctuation, so chat and commands can't be typed. | `plugin.cpp:209-217`, `:232-241` | Section 7, item 1: subclass Portal's window procedure. |
| P9 | **Bug** (M-H) | Characters never reach Minecraft. `HostDriver.press` sends `keyPress` events; chat, the anvil, signs and command input need `charTyped` (SDL text input). T opens chat; letters probably don't appear. | `HostDriver.java:332-336` | Forward `WM_CHAR` (from the WndProc subclass) as a new `HostState` text field; call `keyboardHandler.charTyped`. |
| P10 | **Bug** (M) | Both guns are probably on screen when Steve holds the Minecraft portal gun: Minecraft draws its hand holding the `portal_gun` item into the overlay, and nothing turns Portal's own viewmodel off or on (`r_drawviewmodel` never appears in the plugin). The research doc already proposed the policy (hide the MC hand while holding the gun, show Portal's gun; otherwise the reverse). | `HostDriver.java:159`, `plugin.cpp:289-292`, `docs/PORTAL_RTX_RESEARCH.md:132` | Add `screen`/`hand` policy to M2. |
| P11 | **Debt** | The `zLift` heuristic (`g_zLift`, up to 4 units, dropped when the player walks 24 units away) papers over Portal resting the player a hair above Minecraft's floor. It works, but it is invisible state that will confuse the next person debugging bob. | `plugin.cpp:261-279`, `:332-339` | Keep, but log when it changes (dev mode) and add it to `HostState` so MC can see it. |
| P12 | **Debt** | `sendEntities` filters triggers by classname prefix and by `FSOLID_TRIGGER/NOT_SOLID`, caps at 128 entities within 2048 units, and `g_entityEdicts` is rescanned every 30 frames. Large maps (escape_02) could exceed 128 solids in range; the overflow is silently dropped (nearest-first is not applied). | `plugin.cpp:580-581`, `:598` | Sort by distance before truncating, or raise `kMaxHostEntities` (the datagram has room up to ~64 KB on loopback). |
| P13 | **Bug** (L) | `fillPortals` keeps, per colour, the nearest *active* portal. Portal 1 maps have multiple linkage groups (e.g. the fixed portals in the relaxation vault). If a map-placed portal of the same colour is nearer than the player's, Minecraft carves the wrong hole. Mitigated by `-1e6` bias for active ones only. | `plugin.cpp:473-478` | Prefer the player's own gun's portals (`m_iLinkageGroupID` == the player's, readable by SendTable name like the other fields). |

### 2.2 Protocol and threading

| # | Sev | Finding | Evidence | Fix |
|---|---|---|---|---|
| T1 | **Bug** (L) | `HostState.seq` and `HostEntities.seq` are never used for ordering on the Minecraft side; the newest *received* packet wins. Loopback UDP preserves order in practice, so this is theoretical. | `HostLink.java:67-71`, `LiveEntities.java:50-53` | Drop packets with `seq <= last`. |
| T2 | **Bug** (M) | `HostLink.receiveLoop` returns on the first `IOException`, and nothing restarts the thread; the link is dead for the rest of the session. On Windows, sends to a closed port can surface as a receive error on an unconnected UDP socket (WSAECONNRESET); the JDK normally absorbs it, but any other error is fatal here. | `HostLink.java:72-75` | Log and `continue`; only exit when the channel is closed. |
| T3 | **OK** | Overlay triple buffer and world double buffer are correct: MC never writes `front` or `reading`; the host re-checks `front` after claiming (`worldrender.cpp:112-122`); MC backs off when both slots are held (`WorldExporter.java:282-285`). Release fences on the Java side and `MemoryBarrier` on the host side are right for x86. | `OverlayLink.java:115-132`, `WorldLink.java:147-177` | None. |
| T4 | **OK** | Header values from shared memory are validated before use (slot index, sizes, vertex counts), so a hostile or stale writer can only produce garbage pixels, never an out-of-bounds read. | `overlay.cpp:128-137`, `worldrender.cpp:47-49, 123-127` | None. |
| T5 | **Bug** (M) | The `HostCollision` fixed cache is **fully cleared on every portal shot** (`setPortals` -> `CACHE.clear()` and `DYNAMIC_CACHE.clear()`). Sloped cells cost milliseconds each to rebuild (the comment in `setDynamic` says so), so the frames after a shot on a slope-heavy map will hitch as the player moves. | `HostCollision.java:154-158`, `:102-103` | Invalidate only cells intersecting the old and new holes (same pattern as `setDynamic`'s dirty boxes). |
| T6 | **OK** | `setDynamic` from the client thread vs `shapeAt` on the server thread: `computeIfAbsent` holds the bin lock during `build`, and `remove` waits for it, so a stale value cannot survive an invalidation. The volatile `dynamic` is read inside the lambda. | `HostCollision.java:94-120`, `:189-190` | None. Worth a comment. |
| T7 | **Debt** | Protocol version is only the trailing digit in each magic. There is no capability or version handshake, no `gameDir`, no FOV, no view offset, no roll, no health, no "MC screen open", no cursor, no wheel, no text. Several of these are needed by M1/M2/M7. | `portalcraft_protocol.h:45-77` | Bump to `PCH2`/`PCM2` with the new fields in one go (section 7, item 4). |
| T8 | **Debt** | `HostEntity.flags` (`kEntityStatic`) and `HostState.velocity` are sent but never read; `kHostDriving` likewise; `Proto.PORTAL_EXISTS/ACTIVE` unused. | `plugin.cpp:612-614`, `Proto.java:16-22` | Remove or use. |
| T9 | **Perf** | Minecraft reads back and copies its full frame at up to 144 fps (`FramerateLimitTrackerMixin`), 3.7 MB at 720p, 14.7 MB at 1440p, while Portal presents at its own rate. | `FramerateLimitTrackerMixin.java:16`, `FrameExporter.java:89-101` | Cap Minecraft at the host's present rate (send it in `HostState`), or 72. |

### 2.3 Minecraft side

| # | Sev | Finding | Evidence | Fix |
|---|---|---|---|---|
| J1 | **Bug** (M) | `HostDriver.tick` applies the host camera at 20 Hz, and clicks are replayed from the same tick. Block placement aims where the mouse was up to 50 ms ago. For video it is "close enough", but fast flicks will place blocks a cell off. | `HostDriver.java:123-126`, `:135-138` | Apply yaw/pitch per frame in `frame()` (and to `Camera` via a mixin, as `BLOCK_RENDER_RESEARCH.md` §2.2 specifies) before processing clicks. |
| J2 | **Bug** (L-M) | `teleport()` sets the client position and queues a server teleport. The server's `ClientboundPlayerPositionPacket` then snaps the client a tick or two later, after the client has already moved on with its velocity. Possible small stutter after each portal. The live tests say it feels fine; keep an eye on it in recordings. | `HostDriver.java:233-252` | If it shows, apply the server teleport first and let the client follow from the position packet. |
| J3 | **Bug** (M) | `HostCollision` is a process-wide singleton: one map, no player context. Fine in single-player, wrong as soon as two players on the same server are in different Portal maps (M6). | `HostCollision.java:43-51` | Key maps by player (section 7, item 6). |
| J4 | **Bug** (M) | All Portal maps share Source coordinates, and `Units` maps them to the same Minecraft coordinates. A map change (`LevelInit` -> `HostState.map`) swaps the collision but leaves the previous chamber's blocks in place. | `Units.java:17-23`, `HostDriver.java:213-231` | Per-map offset or dimension (M5). |
| J5 | **Bug** (M) | Displacements (`LUMP_DISPINFO`) are not read. Portal 1 uses few, but the escape maps have some rubble/terrain displacements; those surfaces will be air to Steve. (M on which maps.) | `BspMap.java:41-48` | Add displacement triangles as sloped brushes, or as `kColTris`-style triangles in a later collision layer. Check `escape_01/02` first. |
| J6 | **OK** | `WorldExporter` is on the render thread; `markDirty` is synchronized; section reads are the same unlocked reads vanilla's chunk builder does. `MESH_NANOS_PER_FRAME` = 3 ms keeps it from stalling frames. | `WorldExporter.java:50`, `:88-97`, `:207-222` | None. |
| J7 | **Bug** (M) | The block atlas is copied from each sprite's **first** animation frame; water, lava, fire, portal blocks stand still in Portal. The host-side comment claims "animated sprites" are handled; they are not. | `WorldAtlas.java:14-16` | Re-upload the atlas on a timer with the current frame (SkyCraft's `SkyAtlas` does this), or skip until M2. |
| J8 | **Bug** (M) | Block light (`lightCoords`) is dropped in `MeshBuilder.put`; only tint x AO x face shade survive. Blocks are full-bright and will not match Portal's lit chambers, and won't react to Portal's auto-exposure (HDR tonemap scale). | `WorldExporter.java:363-374`, `worldrender.cpp:153-158` | M2 lighting. |
| J9 | **Debt** | `giveGun` runs on every resync and `PauseScreen` is closed every tick while linked, so Minecraft's own menu and options are unreachable in host mode. | `HostDriver.java:90-92`, `:112` | Intentional for now; M1 gives Esc semantics. |
| J10 | **Debt** | Unused import `java.nio.file.Files` in `HostDriver`; `sdk::clientSetViewAngles` unused; `vcallVector` only used once. | `HostDriver.java:3`, `sdk.h:103` | Tidy. |
| J11 | **OK** | BSP/VPK/PHY parsers bounds-check offsets and throw `IOException` rather than reading out of range (`Phy.check`, `StaticProps.props` size checks, `Vpk.read`). A corrupt map can only fail a load, which `loadMap` catches and records in `failedMap`. | `Phy.java:107-111`, `StaticProps.java:107-109`, `HostDriver.java:226-230` | None. |
| J12 | **Perf** | `CACHE` grows without bound as the player explores and raycasts (128-block clips touch many cells). Fine for a chamber, worth a soft cap for long sessions. | `HostCollision.java:52` | Evict cells far from the player on map change / every N minutes. |

### 2.4 Memory and perf in a 32-bit process

Mapped views inside `hl2.exe`: overlay 44,240,896 B (42.2 MB) + world 26,218,496 B (25.0 MB) = **67 MB of
address space**, plus a dynamic overlay texture (up to 14.7 MB at 1440p), the atlas texture (up to
16 MB) and the D3D runtime's UP scratch buffer (~4.7 MB). About 100 MB on top of Portal's own
footprint (H on the sizes, from `portalcraft_protocol.h:118-123, 147-154`). That is fine if `hl2.exe`
is `/LARGEADDRESSAWARE` (4 GB VA on 64-bit Windows) and tight but survivable if not (2 GB). **Check
the flag** (`dumpbin /headers hl2.exe | findstr "large"`) before raising the overlay to 4K; at
3840x2160 three slots would be 100 MB. Prefer recording at 1080p/1440p and keep `kOverlayMaxW/H`.

Per-frame CPU on the host: one 1280x720 RGBA->BGRA SSSE3 swizzle into a locked texture (fast), two
state-block captures, and the two UP draws (P6). Nothing else is per frame.

---

## 3. Hardcoded assumptions that will break elsewhere

| Assumption | Where | Breaks on | Fix |
|---|---|---|---|
| Portal maps at `D:/SteamLibrary/steamapps/common/Portal/portal/maps` unless `PORTALCRAFT_MAPS`/`-Dportalcraft.mapsDir` | `HostDriver.java:39-40` | the laptop, any friend | Send Portal's game directory in `HostState` (new field); no config at all |
| Same path in `install.cmd` default and all four map-reading tests | `install.cmd:5`, `BspMapTest.java:19`, `FloorAccuracyTest.java:38`, `HostAimTest.java:23`, `StaticPropsTest.java:20` | laptop (tests skip, silently) | Tests: use `find-portal.ps1`'s result via a system property, or a committed fixture map (section 4) |
| VS 2022 Build Tools at `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools` | `build.cmd:4`, `setup.ps1:41` | any other VS edition/path | `vswhere.exe` |
| Window `-w 1600 -h 900` windowed | `play-portal.cmd:11` | 1366x768 laptop screen; 4K monitors (overlay cap 2560x1440 silently disables the HUD, `FrameExporter.java:69`) | Pick from the desktop size; log loudly when the overlay is skipped |
| Portal hull 32 units -> Steve 0.8 blocks wide | `AvatarDimensionsMixin.java:19` | Portal with RTX (same engine, same hull: fine), other hosts | Send hull size in `HostState` |
| Portal oval 64x108 units, hole depth 72 | `HostCollision.java:25-29` | nothing in Portal 1 | Fine |
| 1 block = 40 units, yaw = -src - 90 | `Units.java:10, 34-36` | nothing | Fine (verified live) |
| Interface names `VEngineServer021`, `VEngineClient013`, `VEngineRenderView014`, `VModelInfoServer004/003`, `PlayerInfoManager002`, `GameMovement001` | `plugin.cpp:728-734`, `worldrender.cpp:254-255` | a Portal update that bumps versions (Valve has been updating Portal in 2024-25) | Fingerprint gate (P3); log every requested interface that comes back null |
| vtable slots listed in section 1 and `sdk.h:48-104`, `plugin.cpp:494`, `overlay.cpp:258`, `worldrender.cpp:260` | | Portal update, Portal RTX (older engine fork: `VEngineClient014` exists in both, slot order may differ) | Same |
| `CMoveData` offsets 36/44/48/52/64/152, `edict_t` size 20, SendProp size 80 | `sdk.h:37-39, 70-73, 77-82` | Portal RTX (M), Portal update (L) | The existing runtime check covers origin; add velocity (compare with `IPlayerInfo` velocity) |
| `SDL` scancodes for Minecraft input, modifier bit values | `plugin.cpp:209-217`, `HostDriver.java:322-330` | a Minecraft version that changes input backends | Fine for 26.3 |
| Minecraft 26.3, Fabric Loader 0.19.5, Loom `1.18-SNAPSHOT` | `gradle.properties` | Loom snapshot drift breaks the build without any code change | Pin a released Loom |
| FOV: nothing is assumed yet because Minecraft doesn't render the level. The moment anything on the MC side must match the host camera (cursor hit-testing is fine; the research doc's occluder path is not), `fov_desired` 75..90 and hor+ scaling matter | `docs/BLOCK_RENDER_RESEARCH.md:134-153` | | Send the server's `GetFOV()` in `HostState` when needed |
| Spawn coordinates of `testchmb_a_00` in tests | `BspMapTest.java:21`, `HostAimTest.java:24` | a different Portal build's map (unlikely) | Fine |

---

## 4. Code quality

**Good:** one idea per file, short comments that say *why*, protocol in one header with
`static_assert`s mirrored by `WorldFormatTest.layoutMatchesProtocol`, runtime layout self-checks,
bounded parsers, and no byte patterns anywhere in the plugin.

**Dead or stale:**
- `README.md` "Known gaps" (lines 87-90) still says dynamic models aren't solid and blocks aren't drawn: both shipped on 10-01/10-02. Update it with every milestone.
- `kEntityStatic`, `HostState.velocity`, `kHostDriving` unused (T8). `sdk::clientSetViewAngles` unused. `Files` import (J10).
- `host/portal/ref/` is git-ignored but exists on disk with Valve headers; `THIRD-PARTY-NOTICES.md` describes this correctly.
- `host/portal/prebuilt/portalcraft.dll` is committed (147 KB) and refreshed per change; the repo will grow ~150 KB per refresh. Acceptable while private; move to GitHub Releases when packaging (M7).

**Tests:** `WorldFormatTest` is the only portable test. The other four need Portal at `D:/...` and
skip otherwise, so the laptop and CI run almost nothing. `FloorAccuracyTest` is a 741-line analysis
harness more than a test. Missing entirely: `Proto` round-trips against the C layout sizes (228/44/108
are asserted in the header, never in Java), `HostCollision.setPortals` hole carving, `LiveEntities.place`
for each solid type, `Units` round-trips. A tiny **fixture map** compiled by Kaydn in Hammer (his own
output, not Valve's) committed under `src/test/resources` would make the BSP/prop tests portable.

**Dev-only tools that ship in the release build today:** `PCK1` fake keys, `PCT1` tick trace, `PCC1`
console command (`plugin.cpp:181-198`), the once-a-second `trace:` log line (`plugin.cpp:701-710`),
`PCD1` dev go-to (`HostLink.java:55-59`, `HostDriver.java:118-121`), plus `fake_mc.py`, `fake_world.py`,
`capture-window.ps1`, `vtslots.py`. **Gate them**: in the plugin behind a launch option
(`-portalcraft_dev` in `GetCommandLineA()`), in the mod behind `-Dportalcraft.dev=true`; log once
"dev channel enabled" when on. Keep the scripts in `host/portal/tools/`, they are good.

**Conventions:** consistent (tabs, `portalcraft$` mixin prefix, `g_` globals in C++). One oddity:
`PortalCraftClient` and `PortalCraft` register the standalone gun and the host bridge in the same
mod; a `portalcraft.host` flag or two Fabric entrypoints would make "plain mod" vs "host mode" explicit.

---

## 5. Security and permissions

What any local process can do today, unauthenticated, over loopback UDP:

| Packet | To | Effect | Evidence |
|---|---|---|---|
| `PCC1 <text>` | Portal :27515 | runs an arbitrary **server console command** in Portal (`map`, `quit`, `exec`, `con_logfile` to write a file, `sv_cheats 1` + anything) | `plugin.cpp:189-198` |
| `PCK1` | Portal :27515 | injects held keys for N ms, forwarded to Minecraft | `plugin.cpp:184-188` |
| `PCH1` (a spoofed `HostState`) | Minecraft :27516 | drives Minecraft's keyboard and mouse (any scancode, including `T` + letters once text input exists), moves the player, changes the loaded map name | `HostLink.java:67-71`, `HostDriver.java:135-138` |
| `PCM1` (spoofed `McState`) | Portal :27515 | moves Portal's player anywhere | `plugin.cpp:174-180`, `:268-279` |
| `PCD1` | Minecraft :27516 | teleports the Minecraft player | `HostLink.java:55-59` |
| writes to `Local\PortalCraft_*` | both | garbage pixels / garbage mesh (validated, no memory safety issue) | T4 |

Is that acceptable? For a solo dev box, mostly yes: the sockets bind `127.0.0.1` only
(`plugin.cpp:147`, `HostLink.java:36`), so nothing on the LAN can reach them, Portal runs with
`-insecure` (no VAC), and a local attacker already owns the session. It is **not** acceptable for a
build friends install, because (a) it is a persistent unauthenticated command channel into a game
process, (b) browsers cannot send raw UDP but any other app can, and (c) it will show up in a
Discord/Steam screenshot as "runs console commands from anyone".

Constrain it cheaply:
1. **Gate the dev packets** (`PCC1`, `PCK1`, `PCT1`, `PCD1`) behind the dev flag (section 4). In release builds, don't even parse them.
2. **Pair the two processes.** The shared-memory mappings already have the right scope (same user, same session). Put a 16-byte random session token in `OverlayHeader` (the host writes it at creation) and require it in every `HostState`/`McState`; drop packets without it. A process that can open the mapping could still read the token, but the mapping's default DACL limits that to the same user and integrity level, which is the same boundary as everything else on the box.
3. Alternatively move state exchange into the mappings entirely (SkyCraft's model, `SkyCraft/skse/src/Link.cpp`) and keep UDP only for dev. SkyCraft also sets an explicit SDDL on the mapping so an elevated Minecraft can still open it (`Link.cpp:33-81`); PortalCraft uses the default descriptor. If Portal is ever run elevated and Minecraft isn't, Minecraft's `OpenFileMappingW` will fail silently (`OverlayLink.java:71-74`): log the `GetLastError` value there.

Portal itself: the plugin runs inside the game process with the user's rights; it opens one log file
next to itself (`plugin.cpp:718-723`) and nothing else. `-insecure` is required for plugins and keeps
VAC out of the picture (documented at `docs/PORTAL_RTX_RESEARCH.md:53`).

---

## 6. Legal and redistribution

| Item | Status | Must never be committed or shipped |
|---|---|---|
| Valve engine headers | Hand-written mirrors only (`sdk.h`); real SDK headers live in git-ignored `host/portal/ref/` (`.gitignore`, `THIRD-PARTY-NOTICES.md:36-40`). H | Don't commit `ref/` even though the SDK 2013 licence would allow non-commercial use; keeping it out keeps the question simple |
| Portal maps, models, textures (`.bsp`, `.vpk`, `.phy`, `.mdl`) | Read from the user's own install at runtime (`GameFiles.java:32-49`) H | Never commit a `.bsp` (tests reference by path only); never ship maps with the installer; never embed Portal textures in Minecraft resources |
| Portal with RTX assets (NVIDIA remaster) | Not touched yet | Same rule; also don't redistribute the Remix runtime/bridge binaries from Portal RTX. dxvk-remix (zlib) and bridge-remix (MIT) from GitHub are fine to ship *if* needed (`docs/PORTAL_RTX_RESEARCH.md:91`) |
| Minecraft | Loom pulls the client from Mojang into the Gradle cache; `runClient` runs offline as "Steve" (`build.gradle:9`) | Never bundle the Minecraft jar, assets or a cracked launcher. The current dev-client flow is fine for development but is not how friends should play: ship a Prism instance and let each friend sign in with their own account (SkyCraft's approach, `SkyCraft/README.md` "Installing") |
| Block atlas copied into shared memory at runtime (`WorldAtlas`) | Runtime only, never written to disk | Keep it that way; under RTX the Remix API wants a file path for textures (`docs/PORTAL_RTX_RESEARCH.md:92`): write it to `%TEMP%` at runtime and delete it, don't ship it |
| SkyCraft (MIT, chasmlol 2026) | Attribution present with the full MIT text and a list of borrowed patterns (`THIRD-PARTY-NOTICES.md:3-28`); adapted files carry one-line credits | Keep the notice in any installer/zip; add it to the mod's `fabric.mod.json` `contact`/license metadata too |
| Fabric Loader/API, Mixin, MixinExtras | Apache 2.0 / MIT | Fine to redistribute in an instance |
| Temurin JDK | GPLv2+CE, redistributable; currently downloaded by `setup.ps1:14-21` rather than bundled | Fine either way |
| Trademarks | "Portal", "Source", "Minecraft" are used descriptively with the disclaimer in `THIRD-PARTY-NOTICES.md:42-43` | Keep the disclaimer on the README, the installer and the channel description |
| Videos | Valve's video policy and Mojang's content guidelines both allow monetised gameplay videos of modded play (M: policy pages, not re-read for this audit) | |
| **The repo has no `LICENSE`** while `fabric.mod.json:11` declares `"license": "MIT"` | H | Add `LICENSE` (MIT, Kaydn 2026) now; it is required for the SkyCraft-derived code's terms to be coherent and for friends to legally receive the build |

---

## 7. Change these before building more on them

1. **Input model: subclass Portal's window procedure instead of polling `GetAsyncKeyState`.**
   `SetWindowLongPtrA(hwnd, GWLP_WNDPROC, ...)` on the `hl2` window (found by `GetForegroundWindow`/process id, which `gameHasFocus` already does, `plugin.cpp:219-230`). It yields `WM_KEYDOWN/UP` with repeat, `WM_CHAR` for text, `WM_MOUSEWHEEL`, `WM_MOUSEMOVE` and lets the plugin *swallow* a key from Portal (return 0) when a Minecraft screen is open. Everything in M1 depends on this; the current polling can't deliver wheel or text at all (P8, P9).
2. **Per-view camera capture instead of "first SceneEnd + WorldToScreenMatrix".** Hook `IVRenderView::ViewSetup3D` (interface slot, same technique as `SceneEnd`) to record the current `CViewSetup`, build the projection from it, and draw in every `SceneEnd` with the view's own matrix. This fixes P4 and is the only way to get blocks visible through portals in plain Portal. Unknown: whether the engine leaves the portal clip plane enabled at `SceneEnd` (if yes, our FFP draw is clipped correctly for free; if not, add it with `SetClipPlane`). M.
3. **Device capture from `Present`, not a memory scan** (P5). Delete `findGameDevice`.
4. **Protocol v2 in one bump**, with: `gameDir[260]`, `fov`, `viewOffsetZ` (ducked), `roll`, `health`, `presentHz`, `cursorX/Y`, `wheelDelta`, `text[16]` (UTF-16 chars this frame), `hullWidth`, `mapIndex`; and in `McState`: `screenOpen`, `wantsCursor`, `hudScale`. Plus the session token (section 5). Keep the Java mirror and the `static_assert`s in step; add a `ProtoTest` asserting the Java sizes.
5. **Vertex buffers for the world mesh** (P6), and while there, a second mesh channel with a **texture table** (texture id per draw range) so entities (M3) can use it. The atlas becomes texture 0.
6. **`HostCollision` keyed by player and map.** `BlockCollisionsMixin` receives the `CollisionContext`, which carries the entity; look up that entity's map. Store maps in a `Map<String, Loaded>` (a map is ~1 MB of brushes; loading all of Portal's 19 is fine). This is a prerequisite for M6 and makes M5 (per-map offsets) natural: the offset lives with the loaded map.
7. **Gate dev tools and add the fingerprint gate** (section 4, P3). Small, and it stops the "mystery crash after a Steam update" class before friends hit it.

---

## 8. Roadmap

Effort is in focused days for one developer plus Claude sessions. Risk: L/M/H.

### M0: Stabilise and verify the current build (2-4 days, risk L-M)

Done when: a fresh `play-portal.cmd` on this PC shows placed blocks world-locked in `testchmb_a_00`, hidden by Portal geometry, placed in front of walls; `mat_queue_mode` is enforced; `Unload` unhooks; dev packets are gated; `LICENSE` exists; README "Known gaps" is current.

First-launch checklist for block rendering and placement (what to look for, in order):

| Step | Expect in `run/logs/latest.log` | Expect in `portal/addons/portalcraft.log` | If not |
|---|---|---|---|
| Start both, click into Portal | `host linked (testchmb_a_00)`, `loaded ... (N solid brushes)`, `overlay mapping open (42 MB)`, `matching the host's WxH` | `Minecraft linked`, `ICollideable check ... layout OK`, `CMoveData check ... layout OK`, `overlay: drawing Minecraft WxH onto WxH` | Already verified on 10-01 (log lines 102-107 / 13-19) |
| World mapping | `world mapping open (32 MB)` | `world: mapping Local\PortalCraft_World_v1 ready (32 MB), SceneEnd hooked` | `can't map 33558528 bytes of the world mapping` or `the Portal plugin speaks PCW1` means an old plugin DLL; rerun setup with Portal closed (`setup.ps1:50-53` refuses to overwrite a running Portal's DLL) |
| Atlas | `block atlas is WxH (built in N ms)`, `sent the WxH block atlas to the host` | `world: block atlas WxH uploaded` | `block atlas WxH is bigger than the mapping's 2048x2048` -> a resource pack or mod inflated the atlas; vanilla fits |
| Dropped items (PCW2) | `item atlas is 1024x512`, `sent the 1024x512 item atlas to the host`; after throwing an item, `entity mesh #1: 1 items, ...` | `world: item atlas 1024x512 uploaded`, `world: drawing entities: ...` | `entity export failed (logged once)` with a stack; `item atlas WxH is bigger than the mapping's 1048576 pixels` -> mods grew it past one doubling (block items still draw) |
| Reset | `world export reset: N non-empty sections to mesh` (N is small: the void world has only what you placed) | | `world export failed (logged once)` with a stack: the merged mesher hit a 26.3 API edge (fluid renderer or `tesselateBlock` signature); the exception tells which |
| Place a block (right click, not holding the gun) | `world mesh #1: S solid + T translucent vertices from K sections (slot 0, ms)` | `world: drawing S solid + T translucent vertices` | plugin says `not drawing: Minecraft isn't sending frames` -> overlay stale gate (`worldrender.cpp:98-100`); `no WorldToScreenMatrix` -> slot 36 wrong (P3); `bad slot` -> header corruption |
| Walk around it | block stays put against walls and floor; walking behind a wall hides it | | if it slides with the mouse, the matrix is from the wrong view (P4) |
| Place against a wall | block sits in front of the wall, sunk at most half a block (`HostAim.placeInNeighbour`, `HostAimTest.aimFromTheSpawn` documents the expected cells) | | |
| Place on the spawn floor | block top at y=5 while the floor is at 4.05 (2 units sunk) | | expected, not a bug |
| Open both portals, look at a block through one | **it will not be visible through the portal** | `world: 3 SceneEnd calls this frame` | expected until M2; note whether the block in the *main* view still draws correctly when portals are open (P4) |
| Break a block (left click) | mesh republishes with fewer vertices | | no crack animation and no selection outline are expected gaps (M2) |
| Switch hotbar with 1-9, hold the portal gun, click | Portal fires; Minecraft does nothing | | mouse wheel does nothing: expected (M1) |

Also in M0: P1, P2, P3 (SEH on the matrix), P5, dev gating, `HostLink` receive loop (T2), tests' map
path from a property, pin Loom, delete dead fields (T8/J10), `README` refresh.

### M1: Input completeness and Minecraft screens (3-5 days, risk M)

Done when: you can open the inventory, drag items with the mouse, type `/give @s tnt 64` in chat, scroll
the hotbar, press Esc to close a Minecraft screen (and Esc reaches Portal's menu when none is open), all
on camera, with a visible cursor.

- WndProc subclass (section 7.1). Keys, repeats, `WM_CHAR`, wheel, raw mouse deltas.
- `McState.screenOpen`: while set, the plugin swallows keys/clicks from Portal, releases Portal's mouse look (`m_rawinput`/`cl_mouseenable 0` via `IVEngineClient::ClientCmd`, or simply stop forwarding deltas by swallowing `WM_INPUT`/`WM_MOUSEMOVE`), accumulates an absolute cursor from mouse deltas clamped to the back buffer, sends `cursorX/Y`.
- Minecraft: `mouseHandler.onMove`, `onScroll`, `charTyped` from the new fields; draw a cursor sprite in a GUI layer (Minecraft relies on the OS cursor, which Portal hides); keep `InputConstantsMixin` as is.
- Esc policy: screen open -> Minecraft; no screen -> Portal. Pause menu stays suppressed (J9) unless a `-portalcraft_dev` flag.
- Minecraft screens that are not `AbstractContainerScreen`/`ChatScreen`/`PauseScreen` are currently filtered out of the overlay (`FrameExporter.java:52-58`); widen to any screen once the cursor exists.

### M2: Rendering fidelity in Portal (5-8 days, risk M-H)

Done when: blocks are visible through portals, lit like the chamber, the viewmodel is sane, and breaking blocks shows cracks and the outline.

- Per-view draw (section 7.2). Verify the clip plane and stencil behaviour with both portals open. If Portal renders portal views with stencil, keep `D3DRS_STENCILENABLE` as the engine left it instead of forcing it off (`worldrender.cpp:174`).
- Lighting: two options, pick after a one-day spike. (a) **Host-side**: `IVEngineClient::ComputeLighting(pos, normal, clamp, out)` per *block* (cached by block position; a few thousand calls per mesh change, not per vertex), multiplied into the vertex colour when the vertex buffer is built (P6 makes this natural). Matches lightmaps and ambient exactly. Slot needs verification; guard it. (b) **MC-side**: parse `LUMP_LEAF_AMBIENT_LIGHTING_HDR` (lump 56) and sample the ambient cube per block in `WorldExporter` (MC already has the BSP). Offset-free, less exact. Either way also multiply by the current tonemap scale under HDR (`mat_hdr_tonemapscale` through `ICvar`), or the blocks will pulse against Portal's auto-exposure. Emissive blocks (glowstone, lava, torches): keep them full-bright and tag them in the colour's alpha for a later additive pass.
- Animated atlas frames (J7): republish the atlas at ~10 Hz with the current frame of each animated sprite.
- Viewmodel policy (P10): holding the MC gun -> hide MC's hand (`HideHandMixin`/`renderHand` cancel) and `r_drawviewmodel 1`; otherwise `r_drawviewmodel 0`.
- Block outline and crack overlay: export as a tiny extra mesh (texture table from 7.5: outline untextured, cracks from the `destroy_stage_N` sprites).
- Cap MC's frame rate to the host's present rate (T9).

### M3: Minecraft entities, items, mobs and particles drawn in Portal (6-10 days, risk M)

Done when: a creeper walks into the chamber, a dropped item spins on a button, TNT flashes and explodes, and a friend's Steve stands next to you (M6 uses this).

- Generic capture: each frame, run the entity render dispatch (and the particle engine) with a capturing `MultiBufferSource` whose `VertexConsumer`s record position/colour/UV per `RenderType` texture. SkyCraft exports items, arrows and the avatar explicitly (`SkyCraft/.../render/AvatarExporter.java`); a generic consumer covers all mobs at once.
- Texture table channel (7.5): send each distinct entity texture once (sprite sheets are small), reference by id. Mob textures are Mojang assets and stay in shared memory only (section 6).
- Camera: entity meshes are camera-independent except billboards (particles, name tags); compute billboards in the plugin from the view it draws in, or accept the 20 Hz MC camera for them.
- Lighting: same per-block sampler as M2, sampled at the entity's feet.
- Shadows: skip (Portal has its own shadow system; a blob under each entity drawn with a decal texture is a cheap later win).

### M4: Placed blocks as Portal physics; hazards and health (6-10 days, risk H)

Done when: a companion cube rests on your bridge, a turret can't see you behind your wall, goo/turrets/pellets hurt Steve, and dying in Portal and dying in Minecraft agree.

- Static vphysics per 16x16x16 section: `VPhysics031` -> `IPhysicsCollision::ConvertConvexToCollide` from one convex box per solid block (merged into slabs where possible) -> `IPhysicsEnvironment::CreatePolyObjectStatic`. Interface-only, no patterns. Cubes, turrets, pellets and ragdolls collide with it. Rebuild a section's object when its mesh changes. Risk: interface slots again (fingerprint-gated), and vphysics objects are not seen by engine *traces*, so turret line of sight, portal placement on blocks and bullets still pass through. For traces you need an entity with a collideable; a `func_brush`-style entity per section with a custom `CPhysCollide` is the known route (Garry's Mod does this) but needs `CreateEntityByName` + `VPhysicsInitStatic` through datamaps. Do the vphysics tier first and measure how much the trace tier matters on camera.
- Health bridge: read the player's `m_iHealth` by SendTable name (same technique as `prop_portal`), send it; Minecraft applies deltas as damage; Minecraft damage (mobs, TNT) is sent back and applied with `SetHealth` through the entity's datamap input or `ent_setname` + `hurtme` (cheat). Death: whichever dies first triggers the other's respawn/reload. Portal's death reloads the last save; `g_needSync`/`teleportSeq` already resync position (`plugin.cpp:344-353`).
- Hazards without health: fizzlers already fizzle portals natively; `trigger_hurt` (goo) only via health.

### M5: Level flow and saves (3-5 days, risk M)

Done when: blocks from chamber 00 never appear in chamber 01, loading a Portal quicksave puts Steve in the right place, and each chamber's builds persist in the Minecraft world.

- Per-map offset: `mapIndex` in `HostState` (the plugin assigns indices from a fixed table of Portal's maps, falling back to a hash) and a 4096-block XZ offset per index applied in `Units` and `WorldExporter`. Simpler and save-compatible compared with one dimension per map; revisit dimensions if offsets ever collide with the void world's border.
- Map change: on `HostState.map` change, Minecraft swaps the collision (already), teleports to the new spawn (already via `g_needSync`), and the exporter resets (`WorldExporter.reset` already keys on level/generation; add the map).
- Portal saves: on load, the player jump is handled by the `>0.5 unit` rule; entity states resync from the next `HostEntities`. Verify cubes you had stacked on blocks (M4) fall correctly after a load.
- Minecraft saves: the void world autosaves; nothing to do. Consider `/gamerule keepInventory true` and no fall damage (already in `level.dat`) as the let's-play defaults.

### M6: Multiplayer with friends (5-8 days, risk M-H)

What syncs today: nothing Portal-related. The standalone portal gun (`PortalEntity`) is fully server-synced and works in ordinary Minecraft multiplayer; host mode is single-player only (`HostCollision` singleton, J3; `giveGun`/`teleport` assume the integrated server, `HostDriver.java:187, 238`).

Model (SkyCraft's, the only practical one because Portal 1 and Portal with RTX have no co-op): **the Minecraft world is shared; each friend runs their own Portal.** Blocks, items, mobs, chat and each other's Steves are shared through Minecraft. Puzzle state (portals, cubes, doors, lifts) is per player, like each Skyrim is per player in SkyCraft (`SkyCraft/README.md` "Playing with friends"). Two players standing in "the same" chamber see each other's Steves (M3 avatars) but each sees their own cubes.

Done when: two PCs each running Portal + PortalCraft join one Minecraft world (host "Open to LAN" + an e4mc-style tunnel, as SkyCraft bundles), both see each other's Steve in Portal, both build in the same chamber, and blocks placed by one appear in the other's Portal within a second.

- Server: `HostCollision` per player (7.6); the server needs each player's map, so it must run on a PC with Portal installed, which the host's integrated server satisfies. A dedicated server would need the BSPs copied, which stays within "you own Portal" if it runs on the host's machine.
- Client: other players are ordinary remote players; M3's capture draws them.
- Different maps at once: per-map offsets (M5) keep them apart in Minecraft space; the host's collision table loads whatever maps players report.
- Optional later: sync *portal pairs* only (each player's two portals placed in the other players' Portals through `prop_portal`'s `NewLocation` input with a distinct linkage group). Everything else (cubes, doors) stays local; keep expectations there.

### M7: Packaging for friends (3-5 days, risk M)

Done when: a friend with Steam Portal and a Microsoft account runs one installer, signs in once, and `PortalCraft.cmd` starts both games linked. No Gradle, no VS, no JDK download, no env vars.

- Release jar from `gradle build`; verify the mixins work in a production Fabric instance (Loom remaps; the `renderFrame`/`runTick`/`setSectionDirty` targets are vanilla names, fine).
- Portable Prism Launcher instance (`%LOCALAPPDATA%\PortalCraft`) with Fabric 26.3 + Fabric API + the jar + a JVM arg for hidden start; this is exactly SkyCraft's layout (`SkyCraft/tools/minecraft-bundle/Prism/instances/SkyCraft/mmc-pack.json`).
- Plugin install from the installer (the `setup.ps1` logic, minus the build step); `gameDir` in the protocol removes `PORTALCRAFT_MAPS`.
- Launch: `hl2.exe -game portal -insecure -novid +cl_updaterate 66 ... +mat_queue_mode 0`, window size from the desktop; the plugin forces what it needs anyway (P1).
- Fingerprint gate so a Steam update produces "PortalCraft: unsupported Portal build 1745011, link only" instead of a crash.
- Test on the laptop: the first run without `D:\SteamLibrary` is the real test of section 3.

### M8: Video and content readiness (2-3 days, risk L)

Done when: a 10-minute test recording of chamber 04-06 with a friend looks like a finished video.

- Record only the Portal window (the Minecraft window is hidden behind it); OBS game capture on `hl2.exe` already includes the overlay since it is drawn before `Present`.
- 1080p60 or 1440p60; HUD scale 2 or 3 at 1080p (`options.txt` `guiScale`, or send `hudScale` from the plugin).
- Quiet logs: no `trace:` lines, no `entity #N moved` spam (`LiveEntities.java:87-90` logs 40 of them), no `Can't keep up` from the integrated server (seen at `run/logs/latest.log:191`; likely the 2.3 s hitch from a cache clear, T5).
- Set pieces that make clips: building a staircase to skip a puzzle; a cube landing on a block bridge (M4); water poured into goo; a creeper in the turret room; TNT on the exit door; friends' Steves falling through portals; infinite-fall loops with blocks.
- Death and failure states must be funny, not broken: health bridge (M4), respawn (M5).

### M9: Portal with RTX (8-15 days, risk H)

Done when: the same session runs in Portal with RTX, blocks are path-traced and seen through ray portals, and the HUD overlay is on top.

- Preconditions from `docs/PORTAL_RTX_RESEARCH.md` §3 and the checklist at `:180-190`: bitness, `addons/` loading (unknown; ASI loader fallback), interface versions in the RTX `engine.dll`, whether `bin\d3d9.dll` exports `remixapi_*`.
- Hooks: fingerprint profile for the RTX binaries; the interface-based hooks (`ProcessMovement`, `SceneEnd`, `ViewSetup3D`, `ICollideable`) should carry over; expect slot differences and re-derive with `vtslots.py`.
- Rendering through the bridge: our fixed-function draws go through the Remix bridge `d3d9.dll` and are captured as geometry (this is the path Remix is built for). Requirements: vertex buffers not UP (P6), stable texture hash for the atlas so it can be classified in `rtx.conf` (world-space, not UI), emissive blocks as a separate draw with a texture tagged emissive. Lighting then comes for free, and so do blocks through portals.
- Overlay cost: 8 MB/frame across the 32->64-bit bridge for a 1080p HUD. Options: only ship the overlay when a screen or HUD change happened (dirty flag), or draw the HUD as a 3D quad with the hand as 3D geometry (M3's capture can render the hand too). Measure first.
- Remix API (optional M10): `CreateLight` for torches/glowstone, `CreateMaterial` with PBR for the atlas. Only if the shipped bridge exports it; otherwise swap in the GitHub bridge+runtime pair (zlib/MIT).

### Dev tooling (continuous)

- Keep `fake_mc.py`/`fake_world.py`; add `fake_entities.py` for M3 and a `--replay` of a recorded `HostState` stream for regression without Portal running.
- A `portalcraft_status` console command (plugin) printing hook state, link state, mesh counts and fingerprint.
- Capture-window script already exists; add a one-key "screenshot both logs + window" for bug reports.
- Fixture map for portable tests (section 4).

---

## 9. Open questions (verify before relying on them)

| # | Question | Where it matters | How to check |
|---|---|---|---|
| Q1 | Is `hl2.exe` `/LARGEADDRESSAWARE`? | 2.4, overlay size | `dumpbin /headers` |
| Q2 | With two portals open, which `SceneEnd` is the main view, and is the portal clip plane still enabled at that point? | P4, M2 | Log the render target/viewport per `SceneEnd`; try a block straddling the portal plane |
| Q3 | Does Portal render portal views with stencil or render-to-texture on this build? | M2 depth inside the portal oval | `r_portal_use_stencils` value at runtime |
| Q4 | Does Portal's viewmodel currently draw under Minecraft's hand? | P10 | One screenshot while holding the MC gun |
| Q5 | Which `ComputeLighting` slot does VEngineClient013 have on build 19017868? | M2 lighting | `vtslots.py` on `cdll_int.h` from the SDK branch Portal matches, then a guarded call at the spawn (expect a sane RGB) |
| Q6 | Does `charTyped` already work by accident through SDL? | P9 | Open chat, type |
| Q7 | Does `escape_01/02` have displacements? | J5 | Count `LUMP_DISPINFO` entries |
| Q8 | Portal RTX: addons loading, interface versions, bridge exports | M9 | Research doc checklist |

---

## 10. Top five next steps

1. **M0 first-launch verification** with the checklist above, plus the P1 (`mat_queue_mode`) and P2 (`Unload`) fixes, because both are crash classes that would hit mid-recording.
2. **Protocol v2 and the input rewrite** (7.1, 7.4): they unblock inventories, chat and commands, which every video needs.
3. **Per-view camera capture + lighting** (M2): the "blocks through a portal, lit like the room" shot is the thumbnail.
4. **Entity capture** (M3): creepers, items, TNT and friends on screen.
5. **Packaging** (M7) as soon as M1-M2 are in, so friends can start playing while M4-M6 land; add `LICENSE` and gate the dev channel in the same pass.
