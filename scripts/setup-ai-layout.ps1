param([switch]$SkipRenderProbe, [switch]$DockerRenderer)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if ($DockerRenderer -and $SkipRenderProbe) { throw '-DockerRenderer requires its render probe; omit -SkipRenderProbe.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskTools = Join-Path $taskRoot '.tools'
$taskDownloads = Join-Path $taskTools 'downloads'
$taskLogs = Join-Path $taskRoot 'target/ai-layout-setup'
New-Item -ItemType Directory -Force -Path $taskDownloads, $taskLogs, (Join-Path $taskTools 'layout-ai') | Out-Null

function Get-VerifiedFile {
    param([string]$Url, [string]$Destination, [string]$Sha256)
    if ((Test-Path -LiteralPath $Destination) -and (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $Sha256) {
        Write-Host "Verified existing $Destination"
        return
    }
    $taskPartial = $Destination + '.partial-' + [guid]::NewGuid().ToString('N')
    try {
        Write-Host "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $taskPartial -TimeoutSec 1200
        if ((Get-FileHash -LiteralPath $taskPartial -Algorithm SHA256).Hash -ne $Sha256) { throw "SHA256 mismatch: $Url" }
        Move-Item -LiteralPath $taskPartial -Destination $Destination -Force
    } finally {
        if (Test-Path -LiteralPath $taskPartial) { Remove-Item -LiteralPath $taskPartial -Force }
    }
}

function Invoke-HiddenProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$LogName, [int]$TimeoutSeconds = 120)
    $taskStart = New-Object Diagnostics.ProcessStartInfo
    $taskStart.FileName = $Executable
    $taskStart.UseShellExecute = $false
    $taskStart.CreateNoWindow = $true
    $taskStart.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $taskStart.RedirectStandardOutput = $true
    $taskStart.RedirectStandardError = $true
    # Windows command-line quoting, including trailing backslashes before a closing quote.
    $taskStart.Arguments = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + [regex]::Replace([regex]::Replace($_, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"' }
        else { $_ }
    }) -join ' '
    $taskProcess = New-Object Diagnostics.Process
    $taskProcess.StartInfo = $taskStart
    try {
        [void]$taskProcess.Start()
        $taskOut = $taskProcess.StandardOutput.ReadToEndAsync()
        $taskErr = $taskProcess.StandardError.ReadToEndAsync()
        if (-not $taskProcess.WaitForExit($TimeoutSeconds * 1000)) {
            $taskFailure = "Process timed out after ${TimeoutSeconds}s: $Executable"
            [IO.File]::WriteAllText((Join-Path $taskLogs $LogName), $taskFailure)
            if ($PSVersionTable.PSVersion.Major -ge 7) { $taskProcess.Kill($true) }
            else { $taskProcess.Kill() }
            throw "$taskFailure. See $taskLogs/$LogName. Check local application startup and third-party desktop hooks."
        }
        $taskText = $taskOut.GetAwaiter().GetResult() + $taskErr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $taskLogs $LogName), $taskText)
        if ($taskProcess.ExitCode -ne 0) { throw "Process exited $($taskProcess.ExitCode): $Executable. See $taskLogs/$LogName" }
        return $taskText.Trim()
    } finally { $taskProcess.Dispose() }
}

