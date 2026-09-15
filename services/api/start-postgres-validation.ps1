param([string]$JavaExecutable = 'C:\Program Files\Java\jdk-17.0.16\bin\java.exe')
$ErrorActionPreference='Stop'
$taskRuntimeDir=Join-Path $PSScriptRoot 'runtime/postgres-validation'
$taskJar=Join-Path $PSScriptRoot 'target/payment-operations-api-0.1.0.jar'
if(Get-NetTCPConnection -State Listen -LocalPort 8089 -ErrorAction SilentlyContinue){throw 'Validation port 8089 is already occupied; inspect it before continuing.'}
if(-not(Test-Path -LiteralPath $taskJar)){throw 'Build the service jar first.'}
New-Item -ItemType Directory -Force -Path $taskRuntimeDir|Out-Null
$env:PORT='8089'
$env:SPRING_PROFILES_ACTIVE='postgres'
$env:POI_DB_URL='jdbc:postgresql://127.0.0.1:5438/poi'
$env:POI_DB_USER='poi'
if(-not $env:POI_DB_PASSWORD){$env:POI_DB_PASSWORD='poi-local-only'}
$env:POI_WORKER_URL='http://127.0.0.1:8091'
$env:POI_SERVICE_KEY='poi-local-service-key'
$env:POI_DATASET_VERSION='synthetic-payments-v1'
$env:POI_FIXTURES=(Resolve-Path (Join-Path $PSScriptRoot '../../data/fixtures/cases.json')).Path
$taskArguments=@('-Duser.timezone=UTC','-Djdk.net.unixdomain.tmpdir=runtime/socket-fallback-unavailable','-jar',('"'+$taskJar+'"'),'--server.address=127.0.0.1')
$taskProcess=Start-Process -FilePath $JavaExecutable -ArgumentList $taskArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRuntimeDir 'api.stdout.log') -RedirectStandardError (Join-Path $taskRuntimeDir 'api.stderr.log') -PassThru
$taskProcess.Id|Set-Content -LiteralPath (Join-Path $taskRuntimeDir 'api.pid')
[PSCustomObject]@{pid=$taskProcess.Id;url='http://127.0.0.1:8089';profile='postgres';runtime=$taskRuntimeDir}|ConvertTo-Json
