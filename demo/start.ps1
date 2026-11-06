# Starts the local demo without Docker: WireMock (the demo downstream APIs) on :8089 from its standalone jar, and
# apiorch (`local` profile) on :8080 / :9091 with a file-based H2 database in data/h2, so pipelines, endpoints and runs
# survive a restart. Stop both with demo/stop.ps1. Windows PowerShell 5.1 or PowerShell 7; needs JDK 17.
#
#   demo/start.ps1           start, building the jar first if there is none
#   demo/start.ps1 -Build    rebuild the jar first (skips tests)
#   demo/start.ps1 -Reset    delete the H2 database first, for a fresh walkthrough
param([switch]$Build, [switch]$Reset)
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$work = Join-Path $root 'target\demo'
$dataDir = Join-Path $root 'data\h2'
$appPort = 8080; $mgmtPort = 9091; $wiremockPort = 8089

function Find-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return (Join-Path $env:JAVA_HOME 'bin\java.exe')
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'JDK 17 not found: set JAVA_HOME or put java on PATH.'
}

function Test-Running($name, $marker) {  # true when the PID file names a live process started by this script
    $pidFile = Join-Path $work "$name.pid"
    if (-not (Test-Path $pidFile)) { return $false }
    $id = Get-Content $pidFile
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue
    if ($proc -and $proc.CommandLine -like "*$marker*") { return $true }
    Remove-Item $pidFile
    return $false
}

function Assert-PortFree($port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        $owner = (Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue).ProcessName
        throw "Port $port is already in use by $owner (PID $($listener.OwningProcess)). Stop it first."
    }
}

function Start-Java($name, [string[]]$javaArgs) {
    # one quoted command line, so paths with spaces survive Windows PowerShell 5.1's argument joining
    $line = ($javaArgs | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $proc = Start-Process -FilePath $java -ArgumentList $line -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $work "$name.log") -RedirectStandardError (Join-Path $work "$name.err.log")
    Set-Content -Path (Join-Path $work "$name.pid") -Value $proc.Id
    return $proc
}

function Wait-Http($url, $proc, $name, $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { throw "$name exited during startup; see target\demo\$name.log and $name.err.log" }
        try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 $url | Out-Null; return } catch { Start-Sleep -Seconds 1 }
    }
    throw "$name did not become ready within $seconds s; see target\demo\$name.log"
}

$java = Find-Java
New-Item -ItemType Directory -Force $work | Out-Null

if ((Test-Running 'apiorch' 'apiorch.jar') -or (Test-Running 'wiremock' 'wiremock-standalone')) {
    throw 'The demo is already running. Stop it with demo/stop.ps1 first.'
}
Assert-PortFree $appPort; Assert-PortFree $mgmtPort; Assert-PortFree $wiremockPort

# the app jar: built on demand, then copied, so a running demo never locks the jar the build repackages
$jar = Get-ChildItem (Join-Path $root 'target') -Filter 'apiorch-service-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if ($Build -or -not $jar) {
    Write-Host 'Building apiorch (tests skipped)...'
    & (Join-Path $root 'mvnw.cmd') -B -q -f (Join-Path $root 'pom.xml') package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'The build failed.' }
    $jar = Get-ChildItem (Join-Path $root 'target') -Filter 'apiorch-service-*.jar' | Select-Object -First 1
}
Copy-Item $jar.FullName (Join-Path $work 'apiorch.jar') -Force

# WireMock: the standalone jar the build already resolved as a test dependency
$wiremockVersion = ([regex]'<wiremock.version>([^<]+)<').Match((Get-Content (Join-Path $root 'pom.xml') -Raw)).Groups[1].Value
$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$wiremockJar = Join-Path $m2 "org\wiremock\wiremock-standalone\$wiremockVersion\wiremock-standalone-$wiremockVersion.jar"
if (-not (Test-Path $wiremockJar)) {
    Write-Host "Fetching wiremock-standalone $wiremockVersion..."
    & (Join-Path $root 'mvnw.cmd') -B -q -f (Join-Path $root 'pom.xml') dependency:get `
        "-Dartifact=org.wiremock:wiremock-standalone:$wiremockVersion"
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch wiremock-standalone.' }
}

if ($Reset -and (Test-Path $dataDir)) {
    Remove-Item -Recurse -Force $dataDir
    Write-Host 'Deleted the H2 database.'
}
New-Item -ItemType Directory -Force $dataDir | Out-Null
$h2Path = (Join-Path $dataDir 'apiorch') -replace '\\', '/'
# the same SQL Server mode as the in-memory `local` database; AUTO_SERVER lets a SQL client open the file while it runs
$h2Url = "jdbc:h2:file:$h2Path;MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE"

Write-Host "Starting WireMock on :$wiremockPort..."
$wiremock = Start-Java 'wiremock' @('-jar', $wiremockJar, '--port', "$wiremockPort",
    '--root-dir', (Join-Path $root 'demo\wiremock'), '--global-response-templating', '--disable-banner')
Wait-Http "http://localhost:$wiremockPort/__admin/health" $wiremock 'wiremock' 60

Write-Host "Starting apiorch on :$appPort (H2 file $dataDir)..."
$app = Start-Java 'apiorch' @('-jar', (Join-Path $work 'apiorch.jar'),
    '--spring.profiles.active=local',
    "--spring.datasource.url=$h2Url",
    # demo/stop.ps1 shuts the app down gracefully over local JMX (the HTTP shutdown endpoint stays denied)
    '--management.endpoint.shutdown.enabled=true', '--spring.jmx.enabled=true',
    '--management.endpoints.jmx.exposure.include=shutdown')
Wait-Http "http://localhost:$mgmtPort/actuator/health/readiness" $app 'apiorch' 180

Write-Host ''
Write-Host 'apiorch is ready.' -ForegroundColor Green
Write-Host "  Swagger UI    http://localhost:$appPort/swagger-ui/index.html"
Write-Host "  Start a run   POST http://localhost:$appPort/api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync"
Write-Host "  Walkthrough   demo/walkthrough.ps1 (on a fresh database: demo/start.ps1 -Reset)"
Write-Host "  Stub APIs     http://localhost:$wiremockPort/__admin/mappings"
Write-Host "  Logs          target\demo\apiorch.log, target\demo\wiremock.log"
Write-Host '  Stop          demo/stop.ps1'
