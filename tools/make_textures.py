"""Procedural textures for PortalCraft. Run from the repo root."""
import math, random
from PIL import Image

ROOT = "src/main/resources/assets/portalcraft/textures"

def portal(rgb, path):
    """32x64 oval: bright rim, soft glow inward, dim swirling centre."""
    w, h = 32, 64
    img = Image.new("RGBA", (w, h))
    rnd = random.Random(7)
    for y in range(h):
        for x in range(w):
            nx = (x + 0.5 - w / 2) / (w / 2 - 1)
            ny = (y + 0.5 - h / 2) / (h / 2 - 1)
            d = math.sqrt(nx * nx + ny * ny)
            if d > 1.0:
                continue
            ang = math.atan2(ny, nx)
            swirl = 0.5 + 0.5 * math.sin(ang * 3 + d * 9)
            if d > 0.86:  # rim
                k = 1.0
                a = 255
            else:  # interior: glow fading to the centre, with swirl bands
                k = 0.25 + 0.55 * (d / 0.86) ** 2 + 0.15 * swirl
                a = int(120 + 110 * (d / 0.86) ** 2)
            k += rnd.uniform(-0.04, 0.04)
            r, g, b = rgb
            mix = max(0.0, k - 0.85) * 3  # whiten the hottest pixels
            px = [min(255, int(c * min(k, 1.0) + (255 - c) * mix)) for c in (r, g, b)]
            img.putpixel((x, y), (*px, max(0, min(255, a))))
    img.save(path)

def gun(path):
    """16x16 item, pointing up-right like vanilla tools: Aperture's handheld portal device. White
    shell over a black core, the grip under the rear, a black collar, and at the front the two
    prongs we see from the side with the blue glow recessed between them."""
    P = {
        "k": (22, 22, 28, 255),     # outline
        "W": (246, 246, 242, 255),  # white shell, lit
        "w": (206, 208, 210, 255),  # white shell
        "s": (150, 154, 162, 255),  # white shell, shade
        "b": (44, 44, 52, 255),     # black core, grip
        "B": (84, 86, 98, 255),     # black, lit
        "g": (36, 128, 214, 255),   # glow, edge
        "c": (96, 200, 255, 255),   # glow
        "C": (226, 250, 255, 255),  # glow, core
    }

    def body(a, b):
        # a: along the gun, 0 (rear, bottom-left) .. 30 (front, top-right); b: across, < 0 is the lit upper-left side
        if 7 <= a <= 11 and 2.5 <= b <= 7.5:
            return "B" if a <= 8 else "b"  # grip
        if ((a - 7.0) / 4.2) ** 2 + (b / 3.6) ** 2 <= 1.0:
            return "W" if b < -1.2 else "w" if b < 1.2 else "s"  # rear bulb
        if 10 <= a <= 18 and -2.6 <= b <= 2.6:
            return "b" if b >= 1.0 else ("W" if b < -1.0 else "w")  # barrel
        return None

    # The front by hand (x, y): at 16x16 the prongs only read pixel by pixel.
    front = {
        (8, 4): "B", (9, 5): "B", (10, 6): "b", (11, 7): "b", (9, 4): "B", (10, 5): "b", (11, 6): "b",  # collar
        (9, 3): "W", (10, 2): "W", (11, 1): "W", (12, 0): "w",                                         # top prong
        (12, 6): "s", (13, 5): "s", (14, 4): "s", (15, 3): "s",                                        # bottom prong
        (10, 3): "k", (11, 2): "k", (12, 1): "k", (12, 5): "k", (13, 4): "k", (14, 3): "k",            # gaps
        (10, 4): "c", (11, 3): "c", (12, 2): "g", (11, 4): "C", (12, 3): "C", (13, 2): "c",            # glow
        (11, 5): "c", (12, 4): "c", (13, 3): "g",
    }
    grid = [[None] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            a, b = x + (15 - y), x + y - 15
            grid[y][x] = front.get((x, y)) if a >= 19 else body(a, b)
    # outline: every empty pixel next to a filled one
    out = [row[:] for row in grid]
    for y in range(16):
        for x in range(16):
            if grid[y][x] is None and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and grid[y + dy][x + dx] not in (None, "k")
                                          for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out[y][x] = "k"
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            if out[y][x]:
                img.putpixel((x, y), P[out[y][x]])
    img.save(path)

def gun_model(path):
    """16x16 palette for the 3D in-hand portal gun (models/item/portal_gun_in_hand.json): 4x4
    swatches with a lit edge, the way vanilla's spyglass texture is laid out. Rows: white shell,
    black core, the glow, the prongs."""
    sw = {
        (0, 0): ((246, 246, 242), (226, 228, 228)),  # shell, lit
        (1, 0): ((214, 216, 218), (196, 198, 200)),  # shell
        (2, 0): ((168, 172, 178), (150, 154, 162)),  # shell, shade
        (0, 1): ((84, 86, 98), (64, 66, 76)),        # black, lit
        (1, 1): ((46, 46, 54), (36, 36, 42)),        # black
        (0, 2): ((226, 250, 255), (180, 236, 255)),  # glow core
        (1, 2): ((96, 200, 255), (70, 170, 240)),    # glow
        (2, 2): ((36, 128, 214), (28, 104, 186)),    # glow, edge
        (0, 3): ((250, 250, 248), (220, 222, 224)),  # prong
    }
    img = Image.new("RGBA", (16, 16))
    for (cx, cy), (main, edge) in sw.items():
        for y in range(4):
            for x in range(4):
                img.putpixel((cx * 4 + x, cy * 4 + y), (*(edge if x == 3 or y == 3 else main), 255))
    img.save(path)

if __name__ == "__main__":
    import os
    os.makedirs(f"{ROOT}/entity", exist_ok=True)
    os.makedirs(f"{ROOT}/item", exist_ok=True)
    portal((42, 140, 255), f"{ROOT}/entity/portal_primary.png")
    portal((255, 138, 30), f"{ROOT}/entity/portal_secondary.png")
    gun(f"{ROOT}/item/portal_gun.png")
    gun_model(f"{ROOT}/item/portal_gun_model.png")
    print("textures written")
