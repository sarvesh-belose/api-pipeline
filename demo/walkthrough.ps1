# US-8.1 slice A: the reorder demo, scripted with pauses. Start WireMock and the app first (see demo/README.md).
# Windows PowerShell 5.1 or PowerShell 7.
$ErrorActionPreference = 'Stop'
$app = 'http://localhost:8080'
$wiremock = 'http://localhost:8089'
$seed = Join-Path $PSScriptRoot '..\src\main\resources\seed'
$customer = @{ name = 'Asha Rao'; pan = 'ABCDE1234F' } | ConvertTo-Json

function Step($title) {
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
    Read-Host 'Press Enter' | Out-Null
}

function Run {
    Invoke-RestMethod -Method Post -Uri "$app/api/v1/pipelines/DEMO_ONBOARD/runs?mode=sync" `
        -ContentType 'application/json' -Body $customer
}

function Draft($file) {
    $definition = Get-Content (Join-Path $seed $file) -Raw | ConvertFrom-Json
    $body = @{ definition = $definition } | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Method Put -Uri "$app/api/v1/admin/pipelines/DEMO_ONBOARD/draft" `
        -ContentType 'application/json' -Body $body | Out-Null
}

function Publish($comment) {
    $body = @{ comment = $comment } | ConvertTo-Json
    try {
        $res = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$app/api/v1/admin/pipelines/DEMO_ONBOARD/publish" `
            -ContentType 'application/json' -Body $body
        return "$($res.StatusCode) $($res.Content)"
    } catch {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        return "$([int]$_.Exception.Response.StatusCode) $($reader.ReadToEnd())"
    }
}

function Kyc($state) {
    Invoke-RestMethod -Method Put -Uri "$wiremock/__admin/scenarios/kyc/state" -ContentType 'application/json' `
        -Body (@{ state = $state } | ConvertTo-Json) | Out-Null
}

function Calls($runId) {
    (Invoke-RestMethod "$app/api/v1/runs/$runId/timeline").calls |
        Select-Object seq, type, stepId, attempt, url, httpStatus, durationMs, outcome | Format-Table -AutoSize
}

Step '1. Run v1: api1 -> api2 -> api3 -> api5'
$run = Run
$run | Select-Object runId, version, status | Format-List
$run.output | Format-List

Step '2. Its timeline: the inbound request, four calls in order, the response'
Calls $run.runId

Step '3. Publish v2 (api1 -> api2 -> api5 -> api3) and run again, with no restart'
Draft 'DEMO_ONBOARD.v2-reordered.json'
Publish 'business reorder: notify before scoring'
$run = Run
"version $($run.version)"
Calls $run.runId

Step '4. A reorder that breaks a data dependency is refused'
Draft 'DEMO_ONBOARD.invalid-reorder.json'
Publish 'broken reorder'

Step '5. KYC flaky (503, 503, 200): the run still completes, with three attempts on api2'
Kyc 'flaky'
$run = Run
Calls $run.runId

Step '6. KYC down: after five failures the circuit opens and runs fail fast'
Kyc 'down'
1..6 | ForEach-Object { try { Run | Out-Null } catch { } }
Invoke-RestMethod "$app/api/v1/admin/endpoints/kyc-svc/circuit" | Format-List
Kyc 'Started'
Invoke-RestMethod -Method Post -Uri "$app/api/v1/admin/endpoints/kyc-svc/circuit/reset" `
    -ContentType 'application/json' -Body (@{ reason = 'KYC back' } | ConvertTo-Json) | Out-Null
Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