function Initialize-RenderProbe {
    $taskProbe = Join-Path $taskRoot 'target/ai-layout-render-probe'
    $taskProfile = Join-Path $taskProbe ('profile-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path (Join-Path $taskProfile 'user') | Out-Null
    # Defined by LibreOffice's Common.xcs and Writer.xcs schemas, before the document opens.
    $taskRegistry = @'
<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry">
 <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop><prop oor:name="DisableActiveContent" oor:op="fuse"><value>true</value></prop><prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop></item>
 <item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item>
</oor:items>
'@
    [IO.File]::WriteAllText((Join-Path $taskProfile 'user/registrymodifications.xcu'), $taskRegistry)
    Copy-Item -LiteralPath (Join-Path $taskRoot 'src/test/resources/corpus/research-complex.docx') -Destination (Join-Path $taskProbe 'source.docx') -Force
    $taskPdf = Join-Path $taskProbe 'source.pdf'
    if (Test-Path -LiteralPath $taskPdf) { Remove-Item -LiteralPath $taskPdf -Force }
    return [pscustomobject]@{ Directory = $taskProbe; Profile = $taskProfile; Pdf = $taskPdf }
}

function Confirm-RenderProbe {
    param([string]$Pdf)
    if (-not (Test-Path -LiteralPath $Pdf) -or (Get-Item -LiteralPath $Pdf).Length -lt 5) { throw "LibreOffice did not produce $Pdf. Inspect $taskLogs/libreoffice-render.log" }
    $taskPdfStream = [IO.File]::OpenRead($Pdf)
    try { $taskHeader = New-Object byte[] 5; [void]$taskPdfStream.Read($taskHeader, 0, 5) } finally { $taskPdfStream.Dispose() }
    if ([Text.Encoding]::ASCII.GetString($taskHeader) -ne '%PDF-') { throw 'The render probe output is not a PDF.' }
    Write-Host "Render probe ready: $Pdf"
}

function Set-DockerRenderer {
    param([string]$Image)
    $taskSettings = Join-Path $taskTools 'layout-ai/renderer.properties'
    $taskText = if (Test-Path -LiteralPath $taskSettings) { [IO.File]::ReadAllText($taskSettings) } else { '' }
    $taskPattern = '(?m)^\s*doc2tex\.ai-layout\.docker-image\s*[=:][^\r\n]*'
    $taskSetting = "doc2tex.ai-layout.docker-image=$Image"
    if ([regex]::IsMatch($taskText, $taskPattern)) { $taskText = [regex]::Replace($taskText, $taskPattern, $taskSetting) }
    else { $taskText = $taskText.TrimEnd() + "`n" + $taskSetting + "`n" }
    [IO.File]::WriteAllText($taskSettings, $taskText.TrimStart("`r", "`n"))
    Write-Host "Docker renderer selected in $taskSettings"
}

function Clear-DockerRenderer {
    $taskSettings = Join-Path $taskTools 'layout-ai/renderer.properties'
    if (-not (Test-Path -LiteralPath $taskSettings)) { return }
    $taskPattern = '(?m)^\s*doc2tex\.ai-layout\.docker-image\s*[=:][^\r\n]*\r?\n?'
    $taskText = [regex]::Replace([IO.File]::ReadAllText($taskSettings), $taskPattern, '')
    if ($taskText.Trim()) { [IO.File]::WriteAllText($taskSettings, $taskText.TrimStart("`r", "`n")) }
    else { Remove-Item -LiteralPath $taskSettings -Force }
}

$taskRevision = '46bbdf188bb0a772c08aed74882ce7e51a8f1ea6'
$taskModelBase = "https://huggingface.co/PaddlePaddle/PP-DocLayoutV3_onnx/resolve/$taskRevision"
Get-VerifiedFile "$taskModelBase/inference.onnx" (Join-Path $taskTools 'layout-ai/inference.onnx') '45bf71750b00739a41fc209f132eb104a4d6b5bb29483c9078164d8b87cf28ba'
Get-VerifiedFile "$taskModelBase/inference.yml" (Join-Path $taskTools 'layout-ai/inference.yml') '506fcfac13b3b546ae40d7886b44126420f392adb694e3f8bb6a6286a1f90fdc'

if ($DockerRenderer) {
    $taskDockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $taskDockerCommand) { throw 'Docker is required for -DockerRenderer. Install Docker Desktop, then rerun this explicit setup mode.' }
    $taskDocker = $taskDockerCommand.Source
    try { Invoke-HiddenProcess $taskDocker @('info', '--format', '{{.OSType}}') 'docker-info.log' 15 | Out-Null }
    catch {
        Write-Host 'Starting the installed Docker Desktop for the requested container renderer...'
        Invoke-HiddenProcess $taskDocker @('desktop', 'start', '--timeout', '60') 'docker-start.log' 75 | Out-Null
    }
    $taskDockerOs = Invoke-HiddenProcess $taskDocker @('info', '--format', '{{.OSType}}') 'docker-info.log' 15
    if ($taskDockerOs -ne 'linux') { throw 'The layout renderer requires the Docker Linux engine.' }
    $taskImage = 'doc2tex-layout-renderer:26.2.6'
    Write-Host 'Building the pinned LibreOffice container renderer...'
    Invoke-HiddenProcess $taskDocker @('build', '--platform', 'linux/amd64', '--progress', 'plain', '--tag', $taskImage, (Join-Path $taskRoot 'packaging/layout-renderer')) 'docker-build.log' 1800 | Out-Null
    Write-Host (Invoke-HiddenProcess $taskDocker @('run', '--rm', '--network', 'none', $taskImage, '--headless', '--version') 'libreoffice-version.log' 30)
    $taskProbe = Initialize-RenderProbe
    $taskContainerProfile = '/work/' + (Split-Path -Leaf $taskProbe.Profile)
    $taskContainerName = 'doc2tex-layout-setup-' + [guid]::NewGuid().ToString('N')
    try {
        $taskMount = 'type=bind,"source=' + $taskProbe.Directory.Replace('"', '""') + '",target=/work'
        $taskRenderArguments = @('run', '--rm', '--name', $taskContainerName, '--network', 'none', '--pull=never', '--env', 'XDG_CACHE_HOME=/work/cache')
        if ($env:OS -ne 'Windows_NT') {
            $taskUid = & id -u; $taskGid = & id -g
            $taskRenderArguments += @('--user', "${taskUid}:${taskGid}")
            $taskRenderArguments += @('--mount', $taskMount)
        } else {
            $taskRenderArguments += @('--volume', ($taskProbe.Directory + ':/work:rw'))
        }
        $taskRenderArguments += @($taskImage, "-env:UserInstallation=file://$taskContainerProfile/", '--headless', '--nologo', '--nodefault', '--nofirststartwizard', '--norestore', '--convert-to', 'pdf:writer_pdf_Export', '--outdir', '/work', '/work/source.docx')
        Write-Host (Invoke-HiddenProcess $taskDocker $taskRenderArguments 'libreoffice-render.log' 120)
        Confirm-RenderProbe $taskProbe.Pdf
        Set-DockerRenderer $taskImage
    } finally {
        try { Invoke-HiddenProcess $taskDocker @('rm', '--force', $taskContainerName) 'docker-probe-cleanup.log' 15 | Out-Null }
        catch { Write-Verbose 'The setup container was already removed by --rm.' }
    }
    Write-Host 'Local AI layout prerequisites ready. Docker renders documents; inference stays on the host.'
    return
}

