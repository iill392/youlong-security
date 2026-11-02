# 验证 release APK 完整性（二进制扫描 dex，不反编译）
# 用法: powershell -ExecutionPolicy Bypass -File scripts\verify_release_apk.ps1

$ErrorActionPreference = 'Stop'
$apkDir = "e:\安全护盾\anquan\build-out\outputs\apk\release"
$apk = Get-ChildItem $apkDir -Filter "app-release-*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $apk) { Write-Host "❌ 未找到 release APK"; exit 1 }
Write-Host "✅ APK: $($apk.Name) ($([Math]::Round($apk.Length/1MB,1)) MB, $($apk.LastWriteTime))"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)

# 1. 检查 dex 条目
$dexEntries = $zip.Entries | Where-Object { $_.Name -match '^classes\d*\.dex$' }
Write-Host "`n=== dex 条目 ==="
foreach ($e in $dexEntries) {
    Write-Host "  $($e.FullName): $($e.Length) bytes"
}

# 2. 二进制扫描关键类描述符（R8 可能重打包，扫多种描述符形式）
$needles = @(
    'Lcom/youlong/hd/YouLongApp;',
    'Lcom/youlong/hd/MainActivity;',
    'Lcom/youlong/hd/StrX;',
    'Lcom/youlong/hd/YouLongShield;',
    'Lcom/youlong/hd/MainScreenKt;',
    'Lcom/youlong/hd/ForegroundService;'
)

Write-Host "`n=== 类描述符扫描（任一 dex）==="
$found = @{}
foreach ($needle in $needles) { $found[$needle] = $false }

foreach ($e in $dexEntries) {
    $stream = $e.Open()
    $ms = New-Object System.IO.MemoryStream
    $stream.CopyTo($ms)
    $stream.Close()
    $bytes = $ms.ToArray()
    $ms.Close()
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)

    foreach ($needle in $needles) {
        if ($text.Contains($needle)) { $found[$needle] = $true }
    }
}

$allOk = $true
foreach ($needle in $needles) {
    $status = if ($found[$needle]) { '✅' } else { '❌' }
    if (-not $found[$needle]) { $allOk = $false }
    Write-Host "  $status $needle"
}
$zip.Dispose()

# 3. manifest 检查
$aapt = Get-ChildItem "D:\Android\sdk\build-tools" -Recurse -Filter "aapt2.exe" | Sort-Object FullName -Descending | Select-Object -First 1
$badging = & $aapt.FullName dump badging $apk.FullName 2>&1
$pkg = ($badging | Select-String "package: name='([^']+)'" | Select-Object -First 1).Matches.Groups[1].Value
Write-Host "`n=== manifest ==="
Write-Host "  包名: $pkg"
$appName = ($badging | Select-String "application-label:'([^']+)'" | Select-Object -First 1).Matches.Groups[1].Value
Write-Host "  应用名: $appName"

if ($allOk -and $pkg -eq 'com.youlong.hd') {
    Write-Host "`n🎉 APK 验证通过！可以安装"
    exit 0
} else {
    Write-Host "`n❌ APK 验证失败！"
    exit 1
}
