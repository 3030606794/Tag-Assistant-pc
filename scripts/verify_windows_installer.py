"""Read-only checks of the actual MSI and the MSI embedded in jpackage's EXE.

Uses Windows Installer / resource APIs from the Windows SDK, never installs or
executes the application, and writes only its evidence report and extracted MSI.
"""
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import re
import sys


UPGRADE_UUID = 'e80107f9-71c7-44f0-b405-650d3d275768'


class WindowsInstallerReader:
    def __init__(self, path: Path):
        self.api = ctypes.WinDLL('msi', use_last_error=True)
        api = self.api
        handle = wintypes.UINT
        pointer = ctypes.POINTER(handle)
        api.MsiOpenDatabaseW.argtypes = [wintypes.LPCWSTR, ctypes.c_void_p, pointer]
        api.MsiDatabaseOpenViewW.argtypes = [handle, wintypes.LPCWSTR, pointer]
        api.MsiViewExecute.argtypes = [handle, handle]
        api.MsiViewFetch.argtypes = [handle, pointer]
        api.MsiRecordGetStringW.argtypes = [handle, handle, wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        api.MsiGetSummaryInformationW.argtypes = [handle, wintypes.LPCWSTR, handle, pointer]
        api.MsiSummaryInfoGetPropertyW.argtypes = [
            handle, handle, pointer, ctypes.POINTER(ctypes.c_int),
            ctypes.POINTER(wintypes.FILETIME), wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD),
        ]
        api.MsiCloseHandle.argtypes = [handle]
        self.database = handle()
        self.check(api.MsiOpenDatabaseW(str(path.resolve()), None, ctypes.byref(self.database)))

    @staticmethod
    def check(status: int, accepted: tuple[int, ...] = (0,)) -> None:
        if status not in accepted:
            raise OSError(status, f'Windows Installer read failed with code {status}')

    def close(self) -> None:
        self.api.MsiCloseHandle(self.database)

    def string(self, record: wintypes.UINT, index: int) -> str:
        length = wintypes.DWORD(0)
        self.check(self.api.MsiRecordGetStringW(record, index, None, ctypes.byref(length)), (0, 234))
        length.value += 1
        output = ctypes.create_unicode_buffer(length.value)
        self.check(self.api.MsiRecordGetStringW(record, index, output, ctypes.byref(length)))
        return output.value

    def rows(self, table: str, columns: tuple[str, ...]) -> list[dict[str, str]]:
        query = 'SELECT ' + ', '.join(f'`{name}`' for name in columns) + f' FROM `{table}`'
        view = wintypes.UINT()
        self.check(self.api.MsiDatabaseOpenViewW(self.database, query, ctypes.byref(view)))
        result = []
        try:
            self.check(self.api.MsiViewExecute(view, 0))
            while True:
                record = wintypes.UINT()
                status = self.api.MsiViewFetch(view, ctypes.byref(record))
                if status == 259:  # ERROR_NO_MORE_ITEMS
                    return result
                self.check(status)
                try:
                    result.append({name: self.string(record, i + 1) for i, name in enumerate(columns)})
                finally:
                    self.api.MsiCloseHandle(record)
        finally:
            self.api.MsiCloseHandle(view)

    def summary_template(self) -> str:
        summary = wintypes.UINT()
        self.check(self.api.MsiGetSummaryInformationW(self.database, None, 0, ctypes.byref(summary)))
        try:
            data_type = wintypes.UINT()
            integer = ctypes.c_int()
            timestamp = wintypes.FILETIME()
            length = wintypes.DWORD(0)
            args = (summary, 7, ctypes.byref(data_type), ctypes.byref(integer), ctypes.byref(timestamp))
            self.check(self.api.MsiSummaryInfoGetPropertyW(*args, None, ctypes.byref(length)), (0, 234))
            length.value += 1
            text = ctypes.create_unicode_buffer(length.value)
            self.check(self.api.MsiSummaryInfoGetPropertyW(*args, text, ctypes.byref(length)))
            return text.value
        finally:
            self.api.MsiCloseHandle(summary)


def extract_embedded_msi(executable: Path, destination: Path) -> None:
    # JDK 17 WinExeBundler stores the MSI in resource type RT_RCDATA (10), name
    # "msi". LOAD_LIBRARY_AS_DATAFILE reads PE resources without running code.
    api = ctypes.WinDLL('kernel32', use_last_error=True)
    api.LoadLibraryExW.argtypes = [wintypes.LPCWSTR, wintypes.HANDLE, wintypes.DWORD]
    api.LoadLibraryExW.restype = wintypes.HMODULE
    api.FindResourceW.argtypes = [wintypes.HMODULE, wintypes.LPCWSTR, ctypes.c_void_p]
    api.FindResourceW.restype = wintypes.HRSRC
    api.SizeofResource.argtypes = [wintypes.HMODULE, wintypes.HRSRC]
    api.SizeofResource.restype = wintypes.DWORD
    api.LoadResource.argtypes = [wintypes.HMODULE, wintypes.HRSRC]
    api.LoadResource.restype = wintypes.HGLOBAL
    api.LockResource.argtypes = [wintypes.HGLOBAL]
    api.LockResource.restype = ctypes.c_void_p
    api.FreeLibrary.argtypes = [wintypes.HMODULE]
    module = api.LoadLibraryExW(str(executable.resolve()), None, 0x00000002)
    if not module:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        resource = api.FindResourceW(module, 'msi', 10)
        if not resource:
            raise ctypes.WinError(ctypes.get_last_error())
        size = api.SizeofResource(module, resource)
        loaded = api.LoadResource(module, resource)
        pointer = api.LockResource(loaded) if loaded else None
        if not pointer or size < 8:
            raise RuntimeError('jpackage EXE does not contain a readable MSI resource')
        data = ctypes.string_at(pointer, size)
        if not data.startswith(bytes.fromhex('d0cf11e0a1b11ae1')):
            raise RuntimeError('Embedded data is not an MSI compound document')
        destination.write_bytes(data)
    finally:
        api.FreeLibrary(module)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def has_chinese(text: str) -> bool:
    return bool(re.search(r'[\u3400-\u9fff]', text))


