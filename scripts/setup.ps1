$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) {
    Write-Host '.env already exists; keeping all values.'
    exit 0
}
function New-RandomBase64([int]$length) {
    $bytes = New-Object byte[] $length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    [Convert]::ToBase64String($bytes)
}
$template = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.example'))
$template = $template.Replace('DB_PASSWORD=replace-with-random-password', ('DB_PASSWORD=' + (New-RandomBase64 24)))
$template = $template.Replace('TOKEN_ENCRYPTION_KEY=', ('TOKEN_ENCRYPTION_KEY=' + (New-RandomBase64 32)))
[IO.File]::WriteAllText($envPath, $template, (New-Object Text.UTF8Encoding $false))
Write-Host 'Created .env with local secrets. Add GEMINI_API_KEY yourself.'
