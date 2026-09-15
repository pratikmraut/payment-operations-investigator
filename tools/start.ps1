param(
    [ValidateSet('GPU','CPU')][string]$Mode = 'GPU',
    [switch]$WithAI,
    [ValidateRange(30, 600)][int]$ReadyTimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'CPU') {
    & (Join-Path $PSScriptRoot 'start-cpu-demo.ps1') -WithAI:$WithAI -ReadyTimeoutSeconds $ReadyTimeoutSeconds
    return
}
# GPU is the default, including legacy invocations with -WithAI.
. (Join-Path $PSScriptRoot 'native-gpu-lifecycle.ps1')
foreach ($spec in @(Get-GpuSpecs)) {
    foreach ($file in $spec.required) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw ('GPU preparation is incomplete: ' + $file + '. See docs/NATIVE_GPU_SETUP.md. CPU website was not moved.')
        }
    }
}
& (Join-Path $PSScriptRoot 'set-default-gpu.ps1')
& (Join-Path $PSScriptRoot 'start-native-gpu-demo.ps1') -ReadyTimeoutSeconds ([Math]::Min(60, $ReadyTimeoutSeconds))
& (Join-Path $PSScriptRoot 'stop-cpu-demo.ps1') -IfRunning
Write-Output 'Default Intel GPU workbench: http://127.0.0.1:5178/'
Write-Output 'Local demo login: Analyst - Northstar / demo-pass-local'
