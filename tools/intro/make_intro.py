"""Chamber 00 opening with Steve dropping out of a green portal in the vault's ceiling, in place of
Portal's waking up in the bed.

    uv run --no-project --with vpk --with numpy --with srctools python tools/intro/make_intro.py <Portal folder> on|off

What "on" writes, all of it made on this PC from this PC's Portal, none of it over a file of Valve's:

    portal/custom/pc-intro/                   the green portal: a flat model, its turning-spiral material,
                                              and the globs round its rim (a particle system of its own)
    portal/maps/testchmb_a_00_l_0.lmp         the chamber's entities with the opening changed (a "lump
                                              file": the engine reads it in place of the map's own list)
    portal/maps/testchmb_a_00_particles.txt   tells the engine to load the globs for this map

"off" takes both away again, and the chamber opens as Valve made it.

The opening, in the map's own terms (see the entities it adds, below): the player starts in the
unlit room Valve keeps off to the side of the map, so the vault is empty; a camera lying on the
vault's floor looks up at the ceiling; the green portal (a prop: nothing is behind it) appears
there with its globs; the player is put just under it and falls to the floor; the portal goes; the
camera lets go. The portal is the only green one: Portal's own stay blue and orange.
"""
import io
import math
import re
import shutil
import struct
import subprocess
import uuid
import sys
from pathlib import Path

import vpk

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "green-portals"))
import particles  # noqa: E402
import swirl  # noqa: E402

MAP = "testchmb_a_00"
# ---- the scene, in the map's units -----------------------------------------------------------
HIDDEN = (-3930, 1184, 4)  # Valve's unlit side room (its two start-up portals are in it)
PORTAL = (-576, -338, 343)  # on the vault's ceiling
DROP = (-576, -338, 252)  # the player's feet, his head just under the portal
CAMERA = (-626, -398, 166)  # on the vault's floor, in a corner
AIM = (-574, -338, 276)  # what it looks at: between the portal and where he lands
APPEAR, FALL, GONE, RELEASE = 2.2, 3.4, 5.6, 6.6  # seconds after the map starts

SWIRL_VMT = """"UnlitGeneric"
{
	// PortalCraft: the green portal of the opening. Two layers of a spiral, turned against each other.
	"$basetexture" "models/portalcraft/pc_swirl"
	"$model" "1"
	"$translucent" "1"
	"$nocull" "1"
	"$detail" "models/portalcraft/pc_swirl_detail"
	"$detailscale" "1"
	"$detailblendmode" "0"
	"$detailblendfactor" "1"
	"$center" "[0.5 0.5]"
	"$turn" "0"
	"$turnback" "0"
	"Proxies"
	{
		"LinearRamp"
		{
			"rate" "-70"
			"initialValue" "0"
			"resultVar" "$turn"
		}
		"TextureTransform"
		{
			"centerVar" "$center"
			"rotateVar" "$turn"
			"resultVar" "$basetexturetransform"
		}
		"LinearRamp"
		{
			"rate" "110"
			"initialValue" "0"
			"resultVar" "$turnback"
		}
		"TextureTransform"
		{
			"centerVar" "$center"
			"rotateVar" "$turnback"
			"resultVar" "$detailtexturetransform"
		}
	}
}
"""
GLOB_VMT = particles.GLOB_VMT.replace("effects/pc_glob", "effects/pc_intro_glob")
CLAMPED = 0x4 | 0x8 | 0x100 | 0x200 | 0x2000

# The portal's model: one rectangle, 80 by 128 units, standing up (studiomdl turns what it reads a
# quarter round the up axis: it comes out in the model's x-z plane), drawn from both sides by the
# material. Laid against a ceiling by rolling its entity a quarter turn. (Modelled lying flat, it was
# not drawn at all.)
HALF_W, HALF_H = 40, 64
QC = """$modelname "portalcraft/pc_portal.mdl"
$cdmaterials "models/portalcraft"
$surfaceprop "default"
$body body "pc_portal_ref.smd"
$sequence idle "pc_portal_ref.smd" fps 1
"""


def smd() -> str:
    corners = [(-HALF_W, -HALF_H, 0.0, 1.0), (HALF_W, -HALF_H, 1.0, 1.0), (HALF_W, HALF_H, 1.0, 0.0), (-HALF_W, HALF_H, 0.0, 0.0)]  # y, z, u, v
    lines = ["version 1", "nodes", '0 "root" -1', "end", "skeleton", "time 0", "0 0 0 0 0 0 0", "end", "triangles"]
    for a, b, c in ((0, 1, 2), (0, 2, 3)):
        lines.append("pc_swirl")
        for i in (a, b, c):
            y, z, u, v = corners[i]
            lines.append(f"0 0 {y} {z} 1 0 0 {u} {v}")
    return "\n".join(lines + ["end", ""])


