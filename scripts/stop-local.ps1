$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot '.tools\local-processes.json'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host 'No local processes recorded.'; exit 0 }
$record = Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
if (-not [string]::Equals([string]$record.projectRoot, $projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Host 'Discarding a legacy or copied process record. No process was stopped.'
    Remove-Item -LiteralPath $pidFile
    exit 0
}
# Validate every identity before stopping anything: copied/reused PIDs are unsafe.
$processes = @()
foreach ($entry in @($record.backend,$record.frontend)) {
    $processId = 0
    $startedAt = [DateTimeOffset]::MinValue
    if (-not [int]::TryParse([string]$entry.id, [ref]$processId) -or $processId -le 0 -or
        -not [DateTimeOffset]::TryParse([string]$entry.startedAtUtc, [ref]$startedAt)) {
        throw 'Invalid local process record. No process was stopped.'
    }
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    if (-not [string]::Equals($process.Path, [string]$entry.executable, [StringComparison]::OrdinalIgnoreCase) -or
        $process.StartTime.ToUniversalTime().Ticks -ne $startedAt.UtcDateTime.Ticks) {
        throw 'A recorded PID belongs to a different process. No process was stopped.'
    }
    $processes += $process
}
foreach ($process in $processes) { Stop-Process -InputObject $process }
Remove-Item -LiteralPath $pidFile
