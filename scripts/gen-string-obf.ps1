# ============================================================
# String obfuscator generator v1.0 (Phase 5)
# Usage:  .\gen-string-obf.ps1 "string to obfuscate"
# Output: Java byte[] literal (XOR 0x5A) for StrX.java
# ============================================================
param([string]$s = "")

if ([string]::IsNullOrEmpty($s)) {
    Write-Host 'Usage: .\gen-string-obf.ps1 "string"' -ForegroundColor Yellow
    exit 1
}

$key = 0x5A
$bytes = [System.Text.Encoding]::UTF8.GetBytes($s)
$parts = @()
foreach ($b in $bytes) {
    $parts += ("0x{0:X2}" -f ($b -bxor $key))
}

Write-Host ""
Write-Host ("// '" + $s + "'") -ForegroundColor Cyan
Write-Host ("// len " + $bytes.Length + " bytes") -ForegroundColor DarkGray
Write-Host ("new byte[]{ " + ($parts -join ", ") + " };")
Write-Host ""