def model(portal: Path, out: Path, work: Path) -> None:
    work.mkdir(parents=True, exist_ok=True)
    (work / "pc_portal.qc").write_text(QC)
    (work / "pc_portal_ref.smd").write_text(smd())
    built = portal / "portal" / "models" / "portalcraft"
    run = subprocess.run([str(portal / "bin" / "studiomdl.exe"), "-game", str(portal / "portal"), "-nop4", str(work / "pc_portal.qc")], capture_output=True, text=True)
    if not (built / "pc_portal.mdl").exists():
        raise SystemExit("studiomdl did not write the model:\n" + run.stdout[-1500:] + run.stderr[-500:])
    target = out / "models" / "portalcraft"
    target.mkdir(parents=True, exist_ok=True)
    for f in built.glob("pc_portal.*"):
        shutil.move(str(f), str(target / f.name))
    if not any(built.iterdir()):
        built.rmdir()  # (studiomdl made it: nothing of anyone's was in it)


def rim(pak) -> bytes:
    """Portal's own portal particles with one system more: the globs (its blue rim sparks, changed as
    for the green portals, under another name). In Portal's file because that one is surely loaded:
    a file of our own, added to the game's list of particle files, was not."""
    from srctools.dmx import Element, ValueType

    data = pak["particles/portals.pcf"].read()
    root, fmt, ver = Element.parse(io.BytesIO(data))
    copy = Element.parse(io.BytesIO(data))[0]  # (parsed a second time: the system to change, Portal's own left as they are)
    systems = {s.name: s for s in copy["particlesystemdefinitions"].iter_elem()}
    edge = systems["portal_1_edge"]
    sprites = next(r for s in systems.values() for r in s["renderers"].iter_elem() if r.name == "render_animated_sprites")
    for el in particles._elements(edge):
        for key, attr in list(el.items()):
            if attr.type == ValueType.COLOR and not attr.is_array:
                el[key] = particles._green(attr.val_color)
    particles._globs(edge, sprites)
    for init in edge["initializers"].iter_elem():
        if init.name == "Position Within Sphere Random":  # the ring they start on, out to this portal's rim (it is bigger than Portal's)
            init["distance_min"] = 60.0
            init["distance_max"] = 70.0
    for el in particles._elements(edge):
        el.uuid = uuid.uuid4()  # (elements are written out by id: with the ids of Portal's own, Portal's own were written in their place)
    edge.name = "pc_intro_rim"
    edge["material"] = "effects\\pc_intro_glob.vmt"
    if len(edge["children"]):
        raise SystemExit("the rim's particle system has grown children: not handled")
    root["particlesystemdefinitions"].append(edge)
    out = io.BytesIO()
    root.export_binary(out, version=2, fmt_name=fmt, fmt_ver=ver)
    return out.getvalue()


# ---- the chamber's entities ---------------------------------------------------------------------

def vec(v) -> str:
    return " ".join(f"{c:g}" for c in v)


def look(src, dst) -> str:
    """Entity angles (pitch yaw roll) looking from src at dst."""
    dx, dy, dz = (d - s for s, d in zip(src, dst))
    return f"{-math.degrees(math.atan2(dz, math.hypot(dx, dy))):.1f} {math.degrees(math.atan2(dy, dx)) % 360:.1f} 0"


def entity(**pairs) -> str:
    outputs = pairs.pop("outputs", [])
    lines = [f'"{k}" "{v}"' for k, v in pairs.items()] + [f'"{name}" "{value}"' for name, value in outputs]
    return "{\n" + "\n".join(lines) + "\n}\n"


