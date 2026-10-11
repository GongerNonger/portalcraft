"""Portal's two portals recoloured Rick-and-Morty green, as a folder for Portal's custom/ directory.

Reads the portal colour ramps out of Portal's own package and turns their hue to green in place
(brightness and saturation kept, so the rim still runs from dark to white-hot): the files are
Valve's own, byte for byte, but for the colour of each pixel. Nothing of Valve's is kept in the
repository: the folder is made on this PC from this PC's Portal.

With "closed", the portals also can't be seen through: Portal draws a portal that has no partner
as a swirl of its colour, fading it out as the pair links (the PortalStatic material proxy). The
two overlay materials are written with that held at full, so a linked portal keeps the swirl. It
still works as a portal.

    uv run --no-project --with vpk python tools/green-portals/make_green_portals.py <Portal folder> <out folder> [closed]
"""
import colorsys
import re
import struct
import sys
from pathlib import Path

import vpk

GREEN_HUE = 105 / 360  # the show's portals: an acid green, a little to the yellow side
BGR888, BGRA8888 = 3, 12
FILES = [
    "materials/models/portals/portal-blue-color.vtf",
    "materials/models/portals/portal-blue-color-dx8.vtf",
    "materials/models/portals/portal-orange-color.vtf",
    "materials/models/portals/portal-orange-color-dx8.vtf",
]


def pixels_at(data: bytes) -> int:
    """Where the full-size image data (every mipmap, smallest first) starts: it runs to the end."""
    major, minor, header = struct.unpack_from("<III", data, 4)
    if (major, minor) >= (7, 3):
        for i in range(struct.unpack_from("<I", data, 68)[0]):
            tag, offset = data[80 + i * 8:83 + i * 8], struct.unpack_from("<I", data, 84 + i * 8)[0]
            if tag == b"\x30\x00\x00":
                return offset
        raise ValueError("no image data")
    low_format, low_w, low_h = struct.unpack_from("<iBB", data, 57)
    return header + (0 if low_format == -1 else max(1, (low_w + 3) // 4) * max(1, (low_h + 3) // 4) * 8)


def green(data: bytes) -> bytes:
    fmt = struct.unpack_from("<i", data, 52)[0]
    step = {BGR888: 3, BGRA8888: 4}[fmt]
    out = bytearray(data)
    for i in range(pixels_at(data), len(data) - step + 1, step):
        b, g, r = data[i], data[i + 1], data[i + 2]
        _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        r, g, b = colorsys.hsv_to_rgb(GREEN_HUE, s, v)
        out[i], out[i + 1], out[i + 2] = round(b * 255), round(g * 255), round(r * 255)
    return bytes(out)


OVERLAYS = ["materials/models/portals/portalstaticoverlay_1.vmt", "materials/models/portals/portalstaticoverlay_2.vmt"]


def closed(vmt: str) -> str:
    vmt, n = re.subn(r'("\$PortalStatic"\s+)"[0-9.]+"', r'\g<1>"1.0"', vmt, count=1)
    vmt, m = re.subn(r'"PortalStatic"\s*\{[^}]*\}', "", vmt, count=1)
    if n != 1 or m != 1:
        raise ValueError("the overlay material isn't laid out as expected")
    return vmt


def main() -> None:
    portal, out = Path(sys.argv[1]), Path(sys.argv[2])
    pak = vpk.open(str(portal / "portal" / "portal_pak_dir.vpk"))
    for name in FILES:
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(green(pak[name].read()))
        print("wrote", target)
    if "closed" in sys.argv[3:]:
        for name in OVERLAYS:
            target = out / name
            target.write_text(closed(pak[name].read().decode("latin1")), encoding="latin1", newline="")
            print("wrote", target)


if __name__ == "__main__":
    main()
