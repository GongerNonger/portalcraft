# PortalCraft: audit and plan to "beatable as Steve"

Date: 2026-10-06 (evening). Read-only audit of the repo at `a86d784` (main, tag `stable-8`). Nothing
was run or built; Portal and Minecraft were not launched. Evidence is cited as `file:line`.
Confidence tags: **H** read directly in code, logs or map data; **M** inferred from them; **L** educated
guess. This supersedes the 10-02 audit (commit `32a9f7c`); what became of its items is in Appendix A.

The bar, in the owner's words: "a creative player should be able to solve all the puzzles as Steve as
soon as portals and movement work correctly." So: the whole game beatable, no game-breakers, no
jitter while moving. Not: blocks through portals, lighting, co-op, RTX.

What was read: all of `host/portal/src`, `src/main/java/dev/portalcraft/host`, `src/main/java/dev/portalcraft/mixin`,
`src/client/java/dev/portalcraft/client/HostDriver.java` and `HostHealth.java`, `protocol/portalcraft_protocol.h`,
`run/devtests/suite.sh`, the plugin log (`portal/addons/portalcraft.log`, 8248 lines, last write 23:22)
and `run/logs/latest.log`, `git log --stat stable-1..HEAD`, the notes file
(`project_minecraft_crossover.md`, treated as a witness, not as truth), the entity lump (lump 0) of all
18 campaign BSPs, Portal's own code in `host/portal/ref/portal-base/sp/src/game/{server,shared}/portal`
plus `server/triggers.cpp` and `shared/gamemovement.cpp`, and SkyCraft 0.1.2.

**SkyCraft:** it is not under `C:\tmp` or `C:\Users\Administrator`; the clone the 10-02 audit used is
still in this session's scratchpad (`%TEMP%\claude\C--Users-Administrator\ad80120e-...\scratchpad\SkyCraft`,
one squashed commit `bfcaf17`). I read it there rather than cloning again. It will vanish with the
scratchpad; if it is wanted on disk, `git clone --depth 1 https://github.com/chasmlol/SkyCraft host/portal/ref/skycraft`
(the folder is git-ignored, `.gitignore:12`).

---

## 0. Summary

**Judgements I am most confident of (H unless marked):**

1. **The portal-crossing core is sound and is the best-tested thing in the repo.** Minecraft carries
   Steve through a linked pair inside its own physics step (`PlayerCrossings.java:118-170`), sends
   positions "unfolded" so Portal's own teleport fires where Steve went (`HostDriver.java:343-344, 587-590`),
   and the plugin matches that teleport instead of handing it back (`plugin.cpp:1774-1801`). The carry
   is the same rigid move Portal makes (`prop_portal.cpp:1062-1063` carries the hull centre; ours does,
   `PlayerCrossings.java:249-252`), the exit rules match Portal's constants exactly (300 up out of a
   floor, 1000 cap: `prop_portal.cpp:43-45, 1141-1142, 1160-1162` vs `PlayerCrossings.java:212-230`),
   and the air physics match `sv_gravity 600` / no drag / 3500 cap (`PortalAir.java:22-25`). The suite
   passes 16/16 on a clean world. Leave this alone.

2. **The reconciliation classifier in `serverProcessMovement` is the wrong model, and most of the
   last three days' plugin commits are patches on it.** Every tick Portal's movement runs from a
   stale position (last tick's write), the result is diffed against what was written, and the
   difference is sorted by *magnitude* into teleport (>24 u), impulse (velocity jump >40 u/s with no
   move), or shove (>0.5 u), with eight suppression rules layered on top (`plugin.cpp:1741-1763,
   1854-1912`: `carriedLately`, `g_onLooseProp`, `g_crossedAt`, `bounced`, `draggedOnProp` x3,
   `g_riding`). SkyCraft never does this: it overwrites the host player every frame, zeroes its
   velocity, and ignores any host-side displacement under 300 units (`Game.cpp:19, 582-585, 788-793`).
   PortalCraft cannot ignore everything (portals, lifts, moving platforms are real), but it should
   hand over only what it can *name*, and ignore the rest by default. Details and the smallest change
   in section 7.

3. **The impulse path hands over noise, not game pushes.** It was built for `trigger_push` and
   `env_physexplosion` (`docs/PORTAL_MECHANICS.md:12-13`). In Portal's code a `trigger_push` on a player
   sets *base velocity* (`triggers.cpp:2270-2290`), which the movement adds before the move and
   subtracts after (`gamemovement.cpp:1467, 1490-1509`), so the plugin never sees it as a velocity
   change; it sees a per-tick position delta, i.e. a shove. And no `env_physexplosion` in the campaign
   has the "push players" flag (a_11 flags 13, a_15 flags 1; lump data). The eight `impulse:` lines in
   today's log are all noise: Portal's velocity jumping from 0 to -846 u/s mid infinite-fall
   (`portalcraft.log:1944-1946, 4078-4083`) and a 25,640 u/s velocity beside a cube (`:7074-7075`).

4. **A concrete plugin bug feeds that noise:** `interpolatedMinecraft` computes the velocity as
   `(pb - pa) * 20` *before* checking whether the two steps are 64+ units apart (a teleport) and
   returns `pb` without resetting it (`plugin.cpp:1521-1524`). After any `--goto`, level start or map
   change Portal's player is written with a velocity of thousands of u/s for one tick; the log shows
   25,640 u/s (`:7074`). The 10-05 "never faster than Portal's speed limit" fix (`HostDriver.java:1091-1092`)
   caps the symptom on the Minecraft side. One line fixes the cause.

5. **Chamber-by-chamber, two things are likely game-breakers and neither is in the suite:**
   (a) riding a *sideways-moving* platform over a death pit, needed from chamber 08 (`testchmb_a_04`,
   `rail_cart_lvl5` speed 50) and again in 12, 13, 15, 18, 19; nothing scripted has ever started one.
   (b) the GLaDOS cores: `prop_glados_core` uses `models/props_bts/glados_ball_reference.mdl`, which
   is not in `LiveEntities.movableProp` (`LiveEntities.java:144-146`), so a carried core stays solid in
   Minecraft (`:190-191` only un-solidifies movable props) and Steve will be blocked by the thing in
   his own hands, the exact bug that `carrying()` fixed for cubes (`:37-44`). (M on the consequence,
   H on the code and the model name.)

**Least sure of:** whether the "took its player back" bounce-backs (`plugin.cpp:1854-1867`) go away
once Portal's forced duck is mirrored (section 3.2; I could not trace the geometry of the one case the
notes describe); whether `func_breakable_surf` glass in `escape_01` stops being solid in our copy
once a rocket shatters it (L); whether the ending of `escape_02` (upward `trigger_push` at 21 u/s plus
`trigger_gravity` 0.00001) happens inside the scripted hand-over or just before it (M: both
`point_viewcontrol`s carry spawnflag 4, "take control", which sets `FL_FROZEN`, `triggers.cpp:2761,
3067-3069, 1670`, and the plugin keys on that flag, `plugin.cpp:761-769`).

