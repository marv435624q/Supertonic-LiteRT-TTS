param(
    [ValidateSet("RTF", "FULL")]
    [string]$Mode = "RTF"
)

$ErrorActionPreference = "Stop"
$Package = "com.supertonic.tts"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

function Find-Adb {
    $candidates = @(
        "C:\platform-tools\adb.exe",
        (Join-Path $Root "platform-tools\adb.exe")
    )
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) {
            return (Resolve-Path $c).Path
        }
    }
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "adb.exe not found. Expected C:\platform-tools\adb.exe, Android SDK platform-tools, or adb.exe in PATH."
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string[]]$Args
    )
    try {
        & $script:Adb @Args 2>&1 | Out-File -FilePath $Path -Encoding utf8 -Width 4096
    } catch {
        ("[ERROR] " + $_.Exception.Message) | Out-File -FilePath $Path -Encoding utf8 -Append
    }
}

function Append-AdbShell {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Title,
        [Parameter(Mandatory=$true)][string]$Command
    )
    ("`r`n===== " + $Title + " =====") | Out-File $Path -Encoding utf8 -Append
    try {
        & $script:Adb shell $Command 2>&1 | Out-File $Path -Encoding utf8 -Width 4096 -Append
    } catch {
        ("[ERROR] " + $_.Exception.Message) | Out-File $Path -Encoding utf8 -Append
    }
}


function Save-PackageInfo {
    param([string]$Path)
    try {
        $raw = & $script:Adb shell dumpsys package $script:Package 2>&1
        $raw | Select-String -Pattern @('versionName=', 'versionCode=', 'debuggable', 'primaryCpuAbi=', 'secondaryCpuAbi=') -SimpleMatch |
            ForEach-Object { $_.Line } | Out-File -FilePath $Path -Encoding utf8 -Width 4096
    } catch {
        ("[ERROR] package info capture failed: " + $_.Exception.Message) |
            Out-File -FilePath $Path -Encoding utf8
    }
}

function Save-AppSettings {
    param([string]$Path)
    try {
        & $script:Adb exec-out run-as $script:Package cat "shared_prefs/supertonic_tts.xml" 2>&1 |
            Out-File -FilePath $Path -Encoding utf8 -Width 4096
    } catch {
        ("[INFO] run-as/settings unavailable: " + $_.Exception.Message) |
            Out-File -FilePath $Path -Encoding utf8
    }
}

