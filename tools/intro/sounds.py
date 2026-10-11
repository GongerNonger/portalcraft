"""The green portal's three sounds, made here: it opening, something coming through it, it closing.

Our own, in the manner of a cartoon portal (a wet, wobbling sweep with bubbles in it), not anyone's
recording. To use recordings of your own instead, put them beside this file as open.wav, pass.wav
and close.wav (44.1 kHz, 16-bit): make_intro.py takes those when they are there.

    uv run --no-project --with numpy python tools/intro/sounds.py <out folder>   (writes the three to listen to)
"""
import struct
import sys
from pathlib import Path

import numpy as np

RATE = 44100
NAMES = ("open", "pass", "close")


def _t(seconds: float) -> np.ndarray:
    return np.arange(int(RATE * seconds)) / RATE


def _sweep(t: np.ndarray, start: float, end: float, wobble_hz: float, wobble: float, curve: float = 1.0) -> np.ndarray:
    """A tone gliding from start to end Hz (curve bends the glide), its pitch wobbling."""
    u = (t / t[-1]) ** curve
    freq = start * (end / start) ** u * (1 + wobble * np.sin(2 * np.pi * wobble_hz * t))
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    return np.sin(phase) + 0.35 * np.sin(2 * phase) + 0.12 * np.sin(3 * phase)


def _env(t: np.ndarray, attack: float, release: float) -> np.ndarray:
    return np.clip(t / attack, 0, 1) * np.clip((t[-1] - t) / release, 0, 1)


def _whoosh(t: np.ndarray, rng: np.random.Generator, low: float, high: float) -> np.ndarray:
    """Noise through a band that slides from low to high Hz."""
    noise, out = rng.standard_normal(len(t)), np.zeros(len(t))
    lp = bp = 0.0
    centre = low * (high / low) ** (t / t[-1])
    for i in range(len(t)):  # a state-variable filter, its centre moving
        f = 2 * np.sin(np.pi * min(centre[i], RATE / 6) / RATE)
        lp += f * bp
        bp += f * (noise[i] - lp - 0.35 * bp)
        out[i] = bp
    return out / (np.abs(out).max() + 1e-9)


def _bubbles(t: np.ndarray, rng: np.random.Generator, count: int, low: float, high: float) -> np.ndarray:
    """Little rising blips at scattered moments: the bubbling."""
    out = np.zeros(len(t))
    for _ in range(count):
        at, length, pitch = rng.uniform(0.05, t[-1] - 0.12), rng.uniform(0.035, 0.09), rng.uniform(low, high)
        n = int(length * RATE)
        k = np.arange(n) / RATE
        blip = np.sin(2 * np.pi * pitch * (1 + 6 * k) * k) * np.exp(-k / (length / 3)) * rng.uniform(0.3, 1.0)
        i = int(at * RATE)
        out[i:i + n] += blip[: len(out) - i]
    return out


def make(name: str) -> np.ndarray:
    rng = np.random.default_rng({"open": 3, "pass": 5, "close": 8}[name])
    if name == "open":  # swells up out of nothing, bubbling, and settles into a hum
        t = _t(1.25)
        s = 0.55 * _sweep(t, 95, 430, 13, 0.06, 0.6) * _env(t, 0.12, 0.5)
        s += 0.30 * _whoosh(t, rng, 300, 2600) * _env(t, 0.25, 0.6)
        s += 0.32 * _bubbles(t, rng, 16, 350, 1100) * _env(t, 0.1, 0.3)
    elif name == "pass":  # a quick wet gulp
        t = _t(0.42)
        s = 0.6 * _sweep(t, 520, 150, 22, 0.10, 0.7) * _env(t, 0.015, 0.25)
        s += 0.35 * _whoosh(t, rng, 2400, 500) * _env(t, 0.02, 0.3)
        s += 0.3 * _bubbles(t, rng, 5, 500, 1300)
    else:  # drawn shut, falling, with a last pop
        t = _t(0.8)
        s = 0.6 * _sweep(t, 460, 70, 16, 0.07, 1.6) * _env(t, 0.02, 0.25)
        s += 0.28 * _whoosh(t, rng, 2800, 250) * _env(t, 0.03, 0.4)
        s += 0.25 * _bubbles(t, rng, 7, 300, 900) * _env(t, 0.02, 0.4)
        k = _t(0.07)
        pop = np.sin(2 * np.pi * 180 * (1 - 5 * k) * k) * np.exp(-k / 0.018)
        i = int(0.66 * RATE)
        s[i:i + len(pop)] += 0.7 * pop
    return 0.89 * s / np.abs(s).max()


def wav(samples: np.ndarray) -> bytes:
    """Mono, 16-bit, 44.1 kHz: what the game plays as it is."""
    data = (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()
    return b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16) + b"data" + struct.pack("<I", len(data)) + data


def write(folder: Path) -> None:
    """The three into folder as intro_<name>.wav: recordings beside this file if there are any, else made."""
    folder.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        own = Path(__file__).parent / f"{name}.wav"
        (folder / f"intro_{name}.wav").write_bytes(own.read_bytes() if own.exists() else wav(make(name)))


if __name__ == "__main__":
    write(Path(sys.argv[1]))