---

## 1. Architecture as built (what matters for movement)

| Piece | Where | Owner |
|---|---|---|
| Steve's position and velocity, every tick | Minecraft (`HostDriver.tick`/`tickEnd`, `HostDriver.java:130-346`) | Minecraft |
| Portal's player written to Steve's interpolated position after Portal's own movement ran | `plugin.cpp:1914-1973` (`applyMinecraft` `:1530-1550`) | plugin |
| Portal-side moves classified and handed back (teleport / shove offset / impulse) | `plugin.cpp:1733-1912`; applied `HostDriver.java:1019-1086` | plugin decides |
| Portal crossings | Minecraft makes them (`PlayerCrossings`); plugin matches Portal's teleport or forces one (`plugin.cpp:1774-1848`) | both, reconciled by `crossCount`/`crossMatched` |
| Collision: BSP brushes, static props, live entities (16 Hz), holes behind linked portals | `HostCollision`, `LiveEntities`, `BspMap`, `StaticProps` | Minecraft copy of Portal |
| Riding a lift (vertical) | plugin keeps Portal's z, Minecraft follows (`plugin.cpp:821-864, 1951-1954`; `HostDriver.java:290-304`) | Portal |
| Carried sideways by a mover | Minecraft's `carry()` applies the entity's 16 Hz delta (`HostDriver.java:746-765`); plugin suppresses hand-overs for 1.5 s (`:813-815, 1882-1884`) | Minecraft, approximately |
| Scripted scenes | `FL_FROZEN`/`FL_ATCONTROLS` -> Portal drives, Minecraft follows with no input (`plugin.cpp:764-783`; `HostDriver.java:305-317`). `m_hViewEntity` is not networked so that branch is dead (`:745`, lookup returns -1 per the notes) | Portal |
| Health, death, checkpoint reload | Minecraft owns health; plugin refunds Portal's damage and forwards it; death either way kills the other; `reload` after 4 s (`plugin.cpp:900-966`; `HostHealth.java`) | Minecraft |
| Air physics after portals / in falls | `PortalAir` + `LivingEntityAirMixin` | Minecraft, with Portal's numbers |
| Steve's blocks in Portal | static vphysics boxes + `TraceRay` hook (`plugin.cpp:968-1140, 2030-2128`) | plugin |
| Camera | Portal's mouse look; plugin only sets eye height, smooths small steps, F5 (`camera.cpp:265-308`) | Portal |

---

## 2. Audit of what has been done

Severity for the goal: **Breaker** (can stop a chamber), **Jitter** (visible shake/snap), **Debt**
(works, will bite), **OK**.

