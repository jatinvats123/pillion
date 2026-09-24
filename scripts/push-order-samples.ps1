# Puts the mock order screenshots (invented brands, names, numbers) into a device's gallery
# (Pictures/PillionSamples), to test "Share -> Pillion: scan order" and the photo picker with
# real OCR. The numbers are made up: never send SMS or place calls to them.
#   .\scripts\push-order-samples.ps1 [-Serial emulator-5554]
param([string]$Serial)

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
$target = if ($Serial) { @('-s', $Serial) } else { @() }
$source = Join-Path $PSScriptRoot '..\android\app\src\debug\assets\order_samples'
$dest = '/sdcard/Pictures/PillionSamples'

& $adb @target shell mkdir -p $dest
Get-ChildItem $source -Filter '*.png' | ForEach-Object {
    & $adb @target push $_.FullName "$dest/$($_.Name)" | Out-Null
    # Android 10 and older pick new files up from this broadcast.
    & $adb @target shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$dest/$($_.Name)" | Out-Null
    Write-Host "pushed $($_.Name)"
}
# Android 11+: rescan the shared storage so the gallery and the photo picker list them.
& $adb @target shell content call --uri content://media --method scan_volume --arg external_primary | Out-Null
Write-Host "Done: Photos / Files > Pictures > PillionSamples"
