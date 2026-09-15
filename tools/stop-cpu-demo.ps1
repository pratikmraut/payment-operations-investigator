param([switch]$IfRunning)
$ErrorActionPreference = 'Stop'
$poiProject = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).ProviderPath
$poiComposeProject = 'payment-operations-investigator'
$poiCpuServices = @('web','api','investigator','mock-inquiry','ollama','postgres')
$dockerCommands = @(Get-Command docker.exe -CommandType Application -ErrorAction SilentlyContinue)
if ($dockerCommands.Count -eq 0) {
    if ($IfRunning) { Write-Output 'Docker is unavailable; the native website remains running.'; return }
    throw 'Docker is unavailable. No containers were stopped.'
}
$dockerPath = $dockerCommands[0].Source
try {
    $containerIds = @(& $dockerPath ps --no-trunc --filter ('label=com.docker.compose.project=' + $poiComposeProject) --format '{{.ID}}' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not responding.' }
} catch {
    if ($IfRunning) { Write-Output 'Docker is unavailable; the native website remains running.'; return }
    throw 'Cannot list the preserved CPU containers. Nothing was stopped.'
}
if ($containerIds.Count -eq 0) {
    Write-Output 'The preserved CPU demonstration is already stopped.'
    return
}
# Verify every target before stopping any. Container IDs avoid Compose environment
# overrides and ensure that another checkout with the same name is never stopped.
foreach ($containerId in $containerIds) {
    if ($containerId -notmatch '^[a-f0-9]{64}$') { throw 'Unexpected Docker container identity. Nothing was stopped.' }
    $labelsJson = & $dockerPath inspect $containerId --format '{{json .Config.Labels}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify a CPU container. Nothing was stopped.' }
    $labels = $labelsJson | ConvertFrom-Json
    $workingDirectory = [string]$labels.'com.docker.compose.project.working_dir'
    if ($labels.'com.docker.compose.project' -ne $poiComposeProject -or
        $poiCpuServices -notcontains $labels.'com.docker.compose.service' -or
        -not [IO.Path]::IsPathRooted($workingDirectory) -or
        [IO.Path]::GetFullPath($workingDirectory).TrimEnd('\') -ne $poiProject.TrimEnd('\')) {
        throw 'A container does not belong to this CPU demonstration checkout. Nothing was stopped.'
    }
}
& $dockerPath container stop --time 15 @containerIds | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'The verified CPU container stop failed. Native services and stored data were not removed.' }
Write-Output 'Preserved CPU containers stopped. Their database, model, checkpoint volumes and configuration remain available.'