### 2.1 Reconciliation (plugin `serverProcessMovement`)

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| R1 | Jitter/Breaker | Magnitude-based classification of every Portal-side delta, with suppression windows in milliseconds (`carriedLately` 1500 ms, `g_crossedAt` 500 ms, `g_rideStill` 300 ms) and units (0.5 / 3 / 8 / 12 / 24 / 64) and u/s (40 / 300 / 450 / 700). Each threshold was tuned from one trace (commit messages `5dfa751`, `03cdec3`, `853af24`). | `plugin.cpp:1741-1763, 1873-1912, 813-815` | Wrong model. Section 7 says what to replace it with. Not urgent *if* the chamber sweep (section 6) shows no jitter; urgent the moment it does, because the next bug will get a ninth rule. |
| R2 | Breaker | Velocity spike after a teleport between tick samples (see Summary 4). | `plugin.cpp:1521-1524`; log `:7073-7075` | Bug. Fix first. |
| R3 | Jitter | Impulse path (see Summary 3). With R2 fixed and the `trigger_push` reality, nothing legitimate reaches it. | `plugin.cpp:1741-1763`; `triggers.cpp:2270-2290` | Remove, or gate on "player overlaps a `trigger_push` with `SF_TRIG_PUSH_ONCE`" (the only case that is an impulse, `triggers.cpp:2223-2227`; none in the campaign lumps). |
| R4 | OK | Hard teleports (>24 u) handed with ack; level start, `trigger_teleport`, portal matches. Sound; the `following()` gate stops driving until the ack (`:1436-1438`). | `plugin.cpp:1885-1912` | Keep. |
| R5 | Debt | Shoves summed as offsets relative to Minecraft's ack (`pendingShoves`, `moveBase` check) is correct; but shoves are also what a `trigger_push` on the player becomes (base velocity = per-tick displacement). So a 40 u/s nudge in a_15's fire pit is 0.6 u/tick, just over the 0.5 hand-over floor, and will be handed as a stream of 0.6-unit offsets with 1-2 ticks' latency. Works as a conveyor, has never been tried. | `plugin.cpp:1411-1419, 1878-1881`; lumps (a_14, a_15, escape_02 `trigger_push` speed 40/120/300/350/500 flags 1) | Test in a_15 and escape_02. If it stutters, the fix is cause-based: the plugin can see the player's `FL_BASEVELOCITY` flag (`m_fFlags` is read already, `:744`) and `m_vecBaseVelocity` by name, and hand *that* as a velocity to Minecraft instead of offsets. |
| R6 | Jitter | `zLift` heuristic still present (learned up to 4 u, dropped by floor height / distance). | `plugin.cpp:1444-1451, 1720-1731, 1537-1545` | Works; invisible state. Leave until R1 is replaced (it becomes a named cause: "Portal rests its hull higher than ours"). |
| R7 | OK | `clampToProps`: a server-side hull sweep against cubes/radio before placing the player, slide along the face, hand a shove only if >8 u apart, off while holding. The one piece that is cause-based (it *predicts* Portal's push). | `plugin.cpp:2449-2483, 1929-1950` | Keep; extend its prop list (P2). |
| R8 | Debt | `g_onLooseProp` from `m_hGroundEntity` class prefix; `g_riding` only from vertical motion of the ground entity; sideways carry is a 1.5 s timer. | `plugin.cpp:821-864` | Replace the timer with "subtract the ground entity's own delta this tick before classifying" (section 7). |

### 2.2 Portal crossings

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| C1 | OK | Minecraft-side carry, fit inside the exit opening, rest of the step swept against the world, previous-tick position carried too (no interpolation smear), server teleport removed in favour of the "moved wrongly" grace and `isEntityCollidingWithAnythingNew` off in host maps. | `PlayerCrossings.java:118-170, 177-203`; `HostDriver.java:498-548`; `LivingEntityAirMixin.java:36-42`; `ServerMoveCheckMixin.java` | Sound. Verified by the suite (wall, fling, crouched, two 30 s infinite falls). |
| C2 | Jitter | **Centre mismatch.** Portal tests its player's centre, `origin + half hull` (36 standing, 18 ducked: `portal_player.cpp:535`), against the plane and the hole (`prop_portal.cpp:895-898`). It force-ducks the player on any crossing whose *entry* portal has a vertical component and the pair is not both floor/ceiling (`prop_portal.cpp:1016-1046`; a wall entry never ducks), and shifts the centre 16 u. Un-ducking then takes Portal's own duck timer. Steve's centre is at +36 (or +30 sneaking, `plugin.cpp:328-333`). For those ticks every floor/ceiling plane test differs by 12-18 u between the two sides. The `bounced` rule (`plugin.cpp:1854-1867`), `justBehindTicks >= 6` and `FIT_FRONT 2` (`PlayerCrossings.java:177-182`) are all symptoms of this. | as cited | Mirror Portal's duck: `m_bDucked` is a networked `DT_Local` field, so `findProp` finds it like `m_fFlags`. Send it in `HostState`; use 18 as the half height in `PlayerCrossings.step` and `geometricCrossing` while it is set. Then try removing `bounced` and watch the "not handed over" count in the suite. (M that this removes them all.) |
| C3 | OK | Forced crossing when Portal refuses (two ticks well behind, six just behind, eight pending, or just-opened portal). Matches Portal's ownership rule: it only teleports what the simulator owns, taken on `StartTouch` (`prop_portal.cpp:806, 1530`). | `plugin.cpp:1805-1848` | Keep; the "just opened" case is the right reading of ownership. |
| C4 | Debt | The hole is a 64x108 box, 12 u in front, 72 u (walls) / 640 u (floor, ceiling) behind, cut from the cell shapes. Portal's hole is 62.7x105.8, from 0.5 in front to 500 behind (`PortalSimulation.cpp:257-299`) and contains the exit side's world geometry; ours is void. | `HostCollision.java:25-48, 200-220` | No failing case in hand. Leave until the chamber sweep produces one (a wall portal placed low on a wall opposite a shallow floor is where it would show). |
| C5 | Debt | Any portal change clears the whole fixed cache (`CACHE.clear()`), not just cells the holes touch. Sloped cells cost ms each (comment `:123`). Possibly the "worst frame 212 ms" in the perf lines. | `HostCollision.java:179-192` | Invalidate the union of old and new hole boxes, as `setDynamic` does. Low priority. |
| C6 | OK | Heading turns with the crossing until Portal's view catches up. | `HostDriver.java:982-1017` | Sound. |

### 2.3 Collision copy and live entities

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| E1 | OK | Brushes, static props, 1/16-block column cutting for slopes, live entities as BSP models / .phy hulls / boxes, per-cell cache with dirty-box invalidation. | `HostCollision.java`, `LiveEntities.java:294-383` | Sound. Same approach as SkyCraft's 1/8-block micro-steps (`SkyCraft docs/DESIGN.md` 5.1), finer. |
| E2 | Breaker | Movable props are an allowlist by model substring: `metal_box`, `turret`, `radio` (`LiveEntities.java:144-146`), and the plugin's sweep list is `metal_box`, `radio` only (`plugin.cpp:2393`). Everything else that is `prop_physics` (28 chairs in a_15, oil drums, PC cases, cinder blocks in `escape_01`; the four GLaDOS cores) is a solid, immovable wall to Steve, and a carried core stays solid (Summary 5b). | lumps; `LiveEntities.java:190-191` | Send the entity's class and whether its physics is motion-enabled in `HostEntity` (there is room: `flags`), and make "movable" = `prop_physics*` or `prop_glados_core` with motion on. Keep the button-innards exclusion the plugin learned (`:2386-2389`) by class, not model. "Carried" = any VPHYSICS prop within reach while `gunEffect == 2`. |
| E3 | OK | Excluded on purpose: security cameras, energy balls, Portal's shadow clones, `physicsshadowclone`, triggers. All justified from Portal's code (clones: `physicsshadowclone.cpp`; balls kill on touch themselves: `prop_energy_ball.cpp:376-379`, DMG_DISSOLVE 1500). | `LiveEntities.java:296-315`; `plugin.cpp:2272-2276` | Keep. |
| E4 | Debt | `func_physbox` (a_15 broken stairs, 22 in `escape_01`, 3 in `escape_00`) is VPHYSICS with a `*N` model; `place()` has no branch for that and falls to the bounds box. | `LiveEntities.java:318-341, 373-381` | Approximate but solid; fine unless a stair is unclimbable. |
| E5 | Jitter | Steve's own block boxes are static vphysics in Portal. Portal's player, written a hair inside one, is pushed out by Portal's physics and that push is handed back. The two 8.03 u "push:" lines at the a_10 fling start spot are exactly where the owner's builds sit (notes, "a_10 near (-1400,-2908)"). | `portalcraft.log:858, 1286`; `plugin.cpp:1022-1038` | Cause-based fix: a delta that starts inside one of our own `g_blockBoxes` is ours to ignore (Minecraft already put Steve legally against the block). |

### 2.4 Movers (lifts, platforms, doors, buttons)

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| M1 | OK | Vertical lifts: Portal owns z while the ground entity moves; level-start hold (2.5 s) and riding snap in the first 8 s. Soaked over every map via `map X`. | `plugin.cpp:821-864`; `HostDriver.java:85-93, 232-236, 290-304` | Sound for `map`. Real `trigger_changelevel` elevators never driven in sequence (notes 10-06 18:00). |
| M2 | Breaker (unknown) | Sideways platforms: carried by `carry()` from 16 Hz entity deltas (`HostDriver.java:746-765`), hand-overs suppressed 1.5 s (`carriedLately`). Never confirmed on a moving platform. Needed in chambers 08, 12, 13, 15, 18, 19 (lumps: `rail_cart_lvl5` 50 u/s, `rail_cart_rm6`, `func_tracktrain_lvl7`, `tractrain_brush_1/2` 30, `func_tracktrain_b00`, `rail_cart_lab2` 40). | lumps; code cited | Highest-value untested mechanic. Section 6 step 3. |
| M3 | OK | Floor buttons: walks over them pass in four directions (suite); slope step-up (`EntityStepMixin`), camera step smoothing (`camera.cpp:276-289`). | suite; mixins | Sound. |
| M4 | OK | Doors: streamed BSP brushes; `func_door_rotating` likewise. | `LiveEntities.java:318-330` | Sound. |

### 2.5 Props (cubes)

A four-layer stack: Minecraft's copy as an upright box with 0.5 u skin (`LiveEntities.java:141, 342-372`),
the plugin's hull sweep (`clampToProps`), Minecraft's per-tick "hit" to shove it (`pushProps`,
`HostDriver.java:362-400`), and the `draggedOnProp` / `g_onLooseProp` suppressions. Push and stand
pass the suite on a clean world; carry passes `cubetest`. It works, and every layer has a reason in
its comment. The honest judgement: it is as good as "Portal's player stands where Steve is" can get
without Portal's own contact resolution, and I would not touch it before the chamber sweep. What I
would change is only E2 (which props count) and, with R1, stop handing anything back while
`g_onLooseProp` is set (today only impulses under 700 u/s are suppressed there, `plugin.cpp:1752-1754`).

### 2.6 Health, death, scripted scenes, hazards

Sound and verified live for toxic water, falls and the energy ball (notes 10-02 night, 10-05 commit
`1224c39`). Turret bullets, rocket explosions, death-field `trigger_hurt`s (type 0/generic in a_14 and
escape_02, DMG_BURN type 8 incinerators in a_13, a_15, escape_02) and the neurotoxin all go through
the same refund path (`plugin.cpp:950-965`) and have not been tried (M that they work: it is the same
code). One gap: Portal's own `FindClosestPassableSpace` failure deals 1 crush damage per frame
(`portal_player.cpp:748-759`); refunded and forwarded as x0.2, Steve would bleed slowly in a place
Portal cannot free its player. Worth a log line when `health < 100` arrives with no obvious source.

### 2.7 Camera and input

Sound. Eye height, step smoothing, sprint FOV, F5 with hull trace, Chell hidden by `DrawModel`
hooks (`camera.cpp`). Keys polled, wheel and text via a window subclass, cursor while a screen is
open (`plugin.cpp:2545-2711`). None of it is on the critical path for "beatable".

### 2.8 Rendering

Blocks over the platform lasers (`-pcearlysolid`, `worldrender.cpp:817-830, 975-977`) and the rim it
gives: not on the path to beatable. Park both.

### 2.9 Test rig

Good and more than SkyCraft has (two JUnit tests, no rig). Gaps: the suite covers two maps and never
a mover, a turret, a hazard, a carried prop through a portal, or a level change; the replay ring is
45 s (`plugin.cpp:486`), shorter than any chamber; `restart.sh` can `+load` a save. Section 6 builds on
exactly these.

### 2.10 Repo hygiene for a release

`tools/setup.ps1:26-28` seeds only `worlds/PortalCraft/level.dat`; `worlds/PortalCraft/` holds only
that file (H). Minecraft 26.3 needs `data/minecraft/world_gen_settings.dat` too (notes 10-06). Commit
the `data/` folder from a fresh `run/saves/PortalCraft` and copy it.

---

## 3. The references, point by point

### 3.1 Portal's own code (`host/portal/ref/portal-base`, MIT mod base; constants may differ from retail)

| Question | What the code says | Ours | Gap |
|---|---|---|---|
| When does Portal teleport the player? | Simulator owns the entity (ownership on `StartTouch`), it is linked, the *centre* is behind the plane, and the entity is in the hole shape. `prop_portal.cpp:804-927, 1530` | Minecraft: centre behind plane and inside the oval (`PlayerCrossings.java:136-141`); plugin forces after 2/6/8 ticks or at once for a just-opened portal (`plugin.cpp:1833-1834`) | Ownership is modelled by the "just opened" rule; the hole test uses an oval where Portal uses the 98 % rectangle (`PortalSimulation.cpp:274-292`). Minor. |
| Where is the centre? | `origin + half hull`: 36 standing, 18 ducked. `portal_player.cpp:535` | 36 / 30 sneaking / 12 gliding (`plugin.cpp:328-333`) | C2. |
| Forced duck | Entry portal with any vertical component, pair not both floor/ceiling: duck, centre shifted 16. `prop_portal.cpp:1016-1046`; `ForceDuckThisFrame` only sets `m_bDucked` (`portal_player.cpp:1159-1166`) | Not modelled | C2. |
| Position carry | `M * centre + (origin - centre)` (`:1062-1063`), then `FindClosestPassableSpace` fix-ups elsewhere | Same carry; our own `fit` (`PlayerCrossings.java:197-203`) | Equivalent by design; plugin discards Portal's fix-up for matched crossings (`plugin.cpp:1777-1786`). Fine. |
| Exit velocity | Floor exit: z at least 300 for players; cap 1000. `prop_portal.cpp:43-45, 1141-1142, 1160-1162` | Same (`PlayerCrossings.java:212-230`) | None. The notes said "from memory, unverified"; now verified. |
| Hole shape | 0.5 in front to 500 behind, 98 % of 64x108; holds remote geometry. `PortalSimulation.cpp:257-299` | 12 front, 72/640 behind, 100 %, void | C4. |
| Funnel | Only with no sideways input, falling, near the portal. `portal_gamemovement.cpp:177-230, 314-343` | Looser conditions, capped blend (`PortalAir.java:82-109`) | Acceptable. |
| `trigger_push` on a player | Base velocity, not an impulse (`triggers.cpp:2270-2290`); `SF_TRIG_PUSH_ONCE` is the only impulse (`:2223-2227`) | Handed as impulses in theory, as shoves in practice | R3, R5. |
| `env_physexplosion` on players | Only with flag 2; none in the campaign lumps | n/a | Remove the assumption from `PORTAL_MECHANICS.md:13`. |
| Energy ball vs player | 1500 DMG_DISSOLVE on touch (`prop_energy_ball.cpp:376-379`) | Refund + kill flag | Verified live. |
| Scripted cameras | `point_viewcontrol` flag 4 -> `EnableControl(false)` -> `FL_FROZEN` (`triggers.cpp:2761, 3067-3069, 1670`). Both campaign cameras have flags 28. | `FL_FROZEN` -> `kHostScripted` | Covered. |
| Cube/turret pickup | `PlayerUse` is Portal's own (`portal_player.cpp:866-905`) | E is Portal's use; Tab is Minecraft's E (`HostDriver.java:1113-1133`) | Covered. |
| Standing on a prop | Portal's player rides vphysics ground (`VPhysicsShadowUpdate`, `portal_player.cpp:624-654`) and gets velocity kicks from it (`:684-713`) | The kicks are what `g_onLooseProp` suppresses | R1. |

### 3.2 SkyCraft (chasmlol, 0.1.2)

| Topic | SkyCraft | PortalCraft | Lesson |
|---|---|---|---|
| Who owns position | Minecraft; Skyrim's player set every frame, its velocity zeroed. `Game.cpp:788-793` | Same, but Portal's movement runs first and its velocity is written from Minecraft's | Same model. |
| Host-side pushes | Ignored under 300 units; over that, a full resync by `teleportSeq`. `Game.cpp:19, 576-591` | Classified from 0.5 units up | Default-ignore is the proven model; Portal needs named exceptions (portals, movers), not a lower threshold. |
| Hand-over to the host | Furniture, mount, kill move, AI-driven, certain camera states; hand-back is a resync. `Game.cpp:380-410, 610-622` | `FL_FROZEN`/`FL_ATCONTROLS` | Same idea; ours is narrower because Portal is simpler. |
| Waiting for collision after a teleport | Minecraft holds still and reports `ack - 1` until released; Skyrim re-teleports if the hold is far from its player. `SkyClient.java:385`; `Game.cpp:624-633` | `holdWithHostUntil` 2.5 s in level starts (`HostDriver.java:278-283`) | Equivalent. |
| Moving platforms, standing on physics objects | Nothing. Skyrim has no player-carrying movers; NPCs are pushed out of blocks (`NpcBlocks.cpp`). | Needed | Nothing to borrow. PortalCraft is past SkyCraft here. |
| Level transitions | World id change -> collision epoch reset + teleport. `Game.cpp:565-574` | `LevelInit` -> `g_needSync`; `MapRegions` x-offset per map | Equivalent. |
| Slopes | 1/8-block micro-steps (DESIGN 5.1) | 1/16 columns + `EntityStepMixin` | Ours is finer. |
| Camera | Skyrim's camera overwritten from Minecraft's eye and bob (`Game.cpp:800-905`) | Portal keeps its own look; plugin adjusts height | Different, both fine. |
| Health | Minecraft authoritative, host damage forwarded, death kills the host player (DESIGN 8.3) | Same | Same. |
| Testing | Two unit tests, no rig | 16-check scripted suite, two pairs, replay tool | Nothing to borrow. |

---

## 4. Chamber-by-chamber inventory

Entity counts are from lump 0 of each BSP in `D:\SteamLibrary\steamapps\common\Portal\portal\maps`
(H). Chamber numbers per map are from memory of the game (M). Status: **H**andled (code + verified),
**h** handled in code but unverified in that chamber, **U**nknown/untested, **X** unhandled.
"Native" means Portal does it to its own player/props and nothing of ours is in the way.

| Map | Chambers | Needs | Status, per mechanic |
|---|---|---|---|
| `testchmb_a_00` | 00-01 | Wake-up camera (`point_viewcontrol` flags 28), `trigger_teleport` blackout, 8 fixed `prop_portal`, 1 cube on a button, 2 fizzlers, elevator | Scripted **H** (verified 10-02), teleport **H** (opaque hard move), fixed portals **H**, cube/button **H** (suite on a_02), lift **H** |
| `testchmb_a_01` | 02-03 | Get the gun (`weapon_portalgun`), first shots, `func_portal_bumper`/`noportal` placement, 3 elevators, 1 rotating door | Gun pickup native **H** (3D gun verified), placement native **H**, doors **H** |
| `testchmb_a_02` | 04-05 | Cubes (2) through portals onto buttons, fizzlers, elevators | **H** (suite: push, stand, carry); cube *through a portal* while held: native, **h** |
| `testchmb_a_03` | 06-07 | Energy balls (2 launchers, life 12) into catchers, ball-trap doors, elevators, `func_tracktrain_b00` platform | Ball kill **H** (verified), catcher native **h**, platform ride **U** (M2) |
| `testchmb_a_04` | 08 | Energy ball starts `rail_cart_lvl5` (50 u/s) over a death field (`trigger_hurt` 100 generic at 62,62,-192); ride it | **Platform ride U** (first hard dependency on M2); death field **h** (same path as toxic water) |
| `testchmb_a_05` | 09 | Cube, buttons, elevators | **H** |
| `testchmb_a_06` | 10 | First momentum fling, cube | Fling **H** (suite "fling", numbers verified vs source) |
| `testchmb_a_07` | 11-12 | Second portal colour (2 `weapon_portalgun`), rotating doors (4), `rail_cart_rm6`, energy ball, toxic water (`hurt_player_goo_`), `trigger_push` 400 flags 9 | Gun upgrade native **h**, goo **H**, platform **U**, push **U** (R5) |
| `testchmb_a_08` | 13 | Energy ball, `func_tracktrain_lvl7`, 2 cubes, `trigger_vphysics_motion` (props only) | Platform **U**, rest **H** |
| `testchmb_a_09` | 14 | Flings over a death field (500,1768,-288), energy ball, cube | Fling **H**, field **h** |
| `testchmb_a_10` | 15 | Three launchers, two `tractrain_brush` platforms (30 u/s) over goo, spawned cart, 4 `trigger_push` 400, 5 fizzlers, 5 security cameras | Portals **H** (suite lives here), platforms **U** (jump on a moving one: `carriedLately`, unconfirmed), push **U**, cameras non-solid **H** |
| `testchmb_a_11` | 16 | 12 floor turrets, 10 cubes as shields, 5 `npc_bullseye`, 2 `env_physexplosion` (flags 13: props only) | Turret bullets vs Steve **h** (refund path), turrets vs Steve's blocks **H** (TraceRay hook verified for portal shots), knocking a turret over by walking into it **U** (`pushProps` hit; Portal's player would push by contact), picking one up native **h** |
| `testchmb_a_13` | 17 | Companion cube, incinerator (`trigger_hurt` 100 DMG_BURN at 1356,-192,-208), 3 launchers, `box_pusher` push 450 (props), 4 camera props | Cube **H**, incinerator native + burn kill **h**, balls **H** |
| `testchmb_a_14` | 18 | Long chamber: 4 turrets, 14 bullseyes, 2 launchers, water hazards (3 `trigger_hurt`), `func_tracktrain_b00`, 8 rotating doors, 2 `trigger_push` 40 flags 1, generic death field | Turrets **h**, platform **U**, pushes **U**, doors **H** |
| `testchmb_a_15` | 19 | `rail_cart_lab2` (40 u/s) into fire; 11 `trigger_hurt` (fire 1/tick DMG_BURN, 200 burn, water); escape begins: 5 `func_physbox` broken stairs, 12 `func_rotating`, 4 ragdolls, 28 chairs + junk `prop_physics`, pushes 40/350/500, `physexplosion` lids (props) | Platform **U**, fire **h**, physbox stairs **h** (E4, bounds boxes), junk props **immovable to Steve** (E2: jump or punch), `func_rotating` fans streamed as BSP **h** |
| `escape_00` | — | Box tubes (10 `func_tracktrain` 600 u/s, props only), 1 death field, 3 physboxes, 34 doors, 18 portal detectors, cans/bottles | Mostly native **h**; junk props E2 |
| `escape_01` | — | Rocket turret (`laser_1`) whose rocket must be redirected through portals into 3 `func_breakable_surf` panes; 10 turrets; 22 physboxes; 7 oil drums, chairs, cinder blocks | Rocket native **h**, explosion damage **h**, glass becoming passable after shattering **U** (L), turrets **h**, physbox stacks **h**, junk E2 |
| `escape_02` | GLaDOS | 4 `prop_glados_core` (`glados_ball_reference.mdl`) to carry into the incinerator (`trigger_hurt` DMG_BURN at 10384,1216,176), `rocket_1`, neurotoxin countdown (4 `vgui`), `logic_playerproxy`, ending: `trigger_push` up 21 + `trigger_gravity` 0.00001 + 9 pushes at 300 + `point_viewcontrol` flags 28 + credits | **Cores: X** (E2, carried core stays solid), rocket **h**, neurotoxin **h**, ending **U** (scripted if the camera takes control before the pull-up; M) |