def entities(text: str) -> str:
    blocks = re.findall(r"\{[^{}]*\}\n?", text)
    if "".join(blocks).strip() != text.strip("\0\n ").strip():
        raise SystemExit("the map's entity list isn't laid out as expected")

    def has(block, key, value):
        return re.search(rf'"{key}"\s+"{re.escape(value)}"', block) is not None

    def drop(block, pattern):
        new, n = re.subn(rf'\n"[^"]+" "{pattern}"', "", block)
        if n == 0:
            raise SystemExit(f"the map has changed: nothing matches {pattern}")
        return new

    out = []
    for b in blocks:
        if has(b, "classname", "info_player_start"):
            b = re.sub(r'"origin" "[^"]*"', f'"origin" "{vec(HIDDEN)}"', b)
        elif has(b, "classname", "logic_auto") and "blackout_viewcontroller,Enable" in b:
            b = drop(b, r"blackout_viewcontroller,[^\"]*")  # the waking-up camera stays off
        elif has(b, "targetname", "blackout") and has(b, "classname", "prop_dynamic"):
            b = drop(b, r"trigger_knockout_teleport,Enable[^\"]*")  # (it put the player by the bed: he is not in it)
        elif has(b, "targetname", "fade_intro"):
            b = re.sub(r'"holdtime" "[^"]*"', '"holdtime" "0.4"', b)
            b = re.sub(r'"duration" "[^"]*"', '"duration" "1.2"', b)
        out.append(b)
    out.append(entity(classname="point_viewcontrol", targetname="pc_intro_camera", origin=vec(CAMERA), angles=look(CAMERA, AIM), spawnflags=12, wait=10,  # (12: holds the view till told to let go, and the player's controls with it)
        acceleration=500, deceleration=500, speed=0))
    out.append(entity(classname="prop_dynamic", targetname="pc_intro_portal", model="models/portalcraft/pc_portal.mdl", origin=vec(PORTAL), angles="0 0 90",
        StartDisabled=1, solid=0, disableshadows=1, rendercolor="255 255 255", renderamt=255, fademindist=-1, fadescale=1))
    out.append(entity(classname="info_particle_system", targetname="pc_intro_rim", effect_name="pc_intro_rim", origin=vec(PORTAL), angles="0 0 0", start_active=0))
    out.append(entity(classname="point_teleport", targetname="pc_intro_drop", target="!player", origin=vec(DROP), angles="0 200 0"))
    out.append(entity(classname="logic_auto", spawnflags=1, origin=vec(CAMERA), outputs=[
        ("OnMapSpawn", "pc_intro_camera,Enable,,0,-1"),
        ("OnMapSpawn", "blackout,Kill,,0,-1"),
        ("OnMapSpawn", f"pc_intro_portal,Enable,,{APPEAR},-1"),
        ("OnMapSpawn", f"pc_intro_rim,Start,,{APPEAR},-1"),
        ("OnMapSpawn", f"pc_intro_drop,Teleport,,{FALL},-1"),
        ("OnMapSpawn", f"pc_intro_rim,Stop,,{GONE - 0.4:g},-1"),
        ("OnMapSpawn", f"pc_intro_portal,Disable,,{GONE},-1"),
        ("OnMapSpawn", f"pc_intro_camera,Disable,,{RELEASE},-1"),
        ("OnMapSpawn", "bed_cover,SetAnimation,closing,9,-1"),
    ]))
    return "".join(out)


def lump(bsp: bytes) -> bytes:
    ofs, length = struct.unpack_from("<ii", bsp, 8)
    revision = struct.unpack_from("<i", bsp, 8 + 64 * 16)[0]
    text = entities(bsp[ofs:ofs + length].decode("latin1").rstrip("\0")).encode("latin1") + b"\0"
    return struct.pack("<iiiii", 20, 0, 0, len(text), revision) + text


def main() -> None:
    portal, mode = Path(sys.argv[1]), sys.argv[2]
    game = portal / "portal"
    pack, lmp, manifest = game / "custom" / "pc-intro", game / "maps" / f"{MAP}_l_0.lmp", game / "maps" / f"{MAP}_particles.txt"
    if (pack / "materials" / "models" / "portalcraft").exists():
        shutil.rmtree(pack)
    for f in (lmp, manifest):
        if f.exists():
            f.unlink()
    if mode != "on":
        print("The opening is Valve's own again.")
        return
    pak = vpk.open(str(game / "portal_pak_dir.vpk"))
    mats = pack / "materials" / "models" / "portalcraft"
    mats.mkdir(parents=True)
    (mats / "pc_swirl.vtf").write_bytes(swirl.vtf(swirl.body(), CLAMPED))
    (mats / "pc_swirl_detail.vtf").write_bytes(swirl.vtf(swirl.detail(), CLAMPED))
    (mats / "pc_swirl.vmt").write_text(SWIRL_VMT, newline="")
    effects = pack / "materials" / "effects"
    effects.mkdir(parents=True)
    (effects / "pc_intro_glob.vtf").write_bytes(swirl.vtf(particles.glob(), CLAMPED))
    (effects / "pc_intro_glob.vmt").write_text(GLOB_VMT, newline="")
    (pack / "particles").mkdir()
    (pack / "particles" / "portals.pcf").write_bytes(rim(pak))
    model(portal, pack, HERE / "build")
    lmp.write_bytes(lump((game / "maps" / f"{MAP}.bsp").read_bytes()))
    print("The opening is on: Steve drops out of a green portal in chamber 00. Restart Portal.")


if __name__ == "__main__":
    main()
