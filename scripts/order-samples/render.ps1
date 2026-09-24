# Renders the mock order screens (order_*.html: invented brands, names and numbers) to 1080x2340
# PNGs, like a phone screenshot, into the debug build's assets (the debug card's "Scan test image").
#   .\scripts\order-samples\render.ps1
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$out = Join-Path $PSScriptRoot '..\..\android\app\src\debug\assets\order_samples'
New-Item -ItemType Directory -Force $out | Out-Null
$out = (Resolve-Path $out).Path
Get-ChildItem $PSScriptRoot -Filter 'order_*.html' | ForEach-Object {
    $png = Join-Path $out ($_.BaseName + '.png')
    $page = 'file:///' + $_.FullName.Replace('\', '/')
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=3 --window-size=360,780 "--screenshot=$png" $page 2>$null | Out-Null
    Write-Host "$($_.BaseName).png"
}
