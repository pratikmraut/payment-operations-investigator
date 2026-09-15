# Shared helpers for the additive native demo. Dot-source only from its wrappers.
$ErrorActionPreference = 'Stop'
$gpuProjectRoot = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).ProviderPath
$gpuRuntimeRoot = Join-Path $gpuProjectRoot 'runtime\native-ollama'
$gpuPrivateRoot = Join-Path $gpuRuntimeRoot 'private'
$gpuStackReceiptPath = Join-Path $gpuRuntimeRoot 'demo-server.json'
$gpuStackLogRoot = Join-Path $gpuRuntimeRoot 'logs'
$gpuJavaSocketRoot = Join-Path $gpuProjectRoot 'runtime\jt'

function Assert-GpuPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    $prefix = $gpuProjectRoot.TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Native demo path escapes the project.' }
    $current = $gpuProjectRoot
    foreach ($part in $absolute.Substring($prefix.Length).Split('\')) {
        $current = Join-Path $current $part
        if ((Test-Path -LiteralPath $current) -and
            (((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            throw ('Native demo path contains a junction or symbolic link: ' + $current)
        }
    }
    return $absolute
}

function Get-GpuListeners([int]$Port) {
    @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $Port })
}

function Get-GpuSpecs([ValidateSet(5178, 5179)][int]$WebPort = 5178) {
    $java = 'C:\Program Files\Java\jdk-17.0.16\bin\java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { $java = @(Get-Command java.exe -CommandType Application -ErrorAction Stop)[0].Source }
    $node = @(Get-Command node.exe -CommandType Application -ErrorAction Stop)[0].Source
    $python = Join-Path $gpuProjectRoot 'services\investigator\.venv\Scripts\python.exe'
    $pythonConfig = Join-Path $gpuProjectRoot 'services\investigator\.venv\pyvenv.cfg'
    $basePythonLine = @(Get-Content -LiteralPath $pythonConfig | Where-Object { $_ -match '^executable\s*=' })
    $pythonImages = @($python)
    if ($basePythonLine.Count -eq 1) { $pythonImages += ($basePythonLine[0] -replace '^executable\s*=\s*', '').Trim() }
    $jar = Join-Path $gpuRuntimeRoot 'artifacts\api.jar'
    $apiArgs = @(('-Djdk.net.unixdomain.tmpdir=' + $gpuJavaSocketRoot))
    $apiRequired = @($java, $jar)
    $apiHosts = Assert-GpuPath (Join-Path $gpuPrivateRoot 'api-hosts')
    if (Test-Path -LiteralPath $apiHosts -PathType Leaf) {
        # An explicitly supplied private hosts file replaces DNS for this API JVM only.
        # Keep all required hostnames in it; certificate/hostname verification stays enabled.
        $apiArgs += '-Djdk.net.hosts.file=' + $apiHosts
        $apiRequired += $apiHosts
    }
    $apiArgs += @('-jar', $jar)
    $vite = Join-Path $gpuProjectRoot 'apps\web\node_modules\vite\bin\vite.js'
    $viteConfig = Join-Path $gpuProjectRoot 'apps\web\vite.gpu.config.ts'
    return @(
        [pscustomobject]@{role='worker'; port=8093; executable=$python; allowedImages=$pythonImages; args=@('-m','uvicorn','investigator.main:app','--host','127.0.0.1','--port','8093'); markers=@('uvicorn','investigator.main:app','127.0.0.1','8093'); cwd=(Join-Path $gpuProjectRoot 'services\investigator'); health='http://127.0.0.1:8093/health'; required=@($python,$pythonConfig)},
        [pscustomobject]@{role='api'; port=8089; executable=$java; allowedImages=@($java); args=$apiArgs; markers=@('-jar',$jar); cwd=$gpuPrivateRoot; health='http://127.0.0.1:8089/api/health'; required=$apiRequired},
        [pscustomobject]@{role='web'; port=$WebPort; executable=$node; allowedImages=@($node); args=@($vite,'preview','--config',$viteConfig,'--host','127.0.0.1','--port',([string]$WebPort),'--strictPort'); markers=@($vite,'preview',$viteConfig,'127.0.0.1',([string]$WebPort)); cwd=(Join-Path $gpuProjectRoot 'apps\web'); health=('http://127.0.0.1:' + $WebPort + '/'); required=@($node,$vite,$viteConfig,(Join-Path $gpuProjectRoot 'runtime\native-gpu\web-dist\index.html'))}
    )
}

function Get-GpuReceiptSpec($Service, $CurrentSpecs) {
    $matchingSpecs = @($CurrentSpecs | Where-Object { $_.role -eq $Service.role -and $_.port -eq $Service.port })
    if ($matchingSpecs.Count -eq 1) { return $matchingSpecs[0] }
    # The former experiment port is recognized only to verify/stop its recorded
    # process or archive a stale receipt. New startup always uses the default specs.
    if ($Service.role -eq 'web' -and $Service.port -eq 5179) {
        return @((Get-GpuSpecs -WebPort 5179) | Where-Object { $_.role -eq 'web' })[0]
    }
    throw 'Unknown service in native demo receipt.'
}

function Get-GpuProcessRecord([int]$ServerId) {
    $proc = Get-Process -Id $ServerId -ErrorAction SilentlyContinue
    if ($null -eq $proc) { return $null }
    try {
        $info = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $ServerId) -ErrorAction Stop
        if ($null -eq $info -or -not $info.ExecutablePath -or -not $info.CommandLine) { throw 'Cannot verify process executable and command line.' }
        $started = $proc.StartTime.ToUniversalTime().ToString('O')
    } catch {
        # A virtualenv launcher can exit as soon as its verified child stops.
        # Only absence permits this result; a live but unverifiable PID still fails closed.
        if ($null -eq (Get-Process -Id $ServerId -ErrorAction SilentlyContinue)) { return $null }
        throw
    }
    return [pscustomobject]@{processId=$ServerId; executablePath=$info.ExecutablePath; startTimeUtc=$started; commandLine=$info.CommandLine; parentProcessId=[int]$info.ParentProcessId}
}

