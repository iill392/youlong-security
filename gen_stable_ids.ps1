# Convert aapt2 dump format to stable-ids format
# Input: "    resource 0x7f010000 anim/abc_fade_in"
# Output: "anim/abc_fade_in = 0x7f010000"
$content = Get-Content "d:\codex\YouLongTool\ds_res_ids_raw.txt"
$lines = $content | ForEach-Object {
    if ($_ -match '^\s+resource\s+(0x[0-9a-f]+)\s+(\S+/\S+)') {
        "$($Matches[2]) = $($Matches[1])"
    }
}
$lines | Set-Content "d:\codex\YouLongTool\ds_stable_ids.txt"
Write-Host "Generated $($lines.Count) stable IDs -> $( (Get-Item 'd:\codex\YouLongTool\ds_stable_ids.txt').Length) bytes"
