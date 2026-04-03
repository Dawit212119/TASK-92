# =============================================================================
# CivicWorks – one-click test runner (Windows PowerShell)
#
# Usage (from repo root):
#   .\run_tests.ps1              # clean start, rebuild images
#   .\run_tests.ps1 -NoRebuild   # skip docker rebuild (faster)
#
# Prerequisites: Docker Desktop, Maven 3.x + Java 17+ on PATH
# =============================================================================
param(
    [switch]$NoRebuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $RepoDir

# ── colour helpers ────────────────────────────────────────────────────────────
function Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red }
function Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Yellow }

$UnitExit = 0
$ApiExit  = 0

# ── 1. Clean-start Docker services ───────────────────────────────────────────
Info "Stopping and removing previous containers + volumes..."
docker compose down -v --remove-orphans 2>$null

if (-not $NoRebuild) {
    Info "Building and starting services (may take a few minutes on first run)..."
    docker compose up -d --build
} else {
    Info "Starting services (no rebuild)..."
    docker compose up -d
}

# ── 2. Wait for actuator health, then Flyway schema (avoids seeding empty DB) ─
$HostAppPort = if ($env:HOST_APP_PORT) { [int]$env:HOST_APP_PORT } else { 18080 }
$HealthUrl = "http://127.0.0.1:$HostAppPort/actuator/health"
Info "Waiting for app health at $HealthUrl ..."
$MaxWait = 180
$Waited  = 0
$Ready   = $false

while (-not $Ready -and $Waited -lt $MaxWait) {
    try {
        $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) { $Ready = $true }
    } catch {
        Start-Sleep -Seconds 3
        $Waited += 3
        Write-Host -NoNewline "."
    }
}
Write-Host ""

if (-not $Ready) {
    Fail "App did not become healthy within ${MaxWait}s. Showing logs:"
    docker compose logs app | Select-Object -Last 40
    exit 1
}
Info "App healthy (waited ${Waited}s). Waiting for Flyway table 'accounts' in Postgres..."

$MaxSchema = 120
$SchemaWait = 0
$SchemaOk = $false
while (-not $SchemaOk -and $SchemaWait -lt $MaxSchema) {
    $checkSql = "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='accounts'"
    $out = docker compose exec -T db psql -U civicworks -d civicworks -tAc "$checkSql" 2>$null
    if (($out -replace '\s', '') -eq '1') { $SchemaOk = $true } else {
        Start-Sleep -Seconds 2
        $SchemaWait += 2
        Write-Host -NoNewline "."
    }
}
Write-Host ""

if (-not $SchemaOk) {
    Fail "accounts table not found after ${MaxSchema}s. Showing app logs:"
    docker compose logs app | Select-Object -Last 50
    exit 1
}
Info "Database schema ready (waited ${SchemaWait}s)."

# ── 3. Seed test data ────────────────────────────────────────────────────────
Info "Seeding test data..."
Get-Content "API_tests\seed.sql" | docker compose exec -T db psql -U civicworks civicworks
Info "Seed complete."

# ── 4. Unit tests (Maven / JUnit) ─────────────────────────────────────────────
Info "========================================="
Info "Running unit tests (Maven / JUnit)..."
Info "========================================="

$MvnOutput = & mvn -B test 2>&1
$MvnOutput | Write-Host

if ($LASTEXITCODE -eq 0) {
    Pass "All JUnit unit tests passed."
} else {
    $UnitExit = 1
    Fail "Some JUnit unit tests failed."
}

# Parse Maven counts
$UnitTotal  = ($MvnOutput | Select-String 'Tests run: (\d+)').Matches |
               ForEach-Object { [int]$_.Groups[1].Value } |
               Measure-Object -Sum | Select-Object -ExpandProperty Sum
$UnitFailed = ($MvnOutput | Select-String 'Failures: (\d+)').Matches |
               ForEach-Object { [int]$_.Groups[1].Value } |
               Measure-Object -Sum | Select-Object -ExpandProperty Sum
$UnitErrors = ($MvnOutput | Select-String 'Errors: (\d+)').Matches |
               ForEach-Object { [int]$_.Groups[1].Value } |
               Measure-Object -Sum | Select-Object -ExpandProperty Sum
$UnitPassed = $UnitTotal - $UnitFailed - $UnitErrors

# ── 5. API tests (pytest inside Docker) ──────────────────────────────────────
Info "========================================="
Info "Building API test image..."
Info "========================================="
docker build -q -t civicworks-api-tests API_tests/

# Determine compose project network
$ProjectRaw  = (Split-Path -Leaf $RepoDir).ToLower() -replace '[^a-z0-9_-]', ''
$NetworkName = "${ProjectRaw}_default"

# Verify network; fall back to first match
$NetworkExists = docker network ls --format '{{.Name}}' |
                  Where-Object { $_ -eq $NetworkName }
if (-not $NetworkExists) {
    $NetworkName = docker network ls --format '{{.Name}}' |
                    Where-Object { $_ -like "*${ProjectRaw}*default*" } |
                    Select-Object -First 1
}
Info "Using Docker network: $NetworkName"

Info "========================================="
Info "Running API tests (pytest)..."
Info "========================================="

$PytestOutput = docker run --rm `
    --network $NetworkName `
    -e BASE_URL="http://app:8080" `
    civicworks-api-tests `
    pytest -v --tb=short --no-header tests/ 2>&1

$PytestOutput | Write-Host

if ($LASTEXITCODE -eq 0) {
    Pass "All API tests passed."
} else {
    $ApiExit = 1
    Fail "Some API tests failed."
}

# Parse pytest summary
$SummaryLine = ($PytestOutput | Select-String '^\=+ .*(passed|failed|error)') |
                Select-Object -Last 1
$ApiPassed = if ($SummaryLine -match '(\d+) passed') { [int]$Matches[1] } else { 0 }
$ApiFailed = if ($SummaryLine -match '(\d+) failed') { [int]$Matches[1] } else { 0 }
$ApiErrors = if ($SummaryLine -match '(\d+) error')  { [int]$Matches[1] } else { 0 }
$ApiTotal  = $ApiPassed + $ApiFailed + $ApiErrors

# ── 6. Summary ────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════════════╗"
Write-Host "║                    TEST SUITE SUMMARY                       ║"
Write-Host "╠══════════════════════════════════════════════════════════════╣"
Write-Host ("║  {0,-20}  {1,4} total  {2,4} passed  {3,4} failed      ║" -f `
    "Unit (JUnit)", $UnitTotal, $UnitPassed, ($UnitFailed + $UnitErrors))
Write-Host ("║  {0,-20}  {1,4} total  {2,4} passed  {3,4} failed      ║" -f `
    "API (pytest)", $ApiTotal, $ApiPassed, ($ApiFailed + $ApiErrors))
Write-Host "╚══════════════════════════════════════════════════════════════╝"

$OverallExit = $UnitExit + $ApiExit
if ($OverallExit -eq 0) {
    Pass "All suites passed."
} else {
    Fail "One or more suites had failures. See output above."
}

exit $OverallExit
