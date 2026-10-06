"""Stand-in for Minecraft's world exporter: publishes a test mesh into Portal's world mapping.

  python fake_world.py --at -600,-360,160            # one 40-unit cube (block mesh) with its min corner there
  python fake_world.py --at -600,-360,160 --entity   # a 10-unit cube in the entity mesh instead
  python fake_world.py --clear                       # publish an empty block mesh (with --entity: entity mesh)

Portal must be running (it creates the mapping). Layout: protocol/portalcraft_protocol.h, WorldHeader.
The entity mesh is normally rewritten every frame by Minecraft; with Minecraft closed the host draws
nothing at all (it waits for fresh overlay frames), so run fake_mc.py alongside or expect no output.
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
ITEM_ATLAS_OFFSET = MESH_OFFSET + 2 * SLOT_BYTES
ITEM_ATLAS_BYTES = 1024 * 1024 * 4
ENTITY_OFFSET = ITEM_ATLAS_OFFSET + ITEM_ATLAS_BYTES
ENTITY_MAX_VERTICES = 65536
ENTITY_SLOT_BYTES = ENTITY_MAX_VERTICES * 24
SKIN_OFFSET = ENTITY_OFFSET + 2 * ENTITY_SLOT_BYTES
TOTAL = SKIN_OFFSET + 256 * 256 * 4 + 1024 * 1024 * 4 + 512 * 64 * 4 + 1024 * 1024 * 4
VERTEX = struct.Struct("<3fI2f")
# WorldHeader offsets
ATLAS_SEQ, MESH_SEQ, FRONT, READING, SLOT_SOLID, SLOT_TRANSLUCENT = 12, 16, 20, 24, 28, 36
ENTITY_SEQ, ENTITY_FRONT, ENTITY_READING = 56, 60, 64
ENTITY_COUNTS = (68, 76, 84, 92)  # block solid, block translucent, item solid, item translucent


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


def free_slot(front, reading):
    for slot in (0, 1):
        if slot != front and slot != reading:
            return slot
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--at", default="-600,-360,160")
    ap.add_argument("--clear", action="store_true")
    ap.add_argument("--entity", action="store_true", help="publish into the entity mesh (block-atlas solid range)")
    ap.add_argument("--instance", type=int, default=0, help="which Portal (hl2.exe -pcinstance N); 0 is the usual one")
    a = ap.parse_args()
    m = mmap.mmap(-1, TOTAL, tagname=MAPPING + (f"_{a.instance}" if a.instance > 0 else ""))
    if m[0:4] != b"PCW6":
        print(f"Portal's world mapping isn't there or isn't PCW6 (magic {bytes(m[0:4])!r}); start Portal with this plugin first")
        return
    size, pixels = atlas_pixels()
    struct.pack_into("<II", m, 4, size, size)
    m[ATLAS_OFFSET:ATLAS_OFFSET + len(pixels)] = pixels
    (atlas_seq,) = struct.unpack_from("<I", m, ATLAS_SEQ)
    struct.pack_into("<I", m, ATLAS_SEQ, atlas_seq + 1)
    at = [float(v) for v in a.at.split(",")]
    if a.entity:
        verts = [] if a.clear else cube(*at, s=10.0)
        seq, front, reading = struct.unpack_from("<III", m, ENTITY_SEQ)
        slot = free_slot(front, reading)
        if slot is None:
            print("both entity slots busy; try again")
            return
        base = ENTITY_OFFSET + slot * ENTITY_SLOT_BYTES
        m[base:base + 24 * len(verts)] = b"".join(verts)
        for i, offset in enumerate(ENTITY_COUNTS):
            struct.pack_into("<I", m, offset + slot * 4, len(verts) if i == 0 else 0)
        struct.pack_into("<I", m, ENTITY_FRONT, slot)
        struct.pack_into("<I", m, ENTITY_SEQ, (seq + 1) & 0xFFFFFFFF or 1)
        print(f"published {len(verts)} entity vertices in slot {slot}; atlas {size}x{size}")
        return
    verts = [] if a.clear else cube(*at)
    mesh_seq, front, reading = struct.unpack_from("<III", m, MESH_SEQ)
    slot = free_slot(front, reading)
    if slot is None:
        print("both mesh slots busy; try again")
        return
    base = MESH_OFFSET + slot * SLOT_BYTES
    m[base:base + 24 * len(verts)] = b"".join(verts)
    struct.pack_into("<I", m, SLOT_SOLID + slot * 4, len(verts))
    struct.pack_into("<I", m, SLOT_TRANSLUCENT + slot * 4, 0)
    struct.pack_into("<I", m, FRONT, slot)
    struct.pack_into("<I", m, MESH_SEQ, (mesh_seq + 1) & 0xFFFFFFFF or 1)
    print(f"published {len(verts)} vertices in slot {slot}; atlas {size}x{size}")


if __name__ == "__main__":
    main()
