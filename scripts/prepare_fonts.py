"""Prepare pinned, offline Simplified Chinese vector fonts on the cloud runner."""
from pathlib import Path
import hashlib
import json
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCE_REVISION = 'bd8f81ddb5c74d5c8897b36ad88b440266245103'
SOURCE_URL = f'https://raw.githubusercontent.com/google/fonts/{SOURCE_REVISION}/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf'
SOURCE_SHA256 = 'a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da'
LICENSE_SHA256 = '1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9'


def prepare_fonts():
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont

    license_file = ROOT / 'desktop/src/main/resources/licenses/NotoSansSC-OFL.txt'
    assert hashlib.sha256(license_file.read_bytes()).hexdigest() == LICENSE_SHA256, 'Chinese font license changed'
    output = ROOT / 'desktop/src/main/resources/fonts'
    output.mkdir(parents=True, exist_ok=True)
    report = {'source_url': SOURCE_URL, 'source_sha256': SOURCE_SHA256, 'fonts': []}
    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / 'NotoSansSC-variable.ttf'
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response, source.open('wb') as stream:
            while chunk := response.read(128 * 1024):
                stream.write(chunk)
        assert hashlib.sha256(source.read_bytes()).hexdigest() == SOURCE_SHA256, 'Chinese font checksum mismatch'
        for style, weight in [('regular', 400), ('medium', 500), ('bold', 700)]:
            with TTFont(source, recalcTimestamp=False) as variable:
                font = instantiateVariableFont(variable, {'wght': weight}, inplace=False, updateFontNames=True)
            try:
                assert 'fvar' not in font, 'Font must have a static weight'
                assert font['OS/2'].usWeightClass == weight, 'Font weight mismatch'
                cmap = font.getBestCmap()
                assert all(ord(char) in cmap for char in 'EPLAN标签助手电气图纸导入导出设置文件全部收藏0123456789'), 'Required Chinese glyph missing'
                destination = output / f'noto_sans_sc_{style}.ttf'
                font.recalcTimestamp = False
                font.save(destination)
                report['fonts'].append({'file': destination.name, 'weight': weight,
                    'unicode_glyphs': len(cmap), 'bytes': destination.stat().st_size,
                    'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()})
            finally:
                font.close()
    evidence = ROOT / 'desktop/build/previews'
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / 'font-resources.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Prepared verified, offline Noto Sans SC fonts: regular / medium / bold.')


if __name__ == '__main__':
    prepare_fonts()
