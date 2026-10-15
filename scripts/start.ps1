$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location $taskRoot
try {
    Push-Location (Join-Path $taskRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    & (Join-Path $taskRoot 'mvnw.cmd') spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'The backend exited with an error.' }
} finally { Pop-Location }
