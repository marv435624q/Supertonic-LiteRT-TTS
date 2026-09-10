param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

if (-not (Test-Path Env:FORCE_ORT_REBUILD)) { $env:FORCE_ORT_REBUILD = '0' }

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$OrtVersion = '1.28.0'
$OrtTag = 'v1.28.0'
$QairtBuild = '2.44.0.260225'
$AndroidApi = 27
$PreferredNdk = '29.0.14206865'
$TemplateAar = Join-Path $Root 'sdk\libs\onnxruntime-android-qnn-1.28.0-hta.aar'
$OutputAar = Join-Path $Root 'sdk\libs\onnxruntime-android-qnn-xnnpack-1.28.0-hta.aar'
$ShaFile = "$OutputAar.sha256"
$BuildInfo = "$OutputAar.buildinfo.txt"
$HtaPatch = Join-Path $Root 'third_party\onnxruntime\ORT-1.28.0-QNN-HTA.patch'
$PublishedLogFile = Join-Path $Root 'BUILD_CUSTOM_ORT_QNN_XNNPACK.log'
$LogFile = $PublishedLogFile

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "=== $Message ===" -ForegroundColor Cyan
}

function Fail([string]$Message) {
    throw $Message
}

function Find-ExistingPath([string[]]$Candidates, [string]$RequiredChild) {
    foreach ($c in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        $expanded = [Environment]::ExpandEnvironmentVariables($c)
        if (Test-Path (Join-Path $expanded $RequiredChild)) {
            return (Resolve-Path $expanded).Path
        }
    }
    return $null
}

function Invoke-Checked([string]$Exe, [string[]]$ArgumentList, [string]$WorkingDirectory = '') {
    if ($WorkingDirectory) {
        Push-Location $WorkingDirectory
    }
    try {
        Write-Host "> $Exe $($ArgumentList -join ' ')"
        & $Exe @ArgumentList
        if ($LASTEXITCODE -ne 0) {
            Fail ("Command failed with exit code {0}: {1} {2}" -f $LASTEXITCODE, $Exe, ($ArgumentList -join ' '))
        }
    } finally {
        if ($WorkingDirectory) { Pop-Location }
    }
}


function Get-AarEntryNames([string]$AarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        return @($zip.Entries | ForEach-Object { $_.FullName })
    } finally {
        $zip.Dispose()
    }
}

