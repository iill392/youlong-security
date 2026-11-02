$content = Get-Content "d:\codex\YouLongTool\ds_res_ids_raw.txt" -Encoding UTF8
$lines = @()
foreach ($_ in $content) {
    if ($_ -match '^\s+resource\s+(0x[0-9a-f]+)\s+(\S+/\S+)') {
        $lines += "$($Matches[2]) = $($Matches[1])"
    }
}
[System.IO.File]::WriteAllLines("d:\codex\YouLongTool\ds_stable_ids.txt", $lines)
Write-Host "OK: $($lines.Count) lines, $( (Get-Item 'd:\codex\YouLongTool\ds_stable_ids.txt').Length) bytes, ASCII=$([System.Text.Encoding]::ASCII.GetString((Get-Content 'd:\codex\YouLongTool\ds_stable_ids.txt' -Encoding Byte -TotalCount 3)))"