# Pull labelled AquaScope moisture scans from a connected phone, then train scorer weights.
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$OutDir = Join-Path $Root "data\ultrasonic\dataset"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Out = Join-Path $OutDir "locations.json"

Write-Host "Checking device..."
$devs = adb devices 2>&1 | Out-String
if ($devs -notmatch "(?m)^[^\s]+\s+device\s*$") {
    Write-Error "No authorized device. Unlock phone and Allow USB debugging, then retry."
}

Write-Host "Pulling files/aquascope_data/locations.json ..."
adb exec-out run-as com.aquascope cat files/aquascope_data/locations.json | Set-Content -Path $Out -Encoding utf8
$len = (Get-Item $Out).Length
Write-Host "Saved $Out ($len bytes)"
if ($len -lt 10) {
    Write-Error "Empty pull - teach dry/moist scans on the phone first."
}

python "$Root\tools\train_ultrasonic_scorer.py" $Out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Done. Rebuild/install the app to load assets/ultrasonic/trained_weights.json"
