# ============================================================
# 加固效果校验脚本（游龙安全护盾）
# 用法: powershell -File verify_hardening.ps1 <APK路径>
# ============================================================
param([Parameter(Mandatory=$true)][string]$Apk)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
function EntryText($predicate) {
    $e = $zip.Entries | Where-Object { & $predicate $_ } | Select-Object -First 1
    if (-not $e) { return $null }
    $ms = New-Object System.IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    return $ms.ToArray()
}

Write-Host '========== 1. assets 目录内容（应为密文 + .java 伪装） =========='
$zip.Entries | Where-Object { $_.FullName -like 'assets/*' } |
    ForEach-Object { '{0,-28} {1,10} bytes  (存储压缩后 {2})' -f $_.FullName, $_.Length, $_.CompressedLength }

Write-Host ''
Write-Host '========== 2. 明文 HTML/JS 是否泄漏 =========='
$htmlLeak = $zip.Entries | Where-Object { $_.FullName -like 'assets/*' -and ($_.FullName -like '*.html' -or $_.FullName -like '*.js') }
if ($htmlLeak) { Write-Host "❌ 发现明文 HTML/JS: $($htmlLeak.FullName -join ', ')" } else { Write-Host '✅ assets 下无任何 .html / .js 明文文件' }

$assetNames = ($zip.Entries | Where-Object { $_.FullName -like 'assets/*' } | ForEach-Object { $_.FullName }) -join ' '
if ($assetNames -match 'index\.java') { Write-Host '✅ index.html 已伪装为 index.java' } else { Write-Host '⚠️ 未找到 index.java' }

Write-Host ''
Write-Host '========== 3. 加密资产密文特征 =========='
$assetBytes = EntryText { param($e) $e.FullName -like 'assets/*.java' }
if ($assetBytes) {
    # 注意：必须按原始字节统计，不能用 ASCII.GetString —— 非 ASCII 字节会被
    # 替换成 '?'（本身是可打印字符），导致熵判断完全失真。
    $printable = 0
    foreach ($b in $assetBytes) { if ($b -ge 0x20 -and $b -le 0x7E) { $printable++ } }
    $ratio = [math]::Round($printable / $assetBytes.Length, 3)
    Write-Host ("密文长度 = {0} 字节, 可打印字节占比 = {1}" -f $assetBytes.Length, $ratio)
    Write-Host '  参考值：纯随机密文 ≈ 0.371；明文 HTML/JS ≈ 0.95+'
    if ($ratio -lt 0.6) { Write-Host '✅ 呈现随机密文特征（已加密）' }
    elseif ($ratio -lt 0.9) { Write-Host '⚠️ 熵值偏低，需人工确认' }
    else { Write-Host '❌ 接近明文，疑似未加密' }

    # 关键明文串检查（这些在源 index.html 中必然存在）
    $txt = [System.Text.Encoding]::UTF8.GetString($assetBytes)
    $needles = @('showHome', 'volumeTriggerToggle', 'Android.setVolumeTriggerKey', 'function init', '拦截模式')
    foreach ($n in $needles) {
        if ($txt.Contains($n)) { Write-Host "❌ 密文中检出明文串: $n" }
        else { Write-Host "✅ 未检出明文串: $n" }
    }
}

Write-Host ''
Write-Host '========== 4. dex 混淆名称（形近字符字典生效？） =========='
$dexData = New-Object System.IO.MemoryStream
foreach ($e in ($zip.Entries | Where-Object { $_.FullName -like 'classes*.dex' } | Sort-Object FullName)) {
    $s = $e.Open(); $s.CopyTo($dexData); $s.Close()
    Write-Host ("  {0}  {1:N0} bytes" -f $e.FullName, $e.Length)
}
$dexTxt = [System.Text.Encoding]::UTF8.GetString($dexData.ToArray())

# 形近字符类名样例：L + [包名/] + 首字母(l/I/O) + 其余(l/I/1/O/0)x7-15 ;
# 注意 -repackageclasses 会把类搬到 lI1lIlIO 包，因此描述符含 '/'，正则必须允许
$hits = [regex]::Matches($dexTxt, 'L(?:[A-Za-z0-9]{1,20}/)*[lIO][lI1O0]{6,15};')
Write-Host ("形近字典类名命中数 = {0}" -f $hits.Count)
if ($hits.Count -gt 0) {
    $sample = $hits | Select-Object -First 6 | ForEach-Object { $_.Value }
    Write-Host ('  样例: ' + ($sample -join '  '))
    Write-Host '✅ R8 混淆字典（-classobfuscationdictionary）已生效'
} else {
    Write-Host '⚠️ 未命中形近类名 —— 字典可能未生效'
}
Write-Host ("重打包包名 lI1lIlIO 出现次数 = {0}" -f ([regex]::Matches($dexTxt, 'lI1lIlIO')).Count)

