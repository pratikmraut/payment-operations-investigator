param([string]$Password = 'poi-vector-local-only')
$ErrorActionPreference='Stop'
$taskContainer='payment-operations-investigator-postgres-1'
$taskChecks=[System.Collections.Generic.List[string]]::new()
function Invoke-VectorSql([string]$Sql) {
    $taskOutput=& docker exec -e "PGPASSWORD=$Password" $taskContainer psql -X -h 127.0.0.1 -U poi_vectors -d poi -At -v ON_ERROR_STOP=1 -c $Sql 2>&1
    return @{exitCode=$LASTEXITCODE;output=($taskOutput|Out-String).Trim()}
}
$taskIdentity=Invoke-VectorSql "SELECT current_user || '|' || current_schema() || '|' || current_setting('search_path');"
if($taskIdentity.exitCode -ne 0 -or $taskIdentity.output -ne 'poi_vectors|poi_knowledge|poi_knowledge, public'){throw 'Unexpected vector role identity or schema scope.'}
$taskChecks.Add('TCP password authentication and role-specific search_path')
$taskPrivileges=Invoke-VectorSql 'SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user;'
if($taskPrivileges.exitCode -ne 0 -or $taskPrivileges.output -ne 'f'){throw 'Vector role has unexpected elevated attributes.'}
$taskChecks.Add('No superuser, role/database creation, inheritance, replication or RLS bypass')
$taskOwnSchema=Invoke-VectorSql "BEGIN; CREATE TABLE poi_knowledge.permission_probe(id integer PRIMARY KEY,embedding public.vector(3)); INSERT INTO poi_knowledge.permission_probe VALUES(1,'[1,0,0]'); SELECT id,embedding FROM poi_knowledge.permission_probe; ROLLBACK;"
if($taskOwnSchema.exitCode -ne 0 -or $taskOwnSchema.output -notlike '*1|[[]1,0,0[]]*'){throw 'Vector role cannot create/use a vector table in its own schema.'}
$taskChecks.Add('Own-schema vector table create, insert and select; probe rolled back')
foreach($taskTable in @('payment_case','investigation','review_decision','audit_event')) {
    foreach($taskOperation in @("SELECT * FROM public.$taskTable LIMIT 0;","DELETE FROM public.$taskTable WHERE false;")) {
        $taskDenied=Invoke-VectorSql $taskOperation
        if($taskDenied.exitCode -eq 0 -or $taskDenied.output -notlike '*permission denied*'){throw "Expected permission denial for $taskOperation"}
    }
    $taskChecks.Add("API table $taskTable SELECT and DELETE denied")
}
$taskPublic=Invoke-VectorSql 'BEGIN; CREATE TABLE public.poi_forbidden_probe(id integer); ROLLBACK;'
if($taskPublic.exitCode -eq 0 -or $taskPublic.output -notlike '*permission denied for schema public*'){throw 'Expected public schema CREATE denial.'}
$taskChecks.Add('Public schema CREATE denied')
$taskOwner=Invoke-VectorSql 'SET ROLE poi;'
if($taskOwner.exitCode -eq 0 -or $taskOwner.output -notlike '*permission denied*'){throw 'Expected API owner impersonation denial.'}
$taskChecks.Add('SET ROLE poi denied')
$taskResult=[ordered]@{status='PASS';checkedAt=[DateTime]::UtcNow.ToString('o');database='127.0.0.1:5438/poi';role='poi_vectors';schema='poi_knowledge';checks=$taskChecks;limitations=@('API demo owner poi is still the dedicated development database administrator.','Permission probes use only original synthetic project data.');bootstrap='infra/postgres/020-vector-role.sh'}
$taskPath=Join-Path $PSScriptRoot 'runtime/postgres-validation/vector-role-result.json'
[IO.File]::WriteAllText($taskPath,($taskResult|ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
$taskResult|ConvertTo-Json -Depth 10
