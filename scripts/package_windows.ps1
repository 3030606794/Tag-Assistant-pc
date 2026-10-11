param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$ApplicationName = 'EPLAN标签助手'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Windows installers must be packaged on the Windows cloud runner.' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$image = Join-Path $root "desktop/build/compose/binaries/main/app/$ApplicationName"
if (-not (Test-Path -LiteralPath (Join-Path $image "$ApplicationName.exe"))) {
    Get-ChildItem -LiteralPath (Join-Path $root 'desktop/build/compose/binaries/main/app') -Force |
        Select-Object Name, FullName | Format-Table -AutoSize | Out-Host
    throw "The Chinese application launcher is missing from the distributable: $image"
}
$jpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw 'JDK 17 jpackage was not found.' }
$resources = Join-Path $PSScriptRoot 'installer'
$generated = Join-Path $root 'desktop/build/installer-zh-CN'
New-Item -ItemType Directory -Force -Path $generated | Out-Null

# RTF Unicode escapes preserve Chinese on runners with an English system code page.
# The original English MIT license remains complete, both here and in delivery/LICENSE.
$license = (Get-Content -LiteralPath (Join-Path $resources 'LICENSE.zh-CN.txt') -Raw -Encoding utf8) +
    "`n" + (Get-Content -LiteralPath (Join-Path $root 'LICENSE') -Raw -Encoding utf8)
$rtf = [System.Text.StringBuilder]::new('{\rtf1\ansi\ansicpg936\uc1\deff0{\fonttbl{\f0\fnil\fcharset134 Microsoft YaHei;}}\f0\fs18 ')
foreach ($character in $license.ToCharArray()) {
    $code = [int]$character
    if ($character -eq "`r") { continue }
    if ($character -eq "`n") { [void]$rtf.Append('\par '); continue }
    if ($character -in @('\', '{', '}')) { [void]$rtf.Append('\').Append($character); continue }
    if ($code -gt 127) {
        if ($code -gt 32767) { $code -= 65536 }
        [void]$rtf.Append('\u').Append($code).Append('?')
    } else { [void]$rtf.Append($character) }
}
[void]$rtf.Append('}')
$licensePath = Join-Path $generated 'LICENSE.zh-CN.rtf'
[System.IO.File]::WriteAllText($licensePath, $rtf.ToString(), [System.Text.Encoding]::ASCII)

# Official jpackage is invoked on Compose's existing app image: no second runtime
# build, no installer patching, and no dependencies fetched by the installed app.
$common = @(
    '--app-image', $image,
    '--name', $ApplicationName,
    '--app-version', $Version,
    '--vendor', '光光',
    '--description', 'EPLAN 标签助手 · Windows 离线版',
    '--copyright', 'Copyright 2026 Eplan Pocket contributors',
    '--icon', (Join-Path $root 'desktop/src/main/resources/branding/app.ico'),
    '--license-file', $licensePath,
    '--resource-dir', $resources,
    '--install-dir', 'EplanTagAssistantPC',
    '--win-upgrade-uuid', 'e80107f9-71c7-44f0-b405-650d3d275768',
    '--win-menu', '--win-menu-group', 'EPLAN 标签助手',
    '--win-shortcut', '--win-dir-chooser', '--verbose'
)
$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    # jpackage selects WiXUI localization from the JVM default locale. This is
    # scoped to packaging, so the user's Windows or application locale is untouched.
    $env:JAVA_TOOL_OPTIONS = "$oldJavaToolOptions -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN".Trim()
    foreach ($format in @('msi', 'exe')) {
        $destination = Join-Path $root "desktop/build/compose/binaries/main/$format"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        & $jpackage @common --type $format --dest $destination
        if ($LASTEXITCODE -ne 0) { throw "Chinese $format packaging failed with exit code $LASTEXITCODE." }
    }
} finally {
    if ($null -eq $oldJavaToolOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    else { $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions }
}