$taskSoffice = $env:SOFFICE_PATH
if ($taskSoffice) {
    if (-not (Test-Path -LiteralPath $taskSoffice -PathType Leaf)) { throw 'SOFFICE_PATH must point to a LibreOffice executable.' }
} elseif ($env:OS -eq 'Windows_NT') {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'The pinned local LibreOffice build requires 64-bit Windows. Set SOFFICE_PATH to a compatible installation.' }
    $taskDestination = [IO.Path]::GetFullPath((Join-Path $taskTools 'libreoffice'))
    $taskSoffice = Join-Path $taskDestination 'program/soffice.com'
    if (-not (Test-Path -LiteralPath $taskSoffice -PathType Leaf)) {
        $taskMsi = Join-Path $taskDownloads 'LibreOffice_26.2.6_Win_x86-64.msi'
        # Published by The Document Foundation at the matching .msi.sha256 URL.
        Get-VerifiedFile 'https://download.documentfoundation.org/libreoffice/stable/26.2.6/win/x86_64/LibreOffice_26.2.6_Win_x86-64.msi' $taskMsi 'f9877032fd908beb9c0ddf06df4af5c2e85f419c42e14876c4cce5aae5fb2660'
        $taskStage = [IO.Path]::GetFullPath((Join-Path $taskTools ('libreoffice-stage-' + [guid]::NewGuid().ToString('N'))))
        $taskToolsPrefix = [IO.Path]::GetFullPath($taskTools).TrimEnd('\') + '\'
        foreach ($taskPath in @($taskStage, $taskDestination)) {
            if (-not $taskPath.StartsWith($taskToolsPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'LibreOffice extraction path escaped the project tools folder.' }
        }
        if (Test-Path -LiteralPath $taskDestination) { throw "Incomplete local LibreOffice exists at $taskDestination. Rename it before retrying; no files were replaced." }
        try {
            Write-Host 'Extracting LibreOffice locally (administrative image only; no system installation)...'
            $taskMsiLog = Join-Path $taskLogs 'libreoffice-msi.log'
            Invoke-HiddenProcess (Join-Path $env:SystemRoot 'System32/msiexec.exe') @('/a', $taskMsi, '/qn', '/norestart', "TARGETDIR=$taskStage", '/L*v', $taskMsiLog) 'libreoffice-extraction.log' 600 | Out-Null
            $taskCandidates = @(Get-ChildItem -LiteralPath $taskStage -Filter 'soffice.com' -Recurse -File)
            if ($taskCandidates.Count -ne 1) { throw 'Expected one soffice.com in the verified LibreOffice image.' }
            $taskIncoming = Split-Path -Parent $taskCandidates[0].DirectoryName
            Move-Item -LiteralPath $taskIncoming -Destination $taskDestination
        } finally {
            if (Test-Path -LiteralPath $taskStage) { Remove-Item -LiteralPath $taskStage -Recurse -Force }
        }
    }
} else {
    $taskCommand = Get-Command libreoffice, soffice -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskCommand) { $taskSoffice = $taskCommand.Source }
    elseif (Test-Path -LiteralPath '/Applications/LibreOffice.app/Contents/MacOS/soffice') { $taskSoffice = '/Applications/LibreOffice.app/Contents/MacOS/soffice' }
    else { throw 'Install LibreOffice manually: Debian/Ubuntu: sudo apt install libreoffice-writer; Fedora: sudo dnf install libreoffice-writer; macOS: brew install --cask libreoffice. Then rerun, or set SOFFICE_PATH to its executable. This script never installs system packages.' }
}

