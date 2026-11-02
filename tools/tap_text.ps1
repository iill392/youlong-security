param(
    [Parameter(Mandatory = $true)][string]$Text,
    [switch]$DumpOnly
)

$adb = 'D:\Android\sdk\platform-tools\adb.exe'

& $adb shell 'uiautomator dump /sdcard/_ui.xml' 2>&1 | Out-Null
$xml = (& $adb shell 'cat /sdcard/_ui.xml') -join "`n"

# 把 text="..." 与紧随其后的 bounds="[x1,y1][x2,y2]" 配对
$pattern = 'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
$found = @()
foreach ($m in [regex]::Matches($xml, $pattern)) {
    $t = $m.Groups[1].Value
    if ($t -like "*$Text*") {
        $x1 = [int]$m.Groups[2].Value
        $y1 = [int]$m.Groups[3].Value
        $x2 = [int]$m.Groups[4].Value
        $y2 = [int]$m.Groups[5].Value
        $found += [pscustomobject]@{
            Text  = $t
            X     = [int](($x1 + $x2) / 2)
            Y     = [int](($y1 + $y2) / 2)
            Bounds = "$x1,$y1 - $x2,$y2"
        }
    }
}

if ($found.Count -eq 0) {
    Write-Output "NOT FOUND: $Text"
    exit 1
}

$found | ForEach-Object { Write-Output ("FOUND '{0}'  tap=({1},{2})  bounds={3}" -f $_.Text, $_.X, $_.Y, $_.Bounds) }

if (-not $DumpOnly) {
    $t = $found[0]
    & $adb shell "input tap $($t.X) $($t.Y)" | Out-Null
    Write-Output ("TAPPED ({0},{1})" -f $t.X, $t.Y)
}
