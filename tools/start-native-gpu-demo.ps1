param([ValidateRange(5, 60)][int]$ReadyTimeoutSeconds = 60)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-gpu-lifecycle.ps1')
$mutex = Get-GpuLifecycleMutex
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'Another native GPU demo lifecycle operation is running.' }
    $null = Get-Command Get-NetTCPConnection -ErrorAction Stop
    foreach ($path in @($gpuPrivateRoot, $gpuStackLogRoot, $gpuStackReceiptPath, $gpuJavaSocketRoot)) { $null = Assert-GpuPath $path }
    if ([Text.Encoding]::UTF8.GetByteCount((Join-Path $gpuJavaSocketRoot 'socket_2147483647')) -gt 100) {
        throw 'The project path is too long for the scoped Java socket directory. Use a shorter project path before starting the native demo.'
    }
    $specs = @(Get-GpuSpecs)
    foreach ($spec in $specs) {
        foreach ($file in $spec.required) {
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw ('Required native demo file is missing: ' + $file) }
            if ($file.StartsWith($gpuProjectRoot, [StringComparison]::OrdinalIgnoreCase)) { $null = Assert-GpuPath $file }
        }
    }
    $bundlePath = Join-Path $gpuProjectRoot 'runtime\obpm-uat\private\qa\bundles'
    $null = Assert-GpuPath $bundlePath
    if (-not (Test-Path -LiteralPath $bundlePath -PathType Container)) { throw 'Private UAT bundle directory is missing.' }

    if (Test-Path -LiteralPath $gpuStackReceiptPath -PathType Leaf) {
        $previous = Get-Content -LiteralPath $gpuStackReceiptPath -Raw | ConvertFrom-Json
        if ($previous.schemaVersion -ne 1) { throw 'Invalid native demo receipt.' }
        $liveCount = 0
        $legacyLayout = $false
        $seenRoles = @{}
        foreach ($service in @($previous.services)) {
            $recordedSpec = Get-GpuReceiptSpec $service $specs
            if ($seenRoles.ContainsKey($service.role)) { throw 'Duplicate service in native demo receipt.' }
            $seenRoles[$service.role]=$true
            if ($service.role -eq 'web' -and $service.port -eq 5179) { $legacyLayout=$true }
            if ($null -ne (Get-GpuVerifiedProcess $service.server $recordedSpec)) { $liveCount++ }
        }
        if ($liveCount -gt 0) {
            if ($legacyLayout) { throw 'The former GPU demo is still running on port 5179. Use stop-native-gpu-demo.ps1 before starting the main GPU website.' }
            if ($liveCount -ne 3 -or @($previous.services).Count -ne 3) { throw 'Native demo is partly running. Use stop-native-gpu-demo.ps1 before restarting.' }
            $ollamaReuse = (& (Join-Path $PSScriptRoot 'start-native-ollama.ps1') | Out-String) | ConvertFrom-Json
            if ($ollamaReuse.status -eq 'started') {
                $previous.ollamaStartedByDemo=$true
                $previous.ollamaReceipt=Get-Content -LiteralPath (Join-Path $gpuRuntimeRoot 'server.json') -Raw | ConvertFrom-Json
                Save-GpuStackReceipt $previous
            }
            foreach ($spec in $specs) { Wait-GpuService (@($previous.services | Where-Object { $_.role -eq $spec.role })[0]) $spec $ReadyTimeoutSeconds }
            [pscustomobject]@{status='already-running'; url='http://127.0.0.1:5178/'; receipt=$gpuStackReceiptPath} | ConvertTo-Json
            return
        }
    }
    foreach ($spec in $specs) {
        if (@(Get-GpuListeners $spec.port).Count -ne 0) { throw ('Unmanaged listener on native demo port ' + $spec.port + '. Nothing was stopped.') }
    }
    foreach ($dir in @($gpuPrivateRoot, $gpuStackLogRoot, $gpuJavaSocketRoot, (Join-Path $gpuPrivateRoot 'answers'))) {
        $null = Assert-GpuPath $dir
        $null = New-Item -ItemType Directory -Path $dir -Force
    }
    if (Test-Path -LiteralPath $gpuStackReceiptPath -PathType Leaf) {
        Copy-Item -LiteralPath $gpuStackReceiptPath -Destination (Join-Path $gpuStackLogRoot ('demo-receipt-' + [Guid]::NewGuid().ToString('N') + '.json'))
    }
    $serviceKey = Get-GpuSecret 'service-key.txt'
    $dbPassword = Get-GpuSecret 'db-password.txt'
    $state = [pscustomobject]@{schemaVersion=1; createdAtUtc=[DateTime]::UtcNow.ToString('O'); status='starting'; services=@(); ollamaStartedByDemo=$false; ollamaReceipt=$null; apiJarSha256=(Get-FileHash -LiteralPath (Join-Path $gpuRuntimeRoot 'artifacts\api.jar') -Algorithm SHA256).Hash.ToLowerInvariant()}
    try {
        $ollamaResult = (& (Join-Path $PSScriptRoot 'start-native-ollama.ps1') | Out-String) | ConvertFrom-Json
        $state.ollamaStartedByDemo = $ollamaResult.status -eq 'started'
        $state.ollamaReceipt = Get-Content -LiteralPath (Join-Path $gpuRuntimeRoot 'server.json') -Raw | ConvertFrom-Json
        Save-GpuStackReceipt $state
        foreach ($spec in $specs) {
            $childEnv = Get-GpuCleanEnvironment
            if ($spec.role -eq 'worker') {
                $childEnv.POI_SERVICE_KEY=$serviceKey
                $childEnv.POI_KNOWLEDGE_PATH=Join-Path $gpuProjectRoot 'data\knowledge\runbooks.json'
                $childEnv.POI_CHECKPOINT_PATH=Join-Path $gpuPrivateRoot 'checkpoints.sqlite'
                $childEnv.POI_RETRIEVAL_MODE='lexical'; $childEnv.POI_VECTOR_DB_URL=$null
                $childEnv.OLLAMA_BASE_URL='http://127.0.0.1:11435'; $childEnv.OLLAMA_MODEL='qwen3:8b'
                $childEnv.POI_UAT_MODEL='qwen3:8b'; $childEnv.POI_UAT_CONTEXT_TOKENS='32768'
                $childEnv.POI_UAT_OUTPUT_TOKENS='1400'; $childEnv.POI_MODEL_THREADS='4'
                $childEnv.POI_UAT_MODEL_TIMEOUT_SECONDS='360'; $childEnv.POI_UAT_MODEL_KEEP_ALIVE_SECONDS='0'
                $childEnv.POI_MODEL_TIMEOUT_SECONDS='360'; $childEnv.POI_MODEL_CONTEXT_TOKENS='4096'
                $childEnv.POI_TOOL_OUTPUT_TOKENS='384'; $childEnv.POI_SYNTHESIS_OUTPUT_TOKENS='384'
            } elseif ($spec.role -eq 'api') {
                $childEnv.PORT='8089'; $childEnv.SERVER_ADDRESS='127.0.0.1'
                $childEnv.SERVER_SERVLET_SESSION_COOKIE_NAME='POI_GPU_SESSION'
                $childEnv.SPRING_PROFILES_ACTIVE=$null; $childEnv.POI_DB_USER='sa'; $childEnv.POI_DB_PASSWORD=$dbPassword
                $childEnv.POI_DB_URL='jdbc:h2:file:' + ((Join-Path $gpuPrivateRoot 'poi-gpu').Replace('\','/')) + ';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH'
                $childEnv.POI_WORKER_URL='http://127.0.0.1:8093'; $childEnv.POI_SERVICE_KEY=$serviceKey
                $childEnv.POI_WORKER_TIMEOUT_SECONDS='390'; $childEnv.POI_UAT_WORKER_TIMEOUT_SECONDS='390'
                $childEnv.POI_UAT_BUNDLE_DIR=$bundlePath; $childEnv.POI_UAT_RESULT_DIR=Join-Path $gpuPrivateRoot 'answers'
                $childEnv.POI_FIXTURES=Join-Path $gpuProjectRoot 'data\fixtures\cases.json'
                $childEnv.POI_OBPM_SAMPLES=Join-Path $gpuProjectRoot 'data\obpm\samples'
                $childEnv.POI_OBPM_INQUIRY_ENABLED='false'; $childEnv.POI_COOKIE_SECURE='false'
                $childEnv.POI_DEMO_PASSWORD='demo-pass-local'; $childEnv.POI_DATASET_VERSION='synthetic-payments-v1'
                $childEnv.POI_IMPORT_FIXTURES='true'
                # Explicit discovery configuration is independent of model settings.
                # No bank endpoint is enabled by default; the application uses MOCK.
                foreach ($discoverySetting in @(
                    'POI_PAYMENT_DISCOVERY_MODE', 'POI_PAYMENT_DISCOVERY_BANK_ENABLED',
                    'POI_PAYMENT_DISCOVERY_BANK_URL', 'POI_PAYMENT_DISCOVERY_DEPLOYMENT',
                    'POI_PAYMENT_DISCOVERY_SCOPES', 'POI_PAYMENT_DISCOVERY_TIMEOUT_SECONDS'
                )) {
                    $discoveryValue = [Environment]::GetEnvironmentVariable($discoverySetting, 'Process')
                    if (-not [string]::IsNullOrWhiteSpace($discoveryValue)) { $childEnv[$discoverySetting] = $discoveryValue }
                }
            }
            $service = Start-GpuChild $spec $childEnv
            $state.services += $service
            Save-GpuStackReceipt $state
            Wait-GpuService $service $spec $ReadyTimeoutSeconds
            Save-GpuStackReceipt $state
        }
        $state.status='ready'
        Save-GpuStackReceipt $state
    } catch {
        $failure = $_.Exception.Message
        foreach ($service in @($state.services | Sort-Object { switch ($_.role) {'web' {0} 'api' {1} 'worker' {2}} })) {
            try { Stop-GpuService $service (@($specs | Where-Object { $_.role -eq $service.role })[0]) }
            catch { Write-Warning ('Cleanup retained ' + $service.role + ': ' + $_.Exception.Message) }
        }
        if ($state.ollamaStartedByDemo) {
            try {
                $ollamaCurrent=Get-Content -LiteralPath (Join-Path $gpuRuntimeRoot 'server.json') -Raw | ConvertFrom-Json
                if ($ollamaCurrent.processId -ne $state.ollamaReceipt.processId -or
                    $ollamaCurrent.executablePath -ne $state.ollamaReceipt.executablePath -or
                    (Get-GpuUtcTicks $ollamaCurrent.startTimeUtc) -ne (Get-GpuUtcTicks $state.ollamaReceipt.startTimeUtc)) { throw 'Ollama receipt changed after startup; it was retained.' }
                & (Join-Path $PSScriptRoot 'stop-native-ollama.ps1') | Out-Null
            }
            catch { Write-Warning ('Ollama cleanup retained its receipt: ' + $_.Exception.Message) }
        }
        $state.status='failed'
        Save-GpuStackReceipt $state
        throw ($failure + ' Native demo receipts/logs retained; the Docker baseline was not changed.')
    }
    [pscustomobject]@{
        status='ready'; url='http://127.0.0.1:5178/'; receipt=$gpuStackReceiptPath
        note='Separate native stack started. GPU acceleration and answer accuracy require measurement.'
    } | ConvertTo-Json
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