Mechanics across the game, rolled up:

| Mechanic | Status | What decides it |
|---|---|---|
| Portals: walk, fall, fling, crouch, infinite fall, re-placed mid-fall | **H** | suite 16/16 |
| Momentum flings | **H** | `PortalAir`; constants verified |
| Cubes: push, stand, carry, button | **H** (clean world) | suite + cubetest |
| Floor buttons, doors, fizzlers, placement rules | **H** | suite + native |
| Elevators (vertical), level start | **H** via `map`; **U** via real `changelevel` in sequence | notes 10-06 |
| Sideways platforms (6 maps) | **U** | never started by a script |
| Energy balls, catchers | **H** / native | verified kill |
| Toxic water, death fields, incinerators | **H** / **h** | one path |
| Turrets (3 maps), rocket turret (2) | **h** | untested; same damage path |
| `trigger_push` on the player (4 maps) | **U** | shove stream, R5 |
| Scripted scenes (a_00, escape_02) | **H** / **U** | `FL_FROZEN` |
| GLaDOS cores | **X** | E2 |
| Junk `prop_physics` in corridors (a_15, escape_*) | immovable to Steve | E2; punch/jump as a workaround |
| Breakable glass (escape_01) | **U** | |
| Saves/loads mid-chamber | **H** | `+load pcrestart` used daily |

