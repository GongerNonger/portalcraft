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
    """16x16 item, pointing up-right like vanilla tools. Brass body, obsidian grip, glowing crystal."""
    P = {
        ".": None,
        "k": (24, 18, 32, 255),     # outline
        "o": (52, 38, 70, 255),     # obsidian
        "O": (78, 60, 104, 255),    # obsidian highlight
        "b": (170, 120, 48, 255),   # brass
        "B": (226, 178, 84, 255),   # brass highlight
        "d": (112, 76, 30, 255),    # brass shadow
        "c": (90, 220, 255, 255),   # crystal
        "C": (220, 252, 255, 255),  # crystal core
        "g": (40, 150, 200, 255),   # crystal edge
    }
    rows = [
        "...........kkk..",
        "..........kgcgk.",
        ".........kgcCck.",
        "........kbgcCgk.",
        ".......kBbbggk..",
        "......kBbdbbk...",
        ".....kBbdbbk....",
        "....kBbbdbk.....",
        "...kkbbdbk......",
        "..kOok.dk.......",
        ".kOook.k........",
        "kOoook..........",
        "kooOk...........",
        "kook............",
        ".kk.............",
        "................",
    ]
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if P.get(ch):
                img.putpixel((x, y), P[ch])
    img.save(path)

if __name__ == "__main__":
    import os
    os.makedirs(f"{ROOT}/entity", exist_ok=True)
    os.makedirs(f"{ROOT}/item", exist_ok=True)
    portal((42, 140, 255), f"{ROOT}/entity/portal_primary.png")
    portal((255, 138, 30), f"{ROOT}/entity/portal_secondary.png")
    gun(f"{ROOT}/item/portal_gun.png")
    print("textures written")
