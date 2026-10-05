"""tools/preview_item.py with what the micro-voxel gun needs to be judged: texture alpha (blended,
so glass shows what is behind it), the tint (tintindex 0 faces take --tint), several models drawn
together (an item definition's composite), other display contexts, and a zoom.

  uv run --no-project --with pillow python tools/preview_item_micro.py OUT.png MODEL.json [MODEL.json ...]
      [--context firstperson_righthand|thirdperson_righthand|side|top|front] [--tint 2A8CFF] [--tint1 808080] [--tint2 808080] [--tint3 FFFFFF] [--ambient 1] [--zoom 1]

`side`, `top` and `front` are not Minecraft's: they look at the model square on, to inspect it.
A model that only has a "parent" takes the parent's elements and textures (its own display wins).
"""
import json, math, os, sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preview_item import rot, mul, matmul, faces_of, NORMAL, SHADE

W, H = 960, 540
INSPECT = {"side": [0, 90, 0], "top": [90, 0, 0], "front": [0, 180, 0], "side_left": [0, -90, 0], "quarter": [25, 140, 0]}


def load(path):
    m = json.load(open(path))
    root = path.replace("\\", "/").split("/models/")[0]
    if "parent" in m and "elements" not in m:
        ns, _, p = m["parent"].partition(":")
        parent = load(f"{os.path.dirname(root)}/{ns}/models/{p}.json")
        m = {**parent, **m, "display": {**parent.get("display", {}), **m.get("display", {})}}
    m["_root"] = root
    return m


def main():
    args = [a for a in sys.argv[1:]]
    opt = {}
    for k in ("--context", "--tint", "--tint1", "--tint2", "--tint3", "--ambient", "--zoom"):
        if k in args:
            i = args.index(k)
            opt[k] = args[i + 1]
            del args[i:i + 2]
    out, paths = args[0], args[1:]
    context = opt.get("--context", "firstperson_righthand")
    # tintindex 0: the light; 1 and 2: the glow's spill on the gun (the item definition's default is a half);
    # 3: its spill on the white shell (default white)
    tints = [tuple(int(opt.get(k, d)[i:i + 2], 16) for i in (0, 2, 4)) for k, d in (("--tint", "2A8CFF"), ("--tint1", "808080"), ("--tint2", "808080"), ("--tint3", "FFFFFF"))]
    zoom = float(opt.get("--zoom", 1))
    ambient = float(opt.get("--ambient", 1))
    polys = []
    for path in paths:
        model = load(path)
        if context in INSPECT:
            display, arm = {"rotation": INSPECT[context], "translation": [0, 0, 0], "scale": [1, 1, 1]}, (0.0, -0.05, -2.6)
        else:
            display, arm = model.get("display", {}).get(context, {}), ((0.56, -0.52, -0.72) if context.startswith("firstperson") else (0.0, -0.1, -1.6))
        tr = [t / 16.0 for t in display.get("translation", [0, 0, 0])]
        sc = display.get("scale", [1, 1, 1])
        rx, ry, rz = display.get("rotation", [0, 0, 0])
        R = matmul(matmul(rot("x", rx), rot("y", ry)), rot("z", rz))
        textures = {}
        for k, v in model.get("textures", {}).items():
            ns, _, p = v.partition(":")
            try:
                textures[k] = Image.open(f"{os.path.dirname(model['_root'])}/{ns}/textures/{p}.png").convert("RGBA")
            except OSError:
                pass
        cache = {}
        for e in model["elements"]:
            lit = e.get("light_emission", 0) > 0
            for name, quad in faces_of(e["from"], e["to"]).items():
                f = e["faces"].get(name)
                if not f:
                    continue
                tex = textures.get(f["texture"].lstrip("#"))
                key = (f["texture"], tuple(f.get("uv", [0, 0, 16, 16])))
                if key not in cache:
                    if tex:
                        u0, v0, u1, v1 = key[1]
                        sx, sy = tex.size[0] / 16.0, tex.size[1] / 16.0
                        cache[key] = tex.getpixel((min(tex.size[0] - 1, int((u0 + u1) / 2 * sx)), min(tex.size[1] - 1, int((v0 + v1) / 2 * sy))))
                    else:
                        cache[key] = (255, 0, 255, 255)
                col = cache[key]
                if col[3] == 0:
                    continue
                rgb = col[:3]
                if "tintindex" in f:
                    rgb = tuple(min(255, round(c * t / 255)) for c, t in zip(rgb, tints[f["tintindex"]]))
                shade = 1.0 if lit else SHADE[name] * ambient   # --ambient: a dim room dims all but the glowing faces
                rgb = tuple(min(255, int(c * shade)) for c in rgb)
                n = mul(R, [NORMAL[name][i] / sc[i] for i in range(3)])
                pts = []
                for p in quad:
                    v = [(p[i] / 16.0 - 0.5) * sc[i] for i in range(3)]
                    v = mul(R, v)
                    pts.append([v[i] + tr[i] + arm[i] for i in range(3)])
                polys.append((pts, rgb, col[3], n))
    img = Image.new("RGB", (W, H), (110, 140, 190))
    draw = ImageDraw.Draw(img)
    f = (H / 2) / math.tan(math.radians(70) / 2) * zoom
    aim = (0.0, 0.0)
    if zoom != 1 and polys and context not in INSPECT:   # a zoom centres on the model
        cs = [[sum(p[i] for p in pc[0]) / 4 for i in range(3)] for pc in polys]
        aim = (sum(c[0] / -c[2] for c in cs) / len(cs), sum(c[1] / -c[2] for c in cs) / len(cs))

    def proj(v):
        z = -v[2]
        if z < 0.01:
            return None
        return (W / 2 + (v[0] / z - aim[0]) * f, H / 2 - (v[1] / z - aim[1]) * f)

    polys.sort(key=lambda pc: -sum(math.dist((0, 0, 0), p) for p in pc[0]) / 4)
    for pts, col, alpha, n in polys:
        centre = [sum(p[i] for p in pts) / 4 for i in range(3)]
        if sum(n[i] * -centre[i] for i in range(3)) <= 0:
            continue
        pp = [proj(p) for p in pts]
        if None in pp:
            continue
        if alpha >= 255:
            draw.polygon(pp, fill=col)
        else:
            xs, ys = [p[0] for p in pp], [p[1] for p in pp]
            box = (max(0, int(min(xs)) - 1), max(0, int(min(ys)) - 1), min(W, int(max(xs)) + 2), min(H, int(max(ys)) + 2))
            if box[2] <= box[0] or box[3] <= box[1]:
                continue
            under = img.crop(box).convert("RGBA")
            over = Image.new("RGBA", under.size, (0, 0, 0, 0))
            ImageDraw.Draw(over).polygon([(x - box[0], y - box[1]) for x, y in pp], fill=(*col, alpha))
            img.paste(Image.alpha_composite(under, over).convert("RGB"), box[:2])
    img.save(out)


if __name__ == "__main__":
    main()
