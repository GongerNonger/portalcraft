"""Portal's portal particles for the green portals: every blue and orange in them turned green, and
the sparks round a portal's rim made into globs that swell up and fade, as round the show's portals.

Reads Portal's own particle files and writes changed copies (same systems, same names, so the game
uses them as it does its own).
"""
import colorsys
import io
from pathlib import Path

import numpy as np
from srctools.dmx import Color, Element, ValueType

GREEN_HUE = 105 / 360
FILES = ["particles/portals.pcf", "particles/portal_projectile.pcf", "particles/portalgun.pcf"]
RIMS = {"portal_1_edge", "portal_2_edge"}

GLOB_VMT = """"UnlitGeneric"
{
	// PortalCraft: a glob of the green portal's rim (the colour is the texture's own)
	"$basetexture" "effects/pc_glob"
	"$translucent" "1"
	"$vertexalpha" "1"
}
"""


def _green(c: Color) -> Color:
    h, s, v = colorsys.rgb_to_hsv(c.r / 255, c.g / 255, c.b / 255)
    r, g, b = colorsys.hsv_to_rgb(GREEN_HUE, s, v)
    return Color(round(r * 255), round(g * 255), round(b * 255), c.a)


def _elements(root: Element):
    seen, todo = set(), [root]
    while todo:
        el = todo.pop()
        if id(el) in seen:
            continue
        seen.add(id(el))
        yield el
        for attr in el.values():
            if attr.type == ValueType.ELEMENT:
                todo.extend(e for e in (attr.iter_elem() if attr.is_array else [attr.val_elem]) if e is not None)


def _set(el: Element, key: str, value) -> None:
    if key in el:
        el[key] = value


def _globs(system: Element, sprites: Element) -> None:
    """A rim's sparks (many, small, streaked, faint) as globs: fewer, bigger, round, solid, swelling as they go."""
    system["renderers"] = [sprites]
    for op in system["operators"].iter_elem():
        if op.name == "Radius Scale":
            _set(op, "radius_start_scale", 0.2)
            _set(op, "radius_end_scale", 1.0)
        if op.name == "Alpha Fade and Decay":
            _set(op, "end_fade_in_time", 0.15)
            _set(op, "start_fade_out_time", 0.7)
    for init in system["initializers"].iter_elem():
        if init.name == "Alpha Random":
            _set(init, "alpha_min", 220)
            _set(init, "alpha_max", 255)
        if init.name == "Position Within Sphere Random":
            _set(init, "distance_min", 47.0)
            _set(init, "distance_max", 56.0)
    radius = Element("Radius Random", "DmeParticleOperator")
    for key, value in (("functionName", "Radius Random"), ("radius_min", 2.5), ("radius_max", 7.0), ("radius_random_exponent", 1.0)):
        radius[key] = value
    system["initializers"].append(radius)
    for emitter in system["emitters"].iter_elem():
        _set(emitter, "emission_rate", 45.0)


def particle_file(data: bytes) -> bytes:
    root, fmt, ver = Element.parse(io.BytesIO(data))
    systems = {s.name: s for s in root["particlesystemdefinitions"].iter_elem()}
    for el in _elements(root):
        for key, attr in list(el.items()):
            if attr.type == ValueType.COLOR and not attr.is_array:
                el[key] = _green(attr.val_color)
    sprites = next((r for s in systems.values() for r in s["renderers"].iter_elem() if r.name == "render_animated_sprites"), None)
    for name in RIMS & systems.keys():
        if sprites is not None:
            _globs(systems[name], sprites)
    out = io.BytesIO()
    root.export_binary(out, version=2, fmt_name=fmt, fmt_ver=ver)
    return out.getvalue()


def glob() -> np.ndarray:
    """RGBA floats 0..1: one glob, flat lime with a darker rim and a pale highlight."""
    size = 64
    y, x = np.mgrid[0:size, 0:size] / (size - 1) * 2 - 1
    r = np.hypot(x, y)
    rgb = np.broadcast_to(np.array([0.55, 0.93, 0.28]), (size, size, 3)).copy()
    rim = np.clip((r - 0.62) / 0.06, 0, 1)[..., None]
    rgb = rgb * (1 - rim) + np.array([0.22, 0.60, 0.16]) * rim
    shine = np.clip(1 - np.hypot(x + 0.28, y + 0.30) / 0.24, 0, 1)[..., None] > 0.25
    rgb = np.where(shine, np.array([0.90, 1.0, 0.70]), rgb)
    return np.dstack([rgb, np.clip((0.86 - r) / 0.06, 0, 1)])


def write(pak, out: Path, vtf) -> None:
    """Everything of the particles into the pack. vtf: swirl.vtf."""
    for name in FILES:
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(particle_file(pak[name].read()))
        print("wrote", target)
    effects = out / "materials/effects"
    effects.mkdir(parents=True, exist_ok=True)
    (effects / "pc_glob.vtf").write_bytes(vtf(glob(), 0x4 | 0x8 | 0x100 | 0x200 | 0x2000))
    for n in (1, 2):
        (effects / f"portal_{n}_particle.vmt").write_text(GLOB_VMT, encoding="latin1", newline="")
