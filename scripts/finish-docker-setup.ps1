# Run in an Administrator PowerShell if WSL is not installed.
$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script in an Administrator PowerShell.' }
wsl --install --no-distribution
if ($LASTEXITCODE -ne 0) { throw 'WSL installation did not complete. Follow https://learn.microsoft.com/windows/wsl/install' }
Write-Host 'If Windows requests a restart, restart manually, then open Docker Desktop and select Linux containers.'
Write-Host 'No automatic restart has been requested by this script.'
