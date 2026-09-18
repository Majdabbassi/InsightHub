#!/usr/bin/env bash
# Seeds InsightHub with a demo user, project, and the sample CSVs.
#
#   ./scripts/seed-demo.sh
#
# Requires a running stack (see ./scripts/run-demo.sh). Uses only curl and
# coreutils so it runs on any Linux/macOS box; the Windows equivalent is
# scripts/seed-demo.ps1.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BACKEND_URL:-http://localhost:8080}"
EMAIL="${DEMO_EMAIL:-demo@insighthub.dev}"
PASSWORD="${DEMO_PASSWORD:-demo-password-123}"
FULL_NAME="${DEMO_FULL_NAME:-Demo User}"
PROJECT_NAME="E-commerce Demo"

die() { echo "error: $*" >&2; exit 1; }

echo "Checking backend at ${BASE_URL}/api/health ..."
curl -fsS "${BASE_URL}/api/health" >/dev/null 2>&1 \
    || die "backend is not reachable - start the stack first (./scripts/run-demo.sh)"

echo "Registering ${EMAIL} ..."
REGISTER_CODE="$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "${BASE_URL}/api/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"fullName\":\"${FULL_NAME}\"}")"
if [ "${REGISTER_CODE}" != "201" ] && [ "${REGISTER_CODE}" != "409" ]; then
    die "register returned HTTP ${REGISTER_CODE} - unexpected response"
fi
[ "${REGISTER_CODE}" = "201" ] && echo "  -> created new account" || echo "  -> account already exists"

echo "Logging in ..."
TOKEN="$(curl -fsS -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["token"])')"
[ -n "${TOKEN}" ] || die "empty token from login"

AUTH="Authorization: Bearer ${TOKEN}"

echo "Creating project '${PROJECT_NAME}' ..."
PROJECT_JSON="$(curl -fsS -X POST "${BASE_URL}/api/projects" \
    -H "${AUTH}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${PROJECT_NAME}\",\"description\":\"Created by scripts/seed-demo.sh with the sample CSVs.\"}")"
PROJECT_ID="$(printf '%s' "${PROJECT_JSON}" | python3 -c 'import sys, json; print(json.load(sys.stdin)["id"])')"
[ -n "${PROJECT_ID}" ] || die "failed to read project id"

for CSV in customers.csv orders.csv order_items.csv; do
    SAMPLE="samples/${CSV}"
    [ -f "${SAMPLE}" ] || die "missing sample file ${SAMPLE}"
    echo "Uploading ${SAMPLE} ..."
    curl -fsS -X POST "${BASE_URL}/api/projects/${PROJECT_ID}/datasets" \
        -H "${AUTH}" \
        -F "file=@${SAMPLE}" >/dev/null
done

echo "Analysis is triggered per dataset from the UI; relationships/insights render automatically."
echo
echo "Demo account:  ${EMAIL} / ${PASSWORD}"
echo "Project:       ${PROJECT_NAME} (id ${PROJECT_ID})"
echo "Open http://localhost:4200 and log in."