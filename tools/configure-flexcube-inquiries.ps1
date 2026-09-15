param(
    [Parameter(Mandatory)][uri]$ServiceBaseUrl,
    [string]$ServiceUser = '',
    [string]$PostingDate = '',
    [string]$SourceTimezone = 'UNKNOWN',
    [switch]$EnableBankCalls,
    [string]$ConfigPath = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-gpu-lifecycle.ps1')
if (-not $ConfigPath) { $ConfigPath = Join-Path $gpuPrivateRoot 'application.properties' }
$ConfigPath = Assert-GpuPath $ConfigPath
if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'The existing project application.properties file is required.' }
if ($ServiceBaseUrl.Scheme -ne 'https' -or -not $ServiceBaseUrl.Host -or $ServiceBaseUrl.UserInfo -or $ServiceBaseUrl.Query -or $ServiceBaseUrl.Fragment -or $ServiceBaseUrl.IsLoopback) {
    throw 'Supply the fixed HTTPS FCAPIService base URL without credentials, query or fragment.'
}
if ($ServiceUser -and $ServiceUser -notmatch '^[A-Za-z0-9_.@-]{1,100}$') { throw 'Invalid configured FLEXCUBE service user.' }
if ($PostingDate) {
    if ($PostingDate -notmatch '^[0-9]{8}$') { throw 'Use the confirmed bank posting date in yyyyMMdd format.' }
    $null = [datetime]::ParseExact($PostingDate, 'yyyyMMdd', [Globalization.CultureInfo]::InvariantCulture)
}
if ($EnableBankCalls -and (-not $PostingDate -or -not $ServiceUser)) { throw 'Enabling bank calls requires the confirmed service user and bank posting date.' }
if ($SourceTimezone -notmatch '^[A-Za-z0-9_+:/.-]{1,100}$') { throw 'Use UNKNOWN or a Java source timezone such as Asia/Kolkata.' }
$base = $ServiceBaseUrl.AbsoluteUri.TrimEnd('/')
if (-not $base.EndsWith('/FCAPIService', [StringComparison]::Ordinal)) { throw 'The base URL must end with /FCAPIService.' }
$settings = [ordered]@{
    'poi.payment-discovery.wire-format' = 'FLEXCUBE'
    'poi.payment-discovery.bank-url' = "$base/NEFTPaymentDiscoveryInquiryService/processRequest"
    'poi.payment-discovery.bank-enabled' = $EnableBankCalls.IsPresent.ToString().ToLowerInvariant()
    'poi.payment-discovery.mode' = $(if ($EnableBankCalls) { 'BANK_API' } else { 'MOCK' })
    'poi.case-evidence.wire-format' = 'FLEXCUBE'
    'poi.case-evidence.api-url' = "$base/NEFTEvidenceInquiryService/processRequest"
    'poi.case-evidence.api-enabled' = $EnableBankCalls.IsPresent.ToString().ToLowerInvariant()
    'poi.flexcube.user-id' = $ServiceUser
    'poi.flexcube.channel' = 'API'
    'poi.flexcube.posting-date' = $PostingDate
    'poi.flexcube.posting-date-policy' = 'EXPLICIT'
    'poi.flexcube.posting-zone' = 'Asia/Kolkata'
    'poi.flexcube.source-timezone' = $SourceTimezone
}
$original = [IO.File]::ReadAllText($ConfigPath)
$lines = [Collections.Generic.List[string]]::new()
foreach ($line in ($original -split '\r?\n')) {
    $key = if ($line -match '^\s*([^#!\s][^=:\s]*)\s*[=:]') { $Matches[1] } else { '' }
    if (-not $settings.Contains($key)) { $lines.Add($line) }
}
foreach ($entry in $settings.GetEnumerator()) { $lines.Add("$($entry.Key)=$($entry.Value)") }
$backup = Assert-GpuPath ($ConfigPath + '.before-flexcube-' + [guid]::NewGuid().ToString('N') + '.bak')
$temporary = Assert-GpuPath ($ConfigPath + '.flexcube-' + [guid]::NewGuid().ToString('N') + '.tmp')
[IO.File]::WriteAllText($backup, $original, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($temporary, ($lines -join "`r`n"), [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $temporary -Destination $ConfigPath -Force
[pscustomobject]@{
    configured = $true
    bankCallsEnabled = $EnableBankCalls.IsPresent
    postingDatePolicy = 'EXPLICIT'
    restartRequired = $true
    networkCallsMade = 0
    config = $ConfigPath
    backup = $backup
} | ConvertTo-Json
