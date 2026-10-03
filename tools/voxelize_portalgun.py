"""Voxelizes Portal's own first-person portal gun into a Minecraft item model.

Reads models/weapons/v_portalgun (.mdl/.vvd/.dx90.vtx) and its texture straight from your Steam
Portal's VPKs (nothing of Valve's is kept in this repo), samples every triangle of the gun and its
glass light into voxels coloured from the texture, merges same-coloured voxels into boxes, and
writes a resource pack that replaces the in-hand gun with it:
  run/resourcepacks/portalcraft-voxel-gun/  (models/item/portal_gun_in_hand.json + its palette)
It's built from Valve's model, so it stays on your PC: the repo's own gun is the hand-made one
(tools/make_gun_model.py), and the pack only changes how yours looks. It's held the same way (its
display transforms are copied from the hand-made model). The glass light becomes a glowing,
tinted part (blue or orange, as the gun last fired).

  uv run --no-project --with pillow --with numpy python tools/voxelize_portalgun.py [--portal DIR] [--length 26]
"""
import argparse, json, math, os, struct, sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class VPK:
    def __init__(self, dir_path):
        self.base = dir_path[:-len("_dir.vpk")]
        d = open(dir_path, "rb").read()
        _, ver, tree = struct.unpack_from("<III", d, 0)
        self.hdr, self.tree, self.data = (12 if ver == 1 else 28), tree, d
        self.entries = {}
        p = self.hdr

        def cstr():
            nonlocal p
            e = d.index(b"\0", p)
            s = d[p:e].decode("latin-1")
            p = e + 1
            return s

        while (ext := cstr()):
            while (path := cstr()):
                while (name := cstr()):
                    _, pre, arch, off, ln, _ = struct.unpack_from("<IHHIIH", d, p)
                    p += 18
                    self.entries[((path + "/") if path.strip() else "") + name + "." + ext] = (arch, off, ln, d[p:p + pre])
                    p += pre

    def read(self, name):
        arch, off, ln, pre = self.entries[name]
        if arch == 0x7FFF:
            o = self.hdr + self.tree + off
            return pre + self.data[o:o + ln]
        with open(f"{self.base}_{arch:03d}.vpk", "rb") as f:
            f.seek(off)
            return pre + f.read(ln)


