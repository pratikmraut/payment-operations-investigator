# Move only this project's existing Docker web binding. Model/backend state is untouched.
$ErrorActionPreference = 'Stop'
$poiRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).ProviderPath
$dockerCommand = Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) { return }
$dockerPath = $dockerCommand.Source
$containerName = 'payment-operations-investigator-web-1'
try { $existing = & $dockerPath ps -a --filter ('name=^/' + $containerName + '$') --format '{{.ID}}' 2>$null }
catch { return } # Docker may be stopped while the prepared native demo is usable.
if ($LASTEXITCODE -ne 0 -or -not $existing) { return }
$labelsJson = & $dockerPath inspect $containerName --format '{{json .Config.Labels}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the preserved CPU website container.' }
$labels = $labelsJson | ConvertFrom-Json
if ($labels.'com.docker.compose.project' -ne 'payment-operations-investigator' -or
    $labels.'com.docker.compose.service' -ne 'web' -or
    [IO.Path]::GetFullPath($labels.'com.docker.compose.project.working_dir').TrimEnd('\') -ne $poiRoot.TrimEnd('\')) {
    throw 'The named web container does not belong to this project checkout. Nothing was changed.'
}
$bindingJson = & $dockerPath inspect $containerName --format '{{json .HostConfig.PortBindings}}'
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the preserved CPU website binding.' }
$binding = @((($bindingJson | ConvertFrom-Json).'80/tcp'))
if ($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -notin @('5178','5180')) {
    throw 'Unexpected CPU website binding. Nothing was changed.'
}
if ($binding[0].HostPort -eq '5180') { return }
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 5180)
if ($listeners.Count -ne 0) { throw 'Port 5180 is already occupied. No process was stopped.' }
Push-Location -LiteralPath $poiRoot
try {
    & $dockerPath compose -f compose.yaml -f infra/compose.uat-qa.yaml -f compose.override.yaml up -d --no-deps --no-build web
    if ($LASTEXITCODE -ne 0) { throw 'CPU website port migration failed; inspect this project web container.' }
    Write-Output 'Preserved CPU demonstration: http://127.0.0.1:5180/evidences'
} finally { Pop-Location }
