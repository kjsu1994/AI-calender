param([switch]$CheckConfiguration)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$toolDirectory = Join-Path $projectRoot '.tools\cloudflared'
$cloudflaredExe = Join-Path $toolDirectory 'cloudflared.exe'
$pidFile = Join-Path $projectRoot '.tools\cloudflare-process.json'
$configuration = @{}
$problems = New-Object 'Collections.Generic.List[string]'
foreach ($name in @('CLOUDFLARE_TUNNEL_TOKEN', 'CLOUDFLARE_PUBLIC_URL', 'APP_PORT')) {
    $configuration[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

# Read only the required values. Do not import or print the rest of .env.
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -notmatch '^\s*(?:export\s+)?(CLOUDFLARE_TUNNEL_TOKEN|CLOUDFLARE_PUBLIC_URL|APP_PORT)\s*=\s*(.*)$') { continue }
        $name = $matches[1]
        $rawValue = $matches[2].Trim()
        if ($rawValue.StartsWith('"')) {
            if ($rawValue -notmatch '^"([^"]*)"\s*(?:#.*)?$') { $problems.Add("Invalid quoted value for $name."); continue }
            $configuration[$name] = $matches[1]
        } elseif ($rawValue.StartsWith("'")) {
            if ($rawValue -notmatch "^'([^']*)'\s*(?:#.*)?$") { $problems.Add("Invalid quoted value for $name."); continue }
            $configuration[$name] = $matches[1]
        } else {
            $configuration[$name] = ($rawValue -split '\s+#', 2)[0].Trim()
        }
    }
}
$token = [string]$configuration['CLOUDFLARE_TUNNEL_TOKEN']
if ([string]::IsNullOrWhiteSpace($token)) { $problems.Add('Missing CLOUDFLARE_TUNNEL_TOKEN in .env.') }
elseif ($token -match '\s') { $problems.Add('CLOUDFLARE_TUNNEL_TOKEN must contain only the token, not an installation command.') }

$publicUri = $null
$publicValue = [string]$configuration['CLOUDFLARE_PUBLIC_URL']
if ([string]::IsNullOrWhiteSpace($publicValue)) {
    $problems.Add('Missing CLOUDFLARE_PUBLIC_URL in .env (expected https://ctfmon.shop).')
} elseif (-not [Uri]::TryCreate($publicValue, [UriKind]::Absolute, [ref]$publicUri) -or
    $publicUri.Scheme -ne 'https' -or $publicUri.HostNameType -ne [UriHostNameType]::Dns -or
    $publicUri.IsLoopback -or $publicUri.Host.EndsWith('.localhost') -or -not $publicUri.Host.Contains('.') -or $publicUri.Port -ne 443 -or
    $publicUri.UserInfo -or $publicUri.Query -or $publicUri.Fragment -or $publicUri.AbsolutePath -ne '/') {
    $problems.Add('CLOUDFLARE_PUBLIC_URL must be an HTTPS domain origin without credentials, a path, query, fragment, or custom port.')
}
$appPort = 8085
if ($configuration['APP_PORT'] -and (-not [int]::TryParse($configuration['APP_PORT'], [ref]$appPort) -or $appPort -lt 1 -or $appPort -gt 65535)) {
    $problems.Add('APP_PORT must be between 1 and 65535.')
}
if ($problems.Count -gt 0) {
    foreach ($problem in $problems) { Write-Host $problem }
    if ($CheckConfiguration) { exit 1 }
    throw 'Cloudflare configuration is incomplete. Update the indicated .env values; no tunnel was started.'
}
$publicUrl = $publicUri.GetLeftPart([UriPartial]::Authority)
Write-Host "Public URL: $publicUrl"
if ($CheckConfiguration) {
    Write-Host 'Configuration format is valid and a token is present. Token validity was not checked.'
    Write-Host 'No network requests, downloads, process launches, or settings changes were made.'
    exit 0
}

