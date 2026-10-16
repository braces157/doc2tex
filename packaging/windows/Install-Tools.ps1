param([switch]$AcceptDownloads)
. (Join-Path $PSScriptRoot 'Common.ps1')
$taskHome = Get-AppHome
try { $taskGate = [IO.File]::Open((Join-Path $taskHome '.launcher.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
catch { throw 'Another launcher/installer is active. Wait for it to finish.' }
try {
    if (Get-LiveInstance $taskHome) { throw 'Run Stop doc2TeX.cmd before installing or repairing tools.' }
    try { $taskInstanceGate = [IO.File]::Open((Join-Path $taskHome '.instance.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'A server still holds this data folder. Stop it before installing or repairing tools.' }
    try {
    Write-Host 'This downloads pinned Pandoc (GPL-2.0-or-later), Tectonic (MIT), and Noto fonts (OFL) from GitHub into your local data folder. SHA-256 checks run before installation. The first PDF build can separately download TeX packages from Tectonic servers. No documents are uploaded by this setup.'
    if (-not $AcceptDownloads -and (Read-Host 'Download the tools and fonts now? Type YES to continue') -cne 'YES') { Write-Host 'No downloads started.'; return }
    & (Join-Path $PSScriptRoot 'scripts/setup-tools.ps1') -Repair -ToolsDirectory (Join-Path $taskHome '.tools')
    if (-not $? -or $LASTEXITCODE -ne 0) { throw 'Tool setup failed. Inspect the setup output, reconnect/repair as needed, then retry. Projects were retained.' }
    Write-Host 'Launch doc2TeX.cmd again. If a download failed, rerun this installer when connected.'
    } finally { $taskInstanceGate.Dispose() }
} finally { $taskGate.Dispose() }
