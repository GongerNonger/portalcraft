"""The voxel gun's crackle while it holds something: three jagged arcs from its prong tips to the
point between them in front of its barrel, in a few shapes the gun flickers through (Portal draws the
same between its own gun's prongs, as beams; ours are voxels, like the rest of the gun).

Each arc is a thin core of micro-voxels, white-hot, inside a see-through glow of bigger ones that
fades along it, with a fork or two splitting off: depth, rather than one flat bolt. The first draft
(one size of voxel, one colour) is tag gun-arcs-v1.

Writes, into a voxel gun resource pack folder (packs/portalcraft-voxel-gun, or a copy of it):
  models/item/portal_gun_arcs/arc_<n>.json   one shape of the arcs each
  textures/item/portal_gun_arc.png           the core's colour
  textures/item/portal_gun_arc_glow*.png     the glow's, three strengths (see-through)
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
JITTER = 1.5  # how far an arc wanders off the straight line, at most, in the middle
CORE_STEP, CORE_SIZE = 0.13, 0.17  # the core: a micro-voxel every this far, this big
GLOW_STEP, GLOW_SIZE = 0.42, 0.62  # the glow round it
FORK_SIZE = 0.13
GLOWS = 3  # strengths of the glow, strongest first: where along an arc a glow voxel is picks one


def arc(rng: random.Random, a, b, jitter=JITTER, kinks=5, step=CORE_STEP):
    """Points along a jagged line from a to b: a few kinks, each pushed off the line at random."""
    d = tuple(b[i] - a[i] for i in range(3))
    length = math.sqrt(sum(c * c for c in d))
    # two directions across the line
    u = (d[1], -d[0], 0.0) if abs(d[2]) < 0.9 * length else (0.0, d[2], -d[1])
    ul = math.sqrt(sum(c * c for c in u))
    u = tuple(c / ul for c in u)
    w = (d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0])
    wl = math.sqrt(sum(c * c for c in w))
    w = tuple(c / wl for c in w)
    knots = []
    for t in [0.0] + sorted(rng.uniform(0.1, 0.9) for _ in range(kinks)) + [1.0]:
        reach = jitter * math.sin(math.pi * t)  # pinned at both ends
        p, q = rng.uniform(-reach, reach), rng.uniform(-reach, reach)
        knots.append(tuple(a[i] + d[i] * t + u[i] * p + w[i] * q for i in range(3)))
    points = []
    for k0, k1 in zip(knots, knots[1:]):
        seg = math.sqrt(sum((k1[i] - k0[i]) ** 2 for i in range(3)))
        n = max(1, int(seg / step))
        points += [tuple(k0[i] + (k1[i] - k0[i]) * s / n for i in range(3)) for s in range(n)]
    return points + [b]


def element(p, size, texture):
    half = size / 2
    faces = {f: {"texture": texture, "uv": [0, 0, 16, 16]} for f in ("north", "south", "east", "west", "up", "down")}
    return {"from": [round(p[i] - half, 3) for i in range(3)], "to": [round(p[i] + half, 3) for i in range(3)], "shade": False, "light_emission": 15, "faces": faces}


def bolt(rng: random.Random, tip):
    """One arc: its core, a glow along it brightest at the prong and fading to the middle, and forks."""
    core = arc(rng, tip, MOUTH)
    out = [element(p, CORE_SIZE, "#core") for p in core]
    glow = arc(rng, tip, MOUTH, jitter=JITTER * 0.35, kinks=3, step=GLOW_STEP)  # (a smoother line: the glow round the crooked core)
    for i, p in enumerate(glow):
        strength = min(GLOWS - 1, int(i / max(1, len(glow) - 1) * GLOWS))
        out.append(element(p, GLOW_SIZE * (1.0 - 0.25 * strength), f"#glow{strength}"))
    for _ in range(rng.choice((1, 1, 2))):  # forks: short crooked spurs off the core, ending in the air
        start = core[rng.randrange(len(core) // 5, len(core) * 4 // 5)]
        end = tuple(start[i] + rng.uniform(-1.6, 1.6) for i in range(3))
        out += [element(p, FORK_SIZE, "#core") for p in arc(rng, start, end, jitter=0.5, kinks=2)]
    return out


def write(pack: Path) -> None:
    assets = pack / "assets" / "portalcraft"
    models, textures = assets / "models" / "item", assets / "textures" / "item"
    body = json.loads((models / "portal_gun_fire" / "body.json").read_text(encoding="utf-8"))
    (models / "portal_gun_arcs").mkdir(parents=True, exist_ok=True)
    rng = random.Random(4)
    tex = {"core": "portalcraft:item/portal_gun_arc", "particle": "portalcraft:item/portal_gun_arc"}
    tex.update({f"glow{s}": f"portalcraft:item/portal_gun_arc_glow{s}" for s in range(GLOWS)})
    for n in range(FRAMES):
        elements = []
        for tip in TIPS:
            if rng.random() < 0.2 and n % 2:  # now and then an arc is out for a frame
                continue
            elements += bolt(rng, tip)
        model = {"textures": tex, "elements": elements, "display": body["display"]}
        (models / "portal_gun_arcs" / f"arc_{n}.json").write_text(json.dumps(model, separators=(",", ":")), encoding="utf-8")
    textures.mkdir(parents=True, exist_ok=True)
    # the core: white-hot, a touch of yellow at its edge
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = 1.0 - min(1.0, math.hypot(x - 7.5, y - 7.5) / 10.5)
            img.putpixel((x, y), (255, int(240 + 15 * c), int(170 + 85 * c), 255))
    img.save(textures / "portal_gun_arc.png")
    # the glow: warm yellow, see-through, softer the farther from the prong; each voxel's own edge faded
    for s in range(GLOWS):
        img = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                edge = 1.0 - min(1.0, math.hypot(x - 7.5, y - 7.5) / 11.0)
                img.putpixel((x, y), (255, 214, 96, int((110 - 30 * s) * (0.55 + 0.45 * edge))))
        img.save(textures / f"portal_gun_arc_glow{s}.png")
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
