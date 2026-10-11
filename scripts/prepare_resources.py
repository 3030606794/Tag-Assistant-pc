"""Cloud packaging: pinned CPython runtime, offline helpers and original SVG branding."""
import hashlib
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


def icon():
    from PIL import Image, ImageDraw
    from fontTools.pens.basePen import BasePen
    from fontTools.svgLib.path import parse_path
    image = Image.new("RGB", (1024, 1024), "white")
    draw = ImageDraw.Draw(image)

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
        def _closePath(self):
            if self.points:
                draw.polygon([(x * .72 + 143, y * .72 + 143) for x, y in self.points], fill="black")
                self.points = []
        def _endPath(self):
            self._closePath()
    svg = ET.parse(ROOT / "desktop" / "src" / "main" / "resources" / "branding" / "label.svg").getroot()
    # Rasterize compound contours with SVG even/odd holes preserved by separate masks.
    from fontTools.pens.recordingPen import RecordingPen
    from PIL import ImageChops
    mask = Image.new("1", image.size)
    class MaskPen(Pen):
        def _closePath(self):
            if self.points:
                contour = Image.new("1", image.size)
                ImageDraw.Draw(contour).polygon([(x*.72+143, y*.72+143) for x,y in self.points], fill=1)
                nonlocal mask
                mask = ImageChops.logical_xor(mask, contour)
                self.points = []
    for path in svg.findall("{http://www.w3.org/2000/svg}path"):
        parse_path(path.attrib["d"], MaskPen())
    image.paste("black", mask=mask)
    image = image.resize((256, 256), Image.Resampling.LANCZOS)
    target = ROOT / "desktop" / "src" / "main" / "resources" / "branding" / "app.ico"
    image.save(target, sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
    image.save(target.with_suffix(".png"))


def main():
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
    icon()
    print("Prepared verified embedded CPython, offline tool, licenses and white/black label icon.")


if __name__ == "__main__":
    main()