function Get-GpuUtcTicks($Value) {
    if ($Value -is [DateTimeOffset]) { return $Value.UtcDateTime.Ticks }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { throw 'Receipt timestamp has no timezone.' }
        return $Value.ToUniversalTime().Ticks
    }
    $parsed = [DateTimeOffset]::MinValue
    if ($Value -isnot [string] -or $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$' -or -not [DateTimeOffset]::TryParse($Value,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None, [ref]$parsed)) { throw 'Invalid native process timestamp.' }
    return $parsed.UtcDateTime.Ticks
}

function Get-GpuVerifiedProcess($Record, $Spec) {
    $idNumber = 0
    if ($null -eq $Record -or -not [int]::TryParse([string]$Record.processId, [ref]$idNumber) -or $idNumber -le 0 -or
        $Spec.allowedImages -notcontains $Record.executablePath) { throw 'Unrecognized executable or process ID in native demo receipt.' }
    foreach ($marker in $Spec.markers) {
        if (-not ([string]$Record.commandLine).Contains($marker)) { throw 'Recorded command line does not identify the native demo service.' }
    }
    $current = Get-GpuProcessRecord $idNumber
    if ($null -eq $current) { return $null }
    if ($current.executablePath -ne $Record.executablePath -or (Get-GpuUtcTicks $current.startTimeUtc) -ne (Get-GpuUtcTicks $Record.startTimeUtc) -or
        $current.commandLine -cne $Record.commandLine) { throw 'Process identity changed; no process will be stopped or reused.' }
    return $current
}

