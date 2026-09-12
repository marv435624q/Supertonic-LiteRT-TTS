param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,

    [string]$Adb = "adb",

    [string]$Backup = "supertonic-model-backup.tar"
)

$ErrorActionPreference = "Stop"
$packageName = "com.supertonic.tts"
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$backupPath = [System.IO.Path]::GetFullPath($Backup)
$adbCommand = Get-Command $Adb -ErrorAction Stop
$adbPath = $adbCommand.Source

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & $adbPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code $LASTEXITCODE: $($Arguments -join ' ')"
    }
}

function Invoke-AdbBinary {
    param(
        [Parameter(Mandatory = $true)][string]$Arguments,
        [string]$InputFile,
        [string]$OutputFile
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adbPath
    $startInfo.Arguments = $Arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = -not [string]::IsNullOrEmpty($InputFile)
    $startInfo.RedirectStandardOutput = -not [string]::IsNullOrEmpty($OutputFile)

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()

    if ($startInfo.RedirectStandardInput) {
        $inputStream = [System.IO.File]::OpenRead($InputFile)
        try {
            $inputStream.CopyTo($process.StandardInput.BaseStream)
            $process.StandardInput.Close()
        } finally {
            $inputStream.Dispose()
        }
    }
    if ($startInfo.RedirectStandardOutput) {
        $outputStream = [System.IO.File]::Create($OutputFile)
        try {
            $process.StandardOutput.BaseStream.CopyTo($outputStream)
        } finally {
            $outputStream.Dispose()
        }
    }

    $process.WaitForExit()
    return $process.ExitCode
}

Write-Host "Trying an in-place update first..."
& $adbPath install -r $apkPath
if ($LASTEXITCODE -eq 0) {
    Write-Host "Update installed. Existing models and settings were preserved."
    exit 0
}

Write-Host "The installed APK uses an older signing key. Backing up downloaded models..."
Invoke-Adb shell am force-stop $packageName

$backupExitCode = Invoke-AdbBinary -Arguments "exec-out run-as $packageName tar -cf - files" -OutputFile $backupPath
if ($backupExitCode -ne 0) {
    throw "Model backup failed with exit code $backupExitCode. The installed app was not removed."
}
if (-not (Test-Path -LiteralPath $backupPath) -or (Get-Item -LiteralPath $backupPath).Length -eq 0) {
    throw "Model backup is empty. The installed app was not removed."
}

Write-Host "Backup complete: $backupPath"
Invoke-Adb uninstall $packageName
Invoke-Adb install $apkPath

$restoreExitCode = Invoke-AdbBinary -Arguments "exec-in run-as $packageName tar -xf -" -InputFile $backupPath
if ($restoreExitCode -ne 0) {
    throw "Model restore failed with exit code $restoreExitCode. The backup remains at $backupPath."
}

Write-Host "Installed the release-key APK and restored the downloaded models."
Write-Host "Keep $backupPath until you have opened the app and verified the models."
