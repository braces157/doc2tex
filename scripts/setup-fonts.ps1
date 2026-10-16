param([string]$FontsDirectory)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Global -Force
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskFonts = if ($FontsDirectory) { [IO.Path]::GetFullPath($FontsDirectory) } else { Join-Path $taskRoot '.tools/fonts' }
New-Item -ItemType Directory -Path $taskFonts -Force | Out-Null
$taskNoto = 'https://raw.githubusercontent.com/notofonts/noto-fonts/ffebf8c1ee449e544955a7e813c54f9b73848eac'
$taskCjk = 'https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c'
$taskAssets = @(
    @{ name='NotoSerif-Regular.ttf'; url="$taskNoto/hinted/ttf/NotoSerif/NotoSerif-Regular.ttf"; hash='C8F669CEB2C9C60CCF55198B305E08A997FFCA79A38CC7EEB551E643CBE66505' },
    @{ name='NotoSansThai-Regular.ttf'; url="$taskNoto/hinted/ttf/NotoSansThai/NotoSansThai-Regular.ttf"; hash='404DDFB5ED0AAA6B6EC8A85700D682978992062D67DA93903967B56CBD9A4ACC' },
    @{ name='NotoNaskhArabic-Regular.ttf'; url="$taskNoto/hinted/ttf/NotoNaskhArabic/NotoNaskhArabic-Regular.ttf"; hash='2F4B88E6EE50FA82C617E2D1D4BA18281CB1C6CD71C3AF3EC64970C23995DB4B' },
    @{ name='NotoSansCJKsc-Regular.otf'; url="$taskCjk/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf"; hash='2C76254F6FC379FDDFCE0A7E84FB5385BB135D3E399294F6EEB6680D0365B74B' },
    @{ name='OFL-Noto.txt'; url="$taskNoto/LICENSE"; hash='0DAB92D0544F7B233403F14B84A663BDBFA746982EDA629E7F4F9FFE1B036FEB' },
    @{ name='OFL-Noto-CJK.txt'; url="$taskCjk/Sans/LICENSE"; hash='6A73F9541C2DE74158C0E7CF6B0A58EF774F5A780BF191F2D7EC9CC53EFE2BF2' }
)
foreach ($taskAsset in $taskAssets) {
    $taskTarget = Join-Path $taskFonts $taskAsset.name
    if ((Test-Path -LiteralPath $taskTarget) -and (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -eq $taskAsset.hash) { continue }
    Write-Host "Downloading $($taskAsset.name)..."
    Invoke-WebRequest -Uri $taskAsset.url -OutFile $taskTarget -TimeoutSec 120
    if ((Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -ne $taskAsset.hash) { throw "Font checksum failed: $($taskAsset.name)" }
}
Write-Host 'Multilingual fonts ready. Font licenses are included in multilingual exports.'
