"""Writes the image fixtures of oneechan.host.platform.GraphicsSelfTest.

    uv run --with pillow python -I android-compat/scripts/make_graphics_fixtures.py

The pixel patterns are mirrored in GraphicsSelfTest.kt (Patterns); change both together.
HEIC/AVIF come from macOS `sips` (ImageIO), since only the native backend decodes them.
"""

import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/oneechan/host/platform/graphics-selftest"


def rgba(x: int, y: int) -> tuple[int, int, int, int]:
    a = 255 if x % 4 == 0 else (x * 9 + y * 17 + 1) & 0xFF
    return ((x * 11 + y * 3) & 0xFF, (x * 5 + y * 13) & 0xFF, (x * x + y * 7) & 0xFF, a)


def smooth(x: int, y: int) -> tuple[int, int, int]:
    return (x * 4, y * 5, (x + y) * 2)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    pattern = Image.new("RGBA", (23, 17))
    pattern.putdata([rgba(x, y) for y in range(17) for x in range(23)])
    pattern.save(OUT / "pattern.png", optimize=True)
    pattern.save(OUT / "pattern.webp", lossless=True, exact=True)

    photo = Image.new("RGB", (64, 48))
    photo.putdata([smooth(x, y) for y in range(48) for x in range(64)])
    photo.save(OUT / "smooth.jpg", quality=95, subsampling=0)
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: rotate 90 degrees clockwise to display.
    photo.save(OUT / "rotated.jpg", quality=95, subsampling=0, exif=exif)

    palette = Image.new("P", (16, 16))
    palette.putpalette([c for i in range(256) for c in ((i % 16) * 16, (i // 16) * 16, 128)])
    palette.putdata(list(range(256)))
    palette.save(OUT / "palette.gif")

    if shutil.which("sips"):
        for fmt, name in (("heic", "smooth.heic"), ("avif", "smooth.avif")):
            png = OUT / "smooth-tmp.png"
            photo.save(png)
            result = subprocess.run(["sips", "-s", "format", fmt, str(png), "--out", str(OUT / name)], capture_output=True, text=True)
            png.unlink()
            if result.returncode != 0:
                print(f"sips could not write {fmt}: {result.stderr.strip()}", file=sys.stderr)
    for path in sorted(OUT.iterdir()):
        print(path.name, path.stat().st_size)


if __name__ == "__main__":
    main()
