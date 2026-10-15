"""The voxel gun's crackle while it holds something: three jagged arcs of glowing voxels from its prong
tips to its barrel, in a few shapes the gun flickers through (Portal draws the same between its own
gun's prongs, as beams; ours are voxels, like the rest of the gun).

Writes, into a voxel gun resource pack folder (packs/portalcraft-voxel-gun, or a copy of it):
  models/item/portal_gun_arcs/arc_<n>.json   one shape of the arcs each
  textures/item/portal_gun_arc.png           their colour
and puts them in the holding pose of items/portal_gun.json (fire_frame 112: pickup's last frame,
held while the gun holds an object), chosen among by portalcraft:arc_frame (gun/ArcFrame.java).

    uv run --no-project --with pillow python tools/make_gun_arcs.py <pack folder> [<pack folder> ...]
"""
import json
import math
import random
import sys
from pathlib import Path

from PIL import Image

FRAMES = 6
HOLD_FRAME = 112  # GunAnimation: PICKUP base 100 + its last frame (11) + 1
# In the holding pose (portal_gun_pickup/bend_12), model pixels: the three prongs' tips, and the
# point in front of the barrel between them where the three arcs meet (Portal's own beams meet at its "muzzle").
TIPS = [(2.0, 1.8, -9.3), (13.8, 2.4, -10.4), (10.4, 11.4, -9.2)]
MOUTH = (8.6, 5.4, -7.2)
STEP = 0.32  # one voxel of an arc every this far along it
SIZE = 0.46  # and this big
JITTER = 1.5  # how far an arc wanders off the straight line, at most, in the middle


def arc(rng: random.Random, a, b):
    """Points along a jagged line from a to b: a few kinks, each pushed off the line at random."""
    ax, ay, az = a
    bx, by, bz = b
    d = (bx - ax, by - ay, bz - az)
    length = math.sqrt(sum(c * c for c in d))
    # two directions across the line
    u = (d[1], -d[0], 0.0) if abs(d[2]) < 0.9 * length else (0.0, d[2], -d[1])
    ul = math.sqrt(sum(c * c for c in u))
    u = tuple(c / ul for c in u)
    w = (d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0])
    wl = math.sqrt(sum(c * c for c in w))
    w = tuple(c / wl for c in w)
    kinks = [0.0] + sorted(rng.uniform(0.1, 0.9) for _ in range(5)) + [1.0]
    knots = []
    for t in kinks:
        reach = JITTER * math.sin(math.pi * t)  # pinned at both ends
        p, q = rng.uniform(-reach, reach), rng.uniform(-reach, reach)
        knots.append(tuple(a[i] + d[i] * t + u[i] * p + w[i] * q for i in range(3)))
    points = []
    for k0, k1 in zip(knots, knots[1:]):
        seg = math.sqrt(sum((k1[i] - k0[i]) ** 2 for i in range(3)))
        n = max(1, int(seg / STEP))
        points += [tuple(k0[i] + (k1[i] - k0[i]) * s / n for i in range(3)) for s in range(n)]
    return points + [b]


def element(p):
    half = SIZE / 2
    faces = {f: {"texture": "#arc", "uv": [0, 0, 16, 16]} for f in ("north", "south", "east", "west", "up", "down")}
    return {"from": [round(p[i] - half, 3) for i in range(3)], "to": [round(p[i] + half, 3) for i in range(3)], "shade": False, "light_emission": 15, "faces": faces}


def write(pack: Path) -> None:
    assets = pack / "assets" / "portalcraft"
    models, textures = assets / "models" / "item", assets / "textures" / "item"
    body = json.loads((models / "portal_gun_fire" / "body.json").read_text(encoding="utf-8"))
    (models / "portal_gun_arcs").mkdir(parents=True, exist_ok=True)
    rng = random.Random(4)
    for n in range(FRAMES):
        elements = []
        for tip in TIPS:
            if rng.random() < 0.2 and n % 2:  # now and then an arc is out for a frame
                continue
            elements += [element(p) for p in arc(rng, tip, MOUTH)]
        model = {"textures": {"arc": "portalcraft:item/portal_gun_arc", "particle": "portalcraft:item/portal_gun_arc"}, "elements": elements,
                 "display": body["display"]}
        (models / "portal_gun_arcs" / f"arc_{n}.json").write_text(json.dumps(model, separators=(",", ":")), encoding="utf-8")
    # the colour: pale yellow, whiter in the middle (one 16x16 picture, the same on every face)
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = 1.0 - min(1.0, math.hypot(x - 7.5, y - 7.5) / 10.5)
            img.putpixel((x, y), (255, int(232 + 23 * c), int(120 + 125 * c), 255))
    textures.mkdir(parents=True, exist_ok=True)
    img.save(textures / "portal_gun_arc.png")
    # into the holding pose
    items = assets / "items" / "portal_gun.json"
    j = json.loads(items.read_text(encoding="utf-8"))
    for entry in j["model"]["fallback"]["entries"]:
        if entry["threshold"] == HOLD_FRAME:
            parts = [m for m in entry["model"]["models"] if m.get("property") != "portalcraft:arc_frame"]
            parts.append({"type": "minecraft:range_dispatch", "property": "portalcraft:arc_frame",
                          "entries": [{"threshold": n, "model": {"type": "minecraft:model", "model": f"portalcraft:item/portal_gun_arcs/arc_{n}"}} for n in range(FRAMES)]})
            entry["model"]["models"] = parts
            break
    else:
        raise SystemExit(f"{items}: no holding frame ({HOLD_FRAME})")
    items.write_text(json.dumps(j, indent=2), encoding="utf-8")
    print("arcs written into", pack)


if __name__ == "__main__":
    for folder in sys.argv[1:]:
        write(Path(folder))
