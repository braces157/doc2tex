param([switch]$NoBrowser)
. (Join-Path $PSScriptRoot 'Common.ps1')
$taskHome = Get-AppHome
$taskGatePath = Join-Path $taskHome '.launcher.lock'
try { $taskGate = [IO.File]::Open($taskGatePath, 'OpenOrCreate', 'ReadWrite', 'None') }
catch { throw 'Another launcher or tool installer is active. Wait for it to finish, then launch again.' }
try {
    $taskExisting = Get-LiveInstance $taskHome
    if ($taskExisting) { Open-AppBrowser $taskExisting -NoBrowser:$NoBrowser; return }
    $taskSettingsPath = Join-Path $taskHome 'settings.json'
    if (-not (Test-Path -LiteralPath $taskSettingsPath)) { [IO.File]::WriteAllText($taskSettingsPath, '{"port":18749}') }
    $taskSettings = Get-Content -LiteralPath $taskSettingsPath -Raw | ConvertFrom-Json
    $taskPort = $taskSettings.port
    if ($taskPort -isnot [int] -and $taskPort -isnot [long]) { throw 'settings.json port must be an integer between 1024 and 65535.' }
    if ($taskPort -lt 1024 -or $taskPort -gt 65535) { throw 'settings.json port must be between 1024 and 65535.' }
    $taskJava = Join-Path $PSScriptRoot 'runtime/bin/java.exe'
    $taskJar = Join-Path $PSScriptRoot 'app/doc2tex.jar'
    if (-not (Test-Path -LiteralPath $taskJava) -or -not (Test-Path -LiteralPath $taskJar)) { throw 'Extract the complete Windows package first; its runtime or application is missing.' }
    Write-Host "Projects/settings: $taskHome"
    Write-Host 'No downloads run at launch. If setup is needed, close the app and run Install tools.cmd. The first PDF build may fetch TeX packages; read INSTALL.md before using it.'
    $env:DOC2TEX_FONT_DIR = Join-Path $taskHome '.tools/fonts'
    $taskLogs = Join-Path $taskHome 'logs'; New-Item -ItemType Directory -Force -Path $taskLogs | Out-Null
    $taskArgs = @("-Ddoc2tex.home=`"$taskHome`"", '-jar', "`"$taskJar`"", '--server.address=127.0.0.1', "--server.port=$taskPort", "--doc2tex.data-dir=`"$(Join-Path $taskHome 'jobs')`"", "--doc2tex.pandoc=`"$(Join-Path $taskHome '.tools/pandoc/pandoc.exe')`"", "--doc2tex.tectonic=`"$(Join-Path $taskHome '.tools/tectonic/tectonic.exe')`"")
    $taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArgs -WorkingDirectory $taskHome -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLogs 'startup.log') -RedirectStandardError (Join-Path $taskLogs 'startup-error.log') -PassThru
    $null = $taskProcess.Handle
    for ($taskAttempt = 0; $taskAttempt -lt 90; $taskAttempt++) {
        $taskProcess.Refresh()
        if ($taskProcess.HasExited) { throw "The server exited ($($taskProcess.ExitCode)). Check $taskLogs, or stop the app already using this data folder/port. No projects were deleted." }
        $taskInstance = Get-LiveInstance $taskHome
        if ($taskInstance -and $taskInstance.pid -eq $taskProcess.Id) { Open-AppBrowser $taskInstance -NoBrowser:$NoBrowser; return }
        Start-Sleep -Milliseconds 500
    }
    throw "Startup did not become ready. Inspect $taskLogs and use Stop doc2TeX.cmd before retrying. No projects were deleted."
} finally { $taskGate.Dispose() }
