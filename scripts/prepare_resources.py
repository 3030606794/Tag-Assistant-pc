"""Cloud packaging: pinned CPython runtime, offline helpers and original SVG branding."""
import argparse
import hashlib
import math
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "desktop" / "build" / "app-resources" / "common"
PYTHON_URL = "https://www.python.org/ftp/python/3.13.9/python-3.13.9-embed-amd64.zip"
PYTHON_SHA256 = "91d828c2da3a029b41699e918674a0cb379c02cf20dab9c501306885f837402a"


def icon(output_directory: Path | None = None):
    from PIL import Image, ImageDraw
    from fontTools.pens.basePen import BasePen
    from fontTools.svgLib.path import parse_path
    # A continuous superellipse gives the white tile an Apple-style silhouette.
    # Exponent 4.5 corresponds to about a 24% corner radius at the diagonal, with
    # smoothly changing curvature rather than a circular corner joined to a line.
    # Render large, then downsample all ICO sizes so transparent edges remain smooth.
    resolution = 2048
    image = Image.new("RGBA", (resolution, resolution), "white")
    scale = resolution / 1024
    tile = Image.new("L", image.size, 0)
    center = (resolution - 1) / 2
    perimeter = []
    for point in range(2048):
        angle = 2 * math.pi * point / 2048
        cosine, sine = math.cos(angle), math.sin(angle)
        perimeter.append((
            center + center * math.copysign(abs(cosine) ** (2 / 4.5), cosine),
            center + center * math.copysign(abs(sine) ** (2 / 4.5), sine),
        ))
    ImageDraw.Draw(tile).polygon(perimeter, fill=255)

    class Pen(BasePen):
        def __init__(self):
            super().__init__(None)
            self.points = []
        def _moveTo(self, p):
            self.points = [p]
        def _lineTo(self, p):
            self.points.append(p)
        def _curveToOne(self, p1, p2, p3):
            p0 = self._getCurrentPoint()
            for i in range(1, 33):
                t = i / 32
                self.points.append(tuple((1-t)**3*p0[j] + 3*(1-t)**2*t*p1[j] + 3*(1-t)*t*t*p2[j] + t**3*p3[j] for j in range(2)))
        def _endPath(self):
            self._closePath()
    svg = ET.parse(ROOT / "desktop" / "src" / "main" / "resources" / "branding" / "label.svg").getroot()
    # Rasterize compound contours with SVG even/odd holes preserved by separate masks.
    from PIL import ImageChops
    mask = Image.new("1", image.size)
    class MaskPen(Pen):
        def _closePath(self):
            if self.points:
                contour = Image.new("1", image.size)
                ImageDraw.Draw(contour).polygon([((x*.72+143)*scale, (y*.72+143)*scale) for x,y in self.points], fill=1)
                nonlocal mask
                mask = ImageChops.logical_xor(mask, contour)
                self.points = []
    for path in svg.findall("{http://www.w3.org/2000/svg}path"):
        parse_path(path.attrib["d"], MaskPen())
    image.paste("black", mask=mask)
    image.putalpha(tile)
    sizes = [(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)]
    frames = []
    for size in sizes:
        frame = image.resize(size, Image.Resampling.LANCZOS)
        # Remove only Lanczos' near-zero / near-opaque ringing. Genuine edge
        # antialiasing remains; distant outside corners are fully transparent.
        frame.putalpha(frame.getchannel("A").point(lambda value: 0 if value < 4 else 255 if value > 251 else value))
        frames.append(frame)
    directory = output_directory or ROOT / "desktop" / "src" / "main" / "resources" / "branding"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / "app.ico"
    frames[-1].save(target, sizes=sizes, append_images=frames[:-1])
    frames[-1].save(target.with_suffix(".png"))
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--icon-only", action="store_true", help="Convert only the original SVG icon; skip runtime preparation.")
    parser.add_argument("--icon-output", type=Path, help="Output folder for a standalone icon preview.")
    arguments = parser.parse_args()
    if arguments.icon_only:
        target = icon(arguments.icon_output)
        print(f"Prepared white/black continuous-corner icon: {target}")
        return
    if arguments.icon_output is not None:
        parser.error("--icon-output requires --icon-only")
    from prepare_fonts import prepare_fonts
    prepare_fonts()
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        archive = Path(temporary, "python.zip")
        with urllib.request.urlopen(PYTHON_URL, timeout=60) as response, archive.open("wb") as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != PYTHON_SHA256:
            raise SystemExit("CPython runtime checksum mismatch")
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(OUT / "python")
    (OUT / "tools").mkdir(exist_ok=True)
    shutil.copy2(ROOT / "tools" / "offline_tools.py", OUT / "tools" / "offline_tools.py")
    shutil.copytree(ROOT / "desktop" / "src" / "main" / "resources" / "licenses", OUT / "licenses", dirs_exist_ok=True)
    shutil.copy2(ROOT / "LICENSE", OUT / "licenses" / "project-license.txt")
    target = icon()
    evidence = ROOT / "desktop" / "build" / "previews"
    evidence.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, evidence / "app.ico")
    shutil.copy2(target.with_suffix(".png"), evidence / "app.png")
    print("Prepared verified embedded CPython, offline tool, licenses and white/black continuous-corner label icon.")


if __name__ == "__main__":
    main()
