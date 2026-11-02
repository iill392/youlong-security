$enc = [Text.Encoding]::UTF8
$targets = @(
  'E:\anquan\embedded\manager\src\main\kotlin\roro\stellar\manager\adb\AdbPairingService.kt',
  'E:\anquan\embedded\manager\src\main\kotlin\roro\stellar\manager\startup\notification\BootStartNotifications.kt'
)
foreach ($p in $targets) {
  $t = [IO.File]::ReadAllText($p, $enc)
  $before = ([regex]::Matches($t, [regex]::Escape('R.drawable.ic_stellar'))).Count
  $t = $t.Replace('R.drawable.ic_stellar', 'android.R.drawable.ic_menu_manage')
  [IO.File]::WriteAllText($p, $t, $enc)
  Write-Output ("{0}: 替换 {1} 处" -f (Split-Path $p -Leaf), $before)
}
