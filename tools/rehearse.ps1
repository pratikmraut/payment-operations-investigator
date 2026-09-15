param([string]$Python = 'python', [string]$AcceptanceReport = 'docs/validation/acceptance-clean-compose.json')
$ErrorActionPreference = 'Stop'
$poiProject = Split-Path -Parent $PSScriptRoot
$poiRunName = 'poi-rehearsal-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
if ($poiRunName -notmatch '^poi-rehearsal-[0-9a-f]{10}$') { throw 'Invalid generated rehearsal scope.' }
$poiComposeArgs = @('compose', '-f', 'compose.yaml', '-f', 'infra/compose.rehearsal.yaml', '-p', $poiRunName)
$poiLogPath = Join-Path $poiProject ('runtime/' + $poiRunName)
New-Item -ItemType Directory -Path $poiLogPath -Force | Out-Null
$poiManifestPath = Join-Path $poiLogPath 'manifest.json'
$poiTimer = [Diagnostics.Stopwatch]::StartNew()
$poiTestsPassed = $false
$poiManifest = [ordered]@{
    runId = $poiRunName
    project = $poiRunName
    startedAt = [DateTime]::UtcNow.ToString('o')
    status = 'failed'
    stage = 'initializing'
    composeFiles = @('compose.yaml', 'infra/compose.rehearsal.yaml')
    baseUrl = 'http://127.0.0.1:15178'
    checks = @()
    timings = @()
    errors = @()
    logErrors = @()
    acceptance = [ordered]@{
        status = 'not_run'
        defaultReport = $AcceptanceReport
        capturedReport = $null
    }
    cleanup = [ordered]@{
        status = 'not_attempted'
        errors = @()
        remainingContainers = @()
        remainingVolumes = @()
    }
    limitations = @(
        'Fresh built source and fresh data volumes; not a Git-clone or model-inference test.'
        'Socket timeout settings bound idle waits, not a hard total wall-clock deadline.'
    )
}

function Save-PoiManifest {
    [IO.File]::WriteAllText(
        $poiManifestPath,
        ($poiManifest | ConvertTo-Json -Depth 12),
        [Text.UTF8Encoding]::new($false)
    )
}

function Invoke-PoiCompose {
    param([string[]]$Arguments)
    $poiOutput = & docker @poiComposeArgs @Arguments 2>&1
    $poiExit = $LASTEXITCODE
    return [pscustomobject]@{ exitCode = $poiExit; output = ($poiOutput | Out-String) }
}

function Invoke-PoiTimed {
    param([string]$Name, [scriptblock]$Operation)
    $poiStepTimer = [Diagnostics.Stopwatch]::StartNew()
    $poiTiming = [ordered]@{ operation = $Name; startedAt = [DateTime]::UtcNow.ToString('o'); status = 'failed' }
    try {
        & $Operation
        $poiTiming.status = 'passed'
    } catch {
        $poiTiming.error = $_.Exception.Message
        throw
    } finally {
        $poiTiming.elapsedSeconds = [Math]::Round($poiStepTimer.Elapsed.TotalSeconds, 3)
        $poiManifest.timings += $poiTiming
    }
}

