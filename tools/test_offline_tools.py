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

    def test_damaged_xlsx_errors_are_chinese_and_original_names_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            broken = Path(directory, "Factory A.xlsx")
            missing = Path(directory, "Factory B.xlsx")
            malformed = Path(directory, "Factory C.xlsx")
            broken.write_bytes(b"broken")
            with zipfile.ZipFile(missing, "w") as archive:
                archive.writestr("unrelated.xml", "<data/>")
            with zipfile.ZipFile(malformed, "w") as archive:
                archive.writestr("xl/workbook.xml", "<broken>")
            paths = [broken, missing, malformed]
            originals = [path.read_bytes() for path in paths]
            result = tools.diagnose({"exports": [{"path": str(path), "name": path.name} for path in paths]})
            self.assertEqual([path.name for path in paths], [item["name"] for item in result["exports"]])
            self.assertTrue(all(not item["ok"] for item in result["exports"]))
            for item in result["exports"]:
                self.assertRegex(item["message"], "[\\u4e00-\\u9fff]")
                self.assertNotIn("File is not a zip file", item["message"])
                self.assertNotIn("There is no item named", item["message"])
                self.assertNotIn("no element found", item["message"])
            self.assertEqual(originals, [path.read_bytes() for path in paths])
            self.assertIn("不能证明 PDF 完整可解析或标签提取准确", result["report"])

    def test_invalid_file_list_is_rejected_with_chinese_message(self):
        with self.assertRaisesRegex(tools.ToolValidationError, "文件列表格式无效"):
            tools.diagnose({"documents": "not a list"})


if __name__ == "__main__":
    unittest.main()
