# Puts the mock order screenshots (invented brands, names, numbers) into a device's gallery
# (Pictures/PillionSamples), to test "Share -> Pillion: scan order" and the photo picker with
# real OCR. The numbers are made up: never send SMS or place calls to them.
#   .\scripts\push-order-samples.ps1 [-Serial emulator-5554]
param([string]$Serial)

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$sources = 'debug', 'main' | ForEach-Object { Join-Path $PSScriptRoot "..\android\app\src\$_\assets\order_samples" }
$dest = '/sdcard/Pictures/PillionSamples'

$sdk = [int](& $adb @target shell getprop ro.build.version.sdk).Trim()
& $adb @target shell mkdir -p $dest
Get-ChildItem $sources -Filter '*.png' | ForEach-Object {
    & $adb @target push $_.FullName "$dest/$($_.Name)" 2>&1 | Out-Null
    # Android 10 and older pick new files up from this broadcast (newer versions stall on it).
    if ($sdk -le 29) { & $adb @target shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$dest/$($_.Name)" | Out-Null }
    Write-Host "pushed $($_.Name)"
}
# Android 11+: rescan the shared storage so the gallery and the photo picker list them.
if ($sdk -ge 30) { & $adb @target shell content call --uri content://media --method scan_volume --arg external_primary | Out-Null }
Write-Host "Done: Photos / Files > Pictures > PillionSamples"
