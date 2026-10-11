"""Portal's two portals recoloured Rick-and-Morty green, as a folder for Portal's custom/ directory.

Reads the portal colour ramps out of Portal's own package and turns their hue to green in place
(brightness and saturation kept, so the rim still runs from dark to white-hot): the files are
Valve's own, byte for byte, but for the colour of each pixel. Nothing of Valve's is kept in the
repository: the folder is made on this PC from this PC's Portal.

With "closed", the portals also can't be seen through: Portal draws a portal that has no partner
as a swirl of its colour, fading it out as the pair links (the PortalStatic material proxy). The
two overlay materials are written with that held at full, so a linked portal keeps the swirl. It
still works as a portal.

With "swirl", the portals are the show's: a turning green spiral with a soft wobbling edge
(swirl.py draws it), in place of Portal's own overlay material, and not seen through either.

    uv run --no-project --with vpk --with numpy python tools/green-portals/make_green_portals.py <Portal folder> <out folder> [closed|swirl]
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


SWIRL_VMT = """"UnlitGeneric"
{
	// PortalCraft: the Rick-and-Morty portal. Two layers of a spiral, turned against each other.
	"$basetexture" "models/portals/pc_swirl"
	"$translucent" "1"
	"$detail" "models/portals/pc_swirl_detail"
	"$detailscale" "1"
	"$detailblendmode" "0"
	"$detailblendfactor" "1"
	"$center" "[0.5 0.5]"
	"$turn" "0"
	"$turnback" "0"
	"Proxies"
	{
		"LinearRamp"
		{
			"rate" "%s"
			"initialValue" "0"
			"resultVar" "$turn"
		}
		"TextureTransform"
		{
			"centerVar" "$center"
			"rotateVar" "$turn"
			"resultVar" "$basetexturetransform"
		}
		"LinearRamp"
		{
			"rate" "%s"
			"initialValue" "0"
			"resultVar" "$turnback"
		}
		"TextureTransform"
		{
			"centerVar" "$center"
			"rotateVar" "$turnback"
			"resultVar" "$detailtexturetransform"
		}
	}
}
"""
CLAMPED = 0x4 | 0x8 | 0x100 | 0x200 | 0x2000  # clamp s, clamp t, no mipmaps, no lod, eight-bit alpha


def swirl(out: Path) -> None:
    sys.path.insert(0, str(Path(__file__).parent))
    import swirl as art

    folder = out / "materials/models/portals"
    (folder / "pc_swirl.vtf").write_bytes(art.vtf(art.body(), CLAMPED))
    (folder / "pc_swirl_detail.vtf").write_bytes(art.vtf(art.detail(), CLAMPED))
    for name, rates in zip(OVERLAYS, (("-70", "110"), ("70", "-110"))):
        (out / name).write_text(SWIRL_VMT % rates, encoding="latin1", newline="")
        print("wrote", out / name)


def main() -> None:
    portal, out = Path(sys.argv[1]), Path(sys.argv[2])
    pak = vpk.open(str(portal / "portal" / "portal_pak_dir.vpk"))
    for name in FILES:
        target = out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(green(pak[name].read()))
        print("wrote", target)
    if "swirl" in sys.argv[3:]:
        swirl(out)
    if "closed" in sys.argv[3:]:
        for name in OVERLAYS:
            target = out / name
            target.write_text(closed(pak[name].read().decode("latin1")), encoding="latin1", newline="")
            print("wrote", target)


if __name__ == "__main__":
    main()
