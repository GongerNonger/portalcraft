"""Micro-voxel version of tools/voxelize_portalgun.py: Portal's own first-person portal gun as a
Minecraft item model, with finer voxels, its materials, and its firing animation as keyframes.

Reads models/weapons/v_portalgun (.mdl/.vvd/.dx90.vtx) and its textures straight from your Steam
Portal's VPKs (nothing of Valve's is kept in this repo; what it writes goes under run/, which git
ignores), and writes a resource pack:
  run/resourcepacks/portalcraft-voxel-gun-micro/
    assets/portalcraft/models/item/portal_gun_in_hand.json   the gun at rest (what the mod's item
                                                             definition already shows in the hand)
    assets/portalcraft/textures/item/portal_gun_voxels.png   its palette
    assets/portalcraft/models/item/portal_gun_fire/...       the firing animation's keyframes
    proposed/items/portal_gun.json                           an item definition that plays them
                                                             (not active: see proposed/README.md)

What differs from the 0.5 voxel gun:
  * Voxels are --voxel (0.25) model pixels. Only the faces that can be seen are written (a flood
    fill from outside finds them), and same-coloured voxels are merged into boxes.
  * Materials. Each voxel is classed from Valve's own maps: painted white shell (bright base
    texture), shiny dark metal (dark, with a strong phong mask: the alpha of v_portalgun_normal),
    matte black (dark, weak phong mask), the glass tube (the v_portalgun_glass mesh) and the light.
    Minecraft's item models have no specular, so the shine is baked into the colours from the
    surface normal: a few brightness steps, plus a highlight whose tightness is the exponent map's
    and whose strength is the phong mask's ($phongboost 3), plus speckle on the metal.
  * The glass is translucent (texture alpha), tinted and glowing, around an opaque glowing core.
    Both use tintindex 0 and light_emission 15, as the 0.5 gun's light does, so the mod's blue or
    orange and its flash colour them. The glass is written last so what is inside is drawn first.
  * The firing animation (the model's `fire1` sequence) is sampled once per game tick into
    keyframes: the recoil of the whole gun and the slide of its front cover become display
    transforms, and the claws, wires and glass (which bend) are voxelized again in each pose.

--hybrid writes a third pack instead, run/resourcepacks/portalcraft-voxel-gun-hybrid/:
  * Two voxel sizes on one grid (--coarse 0.5, two fine voxels a side): the painted white shell is
    in coarse voxels (the chunky look of the 0.5 gun), and everything dark (the collar and barrel,
    the claws, the wires, the glass and its core) stays fine. The seam is the paint's own edge.
  * The gun is lit by its own light (--spill). Dark surfaces near the glass tube and the muzzle
    that face them are tinted: tintindex 1 where the glow would be strong, 2 in a wider, weaker
    zone. Their palette colours are baked at double brightness, so the item definition's default
    tint (0xFF808080, a half) shows them exactly as they are unlit, and the mod can send a
    brighter, coloured tint (custom model data colours 1 and 2). The pack carries its own
    items/portal_gun.json with those two tints.
  * Small lights (--lights), tinted and glowing like the tube (tintindex 0): at the claw tips
    (the model's Arm1/2/3_attach3 attachments) and at its Body_light attachment on the shell.

--v2 writes run/resourcepacks/portalcraft-voxel-gun-hybrid-v2/: the hybrid gun, and
  * a pose for each of fire1's 16 frames (--per-frame), `portal_gun_fire/cover_N` and `bend_N` with
    N = frame + 1, with no recoil in them: the pack's item definition picks them by the mod's
    `portalcraft:fire_frame` property, and the whole gun's kick is data for the mod to apply every
    rendered frame, `assets/portalcraft/gun_recoil.json` (see recoil_frames());
  * the same for the grab (--grab): `portal_gun_pickup/..._1..12` and `portal_gun_release/..._1..21`
    for the `pickup` and `release` sequences, at fire_frame 101..112 and 201..221, their whole-gun
    motion as "pickup" and "release" in gun_recoil.json;
  * a stronger spill (--gain 4, --glow-tints): the spill zones' colours are baked at four times
    their brightness (so only voxels no brighter than 63 can be in them; brighter ones in reach
    join the shell's tintindex 3), and the item definition's tints 1..3 are the mod's
    `portalcraft:gun_glow`, which works the light out on the client;
  * halos (--halo): two thin see-through shells of glowing, tinted voxels around every light, so
    it bleeds into the air around it;
  * the glow on the white shell (--shell-tint): the shell's voxels in the glow's reach take
    tintindex 3 at their own colour (white by default; the mod sends a pastel).

  uv run --no-project --with pillow --with numpy python tools/voxelize_portalgun_micro.py [--voxel 0.25] [--hybrid | --v2] [--info]
"""
import argparse, json, math, os, shutil, struct, sys
from collections import deque

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MATERIALS = "materials/models/weapons/v_models/v_portalgun/"
SHELL, METAL, BLACK, GLASS, LIGHT, HALO1, HALO2 = range(7)
CLASS_NAMES = ["white shell", "shiny dark metal", "matte black", "glass", "light"]
DIRS = [("west", 0, -1), ("east", 0, 1), ("down", 1, -1), ("up", 1, 1), ("north", 2, -1), ("south", 2, 1)]


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


