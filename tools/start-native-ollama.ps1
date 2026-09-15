param([ValidateRange(5, 60)][int]$StartupTimeoutSeconds = 45)

# Starts only the separately installed experimental server. Does not download models.
$ErrorActionPreference = 'Stop'
$poiProjectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).ProviderPath
$poiRuntimeRoot = Join-Path $poiProjectRoot 'runtime\native-ollama'
$poiExecutable = Join-Path $poiRuntimeRoot '0.34.0\ollama.exe'
$poiModelRoot = Join-Path $poiRuntimeRoot 'models'
$poiLogRoot = Join-Path $poiRuntimeRoot 'logs'
$poiReceiptPath = Join-Path $poiRuntimeRoot 'server.json'
$poiExpectedVersion = '0.34.0'
$poiPort = 11435
$poiEndpoint = 'http://127.0.0.1:11435'

function Assert-PoiContainedPath([string]$Candidate) {
    $absolute = [IO.Path]::GetFullPath($Candidate)
    $prefix = $poiProjectRoot.TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Experimental runtime path escapes this project.'
    }
    # Refuse junctions/symlinks inside the project rather than following them outside it.
    $relative = $absolute.Substring($prefix.Length)
    $current = $poiProjectRoot
    foreach ($segment in $relative.Split('\')) {
        $current = Join-Path $current $segment
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw ('Experimental runtime path contains a reparse point: ' + $current)
            }
        }
    }
    return $absolute
}

function Get-PoiPortListeners {
    # Query all listeners so lack of a match is distinct from a failed ownership query.
    @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -eq $poiPort })
}

function Get-PoiUtcTicks($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value.UtcDateTime.Ticks }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { throw 'Receipt timestamp has no timezone.' }
        return $Value.ToUniversalTime().Ticks
    }
    $parsed = [DateTimeOffset]::MinValue
    if ($Value -isnot [string] -or $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$' -or -not [DateTimeOffset]::TryParse($Value,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        throw 'Invalid start time in experimental server receipt.'
    }
    return $parsed.UtcDateTime.Ticks
}

function Get-PoiVerifiedProcess($Receipt) {
    if ($Receipt.schemaVersion -ne 1 -or $Receipt.port -ne $poiPort -or
        $Receipt.hostAddress -ne '127.0.0.1' -or
        $Receipt.executablePath -ne $poiExecutable -or
        ([string]$Receipt.processId) -notmatch '^[1-9][0-9]*$') {
        throw 'Invalid experimental server receipt; no process will be reused or stopped.'
    }
    $serverId = 0
    if (-not [int]::TryParse([string]$Receipt.processId, [ref]$serverId)) {
        throw 'Invalid process ID in experimental server receipt.'
    }
    $recordedStartTicks = Get-PoiUtcTicks $Receipt.startTimeUtc
    $candidate = Get-Process -Id $serverId -ErrorAction SilentlyContinue
    if ($null -eq $candidate) { return $null }
    if ($candidate.Path -ne $poiExecutable -or
        $candidate.StartTime.ToUniversalTime().Ticks -ne $recordedStartTicks) {
        return $null
    }
    return $candidate
}

function Assert-PoiPortOwnership([int]$ServerId, [switch]$RequireListening) {
    $listeners = @(Get-PoiPortListeners)
    foreach ($listener in $listeners) {
        if ($listener.OwningProcess -ne $ServerId -or $listener.LocalAddress -ne '127.0.0.1') {
            throw 'Port 11435 has an unexpected owner or bind address; no process was stopped.'
        }
    }
    if ($RequireListening -and $listeners.Count -eq 0) {
        throw 'The experimental server is not listening on 127.0.0.1:11435.'
    }
}

