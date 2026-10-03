"""Renders an item model JSON the way Minecraft draws it in the first-person right hand, for tuning
3D item models without starting the game. Usage (from the repo root):

  uv run --no-project --with pillow python tools/preview_item.py MODEL.json OUT.png [--context firstperson_righthand]

Follows ItemInHandRenderer for the right hand at rest: the arm offset (0.56, -0.52, -0.72), then the
model's display transform (translation in 1/16 blocks, rotation X then Y then Z, scale), then the
model centred on the origin; a 70 degree vertical field of view (the hand's, whatever the player's
setting). Faces are shaded like Minecraft's (up 1.0, down 0.5, north/south 0.8, east/west 0.6) and
take the average colour of their uv rectangle; painter's algorithm, no texture detail.
"""
import json, math, sys
from PIL import Image, ImageDraw

W, H = 960, 540


def rot(axis, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def mul(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def load(path, root):
    m = json.load(open(path))
    if "parent" in m and not m.get("elements"):
        pass
    return m


def tex_colour(img, uv):
    u0, v0, u1, v1 = uv
    sx, sy = img.size[0] / 16.0, img.size[1] / 16.0
    box = (int(min(u0, u1) * sx), int(min(v0, v1) * sy), max(int(max(u0, u1) * sx), int(min(u0, u1) * sx) + 1),
           max(int(max(v0, v1) * sy), int(min(v0, v1) * sy) + 1))
    px = list(img.crop(box).convert("RGBA").get_flattened_data()) if hasattr(Image.Image, "get_flattened_data") else list(img.crop(box).convert("RGBA").getdata())
    px = [p for p in px if p[3] > 0] or px
    return tuple(sum(p[i] for p in px) // len(px) for i in range(3))


NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def faces_of(fr, to):
    x0, y0, z0 = fr
    x1, y1, z1 = to
    return {
        "north": [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
        "south": [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
        "west": [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
        "east": [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
        "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "down": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
    }


def main():
    model_path, out = sys.argv[1], sys.argv[2]
    context = "firstperson_righthand"
    if "--context" in sys.argv:
        context = sys.argv[sys.argv.index("--context") + 1]
    root = model_path.split("/models/")[0]
    model = json.load(open(model_path))
    display = model.get("display", {}).get(context, {})
    tr = [t / 16.0 for t in display.get("translation", [0, 0, 0])]
    rx, ry, rz = display.get("rotation", [0, 0, 0])
    sc = display.get("scale", [1, 1, 1])
    R = matmul(matmul(rot("x", rx), rot("y", ry)), rot("z", rz))
    textures = {}
    for k, v in model.get("textures", {}).items():
        ns, _, p = v.partition(":")
        if not p:
            ns, p = "minecraft", ns
        try:
            textures[k] = Image.open(f"{root}/textures/{p}.png")
        except OSError:
            pass
    polys = []
    for e in model["elements"]:
        er = e.get("rotation")
        for name, quad in faces_of(e["from"], e["to"]).items():
            f = e["faces"].get(name)
            if not f:
                continue
            tex = textures.get(f["texture"].lstrip("#"))
            col = tex_colour(tex, f.get("uv", [0, 0, 16, 16])) if tex else (255, 0, 255)
            lit = e.get("light_emission", 0) > 0
            shade = 1.0 if lit else SHADE[name]
            col = tuple(int(c * shade) for c in col)
            n = list(NORMAL[name])
            if er:
                n = mul(rot(er["axis"], er["angle"]), n)
            n = mul(R, [n[i] / sc[i] for i in range(3)])
            pts = []
            for p in quad:
                v = list(p)
                if er:
                    o = er["origin"]
                    v = [v[i] - o[i] for i in range(3)]
                    v = mul(rot(er["axis"], er["angle"]), v)
                    v = [v[i] + o[i] for i in range(3)]
                v = [v[i] / 16.0 - 0.5 for i in range(3)]          # the model, centred
                v = [v[i] * sc[i] for i in range(3)]               # display scale
                v = mul(R, v)                                      # display rotation
                v = [v[i] + tr[i] for i in range(3)]               # display translation
                v = [v[0] + 0.56, v[1] - 0.52, v[2] - 0.72]        # the right arm's place
                pts.append(v)
            polys.append((pts, col, n))
    img = Image.new("RGB", (W, H), (110, 140, 190))
    draw = ImageDraw.Draw(img)
    f = (H / 2) / math.tan(math.radians(70) / 2)
    def proj(v):
        z = -v[2]
        if z < 0.01:
            return None
        return (W / 2 + v[0] * f / z, H / 2 - v[1] * f / z)
    polys.sort(key=lambda pc: -sum(math.dist((0, 0, 0), p) for p in pc[0]) / 4)
    for pts, col, n in polys:
        centre = [sum(p[i] for p in pts) / 4 for i in range(3)]
        if sum(n[i] * -centre[i] for i in range(3)) <= 0:
            continue  # facing away from the camera
        pp = [proj(p) for p in pts]
        if None in pp:
            continue
        draw.polygon(pp, fill=col, outline=tuple(max(0, x - 40) for x in col))
    draw.line((W / 2 - 8, H / 2, W / 2 + 8, H / 2), fill=(255, 255, 255))
    draw.line((W / 2, H / 2 - 8, W / 2, H / 2 + 8), fill=(255, 255, 255))
    img.save(out)


if __name__ == "__main__":
    main()
