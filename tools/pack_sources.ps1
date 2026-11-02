$ErrorActionPreference = 'Stop'

$Root = 'E:\anquan'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ZipPath = Join-Path $Root "游龙安全护盾-源码-$Stamp.zip"
$TempDir = Join-Path $env:TEMP "yl_src_$Stamp"

# 需要排除的目录名（任意层级）
$ExcludeDirs = @(
    'build_out', 'build', '.gradle', '.kotlin', '.idea', 'prefab',
    '__pycache__', 'node_modules', '.git', 'captures', '.cxx'
)
# 需要排除的顶层文件（构建产物 / 临时文件）
$ExcludeRootFiles = @(
    'classes.dex', 'classes_dex_dump.txt',
    '游龙安全护盾-9.1.1-debug.apk', '游龙安全护盾-9.1.1-release.apk',
    'shizuku_removed_2026.zip',
    'build_virusdb2.log'
)
# 需要排除的扩展名（产物）
$ExcludeExt = @('.apk', '.aar', '.zip', '.log', '.dex', '.hprof')

Write-Output "准备打包中..."

if (Test-Path $TempDir) { Remove-Item $TempDir -Recurse -Force }
New-Item -ItemType Directory -Path $TempDir | Out-Null
$Stage = Join-Path $TempDir '游龙安全护盾-源码'

# 递归复制，跳过排除项
function Copy-Tree {
    param([string]$Src, [string]$Dst, [int]$Depth = 0)
    New-Item -ItemType Directory -Path $Dst -Force | Out-Null

    foreach ($item in Get-ChildItem -LiteralPath $Src -Force) {
        # 跳过排除的目录
        if ($item.PSIsContainer) {
            if ($ExcludeDirs -contains $item.Name) { continue }
            # 跳过所有 embedded/*/build（构建中间件，非源码）
            Copy-Tree -Src $item.FullName -Dst (Join-Path $Dst $item.Name) -Depth ($Depth + 1)
            continue
        }

        # 顶层文件的排除规则
        if ($Depth -eq 0 -and ($ExcludeRootFiles -contains $item.Name)) { continue }
        if ($ExcludeExt -contains $item.Extension.ToLower()) { continue }
        # 排除 .prefab 相关
        if ($item.Name -like '*.prefab*') { continue }

        Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $Dst $item.Name) -Force
    }
}

Copy-Tree -Src $Root -Dst $Stage

# 统计
$files = Get-ChildItem $Stage -Recurse -File
$sizeMB = ($files | Measure-Object -Property Length -Sum).Sum / 1MB
Write-Output ("暂存文件数: {0}" -f $files.Count)
Write-Output ("暂存体积: {0:N1} MB" -f $sizeMB)

# 压缩
if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
Compress-Archive -Path $Stage -DestinationPath $ZipPath -CompressionLevel Optimal

Remove-Item $TempDir -Recurse -Force

$zip = Get-Item $ZipPath
Write-Output ""
Write-Output ("ZIP: {0}" -f $zip.FullName)
Write-Output ("大小: {0:N1} MB" -f ($zip.Length / 1MB))
