import importlib.util
import tempfile
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("offline_tools", Path(__file__).with_name("offline_tools.py"))
tools = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tools)


class OfflineToolsTests(unittest.TestCase):
    def test_duplicate_detection_keeps_both_originals(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory, "第一份.pdf"), Path(directory, "第二份.pdf")]
            for path in paths:
                path.write_bytes(b"%PDF-1.7\nlocal content")
            result = tools.diagnose({"documents": [{"path": str(p), "name": p.name, "bytes": p.stat().st_size} for p in paths]})
            self.assertEqual([[p.name for p in paths]], result["duplicates"])
            self.assertTrue(all(p.exists() for p in paths))

    def test_missing_or_changed_document_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "图纸.pdf")
            path.write_bytes(b"%PDF-1.7\n")
            self.assertFalse(tools.check_pdf({"path": str(path), "bytes": 500})["ok"])
            self.assertFalse(tools.check_pdf({"path": str(path) + ".missing"})["ok"])

    def test_xlsx_text_and_formula_are_distinguished(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "labels.xlsx")
            ns = tools.NS["s"]
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("xl/workbook.xml", f'<workbook xmlns="{ns}"><sheets><sheet name="标签"/></sheets></workbook>')
                archive.writestr("xl/worksheets/sheet1.xml", f'<worksheet xmlns="{ns}"><sheetData><row><c r="A1" t="inlineStr"><is><t>三列</t></is></c><c r="A2"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>')
            checked = tools.check_xlsx({"path": str(path)})
            self.assertFalse(checked["ok"])
            self.assertEqual(1, checked["formulas"])
            self.assertEqual(["三列"], checked["headers"])

    def test_corrupt_zip_does_not_abort_other_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "broken.xlsx")
            path.write_bytes(b"broken")
            result = tools.diagnose({"exports": [{"path": str(path)}]})
            self.assertTrue(result["ok"])
            self.assertFalse(result["exports"][0]["ok"])


if __name__ == "__main__":
    unittest.main()