def vtf(data):
    """The biggest mip of a DXT1/DXT5 .vtf as an RGBA array (h, w, 4)."""
    w, h = struct.unpack_from("<HH", data, 16)
    fmt = struct.unpack_from("<i", data, 52)[0]
    if fmt not in (13, 15):
        sys.exit(f"unsupported VTF format {fmt}")
    bs = 16 if fmt == 15 else 8
    nx, ny = max(1, (w + 3) // 4), max(1, (h + 3) // 4)
    b = np.frombuffer(data[-nx * ny * bs:], np.uint8).reshape(ny, nx, bs).astype(np.uint32)  # the biggest mip comes last
    c = b[..., 8:] if fmt == 15 else b
    c0, c1 = c[..., 0] | c[..., 1] << 8, c[..., 2] | c[..., 3] << 8
    bits = c[..., 4] | c[..., 5] << 8 | c[..., 6] << 16 | c[..., 7] << 24

    def rgb(v):
        return np.stack([(v >> 11 & 31) * 255 / 31, (v >> 5 & 63) * 255 / 63, (v & 31) * 255 / 31], -1)

    p0, p1 = rgb(c0), rgb(c1)
    four = (c0 > c1)[..., None] | (fmt == 15)
    pal = np.stack([p0, p1, np.where(four, (2 * p0 + p1) / 3, (p0 + p1) / 2), np.where(four, (p0 + 2 * p1) / 3, 0)], -2)
    pa = np.full(pal.shape[:-1], 255.0)
    pa[..., 3] = np.where(four[..., 0], 255, 0)
    out = np.zeros((ny * 4, nx * 4, 4), np.uint8)
    if fmt == 15:
        a0, a1 = b[..., 0].astype(float), b[..., 1].astype(float)
        abits = sum(b[..., 2 + k].astype(np.uint64) << np.uint64(8 * k) for k in range(6))
        eight = a0 > a1
        apal = np.stack([a0, a1] + [np.where(eight, ((7 - k) * a0 + k * a1) / 7, ((5 - k) * a0 + k * a1) / 5 if k < 5 else (0.0 if k == 5 else 255.0)) for k in range(1, 7)], -1)
    for i in range(16):
        k = (bits >> (2 * i) & 3).astype(int)
        out[i // 4::4, i % 4::4, :3] = np.take_along_axis(pal, k[..., None, None].repeat(3, -1), -2)[..., 0, :]
        if fmt == 15:
            ak = (abits >> np.uint64(3 * i) & np.uint64(7)).astype(int)
            out[i // 4::4, i % 4::4, 3] = np.take_along_axis(apal, ak[..., None], -1)[..., 0]
        else:
            out[i // 4::4, i % 4::4, 3] = np.take_along_axis(pa, k[..., None], -1)[..., 0]
    return out[:h, :w]


I32 = lambda b, o: struct.unpack_from("<i", b, o)[0]
F32 = lambda b, o: struct.unpack_from("<f", b, o)[0]
CSTR = lambda b, o: b[o:b.index(b"\0", o)].decode("latin-1")


def quat_matrix(q, pos=(0, 0, 0)):
    x, y, z, w = q
    m = np.eye(4)
    m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                 [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                 [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    m[:3, 3] = pos
    return m


def euler_quat(a):  # Source's AngleQuaternion(RadianEuler)
    sr, cr, sp, cp, sy, cy = math.sin(a[0] / 2), math.cos(a[0] / 2), math.sin(a[1] / 2), math.cos(a[1] / 2), math.sin(a[2] / 2), math.cos(a[2] / 2)
    return (sr * cp * cy - cr * sp * sy, cr * sp * cy + sr * cp * sy, cr * cp * sy - sr * sp * cy, cr * cp * cy + sr * sp * sy)


class Model:
    """A Source .mdl (version 44-48): its bones, sequences, and its animations' bone poses."""

    def __init__(self, mdl):
        self.d = mdl
        self.bones = []
        for k in range(I32(mdl, 156)):
            o = I32(mdl, 160) + k * 216
            self.bones.append(dict(name=CSTR(mdl, o + I32(mdl, o)), parent=I32(mdl, o + 4), pos=struct.unpack_from("<3f", mdl, o + 32),
                                   quat=struct.unpack_from("<4f", mdl, o + 44), rot=struct.unpack_from("<3f", mdl, o + 60),
                                   posscale=struct.unpack_from("<3f", mdl, o + 72), rotscale=struct.unpack_from("<3f", mdl, o + 84),
                                   pose_to_bone=np.vstack([np.array(struct.unpack_from("<12f", mdl, o + 96)).reshape(3, 4), [0, 0, 0, 1]])))
        self.anims = []
        for k in range(I32(mdl, 180)):
            o = I32(mdl, 184) + k * 100
            if I32(mdl, o + 52) or I32(mdl, o + 84):
                sys.exit("this .mdl keeps its animations in blocks or sections, which this reader doesn't follow")
            self.anims.append(dict(name=CSTR(mdl, o + I32(mdl, o + 4)), fps=F32(mdl, o + 8), flags=I32(mdl, o + 12), frames=I32(mdl, o + 16), data=o + I32(mdl, o + 56)))
        self.seqs = []
        for k in range(I32(mdl, 188)):
            o = I32(mdl, 192) + k * 212
            events = [(F32(mdl, o + I32(mdl, o + 28) + e * 80), CSTR(mdl, o + I32(mdl, o + 28) + e * 80 + 12)) for e in range(I32(mdl, o + 24))]
            self.seqs.append(dict(name=CSTR(mdl, o + I32(mdl, o + 4)), activity=CSTR(mdl, o + I32(mdl, o + 8)), flags=I32(mdl, o + 12),
                                  anim=self.anims[struct.unpack_from("<h", mdl, o + I32(mdl, o + 60))[0]], events=events))
        self.textures = [CSTR(mdl, I32(mdl, 208) + k * 64 + I32(mdl, I32(mdl, 208) + k * 64)) for k in range(I32(mdl, 204))]

    def _value(self, o, frame, scale):  # ExtractAnimValue: run-length coded shorts
        d, k = self.d, frame
        while d[o + 1] <= k:  # this run's total
            k -= d[o + 1]
            o += (d[o] + 1) * 2
            if d[o + 1] == 0:
                return 0.0
        return struct.unpack_from("<h", d, o + 2 * ((k + 1) if d[o] > k else d[o]))[0] * scale

    def _local(self, anim, frame):
        d = self.d
        pose = [(b["pos"], b["quat"]) for b in self.bones]
        o = anim["data"]
        while True:
            bone, flags, nxt = d[o], d[o + 1], struct.unpack_from("<h", d, o + 2)[0]
            b = self.bones[bone]
            p = o + 4
            pos, q = b["pos"], b["quat"]
            if flags & 0x02:      # a raw Quaternion48
                x, y, zw = struct.unpack_from("<HHH", d, p)
                x, y, z = (x - 32768) / 32768.0, (y - 32768) / 32768.0, ((zw & 0x7FFF) - 16384) / 16384.0
                w = math.sqrt(max(0.0, 1 - x * x - y * y - z * z))
                q = (x, y, z, -w if zw & 0x8000 else w)
            elif flags & 0x20:    # a raw Quaternion64
                v = struct.unpack_from("<Q", d, p)[0]
                x, y, z = ((((v >> s) & 0x1FFFFF) - 1048576) / 1048576.5 for s in (0, 21, 42))
                w = math.sqrt(max(0.0, 1 - x * x - y * y - z * z))
                q = (x, y, z, -w if v >> 63 else w)
            elif flags & 0x08:    # animated Euler angles
                offs = struct.unpack_from("<3h", d, p)
                q = euler_quat([(self._value(p + offs[i], frame, b["rotscale"][i]) if offs[i] > 0 else 0.0) + b["rot"][i] for i in range(3)])
            if flags & 0x01:      # a raw Vector48 (half floats)
                pos = tuple(float(x) for x in np.frombuffer(d, np.float16, 3, p + (6 if flags & 0x02 else 0) + (8 if flags & 0x20 else 0)))
            elif flags & 0x04:
                pp = p + (6 if flags & 0x08 else 0)
                offs = struct.unpack_from("<3h", d, pp)
                pos = tuple((self._value(pp + offs[i], frame, b["posscale"][i]) if offs[i] > 0 else 0.0) + b["pos"][i] for i in range(3))
            pose[bone] = (pos, q)
            if nxt == 0:
                return pose
            o += nxt

    def skin(self, anim, frame):
        """Each bone's skinning matrix (a reference-pose vertex to its animated place) at a frame,
        which may fall between two frames."""
        f0 = min(anim["frames"] - 1, int(math.floor(frame)))
        f1 = min(anim["frames"] - 1, f0 + 1)
        t = frame - f0
        world = []
        for b, (p0, q0), (p1, q1) in zip(self.bones, self._local(anim, f0), self._local(anim, f1)):
            q0, q1 = np.array(q0), np.array(q1)
            q = q0 * (1 - t) + (q1 if q0 @ q1 >= 0 else -q1) * t
            m = quat_matrix(q / np.linalg.norm(q), np.array(p0) * (1 - t) + np.array(p1) * t)
            world.append(m if b["parent"] < 0 else world[b["parent"]] @ m)
        return [w @ b["pose_to_bone"] for w, b in zip(world, self.bones)]


def read_mesh(mdl, vvd, vtx):
    """LOD 0 of a one-bodypart, one-model .mdl: its vertices, and its triangles (material, i0, i1, i2)."""
    if I32(vvd, 48):
        sys.exit("this .vvd has LOD fixups, which this reader doesn't apply")
    verts = np.frombuffer(vvd, np.dtype([("w", "<f4", 3), ("b", "u1", 3), ("nb", "u1"), ("pos", "<f4", 3), ("nrm", "<f4", 3), ("uv", "<f4", 2)]), I32(vvd, 16), I32(vvd, 56))
    bp = I32(mdl, 236)
    model = bp + I32(mdl, bp + 12)
    meshes = [struct.unpack_from("<iiii", mdl, model + I32(mdl, model + 76) + j * 116) for j in range(I32(mdl, model + 72))]  # material, model, numverts, vertexoffset
    tris = []
    vbp = I32(vtx, 32)
    vmodel = vbp + I32(vtx, vbp + 4)
    vlod = vmodel + I32(vtx, vmodel + 4)
    for j in range(I32(vtx, vlod)):
        mh = vlod + I32(vtx, vlod + 4) + j * 9
        mat, _, _, voff = meshes[j]
        for g in range(I32(vtx, mh)):
            sg = mh + I32(vtx, mh + 4) + g * 25
            nv, vo, ni, io = struct.unpack_from("<iiii", vtx, sg)
            ids = [struct.unpack_from("<H", vtx, sg + vo + k * 9 + 4)[0] for k in range(nv)]
            idx = struct.unpack_from(f"<{ni}H", vtx, sg + io)
            for t in range(0, ni - 2, 3):
                tris.append((mat, *(voff + ids[idx[t + k]] for k in range(3))))
    return verts, np.array(tris)


def rot_axis(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return np.array({"x": [[1, 0, 0], [0, c, -s], [0, s, c]], "y": [[c, 0, s], [0, 1, 0], [-s, 0, c]], "z": [[c, -s, 0], [s, c, 0], [0, 0, 1]]}[axis])


def moved_display(entry, m):
    """A display transform that also applies the rigid motion `m` (4x4, in model pixels) to the model:
    Minecraft places a vertex v at T + R S (v - 8), so v moved to Q v + t is placed by R' = R Q,
    T' = T + R S (Q 8 + t - 8)."""
    r = rot_axis("x", entry["rotation"][0]) @ rot_axis("y", entry["rotation"][1]) @ rot_axis("z", entry["rotation"][2])
    q, t, c = m[:3, :3], m[:3, 3], np.full(3, 8.0)
    r2 = r @ q
    tr = np.array(entry["translation"], float) + r @ (np.array(entry["scale"]) * (q @ c + t - c))
    rot = [math.degrees(math.atan2(-r2[1, 2], r2[2, 2])), math.degrees(math.asin(max(-1.0, min(1.0, r2[0, 2])))), math.degrees(math.atan2(-r2[0, 1], r2[0, 0]))]
    return {"rotation": [round(x, 3) for x in rot], "translation": [round(float(x), 4) for x in tr], "scale": entry["scale"]}


def recoil_frame(entry, m):
    """The recoil `m` (a rigid motion of the model, in model pixels) as what the mod applies just
    before the item's own display transform `entry` (D: translate, rotate, scale): a translation t
    (blocks) and then a rotation r (degrees, a display rotation's XYZ convention), such that
    T(t) R(r) D places the model as moved_display(entry, m) does. With D = T(d) R S: R(r) = R Q R^-1
    and t = d' - R(r) d, where d' is moved_display's translation."""
    r = rot_axis("x", entry["rotation"][0]) @ rot_axis("y", entry["rotation"][1]) @ rot_axis("z", entry["rotation"][2])
    q, c = m[:3, :3], np.full(3, 8.0)
    rr = r @ q @ r.T
    d = np.array(entry["translation"], float)
    d2 = d + r @ (np.array(entry["scale"]) * (q @ c + m[:3, 3] - c))
    t = (d2 - rr @ d) / 16.0
    rot = [math.degrees(math.atan2(-rr[1, 2], rr[2, 2])), math.degrees(math.asin(max(-1.0, min(1.0, rr[0, 2])))), math.degrees(math.atan2(-rr[0, 1], rr[0, 0]))]
    return {"t": [round(float(x), 5) for x in t], "r": [round(x, 3) for x in rot]}


def display_matrix(entry, before=None):
    """A display transform as a 4x4 (blocks), after an optional recoil_frame()."""
    def trs(t, rot, scale):
        m = np.eye(4)
        m[:3, :3] = (rot_axis("x", rot[0]) @ rot_axis("y", rot[1]) @ rot_axis("z", rot[2])) * np.array(scale)
        m[:3, 3] = t
        return m
    d = trs(np.array(entry["translation"]) / 16.0, entry["rotation"], entry["scale"])
    return d if before is None else trs(before["t"], before["r"], [1, 1, 1]) @ d


def scaled_motion(m, k):
    """The rigid motion `m`, `k` times as far (its turn and its shift both)."""
    if k == 1.0:
        return m
    out = np.eye(4)
    ang = math.acos(max(-1.0, min(1.0, (np.trace(m[:3, :3]) - 1) / 2)))
    if ang > 1e-6:
        ax = np.array([m[2, 1] - m[1, 2], m[0, 2] - m[2, 0], m[1, 0] - m[0, 1]]) / (2 * math.sin(ang))
        x, y, z = ax * math.sin(ang * k / 2)
        out = quat_matrix((x, y, z, math.cos(ang * k / 2)))
    out[:3, 3] = m[:3, 3] * k
    return out


class Gun:
    """The viewmodel's gun (not Chell's hands), its textures, and how it sits in a Minecraft model."""

    def __init__(self, a):
        v = VPK(os.path.join(a.portal, "portal", "portal_pak_dir.vpk"))
        mdl = v.read("models/weapons/v_portalgun.mdl")
        self.model = Model(mdl)
        self.verts, tris = read_mesh(mdl, v.read("models/weapons/v_portalgun.vvd"), v.read("models/weapons/v_portalgun.dx90.vtx"))
        names = self.model.textures
        self.tris = tris[np.array([names[t] != "v_hands" for t in tris[:, 0]])]   # the gun; the arms and hands stay behind
        self.glass_mat = [i for i, n in enumerate(names) if n.endswith("glass")][0]
        self.base = vtf(v.read(MATERIALS + "v_portalgun.vtf")).astype(float)
        self.exponent = vtf(v.read(MATERIALS + "v_portalgun_exponent.vtf")).astype(float)       # red: the highlight's tightness
        self.phong = vtf(v.read(MATERIALS + "v_portalgun_normal.vtf")).astype(float)[..., 3]    # the bump map's alpha masks the highlight
        self.glass = vtf(v.read(MATERIALS + "v_portalgun_glass.vtf")).astype(float)
        self.voxel = a.voxel
        self.coarse = int(round(getattr(a, "coarse", 0) / a.voxel)) if getattr(a, "coarse", 0) else 0   # fine voxels a side of a coarse one
        self.spill, self.lights, self.halo = getattr(a, "spill", False), getattr(a, "lights", False), getattr(a, "halo", False)
        self.spill_near = getattr(a, "spill_near", None) or SPILL_NEAR
        self.spill_far = getattr(a, "spill_far", None) or SPILL_FAR
        self.attach = {}
        for k in range(I32(mdl, 240)):
            o = I32(mdl, 244) + k * 92
            self.attach[CSTR(mdl, o + I32(mdl, o))] = (I32(mdl, o + 8), np.vstack([np.array(struct.unpack_from("<12f", mdl, o + 12)).reshape(3, 4), [0, 0, 0, 1]]))
        self.anims = {x["name"]: x for x in self.model.anims}
        self.idle = self.model.skin(self.anims["@idle"], 0)
        self.root = [i for i, b in enumerate(self.model.bones) if b["name"].endswith(".Base")][0]
        self.cover = [i for i, b in enumerate(self.model.bones) if b["name"].endswith(".Front_Cover")][0]
        # The viewmodel's reference pose lies along Source y: the muzzle at -y, the white casing at
        # +y, z up, so -x is its right. Minecraft model axes: x right, y up, z back (south); the
        # muzzle points north (-z). Placed as the 0.5 voxel gun is: centred on x 8, sitting from y 1,
        # its rear at z 16, --length pixels long.
        axes = np.array([[-1.0, 0, 0], [0, 0, 1], [0, 1, 0]])
        pts = self.verts["pos"][np.unique(self.tris[:, 1:])] @ axes.T
        lo, hi = pts.min(0), pts.max(0)
        scale = a.length / (hi[2] - lo[2])
        self.place = np.eye(4)
        self.place[:3, :3] = axes * scale
        self.place[:3, 3] = -(lo + hi) / 2 * scale + np.array([8.0, 1.0 + (hi[1] - lo[1]) * scale / 2, 16.0 - (hi[2] - lo[2]) * scale / 2])
        self.scale = scale

    def attachment(self, name, rel):
        """Where an attachment is, in model pixels, in a pose (see relative())."""
        bone, local = self.attach[name]
        return (self.place @ rel[bone] @ np.linalg.inv(self.model.bones[bone]["pose_to_bone"]) @ local)[:3, 3]

    def relative(self, skin):
        """Each bone's motion relative to the gun's body, in the reference pose's space."""
        inv = np.linalg.inv(skin[self.root])
        return [inv @ s for s in skin]

    def in_pixels(self, m):
        """A motion in the reference pose's space, as one in model pixels."""
        return self.place @ m @ np.linalg.inv(self.place)

    def posed(self, rel):
        """Every vertex's position and normal in model pixels, skinned by `rel` (see relative())."""
        mats = np.array([self.place @ m for m in rel])
        pos = np.zeros((len(self.verts), 3))
        nrm = np.zeros((len(self.verts), 3))
        hp = np.concatenate([self.verts["pos"], np.ones((len(self.verts), 1))], 1)
        for k in range(3):
            w = np.where(self.verts["nb"] > k, self.verts["w"][:, k], 0.0)[:, None]
            m = mats[self.verts["b"][:, k]]
            pos += w * np.einsum("nij,nj->ni", m[:, :3, :], hp)
            nrm += w * np.einsum("nij,nj->ni", m[:, :3, :3], self.verts["nrm"])
        return pos, nrm / np.maximum(1e-9, np.linalg.norm(nrm, axis=1))[:, None]

    def voxelize(self, tris, pos, nrm):
        """Samples triangles into voxels: {cell: class, colour, normal, exponent, phong mask, alpha}."""
        vox = self.voxel
        P, N, U = pos[tris[:, 1:]], nrm[tris[:, 1:]], self.verts["uv"][tris[:, 1:]]
        edge = np.max([np.linalg.norm(P[:, i] - P[:, j], axis=1) for i, j in ((0, 1), (0, 2), (1, 2))], 0)
        steps = np.maximum(2, np.ceil(edge * 3 / vox)).astype(int)
        Q, NN, UU, G = [], [], [], []
        for n in np.unique(steps):
            sel = steps == n
            i, j = np.meshgrid(np.arange(n + 1), np.arange(n + 1), indexing="ij")
            keep = i + j <= n
            B = np.stack([i[keep] / n, j[keep] / n, 1 - (i[keep] + j[keep]) / n], 1)
            Q.append(np.einsum("mk,tkc->tmc", B, P[sel]).reshape(-1, 3))
            NN.append(np.einsum("mk,tkc->tmc", B, N[sel]).reshape(-1, 3))
            UU.append(np.einsum("mk,tkc->tmc", B, U[sel]).reshape(-1, 2))
            G.append(np.repeat(tris[sel, 0] == self.glass_mat, len(B)))
        Q, NN, UU, G = np.concatenate(Q), np.concatenate(NN), np.concatenate(UU) % 1.0, np.concatenate(G)

        def texel(tex, uv):
            h, w = tex.shape[:2]
            return tex[np.minimum(h - 1, (uv[:, 1] * h).astype(int)), np.minimum(w - 1, (uv[:, 0] * w).astype(int))]

        col = texel(self.base, UU)[:, :3]
        ex = texel(self.exponent, UU)[:, 0] / 255.0
        ph = texel(self.phong, UU) / 255.0
        lum = col.mean(1)
        cls = np.where(lum >= 110, SHELL, np.where(ph >= 0.2, METAL, BLACK))
        gl = texel(self.glass, UU)
        cls[G] = GLASS
        col[G] = gl[G, :3]
        alpha = np.where(G, gl[:, 3], 255.0)
        cell = np.floor(Q / vox).astype(np.int64) + 2048
        key = ((cell[:, 0] << 24 | cell[:, 1] << 12 | cell[:, 2]) << 3) | cls
        uk, inv = np.unique(key, return_inverse=True)
        cnt = np.bincount(inv)
        mean = lambda x: np.bincount(inv, x, len(uk)) / cnt
        data = np.stack([mean(col[:, 0]), mean(col[:, 1]), mean(col[:, 2]), mean(NN[:, 0]), mean(NN[:, 1]), mean(NN[:, 2]), mean(ex), mean(ph), mean(alpha)], 1)
        # A cell crossed by several materials takes the one with the most samples (the glass only
        # where nothing else is).
        ucell, ucls = uk >> 3, uk & 7
        order = np.lexsort((np.where(ucls == GLASS, 0, cnt), ucell))
        last = np.r_[ucell[order][1:] != ucell[order][:-1], True]
        pick = order[last]
        cells = np.stack([ucell[pick] >> 24, (ucell[pick] >> 12) & 4095, ucell[pick] & 4095], 1) - 2048
        return {"cell": cells, "cls": ucls[pick], "data": data[pick]}

    def tube(self, pos):
        """The glass tube in a pose: the two ends of its axis, and its radius."""
        gv = np.unique(self.tris[self.tris[:, 0] == self.glass_mat][:, 1:])
        ends = [pos[gv[self.verts["b"][gv, 0] == b]] for b in np.unique(self.verts["b"][gv, 0])]
        return ends[0].mean(0), ends[1].mean(0), np.mean([np.linalg.norm(e - e.mean(0), axis=1).mean() for e in ends])

    def coarsen(self, cell, cls, d):
        """Turns the painted shell into coarse voxels: a coarse cell (self.coarse fine ones a side)
        that the shell's paint crosses, and mostly it, becomes one voxel of the shell (written as
        its fine cells, all alike, which the box merging joins). Returns the cells, their classes
        and data, the cell each takes its speckle from, and their centres in fine cells."""
        r = self.coarse
        blk = np.floor_divide(cell, r)
        uk, inv = np.unique((blk + 2048) @ np.array([1 << 24, 1 << 12, 1]), return_inverse=True)
        shell = cls == SHELL
        n_shell = np.bincount(inv, shell, len(uk))
        n_other = np.bincount(inv, ~shell & (cls != GLASS), len(uk))
        big = (n_shell >= 1) & (n_shell >= n_other)
        avg = np.stack([np.bincount(inv, d[:, j] * shell, len(uk)) / np.maximum(1, n_shell) for j in range(d.shape[1])], 1)[big]
        first = np.zeros(len(uk), int)
        first[inv] = np.arange(len(inv))
        bb = blk[first[big]]
        off = np.stack(np.meshgrid(*[np.arange(r)] * 3, indexing="ij"), -1).reshape(-1, 3)
        keep = ~big[inv]
        ccell = (bb[:, None, :] * r + off[None]).reshape(-1, 3)
        n = len(off)
        return (np.concatenate([cell[keep], ccell]), np.concatenate([cls[keep], np.full(len(ccell), SHELL)]), np.concatenate([d[keep], np.repeat(avg, n, 0)]),
                np.concatenate([cell[keep], np.repeat(bb * r, n, 0)]), np.concatenate([cell[keep] + 0.5, np.repeat(bb * r + r / 2, n, 0)]))

    def light_core(self, pos, vox, taken):
        """The glowing core inside the glass tube: the cells near its axis that nothing else fills."""
        a, b, radius = self.tube(pos)
        lo = np.floor((np.minimum(a, b) - radius) / vox).astype(int)
        hi = np.ceil((np.maximum(a, b) + radius) / vox).astype(int)
        g = np.stack(np.meshgrid(*[np.arange(lo[i], hi[i] + 1) for i in range(3)], indexing="ij"), -1).reshape(-1, 3)
        c = (g + 0.5) * vox
        t = (c - a) @ (b - a) / ((b - a) @ (b - a))
        dist = np.linalg.norm(c - (a + np.outer(t, b - a)), axis=1)
        inside = (t > 0.03) & (t < 0.97) & (dist < radius * 0.45)
        return np.array([tuple(x) for x in g[inside] if tuple(x) not in taken], int).reshape(-1, 3)

    # The model's attachments where Valve's gun glows: each claw's tip, and the light on the shell.
    LIGHTS = {"Arm1_attach3": 5, "Arm2_attach3": 5, "Arm3_attach3": 5, "Body_light": 8}

    def part(self, tris, rel):
        """One piece of the gun in a pose: its voxels' cells, classes and shaded colours."""
        pos, nrm = self.posed(rel)
        v = self.voxelize(tris, pos, nrm)
        cell, cls, d = v["cell"], v["cls"], v["data"]
        speck, centre = cell, cell + 0.5
        if self.coarse:
            cell, cls, d, speck, centre = self.coarsen(cell, cls, d)
        rgb, alpha, normal = shade(speck, cls, d), d[:, 8], d[:, 3:6]
        if (tris[:, 0] == self.glass_mat).any():
            core = self.light_core(pos, self.voxel, {tuple(c) for c in cell[cls != GLASS]})
            keep = ~np.isin(cell @ np.array([1 << 24, 1 << 12, 1]), core @ np.array([1 << 24, 1 << 12, 1])) if len(core) else np.ones(len(cell), bool)
            cell, cls, rgb, alpha, normal, centre = cell[keep], cls[keep], rgb[keep], alpha[keep], normal[keep], centre[keep]
            cell = np.concatenate([cell, core])
            cls = np.concatenate([cls, np.full(len(core), LIGHT)])
            rgb = np.concatenate([rgb, np.full((len(core), 3), 255.0)])
            alpha = np.concatenate([alpha, np.full(len(core), 255.0)])
            normal = np.concatenate([normal, np.zeros((len(core), 3))])
            centre = np.concatenate([centre, core + 0.5])
        centre = centre * self.voxel
        zone = np.zeros(len(cls), int)
        if self.lights:   # the nearest few voxels of the surface to each attachment become a light
            bones = set(np.unique(self.verts["b"][tris[:, 1:], 0]).tolist())
            for name, count in self.LIGHTS.items():
                if self.attach[name][0] not in bones:
                    continue
                dist = np.where((cls != GLASS) & (cls != LIGHT), np.linalg.norm((cell + 0.5) * self.voxel - self.attachment(name, rel), axis=1), 1e9)
                near = np.argsort(dist, kind="stable")[:count]
                near = near[dist[near] < 1.5]
                cls[near], rgb[near] = LIGHT, 255.0
        if self.spill:
            # How strongly the glow reaches a voxel: from the nearest point of the tube's axis, or
            # from the muzzle, falling off with distance and with facing away (half-Lambert on
            # the mean normal, which is short where a thin part's two sides cancel: neutral).
            a, b, _ = self.tube(pos)
            t = np.clip((centre - a) @ (b - a) / ((b - a) @ (b - a)), 0, 1)
            reach = np.zeros(len(cls))
            for src, size in ((a + np.outer(t, b - a), SPILL_TUBE), (np.tile(self.attachment("muzzle", rel), (len(cls), 1)), SPILL_MUZZLE)):
                to = src - centre
                dist = np.maximum(1e-6, np.linalg.norm(to, axis=1))
                facing = 0.5 + 0.5 * np.einsum("ij,ij->i", normal, to) / dist
                reach = np.maximum(reach, facing / (1 + (dist / size) ** 2))
            dark = (cls == METAL) | (cls == BLACK) | (cls == SHELL)
            zone[dark & (reach > self.spill_far)] = 2
            zone[dark & (reach > self.spill_near)] = 1
        if self.halo:
            # Two shells of empty cells around everything that glows (the core, the glass, the
            # small lights): within HALO[0] fine voxels, and from there to HALO[1].
            pack = lambda c: (c + 2048) @ np.array([1 << 24, 1 << 12, 1])
            glow = cell[(cls == LIGHT) | (cls == GLASS)]
            r = int(math.ceil(HALO[1]))
            off = np.stack(np.meshgrid(*[np.arange(-r, r + 1)] * 3, indexing="ij"), -1).reshape(-1, 3)
            dist = np.linalg.norm(off, axis=1)
            taken = pack(cell)
            for kind, lo, hi in ((HALO1, 0.0, HALO[0]), (HALO2, HALO[0], HALO[1])):
                ring = off[(dist > lo) & (dist <= hi)]
                cand = np.unique((glow[:, None, :] + ring[None]).reshape(-1, 3), axis=0)
                cand = cand[~np.isin(pack(cand), taken)]
                taken = np.concatenate([taken, pack(cand)])
                cell = np.concatenate([cell, cand])
                cls = np.concatenate([cls, np.full(len(cand), kind)])
                rgb = np.concatenate([rgb, np.full((len(cand), 3), 255.0)])
                alpha = np.concatenate([alpha, np.full(len(cand), 255.0)])
                zone = np.concatenate([zone, np.zeros(len(cand), int)])
        return {"cell": cell, "cls": cls, "rgb": rgb, "alpha": alpha, "zone": zone}


# The glow's spill onto the gun (--spill): how far it carries from the tube and from the muzzle
# (model pixels), and how much of it makes the near and the far zone.
SPILL_TUBE, SPILL_MUZZLE, SPILL_NEAR, SPILL_FAR = 3.4, 4.6, 0.34, 0.20
# The halos (--halo): how far the inner and the outer shell reach from a glowing voxel, in fine
# voxels, and how opaque each is.
HALO, HALO_ALPHA = (1.0, 2.0), (50, 22)
V2_NEAR, V2_FAR = 0.22, 0.13   # --v2's spill zones

# Where the baked light comes from and where the eye is, in model space (x right, y up, z towards
# the holder): the gun is seen from behind, above and its left in the first-person right hand.
LIGHT_DIR = np.array([-0.35, 0.80, 0.50]) / np.linalg.norm([-0.35, 0.80, 0.50])
EYE_DIR = np.array([-0.45, 0.40, 0.80]) / np.linalg.norm([-0.45, 0.40, 0.80])
HALF = (LIGHT_DIR + EYE_DIR) / np.linalg.norm(LIGHT_DIR + EYE_DIR)
STEPS = 5   # brightness steps of the baked shading


def shade(cell, cls, d):
    """Bakes each voxel's colour: its texture colour, a stepped diffuse term from its true surface
    normal (voxel faces only point six ways, so this is what keeps the curves), and a highlight
    as Source's phong would give for one fixed light and eye (exponent map, phong mask, boost 3)."""
    base, n = d[:, :3], d[:, 3:6]
    n = n / np.maximum(1e-9, np.linalg.norm(n, axis=1))[:, None]
    ex, ph = 1 + 149 * d[:, 6], d[:, 7]
    diff = 0.5 + 0.5 * (n @ LIGHT_DIR)
    diff = np.round(diff * (STEPS - 1)) / (STEPS - 1)
    spec = ph * 3.0 * np.maximum(0.0, n @ HALF) ** ex
    h = ((cell[:, 0] * 73856093) ^ (cell[:, 1] * 19349663) ^ (cell[:, 2] * 83492791)) & 0xFFFF
    noise = h / 65535.0
    out = np.zeros_like(base)
    s = cls == SHELL    # glossy paint: gentle shading, a small tight highlight
    out[s] = base[s] * (0.80 + 0.24 * diff[s])[:, None] + (60 * np.minimum(1.0, spec[s]))[:, None]
    m = cls == METAL    # metal: lifted out of the black, strong shading, a broad bright highlight, speckle
    sheen = np.minimum(1.0, 0.55 * ph[m] * 3.0 * np.maximum(0.0, n[m] @ HALF) ** np.minimum(ex[m], 14.0))
    sheen = np.round(sheen * 4) / 4
    speck = np.where(noise[m] > 0.93, 38.0, np.where(noise[m] < 0.10, -10.0, 0.0))
    out[m] = (base[m] * 1.15 + 12) * (0.45 + 0.75 * diff[m])[:, None] + (125 * sheen + speck * (0.4 + diff[m]))[:, None] * np.array([0.94, 0.97, 1.0])
    b = cls == BLACK    # matte: nearly flat
    out[b] = (base[b] + 6) * (0.70 + 0.40 * diff[b])[:, None] + (25 * np.minimum(1.0, spec[b]))[:, None]
    g = cls == GLASS    # pale, and more opaque where it turns away from the eye (set in the palette)
    out[g] = 225.0
    return np.clip(out, 0, 255)


def glass_alpha(d):
    n = d[:, 3:6] / np.maximum(1e-9, np.linalg.norm(d[:, 3:6], axis=1))[:, None]
    return 1 - np.abs(n @ EYE_DIR)   # 0 facing the eye, 1 edge on


class Palette:
    """The palette sheet: 16 entries a row, each 4x4 texels (so mipmaps don't bleed), per class."""
    COUNTS = {SHELL: 28, METAL: 44, BLACK: 20}
    GLASS_ALPHA = [70, 105, 140]

    def __init__(self, parts, halo=False, shell_tint=False, gain=2):
        self.colours, self.start, self.doubled, self.shell_tint, self.gain = [], {}, {}, shell_tint, gain
        for c, k in self.COUNTS.items():
            rgb = np.concatenate([p["rgb"][p["cls"] == c] for p in parts])
            q = Image.fromarray(rgb.astype(np.uint8).reshape(-1, 1, 3)).quantize(colors=k, method=Image.Quantize.MEDIANCUT)
            pal = np.array(q.getpalette()[:k * 3]).reshape(-1, 3)
            pal = pal[np.unique(np.array(q.get_flattened_data() if hasattr(q, "get_flattened_data") else q.getdata()))]
            self.start[c] = (len(self.colours), len(pal))
            self.colours += [(*map(int, x), 255) for x in pal]
        self.start[GLASS] = (len(self.colours), len(self.GLASS_ALPHA))
        self.colours += [(235, 245, 255, x) for x in self.GLASS_ALPHA]
        self.start[LIGHT] = (len(self.colours), 1)
        self.colours.append((255, 255, 255, 255))
        if halo:
            for c, x in zip((HALO1, HALO2), HALO_ALPHA):
                self.start[c] = (len(self.colours), 1)
                self.colours.append((255, 255, 255, x))

    def index(self, part):
        idx = np.zeros(len(part["cls"]), int)
        for c in self.COUNTS:
            s = part["cls"] == c
            o, k = self.start[c]
            pal = np.array(self.colours[o:o + k], float)[:, :3]
            idx[s] = o + np.argmin(((part["rgb"][s][:, None, :] - pal[None]) ** 2).sum(-1), 1) if s.any() else 0
        g = part["cls"] == GLASS
        idx[g] = self.start[GLASS][0] + np.minimum(len(self.GLASS_ALPHA) - 1, (part["edge"][g] * len(self.GLASS_ALPHA)).astype(int))
        idx[part["cls"] == LIGHT] = self.start[LIGHT][0]
        for c in (HALO1, HALO2):
            if c in self.start:
                idx[part["cls"] == c] = self.start[c][0]
        return idx

    def resolve(self, part):
        """Each voxel's palette entry and tint index (-1: none; 0: the light; 1, 2: the glow's near
        and far spill). A spill voxel takes an entry at double brightness, which the tint's default
        (a half) brings back to what it was; one too bright to double stays untinted."""
        idx = self.index(part)
        tint = np.where(idx >= self.start[GLASS][0], 0, -1)
        for i in np.nonzero((part["zone"] > 0) & (tint < 0))[0]:
            k = int(idx[i])
            c = self.colours[k]
            if max(c[:3]) > 255 // self.gain:
                if self.shell_tint:   # too bright to double: tinted at its own colour (white by default)
                    tint[i] = 3
                continue
            if k not in self.doubled:
                self.doubled[k] = len(self.colours)
                self.colours.append((c[0] * self.gain, c[1] * self.gain, c[2] * self.gain, 255))
            idx[i], tint[i] = self.doubled[k], part["zone"][i]
        return idx, tint

    def see_through(self, k):
        return self.layer(k) > 0

    def layer(self, k):
        """0: opaque; 1: the glass; 2, 3: the inner and the outer halo (each drawn over the last)."""
        o, n = self.start[GLASS]
        if o <= k < o + n:
            return 1
        return 2 if k == self.start.get(HALO1, (-1,))[0] else 3 if k == self.start.get(HALO2, (-1,))[0] else 0

    def save(self, path):
        sheet = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        for i, c in enumerate(self.colours):
            sheet.paste(c, (i % 16 * 4, i // 16 * 4, i % 16 * 4 + 4, i // 16 * 4 + 4))
        sheet.save(path)


def elements(parts, palette, vox, grow):
    """Boxes for voxels: only the faces that can be seen from outside, same-palette voxels merged
    (x runs, then rows in y, then slabs in z). The see-through ones come last, so they are drawn
    over what is behind them."""
    cells = {}   # cell: (palette entry, tint index)
    for p in parts:
        for c, k, t in zip(map(tuple, p["cell"]), *palette.resolve(p)):
            if c not in cells or palette.see_through(cells[c][0]):
                cells[c] = (int(k), int(t))
    solid = {c for c, k in cells.items() if not palette.see_through(k[0])}
    arr = np.array(list(cells))
    lo, hi = arr.min(0) - 1, arr.max(0) + 1
    outside, todo = {tuple(lo)}, deque([tuple(lo)])
    while todo:   # everything the air outside reaches (through the glass too)
        c = todo.popleft()
        for _, ax, s in DIRS:
            n = list(c)
            n[ax] += s
            n = tuple(n)
            if lo[ax] <= n[ax] <= hi[ax] and n not in outside and n not in solid:
                outside.add(n)
                todo.append(n)
    seen = {}
    for c, k in cells.items():
        m = 0
        for i, (_, ax, s) in enumerate(DIRS):
            n = list(c)
            n[ax] += s
            n = tuple(n)
            if palette.see_through(k[0]):   # towards the air, or towards a layer drawn over this one
                show = n not in cells or palette.layer(cells[n][0]) > palette.layer(k[0])
            else:
                show = n in outside
            m |= show << i
        if m:
            seen[c] = m
    remaining = {c: cells[c] for c in seen}
    out, glass, faces = [], {1: [], 2: [], 3: []}, 0
    for c in sorted(seen):
        if c not in remaining:
            continue
        k = remaining[c]
        x0, y0, z0 = c
        x1 = x0
        while remaining.get((x1 + 1, y0, z0)) == k:
            x1 += 1
        y1 = y0
        while all(remaining.get((x, y1 + 1, z0)) == k for x in range(x0, x1 + 1)):
            y1 += 1
        z1 = z0
        while all(remaining.get((x, y, z1 + 1)) == k for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)):
            z1 += 1
        mask = 0
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    del remaining[(x, y, z)]
                    m = seen[(x, y, z)]   # a face shows if any of the voxels along that side shows it
                    edge = (x == x0) | (x == x1) << 1 | (y == y0) << 2 | (y == y1) << 3 | (z == z0) << 4 | (z == z1) << 5
                    mask |= m & edge
        k, tint = k
        u, v = k % 16, k // 16
        face = {"uv": [u + 0.25, v + 0.25, u + 0.75, v + 0.75], "texture": "#gun"}
        if tint >= 0:
            face["tintindex"] = tint
        g = 0.0 if palette.see_through(k) else grow   # a hair's overlap hides the cracks between boxes
        e = {"from": [round(x * vox - g, 4) for x in (x0, y0, z0)], "to": [round(x * vox + g, 4) for x in (x1 + 1, y1 + 1, z1 + 1)],
             "faces": {name: face for i, (name, _, _) in enumerate(DIRS) if mask >> i & 1}}
        if tint == 0:
            e["light_emission"] = 15
        faces += len(e["faces"])
        (glass[palette.layer(k)] if palette.see_through(k) else out).append(e)
    return out + glass[1] + glass[2] + glass[3], faces


def info(gun):
    m = gun.model
    print("sequences of models/weapons/v_portalgun.mdl:")
    for s in m.seqs:
        a = s["anim"]
        print(f"  {s['name']:10s} {s['activity']:24s} {a['frames']:3d} frames at {a['fps']:g} fps = {(a['frames'] - 1) / a['fps']:.3f} s{'  (loops)' if s['flags'] & 1 else ''}"
              + "".join(f"  event at {c:g}: {o}" for c, o in s["events"]))
    names = [b["name"].split(".")[-1] for b in m.bones]
    angle = lambda r: math.degrees(math.acos(max(-1.0, min(1.0, (np.trace(r[:3, :3]) - 1) / 2))))
    for seq in ("@fire1", "@fizzle"):
        a = gun.anims[seq]
        print(f"{seq}: per frame, the body's motion in its own space (model pixels: x right, y up, z back; {gun.scale:.3f} px per Source unit), then parts relative to the body")
        for f in range(a["frames"]):
            skin = m.skin(a, f)
            rec = gun.in_pixels(np.linalg.inv(gun.idle[gun.root]) @ skin[gun.root])
            rel = gun.relative(skin)
            d = moved_display({"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}, rec)
            line = f"  f{f:2d} body shift {np.round(rec[:3, 3] + rec[:3, :3] @ np.full(3, 8.0) - 8, 2)} turn x/y/z {d['rotation']} ({angle(rec):.1f} deg)"
            for nm in ("Front_Cover", "Arm1_B", "Arm1_C", "Wire1_B"):
                r = gun.in_pixels(rel[names.index(nm)])
                parent = gun.in_pixels(rel[m.bones[names.index(nm)]["parent"]])
                line += f" | {nm} {angle(np.linalg.inv(parent) @ r):.1f} deg" if nm != "Front_Cover" else f" | {nm} slide z {r[2, 3]:+.2f} px, {angle(r):.1f} deg"
            print(line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--portal", default="D:/SteamLibrary/steamapps/common/Portal")
    ap.add_argument("--length", type=float, default=26.0, help="the gun's length in model pixels")
    ap.add_argument("--voxel", type=float, default=0.25, help="voxel size in model pixels (1 = vanilla's pixel)")
    ap.add_argument("--pack", help="the resource pack to write (run/resourcepacks/portalcraft-voxel-gun-micro, or -hybrid with --hybrid)")
    ap.add_argument("--hybrid", action="store_true", help="the hybrid pack: --coarse 0.5 --spill --lights")
    ap.add_argument("--coarse", type=float, default=0.0, help="the painted shell's voxel size (a whole multiple of --voxel; 0: all fine)")
    ap.add_argument("--spill", action="store_true", help="tint the dark surfaces the glow would light (tintindex 1 and 2)")
    ap.add_argument("--lights", action="store_true", help="small lights at the claw tips and on the shell (tintindex 0)")
    ap.add_argument("--v2", action="store_true", help="the hybrid v2 pack: --hybrid --halo --shell-tint --per-frame --grab --spill-near 0.27")
    ap.add_argument("--grab", action="store_true", help="with --per-frame: the pickup and release sequences' poses too (fire_frame 101.., 201..)")
    ap.add_argument("--gain", type=int, default=2, help="how many times their brightness the spill zones' colours are baked at (their unlit tint is 1/gain)")
    ap.add_argument("--glow-tints", action="store_true", help="tints 1..3 in the item definition are the mod's portalcraft:gun_glow, not custom model data colours")
    ap.add_argument("--spill-far", type=float, help=f"the far spill zone's threshold ({SPILL_FAR})")
    ap.add_argument("--halo", action="store_true", help="see-through glowing shells around the lights")
    ap.add_argument("--shell-tint", action="store_true", help="tintindex 3 on the white shell in the glow's reach")
    ap.add_argument("--spill-near", type=float, help=f"the near spill zone's threshold ({SPILL_NEAR})")
    ap.add_argument("--per-frame", action="store_true", help="a pose per frame of fire1 without recoil, picked by portalcraft:fire_frame; the recoil as gun_recoil.json")
    ap.add_argument("--recoil", type=float, default=1.0, help="how much of Portal's recoil (the whole gun's kick) the keyframes take")
    ap.add_argument("--ticks", type=int, default=9, help="keyframes of the firing animation, one per game tick")
    ap.add_argument("--no-keyframes", action="store_true", help="only the gun at rest")
    ap.add_argument("--info", action="store_true", help="print the model's sequences and what moves when it fires, and stop")
    a = ap.parse_args()
    if a.v2:
        a.hybrid = a.halo = a.shell_tint = a.per_frame = a.grab = a.glow_tints = True
        a.spill_near, a.spill_far, a.gain = a.spill_near or V2_NEAR, a.spill_far or V2_FAR, 4
    if a.hybrid:
        a.coarse, a.spill, a.lights = a.coarse or 0.5, True, True
    a.pack = a.pack or os.path.join(ROOT, "run", "resourcepacks", "portalcraft-voxel-gun-hybrid-v2" if a.v2 else "portalcraft-voxel-gun-hybrid" if a.hybrid else "portalcraft-voxel-gun-micro")
    gun = Gun(a)
    if a.info:
        info(gun)
        return
    m, bones = gun.model, range(len(gun.model.bones))
    fire = gun.anims["@fire1"]
    print(f"{len(gun.tris)} triangles of the gun ({', '.join(m.textures)}); {gun.scale:.3f} model pixels per Source unit")

    # Which bones only ride along: with the body, or with the front cover (it slides straight
    # back). The rest (the claws' outer joints, the wires, and the glass between them) bend.
    rels = [gun.relative(m.skin(fire, f)) for f in range(fire["frames"])]
    still = {b for b in bones if all(np.abs(r[b] - np.eye(4)).max() < 1e-3 for r in rels)}
    slides = {b for b in bones if b not in still and all(np.abs(r[b] - r[gun.cover]).max() < 1e-3 for r in rels)}
    vb = [set(gun.verts["b"][i, :gun.verts["nb"][i]].tolist()) for i in range(len(gun.verts))]
    kind = np.array([0 if all(vb[i] <= still for i in t[1:]) else 1 if all(vb[i] <= slides for i in t[1:]) else 2 for t in gun.tris])
    kind[gun.tris[:, 0] == gun.glass_mat] = 2
    print(f"triangles: {(kind == 0).sum()} of the body, {(kind == 1).sum()} riding the front cover, {(kind == 2).sum()} that bend (claws, wires, glass)")

    rest = gun.relative(gun.idle)
    parts = {"rest": gun.part(gun.tris, rest)}
    ticks = [] if a.no_keyframes else list(range(1, a.ticks + 1))
    frame = lambda t: t * fire["fps"] / 20.0
    if a.per_frame:   # pose N is frame N - 1
        ticks, frame = list(range(1, fire["frames"] + 1)), lambda t: float(t - 1)
    if ticks:
        parts["body"] = gun.part(gun.tris[kind == 0], rest)
        parts["cover"] = gun.part(gun.tris[kind == 1], rest)
        for t in ticks:
            parts[f"bend_{t}"] = gun.part(gun.tris[kind == 2], gun.relative(m.skin(fire, frame(t))))
    for p in parts.values():
        glass_edge(p)
    palette = Palette(list(parts.values()), a.halo, a.shell_tint, a.gain)
    counts = np.bincount(parts["rest"]["cls"], minlength=5)
    print(f"{len(parts['rest']['cls'])} voxels at rest: " + ", ".join(f"{counts[c]} {CLASS_NAMES[c]}" for c in range(5)) + f"; {len(palette.colours)} palette entries")

    assets = os.path.join(a.pack, "assets", "portalcraft")
    os.makedirs(os.path.join(assets, "textures", "item"), exist_ok=True)
    os.makedirs(os.path.join(assets, "models", "item", "portal_gun_fire"), exist_ok=True)
    os.makedirs(os.path.join(a.pack, "proposed", "items"), exist_ok=True)
    with open(os.path.join(a.pack, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "PortalCraft: Portal's own gun in micro voxels, from your Portal", "pack_format": 97, "min_format": 97, "max_format": 97}}, f)
    display = json.load(open(os.path.join(ROOT, "src/main/resources/assets/portalcraft/models/item/portal_gun_in_hand.json")))["display"]
    textures = {"gun": "portalcraft:item/portal_gun_voxels", "particle": "portalcraft:item/portal_gun"}
    grow = a.voxel * 0.02

    def write(name, model):
        with open(os.path.join(assets, "models", "item", name + ".json"), "w") as f:
            json.dump(model, f, separators=(",", ":"))

    el, faces = elements([parts["rest"]], palette, a.voxel, grow)
    write("portal_gun_in_hand", {"textures": textures, "elements": el, "display": display})
    lo = np.min([e["from"] for e in el], 0)
    hi = np.max([e["to"] for e in el], 0)
    print(f"portal_gun_in_hand: {len(el)} elements, {faces} faces, bounds {np.round(lo, 2)} .. {np.round(hi, 2)}")
    zones = palette.resolve(parts["rest"])[1]
    if a.spill or a.lights:
        zoned = parts["rest"]["zone"] > 0
        rest, bright = parts["rest"], zoned & (zones < 0)
        print(f"at rest: {(zones == 1).sum()} voxels in the near spill, {(zones == 2).sum()} in the far, {(rest['cls'] == LIGHT).sum()} light; "
              f"{bright.sum()} in reach but too bright to tint ({(bright & (rest['cls'] == SHELL)).sum()} of them the white shell)")
    tint = [{"type": "minecraft:custom_model_data", "index": 0, "default": -13988609}]
    item = json.load(open(os.path.join(ROOT, "src/main/resources/assets/portalcraft/items/portal_gun.json")))
    if a.spill:   # the pack's own item definition: the mod's, with the two spill tints (a half by default)
        tint = tint + [{"type": "minecraft:custom_model_data", "index": i, "default": -8355712} for i in (1, 2)]
        if a.shell_tint:
            tint.append({"type": "minecraft:custom_model_data", "index": 3, "default": -1})
            top = np.array(palette.colours)[palette.index(parts["rest"]), :3].max(1)
            print(f"at rest: {(zones == 3).sum()} voxels of the white shell (and highlights) in the glow's reach, tintindex 3"
                  f" ({(zoned & (top > 255 // a.gain) & (top <= 127)).sum()} of them dark enough for a gain of 2 but not of {a.gain})")
        if a.glow_tints:
            cmd = json.loads(json.dumps(item))
            cmd["model"]["fallback"]["tints"] = list(tint)
            tint = tint[:1] + [{"type": "portalcraft:gun_glow", "zone": z, "gain": float(a.gain) if z < 3 else 1.0} for z in (1, 2, 3)]
        item["model"]["fallback"]["tints"] = tint
        if a.glow_tints:   # an emergency fallback, should the mod's tint source not load (its tints want the gain-2 textures)
            with open(os.path.join(a.pack, "proposed", "items", "portal_gun.cmd-tints.json"), "w") as f:
                json.dump(cmd, f, indent=2)
        os.makedirs(os.path.join(assets, "items"), exist_ok=True)
        with open(os.path.join(assets, "items", "portal_gun.json"), "w") as f:
            json.dump(item, f, indent=2)
    if not ticks:
        palette.save(os.path.join(assets, "textures", "item", "portal_gun_voxels.png"))
        return

    # The keyframes. The body and the front cover are each written once; a keyframe's own models
    # only inherit them with that tick's display transform (the recoil, and the cover's slide).
    def moved(motion, recoil_only_first_person=None):
        out = {}
        for ctx, entry in display.items():
            mo = motion if ctx.startswith("firstperson") or recoil_only_first_person is None else recoil_only_first_person
            d = moved_display(display[ctx.replace("lefthand", "righthand")] if "lefthand" in ctx else entry, mo)
            if "lefthand" in ctx:   # the left hand's is the right hand's mirrored, as in the model this copies
                d["rotation"] = [d["rotation"][0], -d["rotation"][1], -d["rotation"][2]]
            out[ctx] = d
        return out

    for name in ("body", "cover"):
        el, faces = elements([parts[name]], palette, a.voxel, grow)
        write(f"portal_gun_fire/{name}", {"textures": textures, "elements": el, "display": display})
        print(f"portal_gun_fire/{name}: {len(el)} elements, {faces} faces")
    cases = []
    if a.per_frame:
        per_frame(a, gun, fire, parts, palette, display, textures, tint, item, write, grow)
        return
    for t in ticks:
        skin = m.skin(fire, frame(t))
        recoil = scaled_motion(gun.in_pixels(np.linalg.inv(gun.idle[gun.root]) @ skin[gun.root]), a.recoil)
        slide = gun.in_pixels(gun.relative(skin)[gun.cover])
        write(f"portal_gun_fire/body_{t}", {"parent": "portalcraft:item/portal_gun_fire/body", "display": moved(recoil, np.eye(4))})
        write(f"portal_gun_fire/cover_{t}", {"parent": "portalcraft:item/portal_gun_fire/cover", "display": moved(recoil @ slide, slide)})
        el, faces = elements([parts[f"bend_{t}"]], palette, a.voxel, grow)
        write(f"portal_gun_fire/bend_{t}", {"textures": textures, "elements": el, "display": moved(recoil, np.eye(4))})
        print(f"portal_gun_fire/bend_{t} (frame {frame(t):g}): {len(el)} elements, {faces} faces; recoil {np.round(recoil[:3, 3] + recoil[:3, :3] @ np.full(3, 8.0) - 8, 2)} px, cover slide {slide[2, 3]:+.2f} px")
        cases.append({"threshold": t, "model": {"type": "minecraft:composite", "models": [
            {"type": "minecraft:model", "model": f"portalcraft:item/portal_gun_fire/{n}_{t}", "tints": tint} for n in ("body", "cover", "bend")]}})
    palette.save(os.path.join(assets, "textures", "item", "portal_gun_voxels.png"))
    item = json.loads(json.dumps(item))
    item["model"]["fallback"] = {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0, "entries": cases, "fallback": item["model"]["fallback"]}
    with open(os.path.join(a.pack, "proposed", "items", "portal_gun.json"), "w") as f:
        json.dump(item, f, indent=2)
    with open(os.path.join(a.pack, "proposed", "README.md"), "w") as f:
        f.write("# The firing animation (not active)\n\n"
                "`items/portal_gun.json` here is the mod's item definition with one change: in the hand it picks a\n"
                "keyframe of the firing animation by the item's custom model data float 0 (0 or missing: the gun at\n"
                f"rest, `portal_gun_in_hand`; 1..{ticks[-1]}: that many game ticks after the shot, each a composite of\n"
                "`portal_gun_fire/body_N`, `cover_N` and `bend_N`).\n\n"
                "It does nothing until the mod writes that float (PortalGunItem.inventoryTick, next to the light's\n"
                "colour). To try it then, copy it to `assets/portalcraft/items/portal_gun.json` in this pack.\n")
    print(f"wrote {a.pack}")


def glass_edge(p):
    """The glass is more opaque towards its silhouette: by how far its cell is from the tube's axis
    across the eye's line, which needs no normals: approximated by its height in the tube."""
    p["edge"] = np.zeros(len(p["cls"]))
    g = p["cls"] == GLASS
    if g.any():
        c = p["cell"][g].astype(float)
        mid = (c[:, :2].max(0) + c[:, :2].min(0)) / 2
        half = (c[:, :2].max(0) - c[:, :2].min(0)) / 2
        side = np.cross(EYE_DIR, [0, 0, 1.0])[:2]
        side /= np.linalg.norm(side)
        p["edge"][g] = np.clip(np.abs(((c[:, :2] - mid) / half) @ side) ** 2, 0, 0.999)


def grab_frames(a, gun, palette, display, textures, tint, rest_model, write, grow):
    """--grab: poses for the `pickup` and `release` sequences as per_frame()'s are for fire1. Their
    claws turn at the root too, so less rides the front cover here than when firing (its own
    `portal_gun_pickup/cover`, shared by both), and more is voxelized per pose. Returns the item
    definition's entries (fire_frame 101.., 201..) and each sequence's recoil frames."""
    m, bones = gun.model, range(len(gun.model.bones))
    seqs = (("pickup", gun.anims["@pickup"], 100), ("release", gun.anims["@release"], 200))
    rels = [gun.relative(m.skin(an, f)) for _, an, _ in seqs for f in range(an["frames"])]
    still = {b for b in bones if all(np.abs(r[b] - np.eye(4)).max() < 1e-3 for r in rels)}
    slides = {b for b in bones if b not in still and all(np.abs(r[b] - r[gun.cover]).max() < 1e-3 for r in rels)}
    vb = [set(gun.verts["b"][i, :gun.verts["nb"][i]].tolist()) for i in range(len(gun.verts))]
    kind = np.array([0 if all(vb[i] <= still for i in t[1:]) else 1 if all(vb[i] <= slides for i in t[1:]) else 2 for t in gun.tris])
    kind[gun.tris[:, 0] == gun.glass_mat] = 2
    print(f"grab: triangles: {(kind == 0).sum()} of the body, {(kind == 1).sum()} riding the front cover, {(kind == 2).sum()} that bend")
    rest = gun.relative(gun.idle)
    models = os.path.join(a.pack, "assets", "portalcraft", "models", "item")
    os.makedirs(os.path.join(models, "portal_gun_pickup"), exist_ok=True)
    body = "portal_gun_fire/body"
    for name in ("body", "cover"):
        part = gun.part(gun.tris[kind == (name == "cover")], rest)
        glass_edge(part)
        el, faces = elements([part], palette, a.voxel, grow)
        if name == "body" and json.load(open(os.path.join(models, body + ".json")))["elements"] == json.loads(json.dumps(el)):
            print("grab: the body is the firing poses' (portal_gun_fire/body)")
            continue
        if name == "body":
            body = "portal_gun_pickup/body"
        write(f"portal_gun_pickup/{name}", {"textures": textures, "elements": el, "display": display})
        print(f"portal_gun_pickup/{name}: {len(el)} elements, {faces} faces")
    fp = display["firstperson_righthand"]
    cases, recoils, worst = [], {}, 0.0
    for seq, an, base in seqs:
        os.makedirs(os.path.join(a.pack, "assets", "portalcraft", "models", "item", f"portal_gun_{seq}"), exist_ok=True)
        recoils[seq] = []
        for n in range(1, an["frames"] + 1):
            skin = m.skin(an, n - 1)
            rel = gun.relative(skin)
            recoil = gun.in_pixels(np.linalg.inv(gun.idle[gun.root]) @ skin[gun.root])
            r = recoil_frame(fp, recoil)
            recoils[seq].append(r)
            worst = max(worst, np.abs(display_matrix(fp, r) - display_matrix(moved_display(fp, recoil))).max())
            if all(np.abs(rel[b] - np.eye(4)).max() < 1e-4 for b in range(gun.root, len(m.bones))):   # the pose at rest
                cases.append({"threshold": base + n, "model": rest_model})
                print(f"portal_gun_{seq} {n} (frame {n - 1}): the gun at rest; recoil t {r['t']} blocks, r {r['r']} deg")
                continue
            slide = gun.in_pixels(rel[gun.cover])
            write(f"portal_gun_{seq}/cover_{n}", {"parent": "portalcraft:item/portal_gun_pickup/cover",
                                                 "display": {ctx: moved_display(entry, slide) for ctx, entry in display.items()}})
            part = gun.part(gun.tris[kind == 2], rel)
            glass_edge(part)
            el, faces = elements([part], palette, a.voxel, grow)
            write(f"portal_gun_{seq}/bend_{n}", {"textures": textures, "elements": el, "display": display})
            print(f"portal_gun_{seq}/bend_{n} (frame {n - 1}): {len(el)} elements, {faces} faces; cover slide {slide[2, 3]:+.2f} px; recoil t {r['t']} blocks, r {r['r']} deg")
            cases.append({"threshold": base + n, "model": {"type": "minecraft:composite", "models": [
                {"type": "minecraft:model", "model": f"portalcraft:item/{name}", "tints": tint}
                for name in (body, f"portal_gun_{seq}/cover_{n}", f"portal_gun_{seq}/bend_{n}")]}})
        cases.append({"threshold": base + an["frames"] + 1, "model": rest_model})   # past its last frame: at rest
    print(f"grab recoil: T(t) R(r) D_rest against the display transform with the motion baked in, largest difference of any matrix entry: {worst:.6f}")
    return cases, recoils


def per_frame(a, gun, fire, parts, palette, display, textures, tint, item, write, grow):
    """--per-frame: a pose for each frame of fire1 with no recoil (the cover's slide is a display
    transform, the bending parts are voxelized in the pose), the item definition that picks them
    by portalcraft:fire_frame, and the recoil as data."""
    m, assets = gun.model, os.path.join(a.pack, "assets", "portalcraft")
    fp = display["firstperson_righthand"]
    cases, recoils, worst = [], [], 0.0
    for n in range(1, fire["frames"] + 1):
        skin = m.skin(fire, n - 1)
        recoil = gun.in_pixels(np.linalg.inv(gun.idle[gun.root]) @ skin[gun.root])
        slide = gun.in_pixels(gun.relative(skin)[gun.cover])
        write(f"portal_gun_fire/cover_{n}", {"parent": "portalcraft:item/portal_gun_fire/cover",
                                            "display": {ctx: moved_display(entry, slide) for ctx, entry in display.items()}})
        el, faces = elements([parts[f"bend_{n}"]], palette, a.voxel, grow)
        write(f"portal_gun_fire/bend_{n}", {"textures": textures, "elements": el, "display": display})
        r = recoil_frame(fp, recoil)
        recoils.append(r)
        # what the mod will do, against the display transform the tick keyframes baked the recoil into
        worst = max(worst, np.abs(display_matrix(fp, r) - display_matrix(moved_display(fp, recoil))).max())
        print(f"portal_gun_fire/bend_{n} (frame {n - 1}): {len(el)} elements, {faces} faces; cover slide {slide[2, 3]:+.2f} px; recoil t {r['t']} blocks, r {r['r']} deg")
        cases.append({"threshold": n, "model": {"type": "minecraft:composite", "models": [
            {"type": "minecraft:model", "model": f"portalcraft:item/portal_gun_fire/{name}", "tints": tint} for name in ("body", f"cover_{n}", f"bend_{n}")]}})
    print(f"recoil: T(t) R(r) D_rest against the baked display transform, largest difference of any matrix entry over the frames: {worst:.6f} (blocks; rotation entries are unitless)")
    static = json.loads(json.dumps(item))
    data = {"fps": int(fire["fps"]) if fire["fps"] == int(fire["fps"]) else fire["fps"], "frames": recoils}
    if a.grab:
        cases.append({"threshold": fire["frames"] + 1, "model": static["model"]["fallback"]})   # between fire's values and the grab's: at rest
        more, grabs = grab_frames(a, gun, palette, display, textures, tint, static["model"]["fallback"], write, grow)
        cases += more
        data.update(grabs)
    palette.save(os.path.join(assets, "textures", "item", "portal_gun_voxels.png"))
    with open(os.path.join(assets, "gun_recoil.json"), "w") as f:
        json.dump(data, f, indent=1)
    item["model"]["fallback"] = {"type": "minecraft:range_dispatch", "property": "portalcraft:fire_frame", "entries": cases, "fallback": static["model"]["fallback"]}
    # In another's hand (and in Portal's views of Steve) the mod's own 27-box gun: the voxel one is
    # some 86,000 vertices, over the 65,536 the avatar's mesh may have, and Steve was drawn without it.
    shutil.copyfile(os.path.join(ROOT, "src/main/resources/assets/portalcraft/models/item/portal_gun_in_hand.json"),
                    os.path.join(assets, "models", "item", "portal_gun_third.json"))
    item["model"]["cases"].append({"when": ["thirdperson_righthand", "thirdperson_lefthand", "head"],
                                   "model": {"type": "minecraft:model", "model": "portalcraft:item/portal_gun_third"}})
    with open(os.path.join(assets, "items", "portal_gun.json"), "w") as f:
        json.dump(item, f, indent=2)
    with open(os.path.join(a.pack, "proposed", "items", "portal_gun.static.json"), "w") as f:
        json.dump(static, f, indent=2)
    with open(os.path.join(a.pack, "proposed", "README.md"), "w") as f:
        f.write("# Without the firing animation\n\n"
                "The pack's `assets/portalcraft/items/portal_gun.json` picks a pose of the firing animation by the mod's\n"
                "`portalcraft:fire_frame` property (0: at rest; 1..16: fire1's frame + 1). `items/portal_gun.static.json`\n"
                "here is the same definition without it (the gun at rest only), should that property not be there.\n")
    print(f"wrote {a.pack}")


if __name__ == "__main__":
    main()
