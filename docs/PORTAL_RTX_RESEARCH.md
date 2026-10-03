# PortalCraft: Portal / Portal with RTX host-side research

Research date: 2026-10-01. This was read-only: nothing was installed or changed on M6 apart from this file.

Confidence tags: **[confirmed]** means verified directly (local binaries, `gh api`, or the source code). **[high]**, **[medium]** and **[low]** mean inferred from secondary sources. **[unknown]** means I couldn't establish it.

Reference model: chasmlol/SkyCraft (MIT; local clone in the session scratchpad). Its design doc describes colour+depth layer compositing. Its protocol (`skycraft_protocol.h` v11) has since moved on: Minecraft now **ships block meshes, an atlas, lights and avatar meshes** (`kRenSection`, `kRenAtlas`, `kRenLights`, `kRenAvatar`, `kRenScene`) and the host **draws them natively**. The hand and GUI still come over as a CPU pixel triple buffer, and collision goes the other way as exact triangles (`kColTris`) plus blocks. That native-mesh model fits Portal much better than depth compositing (see §4.5).

---

## 0. Local install state (M6, read-only check)

| Item | State |
|---|---|
| `D:\SteamLibrary\steamapps\common\Portal` | **Fully installed** while I was working (appmanifest_400: StateFlags 4, buildid **19017868**, 2.59 GB). `portal\steam.inf`: PatchVersion=1745010, ServerVersion=9862575 |
| Portal `hl2.exe` | **PE32 x86** (127 KB) **[confirmed]** |
| Portal `bin\*.dll` (engine, tier0, shaderapidx9, `shaderapivk.dll`, `dxvk_d3d9.dll`, `serverplugin_empty.dll`) | All **x86**. No `win64`/`x64` folder. Valve's current Portal ships an optional DXVK/Vulkan path (`-vulkan`) **[confirmed files; launch flag high]** |
| Portal `portal\bin\` | `client.dll`, `server.dll`, `gamepadui.dll`, all x86 **[confirmed]** |
| Portal `bin\d3d9.dll` / `.trex` | None (expected for plain Portal) |
| `D:\SteamLibrary\steamapps\common\PortalRTX` | **Empty, still downloading**: appmanifest_2012840 has 518 MB of 14.88 GB, target build 18508207, StateFlags 1026. The `downloading\2012840` staging folder has no files yet. **Can't inspect the RTX binaries yet** |
| `C:\Program Files (x86)\Steam\steamapps\common\PortalRTX` | **Leftover from an old install** (Dec 2025): `portal_rtx\cfg\config.cfg`, saves, `nrc_session_log.txt`. No binaries and no appmanifest on C:. Layout is `hl2\`, `platform\`, `portal_rtx\` |
| `C:\...\common\Half-Life 2 RTX` (leftover) | `rtx-remix\logs\bridge32.log` + `bridge64.log` show the same architecture Portal RTX uses: 32-bit `hl2.exe` → bridge client `d3d9.dll` → 64-bit bridge server → `bin\.trex\d3d9.dll` (dxvk-remix). Bridge version `remix-main+6edc6667`, config at `bin\.trex\bridge.conf` **[confirmed for HL2 RTX]** |

Interface strings in the current Portal binaries **[confirmed]**:

- `engine.dll`: `ISERVERPLUGINCALLBACKS001/002/003`, `ISERVERPLUGINHELPERS001`, `plugin_load`, `addons/`, `VEngineServer021`, `VEngineClient013/014`, `EngineTraceServer003`, `EngineTraceClient003`, `VEngineCvar004`, `ServerGameClients003/004`, `ServerGameEnts001`.
- `server.dll`: `ServerGameDLL008/009`, `PlayerInfoManager001/002`, `GameMovement001`, `IEffects001`, `CProp_Portal`/`DT_Prop_Portal`, `m_hLinkedPortal`, `m_matrixThisToLinked`, `m_bActivated`, `m_bIsPortal2`, `m_iLinkageGroupID`, `m_hPortalEnvironment`, `CWeaponPortalgun` with inputs **`FirePortal1`, `FirePortal2`, `FirePortalDirection1`, `FirePortalDirection2`**, `m_bCanFirePortal1/2`, prop_portal inputs **`NewLocation`**, `SetActivatedState`, `Fizzle`, and the commands `portal_place`, `upgrade_portalgun`, `change_portalgun_linkage_id`, `sv_portal_placement_never_fail`, `sv_player_trace_through_portals`.
- **No `VServerTools`** in server.dll, so there's no IServerTools shortcut.

> Note: the current Source SDK 2013 headers define `VEngineServer023` / `ServerGameDLL012` (the TF2-branch engine). Portal exposes **021 / 009**, so a plugin must request the older versions explicitly (`INTERFACEVERSION_VENGINESERVER_VERSION_21` is still in `eiface.h`).

---

## 1. Running code inside Portal 1

### 1.1 Is Portal's game code in source-sdk-2013? **No [confirmed]**

`gh api repos/ValveSoftware/source-sdk-2013/git/trees/master?recursive=1` (default branch `master`, last push 2026-09-05) has game dirs only for `hl2`, `episodic`, `hl2mp`, `tf`, `sdk`, plus shared `Multiplayer`/`econ`/`NextBot`. The 48 paths matching "portal" are areaportal code and the old portal **shaders** (`materialsystem/stdshaders/portal*.cpp/.fxc`). There's no `prop_portal`, `weapon_portalgun` or `portal_player`. What it does have that we can use: `src/public/engine/iserverplugin.h` (`ISERVERPLUGINCALLBACKS003`), `src/utils/serverplugin_sample`, `IEngineTrace.h` (`EngineTraceServer003`/`Client003`), `cdll_int.h` (`VEngineClient014`), and `iplayerinfo.h`.

A community port of the "old Portal 1 source code" to SDK 2013 exists: SonicEraZoR/Portal-Base (https://github.com/SonicEraZoR/Portal-Base, no licence declared). Its provenance isn't an official Valve release. **Use it as a reference for class layouts, not as code to ship.**

### 1.2 Realistic ways in

| Route | Status in current Steam Portal | Notes |
|---|---|---|
| **Server plugin via `addons\*.vdf`** | **Works** **[high]**. Valve broke `plugin_load` in the Feb-2025 HL2/Portal update: the command is now a silent no-op. Loading from `portal\addons\<name>.vdf` still works (tested on Portal build 9786830, Jun 2025) | https://github.com/ValveSoftware/Source-1-Games/issues/6997 (still open). SPT's documented workaround: put `spt-2013.dll` + `spt-2013.vdf` (`"Plugin" { "file" "addons/spt-2013" }`) in `portal\addons` (https://github.com/OutOfBoundsOffice/SourcePauseTool/issues/370). The catch: addons load **very early**, before some modules exist, so defer init to `LevelInit` / first `GameFrame` |
| `plugin_load` console command | **Broken** in current Steam builds **[high]** | evanlin96069/plugin_fix (MIT, https://github.com/evanlin96069/plugin_fix) is an addons plugin that restores it |
| ASI loader / proxy DLL | Works **[high]** | Ultimate ASI Loader dropped into `<game>\bin\winmm.dll` + `*.asi` in `bin` (Source loads `bin\launcher.dll` with an altered search path, so a proxy next to `hl2.exe` is never loaded). This is what the Portal-with-RTX head-tracking mod uses (§3.6) |
| DLL injection | Works (not VAC-protected) | Fine for development; worse UX |
| Rebuild Portal from leaked/ported code | Legally grey | Not recommended |

- **Bitness: current Steam Portal is 32-bit only [confirmed: PE headers]**. Every engine/game DLL is x86, so our plugin must be **Win32 (x86)**. Valve's 2024-25 updates added DXVK/Vulkan and gamepad UI to Portal but no 64-bit build.
- **Single-player:** server plugins run on the listen server in SP. SPT is the proof: it's built and used for single-player Portal. Launching with `-insecure` is the documented safe default (https://wiki.sourceruns.org/Source-Pause-Tool.html).

---

## 2. Precedent tools that hook Portal 1

### 2.1 SourcePauseTool (SPT): **the primary precedent**

- Repo: **OutOfBoundsOffice/SourcePauseTool** (YaLTeR/SourcePauseTool now redirects there). Active: last push 2026-09-17. **MIT** licence (SPT contributors 2014-2025; SPTLib MIT). **[confirmed]**
- Builds: `spt.dll` (2007 / Portal 3420 / 5135), **`spt-2013.dll` (SteamPipe / latest Steam Portal)**, `spt-oe.dll`, `spt-bms.dll`. x86 CMake presets. **[confirmed README]**
- What it hooks or reads (from the source tree) **[confirmed]**:
  - **Portals:** `utils::PortalInfo` (pos, angles, linked handle/pos/angles, isOrange, isActivated, isOpen, linkageId). It reads `CProp_Portal` fields **through datamaps by name** (`m_vecAbsOrigin`, `m_angAbsRotation`, `m_hLinkedPortal`, `m_bIsPortal2`, `m_bActivated`, `m_iLinkageGroupID`) and the player's `m_hPortalEnvironment`, so it doesn't depend on per-build offsets (`spt/utils/ent_list_server.cpp`). `portal_utils` has `transformThroughPortal()`.
  - **Teleport events:** hooks server `TeleportTouchingEntity` (`portalled_pause.cpp`).
  - **Portal placement:** calls the game's own `TraceFirePortal` (`tracing.hpp`, `portal_placement.cpp`).
  - **Traces:** `UTIL_TraceRay` client/server, `CGameMovement::TracePlayerBBox`, `TracePlayerBBoxForGround` (byte patterns per build: 5135, 3420, 1910503, 7122284, 7462488).
  - **Player I/O:** hooks `CInput::CreateMove`, `DecodeUserCmdFromBuffer`, `GetButtonBits`; reads player data, velocity and eye position (`playerio`).
  - **Camera:** hooks `ClientModeShared::OverrideView(CViewSetup*)`, `CInput::MouseMove`, `ShouldDrawLocalPlayer` (`camera.cpp`).
  - **Rendering:** a mesh renderer that hooks `CRendering3dView::DrawOpaqueRenderables` / `DrawTranslucentRenderables` / `CSkyBoxView::DrawInternal` and **draws in every view, portal views included** (it tracks `CurrentPortalRenderDepth`). Also `CViewRender::RenderView` (overlay / second camera).
  - **World collision:** `draw_world_collides.cpp` reads engine BSP collision (`map_brushes`, `map_brushsides`, box brushes) and static-prop `vcollide` via `modelInfo->GetVCollide`.
  - **IPC:** TCP JSON server (`y_spt_ipc`, port 27182) that runs console commands and streams entity props. Someone has already used it to drive Portal from an external agent: cozyblaze/portal-agent (https://github.com/cozyblaze/portal-agent).
- **Portal with RTX: no support found [high]**. Its game detection, pattern sets and issues never mention RTX (GitHub issue search for "rtx" returns 0).

### 2.2 SourceAutoRecord (SAR)

- p2sr/SourceAutoRecord, **MIT**, very active. **Portal 2-engine games only**: Portal 2, Aperture Tag, Mel, Reloaded, INFRA, Stanley Parable and others. **No Portal 1, no Portal with RTX [confirmed README]**. Useful only as a reference for portal-placement and HUD ideas.

### 2.3 Source Speedrun Tools (SST)

- mikesmiffy128/sst (mirror; licence in the download, not SPDX-tagged). Supports L4D1/2 and **Portal 1 builds 3420/4104/5135 plus "some older Steam version"**. No Portal RTX mention (https://mikes.software/sst/). Its `src/sst.c` shows how to load via addons; SPT referenced it for that.

---

## 3. Portal with RTX specifics

| Question | Answer |
|---|---|
| Engine branch | A 32-bit Source engine: the head-tracking mod states "Portal with RTX runs the 32-bit Source engine" and checks for a PE32 `client.dll` **[high]**. Its `VEngineClient014`, 200-byte `CViewSetup` and 84-byte `trace_t` all match the SteamPipe/SDK-2013-era Portal line **[medium]**. NVIDIA's own tutorial makes base Portal Remix-ready by **copying Portal-with-RTX's `bin` over base Portal's `bin`**, which implies a shared engine lineage **[medium]**. It is **not** the 2007-era Portal (3420/5135) **[medium]**. NVIDIA added `c_frustumcull` (BlueAmulet/SourceRTXTweaks). The last game update was around **2025-05-18/19** (DLSS 4 / NRC patch) **[high]** |
| Binaries / bridge | `bin\d3d9.dll` = **Remix bridge client (x86)**. `bin\.trex\NvRemixBridge.exe` = **bridge server (x64)**. `bin\.trex\d3d9.dll` = **dxvk-remix runtime (x64, Vulkan)**, plus `rtx.conf`/`dxvk.conf` in the game root **[high]**. Same layout as the HL2 RTX logs on this PC **[confirmed for HL2 RTX]**. Exact Portal RTX file list: **verify once the download finishes** (checklist below) |
| Swap in a newer open-source runtime? | **Yes, in practice [medium-high]**. rtx-remix issue #1086 (Sep 2026) benchmarks Portal RTX's 2025 build with a **2026 GitHub-Actions dxvk-remix runtime** dropped in. Remix 1.0 ships a `d3d9_runtime.zip` in `.trex` for exactly this kind of swap. The source is NVIDIAGameWorks/dxvk-remix (**zlib**; the bridge is merged into it under `bridge/`; the old bridge-remix repo is MIT). Risk: Portal RTX's runtime is described as a custom build, so options or assets may drift |
| Remix API | **Yes, a public C API**: `public/include/remix/remix_c.h` (API v**0.6.5**): `CreateMaterial`, `CreateMesh`, `DrawInstance`, `CreateLight`/`DrawLightInstance`, `SetupCamera`, `SetConfigVariable`, `Startup`/`Present`, `dxvk_*` interop, and object picking. `remix_c.h` refuses non-x64 builds, **but** `public/include/remixapi/bridge_remix_api.h` exposes it to **32-bit games through the bridge**: `GetModuleHandle("d3d9.dll")` → `remixapi_InitializeLibrary` + `remixapi_RegisterCallbacks(beginScene, endScene, present)`. **Through the bridge only these are forwarded:** CreateMaterial/DestroyMaterial, CreateMesh/DestroyMesh, DrawInstance, CreateLight/DestroyLight/DrawLightInstance, SetConfigVariable, dxvk_CreateD3D9/RegisterD3D9Device. **Not forwarded:** SetupCamera, Startup/Present, CopyRenderingOutput (which includes DEPTH), and picking **[confirmed: `bridge/src/client/remix_api.cpp`]**. Materials take texture **file paths** (`remixapi_Path albedoTexture...`) and can be sprite sheets, so the MC atlas has to be written to disk (DDS/PNG) |
| Does Portal RTX's shipped bridge export `remixapi_InitializeLibrary`? | **[unknown]**: check the `bin\d3d9.dll` exports after install. If it doesn't, swap in a newer bridge+runtime pair |
| Known mods injecting geometry this way | **xoxor4d/p2-rtx** (Portal 2, 32-bit, through the bridge) uses `m_bridge.CreateMesh` / `CreateMaterial` (incl. `MATERIAL_INFO_PORTAL_EXT` ray portals) / `DrawInstance` in `remix_rayportal.cpp` and `model_render.cpp`. **xoxor4d/remix-comp-base** (MIT) is a reusable base with the same `remix_api.cpp`. Xenthio's gmod-rtx-fixes-2 (GPL-2.0) is 64-bit GMod. No known mod injects geometry into **Portal with RTX** specifically **[high]** |
| How portals render under Remix | **Remix "ray portals"**: path-traced teleporting rays on geometry tagged by texture hash (`rtx.rayPortalEnabled`, `rtx.rayPortalModelTextureHashes`, camera-teleport history correction, …) instead of Source's recursive portal views. **One portal pair max** (rtx-remix #663; fine for Portal 1). Anything we inject as geometry is visible through the portals automatically, because the path tracer sees the whole scene |
| Console / `plugin_load` in Portal RTX | Console works (cfg, launch options and console commands are all documented). **`plugin_load` / addons: [unknown]**. Its engine was forked before Valve's Feb-2025 break, so `plugin_load` *probably* still works, but that's unverified. The only public Portal-RTX code mod uses an **ASI loader**, not a server plugin |
| SPT / SAR in Portal RTX | **SAR: no.** **SPT: no evidence**: its byte patterns don't cover the RTX `client.dll` build, so expect most hooks to fail. Its datamap-based features (portal info, ent props) would probably work if it loads at all **[medium]** |

### 3.6 Direct Portal-RTX precedent: itsloopyo/portal-with-rtx-headtracking (MIT)

Repo: https://github.com/itsloopyo/portal-with-rtx-headtracking

- A 32-bit `.asi` loaded by Ultimate ASI Loader as `bin\winmm.dll`.
- Detours `CViewRender::RenderView(CViewSetup*, …)` in `client.dll` and edits origin/angles/FOV. That's proof the Source camera can be driven in RTX and **Remix follows it** without the Remix API.
- Every address is pinned to a **PE fingerprint of `client.dll`** (TimeDateStamp/SizeOfImage/CheckSum), profile `_20250518`. On an unknown build it goes dormant. Its comments document RVAs for `UTIL_TraceLine`, `C_BasePlayer::GetLocalPlayer`, `ScreenTransform`, the `CViewSetup` field offsets (origin +0x40, angles +0x4C, fov +0x38), and `VEngineClient014` vtable slots.
- **We should copy this fingerprint-gated, append-only offset registry pattern.**

---

## 4. Getting what we need from Portal

Units and axes: Source is Z-up, in inches. (Since superseded: PortalCraft now uses **1 block = 32 units** so Portal's 64-unit grid lands on blocks, with Steve scaled 1.25 to stay 72 units tall; see `HostScale`. The original reasoning follows.) **Use 1 block = 40 units.** The HL2/Portal hull is 72 units tall = 1.8 blocks, and its eye is at 64 units vs MC's 1.62 × 40 = 64.8, so height and eye line up almost exactly. Hull width is 32 units (0.8 blocks) vs MC's 0.6 blocks, which is harmless. Step height is 18 units vs MC's 0.6 × 40 = 24. Mapping: `mc.x = src.x/40`, `mc.y = src.z/40`, `mc.z = -src.y/40`, with yaw sign/offset to pin down in Phase 0 (same method as SkyCraft). Velocity: MC blocks/tick × 40 × 20 = units/s. Portal ticks at 66.67 Hz vs MC's 20 TPS, so reuse SkyCraft's `prevX/curX/tickQpc` interpolation.

| Need | Recommended way | Confidence |
|---|---|---|
| **Player position / eye angles (read)** | Server: `IPlayerInfoManager` (`PlayerInfoManager002`) → `IPlayerInfo::GetAbsOrigin/GetAbsAngles`, or datamap `m_vecAbsOrigin` on edict 1. Client: `IVEngineClient::GetViewAngles` (`VEngineClient014`). All stable interfaces | high |
| **Set position / velocity each tick (puppet)** | Best: vtable-hook **`IGameMovement::ProcessMovement(CBasePlayer*, CMoveData*)`** (server exports `GameMovement001`). After the original runs, overwrite `mv->m_vecAbsOrigin` and `mv->m_vecVelocity` with MC's converted state. The engine then runs touch triggers and **Portal's own portal-touch / teleport logic** on the real velocity. Set `cl_predict 0` (or hook the client's `GameMovement001` too) to avoid prediction fights. Angles: `IVEngineClient::SetViewAngles` each frame. MVP fallback: `setpos_exact`/`setang` console commands with `FCVAR_CHEAT` stripped through `ICvar` (laggy, but needs zero RE). The `CMoveData` layout must be checked against Portal's server.dll (SDK-2013 layout expected) | medium |
| **Both portals: pos / normal / linked / open** | SPT's datamap method on `CProp_Portal`: `m_vecAbsOrigin`, `m_angAbsRotation` (normal = forward vector), `m_hLinkedPortal`, `m_bActivated`, `m_bIsPortal2`, `m_iLinkageGroupID`, plus **`m_matrixThisToLinked`** (present in server.dll) for the full transform. Portal hole half-extents are 32 × 54 units (from Portal source; verify) | high |
| **Force-fire primary/secondary** | Most native: inject **`IN_ATTACK` / `IN_ATTACK2`** into the usercmd in a `CInput::CreateMove` hook for one tick. The real weapon fires with its own projectile, delay, animation, sound and placement rules. Aim comes from the eye angles we already mirror from MC. Alternatives: fire the weapon's **`FirePortalDirection1/2`** input (Vector) or `FirePortal1/2`. Without `VServerTools`, do that by walking the entity's **datamap input descriptors** (typedescription `FTYPEDESC_INPUT` → `inputfn`), which needs no offsets, or by un-cheating `ent_fire`. Placement override: prop_portal `NewLocation` input / `portal_place` (cheat). The player needs the gun: `upgrade_portalgun` / `give weapon_portalgun` | high (inputs confirmed present; CreateMove hook needs a pattern) |
| **Teleport → MC** | Let Portal do the teleport. Detect it through SPT's `TeleportTouchingEntity` hook (pattern), or pattern-free: puppet origin jumps / `m_hPortalEnvironment` changes. Then send MC `Teleport{seq, pos, yaw, pitch, velocity}` (SkyCraft's `SkyState.teleportSeq`/`teleportAck` exists but has **no velocity**, so the protocol needs extending). Ignore MC positions until MC acks | medium |
| **Collision for MC** | (a) **Static world: parse the map's BSP brushes** (planes/brushsides → convex hulls → `kColTris`). Read the .bsp through the engine filesystem (`IFileSystem`, VPK-aware) or SPT's in-memory `CCollisionBSPData` approach. Portal maps are almost all brushes, so this is exact and offset-free. Static props: `IVModelInfo::GetVCollide` (SPT does this). (b) **Dynamic** (doors, `func_movelinear` panels, elevators, cubes, turrets): enumerate edicts → `ICollideable` (OBB, origin/angles, solid type, brush submodel `*N`) each tick. (c) Fallback / validation: `IEngineTrace::TraceRay` (`EngineTraceServer003`) on a grid, like SkyCraft stage A. (d) **Portal hole carving:** while a linked pair is open, drop or flag wall triangles inside each portal's rectangle (plus ~1 block behind) so MC can walk or fall *into* the hole. Otherwise MC's wall blocks the player before Portal's teleport fires. This is the hardest new piece | medium |
| **View / projection** | Plain Portal: read `CViewSetup` in a `RenderView`/`OverrideView` hook (origin, angles, horizontal fov, 4:3-relative fov semantics) or `IVEngineClient::WorldToScreenMatrix()`. With the native-mesh approach below you mostly don't need it. RTX: same hook (head-tracking mod offsets), and Remix derives its camera from the game's D3D9 transforms | high |
| **Depth buffer** | **Avoid depth compositing.** Plain Portal is D3D9 (D24S8, not readable without the INTZ trick) and *every portal view is a separate render with its own depth*, so one depth layer can't composite MC blocks seen through portals. **Under Remix the rasterised depth is meaningless:** the final image is path traced in the 64-bit process. Remix's own depth is only reachable through `dxvk_CopyRenderingOutput(DEPTH)`, which the **bridge doesn't forward** | high |

### 4.5 Rendering MC content in Portal: use the native-mesh path (like SkyCraft v11)

- **World blocks and entities:** consume SkyCraft-style `kRenSection`/`kRenAtlas`/`kRenScene`/`kRenAvatar` and draw them **with Source's material system inside SPT-style `DrawOpaqueRenderables`/`DrawTranslucentRenderables` hooks**. They then appear correctly in the main view **and inside every portal view**, depth-tested by the engine. The atlas becomes a procedural `ITexture`.
- **Under RTX**, two options:
  - (i) The same Source draws get captured by Remix and path-traced. This should work if they go through the fixed-function path Remix needs; NVIDIA's own Portal setup uses `-dxlevel 70` **[medium]**.
  - (ii) Upgrade path: **Remix API through the bridge**: `CreateMaterial` (atlas written to `.dds`; sprite-sheet fields for animated tiles; emissive for glowstone), `CreateMesh` per 16³ section, `DrawInstance` each frame in the registered beginScene callback, and `CreateLight` for torches/lava (`kRenLights`). These are path-traced, visible through ray portals, and cast and receive GI.
- **Hand + GUI:** a full-screen textured quad after the HUD. Under Remix, orthographic draws are UI by default (`rtx.orthographicIsUI = True`), so they get rasterised on top. **Cost warning:** pushing a 1080p RGBA overlay through the 32→64-bit bridge every frame is about 8 MB per frame of IPC. Prefer drawing the MC hand/held item as 3D (`kRenAvatar`-style meshes, already in the protocol) and send GUI pixels only when an MC screen is open.
- **Nice touch:** when the MC hotbar item is the "portal gun", hide the MC hand and turn Portal's own viewmodel on (`r_drawviewmodel 1`). The real portal gun animates natively.

---

## 5. Recommendation

### Architecture: **plain Portal first, RTX second, one shared core**

`portalcraft.dll` (Win32) splits into:

1. **`core/`**: shared memory link (SkyCraft protocol, forked into a `PortalCraft_v1` mapping), coordinate mapping, collision export (BSP parser + dynamic edicts + portal carving), and the portal/teleport state machine. Everything here is pattern-free: only interfaces and datamaps.
2. **`game/`**: hooks: `CreateMove` (input/fire), `ProcessMovement` (puppet, through the interface vtable), the per-view mesh draw hooks, `RenderView`, `TeleportTouchingEntity`. These sit behind a **PE-fingerprint profile registry**, one profile for Steam Portal (start from SPT's `7462488-portal`-era patterns, then re-derive for build 19017868) and one for Portal RTX (start from the head-tracking mod's `_20250518` RVAs).
3. **`render/`**: `SourceMeshRenderer` (both games) and `RemixApiRenderer` (RTX only, optional).
4. **Loader:** `portal\addons\portalcraft.vdf` for Steam Portal. For RTX, try the addons vdf first and fall back to the ASI proxy (`bin\winmm.dll`).

Why plain first:

- It's installed now and is the same engine family as RTX.
- SPT (MIT) is a working map of nearly every hook we need for this exact game.
- Debugging works (RenderDoc/PIX on D3D9, no bridge process).
- Everything pattern-free carries over to RTX unchanged.
- RTX adds risks that are independent of the gameplay loop: bridge, runtime version, plugin loading, pattern re-derivation.

### Biggest risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | **Portal-hole collision for MC** (MC blocked by the wall behind a portal; floor-portal falls; fling velocity) | Carve portal rectangles out of exported collision while linked. Let Portal's own teleport fire, then forward pos/angles/**velocity** with seq/ack. Prototype early (Phase 2) |
| 2 | Two authorities on movement (MC physics vs Portal teleport/triggers/prediction) | MC authoritative except during the teleport handshake. `cl_predict 0`. Override in `ProcessMovement`, not `setpos` |
| 3 | Valve keeps patching Steam Portal (it broke `plugin_load` in 2025; build 19017868 is newer than SPT's documented builds) | Addons-vdf loading. Interfaces + datamaps first. Patterns gated by fingerprint |
| 4 | Portal RTX: plugin loading unknown, no SPT patterns, a custom Remix runtime, bridge API exports unknown | Verification checklist below. ASI fallback. Swap in an open-source bridge+runtime if needed |
| 5 | Bridge bandwidth (32→64-bit IPC) for per-frame overlay pixels and mesh churn | Meshes created once per section change. Overlay only when a GUI is open. Hand as 3D |
| 6 | Remix material/hash stability for injected geometry (flicker, wrong materials) | Prefer the Remix API path with explicit material hashes |
| 7 | Portal gameplay hazards that act on the puppet (turrets, energy balls, fizzlers, goo, crushers) vs MC health authority | Poll/restore puppet `m_iHealth` (datamap) and forward deltas as `PlayerHurt`. Map goo/`trigger_hurt` to MC damage. Fizzlers already remove portals natively |
| 8 | Licensing | SPT/SAR/bridge/head-tracking are MIT, dxvk-remix is zlib: all fine to borrow with notices. Don't ship Portal-Base or leaked code. Portal RTX assets aren't redistributable |

### Phased plan (each phase ends playable)

| Phase | Goal | Done when |
|---|---|---|
| 0 Link | Empty Win32 server plugin via `portal\addons\*.vdf` on build 19017868. Requests `VEngineServer021` / `EngineTraceServer003` / `PlayerInfoManager002` / `VEngineClient014`. Opens the SkyCraft-style mapping; MC handshakes | Console prints MC heartbeat; Portal player pos/angles stream to MC |
| 1 Walk | Collision export (BSP brushes + `ICollideable` dynamics), puppet via `ProcessMovement`, angles via `SetViewAngles`, input forwarding via `CreateMove` (swallow Portal's moves) | You sprint-jump around `testchmb_a_00` with MC physics; buttons and doors trigger from the puppet |
| 2 Portals | MC "portal gun" item → `IN_ATTACK/ATTACK2` injection; portal state read through datamaps; hole carving; teleport handshake with velocity | Fire both portals from MC, walk and fling through them, momentum preserved |
| 3 Render | Hand/GUI overlay quad; MC blocks drawn natively through per-view mesh hooks (visible through portals); Portal viewmodel toggling | Place blocks in test chambers and see them through portals |
| 4 Hazards & props | Health bridge, cube pickup (`IN_USE`), MC blocks as Portal-side collision (`func_brush`-like or vphysics), so cubes and turrets interact with builds | Full chamber playable |
| 5 RTX port | Fingerprint profile for the RTX `client.dll`/`server.dll`; loader decision; verify the bridge's Remix API; Source-drawn meshes captured by Remix | Same gameplay in Portal with RTX, blocks path-traced and seen through ray portals |
| 6 RTX polish | `RemixApiRenderer` (PBR atlas, emissive blocks, `CreateLight` torches), optional newer runtime swap | Glowstone lights the chamber through portals |

### Verification checklist for when Portal with RTX finishes downloading (read-only)

```powershell
$g='D:\SteamLibrary\steamapps\common\PortalRTX'
# 1) bitness of hl2.exe, bin\engine.dll, bin\d3d9.dll, bin\.trex\d3d9.dll, bin\.trex\NvRemixBridge.exe, portal_rtx\bin\*.dll (PE machine 0x14c vs 0x8664)
# 2) engine.dll strings: plugin_load, addons/, ISERVERPLUGINCALLBACKS003, VEngineServer0xx, EngineTraceServer003
# 3) server.dll strings: FirePortalDirection1, NewLocation, m_matrixThisToLinked, GameMovement001, PlayerInfoManager002
# 4) exports of bin\d3d9.dll: remixapi_InitializeLibrary / remixapi_RegisterCallbacks  (dumpbin /exports)
# 5) bin\.trex\*.log / rtx-remix\logs: bridge + runtime version string
# 6) In game: console `plugin_print`, then try an addons vdf with serverplugin_empty
```

---

## Sources

- Valve SDK tree (checked via `gh api`): https://github.com/ValveSoftware/source-sdk-2013
- `plugin_load` broken / addons still work: https://github.com/ValveSoftware/Source-1-Games/issues/6997 ; SPT workaround https://github.com/OutOfBoundsOffice/SourcePauseTool/issues/370 ; https://github.com/evanlin96069/plugin_fix
- SourcePauseTool (MIT): https://github.com/OutOfBoundsOffice/SourcePauseTool ; usage wiki https://wiki.sourceruns.org/Source-Pause-Tool.html
- SPT IPC used to drive Portal: https://github.com/cozyblaze/portal-agent
- SourceAutoRecord (MIT): https://github.com/p2sr/SourceAutoRecord
- SST: https://mikes.software/sst/ , https://github.com/mikesmiffy128/sst
- Portal-Base (ported old Portal code, no licence): https://github.com/SonicEraZoR/Portal-Base
- dxvk-remix (zlib; Remix API `public/include/remix/remix_c.h`, `public/include/remixapi/bridge_remix_api.h`, `bridge/src/client/remix_api.cpp`, `RtxOptions.md`): https://github.com/NVIDIAGameWorks/dxvk-remix
- bridge-remix (MIT): https://github.com/NVIDIAGameWorks/bridge-remix ; rtx-remix issues #848, #1086, #663: https://github.com/NVIDIAGameWorks/rtx-remix/issues
- Portal RTX file layout and runtime notes: https://github.com/xoxor4d/p2-rtx ; https://www.techspot.com/community/topics/modders-discover-portal-rtx-files-can-make-half-life-2-and-some-other-games-look-stunning.278301/
- NVIDIA "Setting Up Portal for RTX Remix Compatibility": https://docs.omniverse.nvidia.com/kit/docs/rtx_remix/1.2.4/docs/tutorials/tutorial-portalhl2.html
- Remix API from 32-bit through the bridge, precedent: https://github.com/xoxor4d/p2-rtx (`remix_rayportal.cpp`), https://github.com/xoxor4d/remix-comp-base (MIT)
- Portal RTX head-tracking mod (MIT; 32-bit, ASI, client.dll RVAs, build 2025-05-18): https://github.com/itsloopyo/portal-with-rtx-headtracking
- SourceRTXTweaks (`c_frustumcull` note): https://github.com/BlueAmulet/SourceRTXTweaks
- Portal RTX 2025 update (DLSS 4 / NRC): https://store.steampowered.com/news/app/2012840/view/537728173036536926 ; https://www.dsogaming.com/patches/portal-rtx-now-supports-dlss-4-rtx-neural-radiance-cache/
- Remix bridge architecture: https://github.com/NVIDIAGameWorks/bridge-remix ; https://www.nvidia.com/en-us/geforce/news/portal-with-rtx-ray-tracing/
