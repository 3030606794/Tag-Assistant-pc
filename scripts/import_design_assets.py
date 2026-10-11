"""One-time vector/font asset migration; no Android compilation or user data is involved."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT.parent / "eplan-pocket" / "app" / "src" / "main"
DEST = ROOT / "desktop" / "src" / "main" / "resources"
A = "{http://schemas.android.com/apk/res/android}"

def convert(node):
    tag = node.tag.rsplit("}", 1)[-1]
    if tag == "path":
        return ET.Element("path", {"d": node.get(A + "pathData", ""), "fill": "#000000"})
    group = ET.Element("g")
    tx, ty = node.get(A + "translateX", "0"), node.get(A + "translateY", "0")
    sx, sy = node.get(A + "scaleX", "1"), node.get(A + "scaleY", "1")
    transforms = [f"translate({tx},{ty})", f"scale({sx},{sy})"]
    if node.get(A + "rotation"):
        transforms.insert(1, "rotate(%s,%s,%s)" % (node.get(A + "rotation"), node.get(A + "pivotX", "0"), node.get(A + "pivotY", "0")))
    group.set("transform", " ".join(transforms))
    for child in node:
        group.append(convert(child))
    return group

def main():
    (DEST / "symbols").mkdir(parents=True, exist_ok=True)
    (DEST / "fonts").mkdir(parents=True, exist_ok=True)
    (DEST / "branding").mkdir(parents=True, exist_ok=True)
    (DEST / "licenses").mkdir(parents=True, exist_ok=True)
    for source in (ANDROID / "res" / "drawable").glob("symbol_*.xml"):
        vector = ET.parse(source).getroot()
        svg = ET.Element("svg", {"xmlns": "http://www.w3.org/2000/svg", "width": "24", "height": "24", "viewBox": "0 0 %s %s" % (vector.get(A + "viewportWidth"), vector.get(A + "viewportHeight"))})
        for child in vector:
            svg.append(convert(child))
        ET.ElementTree(svg).write(DEST / "symbols" / (source.stem.removeprefix("symbol_") + ".svg"), encoding="utf-8", xml_declaration=True)
    for source in (ANDROID / "res" / "font").glob("roboto_*.ttf"):
        shutil.copy2(source, DEST / "fonts" / source.name)
    for source in (ANDROID / "assets" / "licenses").glob("*.txt"):
        shutil.copy2(source, DEST / "licenses" / source.name)
    shutil.copy2(ANDROID / "assets" / "branding" / "label.svg", DEST / "branding" / "label.svg")
    # Theme code is shared design data; only its platform-specific font loading changes.
    theme = (ANDROID / "java" / "cn" / "guangyao" / "eplan" / "ComposeTheme.kt").read_text(encoding="utf-8")
    start = theme.index("// All product colors")
    theme = theme[:theme.index("data class ThemePreferenceControls")] + theme[start:]
    theme = theme.replace("import androidx.compose.ui.text.font.Font\n", "import androidx.compose.ui.text.platform.Font\n")
    for weight in ("regular", "medium", "bold"):
        theme = theme.replace("R.font.roboto_" + weight, '"fonts/roboto_' + weight + '.ttf"')
    theme = theme.replace("fun PocketTheme(", "fun DesktopTheme(")
    target = ROOT / "desktop" / "src" / "main" / "kotlin" / "cn" / "guangyao" / "eplan" / "DesktopTheme.kt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(theme, encoding="utf-8")
    print("Migrated Purple theme, Roboto, Rounded SVG symbols and original label branding.")

if __name__ == "__main__":
    main()
