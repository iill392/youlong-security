$ErrorActionPreference = 'Stop'
# ==========================================================================
# 命令拦截 + 命令分析 规则回归（从源码抽取正则，保证验证的是线上同一份）
# ==========================================================================
$enc = [Text.Encoding]::UTF8
$src = [IO.File]::ReadAllText('E:\anquan\embedded\server\src\main\kotlin\roro\stellar\server\service\process\CommandInterceptor.kt', $enc)
$TQ = '"""'

function Extract-List([string]$blockName) {
    $pat = 'private val ' + $blockName + ' = listOf\((?<body>[\s\S]*?)\n    \)'
    $m = [regex]::Match($src, $pat)
    if (-not $m.Success) { return @() }
    $inner = 'Regex\(' + $TQ + '(.*?)' + $TQ + '\)'
    return @([regex]::Matches($m.Groups['body'].Value, $inner) | ForEach-Object { $_.Groups[1].Value })
}
function Extract-One([string]$name) {
    $pat = 'private val ' + $name + ' = Regex\(' + $TQ + '(.*?)' + $TQ + '\)'
    $m = [regex]::Match($src, $pat)
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
function Extract-Strings([string]$blockName) {
    $pat = 'private val ' + $blockName + ' = listOf\((?<body>[\s\S]*?)\n    \)'
    $m = [regex]::Match($src, $pat)
    if (-not $m.Success) { return @() }
    return @([regex]::Matches($m.Groups['body'].Value, '"([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}

$stopAll  = Extract-List 'STOP_ALL_PATTERNS'
$needConf = Extract-List 'NEED_CONFIRM_PATTERNS'
$adminPat = Extract-List 'ADMIN_PATTERNS'
$critPkgs = Extract-Strings 'CRITICAL_SYSTEM_PACKAGES'
$enumPkg  = Extract-One 'ENUMERATE_PACKAGES'
$loopStop = Extract-One 'LOOP_STOP'
$writeOps = [regex]::Match($src, 'val writeOps = Regex\(\s*' + $TQ + '([\s\S]*?)' + $TQ + '\s*\)').Groups[1].Value
$readOps  = [regex]::Match($src, 'val readOps = Regex\(' + $TQ + '(.*?)' + $TQ + '\)').Groups[1].Value
$sysPrefix = '^(android|com\.android|com\.google\.android|com\.qualcomm|com\.mediatek|com\.miui|com\.xiaomi|com\.huawei|com\.honor|com\.oppo|com\.coloros|com\.vivo|com\.bbk|com\.oneplus|com\.oplus|com\.realme|com\.meizu|com\.smartisan|com\.sony|com\.samsung|com\.sec|com\.lg|com\.htc|com\.motorola|com\.lenovo|com\.asus|com\.nokia|com\.zte)\.'

Write-Output ('抽取: STOP_ALL=' + $stopAll.Count + ' NEED_CONFIRM=' + $needConf.Count + ' ADMIN=' + $adminPat.Count + ' CRITICAL=' + $critPkgs.Count)
Write-Output ''

$selfPkgs = @('com.youlong.hd','com.youlong.tool')

function Get-Packages([string]$flat) {
    $pkgRegex = '\b([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+){2,})\b'
    $out = @()
    foreach ($m in [regex]::Matches($flat, $pkgRegex)) {
        $p = $m.Groups[1].Value
        if ($p -notlike 'android.*' -and $p -notlike 'java.*' -and $p -notlike 'com.android.internal*') { $out += $p }
    }
    return @($out | Select-Object -Unique)
}

function Get-Decision([string]$cmd) {
    $flat = ($cmd -replace '\s+', ' ').Trim()
    $isReadOnly = $false
    if (-not [regex]::IsMatch($flat, $writeOps)) { $isReadOnly = [regex]::IsMatch($flat, $readOps) }

    foreach ($sp in $selfPkgs) {
        if ($flat.Contains($sp) -and -not $isReadOnly) { return 'BLOCK-SELF' }
    }
    foreach ($r in $stopAll) { if ([regex]::IsMatch($flat, $r)) { return 'BLOCK-STOPALL' } }
    if ([regex]::IsMatch($flat, $enumPkg) -and [regex]::IsMatch($flat, $loopStop)) { return 'BLOCK-STOPALL' }

    $needHit = $false
    foreach ($r in $needConf) { if ([regex]::IsMatch($flat, $r)) { $needHit = $true; break } }
    if ($needHit) {
        foreach ($r in $adminPat) { if ([regex]::IsMatch($flat, $r)) { return 'CONFIRM:admin' } }
        $sysHit = $false
        foreach ($c in $critPkgs) { if ($flat.Contains($c)) { $sysHit = $true; break } }
        if (-not $sysHit) {
            foreach ($p in Get-Packages $flat) { if ([regex]::IsMatch($p, $sysPrefix)) { $sysHit = $true; break } }
        }
        if ($sysHit) { return 'CONFIRM:system' }
        if ((Get-Packages $flat).Count -ge 3) { return 'CONFIRM:bulk' }
        return 'CONFIRM:normal'
    }
    foreach ($r in $adminPat) { if ([regex]::IsMatch($flat, $r)) { return 'CONFIRM:admin' } }
    return 'ALLOW'
}

$cases = @(
    @('pm list packages -3', 'ALLOW'),
    @('pm list packages | grep com.youlong.hd', 'ALLOW'),
    @('dumpsys package com.youlong.hd', 'ALLOW'),
    @('am force-stop com.youlong.hd', 'BLOCK'),
    @('pm uninstall com.youlong.tool', 'BLOCK'),
    @('am kill-all', 'BLOCK'),
    @('pkill -f com.example', 'BLOCK'),
    @('pm list packages -3 | while read p; do am force-stop $p; done', 'BLOCK'),
    @('pm uninstall --user 0 com.example.evil', 'CONFIRM:normal'),
    @('pm disable-user --user 0 com.example.evil', 'CONFIRM:normal'),
    @('pm suspend com.example.evil', 'CONFIRM:normal'),
    @('pm clear com.example.evil', 'CONFIRM:normal'),
    @('pm hide com.example.evil', 'CONFIRM:normal'),
    @('pm uninstall --user 0 com.android.settings', 'CONFIRM:system'),
    @('pm disable-user --user 0 com.android.systemui', 'CONFIRM:system'),
    @('pm suspend com.android.packageinstaller', 'CONFIRM:system'),
    @('pm disable-user --user 0 com.miui.home', 'CONFIRM:system'),
    @('pm uninstall --user 0 com.a.b com.c.d com.e.f', 'CONFIRM:bulk'),
    @('dpm remove-active-admin com.evil/com.evil.Admin', 'CONFIRM:admin'),
    @('pm set-user-restriction no_uninstall_apps 1', 'CONFIRM:admin'),
    @('cmd device_policy set-device-owner com.evil/.Admin', 'CONFIRM:admin'),
    @('am force-stop com.example.evil', 'ALLOW'),
    @('settings put global adb_enabled 1', 'ALLOW')
)

$pass = 0; $fail = 0
foreach ($c in $cases) {
    $got = Get-Decision $c[0]
    $ok = $got.StartsWith($c[1])
    if ($ok) { $pass++ } else { $fail++ }
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Output ('{0}  want {1,-16} got {2,-16} {3}' -f $tag, $c[1], $got, $c[0])
}
Write-Output ''
Write-Output ('TOTAL: PASS=' + $pass + ' FAIL=' + $fail)
if ($fail -gt 0) { exit 1 }
