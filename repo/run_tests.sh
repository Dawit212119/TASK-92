#!/usr/bin/env bash
# =============================================================================
# CivicWorks – one-click test runner
#
# Usage:
#   ./run_tests.sh              # run everything (clean start)
#   ./run_tests.sh --no-rebuild # skip docker rebuild (faster when image is fresh)
#
# What it does:
#   1. Tears down any existing containers + volumes (clean DB)
#   2. Builds and starts PostgreSQL + app via docker compose
#   3. Waits until the app port is open
#   4. Seeds test data (accounts, bills, content items) via psql
#   5. Runs JUnit unit tests   (mvn test inside Maven)
#   6. Builds & runs pytest API tests inside Docker (connects to running app)
#   7. Prints a colour-coded summary: PASS / FAIL counts for both suites
#
# Prerequisites: docker, docker compose v2, mvn (Maven 3.x), Java 17+
# =============================================================================

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

REBUILD=true
for arg in "$@"; do
  [[ "$arg" == "--no-rebuild" ]] && REBUILD=false
done

# ── colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
info() { echo -e "${YELLOW}[INFO]${NC} $*"; }

UNIT_EXIT=0
API_EXIT=0

# ── 1. Clean-start Docker services ───────────────────────────────────────────
info "Stopping and removing previous containers + volumes..."
docker compose down -v --remove-orphans 2>/dev/null || true

if $REBUILD; then
  info "Building and starting services (this may take a few minutes on first run)..."
  docker compose up -d --build
else
  info "Starting services (no rebuild)..."
  docker compose up -d
fi

# ── 2. Wait for app ready (health), then Flyway schema in Postgres ───────────
# Raw TCP open is insufficient: another process can hold the port, or Tomcat can
# race with Flyway in odd environments. Prefer /actuator/health (permitAll in SecurityConfig).
HOST_APP_PORT="${HOST_APP_PORT:-18080}"
HEALTH_URL="http://127.0.0.1:${HOST_APP_PORT}/actuator/health"

app_ready() {
  if command -v curl >/dev/null 2>&1; then
    curl -sf "$HEALTH_URL" >/dev/null 2>&1
  else
    (echo >/dev/tcp/127.0.0.1/"${HOST_APP_PORT}") 2>/dev/null
  fi
}

info "Waiting for app health on ${HEALTH_URL} (fallback: TCP ${HOST_APP_PORT})..."
MAX_WAIT=180
WAIT=0
until app_ready; do
  sleep 3
  WAIT=$((WAIT + 3))
  if [[ $WAIT -ge $MAX_WAIT ]]; then
    fail "App did not become healthy within ${MAX_WAIT}s. Showing logs:"
    docker compose logs app | tail -40
    exit 1
  fi
  echo -n "."
done
echo ""
info "App responded (waited ${WAIT}s). Waiting for Flyway table 'accounts' in Postgres..."

MAX_SCHEMA_WAIT=120
SCHEMA_WAIT=0
while true; do
  tbl=$(docker compose exec -T db psql -U civicworks -d civicworks -tAc \
    "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='accounts'" \
    2>/dev/null | tr -d '[:space:]' || true)
  [[ "$tbl" == "1" ]] && break
  sleep 2
  SCHEMA_WAIT=$((SCHEMA_WAIT + 2))
  if [[ $SCHEMA_WAIT -ge $MAX_SCHEMA_WAIT ]]; then
    fail "accounts table not found after ${MAX_SCHEMA_WAIT}s (Flyway not applied?). App logs:"
    docker compose logs app | tail -50
    exit 1
  fi
  echo -n "."
done
echo ""
info "Database schema ready (waited ${SCHEMA_WAIT}s for migrations)."

# ── 3. Seed test data ────────────────────────────────────────────────────────
info "Seeding test data..."
docker compose exec -T db psql -U civicworks civicworks < API_tests/seed.sql
info "Seed complete."

# ── 4. Unit tests (Maven / JUnit) ─────────────────────────────────────────────
info "========================================="
info "Running unit tests (Maven / JUnit)..."
info "========================================="

MVN_OUTPUT=$(mktemp)
# Do not use `mvn -q` here: quiet mode omits Surefire "Tests run:" lines, so the
# count parsing below would get no matches; `grep` exits 1 and `set -e` aborts
# the script after a successful test run (false CI failure).
if mvn -B test 2>&1 | tee "$MVN_OUTPUT"; then
  pass "All JUnit unit tests passed."
