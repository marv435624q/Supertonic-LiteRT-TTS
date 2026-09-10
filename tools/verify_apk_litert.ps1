param(
  [Parameter(Mandatory=$true)][string]$ApkPath,
  [Parameter(Mandatory=$true)][string]$RuntimePath,
  [string]$ReadElfPath = ""
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (!(Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
if (!(Test-Path $RuntimePath)) { throw "Runtime not found: $RuntimePath" }
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("supertonic-apk-native-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  $zip = [IO.Compression.ZipFile]::OpenRead($ApkPath)
  try {
    $rtEntry = $zip.GetEntry('lib/arm64-v8a/libLiteRt.so')
    $spEntry = $zip.GetEntry('lib/arm64-v8a/libspeech_android.so')
    if ($null -eq $rtEntry) { throw 'APK missing lib/arm64-v8a/libLiteRt.so' }
    if ($null -eq $spEntry) { throw 'APK missing lib/arm64-v8a/libspeech_android.so' }
    $rtOut = Join-Path $tmp 'libLiteRt.so'
    $spOut = Join-Path $tmp 'libspeech_android.so'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($rtEntry, $rtOut, $true)
    [IO.Compression.ZipFileExtensions]::ExtractToFile($spEntry, $spOut, $true)
  } finally { $zip.Dispose() }

  $srcHash = (Get-FileHash -Algorithm SHA256 $RuntimePath).Hash.ToLowerInvariant()
  $apkHash = (Get-FileHash -Algorithm SHA256 $rtOut).Hash.ToLowerInvariant()
  if ($srcHash -ne $apkHash) {
    throw "APK libLiteRt.so does not match the freshly built runtime. source=$srcHash apk=$apkHash"
  }
  Write-Host "[APK-NATIVE] libLiteRt.so hash match: $apkHash"

  if ($ReadElfPath -and (Test-Path $ReadElfPath)) {
    $rtDyn = & $ReadElfPath --wide --dyn-syms $rtOut 2>&1
    if ($LASTEXITCODE -ne 0) { throw "llvm-readelf failed for packaged libLiteRt.so" }
    if (-not ($rtDyn -match '\bTfLiteInterpreterRemoveAllDelegates\b')) {
        throw "APK libLiteRt.so does not export TfLiteInterpreterRemoveAllDelegates"
    }
    if ($rtDyn -match '\bTfLiteInterpreterRemoveDelegatesForSignature\b') {
        throw "SAFE-RECOVERY APK unexpectedly contains experimental targeted-removal ABI"
    }
    Write-Host '[APK-NATIVE] SAFE-RECOVERY ABI verified: RemoveAllDelegates present; experimental targeted-removal absent'
  } else {
    Write-Host '[APK-NATIVE] llvm-readelf not found; skipped dynsym check (hash check passed)'
  }
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
