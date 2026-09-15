param(
    [string]$JavaExecutable
)
$ErrorActionPreference = 'Stop'
$taskServiceDir = $PSScriptRoot
$taskJar = Join-Path $taskServiceDir 'target/payment-operations-api-0.1.0.jar'
$taskRuntimeDir = Join-Path $taskServiceDir 'runtime'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Build the API with Maven package before starting.' }
if (-not $JavaExecutable) {
    if ($env:JAVA_HOME) { $JavaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $JavaExecutable = (Get-Command java -ErrorAction Stop).Source }
}
$taskExisting = Get-NetTCPConnection -State Listen -LocalPort 8088 -ErrorAction SilentlyContinue
if ($taskExisting) { throw 'Port 8088 is already in use. Inspect the existing service; this script will not replace it.' }
New-Item -ItemType Directory -Force -Path $taskRuntimeDir | Out-Null
if (-not $env:POI_SERVICE_KEY) { $env:POI_SERVICE_KEY = 'poi-local-service-key' }
if (-not $env:POI_DATASET_VERSION) { $env:POI_DATASET_VERSION = 'synthetic-payments-v1' }
if (-not $env:POI_FIXTURES) { $env:POI_FIXTURES = (Resolve-Path (Join-Path $taskServiceDir '../../data/fixtures/cases.json')).Path }
if (Test-Path -LiteralPath (Join-Path $taskRuntimeDir 'socket-fallback-unavailable')) {
    throw 'The reserved socket fallback path must remain absent for this Windows JDK workaround.'
}
$taskStdout = Join-Path $taskRuntimeDir 'api.stdout.log'
$taskStderr = Join-Path $taskRuntimeDir 'api.stderr.log'
$taskJavaArguments = @('-Djdk.net.unixdomain.tmpdir=runtime/socket-fallback-unavailable', '-jar', ('"' + $taskJar + '"'), '--server.address=127.0.0.1')
$taskProcess = Start-Process -FilePath $JavaExecutable -ArgumentList $taskJavaArguments -WorkingDirectory $taskServiceDir -WindowStyle Hidden -RedirectStandardOutput $taskStdout -RedirectStandardError $taskStderr -PassThru
$taskProcess.Id | Set-Content -LiteralPath (Join-Path $taskRuntimeDir 'api.pid')
[PSCustomObject]@{pid=$taskProcess.Id; status='STARTING'; url='http://127.0.0.1:8088/api/health'; stdout=$taskStdout; stderr=$taskStderr} | ConvertTo-Json