function Wait-PoiReady($Receipt) {
    $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $lastProbeError = 'The server has not started listening.'
    do {
        $server = Get-PoiVerifiedProcess $Receipt
        if ($null -eq $server) { throw 'The recorded experimental server exited or its identity changed.' }
        Assert-PoiPortOwnership -ServerId $server.Id
        $listeners = @(Get-PoiPortListeners)
        if ($listeners.Count -gt 0) {
            try {
                $version = Invoke-RestMethod -Uri ($poiEndpoint + '/api/version') `
                    -TimeoutSec 2 -MaximumRedirection 0 -ErrorAction Stop
                if ($version.version -ne $poiExpectedVersion) {
                    throw ('Expected Ollama ' + $poiExpectedVersion + ', received ' + $version.version)
                }
                # Verify identity/ownership after the HTTP probe as well.
                $server = Get-PoiVerifiedProcess $Receipt
                if ($null -eq $server) { throw 'Server identity changed during readiness probing.' }
                Assert-PoiPortOwnership -ServerId $server.Id -RequireListening
                return
            } catch {
                $lastProbeError = $_.Exception.Message
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('Experimental server readiness timed out: ' + $lastProbeError)
}

$mutexHasher = [Security.Cryptography.SHA256]::Create()
try {
    $mutexHash = [BitConverter]::ToString($mutexHasher.ComputeHash(
        [Text.Encoding]::UTF8.GetBytes($poiRuntimeRoot.ToLowerInvariant()))).Replace('-', '')
} finally { $mutexHasher.Dispose() }
$poiLifecycleMutex = New-Object Threading.Mutex($false, ('Local\POI.NativeOllama.' + $mutexHash))
$poiHasLifecycleLock = $false
try {
try { $poiHasLifecycleLock = $poiLifecycleMutex.WaitOne(0) }
catch [Threading.AbandonedMutexException] { $poiHasLifecycleLock = $true }
if (-not $poiHasLifecycleLock) { throw 'Another native Ollama start/stop operation is in progress.' }

foreach ($path in @($poiExecutable, $poiModelRoot, $poiLogRoot, $poiReceiptPath)) {
    $null = Assert-PoiContainedPath $path
}
$null = Get-Command Get-NetTCPConnection -ErrorAction Stop
if (-not (Test-Path -LiteralPath $poiExecutable -PathType Leaf)) {
    throw 'Pinned native Ollama executable is missing: runtime\native-ollama\0.34.0\ollama.exe'
}
if (-not (Test-Path -LiteralPath $poiModelRoot -PathType Container)) {
    throw 'Experimental model directory is missing: runtime\native-ollama\models'
}
if (-not (Test-Path -LiteralPath $poiLogRoot)) {
    $null = New-Item -ItemType Directory -Path $poiLogRoot -Force
}

if (Test-Path -LiteralPath $poiReceiptPath -PathType Leaf) {
    $existingReceipt = Get-Content -LiteralPath $poiReceiptPath -Raw | ConvertFrom-Json
    $existingServer = Get-PoiVerifiedProcess $existingReceipt
    if ($null -ne $existingServer) {
        Wait-PoiReady $existingReceipt
        [pscustomobject]@{
            status = 'already-running'; endpoint = $poiEndpoint; processId = $existingServer.Id
            version = $poiExpectedVersion; receipt = $poiReceiptPath
        } | ConvertTo-Json
        return
    }
    if (@(Get-PoiPortListeners).Count -ne 0) {
        throw 'Port 11435 is occupied and does not match the saved server identity. Nothing was stopped.'
    }
    $archivePath = Join-Path $poiLogRoot ('stale-receipt-' + [Guid]::NewGuid().ToString('N') + '.json')
    $null = Assert-PoiContainedPath $archivePath
    Copy-Item -LiteralPath $poiReceiptPath -Destination $archivePath
}
if (@(Get-PoiPortListeners).Count -ne 0) {
    throw 'Port 11435 is already occupied by an unmanaged process. Nothing was stopped.'
}

$logSuffix = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N')
$stdoutPath = Join-Path $poiLogRoot ($logSuffix + '.stdout.log')
$stderrPath = Join-Path $poiLogRoot ($logSuffix + '.stderr.log')
$childEnvironment = @{
    OLLAMA_HOST = '127.0.0.1:11435'
    OLLAMA_MODELS = $poiModelRoot
    OLLAMA_NO_CLOUD = '1'
    OLLAMA_VULKAN = '1'
    OLLAMA_IGPU_ENABLE = '1'
    OLLAMA_NUM_PARALLEL = '1'
    OLLAMA_MAX_LOADED_MODELS = '1'
}
$savedEnvironment = @{}
$newServer = $null
try {
    foreach ($name in $childEnvironment.Keys) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $childEnvironment[$name], 'Process')
    }
    $newServer = Start-Process -FilePath $poiExecutable -ArgumentList @('serve') `
        -WorkingDirectory (Split-Path -Parent $poiExecutable) -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}

$receipt = [pscustomobject]@{
    schemaVersion = 1
    processId = $newServer.Id
    startTimeUtc = $newServer.StartTime.ToUniversalTime().ToString('O')
    executablePath = $poiExecutable
    hostAddress = '127.0.0.1'
    port = $poiPort
    expectedVersion = $poiExpectedVersion
    modelDirectory = $poiModelRoot
    stdoutLog = $stdoutPath
    stderrLog = $stderrPath
    createdAtUtc = [DateTime]::UtcNow.ToString('O')
}

try {
    # The receipt lets the stop script verify the exact server even if readiness fails.
    $temporaryReceipt = Join-Path $poiRuntimeRoot ('server-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $null = Assert-PoiContainedPath $temporaryReceipt
    [IO.File]::WriteAllText($temporaryReceipt, ($receipt | ConvertTo-Json -Depth 4),
        (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporaryReceipt -Destination $poiReceiptPath -Force
    Wait-PoiReady $receipt
} catch {
    $startupFailure = $_.Exception.Message
    try {
        $ownedServer = Get-PoiVerifiedProcess $receipt
        if ($null -ne $ownedServer) {
            Assert-PoiPortOwnership -ServerId $ownedServer.Id
            Stop-Process -InputObject $ownedServer -Force -ErrorAction Stop
            $null = $ownedServer.WaitForExit(10000)
        }
    } catch {
        Write-Warning ('Automatic cleanup did not proceed: ' + $_.Exception.Message)
    }
    throw ($startupFailure + ' Inspect the experimental logs/receipt under runtime\native-ollama.')
}

[pscustomobject]@{
    status = 'started'; endpoint = $poiEndpoint; processId = $newServer.Id
    version = $poiExpectedVersion; receipt = $poiReceiptPath
    stdoutLog = $stdoutPath; stderrLog = $stderrPath
    gpuStatus = 'Vulkan requested; GPU use must be verified from runtime logs and inference measurements.'
} | ConvertTo-Json
} finally {
    if ($poiHasLifecycleLock) { $poiLifecycleMutex.ReleaseMutex() }
    $poiLifecycleMutex.Dispose()
}