function Save-GpuStackReceipt($Receipt) {
    $null = Assert-GpuPath $gpuStackReceiptPath
    $temp = Join-Path $gpuRuntimeRoot ('demo-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    [IO.File]::WriteAllText($temp, ($Receipt | ConvertTo-Json -Depth 9), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temp -Destination $gpuStackReceiptPath -Force
}

function Start-GpuChild($Spec, [hashtable]$ChildEnvironment) {
    if (@(Get-GpuListeners $Spec.port).Count) { throw ('Port is occupied: ' + $Spec.port) }
    $suffix = $Spec.role + '-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N')
    $stdout = Join-Path $gpuStackLogRoot ($suffix + '.stdout.log')
    $stderr = Join-Path $gpuStackLogRoot ($suffix + '.stderr.log')
    $arguments = @($Spec.args | ForEach-Object {
        if ($_ -match '["\r\n]' -or $_.EndsWith('\')) { throw 'Unsupported native process argument.' }
        '"' + $_ + '"'
    }) -join ' '
    $saved = @{}
    $process = $null
    try {
        foreach ($name in $ChildEnvironment.Keys) {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $ChildEnvironment[$name], 'Process')
        }
        $process = Start-Process -FilePath $Spec.executable -ArgumentList $arguments -WorkingDirectory $Spec.cwd `
            -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    } finally {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
    $record = Get-GpuProcessRecord $process.Id
    if ($null -eq $record) { throw ('Native service exited immediately: ' + $Spec.role + '. Inspect ' + $stderr) }
    return [pscustomobject]@{role=$Spec.role; port=$Spec.port; launcher=$record; server=$record; stdoutLog=$stdout; stderrLog=$stderr}
}

function Wait-GpuService($Service, $Spec, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $null = Get-GpuVerifiedProcess $Service.launcher $Spec
        $listeners = @(Get-GpuListeners $Spec.port)
        foreach ($listener in $listeners) {
            if ($listener.LocalAddress -ne '127.0.0.1') { throw 'Native service did not bind exclusively to loopback.' }
            if ($listener.OwningProcess -ne $Service.server.processId) {
                # Windows virtualenv launchers may have a base-Python child owning the socket.
                $child = Get-GpuProcessRecord ([int]$listener.OwningProcess)
                if ($Spec.role -ne 'worker' -or $null -eq $child -or $child.parentProcessId -ne $Service.launcher.processId -or
                    $Spec.allowedImages -notcontains $child.executablePath) { throw 'Native service port has an unrelated owner.' }
                foreach ($marker in $Spec.markers) {
                    if (-not $child.commandLine.Contains($marker)) { throw 'Unexpected worker child command line.' }
                }
                $Service.server = $child
            }
        }
        $server = Get-GpuVerifiedProcess $Service.server $Spec
        if ($null -eq $server) { throw ('Native service exited: ' + $Spec.role) }
        if ($listeners.Count -gt 0) {
            try {
                $response = Invoke-WebRequest -Uri $Spec.health -UseBasicParsing -TimeoutSec 2 -MaximumRedirection 0
                if ($response.StatusCode -eq 200 -and ($Spec.role -eq 'web' -or ($response.Content | ConvertFrom-Json).status -eq 'UP')) {
                    return
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw ('Native service readiness timed out: ' + $Spec.role + '. Inspect its saved logs.')
}

function Stop-GpuService($Service, $Spec) {
    if ($Service.role -ne $Spec.role -or $Service.port -ne $Spec.port) { throw 'Invalid native service receipt.' }
    $server = Get-GpuVerifiedProcess $Service.server $Spec
    $launcher = Get-GpuVerifiedProcess $Service.launcher $Spec
    foreach ($listener in @(Get-GpuListeners $Spec.port)) {
        if ($null -eq $server -or $listener.OwningProcess -ne $server.processId -or $listener.LocalAddress -ne '127.0.0.1') {
            throw 'Native service port ownership changed; nothing was stopped for this service.'
        }
    }
    foreach ($record in @($server, $launcher)) {
        if ($null -eq $record) { continue }
        $current = Get-GpuVerifiedProcess $record $Spec
        if ($null -ne $current) {
            $process = Get-Process -Id $current.processId -ErrorAction Stop
            Stop-Process -InputObject $process -Force -ErrorAction Stop
            if (-not $process.WaitForExit(10000)) { throw 'Verified native process did not exit in ten seconds.' }
        }
    }
}

function Get-GpuLifecycleMutex {
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($gpuRuntimeRoot.ToLowerInvariant()))).Replace('-','') }
    finally { $hasher.Dispose() }
    return (New-Object Threading.Mutex($false, ('Local\POI.NativeGpuDemo.' + $hash)))
}

function Get-GpuSecret([string]$FileName) {
    $path = Join-Path $gpuPrivateRoot $FileName
    $null = Assert-GpuPath $path
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        [IO.File]::WriteAllText($path, [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant(), (New-Object Text.UTF8Encoding($false)))
    }
    $secret = [IO.File]::ReadAllText($path).Trim()
    if ($secret -notmatch '^[a-f0-9]{64}$') { throw 'Invalid native demo secret file.' }
    return $secret
}

function Get-GpuCleanEnvironment {
    # Null values remove inherited credentials/tracing settings only in the new child.
    $child = @{}
    foreach ($name in [Environment]::GetEnvironmentVariables('Process').Keys) {
        if ($name -match '^(POI_|OLLAMA_|SPRING_|SERVER_|UVICORN_|VITE_)') { $child[$name]=$null }
    }
    $settings = @{
        LANGCHAIN_TRACING='false'; LANGCHAIN_TRACING_V2='false'; LANGSMITH_TRACING='false'
        LANGCHAIN_API_KEY=$null; LANGSMITH_API_KEY=$null; LANGCHAIN_ENDPOINT=$null; LANGSMITH_ENDPOINT=$null
        LANGCHAIN_PROJECT=$null; LANGSMITH_PROJECT=$null; LANGSMITH_WORKSPACE_ID=$null
        OPENAI_API_KEY=$null; ANTHROPIC_API_KEY=$null; GOOGLE_API_KEY=$null; GEMINI_API_KEY=$null; COHERE_API_KEY=$null
        OTEL_SDK_DISABLED='true'; DD_TRACE_ENABLED='false'; OTEL_EXPORTER_OTLP_ENDPOINT=$null
        PYTHONUNBUFFERED='1'; OLLAMA_NO_CLOUD='1'; PORT=$null
        PYTHONPATH=$null; PYTHONHOME=$null; VIRTUAL_ENV=$null
        JAVA_TOOL_OPTIONS=$null; _JAVA_OPTIONS=$null; JDK_JAVA_OPTIONS=$null
        HTTP_PROXY=$null; HTTPS_PROXY=$null; ALL_PROXY=$null
        NO_PROXY='127.0.0.1,localhost,::1'
    }
    foreach ($name in $settings.Keys) { $child[$name]=$settings[$name] }
    return $child
}
