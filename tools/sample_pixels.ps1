Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile($args[0])
Write-Output ("size: {0} x {1}" -f $img.Width, $img.Height)
$pts = @(
    @(540, 30,   'statusbar'),
    @(540, 150,  'teal-band'),
    @(540, 260,  'below-teal'),
    @(540, 330,  'title-row'),
    @(540, 500,  'gap'),
    @(540, 700,  'content-bg'),
    @(300, 1180, 'card'),
    @(540, 2290, 'bottomnav')
)
foreach ($p in $pts) {
    $c = $img.GetPixel($p[0], $p[1])
    Write-Output ("{0,-12} ({1,4},{2,4})  #{3:X2}{4:X2}{5:X2}" -f $p[2], $p[0], $p[1], $c.R, $c.G, $c.B)
}
# 扫描竖直方向 x=540 的颜色变化，找出色带边界
Write-Output '--- vertical scan at x=540 ---'
$prev = ''
for ($y = 0; $y -lt 420; $y += 4) {
    $c = $img.GetPixel(540, $y)
    $hex = '#{0:X2}{1:X2}{2:X2}' -f $c.R, $c.G, $c.B
    if ($hex -ne $prev) {
        Write-Output ("y={0,4}  {1}" -f $y, $hex)
        $prev = $hex
    }
}
$img.Dispose()