Write-Host ''
Write-Host '---------- 4b. 仍保留原名的类（因 -keep，属可接受残留） ----------'
$keepNames = @('ProtectService','MainActivity','AdSkipService','AssetsEncryptor','ShieldWarnActivity','YouLongShield','SignatureVerifier','NativeCrypto','GuardNative','StrX','VirusDb','ShizukuUtils','DeviceAdminReceiver','BootReceiver','ForegroundService','RescueWindowService')
foreach ($k in $keepNames) {
    $c = ([regex]::Matches($dexTxt, [regex]::Escape($k))).Count
    Write-Host ("  {0,-22} 出现 {1} 次" -f $k, $c)
}

Write-Host ''
Write-Host '========== 5. 原始源文件名是否泄漏 =========='
$srcLeaks = @('ProtectService.java','MainActivity.java','AdSkipService.java','AssetsEncryptor.java','ShieldWarnActivity.java','GuardSentinel.cpp','NativeCrypto.cpp')
foreach ($f in $srcLeaks) {
    if ($dexTxt.Contains($f)) { Write-Host "❌ dex 中泄漏源文件名: $f" }
    else { Write-Host "✅ dex 中无: $f" }
}

Write-Host ''
Write-Host '========== 6. 日志字符串是否被剥离 =========='
foreach ($s in @('无障碍音量回调','收到 VOLUME_CHANGED_ACTION 广播','音量按键计数=')) {
    if ($dexTxt.Contains($s)) { Write-Host "❌ 仍存在日志串: $s" } else { Write-Host "✅ 日志串已剥离: $s" }
}

Write-Host ''
Write-Host '========== 7. 关键业务字符串（JS 侧）是否泄漏到 dex =========='
foreach ($s in @('触发按键','连按次数','已设为：连续按')) {
    if ($dexTxt.Contains($s)) { Write-Host "⚠️ dex 中出现 JS 文案: $s" } else { Write-Host "✅ 未泄漏: $s" }
}

$zip.Dispose()

Write-Host ''
Write-Host '========== 8. Native SO 符号表 =========='
$nativeDir = 'build-out\native-symbols'
if (Test-Path $nativeDir) { Remove-Item $nativeDir -Recurse -Force }
New-Item -ItemType Directory -Path $nativeDir -Force | Out-Null
$zip2 = [System.IO.Compression.ZipFile]::OpenRead($Apk)
foreach ($e in ($zip2.Entries | Where-Object { $_.FullName -like 'lib/*/libnativecrypto.so' })) {
    $out = Join-Path $nativeDir ($e.FullName -replace '/', '_')
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
}
$zip2.Dispose()

$readelf = 'D:\Android\sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
$nm = 'D:\Android\sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'
Get-ChildItem $nativeDir -File | ForEach-Object {
    Write-Host ("--- " + $_.Name + " ---")
    if (Test-Path $nm) {
        $syms = & $nm --defined-only --dynamic $_.FullName 2>$null
        Write-Host ("  .dynsym 导出符号: " + ($syms | Measure-Object).Count + " 个")
        $syms | ForEach-Object { "    $_" }
    }
    if (Test-Path $readelf) {
        $sections = & $readelf -S $_.FullName 2>$null
        $hasSymtab = ($sections | Select-String -Pattern '\.symtab').Count -gt 0
        if ($hasSymtab) { Write-Host '  ❌ 仍含 .symtab 符号表（未 strip）' } else { Write-Host '  ✅ 已剥离 .symtab 符号表' }
        $hasEhFrame = ($sections | Select-String -Pattern '\.eh_frame\b').Count -gt 0
        if ($hasEhFrame) {
            Write-Host '  ℹ️ 含 .eh_frame —— arm64 上【刻意保留】：ART/libunwindstack 的异步栈回溯（GC 线程扫描 / ANR / tombstone）依赖它'
            Write-Host '     去掉后会退化到帧指针链回溯，而 -O2 默认省略帧指针 → 回溯失败。收益极小而风险高，故保留。'
        }
        if (($sections | Select-String -Pattern '\.ARM\.exidx').Count -gt 0) {
            Write-Host '  ℹ️ 含 .ARM.exidx —— armeabi-v7a 的展开表（与 arm64 的 .eh_frame 等价），同样刻意保留'
        }
        if (($sections | Select-String -Pattern '\.gcc_except_table').Count -gt 0) {
            Write-Host '  ❌ 仍含 .gcc_except_table（-fno-exceptions 未生效）'
        } else {
            Write-Host '  ✅ 无 .gcc_except_table（异常表已消除，-fno-exceptions 生效）'
        }
        $prog = & $readelf -l $_.FullName 2>$null
        if (($prog -join ' ') -match 'GNU_RELRO') { Write-Host '  ✅ 含 GNU_RELRO 段' }
    }
}
