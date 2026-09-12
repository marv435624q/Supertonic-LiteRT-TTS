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

Write-Host "Trying an in-place update first..."
& $adbPath install -r $apkPath
if ($LASTEXITCODE -eq 0) {
    Write-Host "Update installed. Existing models and settings were preserved."
    exit 0
}

Write-Host "The installed APK uses an older signing key. Backing up downloaded models..."
Invoke-Adb shell am force-stop $packageName

$backupOptions = @{
    FilePath = $adbPath
    ArgumentList = @("exec-out", "run-as", $packageName, "tar", "-cf", "-", "files")
    RedirectStandardOutput = $backupPath
    NoNewWindow = $true
    Wait = $true
    PassThru = $true
}
$backupProcess = Start-Process @backupOptions
if ($backupProcess.ExitCode -ne 0) {
    throw "Model backup failed with exit code $($backupProcess.ExitCode). The installed app was not removed."
}
if (-not (Test-Path -LiteralPath $backupPath) -or (Get-Item -LiteralPath $backupPath).Length -eq 0) {
    throw "Model backup is empty. The installed app was not removed."
}

Write-Host "Backup complete: $backupPath"
Invoke-Adb uninstall $packageName
Invoke-Adb install $apkPath

$restoreOptions = @{
    FilePath = $adbPath
    ArgumentList = @("exec-in", "run-as", $packageName, "tar", "-xf", "-")
    RedirectStandardInput = $backupPath
    NoNewWindow = $true
    Wait = $true
    PassThru = $true
}
$restoreProcess = Start-Process @restoreOptions
if ($restoreProcess.ExitCode -ne 0) {
    throw "Model restore failed with exit code $($restoreProcess.ExitCode). The backup remains at $backupPath."
}

Write-Host "Installed the release-key APK and restored the downloaded models."
Write-Host "Keep $backupPath until you have opened the app and verified the models."
