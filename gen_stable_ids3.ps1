$content = Get-Content "d:\codex\YouLongTool\ds_res_ids_raw.txt" -Encoding UTF8
$lines = @()
foreach ($_ in $content) {
    if ($_ -match '^\s+resource\s+(0x[0-9a-f]+)\s+(\S+/\S+)') {
        $lines += "com.jamworks.dynamicspot:$($Matches[2]) = $($Matches[1])"
    }
}
[System.IO.File]::WriteAllLines("d:\codex\YouLongTool\ds_stable_ids.txt", $lines)
Write-Host "OK: $($lines.Count) lines"