def inspect_installer(path: Path, application_name: str, version: str) -> dict:
    database = WindowsInstallerReader(path)
    try:
        properties = {row['Property']: row['Value'] for row in database.rows('Property', ('Property', 'Value'))}
        dialogs = database.rows('Dialog', ('Dialog', 'Title'))
        controls = database.rows('Control', ('Dialog_', 'Control', 'Type', 'Text'))
        shortcuts = database.rows('Shortcut', ('Name', 'Target', 'Directory_'))
        directories = database.rows('Directory', ('Directory', 'Directory_Parent', 'DefaultDir'))
        upgrades = database.rows('Upgrade', ('UpgradeCode', 'Language', 'ActionProperty'))
        template = database.summary_template()
    finally:
        database.close()

    require(properties.get('ProductLanguage') == '2052', 'MSI ProductLanguage must be Simplified Chinese (2052)')
    require(properties.get('ProductName') == application_name, 'Windows installed-app name must be Chinese')
    require(properties.get('ProductVersion') == version, 'Installer version is incorrect')
    require(properties.get('UpgradeCode', '').strip('{}').lower() == UPGRADE_UUID, 'Original PC upgrade UUID was changed')
    require(template.lower().startswith('x64;') and '2052' in template.partition(';')[2].split(','), 'MSI summary must identify x64 / Chinese')
    require(upgrades and all(not row['Language'] for row in upgrades), 'Upgrades must detect the original English package too')
    require(any(row['Directory'] == 'INSTALLDIR' and row['DefaultDir'].split('|')[-1] == 'EplanTagAssistantPC' for row in directories), 'Original installation directory must be preserved')
    require(len(shortcuts) >= 2 and all(row['Name'].split('|')[-1] == application_name for row in shortcuts), 'Desktop and Start Menu shortcuts must use the Chinese application name')

    titles = {row['Dialog']: row['Title'] for row in dialogs}
    for dialog in ('WelcomeDlg', 'LicenseAgreementDlg', 'InstallDirDlg', 'VerifyReadyDlg', 'ExitDialog', 'InstallDirNotEmptyDlg'):
        require(dialog in titles and has_chinese(titles[dialog]) and 'Setup' not in titles[dialog], f'{dialog} title is not Chinese')
    buttons = [row for row in controls if row['Type'] == 'PushButton' and row['Control'] in {'Back', 'Next', 'Cancel', 'Finish', 'Install', 'Yes', 'No'}]
    require(len(buttons) >= 12, 'Expected installer navigation controls are missing')
    require(all(has_chinese(row['Text']) for row in buttons), 'An installer navigation button still contains untranslated English')
    welcome = [row['Text'] for row in controls if row['Dialog_'] == 'WelcomeDlg' and row['Type'] == 'Text']
    require(any(has_chinese(text) for text in welcome), 'Welcome explanation is not Chinese')
    license_controls = [row['Text'] for row in controls if row['Dialog_'] == 'LicenseAgreementDlg' and row['Type'] == 'ScrollableText']
    require(license_controls and any('\\u' in text and 'MIT' in text and 'Permission is hereby granted' in text for text in license_controls), 'License must contain the Chinese reference text and complete original MIT license')

    with path.open('rb') as stream:
        sha256 = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {
        'file': path.name,
        'sha256': sha256,
        'properties': {key: properties.get(key) for key in ('ProductName', 'ProductVersion', 'ProductLanguage', 'UpgradeCode', 'ProductCode')},
        'summary_template': template,
        'dialog_titles': titles,
        'navigation_buttons': buttons,
        'shortcuts': shortcuts,
        'installation_directory': 'EplanTagAssistantPC',
        'cross_language_upgrade': True,
        'chinese_license_with_original_mit': True,
        'passed': True,
    }


def main() -> None:
    # GitHub's English Windows runner otherwise encodes piped stdout as cp1252.
    # This only affects the evidence log; every MSI validation remains mandatory.
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    parser.add_argument('--application-name', default='EPLAN标签助手')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    require(sys.platform == 'win32', 'Windows MSI verification must run on Windows')
    binaries = args.root / 'desktop/build/compose/binaries/main'
    output = args.root / 'desktop/build/previews'
    output.mkdir(parents=True, exist_ok=True)
    installers = list((binaries / 'msi').glob('*.msi'))
    executables = list((binaries / 'exe').glob('*.exe'))
    require(len(installers) == len(executables) == 1, 'Expected exactly one MSI and one EXE installer')
    extracted = args.root / 'desktop/build/installer-zh-CN/embedded-exe.msi'
    extract_embedded_msi(executables[0], extracted)
    standalone = inspect_installer(installers[0], args.application_name, args.version)
    embedded = inspect_installer(extracted, args.application_name, args.version)
    for key in ('ProductName', 'ProductVersion', 'ProductLanguage', 'UpgradeCode'):
        require(standalone['properties'][key] == embedded['properties'][key], f'EXE and MSI disagree on {key}')
    report = {'passed': True, 'standalone_msi': standalone, 'exe_embedded_msi': embedded}
    (output / 'installer-zh-CN.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': True, 'language': '简体中文', 'version': args.version, 'checks': ['MSI语言2052', '中文安装向导', '中文快捷方式', '原升级标识与安装目录', '中文许可及MIT原文', 'EXE内嵌MSI同等验证']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