---

## 5. What is actually left

In the order I would do them. Each item is tied to a chamber in section 4.

1. **Plugin bugs that corrupt movement everywhere:** R2 (velocity spike) and R3 (impulse path). Half a day.
2. **Props that must be movable or carriable:** E2 (cores, junk; send class + motion flag). Half a day.
   Without it `escape_02` is not beatable (M).
3. **Sideways platforms, M2:** confirm riding and jumping on/off in a_04 (chamber 08), then a_10 and a_15.
   Unknown effort: one day if `carry()` holds, three if the mover delta has to be modelled in the plugin.
4. **Duck mirroring, C2:** one protocol field, two uses. One day including the suite's bounce count.
5. **The real walkthrough**, chamber 00 to credits via the real elevators, with the ledger below,
   fixing only what breaks. This is where turrets, `trigger_push`, the glass, the cores and the ending
   get their first real test. Two to four days of play and fixes.
6. **Release blocker:** `setup.ps1` seed world (2.10). An hour.
7. **Only if the walkthrough shows jitter at movers or props:** replace the magnitude classifier
   with the cause-based one (section 7). Two days.

Not left, despite being open in the notes: the laser/rim rendering, the hole's exit geometry, the
funnel conditions, elytra level flight, the standalone-mod portal shader. None stops a chamber.