else
  UNIT_EXIT=1
  fail "Some JUnit unit tests failed."
fi

# Extract counts from Maven output (bash regex — never fails on "no match" like grep -P)
UNIT_TOTAL=0
UNIT_FAILED=0
UNIT_ERRORS=0
while IFS= read -r line; do
  [[ $line =~ Tests\ run:\ ([0-9]+) ]] && UNIT_TOTAL=$((UNIT_TOTAL + ${BASH_REMATCH[1]}))
  [[ $line =~ Failures:\ ([0-9]+) ]] && UNIT_FAILED=$((UNIT_FAILED + ${BASH_REMATCH[1]}))
  [[ $line =~ Errors:\ ([0-9]+) ]] && UNIT_ERRORS=$((UNIT_ERRORS + ${BASH_REMATCH[1]}))
done < "$MVN_OUTPUT"
UNIT_PASSED=$(( UNIT_TOTAL - UNIT_FAILED - UNIT_ERRORS ))
rm -f "$MVN_OUTPUT"

# ── 5. API tests (pytest inside Docker) ──────────────────────────────────────
info "========================================="
info "Building API test image..."
info "========================================="
docker build -q -t civicworks-api-tests API_tests/

# Determine the compose project network
PROJECT=$(basename "$REPO_DIR" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]-_')
NETWORK="${PROJECT}_default"

# Verify network exists; fall back to bridge if not
if ! docker network inspect "$NETWORK" &>/dev/null; then
  # docker compose may prefix with the parent dir name
  NETWORK=$(docker network ls --format '{{.Name}}' | grep -i "${PROJECT}" | grep "default" | head -1)
fi

info "Using Docker network: ${NETWORK}"
info "========================================="
info "Running API tests (pytest)..."
info "========================================="

PYTEST_OUTPUT=$(mktemp)
if docker run --rm \
     --network "$NETWORK" \
     -e BASE_URL="http://app:8080" \
     civicworks-api-tests \
     pytest -v --tb=short --no-header tests/ 2>&1 | tee "$PYTEST_OUTPUT"; then
  pass "All API tests passed."
else
  API_EXIT=1
  fail "Some API tests failed."
fi

# Extract pytest summary line (avoid grep -P for macOS/BSD compatibility)
PYTEST_SUMMARY=$(grep -E '^=+ .*(passed|failed|error)' "$PYTEST_OUTPUT" | tail -1 || echo "No summary line found")
API_PASSED=0
API_FAILED=0
API_ERRORS=0
[[ $PYTEST_SUMMARY =~ ([0-9]+)\ passed ]] && API_PASSED=${BASH_REMATCH[1]}
[[ $PYTEST_SUMMARY =~ ([0-9]+)\ failed ]] && API_FAILED=${BASH_REMATCH[1]}
[[ $PYTEST_SUMMARY =~ ([0-9]+)\ errors? ]] && API_ERRORS=${BASH_REMATCH[1]}
rm -f "$PYTEST_OUTPUT"

# ── 6. Summary ────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    TEST SUITE SUMMARY                       ║"
echo "╠══════════════════════════════════════════════════════════════╣"
printf "║  %-20s  %4s total  %4s passed  %4s failed      ║\n" \
  "Unit (JUnit)" "${UNIT_TOTAL:-?}" "${UNIT_PASSED:-?}" "$((${UNIT_FAILED:-0} + ${UNIT_ERRORS:-0}))"
printf "║  %-20s  %4s total  %4s passed  %4s failed      ║\n" \
  "API (pytest)" "$(( ${API_PASSED:-0} + ${API_FAILED:-0} + ${API_ERRORS:-0} ))" \
  "${API_PASSED:-0}" "$(( ${API_FAILED:-0} + ${API_ERRORS:-0} ))"
echo "╚══════════════════════════════════════════════════════════════╝"

OVERALL_EXIT=$(( UNIT_EXIT + API_EXIT ))
if [[ $OVERALL_EXIT -eq 0 ]]; then
  pass "All suites passed."
else
  fail "One or more suites had failures. See output above."
  # Print failed test names from pytest output if available
  if [[ $API_EXIT -ne 0 ]]; then
    echo ""
    echo "API test failures:"
    docker run --rm \
      --network "$NETWORK" \
      -e BASE_URL="http://app:8080" \
      civicworks-api-tests \
      pytest --tb=line --no-header -q tests/ 2>&1 | grep -E "^FAILED" || true
  fi
fi

exit $OVERALL_EXIT
