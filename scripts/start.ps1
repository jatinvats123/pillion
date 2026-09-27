<#
.SYNOPSIS
  One command to get a Pillion test session up: tunnel, backend and phone, with OK/FAIL checks.

.DESCRIPTION
  1. Tunnel: keeps the cloudflared tunnel in backend/.env (PUBLIC_BASE_URL) if it still answers;
     otherwise starts a new one in its own window and writes its URL into backend/.env.
  2. Backend: starts `npm run dev` in its own window if nothing is running. Restarts it if it was
     started with another tunnel URL (.env is only read at start), doesn't answer, or if more than
     one backend listens on the port (rides live in one process's memory).
  3. Phone: asks for the Wireless debugging IP:port (suggests what adb finds on the network),
     drops stale adb entries, connects, sets adb reverse, then checks the backend from the phone.
  4. OK/FAIL for every check. Prints no secrets: of backend/.env only PUBLIC_BASE_URL and HOST.

  Afterwards, build and install the app with .\scripts\phone.ps1 -Serial <IP:port>.

.EXAMPLE
  .\scripts\start.ps1
  .\scripts\start.ps1 -Phone 192.168.1.47:40251
  .\scripts\start.ps1 -NewTunnel -RestartBackend
  .\scripts\start.ps1 -SkipPhone
#>
param(
    [string]$Phone,
    [int]$Port = 3000,
    [switch]$NewTunnel,
    [switch]$RestartBackend,
    [switch]$SkipPhone
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root 'backend'
$envFile = Join-Path $backendDir '.env'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$script:failures = 0

function Report([bool]$ok, [string]$check, [string]$detail = '') {
    if ($ok) {
        Write-Host '[ OK ] ' -ForegroundColor Green -NoNewline
    } else {
        Write-Host '[FAIL] ' -ForegroundColor Red -NoNewline
        $script:failures++
    }
    Write-Host $check -NoNewline
    if ($detail) { Write-Host "  ($detail)" -ForegroundColor DarkGray } else { Write-Host '' }
}

function Info([string]$text) { Write-Host "       $text" -ForegroundColor DarkGray }

# One setting from backend/.env.
function Get-EnvValue([string]$name) {
    if (-not (Test-Path -LiteralPath $envFile)) { return $null }
    foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
        if ($line -match "^\s*$name\s*=\s*(.*?)\s*$") { return $Matches[1].Trim('"', "'") }
    }
    return $null
}

# Writes one setting into backend/.env and leaves the rest alone. UTF-8 without a BOM (Node would
# misread the first key) and the file's own line endings.
function Set-EnvValue([string]$name, [string]$value) {
    $text = [System.IO.File]::ReadAllText($envFile)
    $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $pattern = "(?m)^[ `t]*$name[ `t]*=[^`r`n]*"
    if ([regex]::IsMatch($text, $pattern)) {
        $text = [regex]::Replace($text, $pattern, "$name=$($value.Replace('$', '$$'))")
    } else {
        if ($text.Length -gt 0 -and -not $text.EndsWith("`n")) { $text += $newline }
        $text += "$name=$value$newline"
    }
    [System.IO.File]::WriteAllText($envFile, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-Health([string]$baseUrl, [int]$timeoutSec = 5) {
    try { return Invoke-RestMethod -Uri "$($baseUrl.TrimEnd('/'))/health" -TimeoutSec $timeoutSec } catch { return $null }
}

# 'up' (tunnel and backend answer), 'no-backend' (the tunnel answers with 502-504), or 'down'.
function Get-TunnelState([string]$url) {
    if (-not $url) { return 'down' }
    try {
        $response = Invoke-WebRequest -Uri "$($url.TrimEnd('/'))/health" -TimeoutSec 10 -UseBasicParsing
        if ($response.StatusCode -eq 200 -and $response.Content -match '"voiceStack"') { return 'up' }
        return 'down'
    } catch {
        $status = 0
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        if ($status -in 502, 503, 504) { return 'no-backend' }
        return 'down'
    }
}

function Get-ListenerPids { @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique) }

# Stops every Pillion backend on the port, with its `node --watch` parent (which would otherwise
# start it again on the next file save, next to the new one). Anything else on the port is left alone.
function Stop-Backends {
    foreach ($id in Get-ListenerPids) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $id" -ErrorAction SilentlyContinue
        if (-not $proc -or $proc.CommandLine -notmatch 'src[\\/]server\.js') {
            Report $false "Port $Port is used by another program" "pid $id; stop it yourself"
            continue
        }
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $($proc.ParentProcessId)" -ErrorAction SilentlyContinue
        if ($parent -and $parent.CommandLine -match '--watch') { Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        Info "stopped backend pid $id"
    }
    Start-Sleep -Milliseconds 800
}

function Start-Backend {
    $command = "`$Host.UI.RawUI.WindowTitle = 'Pillion backend'; Set-Location -LiteralPath '$backendDir'; npm run dev"
    Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoExit', '-Command', $command | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        $health = Get-Health "http://127.0.0.1:$Port" 2
        if ($health) { return $health }
    }
    return $null
}

# A file another process is still writing ('' if it isn't there yet).
function Read-SharedText([string]$path) {
    try {
        $stream = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
        try { return (New-Object System.IO.StreamReader($stream, $true)).ReadToEnd() } finally { $stream.Dispose() }
    } catch {
        return ''
    }
}

# Starts a quick tunnel in its own (minimised) window and returns its https URL once it's registered.
function Start-Tunnel {
    $exe = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
    if (-not $exe -and (Test-Path 'C:\Program Files (x86)\cloudflared\cloudflared.exe')) { $exe = 'C:\Program Files (x86)\cloudflared\cloudflared.exe' }
    if (-not $exe) {
        Report $false 'cloudflared' 'not installed: winget install Cloudflare.cloudflared'
        return $null
    }
    # A dead tunnel's cloudflared (e.g. after the laptop slept) keeps running and keeps its log open,
    # so the old URL would be read back as the new one: stop it, and use a fresh log file each time.
    Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "--url http://localhost:$Port" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $log = Join-Path $env:TEMP "pillion-tunnel-$Port-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
    # cloudflared writes the log itself (--logfile, opened for shared reading); the window shows it too.
    $command = "`$Host.UI.RawUI.WindowTitle = 'Pillion tunnel'; & '$exe' tunnel --no-autoupdate --protocol http2 --logfile '$log' --url http://localhost:$Port"
    Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoExit', '-Command', $command -WindowStyle Minimized | Out-Null
    $url = $null
    for ($i = 0; $i -lt 45; $i++) {
        Start-Sleep -Seconds 1
        $text = Read-SharedText $log
        if (-not $url -and $text -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $url = $Matches[0] }
        if ($url -and $text -match 'Registered tunnel connection') { return $url }
    }
    return $url
}

# This laptop's address on the phone's Wi-Fi (not loopback, VPN/WARP or virtual adapters).
function Get-LanIp([string]$phoneAddress) {
    $subnet = if ($phoneAddress -match '^(\d+\.\d+\.\d+)\.\d+:\d+$') { $Matches[1] + '.' } else { $null }
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WARP|WSL|VirtualBox|VMware|VPN' -and $_.IPAddress -match '^(10|192\.168|172\.(1[6-9]|2\d|3[01]))\.' } |
        Sort-Object { if ($subnet -and $_.IPAddress.StartsWith($subnet)) { 0 } elseif ($_.InterfaceAlias -match 'Wi-?Fi|WLAN') { 1 } else { 2 } } |
        Select-Object -First 1 -ExpandProperty IPAddress
}

# adb output (stdout and stderr) as text, without PowerShell 5.1 turning stderr lines into errors.
function Invoke-Adb {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return ((& $adb @args 2>&1) | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $previous }
}

if (-not (Test-Path -LiteralPath $envFile)) { throw "backend/.env not found (copy backend/.env.example and fill it in)." }
Write-Host "Pillion start ($root)"

# ---- 1. Tunnel
$tunnel = Get-EnvValue 'PUBLIC_BASE_URL'
$tunnelState = if ($NewTunnel) { 'down' } else { Get-TunnelState $tunnel }
$envChanged = $false
if ($tunnelState -eq 'down') {
    if ($tunnel -and -not $NewTunnel) { Info "tunnel in backend/.env no longer answers: $tunnel" }
    Info 'starting a new cloudflared tunnel (window "Pillion tunnel")...'
    $newUrl = Start-Tunnel
    if ($newUrl) {
        Set-EnvValue 'PUBLIC_BASE_URL' $newUrl
        $tunnel = $newUrl
        $envChanged = $true
        Report $true 'New tunnel; PUBLIC_BASE_URL updated in backend/.env' $newUrl
    } else {
        Report $false 'New tunnel' "no URL from cloudflared; see the newest $env:TEMP\pillion-tunnel-$Port-*.log"
    }
} else {
    Info "keeping the tunnel in backend/.env: $tunnel"
}
$tunnelHost = if ($tunnel) { ([uri]$tunnel).Host } else { $null }

# ---- 2. Backend
$listeners = Get-ListenerPids
$health = Get-Health "http://127.0.0.1:$Port" 3
$reason = $null
if ($RestartBackend) { $reason = 'asked to restart' }
elseif ($listeners.Count -gt 1) { $reason = "$($listeners.Count) backends were listening on port $Port" }
elseif ($listeners.Count -eq 1 -and -not $health) { $reason = 'it listens but /health does not answer' }
elseif ($health -and $health.tunnel -ne $tunnelHost) { $reason = 'it was started with another tunnel URL' }
if ($reason) {
    Info "restarting the backend: $reason"
    Stop-Backends
    $health = Start-Backend
} elseif ($listeners.Count -eq 0) {
    Info 'starting the backend (npm run dev, window "Pillion backend")...'
    $health = Start-Backend
}
if ($health) {
    Report $true "Backend on this laptop" "voice $($health.voiceStack), llm $($health.llm), jev $($health.jev), maps $($health.maps), active agents $($health.activeAgents)"
    Report ($health.tools -and $health.tunnel -eq $tunnelHost) 'Backend uses the current tunnel' "$($health.tunnel)"
} else {
    Report $false 'Backend on this laptop' "no answer on http://127.0.0.1:$Port/health; see the 'Pillion backend' window"
}

$hostSetting = Get-EnvValue 'HOST'
$lanIp = Get-LanIp $Phone
if ($lanIp) {
    $hint = if ($hostSetting -ne '0.0.0.0') { "HOST=$hostSetting in backend/.env: set HOST=0.0.0.0 and use -RestartBackend" } else { "http://${lanIp}:$Port" }
    Report ([bool](Get-Health "http://${lanIp}:$Port" 3)) 'Backend over Wi-Fi' $hint
} else {
    Report $false 'Backend over Wi-Fi' 'no Wi-Fi address found on this laptop'
}

# A new quick tunnel's DNS name can take a few seconds to resolve everywhere.
$state = 'down'
for ($i = 0; $i -lt 6; $i++) {
    $state = Get-TunnelState $tunnel
    if ($state -eq 'up') { break }
    Start-Sleep -Seconds 5
}
Report ($state -eq 'up') 'Tunnel reaches the backend' $tunnel
$allowPublic = Get-EnvValue 'ALLOW_PUBLIC_RIDE_START'
if (-not $allowPublic) { $allowPublic = 'false (default)' }
Info "starting rides through the tunnel (phone on mobile data) needs ALLOW_PUBLIC_RIDE_START=true; now: $allowPublic"

# ---- 3. Phone
$serial = $null
if ($SkipPhone) {
    Info 'phone skipped (-SkipPhone)'
} elseif (-not (Test-Path -LiteralPath $adb)) {
    Report $false 'adb' "not found at $adb"
} else {
    # Wireless debugging gets a new port each time; old entries linger as offline.
    foreach ($line in (Invoke-Adb devices) -split "`n" | Select-Object -Skip 1) {
        if ($line -match '^(\S+)\s+(offline|unauthorized)') {
            Invoke-Adb disconnect $Matches[1] | Out-Null
            Info "removed stale adb entry $($Matches[1]) ($($Matches[2]))"
        }
    }
    $found = (Invoke-Adb mdns services) -split "`n" | ForEach-Object { if ($_ -match '_adb-tls-connect\._tcp\s+(\d+\.\d+\.\d+\.\d+:\d+)') { $Matches[1] } } | Select-Object -First 1
    if (-not $Phone) {
        $prompt = 'Phone IP:port from Settings > Developer options > Wireless debugging'
        if ($found) { $prompt += " [Enter = $found]" } else { $prompt += ' [Enter = already connected / USB phone]' }
        try { $Phone = (Read-Host $prompt).Trim() } catch { $Phone = '' }
        if (-not $Phone) { $Phone = $found }
    }
    if ($Phone) {
        $out = Invoke-Adb connect $Phone
        if ($out -match 'connected to') {
            $serial = $Phone
            Report $true "adb connected" $Phone
        } else {
            Report $false "adb connect $Phone" "$($out.Trim()). Unlock the phone and open Wireless debugging: the port changes each time."
        }
    } else {
        $serial = (Invoke-Adb devices) -split "`n" | Select-Object -Skip 1 |
            Where-Object { $_ -match '^(\S+)\s+device$' -and $_ -notmatch '^emulator-' } |
            ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
        Report ([bool]$serial) 'Phone connected to adb' $(if ($serial) { $serial } else { 'none: give its IP:port' })
    }

    if ($serial) {
        Invoke-Adb -s $serial reverse "tcp:$Port" "tcp:$Port" | Out-Null
        Report ((Invoke-Adb -s $serial reverse --list) -match "tcp:$Port\s+tcp:$Port") "adb reverse tcp:$Port" $serial
        Info "phone: $((Invoke-Adb -s $serial shell getprop ro.product.model).Trim()), app installed: $([bool]((Invoke-Adb -s $serial shell pm list packages app.pillion) -match 'app\.pillion'))"
        # From the phone itself. stdin stays open briefly: adb reverse drops a connection whose sender is done.
        $routes = [ordered]@{ 'adb reverse' = '127.0.0.1' }
        if ($lanIp) { $routes['Wi-Fi'] = $lanIp }
        foreach ($route in $routes.GetEnumerator()) {
            $reply = Invoke-Adb -s $serial shell "(printf 'GET /health HTTP/1.0\r\n\r\n'; sleep 2) | nc -w 4 $($route.Value) $Port 2>/dev/null | head -1"
            Report ($reply -match ' 200 ') "Phone reaches the backend via $($route.Key)" "$($route.Value):$Port"
        }
    }
}

Write-Host ''
if ($script:failures -eq 0) { Write-Host 'All checks OK.' -ForegroundColor Green } else { Write-Host "$($script:failures) check(s) failed." -ForegroundColor Red }
if ($serial) { Info "build + install the app: .\scripts\phone.ps1 -Serial $serial" }
if ($envChanged) { Info 'new tunnel: rebuild the app (phone.ps1) if you test on mobile data; Wi-Fi and adb routes are unaffected.' }
exit [int]($script:failures -gt 0)
