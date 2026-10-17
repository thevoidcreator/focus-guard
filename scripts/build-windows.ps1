$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../windows')
$env:FOCUSGUARD_REPO = (Resolve-Path '..').Path
dotnet restore FocusGuard.Tests --locked-mode
if ($LASTEXITCODE) { throw 'Restore failed' }
dotnet test FocusGuard.Tests -c Release --no-restore --logger 'trx;LogFileName=windows-tests.trx'
if ($LASTEXITCODE) { throw 'Tests failed' }
dotnet publish FocusGuard.Console -c Release -r win-x64 --self-contained true -o ../dist/windows-win-x64 -p:EnableWindowsTargeting=true -p:RestoreLockedMode=true
if ($LASTEXITCODE) { throw 'Publish failed' }
Compress-Archive -Path ../dist/windows-win-x64/* -DestinationPath ../dist/FocusGuard-Windows-win-x64.zip -Force
Get-FileHash ../dist/FocusGuard-Windows-win-x64.zip -Algorithm SHA256 | Format-List | Out-File ../dist/Windows-SHA256.txt