# Prevent accidental duplicate launches and PID-reuse mistakes.
if (Test-Path -LiteralPath $pidFile) {
    try { $record = [IO.File]::ReadAllText($pidFile) | ConvertFrom-Json } catch { throw 'Invalid cloudflare-process.json; no process was started.' }
    $recordedId = 0
    if (-not [int]::TryParse([string]$record.pid, [ref]$recordedId) -or $recordedId -le 0 -or
        -not [string]::Equals([string]$record.executable, $cloudflaredExe, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The recorded process does not belong to this Cloudflare launcher. No process was started.'
    }
    $existing = Get-Process -Id $recordedId -ErrorAction SilentlyContinue
    if ($existing) {
        $recordedStart = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string]$record.startedAtUtc, [ref]$recordedStart) -or
            -not [string]::Equals($existing.Path, $cloudflaredExe, [StringComparison]::OrdinalIgnoreCase) -or
            $existing.StartTime.ToUniversalTime().Ticks -ne $recordedStart.UtcDateTime.Ticks) {
            throw 'The recorded PID belongs to a different process. No process was started or stopped.'
        }
        Write-Host 'The recorded Cloudflare connector is already running. Check its status in the Cloudflare dashboard.'
        exit 0
    }
    Remove-Item -LiteralPath $pidFile
}

function Get-LocalJson([string]$Path) {
    $response = $null
    $reader = $null
    try {
        $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$appPort$Path")
        $request.Proxy = $null
        $request.Timeout = 5000
        $request.ReadWriteTimeout = 5000
        $request.AllowAutoRedirect = $false
        $response = $request.GetResponse()
        if ([int]$response.StatusCode -ne 200) { throw 'Unexpected local response.' }
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        return ($reader.ReadToEnd() | ConvertFrom-Json)
    } catch {
        throw 'The local HTTP application is unavailable. Start scripts/start-local.ps1 -Mode Google -Cloudflare first.'
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Close() }
    }
}
$health = Get-LocalJson '/api/health'
if ($health.status -ne 'UP') { throw 'Local application health is not UP; no tunnel was started.' }
$session = Get-LocalJson '/api/session'
if ([string]::IsNullOrWhiteSpace([string]$session.publicBaseUrl) -or ([string]$session.publicBaseUrl).TrimEnd('/') -ine $publicUrl) {
    throw 'The application public URL does not match CLOUDFLARE_PUBLIC_URL. Restart scripts/start-local.ps1 -Mode Google -Cloudflare first.'
}
if ($session.demoMode) { throw 'Start the local application in Google mode before exposing it with this connector.' }

& (Join-Path $PSScriptRoot 'setup-cloudflare.ps1')
Write-Host "In the tunnel dashboard, route the public hostname to service type HTTP, URL localhost:$appPort."
$previousToken = [Environment]::GetEnvironmentVariable('TUNNEL_TOKEN', 'Process')
$connector = $null
try {
    # The token is deliberately absent from command-line arguments and PID state.
    try {
        [Environment]::SetEnvironmentVariable('TUNNEL_TOKEN', $token, 'Process')
        $connector = Start-Process -FilePath $cloudflaredExe -ArgumentList 'tunnel','--no-autoupdate','run' `
            -WorkingDirectory $toolDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $projectRoot '.tools\cloudflare.log') `
            -RedirectStandardError (Join-Path $projectRoot '.tools\cloudflare-error.log')
    } finally {
        [Environment]::SetEnvironmentVariable('TUNNEL_TOKEN', $previousToken, 'Process')
    }
    $record = @{ pid = $connector.Id; executable = $cloudflaredExe; startedAtUtc = $connector.StartTime.ToUniversalTime().ToString('o') }
    [IO.File]::WriteAllText($pidFile, ($record | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
    if ($connector.WaitForExit(5000)) { throw 'Cloudflared exited during startup. Review the local cloudflare logs privately; do not share unredacted logs or tokens.' }
    Write-Host 'Cloudflare connector process is running. Confirm Healthy in the dashboard and open the public URL.'
    Write-Host 'Process startup does not verify the tunnel token, DNS route, or public reachability.'
    Write-Host 'Local diagnostic logs: .tools/cloudflare.log and .tools/cloudflare-error.log (not printed).'
} catch {
    if ($connector) {
        if (-not $connector.HasExited) { $connector.Kill(); $connector.WaitForExit() }
        if (Test-Path -LiteralPath $pidFile) { Remove-Item -LiteralPath $pidFile }
    }
    throw
} finally {
    $token = $null
    $configuration['CLOUDFLARE_TUNNEL_TOKEN'] = $null
}