Save-PoiManifest
Push-Location -LiteralPath $poiProject
try {
    $poiManifest.stage = 'configuration'
    Save-PoiManifest
    Invoke-PoiTimed -Name 'configuration' -Operation {
        $poiResult = Invoke-PoiCompose -Arguments @('config', '--quiet')
        if ($poiResult.exitCode -ne 0) { throw ('Rehearsal Compose configuration failed: ' + $poiResult.output) }
    }
    $poiManifest.stage = 'startup'
    Save-PoiManifest
    Invoke-PoiTimed -Name 'startup' -Operation {
        $poiResult = Invoke-PoiCompose -Arguments @('up', '-d', '--build', '--wait', '--wait-timeout', '180', 'postgres', 'investigator', 'api', 'web')
        if ($poiResult.exitCode -ne 0) { throw ('Fresh-volume rehearsal startup failed: ' + $poiResult.output) }
    }
    $poiManifest.stage = 'readiness'
    Save-PoiManifest
    Invoke-PoiTimed -Name 'proxy_readiness' -Operation {
        $poiDeadline = [DateTime]::UtcNow.AddSeconds(180)
        $poiReady = $false
        do {
            $poiRemaining = ($poiDeadline - [DateTime]::UtcNow).TotalSeconds
            if ($poiRemaining -le 0) { break }
            try {
                $poiTimeout = [int][Math]::Max(1, [Math]::Min(5, [Math]::Floor($poiRemaining)))
                $poiHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:15178/api/health' -TimeoutSec $poiTimeout
                $poiReady = $poiHealth.status -eq 'UP' -and [DateTime]::UtcNow -le $poiDeadline
            } catch { $poiReady = $false }
            $poiRemaining = ($poiDeadline - [DateTime]::UtcNow).TotalMilliseconds
            if (-not $poiReady -and $poiRemaining -gt 0) {
                Start-Sleep -Milliseconds ([int][Math]::Min(2000, $poiRemaining))
            }
        } while (-not $poiReady -and [DateTime]::UtcNow -lt $poiDeadline)
        if (-not $poiReady) { throw 'Rehearsal API proxy did not become ready.' }
        $poiManifest.checks += 'API proxy health is UP'
    }
    Invoke-PoiTimed -Name 'production_html' -Operation {
        $poiHtml = Invoke-WebRequest -Uri 'http://127.0.0.1:15178/' -UseBasicParsing -TimeoutSec 5
        if ($poiHtml.Content -notmatch '/assets/' -or $poiHtml.Content -notmatch 'id="root"') {
            throw 'Rehearsal nginx did not serve the production React entry point.'
        }
        $poiManifest.checks += 'Production React entry point is served'
    }
    $poiManifest.stage = 'acceptance'
    $poiManifest.acceptance.status = 'failed'
    Save-PoiManifest
    Invoke-PoiTimed -Name 'http_acceptance' -Operation {
        & $Python tools/acceptance.py --base-url http://127.0.0.1:15178 --mode replay --report $AcceptanceReport
        if ($LASTEXITCODE -ne 0) { throw 'Fresh-volume HTTP acceptance failed; any older default report is not evidence for this run.' }
        $poiAcceptancePath = Join-Path $poiProject $poiManifest.acceptance.defaultReport
        $poiAcceptance = Get-Content -LiteralPath $poiAcceptancePath -Raw | ConvertFrom-Json
        if ($poiAcceptance.status -ne 'passed' -or [DateTimeOffset]::Parse($poiAcceptance.timestamp) -lt [DateTimeOffset]::Parse($poiManifest.startedAt)) {
            throw 'Acceptance report is not a passing result from this run.'
        }
        $poiCaptured = Join-Path $poiLogPath 'acceptance.json'
        Copy-Item -LiteralPath $poiAcceptancePath -Destination $poiCaptured
        $poiManifest.acceptance.status = 'passed'
        $poiManifest.acceptance.capturedReport = $poiCaptured
        $poiManifest.acceptance.sha256 = (Get-FileHash -LiteralPath $poiCaptured -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $poiTestsPassed = $true
} catch {
    $poiManifest.failureStage = $poiManifest.stage
    $poiManifest.errors += $_.Exception.Message
} finally {
    try {
        # Log capture must never prevent recovery, including file-write failures.
        foreach ($poiLog in @(
            @{ arguments = @('ps', '--all'); filename = 'containers.txt' },
            @{ arguments = @('logs', '--no-color', '--timestamps'); filename = 'compose.log' }
        )) {
            try {
                $poiResult = Invoke-PoiCompose -Arguments $poiLog.arguments
                $poiResult.output | Out-File -LiteralPath (Join-Path $poiLogPath $poiLog.filename) -Encoding utf8
                if ($poiResult.exitCode -ne 0) { throw ('Log capture failed: ' + $poiLog.filename) }
            } catch { $poiManifest.logErrors += $_.Exception.Message }
        }
    } finally {
        try {
            $poiManifest.stage = 'cleanup'
            $poiManifest.cleanup.status = 'failed'
            Invoke-PoiTimed -Name 'cleanup_and_verification' -Operation {
                # The generated project scopes both removal and verification.
                $poiResult = Invoke-PoiCompose -Arguments @('down', '--volumes')
                if ($poiResult.exitCode -ne 0) { throw ('Rehearsal cleanup failed: ' + $poiResult.output) }
                $poiResult = Invoke-PoiCompose -Arguments @('ps', '--all', '--quiet')
                if ($poiResult.exitCode -ne 0) { throw ('Rehearsal container cleanup verification failed: ' + $poiResult.output) }
                $poiManifest.cleanup.remainingContainers = @($poiResult.output -split '\r?\n' | Where-Object { $_.Trim() })
                $poiVolumes = & docker volume ls --filter ('label=com.docker.compose.project=' + $poiRunName) --format '{{.Name}}' 2>&1
                $poiVolumeExit = $LASTEXITCODE
                if ($poiVolumeExit -ne 0) { throw ('Rehearsal volume cleanup verification failed: ' + ($poiVolumes | Out-String)) }
                $poiManifest.cleanup.remainingVolumes = @($poiVolumes | Where-Object { $_.ToString().Trim() })
                if ($poiManifest.cleanup.remainingContainers.Count -or $poiManifest.cleanup.remainingVolumes.Count) {
                    throw 'Rehearsal resources remain in the generated project after cleanup.'
                }
                $poiManifest.cleanup.status = 'passed'
            }
        } catch {
            $poiManifest.cleanup.errors += $_.Exception.Message
        } finally {
            Pop-Location
        }
    }
    $poiManifest.testChecksCompleted = $poiTestsPassed
    $poiManifest.stage = 'finished'
    $poiManifest.completedAt = [DateTime]::UtcNow.ToString('o')
    $poiManifest.elapsedSeconds = [Math]::Round($poiTimer.Elapsed.TotalSeconds, 3)
    if ($poiTestsPassed -and $poiManifest.cleanup.status -eq 'passed' -and -not $poiManifest.errors.Count -and -not $poiManifest.logErrors.Count) {
        $poiManifest.status = 'passed'
    }
    Save-PoiManifest
    Write-Output ('Rehearsal manifest: ' + $poiManifestPath)
}
if ($poiManifest.status -ne 'passed') { throw ('Rehearsal failed; see ' + $poiManifestPath) }
Write-Output 'PASS fresh-volume PostgreSQL, worker, Java and production React acceptance; disposable resources removed and absence verified.'
Write-Output 'This rehearsal uses built source and fresh data volumes; it does not claim a Git clone or model-inference test.'
