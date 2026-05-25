param(
    [string]$DeviceArch = "arm64"
)

$ErrorActionPreference = "Continue"
$AssetsDir = Join-Path $PSScriptRoot "app\src\main\assets"

if (-not (Test-Path $AssetsDir)) { New-Item -ItemType Directory -Path $AssetsDir -Force | Out-Null }

Write-Host "============================================"
Write-Host "  JixVPN 构建准备工具"
Write-Host "============================================"
Write-Host ""

# ---- Step 1: 检查/安装 Go ----
$goPath = (Get-Command "go" -ErrorAction SilentlyContinue).Source
if (-not $goPath) {
    Write-Host "[1/3] Go 未安装，正在通过 winget 安装..."
    winget install GoLang.Go --silent --accept-package-agreements | Out-Null
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    $goPath = (Get-Command "go" -ErrorAction SilentlyContinue).Source
    if (-not $goPath) {
        Write-Host "Go 安装失败，请手动安装: https://go.dev/dl/"
        pause
        exit 1
    }
    Write-Host "  Go 安装完成: $goPath"
} else {
    Write-Host "[1/3] Go 已安装: $goPath"
}

$goVer = go version
Write-Host "  版本: $goVer"

# ---- Step 2: 编译 gotun ----
Write-Host "[2/3] 编译 gotun (目标: android/$DeviceArch)..."
$gotunDir = Join-Path $PSScriptRoot "gotun"
$output = Join-Path $AssetsDir "gotun-arm64"

Push-Location $gotunDir
go mod tidy | Out-Null
$env:GOOS = "android"
$env:GOARCH = $DeviceArch
$env:CGO_ENABLED = "0"
go build -ldflags="-s -w" -o $output .
if ($LASTEXITCODE -eq 0) {
    Write-Host "  gotun 编译成功: $output"
} else {
    Write-Host "  gotun 编译失败！"
    pause
    exit 1
}
Pop-Location

# ---- Step 3: 下载 mihomo ----
Write-Host "[3/3] 下载 mihomo 核心..."
$mihomoVer = "v1.19.25"
$mihomoUrl = "https://github.com/MetaCubeX/mihomo/releases/download/$mihomoVer/mihomo-android-arm64-v8-$mihomoVer.gz"
$mihomoOut = Join-Path $AssetsDir "mihomo-arm64.gz"
$mihomoFinal = Join-Path $AssetsDir "mihomo-arm64"

try {
    Invoke-WebRequest -Uri $mihomoUrl -OutFile $mihomoOut -UseBasicParsing -TimeoutSec 30
    # 解压 .gz (Windows 可能需要 7zip 或 gunzip 命令)
    if (Get-Command "tar" -ErrorAction SilentlyContinue) {
        tar -xzf $mihomoOut -C $AssetsDir
        if (Test-Path (Join-Path $AssetsDir "mihomo-android-arm64-v8-$mihomoVer")) {
            Move-Item (Join-Path $AssetsDir "mihomo-android-arm64-v8-$mihomoVer") $mihomoFinal -Force
        }
    } else {
        # 用 .NET 解压 gzip
        $in = [System.IO.File]::OpenRead($mihomoOut)
        $out = [System.IO.File]::Create($mihomoFinal)
        $gzip = New-Object System.IO.Compression.GzipStream($in, [System.IO.Compression.CompressionMode]::Decompress)
        $gzip.CopyTo($out)
        $gzip.Close(); $out.Close(); $in.Close()
    }
    Remove-Item $mihomoOut -Force -ErrorAction SilentlyContinue
    Write-Host "  mihomo 下载成功: $mihomoFinal"
} catch {
    Write-Host "  mihomo 下载失败: $_"
    Write-Host "  请手动从以下地址下载放到 assets 目录:"
    Write-Host "  $mihomoUrl"
}

# ---- 完成 ----
Write-Host ""
Write-Host "============================================"
Write-Host "  准备完成！"
Write-Host "============================================"
Write-Host ""

Get-ChildItem $AssetsDir | ForEach-Object {
    $size = [math]::Round($_.Length / 1KB)
    Write-Host "  $($_.Name)  ($size KB)"
}

Write-Host ""
Write-Host "现在可以用 Android Studio 打开 $PSScriptRoot 目录编译了"
pause
