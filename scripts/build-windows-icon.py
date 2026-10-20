"""Regenerate the Windows ICO from its SVG using CairoSVG (pip install cairosvg).

Only needed when changing the artwork; normal builds use the checked-in ICO.
"""

import struct
from pathlib import Path

import cairosvg


assets = Path(__file__).resolve().parents[1] / "windows" / "Assets"
sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
images = [
    # This checked-in SVG references the local Android artwork.
    cairosvg.svg2png(url=str(assets / "CodexRemote.svg"), unsafe=True,
                    output_width=size, output_height=size)
    for size in sizes
]
offset = 6 + 16 * len(sizes)
entries = []
for size, image in zip(sizes, images):
    entries.append(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0,
                               1, 32, len(image), offset))
    offset += len(image)
(assets / "CodexRemote.ico").write_bytes(
    struct.pack("<HHH", 0, 1, len(sizes)) + b"".join(entries) + b"".join(images)
)
