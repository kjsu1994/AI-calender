param([ValidateSet('Sample','Google')][string]$Mode = 'Sample', [switch]$Https, [switch]$Cloudflare)
$ErrorActionPreference = 'Stop'
if ($Https -and $Cloudflare) { throw 'Choose either local -Https or -Cloudflare. Cloudflare connects to the HTTP origin.' }
if ($Cloudflare -and $Mode -ne 'Google') { throw 'Cloudflare requires -Mode Google.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
& "$PSScriptRoot\setup.ps1"
foreach ($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.env'))) {
    if ($line -notmatch '^\s*(?:export\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') { continue }
    $name = $matches[1]
    $value = $matches[2].Trim()
    if ($value.StartsWith('"')) {
        if ($value -notmatch '^"([^"]*)"\s*(?:#.*)?$') { throw "Invalid quoted value for $name in .env." }
        $value = $matches[1]
    } elseif ($value.StartsWith("'")) {
        if ($value -notmatch "^'([^']*)'\s*(?:#.*)?$") { throw "Invalid quoted value for $name in .env." }
        $value = $matches[1]
    } else {
        $value = ($value -split '\s+#', 2)[0].Trim()
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
$appPort = 8085
if ($env:APP_PORT) {
    if (-not [int]::TryParse($env:APP_PORT, [ref]$appPort) -or $appPort -lt 1 -or $appPort -gt 65535) { throw 'APP_PORT must be between 1 and 65535.' }
}
$env:LOCAL_HTTPS = if ($Https) { 'true' } else { 'false' }
$env:CLOUDFLARE_MODE = if ($Cloudflare) { 'true' } else { 'false' }
if ($Cloudflare) {
    $publicAddress = $null
    if (-not [Uri]::TryCreate($env:CLOUDFLARE_PUBLIC_URL, [UriKind]::Absolute, [ref]$publicAddress) -or
        $publicAddress.Scheme -ne 'https' -or $publicAddress.HostNameType -ne [UriHostNameType]::Dns -or
        $publicAddress.IsLoopback -or $publicAddress.Host.EndsWith('.localhost') -or
        $publicAddress.AbsolutePath -ne '/' -or $publicAddress.Query -or $publicAddress.Fragment -or
        $publicAddress.UserInfo -or $publicAddress.Port -ne 443) {
        throw 'CLOUDFLARE_PUBLIC_URL must be an HTTPS domain origin, for example https://ctfmon.shop.'
    }
    $env:CLOUDFLARE_PUBLIC_URL = $publicAddress.GetLeftPart([UriPartial]::Authority)
    $env:APP_BASE_URL = $env:CLOUDFLARE_PUBLIC_URL
    $env:COOKIE_SECURE = 'true'
}
if ($Https) {
    foreach ($file in @('server.pem','server-key.pem')) {
        if (-not (Test-Path -LiteralPath (Join-Path $projectRoot ".tools\certs\$file"))) { throw 'Run scripts/setup-local-https.ps1 first.' }
    }
    $env:APP_BASE_URL = if ($env:HTTPS_BASE_URL) { $env:HTTPS_BASE_URL } else { "https://localhost:$appPort" }
    $env:COOKIE_SECURE = 'true'
}
$jdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Java 21 is required for the local fallback. Use Docker Compose for normal operation.' }
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { throw 'JAVA_HOME must point to an installed JDK 21 directory.' }
$node = (Get-Command node.exe -ErrorAction Stop).Source
# A copied JAR can be older than the source. Gradle skips unchanged work itself.
& .\backend\gradlew.bat -p backend bootJar
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
$jar = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'backend\build\libs') -Filter '*-SNAPSHOT.jar' | Select-Object -First 1
if (-not $jar) { throw 'Backend build did not produce an executable JAR.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'frontend\node_modules\vite\bin\vite.js'))) {
    & npm.cmd ci --prefix frontend
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed' }
}
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.tools') | Out-Null
$ffmpeg = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\ffmpeg') -Filter ffmpeg.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ffmpeg) { $env:FFMPEG_PATH = $ffmpeg.FullName }
foreach ($port in @(8080,$appPort)) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use. Stop the existing process first." } }
$runProfile = if ($Mode -eq 'Google') { 'account-check' } else { 'local' }
if ($Mode -eq 'Google' -and (-not $env:GOOGLE_CLIENT_ID -or -not $env:GOOGLE_CLIENT_SECRET -or -not $env:ALLOWED_EMAILS)) { throw 'Set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and ALLOWED_EMAILS in .env first.' }
$backend = Start-Process -FilePath $javaExe -ArgumentList '-jar', ('"' + $jar.FullName + '"'), ("--spring.profiles.active=" + $runProfile) -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput '.tools\backend.log' -RedirectStandardError '.tools\backend-error.log' -PassThru
$frontend = Start-Process -FilePath $node -ArgumentList 'node_modules/vite/bin/vite.js','--host','0.0.0.0','--port',"$appPort",'--strictPort' -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput '.tools\frontend.log' -RedirectStandardError '.tools\frontend-error.log' -PassThru
@{
    projectRoot = $projectRoot
    backend = @{ id = $backend.Id; executable = $javaExe; startedAtUtc = $backend.StartTime.ToUniversalTime().ToString('o') }
    frontend = @{ id = $frontend.Id; executable = $node; startedAtUtc = $frontend.StartTime.ToUniversalTime().ToString('o') }
} | ConvertTo-Json | Set-Content -LiteralPath '.tools\local-processes.json'
$ready = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch { $backend.Refresh(); if ($backend.HasExited) { throw 'Backend exited; inspect .tools/backend.log and .tools/backend-error.log.' } }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'Startup did not become healthy. Inspect .tools/backend.log.' }
$frontend.Refresh()
if ($frontend.HasExited) { throw 'Frontend exited; inspect .tools/frontend-error.log.' }
$protocol = if ($Https) { 'https' } else { 'http' }
if ($Cloudflare) {
    Write-Host "Cloudflare origin ready: http://localhost:$appPort"
    Write-Host "Sign in using $env:CLOUDFLARE_PUBLIC_URL after scripts/start-cloudflare.ps1 connects the tunnel."
} else {
    Write-Host "Local $Mode mode started: ${protocol}://localhost:$appPort"
    Write-Host "Other devices: ${protocol}://<this-PC-IP>:$appPort (network/firewall must allow access)."
}
Write-Host 'This fallback uses H2, separately from the Compose PostgreSQL database.'
if ($Mode -eq 'Sample') { Write-Host 'Google login is disabled in Sample mode.' }