---

## 6. Plan to finish

### Step 1: fix the two plugin bugs (R2, R3)

Build: in `interpolatedMinecraft`, when `dist(pa, pb) > 64` return `pb` with `*velocity` set from
`g_mc.velocity` (carried), not from the step (`plugin.cpp:1521-1524`). Remove the impulse branch, or
reduce it to `SF_TRIG_PUSH_ONCE` overlap (none in the campaign, so removing is the same thing).
Prove: run `suite.sh` on both pairs; `grep -c "impulse:" portalcraft.log` is 0 over a suite run;
`grep "to (" portalcraft.log` never shows a velocity above 3500. Don't: touch the shove path yet.

### Step 2: props by class, not model name (E2)

Build: `HostEntity.flags` gains `kEntityLoose` (class `prop_physics*` / `prop_glados_core` /
`npc_portal_turret_floor`, `MOVETYPE_VPHYSICS`, motion enabled, no move parent; `plugin.cpp:2340-2352`
has the entity in hand). `LiveEntities.movableProp` and the plugin's `refreshLooseProps` read the
flag; "carried" = any loose prop within `CARRY_REACH` while `gunEffect == 2`. Keep the button-innard
exclusion by checking the parent, not the model. Prove: a_02 cube checks still pass; in `escape_02`
(`map escape_02`, walk to a core, `+use`) the log shows the core's entity go `carried`, and Steve can
walk forward holding it; in a_15's office, walking into a chair moves it (`pushProps` hit lands).
Don't: make every prop sweep-stop Steve in `clampToProps` (that was the "can't climb the button" bug).

