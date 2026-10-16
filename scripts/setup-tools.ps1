param([switch]$SkipPreview, [switch]$SkipFonts, [switch]$Repair, [string]$ToolsDirectory)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Global -Force
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Archive/Microsoft.PowerShell.Archive.psd1') -Global -Force
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = if ($ToolsDirectory) { [IO.Path]::GetFullPath($ToolsDirectory) } else { Join-Path $taskRoot '.tools' }
New-Item -ItemType Directory -Force -Path $taskTools | Out-Null

function Install-ProjectTool {
    param([string]$Name, [string]$Url, [string]$Sha256)
    $taskDestination = Join-Path $taskTools $Name
    $taskExecutable = Join-Path $taskDestination "$Name.exe"
    if ((Test-Path -LiteralPath $taskExecutable) -and -not $Repair) { Write-Host "$Name is already installed. Use -Repair to replace a damaged installation."; return }
    $taskArchive = Join-Path $taskTools "$Name.zip"
    Write-Host "Downloading $Name into .tools..."
    Invoke-WebRequest -Uri $Url -OutFile $taskArchive -TimeoutSec 120
    $taskHash = (Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash
    if ($taskHash -ne $Sha256) { throw "Checksum verification failed for $Name." }
    $taskStage = [IO.Path]::GetFullPath((Join-Path $taskTools ('.' + $Name + '-stage-' + [guid]::NewGuid())))
    $taskToolsAbsolute = [IO.Path]::GetFullPath($taskTools).TrimEnd('\')
    if (-not $taskStage.StartsWith($taskToolsAbsolute + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Tool staging path escaped the selected tools folder.' }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskStage
    $taskBinary = Get-ChildItem -LiteralPath $taskStage -Filter "$Name.exe" -Recurse | Select-Object -First 1
    if (-not $taskBinary) { throw "The $Name executable was missing from the release archive." }
    New-Item -ItemType Directory -Force -Path $taskDestination | Out-Null
    # Preserve the archive's contents, then place its executable and adjacent runtime files at the fixed tool path.
    Get-ChildItem -LiteralPath $taskStage | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskDestination -Recurse -Force }
    Get-ChildItem -LiteralPath $taskBinary.DirectoryName | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskDestination -Recurse -Force }
    # Only this fresh, checked staging directory is recursively removed.
    Remove-Item -LiteralPath $taskStage -Recurse -Force
    Remove-Item -LiteralPath $taskArchive
    & $taskExecutable --version | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw "$Name was installed but could not run. Inspect setup diagnostics and the upstream Windows requirements." }
}

Install-ProjectTool -Name 'pandoc' -Url 'https://github.com/jgm/pandoc/releases/download/3.12.1/pandoc-3.12.1-windows-x86_64.zip' -Sha256 '6ef431cb20b2c24a0371fc19200dc49d452be54b1b5c9239f3462841ef53e587'
if (-not $SkipPreview) {
    Install-ProjectTool -Name 'tectonic' -Url 'https://github.com/tectonic-typesetting/tectonic/releases/download/tectonic%400.17.0/tectonic-0.17.0-x86_64-pc-windows-msvc.zip' -Sha256 'f61ce51f0b0ade1015b7de7ef368541c5424e9756ecbd0d7af97d6d48030845f'
}
Write-Host 'Tools ready. Start the backend from the project root.'
if (-not $SkipFonts) { & (Join-Path $PSScriptRoot 'setup-fonts.ps1') -FontsDirectory (Join-Path $taskTools 'fonts') }
