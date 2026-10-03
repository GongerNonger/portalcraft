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
PRONG = (0, 12)


def box(fr, to, swatch, emit=0, faces=("north", "east", "south", "west", "up", "down")):
    u, v = swatch
    e = {"from": fr, "to": to, "faces": {d: {"uv": [u, v, u + 3, v + 3], "texture": "#gun"} for d in faces}}
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
    box([7, 9, 11], [9, 9.5, 13], GLOW, 15),              # the light on top
    # black barrel and its ribbed collar, reaching well ahead of the casing as Portal's does
    box([5, 3, -3], [11, 8, 9], BLACK),
    box([4.5, 2.5, 5], [11.5, 8.5, 6], BLACK_LIT),
    box([4.5, 2.5, 2], [11.5, 8.5, 3], BLACK_LIT),
    box([4.5, 2.5, -1], [11.5, 8.5, 0], BLACK_LIT),
    # the muzzle glow, set back between the claws
    box([6, 4, -4.5], [10, 7, -3], GLOW, 15),
    box([7, 4.75, -4.8], [9, 6.25, -4.5], GLOW_CORE, 15),
    # three white claws round the muzzle
    box([4.5, 2.5, -7], [6.5, 4.5, -3], PRONG),
    box([9.5, 2.5, -7], [11.5, 4.5, -3], PRONG),
    box([7, 7.5, -7], [9, 9, -3], PRONG),
    # the thin arm on top with its hinged claw, and the cable back to the casing
    box([7.5, 8, 0.5], [8.5, 13, 1.5], BLACK),
    box([7.5, 12, -2.5], [8.5, 13, 0.5], BLACK),
    box([7.5, 11, -2.5], [8.5, 12, -1.5], BLACK_LIT),
    box([7.6, 8.5, 1.5], [8.4, 9.5, 9.5], BLACK),
]

model = {
    "textures": {"gun": "portalcraft:item/portal_gun_model", "particle": "portalcraft:item/portal_gun"},
    "elements": elements,
    "gui_light": "front",
    "display": {
        # held as Portal holds it: bottom right, muzzle ahead and turned in toward the crosshair
        "firstperson_righthand": {"rotation": [0, 28, 0], "translation": [1, 2, -3], "scale": [0.8, 0.8, 0.8]},
        "firstperson_lefthand": {"rotation": [0, -28, 0], "translation": [1, 2, -3], "scale": [0.8, 0.8, 0.8]},
        # third person: in the hand, muzzle forward
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 1.5, -1], "scale": [0.6, 0.6, 0.6]},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 1.5, -1], "scale": [0.6, 0.6, 0.6]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.8, 0.8, 0.8]},
    },
}
path = "src/main/resources/assets/portalcraft/models/item/portal_gun_in_hand.json"
json.dump(model, open(path, "w"), indent=2)
print("wrote", path)