def dxt_decode(data, w, h, dxt5):
    """DXT1/DXT5 to an RGB array (h, w, 3)."""
    out = np.zeros((h, w, 3), np.uint8)
    bs = 16 if dxt5 else 8
    p = 0
    for by in range(0, h, 4):
        for bx in range(0, w, 4):
            blk = data[p:p + bs]
            p += bs
            c = blk[8:] if dxt5 else blk
            c0, c1, bits = struct.unpack_from("<HHI", c, 0)

            def rgb(v):
                return np.array([(v >> 11 & 31) * 255 // 31, (v >> 5 & 63) * 255 // 63, (v & 31) * 255 // 31], float)

            a, b = rgb(c0), rgb(c1)
            pal = [a, b, (2 * a + b) / 3, (a + 2 * b) / 3] if (c0 > c1 or dxt5) else [a, b, (a + b) / 2, np.zeros(3)]
            for i in range(16):
                y, x = by + i // 4, bx + i % 4
                if y < h and x < w:
                    out[y, x] = pal[bits >> (2 * i) & 3]
    return out


def vtf(data):
    w, h = struct.unpack_from("<HH", data, 16)
    fmt = struct.unpack_from("<i", data, 52)[0]
    if fmt not in (13, 15):
        sys.exit(f"unsupported VTF format {fmt}")
    size = max(1, (w + 3) // 4) * max(1, (h + 3) // 4) * (16 if fmt == 15 else 8)
    return dxt_decode(data[-size:], w, h, fmt == 15)  # the biggest mip comes last


def mesh_triangles(mdl, vvd, vtx):
    """[(material, [(pos, uv) x3])] for LOD 0 of a one-bodypart, one-model .mdl (version 44-48)."""
    I = lambda b, o: struct.unpack_from("<i", b, o)[0]
    verts = [(struct.unpack_from("<3f", vvd, 64 + i * 48 + 16), struct.unpack_from("<2f", vvd, 64 + i * 48 + 40)) for i in range(I(vvd, 16))]
    bp = I(mdl, 236)
    model = bp + I(mdl, bp + 12)
    nmesh, meshi = I(mdl, model + 72), I(mdl, model + 76)
    meshes = [struct.unpack_from("<iiii", mdl, model + meshi + j * 116) for j in range(nmesh)]  # material, model, numverts, vertexoffset
    tris = []
    vbp = I(vtx, 32)
    vmodel = vbp + I(vtx, vbp + 4)
    vlod = vmodel + I(vtx, vmodel + 4)
    nm, mo = I(vtx, vlod), I(vtx, vlod + 4)
    for j in range(nm):
        mh = vlod + mo + j * 9
        ngroups, go = I(vtx, mh), I(vtx, mh + 4)
        mat, _, _, voff = meshes[j]
        for g in range(ngroups):
            sg = mh + go + g * 25
            nv, vo, ni, io = struct.unpack_from("<iiii", vtx, sg)
            ids = [struct.unpack_from("<H", vtx, sg + vo + k * 9 + 4)[0] for k in range(nv)]
            idx = struct.unpack_from(f"<{ni}H", vtx, sg + io)
            for t in range(0, ni - 2, 3):
                tris.append((mat, [verts[voff + ids[idx[t + k]]] for k in range(3)]))
    return tris


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--portal", default="D:/SteamLibrary/steamapps/common/Portal")
    ap.add_argument("--length", type=float, default=26.0, help="the gun's length in model pixels")
    ap.add_argument("--voxel", type=float, default=0.5, help="voxel size in model pixels (1 = vanilla's pixel)")
    ap.add_argument("--pack", default=os.path.join(ROOT, "run", "resourcepacks", "portalcraft-voxel-gun"), help="the resource pack to write")
    a = ap.parse_args()
    v = VPK(os.path.join(a.portal, "portal", "portal_pak_dir.vpk"))
    mdl = v.read("models/weapons/v_portalgun.mdl")
    tris = mesh_triangles(mdl, v.read("models/weapons/v_portalgun.vvd"), v.read("models/weapons/v_portalgun.dx90.vtx"))
    tex = vtf(v.read("materials/models/weapons/v_models/v_portalgun/v_portalgun.vtf"))
    names = []
    nt, ti = struct.unpack_from("<ii", mdl, 204)
    for k in range(nt):
        no = struct.unpack_from("<i", mdl, ti + k * 64)[0]
        names.append(mdl[ti + k * 64 + no:].split(b"\0")[0].decode())
    gun = [t for t in tris if names[t[0]] != "v_hands"]
    print(f"{len(gun)} triangles of the gun ({', '.join(names)}); texture {tex.shape[1]}x{tex.shape[0]}")

    # The viewmodel's reference pose lies along Source y: the muzzle (the black collar and claws) at
    # -y, the white casing at +y, z up, so -x is its right (Portal's animation bones turn it to face
    # ahead). Minecraft model axes: x right, y up, z back (south); the muzzle points north (-z).
    def to_mc(p):
        return np.array([-p[0], p[2], p[1]])

    pts = np.array([to_mc(p) for _, tri in gun for p, _ in tri])
    lo, hi = pts.min(0), pts.max(0)
    scale = a.length / (hi[2] - lo[2])
    centre = (lo + hi) / 2

    def place(p):  # into model pixels: centred on x 8, sitting from y 1, rear at z 16
        q = (to_mc(p) - centre) * scale
        return q + np.array([8.0, 1.0 + (hi[1] - lo[1]) * scale / 2, 16.0 - (hi[2] - lo[2]) * scale / 2])

    th, tw = tex.shape[:2]
    acc = {}
    for mat, tri in gun:
        glass = names[mat].endswith("glass")
        P = [place(p) for p, _ in tri]
        U = [uv for _, uv in tri]
        edge = max(np.linalg.norm(P[1] - P[0]), np.linalg.norm(P[2] - P[0]), np.linalg.norm(P[2] - P[1]))
        n = max(2, int(math.ceil(edge * 3 / a.voxel)))
        for i in range(n + 1):
            for j in range(n + 1 - i):
                b0, b1 = i / n, j / n
                b2 = 1 - b0 - b1
                q = P[0] * b0 + P[1] * b1 + P[2] * b2
                cell = tuple(int(math.floor(c / a.voxel)) for c in q)
                if glass:
                    col = (255, 255, 255)
                else:
                    u = (U[0][0] * b0 + U[1][0] * b1 + U[2][0] * b2) % 1.0
                    vv = (U[0][1] * b0 + U[1][1] * b1 + U[2][1] * b2) % 1.0
                    col = tuple(int(c) for c in tex[min(th - 1, int(vv * th)), min(tw - 1, int(u * tw))])
                s = acc.setdefault(cell, [0, 0, 0, 0, False])
                s[0] += col[0]; s[1] += col[1]; s[2] += col[2]; s[3] += 1
                s[4] = s[4] or glass
    print(f"{len(acc)} voxels")

    # Quantize to a palette (the glow is its own entry, white so its tint shows true).
    cells = list(acc)
    cols = np.array([[acc[c][k] / acc[c][3] for k in range(3)] for c in cells])
    glow = np.array([acc[c][4] for c in cells])
    img = Image.fromarray(cols[~glow].astype(np.uint8).reshape(-1, 1, 3))
    q = img.quantize(colors=47, method=Image.Quantize.MEDIANCUT)
    pal = np.array(q.getpalette()[:47 * 3]).reshape(-1, 3)
    idx = np.zeros(len(cells), int)
    idx[~glow] = np.array(q.get_flattened_data() if hasattr(q, 'get_flattened_data') else q.getdata())
    idx[glow] = 47
    voxels = {c: int(i) for c, i in zip(cells, idx)}

    # Greedy-merge same-palette voxels into boxes (x runs, then rows in y, then slabs in z).
    remaining = dict(voxels)
    boxes = []
    for c in sorted(voxels):
        if c not in remaining:
            continue
        k = remaining[c]
        x0, y0, z0 = c
        x1 = x0
        while (x1 + 1, y0, z0) in remaining and remaining[(x1 + 1, y0, z0)] == k:
            x1 += 1
        y1 = y0
        while all(remaining.get((x, y1 + 1, z0)) == k for x in range(x0, x1 + 1)):
            y1 += 1
        z1 = z0
        while all(remaining.get((x, y, z1 + 1)) == k for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)):
            z1 += 1
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    del remaining[(x, y, z)]
        boxes.append(((x0, y0, z0), (x1 + 1, y1 + 1, z1 + 1), k))
    print(f"{len(boxes)} boxes")

    # The palette texture: one texel per entry on a 16x16 sheet, the glow white at entry 47.
    sheet = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for i, c in enumerate(pal):
        sheet.putpixel((i % 16, i // 16), (*map(int, c), 255))
    sheet.putpixel((47 % 16, 47 // 16), (255, 255, 255, 255))
    assets = os.path.join(a.pack, "assets", "portalcraft")
    os.makedirs(os.path.join(assets, "textures", "item"), exist_ok=True)
    os.makedirs(os.path.join(assets, "models", "item"), exist_ok=True)
    with open(os.path.join(a.pack, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "PortalCraft: Portal's own gun, voxelized from your Portal", "pack_format": 97, "min_format": 97, "max_format": 97}}, f)
    sheet.save(os.path.join(assets, "textures", "item", "portal_gun_voxels.png"))

    elements = []
    for fr, to, k in boxes:
        u, vv = k % 16, k // 16
        face = {"uv": [u + 0.25, vv + 0.25, u + 0.75, vv + 0.75], "texture": "#gun"}
        if k == 47:
            face["tintindex"] = 0
        e = {"from": [c * a.voxel for c in fr], "to": [c * a.voxel for c in to], "faces": {d: dict(face) for d in ("north", "east", "south", "west", "up", "down")}}
        if k == 47:
            e["light_emission"] = 15
        elements.append(e)
    path = os.path.join(assets, "models", "item", "portal_gun_in_hand.json")
    display = json.load(open(os.path.join(ROOT, "src/main/resources/assets/portalcraft/models/item/portal_gun_in_hand.json")))["display"]
    model = {"textures": {"gun": "portalcraft:item/portal_gun_voxels", "particle": "portalcraft:item/portal_gun"}, "elements": elements, "display": display}
    json.dump(model, open(path, "w"), indent=1)
    lo2 = np.min([b[0] for b in boxes], 0) * a.voxel
    hi2 = np.max([b[1] for b in boxes], 0) * a.voxel
    print(f"wrote {path}: {len(elements)} elements, bounds {lo2} .. {hi2}")


if __name__ == "__main__":
    main()
