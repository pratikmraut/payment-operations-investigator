$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-gpu-lifecycle.ps1')
$mutex = Get-GpuLifecycleMutex
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'Another native GPU demo lifecycle operation is running.' }
    $null = Assert-GpuPath $gpuStackReceiptPath
    if (-not (Test-Path -LiteralPath $gpuStackReceiptPath -PathType Leaf)) {
        [pscustomobject]@{status='no-receipt'; note='No native demo processes were stopped.'} | ConvertTo-Json
        return
    }
    $state = Get-Content -LiteralPath $gpuStackReceiptPath -Raw | ConvertFrom-Json
    if ($state.schemaVersion -ne 1) { throw 'Invalid native demo receipt; nothing was stopped.' }
    $specs = @(Get-GpuSpecs)
    $failures = @()
    foreach ($service in @($state.services | Sort-Object { switch ($_.role) {'web' {0} 'api' {1} 'worker' {2}} })) {
        try {
            $recordedSpec = Get-GpuReceiptSpec $service $specs
            Stop-GpuService $service $recordedSpec
        } catch { $failures += $_.Exception.Message }
    }
    if ($state.ollamaStartedByDemo) {
        try {
            $ollamaPath = Join-Path $gpuRuntimeRoot 'server.json'
            $null = Assert-GpuPath $ollamaPath
            $current = Get-Content -LiteralPath $ollamaPath -Raw | ConvertFrom-Json
            if ($current.processId -ne $state.ollamaReceipt.processId -or
                $current.executablePath -ne $state.ollamaReceipt.executablePath -or
                (Get-GpuUtcTicks $current.startTimeUtc) -ne (Get-GpuUtcTicks $state.ollamaReceipt.startTimeUtc)) {
                throw 'Native Ollama receipt changed since this demo started; it was not stopped.'
            }
            & (Join-Path $PSScriptRoot 'stop-native-ollama.ps1') | Out-Null
        } catch { $failures += $_.Exception.Message }
    }
    if ($failures.Count) { throw ('Some native services were retained: ' + ($failures -join ' | ')) }
    $state.status='stopped'
    Save-GpuStackReceipt $state
    [pscustomobject]@{status='stopped'; receipt=$gpuStackReceiptPath; note='Only verified native demo processes were stopped; state and logs retained.'} | ConvertTo-Json
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
