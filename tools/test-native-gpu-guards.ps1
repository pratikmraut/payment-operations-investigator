# Offline regression checks only. No server starts, stops, sockets or model calls.
$ErrorActionPreference = 'Stop'
$script:gpuGuardChecks = 0
function Assert-GpuGuard([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:gpuGuardChecks++
}

$scripts = @('start-native-ollama.ps1','stop-native-ollama.ps1','native-gpu-lifecycle.ps1','start-native-gpu-demo.ps1','stop-native-gpu-demo.ps1')
foreach ($name in $scripts) {
    $tokens=$null; $errors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name),[ref]$tokens,[ref]$errors)
    Assert-GpuGuard ($errors.Count -eq 0) ('Syntax errors in ' + $name)
}

foreach ($name in @('start-native-ollama.ps1','stop-native-ollama.ps1','native-gpu-lifecycle.ps1')) {
    & {
        param($FileName)
        $tokens=$null; $errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $FileName),[ref]$tokens,[ref]$errors)
        # Load function definitions only; deliberately do not evaluate lifecycle statements.
        foreach ($definition in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
            . ([ScriptBlock]::Create($definition.Extent.Text))
        }
        $functionName=if ($FileName -eq 'native-gpu-lifecycle.ps1') {'Get-GpuUtcTicks'} else {'Get-PoiUtcTicks'}
        $iso='2026-09-13T16:49:36.6082511Z'
        $expected=[long]639249149766082511
        $parsed=('{"startTimeUtc":"' + $iso + '"}' | ConvertFrom-Json).startTimeUtc
        Assert-GpuGuard ((& $functionName $iso) -eq $expected) ($FileName + ': string timestamp lost precision')
        Assert-GpuGuard ((& $functionName $parsed) -eq $expected) ($FileName + ': deserialized timestamp lost precision')
        $typedDate=[DateTime]::Parse($iso,[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::RoundtripKind)
        Assert-GpuGuard ((& $functionName $typedDate) -eq $expected) ($FileName + ': DateTime timestamp lost precision')
        Assert-GpuGuard ((& $functionName ([DateTimeOffset]::Parse($iso))) -eq $expected) ($FileName + ': DateTimeOffset lost precision')
        Assert-GpuGuard ((& $functionName $typedDate.AddTicks(1)) -ne $expected) ($FileName + ': one-tick change was hidden')
        $rejected=$false
        try { $null=& $functionName 'invalid timestamp' } catch { $rejected=$true }
        Assert-GpuGuard $rejected ($FileName + ': malformed timestamp accepted')
        $rejected=$false
        try { $null=& $functionName '2026-09-13T16:49:36.6082511' } catch { $rejected=$true }
        Assert-GpuGuard $rejected ($FileName + ': timezone-free string accepted')
        $rejected=$false
        try { $null=& $functionName ([DateTime]::SpecifyKind($typedDate,[DateTimeKind]::Unspecified)) } catch { $rejected=$true }
        Assert-GpuGuard $rejected ($FileName + ': timezone-free DateTime accepted')

        if ($FileName -ne 'native-gpu-lifecycle.ps1') {
            $poiPort=11435; $poiExecutable='C:\fixture\ollama.exe'
            $mockProcess=[pscustomobject]@{Id=999; Path=$poiExecutable; StartTime=$typedDate}
            function Get-Process { param($Id,$ErrorAction) return $mockProcess }
            $receipt=[pscustomobject]@{schemaVersion=1; port=11435; hostAddress='127.0.0.1'; executablePath=$poiExecutable; processId=999; startTimeUtc=$parsed}
            Assert-GpuGuard ($null -ne (Get-PoiVerifiedProcess $receipt)) ($FileName + ': matching process rejected')
            $mockProcess.StartTime=$typedDate.AddTicks(1)
            $rejected=$false
            try { $rejected=$null -eq (Get-PoiVerifiedProcess $receipt) } catch { $rejected=$true }
            Assert-GpuGuard $rejected ($FileName + ': reused PID start-time mismatch accepted')
        } else {
            $currentSpecs=@([pscustomobject]@{role='web'; port=5178; markers=@('preview','5178')})
            function Get-GpuSpecs {
                param([ValidateSet(5178,5179)][int]$WebPort=5178)
                return [pscustomobject]@{role='web'; port=$WebPort; markers=@('preview',([string]$WebPort))}
            }
            $mainSpec=Get-GpuReceiptSpec ([pscustomobject]@{role='web'; port=5178}) $currentSpecs
            Assert-GpuGuard ($mainSpec.port -eq 5178) 'Main website receipt did not use port 5178'
            $legacySpec=Get-GpuReceiptSpec ([pscustomobject]@{role='web'; port=5179}) $currentSpecs
            Assert-GpuGuard ($legacySpec.port -eq 5179 -and $legacySpec.markers -contains '5179') 'Former website receipt lost its original port identity'
            Assert-GpuGuard ($currentSpecs[0].port -eq 5178 -and $currentSpecs[0].markers -contains '5178') 'Legacy receipt handling mutated the main website specification'
            foreach ($unknown in @([pscustomobject]@{role='web'; port=5180}, [pscustomobject]@{role='worker'; port=5179})) {
                $rejected=$false
                try { $null=Get-GpuReceiptSpec $unknown $currentSpecs } catch { $rejected=$true }
                Assert-GpuGuard $rejected 'Native stack accepted an unknown receipt port or role'
            }
            & {
                $stopProbe=@{called=$false}
                function Get-GpuVerifiedProcess { param($Record,$Spec) return [pscustomobject]@{processId=999} }
                function Get-GpuListeners { param($Port) return [pscustomobject]@{OwningProcess=1000; LocalAddress='127.0.0.1'} }
                function Stop-Process { param($InputObject,$Force,$ErrorAction) $stopProbe.called=$true }
                $rejected=$false
                try { Stop-GpuService ([pscustomobject]@{role='web'; port=5178; server=@{}; launcher=@{}}) ([pscustomobject]@{role='web'; port=5178}) } catch { $rejected=$true }
                Assert-GpuGuard $rejected 'Native stop accepted an unrelated owner on the main website port'
                Assert-GpuGuard (-not $stopProbe.called) 'Native stop attempted to stop an unrelated main website listener'
            }
            $probeState=@{calls=0; disappear=$true; cimThrows=$false}
            function Get-Process {
                param($Id,$ErrorAction)
                $probeState.calls++
                if ($probeState.disappear -and $probeState.calls -gt 1) { return $null }
                return [pscustomobject]@{Id=$Id; StartTime=$typedDate}
            }
            function Get-CimInstance {
                param($ClassName,$Filter,$ErrorAction)
                if ($probeState.cimThrows) { throw 'Mock process exited during CIM lookup' }
                return $null
            }
            Assert-GpuGuard ($null -eq (Get-GpuProcessRecord 999)) 'Native stack rejected a process that exited before CIM lookup'
            $probeState.calls=0; $probeState.disappear=$false
            $rejected=$false
            try { $null=Get-GpuProcessRecord 999 } catch { $rejected=$true }
            Assert-GpuGuard $rejected 'Native stack accepted a live process with unverifiable identity'
            $probeState.calls=0; $probeState.disappear=$true; $probeState.cimThrows=$true
            Assert-GpuGuard ($null -eq (Get-GpuProcessRecord 999)) 'Native stack did not handle a CIM failure after process exit'
            $probeState.calls=0; $probeState.disappear=$false
            $rejected=$false
            try { $null=Get-GpuProcessRecord 999 } catch { $rejected=$true }
            Assert-GpuGuard $rejected 'Native stack ignored a CIM failure for a live process'
            $spec=[pscustomobject]@{allowedImages=@('C:\fixture\java.exe'); markers=@('-jar','C:\fixture\api.jar')}
            $record=[pscustomobject]@{processId=999; executablePath='C:\fixture\java.exe'; commandLine='java -jar C:\fixture\api.jar'; startTimeUtc=$parsed}
            $mockRecord=[pscustomobject]@{processId=999; executablePath=$record.executablePath; commandLine=$record.commandLine; startTimeUtc=$iso}
            function Get-GpuProcessRecord { param($ServerId) return $mockRecord }
            Assert-GpuGuard ($null -ne (Get-GpuVerifiedProcess $record $spec)) 'Native stack matching process rejected'
            $mockRecord.startTimeUtc=$typedDate.AddTicks(1).ToString('O')
            $rejected=$false
            try { $null=Get-GpuVerifiedProcess $record $spec } catch { $rejected=$true }
            Assert-GpuGuard $rejected 'Native stack accepted reused PID with changed start time'
            $mockRecord.startTimeUtc=$iso
            $mockRecord.commandLine='java -jar C:\fixture\unrelated.jar'
            $rejected=$false
            try { $null=Get-GpuVerifiedProcess $record $spec } catch { $rejected=$true }
            Assert-GpuGuard $rejected 'Native stack accepted changed command line'
        }
    } $name
}
[pscustomobject]@{status='passed'; checks=$script:gpuGuardChecks; processStarts=0; processStops=0; networkCalls=0; modelCalls=0} | ConvertTo-Json
