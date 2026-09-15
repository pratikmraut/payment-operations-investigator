param([switch]$WithAI, [ValidateRange(30, 600)][int]$ReadyTimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
$poiProject = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $poiProject
try {
    if ($WithAI) {
        & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml --profile ai up -d --build
    } else {
        & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml up -d --build
    }
    if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed. Review the preceding build output.' }
    if ($WithAI) {
        foreach ($poiModelVariable in @('OLLAMA_MODEL', 'OLLAMA_EMBED_MODEL', 'POI_UAT_MODEL')) {
            $poiModelValue = & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml exec -T investigator printenv $poiModelVariable
            if ($LASTEXITCODE -ne 0) { throw ('Cannot read configured model: ' + $poiModelVariable) }
            $poiModelName = ($poiModelValue | Out-String).Trim()
            if (-not $poiModelName) { throw ('Configured model is empty: ' + $poiModelVariable) }
            & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml exec -T ollama ollama show $poiModelName *> $null
            if ($LASTEXITCODE -eq 0) {
                Write-Output ('Using installed model: ' + $poiModelName)
            } else {
                & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml exec -T ollama ollama pull $poiModelName
                if ($LASTEXITCODE -ne 0) { throw ('Model download failed: ' + $poiModelName) }
            }
        }
    }
    $poiDeadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    $poiReady = $false
    do {
        try {
            $poiApiHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:5180/api/health' -TimeoutSec 5
            $poiWorkerHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8091/health' -TimeoutSec 5
            $poiInquiryHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:8092/health' -TimeoutSec 5
            $poiWebResponse = Invoke-WebRequest -Uri 'http://127.0.0.1:5180/' -UseBasicParsing -TimeoutSec 5
            $poiReady = $poiApiHealth.status -eq 'UP' -and $poiWorkerHealth.status -eq 'UP' -and $poiInquiryHealth.status -eq 'UP' -and $poiWebResponse.StatusCode -eq 200
        } catch {
            $poiReady = $false
        }
        if (-not $poiReady) { Start-Sleep -Seconds 2 }
    } while (-not $poiReady -and [DateTime]::UtcNow -lt $poiDeadline)
    if (-not $poiReady) {
        & docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml ps
        throw 'Application readiness timed out. Run docker compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml logs --tail 80 api investigator mock-inquiry web to inspect this project.'
    }
    Write-Output 'Web, API proxy, investigation worker and mock inquiry responded successfully.'
    Write-Output 'Workbench: http://127.0.0.1:5180'
    Write-Output 'Local demo password: use POI_DEMO_PASSWORD from .env, or demo-pass-local.'
    Write-Output 'Roles: analyst, reviewer, viewer (Northstar), other (Silverline).'
} finally {
    Pop-Location
}
