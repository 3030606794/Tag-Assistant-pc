"""Bundled, network-free helpers. Input/output: one UTF-8 JSON document on stdio.

Only explicitly supplied files are read. This helper never edits application data.
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile
import xml.etree.ElementTree as ET

VERSION = 1
MAX_REQUEST_BYTES = 4 * 1024 * 1024
MAX_XML_BYTES = 64 * 1024 * 1024
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def check_pdf(item):
    path = Path(item["path"])
    if not path.is_file():
        return {"ok": False, "name": item.get("name", path.name), "message": "导入副本不存在"}
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        first = stream.read(1024)
        if b"%PDF-" not in first:
            return {"ok": False, "name": item.get("name", path.name), "message": "文件头不是 PDF"}
        digest.update(first)
        size += len(first)
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    expected = item.get("bytes")
    changed = expected is not None and int(expected) != size
    return {"ok": not changed, "name": item.get("name", path.name), "bytes": size,
            "sha256": digest.hexdigest(), "message": "大小与导入记录不同" if changed else "文件存在，PDF 文件头和大小正常"}


def read_xml(archive, name):
    info = archive.getinfo(name)
    if info.file_size > MAX_XML_BYTES:
        raise ValueError("Excel XML 超过本工具的检查上限")
    with archive.open(info) as stream:
        return ET.parse(stream).getroot()


def check_xlsx(item):
    path = Path(item["path"])
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 10000:
            raise ValueError("Excel 内部文件过多")
        workbook = read_xml(archive, "xl/workbook.xml")
        sheets = workbook.findall("s:sheets/s:sheet", NS)
        if len(sheets) != 1:
            raise ValueError("标签文件应只有一个工作表")
        if "xl/worksheets/sheet1.xml" not in archive.namelist():
            raise ValueError("缺少标签工作表")
        sheet = read_xml(archive, "xl/worksheets/sheet1.xml")
        cells = sheet.findall("s:sheetData/s:row/s:c", NS)
        formulas = sum(c.find("s:f", NS) is not None for c in cells)
        text_cells = sum(c.get("t") in ("inlineStr", "s", "str") for c in cells)
        headers = ["".join(c.itertext()) for c in cells if c.get("r", "").rstrip("0123456789") + "1" == c.get("r")]
        return {"ok": formulas == 0 and text_cells == len(cells), "name": item.get("name", path.name),
                "sheet": sheets[0].get("name"), "cells": len(cells), "textCells": text_cells,
                "formulas": formulas, "headers": headers,
                "message": "单工作表、文本单元格检查通过" if formulas == 0 and text_cells == len(cells)
                           else "发现公式或非文本单元格，请检查文件是否经过外部编辑"}


def diagnose(request):
    documents, exports = [], []
    for item in request.get("documents", []):
        try:
            documents.append(check_pdf(item))
        except (OSError, ValueError, KeyError) as error:
            documents.append({"ok": False, "name": item.get("name", "PDF"), "message": str(error)})
    for item in request.get("exports", []):
        try:
            exports.append(check_xlsx(item))
        except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
            exports.append({"ok": False, "name": item.get("name", "Excel"), "message": str(error)})
    hashes = {}
    for item in documents:
        if item.get("sha256"):
            hashes.setdefault(item["sha256"], []).append(item["name"])
    duplicates = [names for names in hashes.values() if len(names) > 1]
    lines = ["离线资料检查", "", f"图纸副本：{len(documents)} 份；Excel：{len(exports)} 份。", ""]
    for section, items in (("PDF 文件", documents), ("Excel 文件", exports)):
        lines.append(section)
        if not items:
            lines.append("暂无文件。")
        for item in items:
            lines.append(f"{'通过' if item['ok'] else '需查看'} · {item['name']}：{item['message']}")
            if "cells" in item:
                lines.append(f"  {item['cells']} 个单元格；{item['formulas']} 个公式；表头：{'、'.join(item['headers'])}")
        lines.append("")
    if duplicates:
        lines.append("内容完全相同的图纸（仅提示，不删除）：")
        lines.extend("、".join(group) for group in duplicates)
        lines.append("")
    lines.append("此检查确认文件完整性与 Excel 基本结构；标签内容、归属和安装顺序以实际图纸为依据。")
    return {"version": VERSION, "ok": True, "report": "\n".join(lines), "documents": documents,
            "exports": exports, "duplicates": duplicates}


def main():
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        raw = sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1)
        if len(raw) > MAX_REQUEST_BYTES:
            raise ValueError("工具请求过大")
        request = json.loads(raw.decode("utf-8"))
        if request.get("version") != VERSION or request.get("operation") != "diagnose":
            raise ValueError("不支持的工具协议")
        if len(request.get("documents", [])) + len(request.get("exports", [])) > 20000:
            raise ValueError("单次文件数过多，请分批检查")
        response = diagnose(request)
    except Exception as error:
        response = {"version": VERSION, "ok": False, "error": str(error)}
    print(json.dumps(response, ensure_ascii=False))


if __name__ == "__main__":
    main()
