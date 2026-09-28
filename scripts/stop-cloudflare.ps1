$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot '.tools\cloudflare-process.json'
$expectedExe = Join-Path $projectRoot '.tools\cloudflared\cloudflared.exe'
if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host 'No Cloudflare connector process is recorded.'
    exit 0
}
try { $record = [IO.File]::ReadAllText($pidFile) | ConvertFrom-Json } catch { throw 'Invalid cloudflare-process.json. No process was stopped.' }
$recordedId = 0
if (-not [int]::TryParse([string]$record.pid, [ref]$recordedId) -or $recordedId -le 0 -or
    -not [string]::Equals([string]$record.executable, $expectedExe, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The recorded process does not belong to this Cloudflare launcher. No process was stopped.'
}
$connector = Get-Process -Id $recordedId -ErrorAction SilentlyContinue
if (-not $connector) {
    Remove-Item -LiteralPath $pidFile
    Write-Host 'The recorded Cloudflare connector has already exited; removed its stale PID record.'
    exit 0
}
$recordedStart = [DateTimeOffset]::MinValue
if (-not [DateTimeOffset]::TryParse([string]$record.startedAtUtc, [ref]$recordedStart) -or
    -not [string]::Equals($connector.Path, $expectedExe, [StringComparison]::OrdinalIgnoreCase) -or
    $connector.StartTime.ToUniversalTime().Ticks -ne $recordedStart.UtcDateTime.Ticks) {
    throw 'The recorded PID is not the originally launched Cloudflare connector. No process was stopped.'
}
Stop-Process -InputObject $connector -Force
if (-not $connector.WaitForExit(5000)) { throw 'Cloudflare connector termination timed out; its PID record was retained.' }
Remove-Item -LiteralPath $pidFile
Write-Host 'Stopped the recorded Cloudflare connector. The local web application is still running.'
