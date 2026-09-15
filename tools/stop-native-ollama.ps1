# Stops only the experimental server described by this project's verified receipt.
$ErrorActionPreference = 'Stop'
$poiProjectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).ProviderPath
$poiRuntimeRoot = Join-Path $poiProjectRoot 'runtime\native-ollama'
$poiExecutable = Join-Path $poiRuntimeRoot '0.34.0\ollama.exe'
$poiReceiptPath = Join-Path $poiRuntimeRoot 'server.json'
$poiPort = 11435

function Assert-PoiContainedPath([string]$Candidate) {
    $absolute = [IO.Path]::GetFullPath($Candidate)
    $prefix = $poiProjectRoot.TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Experimental runtime path escapes this project.'
    }
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
        throw 'Invalid experimental server receipt; no process will be stopped.'
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
        throw 'The receipt PID belongs to a different executable or start time. Nothing was stopped.'
    }
    return $candidate
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

foreach ($path in @($poiExecutable, $poiReceiptPath)) {
    $null = Assert-PoiContainedPath $path
}
$null = Get-Command Get-NetTCPConnection -ErrorAction Stop
$listeners = @(Get-PoiPortListeners)
if (-not (Test-Path -LiteralPath $poiReceiptPath -PathType Leaf)) {
    if ($listeners.Count -ne 0) {
        throw 'Port 11435 has a listener but this project has no server receipt. Nothing was stopped.'
    }
    [pscustomobject]@{ status = 'not-running'; endpoint = 'http://127.0.0.1:11435' } | ConvertTo-Json
    return
}

$receipt = Get-Content -LiteralPath $poiReceiptPath -Raw | ConvertFrom-Json
$server = Get-PoiVerifiedProcess $receipt
if ($null -eq $server) {
    if ($listeners.Count -ne 0) {
        throw 'The recorded server no longer exists, but port 11435 is occupied. Nothing was stopped.'
    }
    [pscustomobject]@{
        status = 'already-stopped'; endpoint = 'http://127.0.0.1:11435'; receipt = $poiReceiptPath
    } | ConvertTo-Json
    return
}

# Re-read port ownership immediately before stopping the identity-checked process.
foreach ($listener in @(Get-PoiPortListeners)) {
    if ($listener.OwningProcess -ne $server.Id -or $listener.LocalAddress -ne '127.0.0.1') {
        throw 'Port 11435 has an unexpected owner or bind address. Nothing was stopped.'
    }
}
$server = Get-PoiVerifiedProcess $receipt
if ($null -eq $server) { throw 'The server exited before the stop operation; nothing was stopped.' }
Stop-Process -InputObject $server -Force -ErrorAction Stop
if (-not $server.WaitForExit(10000)) {
    throw 'The verified experimental process did not exit within ten seconds; receipt retained.'
}

# Retain the receipt and logs as evidence. A later start archives a stale receipt.
[pscustomobject]@{
    status = 'stopped'; processId = $receipt.processId; endpoint = 'http://127.0.0.1:11435'
    receipt = $poiReceiptPath; listenersRemaining = @(Get-PoiPortListeners).Count
    note = 'Stopped only the recorded native server. Other listeners/processes were not changed.'
} | ConvertTo-Json
} finally {
    if ($poiHasLifecycleLock) { $poiLifecycleMutex.ReleaseMutex() }
    $poiLifecycleMutex.Dispose()
}
