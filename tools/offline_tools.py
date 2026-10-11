"""Bundled, network-free helpers. Input/output: one UTF-8 JSON document on stdio.

Only explicitly supplied files are read. This helper never edits application data.
"""
import hashlib
import json
from pathlib import Path
import sys
import zipfile
import xml.etree.ElementTree as ET

VERSION = 1
MAX_REQUEST_BYTES = 4 * 1024 * 1024
MAX_XML_BYTES = 64 * 1024 * 1024
NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


class ToolValidationError(ValueError):
    """A deliberate, user-facing Chinese validation message."""


def user_error(error):
    """Keep Python/Windows exception wording out of the user-visible report."""
    if isinstance(error, ToolValidationError):
        return str(error)
    if isinstance(error, FileNotFoundError):
        return "文件不存在，请刷新文件列表后重试"
    if isinstance(error, PermissionError):
        return "无法读取文件，请检查权限或关闭占用文件的程序后重试"
    if isinstance(error, zipfile.BadZipFile):
        return "Excel 文件格式无效或已经损坏，无法读取内部结构"
    if isinstance(error, ET.ParseError):
        return "Excel 内部表格数据格式无效，文件可能已经损坏"
    if isinstance(error, json.JSONDecodeError):
        return "离线检查请求格式无效，请重新开始检查"
    if isinstance(error, UnicodeError):
        return "离线检查文字编码无效，请重新开始检查"
    if isinstance(error, KeyError):
        return "缺少检查所需的文件字段或 Excel 内部文件"
    if isinstance(error, (TypeError, ValueError)):
        return "检查参数或文件内容格式无效，请刷新后重试"
    if isinstance(error, OSError):
        return "暂时无法读取文件，请检查文件是否被占用以及磁盘是否可用"
    return "离线检查未能完成，请重新启动应用后重试"


def file_items(request, key):
    items = request.get(key, [])
    if not isinstance(items, list) or any(not isinstance(item, dict) for item in items):
        raise ToolValidationError("离线检查文件列表格式无效，请重新开始检查")
    return items


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
        raise ToolValidationError("Excel 内部表格数据超过本工具的检查上限")
    with archive.open(info) as stream:
        return ET.parse(stream).getroot()


def check_xlsx(item):
    path = Path(item["path"])
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > 10000:
            raise ToolValidationError("Excel 内部文件过多")
        workbook = read_xml(archive, "xl/workbook.xml")
        sheets = workbook.findall("s:sheets/s:sheet", NS)
        if len(sheets) != 1:
            raise ToolValidationError("标签文件应只有一个工作表")
        if "xl/worksheets/sheet1.xml" not in archive.namelist():
            raise ToolValidationError("缺少标签工作表")
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
    for item in file_items(request, "documents"):
        try:
            documents.append(check_pdf(item))
        except (OSError, TypeError, ValueError, KeyError) as error:
            documents.append({"ok": False, "name": item.get("name", "PDF"), "message": user_error(error)})
    for item in file_items(request, "exports"):
        try:
            exports.append(check_xlsx(item))
        except (OSError, TypeError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
            exports.append({"ok": False, "name": item.get("name", "Excel"), "message": user_error(error)})
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
    lines.append("此检查仅查看文件存在性、PDF 文件头和大小、重复内容及 Excel 基本结构，不能证明 PDF 完整可解析或标签提取准确；标签内容、归属和安装顺序以实际图纸为依据。")
    return {"version": VERSION, "ok": True, "report": "\n".join(lines), "documents": documents,
            "exports": exports, "duplicates": duplicates}


def main():
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        raw = sys.stdin.buffer.read(MAX_REQUEST_BYTES + 1)
        if len(raw) > MAX_REQUEST_BYTES:
            raise ToolValidationError("工具请求过大")
        request = json.loads(raw.decode("utf-8"))
        if not isinstance(request, dict):
            raise ToolValidationError("离线检查请求格式无效，请重新开始检查")
        if request.get("version") != VERSION or request.get("operation") != "diagnose":
            raise ToolValidationError("离线工具版本不兼容，请重新安装完整版本")
        if len(file_items(request, "documents")) + len(file_items(request, "exports")) > 20000:
            raise ToolValidationError("单次文件数过多，请分批检查")
        response = diagnose(request)
    except Exception as error:
        response = {"version": VERSION, "ok": False, "error": user_error(error)}
    print(json.dumps(response, ensure_ascii=False))


if __name__ == "__main__":
    main()
