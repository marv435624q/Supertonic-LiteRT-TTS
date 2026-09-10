param(
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path))
)
$ErrorActionPreference = 'Stop'
$Version = '2.2.0'
$Url = "https://dl.google.com/android/maven2/com/google/ai/edge/litert/litert/$Version/litert-$Version.aar"
$Work = Join-Path $ProjectRoot '.cache\litert-stock-compare'
$Aar = Join-Path $Work "litert-$Version.aar"
$Zip = Join-Path $Work "litert-$Version.zip"
$Extract = Join-Path $Work "litert-$Version"
$Custom = Join-Path $ProjectRoot 'litert\arm64-v8a\libLiteRt.so'
$Report = Join-Path $ProjectRoot 'LITERT_STOCK_CUSTOM_REPORT.txt'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
if (!(Test-Path $Custom)) { throw "Custom ARM64 runtime missing: $Custom" }
if (!(Test-Path $Aar)) { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Aar }
Copy-Item $Aar $Zip -Force
if (Test-Path $Extract) { Remove-Item -Recurse -Force $Extract }
Expand-Archive -Path $Zip -DestinationPath $Extract -Force
$Stock = Join-Path $Extract 'jni\arm64-v8a\libLiteRt.so'
if (!(Test-Path $Stock)) { throw "Stock AAR does not contain jni/arm64-v8a/libLiteRt.so" }
function Sha($p) { (Get-FileHash -Algorithm SHA256 $p).Hash.ToLowerInvariant() }
$lines = @()
$lines += "LiteRT stock/custom comparison"
$lines += "Version=$Version"
$lines += "StockURL=$Url"
$lines += "Stock=$Stock"
$lines += "Custom=$Custom"
$lines += "StockSize=$((Get-Item $Stock).Length)"
$lines += "CustomSize=$((Get-Item $Custom).Length)"
$lines += "StockSHA256=$(Sha $Stock)"
$lines += "CustomSHA256=$(Sha $Custom)"
$ndkRoots = @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT)
if ($env:ANDROID_HOME) { $ndkRoots += (Get-ChildItem (Join-Path $env:ANDROID_HOME 'ndk') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName) }
$readelf = $null
foreach ($n in $ndkRoots) {
    if (!$n) { continue }
    $r = Get-ChildItem $n -Recurse -Filter llvm-readelf.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
    if ($r) { $readelf = $r; break }
}
if ($readelf) {
    $stockDyn = & $readelf --dyn-syms -W $Stock 2>&1 | Out-String
    $customDyn = & $readelf --dyn-syms -W $Custom 2>&1 | Out-String
    $required = @('TfLiteXNNPackDelegateCreate','TfLiteInterpreterGetSignatureRunner','TfLiteSignatureRunnerInvoke','TfLiteInterpreterModifyGraphWithDelegateForSignature','TfLiteInterpreterRemoveAllDelegates','SupertonicXnnpackWeightCacheProviderCreate','SupertonicXnnpackWeightCacheProviderLoadOrStartBuild','SupertonicXnnpackWeightCacheProviderStopBuild','SupertonicXnnpackWeightCacheProviderDelete')
    foreach ($sym in $required) {
        $lines += "Symbol[$sym].stock=$([int]($stockDyn -match [regex]::Escape($sym)))"
        $lines += "Symbol[$sym].custom=$([int]($customDyn -match [regex]::Escape($sym)))"
    }
    $lines += ''
    $lines += '=== STOCK DYNAMIC ==='
    $lines += (& $readelf -d -W $Stock 2>&1 | Out-String)
    $lines += '=== CUSTOM DYNAMIC ==='
    $lines += (& $readelf -d -W $Custom 2>&1 | Out-String)
} else {
    $lines += 'llvm-readelf=not-found; set ANDROID_NDK_HOME for ELF/symbol comparison'
}
$lines | Out-File $Report -Encoding utf8 -Width 4096
Write-Host "Report: $Report"
