param(
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Verify')][string]$Phase,
    [string]$BaseUrl = 'http://127.0.0.1:8088',
    [string]$CaseId,
    [string]$StatePath = (Join-Path $PSScriptRoot 'runtime/persistence-probe.json'),
    [string]$ProcessIdPath = (Join-Path $PSScriptRoot 'runtime/api.pid'),
    [string]$ResultPath = (Join-Path $PSScriptRoot 'runtime/persistence-result.json')
)
$ErrorActionPreference='Stop'
$taskPassword=if($env:POI_DEMO_PASSWORD){$env:POI_DEMO_PASSWORD}else{'demo-pass-local'}
function New-ProbeSession([string]$Username) {
    $taskSession=New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $taskBody=@{username=$Username;password=$taskPassword}|ConvertTo-Json -Compress
    $taskIdentity=Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -WebSession $taskSession -ContentType 'application/json' -Body $taskBody
    return @{session=$taskSession;csrf=$taskIdentity.csrfToken}
}
function Get-ProbeJson([string]$Path,$Identity) {
    return Invoke-RestMethod -Uri "$BaseUrl$Path" -WebSession $Identity.session
}
function ConvertTo-ProbeCanonical($Value) {
    if($null -eq $Value) { return $null }
    if($Value -is [System.Collections.IDictionary]) {
        $taskSorted=[ordered]@{}
        foreach($taskKey in ($Value.Keys|Sort-Object)) { $taskSorted[$taskKey]=ConvertTo-ProbeCanonical $Value[$taskKey] }
        return $taskSorted
    }
    if($Value -is [PSCustomObject]) {
        $taskSorted=[ordered]@{}
        foreach($taskProperty in ($Value.PSObject.Properties|Sort-Object Name)) { $taskSorted[$taskProperty.Name]=ConvertTo-ProbeCanonical $taskProperty.Value }
        return $taskSorted
    }
    if($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $taskItems=@(foreach($taskItem in $Value) { ConvertTo-ProbeCanonical $taskItem })
        return ,$taskItems
    }
    return $Value
}
function Get-ProbeDigest($Value) {
    $taskCanonical=ConvertTo-ProbeCanonical $Value|ConvertTo-Json -Depth 100 -Compress
    $taskSha=[System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($taskSha.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskCanonical)))).Replace('-','').ToLowerInvariant() }
    finally { $taskSha.Dispose() }
}
function Get-ProbeProcessId {
    $taskPidPath=$ProcessIdPath
    if(-not(Test-Path -LiteralPath $taskPidPath)){throw 'Native API PID record is required to prove an actual restart.'}
    return [int](Get-Content -LiteralPath $taskPidPath -Raw).Trim()
}
if($Phase -eq 'Prepare') {
    $taskAnalyst=New-ProbeSession 'analyst'
    $taskReviewer=New-ProbeSession 'reviewer'
    if(-not $CaseId) {
        $taskAvailable=(Get-ProbeJson '/api/cases?status=OPEN' $taskAnalyst).items
        $CaseId=($taskAvailable|Sort-Object id -Descending|Select-Object -First 1).id
        if(-not $CaseId){throw 'No open synthetic case is available. Supply -CaseId explicitly.'}
    }
    $taskQuestion=@{question='Prepare a repeatable restart persistence probe using the observed payment evidence.';mode='replay'}|ConvertTo-Json -Compress
    $taskInvestigation=Invoke-RestMethod -Uri "$BaseUrl/api/cases/$CaseId/investigations" -Method Post -WebSession $taskAnalyst.session -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$taskAnalyst.csrf} -Body $taskQuestion -TimeoutSec 100
    $taskCurrent=Get-ProbeJson "/api/cases/$CaseId" $taskReviewer
    $taskKey='persistence-'+[Guid]::NewGuid().ToString()
    $taskDecisionBody=@{investigationId=$taskInvestigation.id;decision='APPROVE';note='Dedicated synthetic restart persistence verification.';expectedVersion=$taskCurrent.version}|ConvertTo-Json -Compress
    $taskDecision=Invoke-RestMethod -Uri "$BaseUrl/api/cases/$CaseId/decisions" -Method Post -WebSession $taskReviewer.session -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$taskReviewer.csrf;'Idempotency-Key'=$taskKey} -Body $taskDecisionBody
    $taskAudit=Get-ProbeJson "/api/cases/$CaseId/audit" $taskReviewer
    $taskState=[ordered]@{schemaVersion=2;phase='PREPARED';createdAt=[DateTime]::UtcNow.ToString('o');baseUrl=$BaseUrl;caseId=$CaseId;investigationId=$taskInvestigation.id;decisionId=$taskDecision.id;caseVersion=$taskDecision.version;caseStatus=$taskDecision.caseStatus;idempotencyKey=$taskKey;decisionBody=$taskDecisionBody;investigationDigest=(Get-ProbeDigest $taskInvestigation);auditDigest=(Get-ProbeDigest $taskAudit);auditCount=@($taskAudit.items).Count;processIdBefore=(Get-ProbeProcessId)}
    [IO.File]::WriteAllText($StatePath,($taskState|ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
    [PSCustomObject]@{status='PREPARED';caseId=$CaseId;investigationId=$taskInvestigation.id;decisionId=$taskDecision.id;statePath=$StatePath;next='Restart only this API at an agreed QA boundary, then run -Phase Verify.'}|ConvertTo-Json
} else {
    if(-not(Test-Path -LiteralPath $StatePath)){throw 'Prepare the persistence probe before restarting.'}
    $taskState=Get-Content -LiteralPath $StatePath -Raw|ConvertFrom-Json
    if($taskState.schemaVersion -ne 2){throw 'Run Prepare with the current canonical-digest probe before restarting.'}
    $taskProcessIdAfter=Get-ProbeProcessId
    if($taskProcessIdAfter -eq $taskState.processIdBefore){throw 'PID is unchanged. This does not prove application restart.'}
    $taskReviewer=New-ProbeSession 'reviewer'
    $taskInvestigation=Get-ProbeJson "/api/investigations/$($taskState.investigationId)" $taskReviewer
    if((Get-ProbeDigest $taskInvestigation) -ne $taskState.investigationDigest){throw 'Stored investigation changed or was lost across restart.'}
    $taskBefore=Get-ProbeJson "/api/cases/$($taskState.caseId)/audit" $taskReviewer
    if((Get-ProbeDigest $taskBefore) -ne $taskState.auditDigest){throw 'Audit entries changed or were lost across restart.'}
    $taskReplay=Invoke-RestMethod -Uri "$BaseUrl/api/cases/$($taskState.caseId)/decisions" -Method Post -WebSession $taskReviewer.session -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$taskReviewer.csrf;'Idempotency-Key'=$taskState.idempotencyKey} -Body $taskState.decisionBody
    if(-not $taskReplay.replayed -or $taskReplay.id -ne $taskState.decisionId -or $taskReplay.version -ne $taskState.caseVersion){throw 'The decision was not replayed from durable storage.'}
    $taskAfter=Get-ProbeJson "/api/cases/$($taskState.caseId)/audit" $taskReviewer
    if((Get-ProbeDigest $taskAfter) -ne $taskState.auditDigest){throw 'Replayed decision unexpectedly appended audit entries.'}
    $taskCase=Get-ProbeJson "/api/cases/$($taskState.caseId)" $taskReviewer
    if($taskCase.version -ne $taskState.caseVersion -or $taskCase.status -ne $taskState.caseStatus){throw 'Replayed decision unexpectedly changed durable case state.'}
    $taskResult=[ordered]@{status='PASS';checkedAt=[DateTime]::UtcNow.ToString('o');caseId=$taskState.caseId;investigationId=$taskState.investigationId;decisionId=$taskReplay.id;processIdBefore=$taskState.processIdBefore;processIdAfter=$taskProcessIdAfter;checks=@('actual API process restart','immutable investigation preserved','audit preserved','durable idempotency replay','no extra audit after replay','case status and version preserved');auditCount=@($taskAfter.items).Count}
    $taskResultPath=$ResultPath
    [IO.File]::WriteAllText($taskResultPath,($taskResult|ConvertTo-Json -Depth 10),(New-Object Text.UTF8Encoding($false)))
    $taskResult|ConvertTo-Json -Depth 10
}