function Test-AarRequiredEntries([string]$AarPath, [switch]$NormalizeForCheck) {
    if (-not (Test-Path $AarPath)) { return $false }
    $names = Get-AarEntryNames $AarPath
    if ($NormalizeForCheck) {
        $names = @($names | ForEach-Object { $_.Replace('\','/') })
    }
    foreach ($required in @(
        'classes.jar',
        'jni/arm64-v8a/libonnxruntime.so',
        'jni/arm64-v8a/libonnxruntime4j_jni.so'
    )) {
        if ($names -notcontains $required) { return $false }
    }
    return $true
}

function Update-AarMetadataHash([string]$AarPath, [string]$ShaPath, [string]$InfoPath) {
    $hash = (Get-FileHash -Algorithm SHA256 $AarPath).Hash.ToLowerInvariant()
    "$hash  $(Split-Path $AarPath -Leaf)" | Set-Content -Encoding ascii $ShaPath

    if (Test-Path $InfoPath) {
        $lines = @(Get-Content $InfoPath)
        $updated = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^aar_sha256=') {
                $lines[$i] = "aar_sha256=$hash"
                $updated = $true
            }
        }
        if (-not $updated) { $lines += "aar_sha256=$hash" }
        $lines | Set-Content -Encoding ascii $InfoPath
    }
    return $hash
}

function Normalize-CachedAarPathsIfPossible {
    if (-not (Test-Path $OutputAar)) { return $false }
    if (Test-AarRequiredEntries $OutputAar) { return $true }
    if (-not (Test-AarRequiredEntries $OutputAar -NormalizeForCheck)) { return $false }

    Write-Host "[FIX] Cached AAR has Windows-style ZIP entry separators. Repacking with forward-slash AAR paths..."

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmp = "$OutputAar.repack.tmp"
    if (Test-Path $tmp) { Remove-Item -Force $tmp }

    $srcZip = [System.IO.Compression.ZipFile]::OpenRead($OutputAar)
    $dstStream = [System.IO.File]::Open($tmp, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $dstZip = [System.IO.Compression.ZipArchive]::new(
        $dstStream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    try {
        $seen = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($entry in $srcZip.Entries) {
            $name = $entry.FullName.Replace('\','/')
            if (-not $seen.Add($name)) { continue }
            if ($name.EndsWith('/')) {
                [void]$dstZip.CreateEntry($name)
                continue
            }

            $newEntry = $dstZip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
            $inStream = $entry.Open()
            $outStream = $newEntry.Open()
            try {
                $inStream.CopyTo($outStream)
            } finally {
                $outStream.Dispose()
                $inStream.Dispose()
            }
        }
    } finally {
        $dstZip.Dispose()
        $dstStream.Dispose()
        $srcZip.Dispose()
    }

    Move-Item -Force $tmp $OutputAar
    $newHash = Update-AarMetadataHash $OutputAar $ShaFile $BuildInfo

    if (-not (Test-AarRequiredEntries $OutputAar)) {
        Fail 'Cached AAR path normalization completed but required forward-slash entries are still missing.'
    }

    Write-Host "[OK] Cached AAR repaired without rebuilding ORT."
    Write-Host "     SHA256: $newHash"
    return $true
}

function Write-RepackedAarWithNativeCore([string]$TemplatePath, [string]$NativeSoPath, [string]$DestinationPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    if (-not (Test-AarRequiredEntries $TemplatePath)) {
        Fail 'Template custom ORT AAR does not contain the required standard AAR entries.'
    }

    $tmp = "$DestinationPath.new"
    if (Test-Path $tmp) { Remove-Item -Force $tmp }

    $srcZip = [System.IO.Compression.ZipFile]::OpenRead($TemplatePath)
    $dstStream = [System.IO.File]::Open($tmp, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $dstZip = [System.IO.Compression.ZipArchive]::new(
        $dstStream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    try {
        $seen = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($entry in $srcZip.Entries) {
            $name = $entry.FullName.Replace('\','/')
            if (-not $seen.Add($name)) { continue }

            if ($name.EndsWith('/')) {
                [void]$dstZip.CreateEntry($name)
                continue
            }

            $newEntry = $dstZip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
            $outStream = $newEntry.Open()
            try {
                if ($name -eq 'jni/arm64-v8a/libonnxruntime.so') {
                    $nativeStream = [System.IO.File]::OpenRead($NativeSoPath)
                    try { $nativeStream.CopyTo($outStream) } finally { $nativeStream.Dispose() }
                } else {
                    $inStream = $entry.Open()
                    try { $inStream.CopyTo($outStream) } finally { $inStream.Dispose() }
                }
            } finally {
                $outStream.Dispose()
            }
        }
    } finally {
        $dstZip.Dispose()
        $dstStream.Dispose()
        $srcZip.Dispose()
    }

    if (Test-Path $DestinationPath) { Remove-Item -Force $DestinationPath }
    Move-Item -Force $tmp $DestinationPath

    if (-not (Test-AarRequiredEntries $DestinationPath)) {
        Fail 'Repacked AAR is missing required forward-slash entries.'
    }
}


function Test-ArtifactCache {
    if ($Force -or $env:FORCE_ORT_REBUILD -eq '1') { return $false }
    if (-not (Test-Path $OutputAar) -or -not (Test-Path $ShaFile) -or -not (Test-Path $BuildInfo)) { return $false }

    $expected = ((Get-Content $ShaFile -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 $OutputAar).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { return $false }

    $info = Get-Content $BuildInfo -Raw
    $metadataOk = ($info -match 'source_tag=v1\.28\.0' -and
                   $info -match 'use_qnn=static_lib' -and
                   $info -match 'use_xnnpack=1' -and
                   $info -match 'hta_patch=1')
    if (-not $metadataOk) { return $false }

    if (Test-AarRequiredEntries $OutputAar) { return $true }
    if (Normalize-CachedAarPathsIfPossible) { return $true }

    return $false
}

Write-Host "Supertonic custom ORT native-core builder" -ForegroundColor Green
Write-Host "Target: ORT $OrtVersion / Android arm64-v8a / QNN static + HTA patch + XNNPACK [REV32.6]"
Write-Host "Log   : $LogFile"

if (Test-ArtifactCache) {
    Write-Host "[OK] Existing QNN+XNNPACK custom AAR passes SHA/build-info/ZIP-structure validation."
    Write-Host "     $OutputAar"
    exit 0
}

if (-not (Test-Path $TemplateAar)) { Fail "Template custom ORT AAR missing: $TemplateAar" }
if (-not (Test-Path $HtaPatch)) { Fail "HTA patch missing: $HtaPatch" }

$git = (Get-Command git.exe -ErrorAction SilentlyContinue)
if (-not $git) { $git = (Get-Command git -ErrorAction SilentlyContinue) }
if (-not $git) { Fail 'Git for Windows is required (git.exe not found on PATH).' }
$GitExe = $git.Source

$pyCmd = $null
$pyPrefix = @()
$py = Get-Command py.exe -ErrorAction SilentlyContinue
if ($py) {
    $pyCmd = $py.Source
    $pyPrefix = @('-3')
} else {
    $py = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $py) { $py = Get-Command python -ErrorAction SilentlyContinue }
    if ($py) { $pyCmd = $py.Source }
}
if (-not $pyCmd) { Fail 'Windows Python 3 is required (py.exe/python.exe not found).' }

$LocalAndroidSdk = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { '' }
$AndroidSdk = Find-ExistingPath @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    $LocalAndroidSdk
) 'platforms'
if (-not $AndroidSdk) { Fail 'Android SDK not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or install Android Studio SDK.' }

$ndkCandidates = @()
$preferred = Join-Path $AndroidSdk "ndk\$PreferredNdk"
if (Test-Path (Join-Path $preferred 'build\cmake\android.toolchain.cmake')) { $ndkCandidates += $preferred }
$ndkRoot = Join-Path $AndroidSdk 'ndk'
if (Test-Path $ndkRoot) {
    $ndkCandidates += @(Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | ForEach-Object { $_.FullName })
}
$AndroidNdk = $ndkCandidates | Where-Object { Test-Path (Join-Path $_ 'build\cmake\android.toolchain.cmake') } | Select-Object -First 1
if (-not $AndroidNdk) { Fail "Android NDK not found under $ndkRoot. This project normally uses NDK $PreferredNdk." }

$QairtRoot = Find-ExistingPath @(
    $env:SUPERTONIC_QAIRT_ROOT,
    $env:QNN_SDK_ROOT,
    $env:QAIRT_SDK_ROOT,
    'C:\platform-tools\qairt-2.44\qairt\2.44.0.260225',
    'C:\Qualcomm\AIStack\QAIRT\2.44.0.260225'
) 'lib\aarch64-android\libQnnHta.so'
if (-not $QairtRoot) {
    Fail "Full QAIRT $QairtBuild SDK not found. Expected lib\aarch64-android\libQnnHta.so. Set SUPERTONIC_QAIRT_ROOT or QNN_SDK_ROOT."
}

$CacheBase = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA 'SupertonicLiteRT\ort128-qnn-xnnpack-hta'
} else {
    Join-Path $env:TEMP 'SupertonicLiteRT-ort128-qnn-xnnpack-hta'
}
New-Item -ItemType Directory -Force -Path $CacheBase | Out-Null

# CMake/FetchContent writes the absolute SUBST drive into the top-level cache,
# nested _deps subbuild caches, generated *.cmake files and Ninja files. A build
# tree is therefore NOT portable between drive letters. Worse, after one failed
# cross-drive configure the top-level CMakeCache can be rewritten to the new
# drive while nested FetchContent state still points at the old drive. Looking
# only at CMAKE_CACHEFILE_DIR is not sufficient.
#
# Keep a tiny physical-path stamp outside the generated build tree. Once a drive
# letter is chosen successfully, future runs prefer exactly that letter. Legacy
# build trees created before this stamp existed are cleaned ONCE. src + pyenv are
# kept, so this does not re-clone ORT or recreate/download the Python toolchain.
$PhysicalBuildCache = Join-Path $CacheBase 'build\Release\CMakeCache.txt'
$DriveStamp = Join-Path $CacheBase '.supertonic-ort-subst-drive.txt'
$StampedBuildDriveLetter = $null
if (Test-Path $DriveStamp) {
    try {
        $stampText = (Get-Content $DriveStamp -Raw).Trim().ToUpperInvariant()
        if ($stampText -match '^[A-Z]$') {
            $StampedBuildDriveLetter = $stampText
        } else {
            Write-Warning "Ignoring invalid ORT SUBST-drive stamp: $DriveStamp"
        }
    } catch {
        Write-Warning "Could not read ORT SUBST-drive stamp. The legacy build tree will be cleaned safely."
    }
}

# Best-effort inspection of the top-level cache. This is only an additional
# guard; the physical stamp above is authoritative because nested _deps state can
# stay stale even after the top-level CMakeCache has changed drive letters.
$CachedBuildDriveLetters = New-Object 'System.Collections.Generic.HashSet[string]'
if (Test-Path $PhysicalBuildCache) {
    try {
        $physicalCacheText = Get-Content $PhysicalBuildCache -Raw
        foreach ($m in [regex]::Matches($physicalCacheText, '(?i)([A-Z]):[\\/]build(?:[\\/]|$)')) {
            [void]$CachedBuildDriveLetters.Add($m.Groups[1].Value.ToUpperInvariant())
        }
    } catch {
        Write-Warning "Could not inspect existing CMakeCache.txt for absolute build-drive references."
    }
}

# Build from a short SUBST path. ORT + FetchContent paths can otherwise exceed
# Windows path limits during the CMake/Ninja build.
$Drive = $null
$OwnedSubstDrive = $false
$driveLetters = New-Object 'System.Collections.Generic.List[string]'
if ($StampedBuildDriveLetter) { [void]$driveLetters.Add($StampedBuildDriveLetter) }
foreach ($letter in @('O','R','T','Q','P','N','M','L')) {
    if (-not $driveLetters.Contains($letter)) { [void]$driveLetters.Add($letter) }
}
foreach ($letter in $driveLetters) {
    $candidate = "$letter`:"
    if (-not (Test-Path "$candidate\")) {
        & subst.exe $candidate $CacheBase | Out-Null
        if ($LASTEXITCODE -eq 0 -and (Test-Path "$candidate\")) {
            $Drive = $candidate
            $OwnedSubstDrive = $true
            break
        }
    }
}
if (-not $Drive) { Fail 'Could not allocate a short SUBST drive for the ORT build.' }

try {
    $SrcDir = "$Drive\src"
    $BuildDir = "$Drive\build"
    $VenvDir = Join-Path $CacheBase 'pyenv'
    $CurrentBuildDriveLetter = $Drive.Substring(0, 1).ToUpperInvariant()

    # Migration / corruption guard:
    #   1) Existing build tree + no stable stamp => legacy tree, clean once.
    #   2) Stamp says another drive => generated tree is non-portable, clean.
    #   3) Top-level cache still contains any other X:/build reference => mixed
    #      cache (the exact O:/build + R:/build failure seen in the logs), clean.
    # Only CacheBase\build is removed. CacheBase\src and CacheBase\pyenv survive.
    $MustCleanBuildTree = $false
    $CleanReasons = New-Object 'System.Collections.Generic.List[string]'
    if (Test-Path $BuildDir) {
        if (-not $StampedBuildDriveLetter) {
            $MustCleanBuildTree = $true
            [void]$CleanReasons.Add('legacy build tree predates the stable SUBST-drive stamp')
        } elseif ($StampedBuildDriveLetter -ne $CurrentBuildDriveLetter) {
            $MustCleanBuildTree = $true
            [void]$CleanReasons.Add(("stable drive is {0}: but this run must use {1}:" -f $StampedBuildDriveLetter, $CurrentBuildDriveLetter))
        }

        $ForeignCacheDrives = @($CachedBuildDriveLetters | Where-Object { $_ -ne $CurrentBuildDriveLetter })
        if ($ForeignCacheDrives.Count -gt 0) {
            $MustCleanBuildTree = $true
            [void]$CleanReasons.Add(("CMakeCache contains foreign build drive(s): {0}" -f (($ForeignCacheDrives | Sort-Object) -join ', ')))
        }
    }

    if ($MustCleanBuildTree) {
        Write-Warning ("Cleaning stale ORT generated build tree only: {0}" -f ($CleanReasons -join '; '))
        Remove-Item -Recurse -Force $BuildDir
        $CachedBuildDriveLetters.Clear()
    }

    # Persist the drive choice only after the stale tree has been handled. From
    # now on the same physical cache will prefer the same short path every run.
    $CurrentBuildDriveLetter | Set-Content -Encoding ascii $DriveStamp

    Write-Step 'Toolchain'
    Write-Host "Android SDK : $AndroidSdk"
    Write-Host "Android NDK : $AndroidNdk"
    Write-Host "QAIRT       : $QairtRoot"
    Write-Host "Work drive  : $Drive -> $CacheBase"
    Write-Host "Git         : $GitExe"
    Write-Host "Python      : $pyCmd $($pyPrefix -join ' ')"

    if (-not (Test-Path (Join-Path $VenvDir 'Scripts\python.exe'))) {
        Write-Host 'Creating isolated Python build environment...'
        Invoke-Checked $pyCmd ($pyPrefix + @('-m','venv',$VenvDir))
    }
    $VenvPython = Join-Path $VenvDir 'Scripts\python.exe'
    Invoke-Checked $VenvPython @('-m','pip','install','--disable-pip-version-check','--upgrade','pip','setuptools','wheel','flatbuffers','cmake>=3.28,<4','ninja')
    $env:PATH = "$(Join-Path $VenvDir 'Scripts');$env:PATH"
    Invoke-Checked (Join-Path $VenvDir 'Scripts\cmake.exe') @('--version')
    Invoke-Checked (Join-Path $VenvDir 'Scripts\ninja.exe') @('--version')

    Write-Step 'ONNX Runtime v1.28.0 source'
    if (-not (Test-Path (Join-Path $SrcDir '.git'))) {
        if (Test-Path $SrcDir) { Remove-Item -Recurse -Force $SrcDir }
        Invoke-Checked $GitExe @('clone','--depth','1','--branch',$OrtTag,'https://github.com/microsoft/onnxruntime.git',$SrcDir)
    } else {
        Invoke-Checked $GitExe @('-C',$SrcDir,'fetch','--depth','1','origin',"refs/tags/${OrtTag}:refs/tags/${OrtTag}")
        Invoke-Checked $GitExe @('-C',$SrcDir,'reset','--hard',$OrtTag)
        Invoke-Checked $GitExe @('-C',$SrcDir,'clean','-fdx')
    }
    $Commit = (& $GitExe -C $SrcDir rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { Fail 'Could not resolve ORT source commit.' }
    Write-Host "ORT commit: $Commit"

    Write-Step 'Apply REV30 HTA backend patch'
    Invoke-Checked $GitExe @('-C',$SrcDir,'apply','--check',$HtaPatch)
    Invoke-Checked $GitExe @('-C',$SrcDir,'apply',$HtaPatch)
    $htaDef = Join-Path $SrcDir 'onnxruntime\core\providers\qnn\builder\qnn_def.h'
    if (-not ((Get-Content $htaDef -Raw) -match 'HTA')) { Fail 'HTA patch verification failed.' }

    Write-Step 'Build native libonnxruntime.so with QNN + XNNPACK'
    if ($Force -or $env:FORCE_ORT_REBUILD -eq '1') {
        if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
    }
    New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

    $BuildPy = Join-Path $SrcDir 'tools\ci_build\build.py'
    $buildArgs = @(
        $BuildPy,
        '--update','--build',
        '--config','Release',
        '--build_dir',$BuildDir,
        '--android',
        '--android_sdk_path',$AndroidSdk,
        '--android_ndk_path',$AndroidNdk,
        '--android_abi','arm64-v8a',
        '--android_api',"$AndroidApi",
        '--cmake_generator','Ninja',
        '--build_shared_lib',
        '--skip_tests',
        '--compile_no_warning_as_error',
        '--parallel',
        '--use_qnn','static_lib',
        '--qnn_home',$QairtRoot,
        '--use_xnnpack',
        '--cmake_extra_defines',
        'onnxruntime_BUILD_UNIT_TESTS=OFF'
    )

    # Do NOT merge native stderr inside PowerShell. Windows PowerShell 5.1 converts
    # redirected native stderr into NativeCommandError records, which can terminate
    # the script even when build.py is still healthy. A tiny Python runner performs
    # stderr->stdout merging outside PowerShell and streams the exact bytes to both
    # the console and the build log. Invoke-Checked then judges only the real process
    # exit code.
    $NativeRunner = Join-Path $CacheBase 'run_native_logged.py'
@'
import os
import subprocess
import sys
import traceback

# Never let Python diagnostics escape on stderr. Windows PowerShell 5.1 may
# promote native stderr to an ErrorRecord. All runner diagnostics go to stdout.
sys.stderr = sys.stdout

def main():
    if len(sys.argv) < 4:
        print("usage: run_native_logged.py LOG EXE [ARGS...]", flush=True)
        return 64

    log_path = sys.argv[1]
    cmd = sys.argv[2:]
    cwd = os.environ.get("SUPERTONIC_ORT_BUILD_CWD") or None

    print("> " + subprocess.list2cmdline(cmd), flush=True)
    os.makedirs(os.path.dirname(os.path.abspath(log_path)), exist_ok=True)

    try:
        with open(log_path, "xb") as log:
            proc = subprocess.Popen(
                cmd,
                cwd=cwd,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                bufsize=0,
            )
            assert proc.stdout is not None
            while True:
                chunk = proc.stdout.readline()
                if not chunk:
                    break
                try:
                    sys.stdout.buffer.write(chunk)
                    sys.stdout.buffer.flush()
                except (BrokenPipeError, OSError):
                    pass
                log.write(chunk)
                log.flush()
            rc = proc.wait()
    except Exception:
        print("[ORT-RUNNER-ERROR]", flush=True)
        traceback.print_exc(file=sys.stdout)
        return 70

    print(f"[ORT-RUNNER-EXIT] {rc}", flush=True)
    return rc

raise SystemExit(main())
'@ | Set-Content -Encoding UTF8 $NativeRunner

    # The project-root log can be locked by another process. Never let that block
    # ORT compilation. Write each build to a brand-new cache log and only publish
    # a copy to the project root after the child process has closed its handle.
    $NativeLogDir = Join-Path $CacheBase 'logs'
    New-Item -ItemType Directory -Force -Path $NativeLogDir | Out-Null
    $NativeLogFile = Join-Path $NativeLogDir ("ORT-QNN-XNNPACK-{0}-{1}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $PID)
    Write-Host "Native build log: $NativeLogFile"

    $env:SUPERTONIC_ORT_BUILD_CWD = $SrcDir
    & $VenvPython $NativeRunner $NativeLogFile $VenvPython @buildArgs
    $ortExitCode = $LASTEXITCODE

    # Best-effort convenience copy only. A locked project-root log must never
    # turn a successful ORT build into a failure.
    try {
        Copy-Item -Force $NativeLogFile $PublishedLogFile -ErrorAction Stop
        $LogFile = $PublishedLogFile
    } catch {
        Write-Warning ("Could not publish root log because it is locked. Use native log instead: {0}" -f $NativeLogFile)
        $LogFile = $NativeLogFile
    }

    if ($ortExitCode -ne 0) {
        Fail ("ORT native build failed with exit code {0}. See {1}" -f $ortExitCode, $NativeLogFile)
    }

    $ConfigDir = Join-Path $BuildDir 'Release'
    $NativeSo = Join-Path $ConfigDir 'libonnxruntime.so'
    $CacheFile = Join-Path $ConfigDir 'CMakeCache.txt'
    if (-not (Test-Path $NativeSo)) { Fail "Built libonnxruntime.so not found: $NativeSo" }
    if (-not (Test-Path $CacheFile)) { Fail "CMakeCache.txt not found: $CacheFile" }
    $cache = Get-Content $CacheFile -Raw
    foreach ($required in @(
        'onnxruntime_USE_QNN:BOOL=ON',
        'onnxruntime_BUILD_QNN_EP_STATIC_LIB:BOOL=ON',
        'onnxruntime_USE_XNNPACK:BOOL=ON'
    )) {
        if ($cache -notmatch [regex]::Escape($required)) { Fail "CMake feature verification failed: $required" }
    }
    Write-Host '[OK] CMakeCache confirms QNN static + XNNPACK.'

    Write-Step 'Repack validated REV30 Java/JNI AAR shell with new native core'
    Write-RepackedAarWithNativeCore $TemplateAar $NativeSo $OutputAar

    $hash = (Get-FileHash -Algorithm SHA256 $OutputAar).Hash.ToLowerInvariant()
    "$hash  $(Split-Path $OutputAar -Leaf)" | Set-Content -Encoding ascii $ShaFile
    @(
        "source_tag=$OrtTag",
        "source_commit=$Commit",
        "qairt_build=$QairtBuild",
        "android_api=$AndroidApi",
        "android_ndk=$(Split-Path $AndroidNdk -Leaf)",
        'use_qnn=static_lib',
        'use_xnnpack=1',
        'hta_patch=1',
        'java_jni_shell=REV30-custom-ORT-1.28.0-HTA',
        "native_core_sha256=$((Get-FileHash -Algorithm SHA256 $NativeSo).Hash.ToLowerInvariant())",
        "aar_sha256=$hash"
    ) | Set-Content -Encoding ascii $BuildInfo

    # Structural AAR checks. The definitive EP check happens on-device through
    # OrtEnvironment.getAvailableProviders(), which REV32 logs before session creation.
    $zip = [System.IO.Compression.ZipFile]::OpenRead($OutputAar)
    try {
        $names = $zip.Entries.FullName
        foreach ($entry in @('classes.jar','jni/arm64-v8a/libonnxruntime.so','jni/arm64-v8a/libonnxruntime4j_jni.so')) {
            if ($names -notcontains $entry) { Fail "Output AAR missing entry: $entry" }
        }
    } finally { $zip.Dispose() }

    Write-Host ''
    Write-Host '[SUCCESS] Custom ORT QNN+XNNPACK AAR created.' -ForegroundColor Green
    Write-Host "AAR    : $OutputAar"
    Write-Host "SHA256 : $hash"
    Write-Host "Info   : $BuildInfo"
    Write-Host 'REV32 will additionally require OrtProvider.XNNPACK/QNN at runtime before creating sessions.'
} finally {
    if ($Drive -and $OwnedSubstDrive) { & subst.exe $Drive /d | Out-Null }
}
