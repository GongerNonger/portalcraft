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


def body() -> np.ndarray:
    """RGBA floats 0..1: the spiral, and in alpha the portal's shape."""
    rng = np.random.default_rng(7)
    y, x = np.mgrid[0:SIZE, 0:SIZE] / (SIZE - 1) * 2 - 1
    r, theta = np.hypot(x, y), np.arctan2(y, x)
    lumpy = _noise(rng, SIZE, 6) * 0.6 + _noise(rng, SIZE, 14) * 0.4
    # three arms winding in to the middle, their edges broken up
    arms = np.sin(3 * theta + 9.0 * np.log(r + 0.08) + (lumpy - 0.5) * 3.0)
    arms = np.clip(arms * 0.5 + 0.5, 0, 1) ** 1.6
    fine = np.sin(7 * theta - 14.0 * np.log(r + 0.05) + lumpy * 5.0) * 0.5 + 0.5
    deep, mid, pale, hot = np.array([0.10, 0.52, 0.05]), np.array([0.30, 0.86, 0.12]), np.array([0.72, 1.0, 0.36]), np.array([0.95, 1.0, 0.72])
    rgb = _mix(np.broadcast_to(mid, (SIZE, SIZE, 3)), pale, arms)
    rgb = _mix(rgb, hot, np.clip(arms * fine * 1.5 - 0.75, 0, 1))
    rgb = _mix(rgb, deep, np.clip((1 - arms) * (1 - fine) * 1.3 - 0.45, 0, 1) * 0.8)
    rgb = _mix(rgb, hot, np.clip(1 - r / 0.22, 0, 1) ** 2 * 0.7)  # a bright eye
    # the edge: wobbling in and out round the rim, feathered, with a pale lip just inside it
    # (out to the rectangle's sides: the hole a linked portal cuts is the oval that just fits it, and showed round a smaller swirl)
    wobble = 0.985 + 0.025 * np.sin(5 * theta + 1.0) + 0.018 * np.sin(11 * theta + 2.2) + 0.012 * np.sin(17 * theta)
    alpha = np.clip((wobble - r) / 0.05, 0, 1)
    rgb = _mix(rgb, pale, np.clip(1 - np.abs(wobble - 0.06 - r) / 0.06, 0, 1) * 0.6)
    return np.dstack([np.clip(rgb, 0, 1), alpha])


def detail() -> np.ndarray:
    """RGB floats 0..1 around mid grey (what a detail texture multiplies by, doubled): finer streaks."""
    rng = np.random.default_rng(11)
    y, x = np.mgrid[0:SIZE, 0:SIZE] / (SIZE - 1) * 2 - 1
    r, theta = np.hypot(x, y), np.arctan2(y, x)
    lumpy = _noise(rng, SIZE, 10)
    streaks = np.sin(5 * theta + 12.0 * np.log(r + 0.06) + lumpy * 6.0) * 0.5 + 0.5
    v = 0.5 + (streaks - 0.5) * 0.45 * np.clip(r / 0.25, 0, 1)  # (even in the middle, which doesn't turn)
    return np.dstack([v, v, v])


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