function Save-Snapshot {
    param([string]$Prefix)
    $out = Join-Path $script:OutDir ($Prefix + "_system.txt")
    ("Capture time: " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff")) |
        Out-File $out -Encoding utf8

    Append-AdbShell $out "IDENTITY" 'echo model=$(getprop ro.product.model); echo device=$(getprop ro.product.device); echo product=$(getprop ro.product.name); echo soc=$(getprop ro.soc.model); echo board=$(getprop ro.board.platform); echo hardware=$(getprop ro.hardware); echo sdk=$(getprop ro.build.version.sdk); echo fingerprint=$(getprop ro.build.fingerprint); uname -a'
    Append-AdbShell $out "CPU ONLINE/PRESENT" 'echo online=$(cat /sys/devices/system/cpu/online 2>/dev/null); echo present=$(cat /sys/devices/system/cpu/present 2>/dev/null); echo possible=$(cat /sys/devices/system/cpu/possible 2>/dev/null)'
    Append-AdbShell $out "CPUFREQ POLICIES" 'for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d "$p" ] || continue; echo ---$p---; for f in affected_cpus related_cpus scaling_governor scaling_cur_freq cpuinfo_cur_freq scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq; do [ -r "$p/$f" ] && echo "$f=$(cat "$p/$f")"; done; done'
    Append-AdbShell $out "THERMAL ZONES" 'for z in /sys/class/thermal/thermal_zone*; do [ -d "$z" ] || continue; t=$(cat "$z/type" 2>/dev/null); v=$(cat "$z/temp" 2>/dev/null); echo "$(basename "$z") type=$t temp=$v"; done'
    Append-AdbShell $out "APP PROCESS" 'pid=$(pidof com.supertonic.tts 2>/dev/null); echo pid=$pid; [ -n "$pid" ] && { cat /proc/$pid/status 2>/dev/null; echo ---threads---; ps -T -p $pid 2>/dev/null; }'
    Append-AdbShell $out "MEMINFO" 'cat /proc/meminfo'
    Append-AdbShell $out "BATTERY" 'dumpsys battery'
    Append-AdbShell $out "POWER" 'dumpsys power'
    Append-AdbShell $out "THERMAL SERVICE" 'dumpsys thermalservice 2>/dev/null'
    ("`r`n===== APP PACKAGE =====") | Out-File $out -Encoding utf8 -Append
    $pkgTemp = Join-Path $script:OutDir ($Prefix + "_package.txt")
    Save-PackageInfo $pkgTemp
    Get-Content $pkgTemp -ErrorAction SilentlyContinue | Out-File $out -Encoding utf8 -Append
}

function Pull-DeepProfiles {
    param([string]$Destination)
    $profileDir = Join-Path $Destination "deep_profiles"
    $tarPath = Join-Path $Destination "deep_profiles.tar"
    $stderrPath = Join-Path $Destination "deep_profiles_pull_stderr.txt"

    try {
        $probe = & $script:Adb shell run-as $script:Package sh -c "test -d cache/accelerator_cache/perf_profiles && echo YES" 2>&1
        if (($probe -join "`n") -notmatch "YES") {
            "[INFO] Deep Profiler directory is absent. This is normal when Deep Profiler is OFF." |
                Out-File (Join-Path $Destination "deep_profiles_status.txt") -Encoding utf8
            return
        }

        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $script:Adb
        $psi.Arguments = "exec-out run-as $($script:Package) tar -C cache/accelerator_cache/perf_profiles -cf - ."
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true

        $p = New-Object System.Diagnostics.Process
        $p.StartInfo = $psi
        [void]$p.Start()

        $fs = [System.IO.File]::Open($tarPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
        try {
            $p.StandardOutput.BaseStream.CopyTo($fs)
        } finally {
            $fs.Dispose()
        }
        $err = $p.StandardError.ReadToEnd()
        $p.WaitForExit()
        $err | Out-File $stderrPath -Encoding utf8

        if ($p.ExitCode -ne 0 -or !(Test-Path $tarPath) -or (Get-Item $tarPath).Length -eq 0) {
            "[ERROR] Deep Profiler archive pull failed. ExitCode=$($p.ExitCode)" |
                Out-File (Join-Path $Destination "deep_profiles_status.txt") -Encoding utf8
            return
        }

        $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
        if (!$tar) {
            "[INFO] Deep Profiler raw TAR saved, but tar.exe was not found for extraction." |
                Out-File (Join-Path $Destination "deep_profiles_status.txt") -Encoding utf8
            return
        }

        New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
        & $tar.Source -xf $tarPath -C $profileDir 2>&1 |
            Out-File (Join-Path $Destination "deep_profiles_extract.txt") -Encoding utf8

        $analyzer = Join-Path $script:Root "tools\analyze_deep_profiles.py"
        if (Test-Path $analyzer) {
            $python = Get-Command python.exe -ErrorAction SilentlyContinue
            if ($python) {
                & $python.Source $analyzer $profileDir 2>&1 |
                    Out-File (Join-Path $Destination "deep_profiles_analyzer.txt") -Encoding utf8
            } else {
                $py = Get-Command py.exe -ErrorAction SilentlyContinue
                if ($py) {
                    & $py.Source -3 $analyzer $profileDir 2>&1 |
                        Out-File (Join-Path $Destination "deep_profiles_analyzer.txt") -Encoding utf8
                }
            }
        }

        "[OK] Deep Profiler files pulled to deep_profiles\" |
            Out-File (Join-Path $Destination "deep_profiles_status.txt") -Encoding utf8
    } catch {
        ("[ERROR] Deep Profiler pull exception: " + $_.Exception.Message) |
            Out-File (Join-Path $Destination "deep_profiles_status.txt") -Encoding utf8
    }
}

function Make-FilteredLogs {
    $logcat = Join-Path $script:OutDir "logcat_all.txt"
    if (!(Test-Path $logcat)) { return }

    $important = Join-Path $script:OutDir "IMPORTANT_TTS_LOG.txt"
    $profiles = Join-Path $script:OutDir "SYNTH_PROFILES.txt"
    $errors = Join-Path $script:OutDir "ERRORS_CRASHES.txt"

    Select-String -Path $logcat -Pattern @(
        "UI-SYNTH-PROFILE",
        "SYNTH_APPLIED",
        "SYNTH_TTFA",
        "SYNTH_PROFILE",
        "[SYNTH-BEGIN]",
        "[SYNTH-END]",
        "[LITERT-SYNTH-END]",
        "[STAGE-END]",
        "[VE-STEP-END]",
        "[MODEL-BACKEND]",
        "MODEL-PRELOAD",
        "[AUTO-BUCKET]",
        "[LITERT-SIGNATURE-SWITCH]",
        "[LITERT-SIGNATURE-SWITCH-FAIL]",
        "[LITERT-INIT-TIMING]",
        "[DURATION-PROBE]",
        "[OVERFLOW-GUARD]",
        "[CPU-THREAD-PLAN]",
        "[CPU-AFFINITY]",
        "XNNPACK",
        "SUPERTONIC-XNN-REMAIN",
        "ONNX",
        "ORT",
        "QNN",
        "thermal_changed",
        "backend=",
        "RTF"
    ) -SimpleMatch | ForEach-Object { $_.Line } |
        Out-File $important -Encoding utf8 -Width 4096

    Select-String -Path $logcat -Pattern @(
        "UI-SYNTH-PROFILE",
        "SYNTH_APPLIED",
        "SYNTH_TTFA",
        "SYNTH_PROFILE",
        "[SYNTH-END]",
        "[LITERT-SYNTH-END]",
        "[AUTO-BUCKET][SELECT]",
        "[OVERFLOW-GUARD]"
    ) -SimpleMatch | ForEach-Object { $_.Line } |
        Out-File $profiles -Encoding utf8 -Width 4096

    $uiProfiles = Join-Path $script:OutDir "UI_SYNTH_PROFILES.txt"
    Select-String -Path $logcat -Pattern "[UI-SYNTH-PROFILE]" -SimpleMatch |
        ForEach-Object { $_.Line } | Out-File $uiProfiles -Encoding utf8 -Width 4096

    $remainingOps = Join-Path $script:OutDir "REMAINING_XNNPACK_OPS.txt"
    Select-String -Path $logcat -Pattern "SUPERTONIC-XNN-REMAIN" -SimpleMatch |
        ForEach-Object { $_.Line } | Out-File $remainingOps -Encoding utf8 -Width 4096

    Select-String -Path $logcat -Pattern @(
        "FATAL EXCEPTION",
        "TTS synthesis failed",
        "SIGSEGV",
        "signal 11",
        "Fatal signal",
        "ANR in com.supertonic.tts",
        "Native crash",
        "Abort message"
    ) -SimpleMatch | ForEach-Object { $_.Line } |
        Out-File $errors -Encoding utf8 -Width 4096
}

function Start-Telemetry {
    if ($script:Mode -ne "FULL") { return $null }

    $telemetryPath = Join-Path $script:OutDir "telemetry_2s.txt"
    "FULL mode: polling every 2 seconds. Do not use FULL mode for authoritative RTF numbers." |
        Out-File $telemetryPath -Encoding utf8

    return Start-Job -ArgumentList $script:Adb, $telemetryPath -ScriptBlock {
        param($adb, $path)
        while ($true) {
            $hostStamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss.fff"
            ("`r`n=== " + $hostStamp + " ===") | Out-File $path -Encoding utf8 -Append
            try {
                & $adb shell 'echo device_ms=$(date +%s%3N 2>/dev/null); for p in /sys/devices/system/cpu/cpufreq/policy*; do [ -d "$p" ] || continue; echo -n "$(basename "$p"):"; cat "$p/scaling_cur_freq" 2>/dev/null; done; for z in /sys/class/thermal/thermal_zone*; do [ -d "$z" ] || continue; t=$(cat "$z/type" 2>/dev/null); v=$(cat "$z/temp" 2>/dev/null); case "$t" in *cpu*|*CPU*|*soc*|*SOC*|*skin*|*Skin*|*gpu*|*GPU*) echo "thermal:$t=$v";; esac; done; pid=$(pidof com.supertonic.tts 2>/dev/null); [ -n "$pid" ] && { echo app_pid=$pid; cat /proc/$pid/stat 2>/dev/null; }' 2>&1 |
                    Out-File $path -Encoding utf8 -Width 4096 -Append
            } catch {
                ("telemetry error: " + $_.Exception.Message) | Out-File $path -Encoding utf8 -Append
            }
            Start-Sleep -Seconds 2
        }
    }
}

$Adb = Find-Adb
$Package = $Package
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logRoot = Join-Path $Root "SD690-TEST-LOGS"
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$OutDir = Join-Path $logRoot ("SD690-" + $Mode + "-" + $stamp)
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$lastPointer = Join-Path $Root "LAST_SD690_LOG.txt"
$OutDir | Out-File $lastPointer -Encoding ascii

$logcatProcess = $null
$telemetryJob = $null
$completed = $false

try {
    $state = (& $Adb get-state 2>&1 | Out-String).Trim()
    if ($state -ne "device") {
        & $Adb devices -l
        throw "No ONLINE ADB device. adb get-state returned: $state"
    }

    Invoke-AdbText (Join-Path $OutDir "adb_devices.txt") @("devices", "-l")
    $soc = (& $Adb shell getprop ro.soc.model 2>$null | Out-String).Trim()
    $board = (& $Adb shell getprop ro.board.platform 2>$null | Out-String).Trim()
    $hardware = (& $Adb shell getprop ro.hardware 2>$null | Out-String).Trim()
    $model = (& $Adb shell getprop ro.product.model 2>$null | Out-String).Trim()

    @(
        "Mode=$Mode",
        "HostTime=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')",
        "ADB=$Adb",
        "DeviceModel=$model",
        "SoC=$soc",
        "Board=$board",
        "Hardware=$hardware",
        "Package=$Package",
        "Note=RTF mode avoids periodic ADB polling. FULL mode polls telemetry every 2 seconds."
    ) | Out-File (Join-Path $OutDir "SESSION.txt") -Encoding utf8

    $identity = ($soc + " " + $board + " " + $hardware).ToLowerInvariant()
    if ($identity -notmatch "sm6350|lito") {
        "WARNING: Device did not identify itself as SM6350/lito. Capture continues anyway." |
            Out-File (Join-Path $OutDir "DEVICE_WARNING.txt") -Encoding utf8
    }

    Save-AppSettings (Join-Path $OutDir "settings_before.xml")
    Save-Snapshot "before"

    # Keep the recorder's own activity out of authoritative RTF numbers:
    # RTF mode records logcat only while the test is running.
    & $Adb logcat -c 2>$null

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = "logcat -b all -v threadtime"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $logcatProcess = New-Object System.Diagnostics.Process
    $logcatProcess.StartInfo = $psi
    [void]$logcatProcess.Start()

    $stdoutTask = $logcatProcess.StandardOutput.ReadToEndAsync()
    $stderrTask = $logcatProcess.StandardError.ReadToEndAsync()

    $telemetryJob = Start-Telemetry

    Write-Host ""
    Write-Host "============================================================"
    Write-Host " SD690 Supertonic test recorder: $Mode"
    Write-Host "============================================================"
    Write-Host "Device : $model / SoC=$soc / board=$board"
    Write-Host "Output : $OutDir"
    Write-Host ""
    if ($Mode -eq "RTF") {
        Write-Host "RTF mode: no periodic CPU/thermal polling while synthesis runs."
    } else {
        Write-Host "FULL mode: CPU frequency/thermal telemetry is polled every 2 seconds."
        Write-Host "FULL mode numbers are diagnostic; use RTF mode for final RTF comparison."
    }
    Write-Host ""
    Write-Host "1) Leave this window open."
    Write-Host "2) Run the desired TTS tests on the SD690 device."
    Write-Host "3) When finished, return here and press ENTER."
    Write-Host ""
    [void](Read-Host "Press ENTER only AFTER the test is finished")

    $completed = $true
}
catch {
    ("[CAPTURE ERROR] " + $_.Exception.ToString()) |
        Out-File (Join-Path $OutDir "CAPTURE_ERROR.txt") -Encoding utf8
    Write-Host ""
    Write-Host "[ERROR] $($_.Exception.Message)"
}
finally {
    if ($telemetryJob) {
        Stop-Job $telemetryJob -ErrorAction SilentlyContinue | Out-Null
        Receive-Job $telemetryJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $telemetryJob -Force -ErrorAction SilentlyContinue
    }

    if ($logcatProcess) {
        try {
            if (!$logcatProcess.HasExited) {
                $logcatProcess.Kill()
                $logcatProcess.WaitForExit()
            }
            if ($stdoutTask) {
                $stdoutTask.Result | Out-File (Join-Path $OutDir "logcat_all.txt") -Encoding utf8 -Width 4096
            }
            if ($stderrTask) {
                $stderrTask.Result | Out-File (Join-Path $OutDir "logcat_stderr.txt") -Encoding utf8 -Width 4096
            }
        } catch {
            ("[ERROR stopping logcat] " + $_.Exception.Message) |
                Out-File (Join-Path $OutDir "logcat_stop_error.txt") -Encoding utf8
        }
    }

    try { Save-AppSettings (Join-Path $OutDir "settings_after.xml") } catch {}
    try { Save-Snapshot "after" } catch {}
    try { Pull-DeepProfiles $OutDir } catch {}
    try { Make-FilteredLogs } catch {}

    $resultFile = Join-Path $OutDir "RESULT.txt"
    @(
        "CompletedNormally=$completed",
        "Finished=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')",
        "Output=$OutDir",
        "ImportantLog=$(Join-Path $OutDir 'IMPORTANT_TTS_LOG.txt')",
        "SynthProfiles=$(Join-Path $OutDir 'SYNTH_PROFILES.txt')",
        "UiSynthProfiles=$(Join-Path $OutDir 'UI_SYNTH_PROFILES.txt')",
        "SettingsBefore=$(Join-Path $OutDir 'settings_before.xml')",
        "SettingsAfter=$(Join-Path $OutDir 'settings_after.xml')"
    ) | Out-File $resultFile -Encoding utf8

    $zipPath = $OutDir + ".zip"
    try {
        if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
        Compress-Archive -Path (Join-Path $OutDir "*") -DestinationPath $zipPath -CompressionLevel Optimal
        ("Zip=" + $zipPath) | Out-File $resultFile -Encoding utf8 -Append
    } catch {
        ("ZipError=" + $_.Exception.Message) | Out-File $resultFile -Encoding utf8 -Append
    }

    Write-Host ""
    Write-Host "============================================================"
    Write-Host " LOGS SAVED"
    Write-Host "============================================================"
    Write-Host "Folder : $OutDir"
    if (Test-Path $zipPath) { Write-Host "ZIP    : $zipPath" }
    Write-Host "Pointer: $lastPointer"
    Write-Host ""
    Write-Host "The files are already saved before this prompt."
    try { Start-Process explorer.exe -ArgumentList @($OutDir) | Out-Null } catch {}
    [void](Read-Host "Press ENTER to close this recorder window")
}
