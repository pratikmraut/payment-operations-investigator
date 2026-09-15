param([ValidateSet('GPU','CPU','All')][string]$Mode = 'GPU')
$ErrorActionPreference = 'Stop'
if ($Mode -in @('GPU','All')) { & (Join-Path $PSScriptRoot 'stop-native-gpu-demo.ps1') }
if ($Mode -in @('CPU','All')) { & (Join-Path $PSScriptRoot 'stop-cpu-demo.ps1') }
