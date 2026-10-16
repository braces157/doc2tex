$ErrorActionPreference = 'Stop'
# A PowerShell 7 parent can pass its module path to Windows PowerShell 5.1.
# Load the matching built-in manifest rather than importing the parent's modules.
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Global -Force
function Get-AppHome {
    $taskHome = if ($env:DOC2TEX_HOME) { [IO.Path]::GetFullPath($env:DOC2TEX_HOME) } else { Join-Path $env:LOCALAPPDATA 'doc2TeX' }
    $taskInstall = [IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
    if ($taskHome.TrimEnd('\').Equals($taskInstall, [StringComparison]::OrdinalIgnoreCase) -or $taskHome.StartsWith($taskInstall + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Choose a DOC2TEX_HOME outside the app installation folder so upgrades preserve your projects.' }
    New-Item -ItemType Directory -Force -Path $taskHome | Out-Null
    return $taskHome
}
function Get-LiveInstance([string]$TaskHome) {
    $taskFile = Join-Path $TaskHome 'instance.json'
    if (-not (Test-Path -LiteralPath $taskFile)) { return $null }
    try {
        $taskInstance = Get-Content -LiteralPath $taskFile -Raw | ConvertFrom-Json
        $taskPort = [int]$taskInstance.port
        if ($taskPort -lt 1024 -or $taskPort -gt 65535 -or [string]$taskInstance.instanceId -notmatch '^[a-f0-9-]{36}$') { return $null }
        $taskStatus = Invoke-RestMethod -Uri "http://127.0.0.1:$taskPort/api/system" -TimeoutSec 8
        if ($taskStatus.packaged -eq $true -and $taskStatus.instanceId -ceq $taskInstance.instanceId) { return $taskInstance }
    } catch { return $null }
    return $null
}
function Open-AppBrowser($TaskInstance, [switch]$NoBrowser) {
    $taskUrl = "http://127.0.0.1:$($TaskInstance.port)"
    Write-Host "doc2TeX: $taskUrl"
    if (-not $NoBrowser) { Start-Process -FilePath $taskUrl }
}
