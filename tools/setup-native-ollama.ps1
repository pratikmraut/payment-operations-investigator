param(
    [switch]$SkipDownload,
    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string]$ModelContainer = 'payment-operations-investigator-ollama-1'
)
$ErrorActionPreference = 'Stop'
$gpuProject = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$gpuRoot = Join-Path $gpuProject 'runtime\native-ollama'
$gpuVersion = '0.34.0'
$gpuArchive = Join-Path $gpuRoot 'ollama-windows-amd64-0.34.0.zip'
$gpuInstall = Join-Path $gpuRoot $gpuVersion
$gpuExpectedSha = 'a7dd1b174f39d3d1b8a25d4cbc86045d0e190b17187bfdcbe2f2ee3b5a11470e'
$gpuExpectedBytes = 1469375054
$gpuUrl = 'https://github.com/ollama/ollama/releases/download/v0.34.0/ollama-windows-amd64.zip'
function Assert-GpuSetupPath([string]$Candidate) {
    $absolute = [IO.Path]::GetFullPath($Candidate)
    $prefix = $gpuProject.TrimEnd('\') + '\'
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Setup path leaves this project.' }
    $current = $gpuProject
    foreach ($segment in $absolute.Substring($prefix.Length).Split('\')) {
        $current = Join-Path $current $segment
        if (Test-Path -LiteralPath $current) {
            if (((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Setup refuses a junction or symbolic link inside its destination.'
            }
        }
    }
}
Assert-GpuSetupPath $gpuRoot
Assert-GpuSetupPath $gpuArchive
Assert-GpuSetupPath $gpuInstall
New-Item -ItemType Directory -Path $gpuRoot -Force | Out-Null

if (-not (Test-Path -LiteralPath $gpuArchive) -or (Get-Item -LiteralPath $gpuArchive).Length -ne $gpuExpectedBytes) {
    if ($SkipDownload) { throw 'The complete pinned Ollama archive is absent. Run without -SkipDownload to download it.' }
    & curl.exe --fail --location --retry 2 --connect-timeout 20 --max-time 1800 --continue-at - --output $gpuArchive $gpuUrl
    if ($LASTEXITCODE -ne 0) { throw 'Official Ollama download failed. A partial archive can be resumed on the next run.' }
}
if ((Get-FileHash -LiteralPath $gpuArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $gpuExpectedSha) {
    throw 'The archive does not match the official pinned release SHA256. It will not be extracted or executed.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$gpuZip = [IO.Compression.ZipFile]::OpenRead($gpuArchive)
try {
    $gpuPrefix = [IO.Path]::GetFullPath($gpuInstall).TrimEnd('\') + '\'
    foreach ($gpuEntry in $gpuZip.Entries) {
        $gpuDestination = [IO.Path]::GetFullPath((Join-Path $gpuInstall $gpuEntry.FullName))
        if (-not $gpuDestination.StartsWith($gpuPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Archive entry is outside the isolated installation directory.'
        }
        Assert-GpuSetupPath $gpuDestination
    }
    $gpuExtractionMarker = Join-Path $gpuInstall 'extraction-complete.json'
    $gpuExtractionComplete = $false
    if (Test-Path -LiteralPath $gpuExtractionMarker) {
        $gpuPreviousExtraction = Get-Content -LiteralPath $gpuExtractionMarker -Raw | ConvertFrom-Json
        $gpuExtractionComplete = $gpuPreviousExtraction.archiveSha256 -eq $gpuExpectedSha
        foreach ($gpuEntry in $gpuZip.Entries) {
            if (-not $gpuEntry.Name) { continue }
            $gpuDestination = Join-Path $gpuInstall $gpuEntry.FullName
            if (-not (Test-Path -LiteralPath $gpuDestination) -or (Get-Item -LiteralPath $gpuDestination).Length -ne $gpuEntry.Length) {
                $gpuExtractionComplete = $false
            }
        }
    }
    if (-not $gpuExtractionComplete) {
        $gpuRunning = @(Get-Process -Name ollama -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($gpuPrefix, [StringComparison]::OrdinalIgnoreCase) })
        if ($gpuRunning.Count -gt 0) { throw 'Stop this experimental runtime before repairing its extracted files.' }
        New-Item -ItemType Directory -Path $gpuInstall -Force | Out-Null
        foreach ($gpuEntry in $gpuZip.Entries) {
            if (-not $gpuEntry.Name) { continue }
            $gpuDestination = [IO.Path]::GetFullPath((Join-Path $gpuInstall $gpuEntry.FullName))
            New-Item -ItemType Directory -Path (Split-Path -Parent $gpuDestination) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($gpuEntry, $gpuDestination, $true)
        }
        @{ archiveSha256=$gpuExpectedSha; extractedAt=[DateTime]::UtcNow.ToString('o') } | ConvertTo-Json |
            Set-Content -LiteralPath $gpuExtractionMarker -Encoding UTF8
    }
} finally { $gpuZip.Dispose() }

# Copy only this model from the existing local store. No model pull or baseline mutation.
$gpuManifestDir = Join-Path $gpuRoot 'models\manifests\registry.ollama.ai\library\qwen3'
$gpuBlobs = Join-Path $gpuRoot 'models\blobs'
Assert-GpuSetupPath $gpuManifestDir
Assert-GpuSetupPath $gpuBlobs
New-Item -ItemType Directory -Path $gpuManifestDir,$gpuBlobs -Force | Out-Null
$gpuManifestPath = Join-Path $gpuManifestDir '8b'
Assert-GpuSetupPath $gpuManifestPath
& docker cp "${ModelContainer}:/root/.ollama/models/manifests/registry.ollama.ai/library/qwen3/8b" $gpuManifestPath
if ($LASTEXITCODE -ne 0) { throw 'Cannot copy the existing qwen3:8b manifest.' }
$gpuManifest = Get-Content -LiteralPath $gpuManifestPath -Raw | ConvertFrom-Json
$gpuCopied = @()
foreach ($gpuLayer in (@($gpuManifest.config) + @($gpuManifest.layers))) {
    if ($gpuLayer.digest -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Unexpected digest in model manifest.' }
    $gpuBlobName = $gpuLayer.digest.Replace(':', '-')
    $gpuBlobPath = Join-Path $gpuBlobs $gpuBlobName
    Assert-GpuSetupPath $gpuBlobPath
    if (-not (Test-Path -LiteralPath $gpuBlobPath)) {
        & docker cp "${ModelContainer}:/root/.ollama/models/blobs/$gpuBlobName" $gpuBlobPath
        if ($LASTEXITCODE -ne 0) { throw 'Cannot copy an existing model blob.' }
    }
    if ((Get-FileHash -LiteralPath $gpuBlobPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $gpuLayer.digest.Substring(7)) {
        throw 'Copied model blob checksum mismatch.'
    }
    $gpuCopied += [ordered]@{ digest=$gpuLayer.digest; bytes=(Get-Item -LiteralPath $gpuBlobPath).Length }
}
$gpuVulkanFiles = @(Get-ChildItem -LiteralPath $gpuInstall -File -Recurse | Where-Object { $_.Name -match 'vulkan' } | ForEach-Object { $_.FullName.Substring($gpuInstall.Length + 1) })
$gpuReceipt = [ordered]@{
    version=$gpuVersion; source=$gpuUrl; archiveSha256=$gpuExpectedSha
    executableSha256=(Get-FileHash -LiteralPath (Join-Path $gpuInstall 'ollama.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
    model='qwen3:8b'; modelDigest=(Get-FileHash -LiteralPath $gpuManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    modelBlobs=$gpuCopied; vulkanFiles=$gpuVulkanFiles
    baselineModified=$false; runtimeStarted=$false; recordedAt=[DateTime]::UtcNow.ToString('o')
}
$gpuReceipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $gpuRoot 'setup-receipt.json') -Encoding UTF8
Write-Output ('Prepared isolated Ollama ' + $gpuVersion + ' and verified qwen3:8b weights.')
Write-Output ('Vulkan-named library files: ' + $gpuVulkanFiles.Count + '. Device discovery and inference are separate checks.')
Write-Output 'No PATH, user environment, driver, startup task or baseline service was changed.'
