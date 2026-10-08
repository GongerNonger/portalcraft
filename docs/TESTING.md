# Testing PortalCraft without a player

Scripted tests drive a running Portal + Minecraft pair through the plugin's dev link (`host/portal/tools/fake_mc.py`:
move Steve, set the view, hold keys, run Portal or Minecraft commands) and read the two logs. They are the copies in
`tools/devtests/`; the working set lives in `run/devtests/` (git-ignored, with its output folders), and the paths in
them are this machine's.

## The pair

- `restart.sh [save|map] [mapname]` starts a pair; `PCI=1` selects a second, independent pair
  (`hl2.exe -multirun -pcinstance 1`, its Minecraft running from a second checkout) so tests don't touch the
  player's own world. Test launches use `-nomouse` (Portal otherwise takes the real mouse) and `-condebug`.
- `reload1.sh [map]` closes pair 1, installs the main checkout's plugin build, and starts it again. The plugin file
  is shared: it can only be replaced while no Portal is running.
- One pair at a time unless there is 10 GB of commit memory free: two Minecrafts ran this PC out of memory.

## The tests

| Script | What it proves |
|---|---|
| `suite.sh` | 16 checks: level start, three portal crossings, two 30 s infinite falls, the floor button four ways, a cube pushed, stood on and carried |
| `sweep.sh` | every level loads with Steve alive, on his feet, the two sides together |
| `lifttest.sh` / `carttest.sh` | riding a lift (a_03) and a sideways cart (a_07): his place on it is steady, jumps land where they left |
| `elevtest.sh <map> <x,y,z>` | standing in an exit lift takes him to the next level |
| `cubeportal.sh fwd\|back`, `cubefloor.sh floorwall\|floorceiling` | a carried cube comes through a portal with him |
| `wallpress.sh` | a carried cube leant on a wall doesn't shake him |
| `turrettest.sh` | a turret is knocked over by walking into it |

The last four run in `pc_test`, a chamber of our own.

## The test chamber

`tools/testmap/make_testmap.py` writes a VMF and `build_testmap.sh` compiles it with Portal's own `vbsp`, `vvis`
and `vrad` and installs it as `maps/pc_test.bsp`: a concrete room, every surface taking portals, with two cubes,
one of GLaDOS's cores, a glass pane, an air current and a turret in a walled alley, all at coordinates the tests
know. When adding an entity, copy its key values from one of Valve's maps (`tools/map_census.py <maps> <prefix>
--detail <class>`): a core without spawnflag 256 can't be picked up.

## Reading the maps and the game's own log

- `tools/map_census.py <maps dir> [prefix]` lists every entity class in the maps, with what each is parented to:
  what Hammer's entity report shows, for all maps at once. Do this before writing a rule by class name; the lifts
  from testchmb_a_08 on are `prop_portal_stats_display`, not `prop_dynamic`.
- In a running game, `fake_mc.py --cmd="developer 2"` and `--cmd="con_logfile pcconsole.log"` make Portal write
  every map output and input as it fires to `portal/pcconsole.log`. That is how to find out why a lift didn't
  leave or a door didn't open (testchmb_a_02's exit lift waits for GLaDOS's last line, which a trigger at the end
  of the chamber starts).

## Traps

- Water (or blocks) left in the Minecraft world stays there across map loads and pushes Steve about in later tests.
- After `map X`, wait for the plugin's `[level start]` line, not for `driving 1`: the old level is still driving.
- Some plugin log lines are capped (matched crossings 60, pushes 300, hurts 5): no line is not no event.
- A teleport (`--goto`) off the top of a physics prop is undone by Portal's physics; walk off it.
