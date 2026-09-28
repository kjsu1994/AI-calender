$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolDirectory = Join-Path $projectRoot '.tools\cloudflared'
$cloudflaredExe = Join-Path $toolDirectory 'cloudflared.exe'

if (-not (Test-Path -LiteralPath $cloudflaredExe)) {
    if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -eq 'ARM64') {
        throw 'This downloader requires 64-bit Windows on an Intel/AMD processor.'
    }
    New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
    $downloadPath = Join-Path $toolDirectory 'cloudflared.download'
    $previousTls = [Net.ServicePointManager]::SecurityProtocol
    $previousProgress = $ProgressPreference
    try {
        [Net.ServicePointManager]::SecurityProtocol = $previousTls -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        $headers = @{ 'User-Agent' = 'dayvoice-cloudflared-setup'; 'Accept' = 'application/vnd.github+json' }
        $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/cloudflare/cloudflared/releases/latest' -Headers $headers -TimeoutSec 30
        $asset = $release.assets | Where-Object name -eq 'cloudflared-windows-amd64.exe' | Select-Object -First 1
        if (-not $asset -or $asset.browser_download_url -notmatch '^https://github\.com/cloudflare/cloudflared/releases/download/[^/]+/cloudflared-windows-amd64\.exe$') {
            throw 'The official release did not provide the expected Windows amd64 asset.'
        }
        Write-Host 'Downloading cloudflared from the official Cloudflare GitHub release...'
        Invoke-WebRequest -UseBasicParsing -Uri $asset.browser_download_url -OutFile $downloadPath -TimeoutSec 180
        $actualDigest = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($asset.digest) {
            if ($asset.digest -notmatch '^sha256:([a-fA-F0-9]{64})$') { throw 'The release asset uses an unsupported checksum format.' }
            if ($actualDigest -cne $matches[1].ToLowerInvariant()) { throw 'Cloudflared download checksum did not match the official release.' }
            Write-Host 'Official release SHA-256 checksum verified.'
        } else {
            Write-Host 'The official release supplied no checksum; downloaded over HTTPS from the official asset URL.'
        }
        Move-Item -LiteralPath $downloadPath -Destination $cloudflaredExe
        $metadata = @{ version = $release.tag_name; sha256 = $actualDigest; officialDigestVerified = [bool]$asset.digest }
        [IO.File]::WriteAllText((Join-Path $toolDirectory 'release.json'), ($metadata | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
    } finally {
        [Net.ServicePointManager]::SecurityProtocol = $previousTls
        $ProgressPreference = $previousProgress
        if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath -Force }
    }
}

# --version is local only: this script never connects a tunnel or reads .env.
$versionInfo = New-Object Diagnostics.ProcessStartInfo
$versionInfo.FileName = $cloudflaredExe
$versionInfo.Arguments = '--version'
$versionInfo.UseShellExecute = $false
$versionInfo.CreateNoWindow = $true
$versionInfo.RedirectStandardOutput = $true
$versionInfo.RedirectStandardError = $true
$versionProcess = New-Object Diagnostics.Process
$versionProcess.StartInfo = $versionInfo
try {
    [void]$versionProcess.Start()
    $versionOutput = $versionProcess.StandardOutput.ReadToEndAsync()
    $versionErrors = $versionProcess.StandardError.ReadToEndAsync()
    if (-not $versionProcess.WaitForExit(10000)) {
        $versionProcess.Kill()
        $versionProcess.WaitForExit()
        throw 'Cloudflared version verification timed out.'
    }
    if ($versionProcess.ExitCode -ne 0 -or $versionOutput.Result -notmatch '^cloudflared version [0-9][^\r\n]*') {
        throw 'Cloudflared did not report a valid version. No tunnel was started.'
    }
    Write-Host $matches[0]
    Write-Host 'Cloudflared is ready locally. No Windows service or tunnel was started.'
} finally {
    $versionProcess.Dispose()
}
