$content = Get-Content "d:\codex\YouLongTool\ds_res_ids_raw.txt" -Encoding UTF8
$lines = @()
foreach ($_ in $content) {
    if ($_ -match '^\s+resource\s+(0x[0-9a-f]+)\s+(\S+/\S+)') {
        $id = $Matches[1].ToUpper()
        $lines += "$($Matches[2]) = $id"
    }
}
# Write with UTF8 without BOM, CRLF
[System.IO.File]::WriteAllText("d:\codex\YouLongTool\ds_stable_ids.txt", ($lines -join "`r`n") + "`r`n")
Write-Host "OK: $($lines.Count) lines"