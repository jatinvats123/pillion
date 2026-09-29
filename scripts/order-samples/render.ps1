# Renders the mock order screens (order_*.html: invented brands, names and numbers) to 1080x2340
# PNGs, like a phone screenshot, into the debug build's assets (the debug card's "Scan test image").
# order_1_clean goes into the main assets instead: every build's "Try a sample order screen".
#   .\scripts\order-samples\render.ps1
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$src = Join-Path $PSScriptRoot '..\..\android\app\src'
Get-ChildItem $PSScriptRoot -Filter 'order_*.html' | ForEach-Object {
    $set = if ($_.BaseName -eq 'order_1_clean') { 'main' } else { 'debug' }
    $out = Join-Path $src "$set\assets\order_samples"
    New-Item -ItemType Directory -Force $out | Out-Null
    $png = Join-Path (Resolve-Path $out).Path ($_.BaseName + '.png')
    $page = 'file:///' + $_.FullName.Replace('\', '/')
    & $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=3 --window-size=360,780 "--screenshot=$png" $page 2>$null | Out-Null
    Write-Host "$set/$($_.BaseName).png"
}
