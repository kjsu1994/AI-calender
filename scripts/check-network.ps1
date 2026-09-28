param([ValidateRange(1,15)][int]$TimeoutSeconds = 3)

# Read-only diagnostics: does not open ports or change Windows/network settings.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$appPort = 8085
$portSource = 'default'
if (Test-Path -LiteralPath $envPath) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^\s*APP_PORT\s*=(.*)$') {
            $portValue = ($matches[1] -split '#', 2)[0].Trim().Trim([char]34, [char]39)
            $parsedPort = 0
            if ([int]::TryParse($portValue, [ref]$parsedPort) -and $parsedPort -ge 1 -and $parsedPort -le 65535) {
                $appPort = $parsedPort
                $portSource = '.env APP_PORT'
            } else {
                Write-Warning 'APP_PORT is invalid; checking the default port 8085.'
                $appPort = 8085
                $portSource = 'default (invalid APP_PORT)'
            }
        }
    }
}

function Test-AppHealth {
    param([string]$BaseUrl)
    $response = $null
    $reader = $null
    try {
        $request = [Net.HttpWebRequest]::Create("$BaseUrl/api/health")
        $request.Method = 'GET'
        $request.Proxy = $null
        $request.Timeout = $TimeoutSeconds * 1000
        $request.ReadWriteTimeout = $TimeoutSeconds * 1000
        $request.AllowAutoRedirect = $false
        $response = $request.GetResponse()
        $reader = New-Object IO.StreamReader($response.GetResponseStream())
        # Read only a small diagnostic response; never print response bodies.
        $buffer = New-Object char[] 2048
        $readCount = $reader.Read($buffer, 0, $buffer.Length)
        $healthLabel = ''
        try {
            $payload = (-join $buffer[0..([Math]::Max(0, $readCount - 1))]) | ConvertFrom-Json
            if ($payload.status -in @('UP', 'DOWN', 'UNKNOWN', 'OUT_OF_SERVICE')) {
                $healthLabel = "; application status=$($payload.status)"
            }
        } catch { }
        Write-Host ("{0}/api/health -> HTTP {1}{2}" -f $BaseUrl, [int]$response.StatusCode, $healthLabel)
    } catch [Net.WebException] {
        if ($_.Exception.Response) {
            $failedResponse = $_.Exception.Response
            try {
                Write-Host ("{0}/api/health -> HTTP {1}" -f $BaseUrl, [int]$failedResponse.StatusCode)
            } finally {
                $failedResponse.Close()
            }
        } else {
            Write-Host ("{0}/api/health -> failed ({1}; timeout limit {2}s)" -f $BaseUrl, $_.Exception.Status, $TimeoutSeconds)
        }
    } catch {
        Write-Host ("{0}/api/health -> failed ({1})" -f $BaseUrl, $_.Exception.GetType().Name)
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Close() }
    }
}

Write-Host "Application port: $appPort ($portSource)"
Write-Host 'No keys or tokens are displayed.'
Write-Host ''
Write-Host 'Listeners on the application port:'
try {
    $listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq $appPort)
    if ($listeners.Count -eq 0) {
        Write-Host 'No listener found. Start the service before testing from another device.'
    } else {
        $listeners | Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table -AutoSize | Out-Host
        Write-Host '0.0.0.0 accepts IPv4 on all interfaces; 127.0.0.1 accepts this PC only.'
        Write-Host 'An IPv6 :: listener may also accept IPv4 depending on the server configuration.'
    }
} catch {
    Write-Host 'Listener status is unavailable with the current Windows permissions/services.'
}

Write-Host ''
Write-Host 'Non-loopback IPv4 interface addresses (including virtual/VPN interfaces, if present):'
$interfaceAddresses = @()
try {
    $interfaceAddresses = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -ne '0.0.0.0' -and $_.IPAddress -notlike '127.*' -and $_.AddressState -eq 'Preferred' } |
        Sort-Object InterfaceIndex, IPAddress)
    if ($interfaceAddresses.Count -eq 0) {
        Write-Host 'No preferred non-loopback IPv4 address found.'
    } else {
        $interfaceAddresses | Select-Object InterfaceAlias, IPAddress, PrefixLength,
            @{Name='URL'; Expression={ 'http://{0}:{1}' -f $_.IPAddress, $appPort }} |
            Format-Table -AutoSize | Out-Host
    }
} catch {
    Write-Host 'Interface address lookup is unavailable with the current Windows permissions/services.'
}

Write-Host ''
Write-Host "Local health requests (direct connection, ${TimeoutSeconds}s connection/read timeout):"
Test-AppHealth -BaseUrl "http://localhost:$appPort"
foreach ($address in @($interfaceAddresses.IPAddress | Select-Object -Unique)) {
    if ($address) { Test-AppHealth -BaseUrl ('http://{0}:{1}' -f $address, $appPort) }
}

Write-Host ''
Write-Host 'Windows connection profiles:'
try {
    $profiles = @(Get-NetConnectionProfile -ErrorAction Stop)
    if ($profiles.Count -eq 0) { Write-Host 'No connection profile found.' }
    else { $profiles | Select-Object InterfaceAlias, NetworkCategory, IPv4Connectivity | Format-Table -AutoSize | Out-Host }
} catch {
    Write-Host 'Connection profiles are unavailable with the current Windows permissions/services.'
}

Write-Host 'Windows firewall profile status:'
try {
    Get-NetFirewallProfile -ErrorAction Stop |
        Select-Object Name, Enabled, DefaultInboundAction, DefaultOutboundAction |
        Format-Table -AutoSize | Out-Host
} catch {
    Write-Host 'Firewall status is unavailable with the current Windows permissions/services.'
}

Write-Host 'Successful requests above prove reachability from this PC only.'
Write-Host 'Test the matching interface URL from the other device separately. Routing, firewall rules,'
Write-Host 'VPNs, or network/client isolation may still block access. An interface IP does not establish'
Write-Host 'that devices share a subnet or Wi-Fi, or that the address is private.'
Write-Host 'This script does not change firewall rules or any network settings.'