$taskVersionProfile = Join-Path $taskLogs ('version-profile-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $taskVersionProfile | Out-Null
$taskVersionProfileUri = ([Uri]([IO.Path]::GetFullPath($taskVersionProfile) + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
Write-Host (Invoke-HiddenProcess $taskSoffice @("-env:UserInstallation=$taskVersionProfileUri", '--headless', '--version') 'libreoffice-version.log' 30)
Write-Host "SOFFICE_PATH=$taskSoffice"
if (-not $SkipRenderProbe) {
    $taskProbe = Initialize-RenderProbe
    $taskProfileUri = ([Uri]([IO.Path]::GetFullPath($taskProbe.Profile) + [IO.Path]::DirectorySeparatorChar)).AbsoluteUri
    Write-Host (Invoke-HiddenProcess $taskSoffice @("-env:UserInstallation=$taskProfileUri", '--headless', '--nologo', '--nodefault', '--nofirststartwizard', '--norestore', '--convert-to', 'pdf:writer_pdf_Export', '--outdir', $taskProbe.Directory, (Join-Path $taskProbe.Directory 'source.docx')) 'libreoffice-render.log' 120)
    Confirm-RenderProbe $taskProbe.Pdf
}
Clear-DockerRenderer
Write-Host 'Local AI layout prerequisites ready. No PATH or registry settings were changed.'