### Step 3: sideways platforms (M2), chamber 08 first

Build nothing yet. Prove by hand once: play a_04 to the pellet, start the cart, `save pc_a04_cart`
while standing on it (saves are not cheats; `restart.sh save` already loads one). From then on the rig
can `+load pc_a04_cart` and trace 300 ticks with W/S and a jump: assert no z reversal over 0.3 u
(the suite's `rev` statistic), no "handing teleport", Steve's x/y tracks the platform's entity origin
within 8 u, and a jump lands back on it. Repeat for a_10 (`tractrain_brush_1`) and a_15 (`rail_cart_lab2`).
If it fails, the fix is in the plugin: read the ground entity's origin delta this tick and subtract it
from the observed delta before classifying (section 7), and in Minecraft apply the same delta from
`HostState` instead of the 16 Hz entity stream. Don't: tune `carriedLately`'s 1500 ms.

### Step 4: mirror Portal's duck (C2)

Build: `findProp(table, "m_bDucked")` on the player (same as `m_fFlags`, `plugin.cpp:744`); `HostState`
flag `kHostDucked`; `steveHalfHeight()` returns 18 while set and Steve is not sneaking;
`PlayerCrossings.step` takes the same half height from `HostState`. Prove: suite's "no bounce-backs"
count (`suite.sh:57`) at 0 over three runs with `bounced` logging left in; then delete the `bounced`
rule and re-run; if the count stays 0, it was the cause. Don't: change `FIT_END`/`FIT_FRONT` at the same time.

### Step 5: the chamber ledger and the walkthrough

Build the ledger first, it is cheap:
- Raise the replay ring from 45 s to 5 min (`kReplayTicks`, `plugin.cpp:486`; the tick struct is ~130 B,
  so 20,000 ticks is under 3 MB). A replay cannot cross a level (`plugin.cpp:2973`), which fits one
  chamber per file.
- For each map, during the walkthrough: at the start lift, `save pc_<map>`; play the chamber; at the
  exit lift, `fake_mc --dump-replay <map>`. That gives `addons/replay-<map>.txt` plus a save: a human
  solve, recorded once.
- Regression = `restart.sh save pc_<map>` on pair 1, `fake_mc --instance 1 --replay replay-<map>.txt --replay-check`,
  then assert: 0 "Steve died" in the Minecraft log, 0 "handing teleport" other than level start,
  the final `trace:` position inside the exit elevator (take its origin from the lump:
  `*_elevator_body` tracktrains), and the drift the checker prints under a chosen bound. Portal's
  demo system is not useful here (it records the client's usercmds, and the mover is Minecraft); the
  replay tool is the right instrument and this is its first real use.
- Walk the game in order through the real elevators (`trigger_changelevel`), not `map X`, so the
  level-change path (`LevelInit`, `g_needSync`, `MapRegions`, the moving lift at the start) is tested
  18 times. Record with `rec.sh` so a hitch can be looked at.

Prove: every chamber's replay passes on a clean world (pair 1) before `stable-9`. Fix only what breaks,
and add the fix's chamber to the suite if it is scriptable (a_04 cart, a_11 turret room, escape_02 core).
Don't: hand-script each puzzle in `suite.sh`; the replays are the suite now.

### Step 6: release hygiene

`setup.ps1` copies `worlds/PortalCraft/data/` too; commit that folder. Update `PORTAL_MECHANICS.md:12-13`
(trigger_push is a shove stream; physexplosion never pushes players). Tag `stable-9`.

### Step 7 (conditional): cause-based classifier

Only if steps 3 and 5 show jitter at movers or props. Section 7.

### What I would deliberately not do

- Rewrite `PlayerCrossings` or move crossings back to Portal-only teleports (the pre-`4d8377f`
  design, which failed at speed, notes 10-02 21:39).
- Let Portal's player fly (option B in `PORTAL_MECHANICS.md`).
- Fill the hole with exit geometry (C4) without a failing chamber.
- Any rendering work (lasers, rim, blocks through portals, lighting) before the walkthrough passes.
- Co-op, RTX, the standalone-mod shader, elytra level flight.
- More timing windows in the plugin.

---

## 7. The one architectural change worth making

**What is wrong:** `serverProcessMovement` cannot tell *why* Portal's player ended up somewhere else,
so it guesses from how far. Every guess that was wrong became a rule. The rules now reference each
other (`impulse` feeds `draggedOnProp`, `bounced` depends on `lastMatchIn`, `g_riding` gates the
forced crossing), and a new mechanic (a sideways platform, a core, a `trigger_push`) lands in whichever
bucket its magnitude happens to fall in.

**Smallest change that fixes the model (two days, plugin only, no protocol change):**

1. **Default-ignore.** A delta under 24 units that nothing below explains is Portal's business and is
   overwritten next tick, as SkyCraft does. Hard teleports (>24 u) stay as they are.
2. **Name the causes Portal exposes, and hand over only those:**
   - *Portal crossing:* already cause-based (`portalCrossing` match, forced crossing).
   - *Mover under the player:* `m_hGroundEntity` is a `func_tracktrain`/`func_door`/`func_movelinear`
     (class is readable, `plugin.cpp:835`); subtract that entity's origin delta this tick
     (`collideableOrigin`, `:2221-2226`) from the observed delta. What is left is noise. This replaces
     `g_riding`'s z-only rule, `g_rideStill`, and `carriedLately`. Minecraft keeps `carry()` for the
     entity-stream case, or better, `HostState` carries the mover delta so Minecraft's copy does not
     lag two ticks.
   - *Base velocity (`trigger_push`):* `m_fFlags & FL_BASEVELOCITY` (`:744`) and `m_vecBaseVelocity`
     by name; send it as a velocity (a new `teleportKind`, or reuse `kMoveImpulse` with the base
     velocity, which is exactly what it was meant for). Steve then has the conveyor's momentum.
   - *Our own block boxes:* a delta that starts inside `g_blockBoxes` is ours (E5): ignore.
   - *Loose prop contact:* `clampToProps` already predicts it; anything else while `g_onLooseProp`
     or `g_gunEffect == 2`: ignore.
3. **Delete** `carriedLately`, `g_crossedAt`, the `bounced` rule (after step 4 of the plan), the
   three `draggedOnProp` conditions and the impulse magnitude test. Keep `zLift` as a named cause
   ("Portal's hull rests higher") until it, too, is measured away.

What this does not change: Minecraft owns position; Portal's player is overwritten every tick;
Portal teleports its own player and the plugin matches it. The architecture stays; only the
classifier becomes explicit.

---

## 8. Things I looked for and could not settle

| Question | Why it matters | How to settle it |
|---|---|---|
| Does `escape_02`'s camera take control before the upward push and zero gravity? | If not, Steve stays on the floor at the ending (Portal's base velocity would come through as a shove stream, 21 u/s = 0.3 u/tick, under the 0.5 floor: dropped) | Play it once (step 5); if it fails, the base-velocity cause in section 7 covers it |
| Does `func_breakable_surf` drop its collision in our copy when shattered? | `escape_01` progression | Shatter it; `LiveEntities` logs "entity #N ... moved"/gone; walk through |
| Is "took its player back" fully explained by the duck mismatch? | Whether `bounced` can be deleted | Step 4's count |
| Do the 8-unit pushes at the owner's a_10 builds come from Steve's block boxes? | E5 | `logSolidNear` already prints the pusher; the next occurrence tells |
| Real `changelevel` elevators in sequence | 18 transitions | Step 5 |
| Portal's `FindClosestPassableSpace` 1-damage-per-frame bleed | Slow unexplained damage | A log line in `bridgeHealth` when health drops by 1 repeatedly |
| Whether `trigger_hurt` type 0 death fields and DMG_BURN incinerators kill the same way as DMG_RADIATION water | a_04, a_09, a_13, a_14, a_15, escape_02 | Same refund code; verify once each in step 5 |

---

## Appendix A: the 10-02 audit's items, where they stand (H unless marked)

| Item | Status |
|---|---|
| P1 `mat_queue_mode` enforced | Done: plugin sets it (`plugin.cpp:3002-3004`) |
| P2 `Unload` unhooks | Done (`:2943-2951`, `hooks::unpatchAll`) |
| P3 fingerprint gate | Done as `kCheckedBuilds` (`:625-663`) for the slot-calling features |
| P4 first-SceneEnd / per-view draw | Done (notes 10-02 late: draw on pop of each view) |
| P8/P9 input model, text, wheel | Done (`:2640-2711`) |
| P10 viewmodel policy | Done (`camera::setViewModel`, `:2737`) |
| P11 `zLift` | Still present (R6) |
| P12 entity cap 128, nearest-first | Still as it was (`:2311-2331`); escape maps have the most props (a_15: 88 `prop_physics`), worth watching the perf line's entity count there (L) |
| P13 nearest active portal per colour | Still as it was (`:2189-2194`); a_00 has 8 `prop_portal`s and works |
| T2 receive loop dies on IOException | Not re-checked |
| T5 cache cleared on every portal shot | Still (C5) |
| J4 shared coordinate space | Done (`MapRegions`) |
| J5 displacements | Not needed: all 18 campaign maps have 0 `dispinfo` bytes (lump 26, H) |
| Dev packets gated | Done (`-portalcraftdev`, `:460-467`) |
| LICENSE | Done (MIT) |
| M7 packaging | Done (`tools/package.ps1`, Prism instance); seed-world bug remains (2.10) |
| M0 "blocks placed, world-locked, depth-tested" | Done |
| M1 input, M2 per-view, M3 entities/mobs/particles, M4 blocks as physics + health, M5 regions, M7 | Done in substance; M2 lighting partial (dlights from blocks), M6 co-op and M9 RTX not started |
