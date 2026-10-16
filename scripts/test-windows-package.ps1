param([Parameter(Mandatory=$true)][string]$PackageDirectory, [string]$EvidenceDirectory, [switch]$DownloadTools)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Global -Force
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPackage = [IO.Path]::GetFullPath($PackageDirectory)
$taskEvidence = if ($EvidenceDirectory) { [IO.Path]::GetFullPath($EvidenceDirectory) } else { Join-Path $taskRoot ('target/package-smoke-' + [guid]::NewGuid()) }
if (Test-Path -LiteralPath $taskEvidence) { throw 'Use a fresh synthetic EvidenceDirectory. This test never cleans existing folders.' }
New-Item -ItemType Directory -Path $taskEvidence | Out-Null
$taskInstall = Join-Path $taskEvidence 'install one'
Copy-Item -LiteralPath $taskPackage -Destination $taskInstall -Recurse
$taskHome = Join-Path $taskEvidence ('Synthetic data R' + [char]0x00e9 + 'sum' + [char]0x00e9)
New-Item -ItemType Directory -Path $taskHome | Out-Null
[IO.File]::WriteAllText((Join-Path $taskHome 'settings.json'), '{"port":19749}')
$taskOldPath = $env:PATH; $taskOldHome = $env:DOC2TEX_HOME
$taskHostJava = [bool](Get-Command java -ErrorAction SilentlyContinue)
$taskHostNode = [bool](Get-Command node -ErrorAction SilentlyContinue)
$taskPowerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskHttpHandler = New-Object Net.Http.HttpClientHandler; $taskHttpHandler.UseProxy = $false
$taskHttp = New-Object Net.Http.HttpClient($taskHttpHandler)
$taskHttp.Timeout = [TimeSpan]::FromSeconds(20)
$taskBase = 'http://127.0.0.1:19749/api'
function Assert-That([bool]$Value, [string]$Message) { if (-not $Value) { throw $Message } }
$taskHelperCount = 0
function Run-Helper([string]$Name, [string[]]$Extra = @()) {
    $script:taskHelperCount++
    $taskPrefix = Join-Path $taskEvidence ("helper-$($script:taskHelperCount)-" + $Name)
    # Wait for this PowerShell process, not its detached Java process tree.
    $taskHelper = Start-Process -FilePath $taskPowerShell -ArgumentList (@('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$(Join-Path $taskInstall $Name)`"") + $Extra) -WindowStyle Hidden -PassThru -RedirectStandardOutput ($taskPrefix + '.log') -RedirectStandardError ($taskPrefix + '-error.log')
    $null = $taskHelper.Handle # Windows PowerShell 5.1 otherwise can lose ExitCode after a fast exit.
    $taskExited = $false
    for ($taskWait = 0; $taskWait -lt 20; $taskWait++) { if ($taskHelper.WaitForExit(30000)) { $taskExited = $true; break } }
    if (-not $taskExited) { $taskHelper.Kill(); throw "Helper timed out: $taskPrefix. The synthetic installation may need repair." }
    $global:LASTEXITCODE = $taskHelper.ExitCode
    Get-Content -LiteralPath ($taskPrefix + '.log') | Write-Host
    if ($taskHelper.ExitCode -ne 0) { throw "Helper $Name failed ($($taskHelper.ExitCode)). Inspect $taskPrefix-error.log" }
}
function Api([string]$Method, [string]$Route, $Body = $null) {
    $taskRequest = New-Object Net.Http.HttpRequestMessage((New-Object Net.Http.HttpMethod($Method)), ($taskBase + $Route))
    if ($null -ne $Body) { $taskRequest.Content = New-Object Net.Http.StringContent(($Body | ConvertTo-Json -Depth 20 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    try {
        $taskResponse = $taskHttp.SendAsync($taskRequest).GetAwaiter().GetResult()
        try { $taskText = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult(); if (-not $taskResponse.IsSuccessStatusCode) { throw "API $Method $Route failed ($([int]$taskResponse.StatusCode)): $taskText" }; if ($taskText) { return $taskText | ConvertFrom-Json } }
        finally { $taskResponse.Dispose() }
    } finally { $taskRequest.Dispose() }
}
function Convert-Fixture([byte[]]$Bytes, [string]$Name) {
    $taskForm = New-Object Net.Http.MultipartFormDataContent
    $taskFile = New-Object Net.Http.ByteArrayContent(,$Bytes); $taskForm.Add($taskFile, 'file', $Name)
    $taskForm.Add((New-Object Net.Http.StringContent('article')), 'template')
    try { $taskResponse = $taskHttp.PostAsync($taskBase + '/jobs', $taskForm).GetAwaiter().GetResult(); try { $taskResponse.EnsureSuccessStatusCode() | Out-Null; $taskJob = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json } finally { $taskResponse.Dispose() } }
    finally { $taskForm.Dispose() }
    for ($taskAttempt = 0; $taskAttempt -lt 90; $taskAttempt++) { $taskJob = Api GET ('/jobs/' + $taskJob.id); if ($taskJob.status -eq 'COMPLETED') { return $taskJob }; if ($taskJob.status -eq 'FAILED') { throw $taskJob.message }; Start-Sleep -Milliseconds 500 }
    throw 'Packaged conversion did not complete.'
}
function Read-Source([string]$Id) { return $taskHttp.GetStringAsync($taskBase + '/jobs/' + $Id + '/source').GetAwaiter().GetResult() }
function Export-Job([string]$Id, [string]$Name) {
    $taskBytes = $taskHttp.GetByteArrayAsync($taskBase + '/jobs/' + $Id + '/download').GetAwaiter().GetResult()
    $taskZip = Join-Path $taskEvidence $Name; [IO.File]::WriteAllBytes($taskZip, $taskBytes)
    return [IO.Compression.ZipFile]::OpenRead($taskZip)
}
try {
    $env:DOC2TEX_HOME = $taskHome
    # Neither Java nor Node is discoverable by the launched processes.
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    Assert-That (-not (Get-Command java,node,npm -ErrorAction SilentlyContinue)) 'Developer runtime leaked into the smoke PATH.'
    Run-Helper 'Launch.ps1' @('-NoBrowser')
    Assert-That ($LASTEXITCODE -eq 0) 'Initial packaged launch failed.'
    $taskStatus = Api GET '/system'; Assert-That (-not $taskStatus.pandoc -and -not $taskStatus.tectonic) 'Fresh data folder should report missing tools.'
    Assert-That ($taskStatus.missingFonts.Count -eq 6) 'Fresh data folder should report missing fonts/licenses.'
    $taskInstance = Get-Content -LiteralPath (Join-Path $taskHome 'instance.json') -Raw | ConvertFrom-Json
    Run-Helper 'Launch.ps1' @('-NoBrowser')
    Assert-That ($LASTEXITCODE -eq 0) 'Reopening a running package failed.'
    Assert-That ((Get-Content -LiteralPath (Join-Path $taskHome 'instance.json') -Raw | ConvertFrom-Json).pid -eq $taskInstance.pid) 'Duplicate launch started another server.'
    $taskDuplicate = Start-Process -FilePath (Join-Path $taskInstall 'runtime/bin/java.exe') -ArgumentList @("-Ddoc2tex.home=`"$taskHome`"", '-jar', "`"$(Join-Path $taskInstall 'app/doc2tex.jar')`"", '--server.port=19750') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskEvidence 'duplicate.log') -RedirectStandardError (Join-Path $taskEvidence 'duplicate-error.log')
    $null = $taskDuplicate.Handle
    Assert-That ($taskDuplicate.WaitForExit(15000)) 'Direct duplicate runtime did not exit.'
    Assert-That ($taskDuplicate.ExitCode -ne 0) 'Direct duplicate runtime bypassed the data-folder lock.'
    Assert-That ([IO.File]::ReadAllText((Join-Path $taskEvidence 'duplicate-error.log')).Contains('already running')) 'Duplicate-runtime lock guidance is missing.'
    $taskForbidden = $taskHttp.PostAsync($taskBase + '/system/stop', (New-Object Net.Http.StringContent(''))).GetAwaiter().GetResult()
    try { Assert-That ([int]$taskForbidden.StatusCode -eq 403) 'Unauthenticated shutdown must be rejected.' } finally { $taskForbidden.Dispose() }
    Run-Helper 'Stop.ps1'; Assert-That ($LASTEXITCODE -eq 0) 'Initial stop failed.'
    if ($DownloadTools) {
        Run-Helper 'Install-Tools.ps1' @('-AcceptDownloads')
        Assert-That ($LASTEXITCODE -eq 0) 'Explicit tool installation failed.'
    } else { Copy-Item -LiteralPath (Join-Path $taskRoot '.tools') -Destination (Join-Path $taskHome '.tools') -Recurse }
    Run-Helper 'Launch.ps1' @('-NoBrowser'); Assert-That ($LASTEXITCODE -eq 0) 'Ready packaged launch failed.'
    $taskStatus = Api GET '/system'; Assert-That ($taskStatus.pandoc -and $taskStatus.tectonic -and $taskStatus.missingFonts.Count -eq 0) 'Installed tool/font diagnostics did not become ready.'
    $taskHtml = $taskHttp.GetStringAsync('http://127.0.0.1:19749/').GetAwaiter().GetResult(); Assert-That ($taskHtml.Contains('id="root"')) 'Built frontend was not served.'
    $taskSample = $taskHttp.GetByteArrayAsync($taskBase + '/sample.docx').GetAwaiter().GetResult()
    $taskJob = Convert-Fixture $taskSample 'Synthetic sample.docx'
    $taskSource = Read-Source $taskJob.id
    $taskJob = Api PUT ('/jobs/' + $taskJob.id + '/source') @{ source=($taskSource + "`n% Synthetic retained edit"); revision=$taskJob.revision }
    $taskJob = Api PATCH ('/jobs/' + $taskJob.id) @{ displayName='Synthetic upgrade retained' }
    $taskVersions = Api GET ('/jobs/' + $taskJob.id + '/versions')
    Assert-That ($taskVersions.items.Count -eq 2) 'Packaged history was not saved.'
    $taskZip = Export-Job $taskJob.id 'sample-export.zip'; try { Assert-That ($null -ne $taskZip.GetEntry('main.tex') -and @($taskZip.Entries | Where-Object { $_.FullName.StartsWith('media/') }).Count -gt 0) 'Sample export is incomplete.' } finally { $taskZip.Dispose() }
    $taskMultilingual = Convert-Fixture ([IO.File]::ReadAllBytes((Join-Path $taskRoot 'src/test/resources/corpus/multilingual.docx'))) 'Synthetic multilingual.docx'
    $taskZip = Export-Job $taskMultilingual.id 'multilingual-export.zip'
    try {
        foreach ($taskFont in @('NotoSerif-Regular.ttf','NotoSansThai-Regular.ttf','NotoNaskhArabic-Regular.ttf','NotoSansCJKsc-Regular.otf','OFL-Noto.txt','OFL-Noto-CJK.txt')) {
            $taskEntry = $taskZip.GetEntry('fonts/' + $taskFont); Assert-That ($null -ne $taskEntry) "Missing exported font/license: $taskFont"
            $taskStream = $taskEntry.Open(); $taskMemory = New-Object IO.MemoryStream
            try { $taskStream.CopyTo($taskMemory); $taskSha = [Security.Cryptography.SHA256]::Create(); try { Assert-That ([Convert]::ToBase64String($taskSha.ComputeHash($taskMemory.ToArray())) -eq [Convert]::ToBase64String($taskSha.ComputeHash([IO.File]::ReadAllBytes((Join-Path $taskHome ('.tools/fonts/' + $taskFont)))))) "Export changed font/license bytes: $taskFont" } finally { $taskSha.Dispose() } }
            finally { $taskStream.Dispose(); $taskMemory.Dispose() }
        }
    } finally { $taskZip.Dispose() }
    Run-Helper 'Stop.ps1'; Assert-That ($LASTEXITCODE -eq 0) 'Pre-upgrade stop failed.'
    $taskSettings = [IO.File]::ReadAllText((Join-Path $taskHome 'settings.json'))
    $taskUpgrade = Join-Path $taskEvidence 'install two'; Copy-Item -LiteralPath $taskPackage -Destination $taskUpgrade -Recurse
    $taskInstall = $taskUpgrade
    Run-Helper 'Launch.ps1' @('-NoBrowser'); Assert-That ($LASTEXITCODE -eq 0) 'Replacement app launch failed.'
    $taskRestored = Api GET ('/jobs/' + $taskJob.id)
    Assert-That ($taskRestored.displayName -eq 'Synthetic upgrade retained' -and $taskRestored.revision -eq 1) 'Upgrade lost project metadata.'
    Assert-That ((Read-Source $taskJob.id).Contains('Synthetic retained edit')) 'Upgrade lost source.'
    Assert-That ((Api GET ('/jobs/' + $taskJob.id + '/versions')).items.Count -eq 2) 'Upgrade lost versions.'
    Assert-That ([IO.File]::ReadAllText((Join-Path $taskHome 'settings.json')) -ceq $taskSettings) 'Upgrade replaced settings.'
    Run-Helper 'Stop.ps1'; Assert-That ($LASTEXITCODE -eq 0) 'Final stop failed.'
    foreach ($taskApp in @((Join-Path $taskEvidence 'install one'), (Join-Path $taskEvidence 'install two'))) {
        $taskAbsoluteApp = [IO.Path]::GetFullPath($taskApp)
        if (-not $taskAbsoluteApp.StartsWith([IO.Path]::GetFullPath($taskEvidence).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Synthetic uninstall path escaped its evidence folder.' }
        Remove-Item -LiteralPath $taskAbsoluteApp -Recurse -Force
    }
    Assert-That (Test-Path -LiteralPath (Join-Path $taskHome ('jobs/' + $taskJob.id + '/input.docx'))) 'Stopping the app lost original data.'
    Assert-That ([IO.File]::ReadAllText((Join-Path $taskHome 'settings.json')) -ceq $taskSettings) 'App-folder uninstall changed settings.'
    @{ result='passed'; environment='Launched process PATH restricted to System32; clean-machine status requires external attestation'; hostJavaOnPathBeforeTest=$taskHostJava; hostNodeOnPathBeforeTest=$taskHostNode; sampleJob=$taskJob.id; multilingualJob=$taskMultilingual.id; versions=2; port=19749; appReplacementPreservedData=$true; uninstallPreservedData=$true } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskEvidence 'result.json') -Encoding UTF8
    Write-Host "Packaged smoke passed. Evidence: $taskEvidence"
} finally {
    try { if (Test-Path -LiteralPath (Join-Path $taskInstall 'Stop.ps1')) { Run-Helper 'Stop.ps1' } } catch { Write-Warning 'Inspect the synthetic smoke logs and stop any remaining synthetic instance.' }
    $taskHttp.Dispose(); $taskHttpHandler.Dispose(); $env:PATH = $taskOldPath; $env:DOC2TEX_HOME = $taskOldHome
}
