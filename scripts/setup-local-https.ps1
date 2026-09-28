param([string[]]$IpAddress)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$certDirectory = Join-Path $projectRoot '.tools\certs'
$caDirectory = Join-Path $certDirectory 'ca'
$toolDirectory = Join-Path $projectRoot '.tools\mkcert'
$mkcertExe = Join-Path $toolDirectory 'mkcert.exe'
$serverCertificate = Join-Path $certDirectory 'server.pem'
$serverKey = Join-Path $certDirectory 'server-key.pem'
$publicCaCertificate = Join-Path $certDirectory 'dayvoice-local-ca.crt'

# Only an explicit invocation of this script creates certificates. It never
# installs a root certificate, opens a firewall port, or restarts the service.
$certificateNames = @('localhost', '127.0.0.1', '::1')
if ($PSBoundParameters.ContainsKey('IpAddress')) {
    if (-not $IpAddress -or $IpAddress.Count -eq 0) { throw 'Provide at least one IPv4 address with -IpAddress.' }
    foreach ($candidate in $IpAddress) {
        $parsedAddress = $null
        if (-not [Net.IPAddress]::TryParse($candidate, [ref]$parsedAddress) -or
            $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
            $parsedAddress.Equals([Net.IPAddress]::Any) -or
            $parsedAddress.Equals([Net.IPAddress]::Broadcast)) {
            throw 'Each -IpAddress value must be a concrete IPv4 address.'
        }
        $certificateNames += $parsedAddress.ToString()
    }
} else {
    try {
        $connectedInterfaces = @(Get-NetIPInterface -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object ConnectionState -eq 'Connected' | Select-Object -ExpandProperty InterfaceIndex)
        $certificateNames += @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -ne '0.0.0.0' -and
                $_.AddressState -eq 'Preferred' -and $_.InterfaceIndex -in $connectedInterfaces } |
            Select-Object -ExpandProperty IPAddress)
    } catch {
        throw 'Could not detect active interface addresses. Re-run with -IpAddress followed by your PC IPv4 address.'
    }
}
$certificateNames = @($certificateNames | Select-Object -Unique)

New-Item -ItemType Directory -Path $certDirectory, $caDirectory, $toolDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $mkcertExe)) {
    # The upstream README documents dl.filippo.io as its official binary host:
    # https://github.com/FiloSottile/mkcert#installation
    if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
        throw 'This downloader requires 64-bit Windows on an Intel/AMD processor.'
    }
    $downloadPath = Join-Path $toolDirectory 'mkcert.download'
    $previousTls = [Net.ServicePointManager]::SecurityProtocol
    $previousProgress = $ProgressPreference
    try {
        [Net.ServicePointManager]::SecurityProtocol = $previousTls -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Write-Host 'Downloading mkcert from the official distribution host...'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.filippo.io/mkcert/latest?for=windows/amd64' `
            -OutFile $downloadPath -TimeoutSec 120
        Move-Item -LiteralPath $downloadPath -Destination $mkcertExe -Force
    } finally {
        [Net.ServicePointManager]::SecurityProtocol = $previousTls
        $ProgressPreference = $previousProgress
        if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath -Force }
    }
}

# Keep this project's CA separate from any existing user CA and restore CAROOT.
$previousCaRoot = [Environment]::GetEnvironmentVariable('CAROOT', 'Process')
try {
    [Environment]::SetEnvironmentVariable('CAROOT', $caDirectory, 'Process')
    $mkcertArguments = @('-cert-file', $serverCertificate, '-key-file', $serverKey) + $certificateNames
    & $mkcertExe @mkcertArguments
    if ($LASTEXITCODE -ne 0) { throw 'mkcert failed to generate the local HTTPS certificate.' }
} finally {
    [Environment]::SetEnvironmentVariable('CAROOT', $previousCaRoot, 'Process')
}

if (-not (Test-Path -LiteralPath $serverCertificate) -or -not (Test-Path -LiteralPath $serverKey)) {
    throw 'The server certificate or key was not generated.'
}
# Export ONLY the public CA certificate. Never copy or serve rootCA-key.pem.
Copy-Item -LiteralPath (Join-Path $caDirectory 'rootCA.pem') -Destination $publicCaCertificate -Force

Write-Host ''
Write-Host ('Certificate names: ' + ($certificateNames -join ', '))
Write-Host "Server certificate: $serverCertificate"
Write-Host "Public CA certificate for manual phone installation: $publicCaCertificate"
Write-Host 'No certificate was added to Windows trust, and the running service was not changed.'
Write-Host 'To trust this project CA for your Windows account, review and run this command yourself:'
$escapedPublicPath = $publicCaCertificate.Replace("'", "''")
Write-Host ("Import-Certificate -FilePath '{0}' -CertStoreLocation Cert:\CurrentUser\Root" -f $escapedPublicPath)
Write-Host 'On a phone, install only dayvoice-local-ca.crt and enable trust in device settings.'
Write-Host 'Keep server-key.pem and ca/rootCA-key.pem on this PC; do not share them.'
Write-Host 'Re-run this script when the PC interface IP changes, then restart HTTPS to load the new certificate.'
