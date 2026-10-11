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

# JDK 17 converts expanded launcher arguments (including classpath) with CP_ACP.
# A process-local UTF-8 manifest preserves Chinese paths on Windows 10 1903+.
# Merge with Microsoft's SDK tool so the JDK's trust, DPI and OS settings survive.
$mtCommand = Get-Command 'mt.exe' -ErrorAction SilentlyContinue
if ($null -ne $mtCommand) { $manifestTool = $mtCommand.Source }
else {
    $sdkBin = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
    $manifestTool = Get-ChildItem -Path (Join-Path $sdkBin '*/x64/mt.exe') -File |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $manifestTool) { throw 'The official Windows SDK manifest tool (mt.exe) was not found.' }

function ConvertTo-ManifestSignature([System.Xml.XmlElement]$Element) {
    $attributes = @($Element.Attributes | Where-Object { $_.NamespaceURI -ne 'http://www.w3.org/2000/xmlns/' } |
        ForEach-Object { "{$($_.NamespaceURI)}$($_.LocalName)=$($_.Value)" } | Sort-Object)
    $children = @($Element.ChildNodes | Where-Object { $_ -is [System.Xml.XmlElement] } |
        ForEach-Object { ConvertTo-ManifestSignature $_ })
    return ([ordered]@{
        name = "{$($Element.NamespaceURI)}$($Element.LocalName)"
        attributes = $attributes
        text = if ($children.Count -eq 0) { $Element.InnerText.Trim() } else { '' }
        children = $children
    } | ConvertTo-Json -Depth 50 -Compress)
}

function Get-PreservedManifestSettings([xml]$Document) {
    $Document.SelectNodes("//*[local-name()='assemblyIdentity' or local-name()='trustInfo' or local-name()='compatibility' or local-name()='dpiAware' or local-name()='dpiAwareness']") |
        ForEach-Object { ConvertTo-ManifestSignature $_ } | Sort-Object
}

$launcher = Join-Path $image "$ApplicationName.exe"
# Keep the SDK tool's native arguments ASCII too; PowerShell copies Unicode paths.
Copy-Item -LiteralPath $launcher -Destination (Join-Path $generated 'launcher.exe') -Force
Copy-Item -LiteralPath (Join-Path $resources 'launcher-utf8.manifest') -Destination $generated -Force
Push-Location -LiteralPath $generated
try {
    & $manifestTool '-nologo' '-inputresource:launcher.exe;#1' '-out:launcher-original.manifest'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the original launcher manifest.' }
    & $manifestTool '-nologo' '-inputresource:launcher.exe;#1' '-manifest' 'launcher-utf8.manifest' '-outputresource:launcher.exe;#1'
    if ($LASTEXITCODE -ne 0) { throw 'Could not merge the UTF-8 launcher manifest.' }
    & $manifestTool '-nologo' '-inputresource:launcher.exe;#1' '-out:launcher-merged.manifest'
    if ($LASTEXITCODE -ne 0) { throw 'Could not read back the merged launcher manifest.' }
} finally { Pop-Location }

[xml]$originalManifest = Get-Content -LiteralPath (Join-Path $generated 'launcher-original.manifest') -Raw -Encoding utf8
[xml]$mergedManifest = Get-Content -LiteralPath (Join-Path $generated 'launcher-merged.manifest') -Raw -Encoding utf8
$activeCodePages = @($mergedManifest.SelectNodes("//*[local-name()='activeCodePage' and namespace-uri()='http://schemas.microsoft.com/SMI/2019/WindowsSettings']"))
if ($activeCodePages.Count -ne 1 -or $activeCodePages[0].InnerText.Trim() -cne 'UTF-8') {
    throw 'The embedded launcher manifest must select the UTF-8 process code page.'
}
$originalSettings = @(Get-PreservedManifestSettings $originalManifest)
$mergedSettings = @(Get-PreservedManifestSettings $mergedManifest)
if (($originalSettings | ConvertTo-Json -Compress) -cne ($mergedSettings | ConvertTo-Json -Compress)) {
    throw 'Merging the launcher manifest changed existing identity, trust, DPI or compatibility settings.'
}
Copy-Item -LiteralPath (Join-Path $generated 'launcher.exe') -Destination $launcher -Force
$previews = Join-Path $root 'desktop/build/previews'
New-Item -ItemType Directory -Force -Path $previews | Out-Null
Copy-Item -LiteralPath (Join-Path $generated 'launcher-merged.manifest') -Destination (Join-Path $previews 'launcher-utf8.manifest') -Force
[ordered]@{
    passed = $true
    launcher = "$ApplicationName.exe"
    activeCodePage = 'UTF-8'
    minimumWindowsForUnicodePaths = 'Windows 10 1903 (build 18362)'
    preservedOriginalIdentityTrustDpiCompatibility = $true
    originalSettings = $originalSettings
    launcherSha256 = (Get-FileHash -LiteralPath $launcher -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $previews 'launcher-utf8.json') -Encoding utf8

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

function ConvertTo-JpackageArgument([string]$Value) {
    # JDK 17 CommandLine.Tokenizer treats backslashes as escapes inside quotes.
    # An argument file also avoids its Windows native launcher's ANSI argv path.
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    # jpackage selects WiXUI localization from the JVM default locale. This is
    # scoped to packaging, so the user's Windows or application locale is untouched.
    $env:JAVA_TOOL_OPTIONS = "$oldJavaToolOptions -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN".Trim()
    foreach ($format in @('msi', 'exe')) {
        $destination = Join-Path $root "desktop/build/compose/binaries/main/$format"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        $arguments = @($common) + @('--type', $format, '--dest', $destination)
        $argumentFileName = "jpackage-$format.args"
        $argumentFile = Join-Path $generated $argumentFileName
        $lines = $arguments | ForEach-Object { ConvertTo-JpackageArgument $_ }
        [System.IO.File]::WriteAllLines($argumentFile, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))
        Push-Location -LiteralPath $generated
        try {
            # The only native argv token is ASCII; all Chinese stays in UTF-8.
            & $jpackage "@$argumentFileName"
            if ($LASTEXITCODE -ne 0) { throw "Chinese $format packaging failed with exit code $LASTEXITCODE." }
        } finally { Pop-Location }
    }
} finally {
    if ($null -eq $oldJavaToolOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    else { $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions }
}
