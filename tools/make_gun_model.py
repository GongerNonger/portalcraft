"""Writes the 3D in-hand portal gun (models/item/portal_gun_in_hand.json): Portal's own gun as you
hold it in first person (big white rear casing nearest you, a black barrel and ribbed collar
pointing ahead, white claws round the glowing muzzle, the thin arm on top), in Minecraft's blocky
style. Built pointing north (-z, away from the camera), rear at +z. Run from the repo root;
preview with tools/preview_item.py. Texture: textures/item/portal_gun_model.png (tools/make_textures.py)."""
import json

# 4x4 swatches in portal_gun_model.png
SHELL, SHELL_MID, SHELL_SHADE = (0, 0), (4, 0), (8, 0)
BLACK_LIT, BLACK = (0, 4), (4, 4)
GLOW_CORE, GLOW, GLOW_EDGE = (0, 8), (4, 8), (8, 8)
LIGHT = (12, 8)  # white; tinted the colour the gun last fired (items/portal_gun.json)
PRONG = (0, 12)


def box(fr, to, swatch, emit=0, faces=("north", "east", "south", "west", "up", "down"), turn=None, tint=False):
    """turn: (axis, angle, origin) rotates the box about origin (vanilla allows -45..45 degrees)."""
    u, v = swatch
    e = {"from": fr, "to": to, "faces": {d: {"uv": [u, v, u + 3, v + 3], "texture": "#gun"} for d in faces}}
    if tint:
        for f in e["faces"].values():
            f["tintindex"] = 0
    if turn:
        axis, angle, origin = turn
        e["rotation"] = {"origin": origin, "axis": axis, "angle": angle}
    if emit:
        e["light_emission"] = emit
    return e


elements = [
    # the rear casing, rounded by stepping its sides in
    box([3, 1, 9], [13, 8, 16], SHELL),
    box([4, 8, 9.5], [12, 9, 15.5], SHELL),
    box([4, 0, 9.5], [12, 1, 15.5], SHELL_SHADE),
    box([2, 2, 10], [3, 7, 15], SHELL_MID),
    box([13, 2, 10], [14, 7, 15], SHELL_SHADE),
    box([7, 9, 11], [9, 9.5, 13], LIGHT, 15, tint=True),  # the light on top
    # black barrel and its ribbed collar, reaching well ahead of the casing as Portal's does
    box([5, 3, -3], [11, 8, 9], BLACK),
    box([4.5, 2.5, 5], [11.5, 8.5, 6], BLACK_LIT),
    box([4.5, 2.5, 2], [11.5, 8.5, 3], BLACK_LIT),
    box([4.5, 2.5, -1], [11.5, 8.5, 0], BLACK_LIT),
    # the muzzle glow, set back between the claws
    box([6, 4, -4.5], [10, 7, -3], LIGHT, 15, tint=True),
    box([7, 4.75, -4.8], [9, 6.25, -4.5], LIGHT, 15, tint=True),
    # Portal's three metal claws round the muzzle (top, lower left, lower right): each a finger with
    # a black knuckle on the collar, reaching forward and bending in toward the barrel's axis
    # (x 8, y 5.5) at the tip.
    box([7, 8.5, -3.5], [9, 9.5, -1.5], BLACK_LIT),                  # knuckles
    box([4, 2, -3.5], [6, 3.5, -1.5], BLACK_LIT),
    box([10, 2, -3.5], [12, 3.5, -1.5], BLACK_LIT),
    box([7.25, 8.5, -7.5], [8.75, 9.5, -3.5], PRONG),                # fingers
    box([4.25, 2.25, -7.5], [5.75, 3.25, -3.5], PRONG),
    box([10.25, 2.25, -7.5], [11.75, 3.25, -3.5], PRONG),
    box([7.25, 8.5, -10], [8.75, 9.5, -7.5], SHELL_MID, turn=("x", -22.5, [8, 9, -7.5])),   # tips, bent in
    box([4.25, 2.25, -10], [5.75, 3.25, -7.5], SHELL_MID, turn=("y", -22.5, [5, 2.75, -7.5])),
    box([10.25, 2.25, -10], [11.75, 3.25, -7.5], SHELL_MID, turn=("y", 22.5, [11, 2.75, -7.5])),
    # the thin arm on top, its little two-jaw gripper, and the cable back to the casing
    box([7.5, 8, 0.5], [8.5, 13, 1.5], BLACK),
    box([7.5, 12, -2], [8.5, 13, 0.5], BLACK),
    box([7.25, 11, -3], [8.75, 12.5, -2], BLACK_LIT),                # gripper head
    box([7.25, 9.75, -3.25], [7.75, 11, -2.25], SHELL_SHADE),        # its jaws
    box([8.25, 9.75, -3.25], [8.75, 11, -2.25], SHELL_SHADE),
    box([7.6, 8.5, 1.5], [8.4, 9.5, 9.5], BLACK),
]

model = {
    "textures": {"gun": "portalcraft:item/portal_gun_model", "particle": "portalcraft:item/portal_gun"},
    "elements": elements,
    "gui_light": "front",
    "display": {
        # held as Portal holds it: bottom right, the barrel's axis aimed straight at the crosshair
        # (pitch 12, yaw 14: found by projecting the barrel's centreline, see the commit)
        "firstperson_righthand": {"rotation": [12, 14, 0], "translation": [1, 2, -3], "scale": [0.8, 0.8, 0.8]},
        "firstperson_lefthand": {"rotation": [12, -14, 0], "translation": [1, 2, -3], "scale": [0.8, 0.8, 0.8]},
        # third person: at the hip, muzzle forward (with no rotation it hung straight down the arm)
        "thirdperson_righthand": {"rotation": [90, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        "thirdperson_lefthand": {"rotation": [90, 0, 0], "translation": [0, 3, 0], "scale": [0.6, 0.6, 0.6]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.8, 0.8, 0.8]},
    },
}
path = "src/main/resources/assets/portalcraft/models/item/portal_gun_in_hand.json"
json.dump(model, open(path, "w"), indent=2)
print("wrote", path)
