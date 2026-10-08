"""Writes pc_test.vmf: PortalCraft's own test chamber, so scripted tests know where everything is.

A 1024 x 1024 x 320 concrete room (every surface takes portals), floor at z 0, walls at x and y = +-512.
  player start (0 0 1), a dual portal gun on the spot
  cubes at (160 0 20) and (160 96 20)
  a glass pane at x -256, y -448..-192; one of GLaDOS's cores at (160 -96 24)
  an air current (trigger_push, 300 units/s towards -x) over x 256..448, y 288..448, z 0..96
Compile and install with build_testmap.sh; load with `map pc_test`.
"""
import itertools

ids = itertools.count(1)
WALL = "CONCRETE/CONCRETE_MODULAR_WALL001A"
FLOOR = "CONCRETE/CONCRETE_MODULAR_FLOOR001A"
CEIL = "CONCRETE/CONCRETE_MODULAR_CEILING001A"


def box(x1, y1, z1, x2, y2, z2, material):
    faces = [
        ((x1, y2, z2), (x2, y2, z2), (x2, y1, z2), "[1 0 0 0]", "[0 -1 0 0]"),
        ((x1, y1, z1), (x2, y1, z1), (x2, y2, z1), "[1 0 0 0]", "[0 -1 0 0]"),
        ((x1, y2, z2), (x1, y1, z2), (x1, y1, z1), "[0 1 0 0]", "[0 0 -1 0]"),
        ((x2, y2, z1), (x2, y1, z1), (x2, y1, z2), "[0 1 0 0]", "[0 0 -1 0]"),
        ((x2, y2, z2), (x1, y2, z2), (x1, y2, z1), "[1 0 0 0]", "[0 0 -1 0]"),
        ((x2, y1, z1), (x1, y1, z1), (x1, y1, z2), "[1 0 0 0]", "[0 0 -1 0]"),
    ]
    out = ["\tsolid\n\t{\n\t\t\"id\" \"%d\"\n" % next(ids)]
    for a, b, c, u, v in faces:
        plane = " ".join("(%d %d %d)" % p for p in (a, b, c))
        out.append(
            "\t\tside\n\t\t{\n\t\t\t\"id\" \"%d\"\n\t\t\t\"plane\" \"%s\"\n\t\t\t\"material\" \"%s\"\n"
            "\t\t\t\"uaxis\" \"%s 0.25\"\n\t\t\t\"vaxis\" \"%s 0.25\"\n\t\t\t\"rotation\" \"0\"\n"
            "\t\t\t\"lightmapscale\" \"16\"\n\t\t\t\"smoothing_groups\" \"0\"\n\t\t}\n" % (next(ids), plane, material, u, v)
        )
    out.append("\t}\n")
    return "".join(out)


def entity(keys, solid=""):
    body = "".join('\t"%s" "%s"\n' % kv for kv in keys.items())
    return 'entity\n{\n\t"id" "%d"\n%s%s}\n' % (next(ids), body, solid)


S, H, T = 512, 320, 16
world = "".join(
    [
        box(-S - T, -S - T, -T, S + T, S + T, 0, FLOOR),
        box(-S - T, -S - T, H, S + T, S + T, H + T, CEIL),
        box(-S - T, -S - T, 0, -S, S + T, H, WALL),
        box(S, -S - T, 0, S + T, S + T, H, WALL),
        box(-S, S, 0, S, S + T, H, WALL),
        box(-S, -S - T, 0, S, -S, H, WALL),
    ]
)
vmf = 'versioninfo\n{\n\t"editorversion" "400"\n\t"mapversion" "1"\n\t"formatversion" "100"\n}\n'
vmf += 'world\n{\n\t"id" "%d"\n\t"mapversion" "1"\n\t"classname" "worldspawn"\n\t"skyname" "sky_day01_01"\n%s}\n' % (next(ids), world)
vmf += entity({"classname": "info_player_start", "origin": "0 0 1", "angles": "0 0 0"})
vmf += entity({"classname": "weapon_portalgun", "origin": "0 0 24", "angles": "0 0 0", "CanFirePortal1": "1", "CanFirePortal2": "1"})
for at in ("160 0 20", "160 96 20"):
    vmf += entity({"classname": "prop_physics", "origin": at, "angles": "0 0 0", "model": "models/props/metal_box.mdl", "spawnflags": "256"})
for at in ("-256 -256 288", "256 -256 288", "-256 256 288", "256 256 288"):
    vmf += entity({"classname": "light", "origin": at, "_light": "255 255 255 300", "_quadratic_attn": "1"})
vmf += entity(
    {"classname": "trigger_push", "origin": "352 368 48", "pushdir": "0 180 0", "speed": "300", "spawnflags": "1", "StartDisabled": "0"},
    box(256, 288, 0, 448, 448, 96, "TOOLS/TOOLSTRIGGER"),
)
# A pane of the first chamber's glass (x = -256, y -448..-192): Minecraft water put behind it must show behind it.
vmf += entity({"classname": "func_brush", "origin": "-256 -320 64", "Solidity": "2", "spawnflags": "2"}, box(-258, -448, 0, -254, -192, 128, "GLASS/GLASSWINDOW_REFRACT01"))
# One of GLaDOS's cores, to carry: they only exist in the last fight otherwise.
vmf += entity({"classname": "prop_glados_core", "origin": "160 -96 24", "angles": "0 0 0", "CoreType": "1", "DelayBetweenLines": "0.4", "spawnflags": "256", "model": "models/props_bts/glados_ball_reference.mdl", "physdamagescale": "0.1"})
open("pc_test.vmf", "w", newline="\n").write(vmf)
print("wrote pc_test.vmf")
