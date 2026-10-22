param([string]$OutputDirectory, [string]$RuntimeArchive, [string]$RuntimeSourcesArchive, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) { throw 'Build this x64 Windows package on 64-bit Windows.' }
$taskRuntimeVersion = '21.0.12.1+1'
$taskRuntimeHash = 'd35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636'
$taskSourcesHash = '573057d03584ae793fb7ec9a14c76d826d9187a53efeefd99da47403a5308234'
$taskBase = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/'
$taskCache = Join-Path $taskRoot 'target/windows-downloads'; New-Item -ItemType Directory -Force -Path $taskCache | Out-Null
function Get-CheckedArchive([string]$Supplied, [string]$Name, [string]$Hash) {
    $taskFile = if ($Supplied) { [IO.Path]::GetFullPath($Supplied) } else { Join-Path $taskCache $Name }
    if (-not (Test-Path -LiteralPath $taskFile)) {
        if ($Supplied) { throw "Missing archive: $taskFile" }
        Write-Host "Downloading pinned runtime component $Name..."
        Invoke-WebRequest -Uri ($taskBase + $Name) -OutFile $taskFile
    }
    if ((Get-FileHash -LiteralPath $taskFile -Algorithm SHA256).Hash -ne $Hash) { throw "Checksum mismatch: $taskFile. Nothing was packaged." }
    return $taskFile
}
$taskRuntimeZip = Get-CheckedArchive $RuntimeArchive 'OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip' $taskRuntimeHash
$taskSources = Get-CheckedArchive $RuntimeSourcesArchive 'OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz' $taskSourcesHash
Push-Location $taskRoot
try {
    if (-not $SkipBuild) {
        & npm.cmd --prefix frontend ci; if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd --prefix frontend run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
        & (Join-Path $taskRoot 'mvnw.cmd') -B package -DskipTests; if ($LASTEXITCODE -ne 0) { throw 'Application packaging failed.' }
    }
    [xml]$taskPom = Get-Content -LiteralPath (Join-Path $taskRoot 'pom.xml')
    $taskVersion = $taskPom.project.version
    if ($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Unexpected project version.' }
    $taskOutput = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $taskRoot 'target/windows-package' }
    New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
    $taskName = "doc2tex-$taskVersion-windows-x64"
    $taskDestination = Join-Path $taskOutput $taskName
    $taskZip = Join-Path $taskOutput "$taskName.zip"
    if ((Test-Path -LiteralPath $taskDestination) -or (Test-Path -LiteralPath $taskZip)) { throw 'Package destination already exists. Choose a new OutputDirectory; existing files are never deleted by this builder.' }
    $taskJar = Join-Path $taskRoot "target/doc2tex-$taskVersion.jar"
    if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Build the executable JAR first, or omit -SkipBuild.' }
    New-Item -ItemType Directory -Path $taskDestination | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $taskRoot 'packaging/windows') | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskDestination -Recurse }
    New-Item -ItemType Directory -Path (Join-Path $taskDestination 'app'),(Join-Path $taskDestination 'scripts'),(Join-Path $taskDestination 'legal') | Out-Null
    Copy-Item -LiteralPath $taskJar -Destination (Join-Path $taskDestination 'app/doc2tex.jar')
    foreach ($taskScript in @('setup-tools.ps1','setup-fonts.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskScript) -Destination (Join-Path $taskDestination 'scripts') }
    foreach ($taskNotice in @('LICENSE','THIRD_PARTY_NOTICES.md','LICENSES')) { Copy-Item -LiteralPath (Join-Path $taskRoot $taskNotice) -Destination (Join-Path $taskDestination 'legal') -Recurse }
    Copy-Item -LiteralPath $taskSources -Destination (Join-Path $taskDestination 'legal/OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz')
    $taskVerificationScripts = Join-Path $taskDestination 'verification/scripts'
    $taskVerificationFixtures = Join-Path $taskDestination 'verification/src/test/resources/corpus'
    New-Item -ItemType Directory -Force -Path $taskVerificationScripts,$taskVerificationFixtures | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'test-windows-package.ps1') -Destination $taskVerificationScripts
    Copy-Item -LiteralPath (Join-Path $taskRoot 'src/test/resources/corpus/multilingual.docx') -Destination $taskVerificationFixtures
    $taskExtract = Join-Path $taskDestination 'runtime-extract'; Expand-Archive -LiteralPath $taskRuntimeZip -DestinationPath $taskExtract
    $taskRuntimeRoot = @(Get-ChildItem -LiteralPath $taskExtract -Directory)
    if ($taskRuntimeRoot.Count -ne 1) { throw 'Unexpected runtime archive structure.' }
    # Rename only the verified archive's one root inside this newly created package.
    $taskResolvedRuntime = [IO.Path]::GetFullPath($taskRuntimeRoot[0].FullName)
    if (-not $taskResolvedRuntime.StartsWith([IO.Path]::GetFullPath($taskDestination).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime path escaped the package.' }
    Move-Item -LiteralPath $taskResolvedRuntime -Destination (Join-Path $taskDestination 'runtime')
    Remove-Item -LiteralPath $taskExtract
    if (-not (Test-Path -LiteralPath (Join-Path $taskDestination 'runtime/bin/java.exe'))) { throw 'The bundled runtime is missing java.exe.' }
    $taskCommit = & git rev-parse HEAD; if ($LASTEXITCODE -ne 0) { throw 'Could not identify package source commit.' }
    $taskDirty = [bool](& git status --porcelain)
    @{ version=$taskVersion; commit=$taskCommit; workingTreeDirty=$taskDirty; runtime="Eclipse Temurin $taskRuntimeVersion Windows x64 JRE"; runtimeSha256=$taskRuntimeHash; runtimeSourcesSha256=$taskSourcesHash; jarSha256=(Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskDestination 'build.json') -Encoding UTF8
    Compress-Archive -LiteralPath $taskDestination -DestinationPath $taskZip
    $taskDigest = (Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($taskZip + '.sha256', "$taskDigest  $taskName.zip`n")
    Write-Host "Package: $taskZip"
} finally { Pop-Location }
