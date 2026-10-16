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
    $taskPrepared = [IO.Path]::GetFullPath((Join-Path $taskTools ('.' + $Name + '-ready-' + [guid]::NewGuid())))
    $taskBackup = [IO.Path]::GetFullPath((Join-Path $taskTools ('.' + $Name + '-backup-' + [guid]::NewGuid())))
    $taskToolsAbsolute = [IO.Path]::GetFullPath($taskTools).TrimEnd('\')
    foreach ($taskPath in @($taskStage,$taskPrepared,$taskBackup,[IO.Path]::GetFullPath($taskDestination))) {
        if (-not $taskPath.StartsWith($taskToolsAbsolute + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Tool replacement path escaped the selected tools folder.' }
    }
    try {
        Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskStage
        $taskBinaries = @(Get-ChildItem -LiteralPath $taskStage -Filter "$Name.exe" -Recurse)
        if ($taskBinaries.Count -ne 1) { throw "Expected one $Name executable in the verified release archive." }
        $taskIncoming = $taskBinaries[0].DirectoryName
        New-Item -ItemType Directory -Path $taskPrepared | Out-Null
        Get-ChildItem -LiteralPath $taskIncoming | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskPrepared -Recurse }
        # Retain any outer archive notices without copying the executable tree twice.
        if ($taskIncoming -ne $taskStage) {
            $taskExtras = @(Get-ChildItem -LiteralPath $taskStage | Where-Object { $_.FullName -ne $taskIncoming })
            if ($taskExtras.Count) {
                $taskNotices = Join-Path $taskPrepared 'upstream-extra'; New-Item -ItemType Directory -Path $taskNotices | Out-Null
                $taskExtras | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskNotices -Recurse }
            }
        }
        $taskVersionOutput = & (Join-Path $taskPrepared "$Name.exe") --version 2>&1
        $taskVersionExit = $LASTEXITCODE
        if ($taskVersionExit -ne 0) { throw "$Name could not run. The previous installation was retained; inspect setup diagnostics and upstream Windows requirements." }
        if (Test-Path -LiteralPath $taskDestination) { Move-Item -LiteralPath $taskDestination -Destination $taskBackup }
        try { Move-Item -LiteralPath $taskPrepared -Destination $taskDestination }
        catch {
            if (-not (Test-Path -LiteralPath $taskDestination) -and (Test-Path -LiteralPath $taskBackup)) { Move-Item -LiteralPath $taskBackup -Destination $taskDestination }
            throw
        }
        if (Test-Path -LiteralPath $taskBackup) { Write-Host "Previous $Name files retained for recovery: $taskBackup" }
        $taskVersionOutput | Select-Object -First 1
    } finally {
        # These are only this invocation's fresh, checked staging directories, never the active/backup tool folder.
        foreach ($taskTemporary in @($taskStage,$taskPrepared)) { if (Test-Path -LiteralPath $taskTemporary) { Remove-Item -LiteralPath $taskTemporary -Recurse -Force } }
    }
    Remove-Item -LiteralPath $taskArchive
}

Install-ProjectTool -Name 'pandoc' -Url 'https://github.com/jgm/pandoc/releases/download/3.12.1/pandoc-3.12.1-windows-x86_64.zip' -Sha256 '6ef431cb20b2c24a0371fc19200dc49d452be54b1b5c9239f3462841ef53e587'
if (-not $SkipPreview) {
    Install-ProjectTool -Name 'tectonic' -Url 'https://github.com/tectonic-typesetting/tectonic/releases/download/tectonic%400.17.0/tectonic-0.17.0-x86_64-pc-windows-msvc.zip' -Sha256 'f61ce51f0b0ade1015b7de7ef368541c5424e9756ecbd0d7af97d6d48030845f'
}
Write-Host 'Tools ready. Start the backend from the project root.'
if (-not $SkipFonts) { & (Join-Path $PSScriptRoot 'setup-fonts.ps1') -FontsDirectory (Join-Path $taskTools 'fonts') }
