# Portal's puzzle mechanics under PortalCraft

Steve moves with Minecraft's physics; Portal renders, runs its portals, triggers, props and
enemies around him. This is where that holds up, and where it doesn't.

## What Portal 1 uses (all 26 maps, from their entity lumps)

| Mechanic | Count | How it works with PortalCraft |
|---|---|---|
| Portals (walk, run, fall through) | everywhere | Portal teleports the player natively and carries its velocity through; Minecraft takes the new place and velocity (a hard handoff). |
| Momentum flings | core of chambers ~09-19 | **Short by ~37%**: see below. |
| `trigger_push` air currents | 37 in 8 maps | Velocity impulses are handed to Minecraft (`kMoveImpulse`). Before 10-03 they were lost: the plugin overwrites the velocity every tick. |
| `env_physexplosion` | 9 | Same impulse path. |
| Doors, lifts, moving platforms (`func_door`, `func_tracktrain`) | ~400 | Live entity collision, ridden by `carry`, shoves are soft handoffs. |
| Fizzlers (`trigger_portal_cleanser`) | 40 | Native: they act on the player's position and clear portals; Minecraft's portal holes follow. |
| Goo, turrets, energy balls (`trigger_hurt`, turrets, launchers) | ~110 | Native: Portal damages its own player at our position. |
| Cubes, buttons | many | Native (E grabs; floor buttons are triggers / physics). |
| Bounce pads, faith plates, gels | 0 | Portal 2 only. |

Fall damage is off in Minecraft (Portal's long-fall boots).

## Flings: why they come up short

Minecraft's player falls under 0.08 blocks/tick² (1280 units/s², twice Portal's `sv_gravity` 600)
with 2% vertical drag, and loses 9% of its horizontal speed every tick in the air. Portal has no
air drag at all. Simulated (drop into a floor portal, exit a wall portal, 128-unit drop after):

| Drop into the portal | Exit speed, Portal | Exit speed, Minecraft | Fling distance, Portal | Fling distance, Minecraft |
|---|---|---|---|---|
| 256 u | 555 u/s | 724 u/s | 361 u | 230 u |
| 512 u | 782 u/s | 1000 u/s | 509 u | 318 u |
| 1024 u | 1109 u/s | 1355 u/s | 723 u | 431 u |

Puzzles are built for Portal's numbers, so flings fall short of their ledges.

## Options (not built: they change how Steve moves)

**A. Portal air physics in Minecraft ("fling mode").** A mixin on the player's air movement: while
Steve is airborne after a portal (and optionally whenever he has fallen more than ~2 blocks), use
Portal's numbers: gravity 600 u/s², no air drag, speed cap 3500 u/s; back to Minecraft's on
landing or in water. Keeps Minecraft collision (flings hit your own blocks), jumps and sprint-jumps
stay Minecraft's. About a day with tuning. The question to decide: only after portals (entry speed
then still Minecraft's, ~25% faster: overshoots), or every long fall too (exact, but long falls
feel floatier than vanilla).

**B. Portal flies the player.** While airborne after a portal, stop overriding: Portal's own
movement flies the player exactly as in Portal, and Minecraft follows it each tick; Minecraft takes
over again on landing. No Minecraft movement change, exact Portal flings, about half a day. But
Minecraft's own blocks don't stop Steve mid-fling (Portal doesn't know them), and the hand-back on
landing needs care.

Recommendation: **A, applied to long falls and post-portal flight**, for exact puzzle numbers with
Minecraft collision; B is the quick fallback.
