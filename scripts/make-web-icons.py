#!/usr/bin/env python3
"""Writes the web icons (web/static/icon.svg, maskable.svg) from the same mark as the
Android launcher icon (android/scripts/make-launcher-icons.py): a note page with a
brain and a pen, teal on white, on a teal tile. PNGs: render the SVGs at 192/512."""
import pathlib

TEAL, EDGE = "#3A807A", "#16302E"
PAGE = "M6 2.5h8.6l4.4 4.4v12.6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z"
FOLD = "M14.6 2.5v3.4a1 1 0 0 0 1 1h3.4"
LINES = "M7 16.6h4.6M7 18.8h3"
BRAIN = [
    "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
    "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
    "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4", "M17.599 6.5a3 3 0 0 0 .399-1.375",
    "M6.003 5.125A3 3 0 0 0 6.401 6.5", "M3.477 10.896a4 4 0 0 1 .585-.396", "M19.938 10.5a4 4 0 0 1 .585.396",
]
PEN_BODY = "M15.132 14.332L20.719 19.919"
PEN_NIB = "M13.506 14.332L12.764 11.964L15.132 12.706Z"
PEN_BAND = "M18.951 19.777L20.577 18.151"


def mark():
    brain = "".join(f'<path d="{d}"/>' for d in BRAIN)
    return (
        f'<path d="{PAGE}" fill="#fff" stroke="{EDGE}" stroke-width=".7" stroke-linejoin="round"/>'
        f'<path d="{FOLD}" fill="none" stroke="{EDGE}" stroke-width=".7" stroke-linejoin="round"/>'
        f'<g transform="translate(5 4.3) scale(.5)" fill="none" stroke="{TEAL}" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">{brain}</g>'
        f'<path d="{LINES}" stroke="{TEAL}" stroke-opacity=".55" stroke-width="1.1" stroke-linecap="round"/>'
        f'<path d="{PEN_BODY}" stroke="{EDGE}" stroke-width="3.7" stroke-linecap="round"/>'
        f'<path d="{PEN_BODY}" stroke="{TEAL}" stroke-width="2.3" stroke-linecap="round"/>'
        f'<path d="{PEN_NIB}" fill="{TEAL}" stroke="{EDGE}" stroke-width=".7" stroke-linejoin="round"/>'
        f'<path d="{PEN_BAND}" stroke="{EDGE}" stroke-width=".7"/>'
    )


def svg(radius, scale):
    off = 12 - 12 * scale  # keep the 24-unit mark centred
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
        f'<rect width="24" height="24" rx="{radius}" fill="{TEAL}"/>'
        f'<g transform="translate({off:.2f} {off:.2f}) scale({scale})">{mark()}</g></svg>\n'
    )


root = pathlib.Path(__file__).resolve().parent.parent / "web" / "static"
(root / "icon.svg").write_text(svg(5.4, 0.86))
(root / "maskable.svg").write_text(svg(0, 0.62))  # inside the 80 % safe zone
print("wrote icon.svg and maskable.svg")
