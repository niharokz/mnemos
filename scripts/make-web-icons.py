#!/usr/bin/env python3
"""Writes the web icons from the Mnemos mark (same shapes as android/scripts/make-launcher-icons.py
and Logo.kt): a note page with a folded corner and three lines, nss palette on the dark tile.
Then render the PNGs (icon-192/512, maskable-512, favicon.ico) with any SVG tool, e.g.
  rsvg-convert -w 192 web/static/icon.svg > web/static/icon-192.png"""
import pathlib

PAGE = "M7.31 4.31H13.88L17.44 7.88V19.13Q17.44 19.69 16.88 19.69H7.88Q7.31 19.69 7.31 19.13Z"
FOLD = "M13.88 4.31L17.44 7.88H14.44Q13.88 7.88 13.88 7.31Z"
LINES = ["M12.28 11.63H14.81", "M10.22 14.34H14.81", "M10.22 17.06H12.75"]
BG, TEAL, MINT, LILAC = "#070b0f", "#3a807a", "#66bdb3", "#a9b8ff"


def mark():
    lines = "".join(f'<path d="{d}"/>' for d in LINES)
    return (f'<path d="{PAGE}" fill="none" stroke="{TEAL}" stroke-width="1.31" stroke-linejoin="round"/>'
            f'<path d="{FOLD}" fill="{LILAC}" stroke="{LILAC}" stroke-width=".56" stroke-linejoin="round"/>'
            f'<circle cx="10.22" cy="11.63" r=".84" fill="{LILAC}"/>'
            f'<g stroke="{MINT}" stroke-width="1.31" stroke-linecap="round">{lines}</g>')


def svg(radius, scale):
    # centre of the mark is (12.375, 12); keep it centred at any scale
    tx, ty = 12 - 12.375 * scale, 12 - 12 * scale
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
            f'<rect width="24" height="24" rx="{radius}" fill="{BG}"/>'
            f'<g transform="translate({tx:.3f} {ty:.3f}) scale({scale})">{mark()}</g></svg>\n')


root = pathlib.Path(__file__).resolve().parent.parent / "web" / "static"
(root / "icon.svg").write_text(svg(5.25, 1.0))
(root / "maskable.svg").write_text(svg(0, 0.8))  # inside the 80 % safe zone
print("wrote icon.svg and maskable.svg")
