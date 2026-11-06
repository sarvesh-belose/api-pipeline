# Stops the local demo started by demo/start.ps1: apiorch gracefully (in-flight steps finish, H2 closes its file), then
# WireMock. The H2 database in data/h2 is kept; `demo/start.ps1 -Reset` deletes it.
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $root 'target\demo'

function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'JDK 17 not found: set JAVA_HOME or put java on PATH.'
}

function Get-DemoProcess($name, $marker) {  # the live process the PID file names, or $null; never some other process
    $pidFile = Join-Path $work "$name.pid"
    if (-not (Test-Path $pidFile)) { return $null }
    $id = Get-Content $pidFile
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
    if ($proc -and $proc.CommandLine -like "*$marker*") { return Get-Process -Id $id }
    Remove-Item $pidFile
    return $null
}

function Wait-Exit($proc, $seconds) {
    try { return $proc.WaitForExit($seconds * 1000) } catch { return $true }
}

$app = Get-DemoProcess 'apiorch' 'apiorch.jar'
if ($app) {
    Write-Host "Stopping apiorch (PID $($app.Id)) gracefully..."
    & (Find-Java) (Join-Path $root 'tools\lb-sim\GracefulStop.java') $app.Id | Out-Null
    # graceful shutdown allows in-flight steps up to 20 s and each phase up to 30 s
    if (-not (Wait-Exit $app 45)) {
        Write-Host 'apiorch did not stop in time; killing it.' -ForegroundColor Yellow
        Stop-Process -Id $app.Id -Force
    }
    Remove-Item (Join-Path $work 'apiorch.pid') -ErrorAction SilentlyContinue
    Write-Host 'apiorch stopped.'
} else {
    Write-Host 'apiorch is not running.'
}

$wiremock = Get-DemoProcess 'wiremock' 'wiremock-standalone'
if ($wiremock) {
    Write-Host "Stopping WireMock (PID $($wiremock.Id))..."
    try { Invoke-RestMethod -Method Post -TimeoutSec 5 'http://localhost:8089/__admin/shutdown' | Out-Null } catch { }
    if (-not (Wait-Exit $wiremock 10)) { Stop-Process -Id $wiremock.Id -Force }
    Remove-Item (Join-Path $work 'wiremock.pid') -ErrorAction SilentlyContinue
    Write-Host 'WireMock stopped.'
} else {
    Write-Host 'WireMock is not running.'
}
