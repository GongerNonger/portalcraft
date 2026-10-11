"""The Rick-and-Morty portal as a texture: a bright green spiral with pale arms, darker flecks and a
soft, wobbling edge. Drawn round; the game stretches it over a portal's tall rectangle, into the
show's upright oval. Two layers come out of here, turned against each other in the game:
the body (arms and edge, with the alpha that shapes the portal) and a detail of finer streaks.

    uv run --no-project --with pillow --with numpy python tools/green-portals/swirl.py <out folder>   (writes PNGs to look at)
"""
import struct
import sys
from pathlib import Path

import numpy as np

SIZE = 512


def _noise(rng: np.random.Generator, size: int, cells: int) -> np.ndarray:
    """Smooth value noise in 0..1 that tiles."""
    grid = rng.random((cells, cells))
    t = np.arange(size) / size * cells
    i, f = t.astype(int) % cells, t % 1
    f = f * f * (3 - 2 * f)
    a, b = grid[i][:, i], grid[i][:, (i + 1) % cells]
    c, d = grid[(i + 1) % cells][:, i], grid[(i + 1) % cells][:, (i + 1) % cells]
    return (a * (1 - f) + b * f) * (1 - f[:, None]) + (c * (1 - f) + d * f) * f[:, None]


def _mix(a, b, t):
    return a + (b - a) * t[..., None]


def _step(v, edge, soft=0.012):
    return np.clip((v - edge) / soft + 0.5, 0, 1)


def body() -> np.ndarray:
    """RGBA floats 0..1: the spiral, and in alpha the portal's shape. Flat colours with hard edges, as drawn in the show."""
    rng = np.random.default_rng(7)
    y, x = np.mgrid[0:SIZE, 0:SIZE] / (SIZE - 1) * 2 - 1
    r, theta = np.hypot(x, y), np.arctan2(y, x)
    lumpy = _noise(rng, SIZE, 5) * 0.6 + _noise(rng, SIZE, 12) * 0.4
    wavy = _noise(rng, SIZE, 9)
    # (out to the rectangle's sides: the hole a linked portal cuts is the oval that just fits it, and showed round a smaller swirl)
    edge = 0.975 + 0.022 * np.sin(5 * theta + 1.0) + 0.016 * np.sin(9 * theta + 2.2) + 0.010 * np.sin(14 * theta)
    ring = 0.70 + 0.035 * np.sin(4 * theta + 0.6) + 0.025 * np.sin(7 * theta + 3.0) + (wavy - 0.5) * 0.06  # where the pale outer ring begins
    # one arm winding in, as bands: how far round the spiral each point is, 0..1 across one turn
    turn = (theta / (2 * np.pi) + 1.05 * np.log(r / 0.7 + 0.04) + (lumpy - 0.5) * 0.22) % 1.0
    dark, green, olive, lime = np.array([0.13, 0.38, 0.16]), np.array([0.25, 0.58, 0.20]), np.array([0.66, 0.78, 0.22]), np.array([0.60, 0.92, 0.30])
    rgb = np.broadcast_to(green, (SIZE, SIZE, 3)).copy()
    rgb = _mix(rgb, dark, _step(turn, 0.0) * (1 - _step(turn, 0.30)))
    rgb = _mix(rgb, olive, _step(turn, 0.52) * (1 - _step(turn, 0.80)))
    rgb = _mix(rgb, dark, np.clip(1 - r / 0.16, 0, 1) ** 0.5 * 0.55)  # the eye, deeper
    # the outer ring: a thin dark line, then pale lime out to the edge
    rgb = _mix(rgb, dark, _step(r, ring - 0.035) * (1 - _step(r, ring)))
    rgb = _mix(rgb, lime, _step(r, ring))
    rgb = _mix(rgb, np.array([0.80, 1.0, 0.45]), _step(r, edge - 0.05, 0.03))  # brighter at the lip: the glow
    return np.dstack([np.clip(rgb, 0, 1), np.clip((edge - r) / 0.035, 0, 1)])


def detail() -> np.ndarray:
    """RGB floats 0..1 around mid grey (what a detail texture multiplies by, doubled): the white flecks
    in the outer ring, which turn the other way, and a few in the bands."""
    rng = np.random.default_rng(11)
    y, x = np.mgrid[0:SIZE, 0:SIZE] / (SIZE - 1) * 2 - 1
    r, theta = np.hypot(x, y), np.arctan2(y, x)
    blobs = _noise(rng, SIZE, 40) * 0.75 + _noise(rng, SIZE, 90) * 0.25  # flecks: the peaks of a noise
    flecks = _step(blobs, 0.74, 0.02) * _step(r, 0.74, 0.03) * (1 - _step(r, 0.95, 0.03))
    inner = _step(blobs, 0.80, 0.02) * (1 - _step(r, 0.66, 0.03)) * _step(r, 0.2, 0.05) * 0.5
    g = 0.5 + 0.5 * np.clip(flecks + inner, 0, 1)
    return np.dstack([g, g, g])


def vtf(image: np.ndarray, flags: int) -> bytes:
    """A one-mipmap BGRA8888 VTF (version 7.2, which every Source game reads). image: floats 0..1, RGB or RGBA."""
    h, w = image.shape[:2]
    rgba = image if image.shape[2] == 4 else np.dstack([image, np.ones((h, w))])
    bgra = (np.clip(rgba[..., [2, 1, 0, 3]], 0, 1) * 255 + 0.5).astype(np.uint8).tobytes()
    header = struct.pack("<4sIIIHHIHH4x3f4xfIBiBBH", b"VTF\0", 7, 2, 80, w, h, flags, 1, 0, 0.2, 0.6, 0.1, 1.0, 12, 1, -1, 0, 0, 1)
    return header.ljust(80, b"\0") + bgra


if __name__ == "__main__":
    from PIL import Image

    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    b = body()
    backdrop = np.full((SIZE, SIZE, 3), 0.45)
    shown = b[..., :3] * b[..., 3:] + backdrop * (1 - b[..., 3:])
    Image.fromarray((shown * 255).astype(np.uint8)).resize((300, 506)).save(out / "swirl_body.png")
    Image.fromarray((detail() * 255).astype(np.uint8)).save(out / "swirl_detail.png")
