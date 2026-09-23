<#
.SYNOPSIS
  Build, install and run the Pillion debug app on a real phone (USB or wireless debugging).

.DESCRIPTION
  1. Picks the connected phone (or -Serial).
  2. adb reverse tcp:3000 -> the phone's localhost:3000 reaches the backend on this laptop.
  3. Builds the debug APK with PILLION_BACKEND_URL=http://localhost:3000 and installs it.
  4. Clears logcat and follows the VoiceSession / RideViewModel logs (Ctrl+C to stop).

.EXAMPLE
  .\scripts\phone.ps1
  .\scripts\phone.ps1 -Serial 192.168.1.47:42939 -SkipBuild
#>
param(
    [string]$Serial,
    [int]$Port = 3000,
    [switch]$SkipBuild,
    [switch]$NoLogcat
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

# 1. Pick the phone: the only non-emulator device, unless -Serial is given.
if (-not $Serial) {
    # Wireless debugging can list one phone twice (IP:port and an mDNS name); dedupe by hardware
    # serial, preferring the IP:port entry.
    $phones = & $adb devices | Select-Object -Skip 1 |
        Where-Object { $_ -match '^(\S+)\s+device$' -and $_ -notmatch '^emulator-' } |
        ForEach-Object { ($_ -split '\s+')[0] } |
        Sort-Object { if ($_ -match '^\d+\.\d+\.\d+\.\d+:\d+$') { 0 } else { 1 } } |
        Group-Object { (& $adb -s $_ shell getprop ro.serialno).Trim() } |
        ForEach-Object { $_.Group[0] }
    if (@($phones).Count -ne 1) { throw "Expected exactly one phone, found: $(@($phones) -join ', '). Pass -Serial." }
    $Serial = @($phones)[0]
}
$model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
$android = (& $adb -s $Serial shell getprop ro.build.version.release).Trim()
Write-Host "Phone: $model (Android $android) [$Serial]"

# 2. Backend check + reverse port forward.
try {
    $health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 3
    Write-Host "Backend OK: voice=$($health.voiceStack) llm=$($health.llm) tools=$($health.tools) jev=$($health.jev) maps=$($health.maps) activeAgents=$($health.activeAgents)"
    if (-not $health.tools) { Write-Warning 'PUBLIC_BASE_URL is not set: rides will not start. Run cloudflared and set it in backend/.env.' }
} catch {
    Write-Warning "Backend not reachable on http://127.0.0.1:$Port - start it with: cd backend; npm start"
}
& $adb -s $Serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
Write-Host "adb reverse: phone localhost:$Port -> laptop localhost:$Port"

# 3. Build and install.
$apk = Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    Push-Location (Join-Path $root 'android')
    try {
        & .\gradlew.bat :app:assembleDebug -q "-PPILLION_BACKEND_URL=http://localhost:$Port"
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    } finally { Pop-Location }
}
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed (on Realme, allow "Install via USB" in Developer options).' }
& $adb -s $Serial shell am start -n app.pillion/.MainActivity | Out-Null
Write-Host "Pillion installed and launched."

# 4. Logs.
if (-not $NoLogcat) {
    & $adb -s $Serial logcat -c
    Write-Host "Following logs (uid=0 vol = your mic level, 0-255). Ctrl+C to stop."
    & $adb -s $Serial logcat -v time -s VoiceSession:* RideViewModel:* RideService:* RideRepository:* DeviceActions:*
}
