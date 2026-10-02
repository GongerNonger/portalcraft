# Portal's puzzle mechanics under PortalCraft

Steve moves with Minecraft's physics; Portal renders, runs its portals, triggers, props and
enemies around him. This is where that holds up, and where it doesn't.

## What Portal 1 uses (all 26 maps, from their entity lumps)

| Mechanic | Count | How it works with PortalCraft |
|---|---|---|
| Portals (walk, run, fall through) | everywhere | Portal teleports the player natively and carries its velocity through; Minecraft takes the new place and velocity (a hard handoff). |
| Momentum flings | core of chambers ~09-19 | Portal air (below): Portal's gravity, no air drag, its speed cap, from the portal until landing. |
| `trigger_push` air currents | 37 in 8 maps | Velocity impulses are handed to Minecraft (`kMoveImpulse`). Before 10-03 they were lost: the plugin overwrites the velocity every tick. |
| `env_physexplosion` | 9 | Same impulse path. |
| Doors, lifts, moving platforms (`func_door`, `func_tracktrain`) | ~400 | Live entity collision, ridden by `carry`, shoves are soft handoffs. |
| Fizzlers (`trigger_portal_cleanser`) | 40 | Native: they act on the player's position and clear portals; Minecraft's portal holes follow. |
| Goo, turrets, energy balls (`trigger_hurt`, turrets, launchers) | ~110 | Portal damages its player at Steve's place; the plugin refunds it and Steve takes it (x0.2: a full Chell is a full Steve), armor and all. A one-hit kill (goo, an energy ball) kills Steve. Steve dying (falls, lava, TNT, turrets) kills Portal's player, so Portal's own death and checkpoint reload follow. |
| Cubes, buttons | many | Native (E grabs; floor buttons are triggers / physics). |
| Props on Steve's blocks | - | Steve's blocks (32 blocks around him) are static physics boxes in Portal: cubes rest on them, turrets stand on them, energy balls bounce off. Turret bullets are traces and still go through. |
| Floor-portal funneling | everywhere | Portal steers a falling player into a floor portal; PortalAir.funnel does it for Steve (within 1.5 blocks, 10 below). |
| Minecraft explosions on props | - | TNT, creepers, beds become a Portal blast (`RadiusDamage`, DMG_BLAST, radius 2x power): cubes fly, turrets tip. The player is left out (Minecraft hurts Steve itself). |
| Arrows, snowballs, punches on props | - | A projectile or a bare-hand punch that lands on a Portal entity hits it (`AddMultiDamage`, DMG_CLUB, force along the hit): cubes get shoved, turrets knocked over. |
| Bounce pads, faith plates, gels | 0 | Portal 2 only. |

Fall damage is ON: Steve has no long-fall boots. Every test chamber and escape level hands him a
water bucket at load (if he has none) to clutch falls with; placing a block under himself or an
ender pearl works too. Going through a portal resets the fall distance, so only the drop after a
portal counts.

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

## Portal air (built: option A below)

From the moment Steve comes out of a portal with speed (or is thrown by an air current or a blast)
until he lands, touches water or a ladder, and on any fall longer than 3 blocks, Steve flies by
Portal's rules (`PortalAir`, `LivingEntityAirMixin`): gravity 600 u/s² (Minecraft's is 1280), no
air drag, speed capped at 3500 u/s, and air control can steer but not add speed. Jumps, sprint-jumps
and short drops are vanilla. Fall damage is still Minecraft's, from the fall distance: long falls
still need the water bucket.

## Minecraft hitting Portal's props

Explosions (`ServerExplosionMixin`), projectiles landing on host geometry (`ProjectileHitMixin`) and
bare-hand punches on host geometry are sent to the plugin (`HostEvents`: PCB1 blasts, PCI1 hits).
A hit only goes through if a live host entity's collision is at the point. The plugin applies them
with the server's own damage calls (IServerTools slots 28-31, checked in server.dll), so Portal's
physics does the rest. Test without Minecraft: `fake_mc.py --blast=x,y,z,320,120` near a cube.

## Options (the scoping, kept for reference)

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

**Raising the terminal velocity doesn't help**: Minecraft's is already 3136 u/s (Portal caps at
3500) and puzzle falls (256-1024 u) never get near either. The cheapest real lever is **keeping
Steve's horizontal speed after a portal until he lands** (no air drag in that flight, Minecraft's
gravity kept): 90% / 88% / 84% of Portal's distance for 256 / 512 / 1024-unit drops, a small
mixin, jumps and falls otherwise untouched.
