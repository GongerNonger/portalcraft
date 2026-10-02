"""Stand-in for Minecraft's block exporter: publishes a test mesh into Portal's world mapping.

  python fake_world.py --at -600,-360,160      # one 40-unit cube with its min corner there
  python fake_world.py --clear                 # publish an empty mesh

Portal must be running (it creates the mapping). Layout: protocol/portalcraft_protocol.h, WorldHeader.
"""
import argparse
import mmap
import struct

MAPPING = "Local\\PortalCraft_World_v1"
ATLAS_MAX = 2048
ATLAS_OFFSET = 4096
MESH_OFFSET = ATLAS_OFFSET + ATLAS_MAX * ATLAS_MAX * 4
MAX_VERTICES = 196608
SLOT_BYTES = MAX_VERTICES * 24
TOTAL = MESH_OFFSET + 2 * SLOT_BYTES
VERTEX = struct.Struct("<3fI2f")


def atlas_pixels(size=64):
    """An original test texture: orange/teal checker with a dark border, top-down RGBA."""
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            border = x < 2 or y < 2 or x >= size - 2 or y >= size - 2
            checker = ((x // 8) + (y // 8)) % 2
            r, g, b = (30, 30, 40) if border else ((240, 140, 40) if checker else (40, 190, 180))
            out[(y * size + x) * 4:(y * size + x) * 4 + 4] = bytes((r, g, b, 255))
    return size, bytes(out)


def cube(x0, y0, z0, s=40.0):
    """Six faces of a cube in Source space, two triangles each, with Minecraft-style face shading."""
    x1, y1, z1 = x0 + s, y0 + s, z0 + s
    faces = [
        ((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), 1.0),  # top
        ((x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0), 0.5),  # bottom
        ((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), 0.8),  # -y
        ((x1, y1, z0), (x0, y1, z0), (x0, y1, z1), (x1, y1, z1), 0.8),  # +y
        ((x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), 0.6),  # -x
        ((x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1), 0.6),  # +x
    ]
    uvs = [(0, 1), (1, 1), (1, 0), (0, 0)]
    out = []
    for *corners, shade in faces:
        c = int(255 * shade)
        color = 0xFF000000 | (c << 16) | (c << 8) | c
        for k in (0, 1, 2, 0, 2, 3):
            out.append(VERTEX.pack(*corners[k], color, *uvs[k]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--at", default="-600,-360,160")
    ap.add_argument("--clear", action="store_true")
    a = ap.parse_args()
    m = mmap.mmap(-1, TOTAL, tagname=MAPPING)
    if m[0:4] != b"PCW1":
        print("Portal's world mapping isn't there (start Portal with the plugin first)")
        return
    size, pixels = atlas_pixels()
    struct.pack_into("<II", m, 4, size, size)
    m[ATLAS_OFFSET:ATLAS_OFFSET + len(pixels)] = pixels
    atlas_seq, mesh_seq, front, reading = struct.unpack_from("<IIII", m, 12)
    struct.pack_into("<I", m, 12, atlas_seq + 1)
    verts = [] if a.clear else cube(*(float(v) for v in a.at.split(",")))
    slot = 0 if front != 0 and reading != 0 else 1
    base = MESH_OFFSET + slot * SLOT_BYTES
    m[base:base + 24 * len(verts)] = b"".join(verts)
    struct.pack_into("<I", m, 28 + slot * 4, len(verts))  # slotSolid[slot]
    struct.pack_into("<I", m, 36 + slot * 4, 0)           # slotTranslucent[slot]
    struct.pack_into("<I", m, 20, slot)                    # front
    struct.pack_into("<I", m, 16, mesh_seq + 1)            # meshSeq
    print(f"published {len(verts)} vertices in slot {slot}; atlas {size}x{size}")


if __name__ == "__main__":
    main()
