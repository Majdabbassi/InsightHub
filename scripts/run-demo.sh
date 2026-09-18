#!/usr/bin/env bash
# One-command startup for InsightHub on Linux/macOS.
#
#   ./scripts/run-demo.sh [--pull-model]
#
# - Creates .env from .env.example on first run, generating a strong JWT_SECRET.
# - Builds and starts the whole stack with Docker Compose.
# - Waits for the backend to come up.
# - With --pull-model, pulls the default Ollama model into the container.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is required but was not found on PATH." >&2
    exit 1
fi

if [ ! -f .env ]; then
    echo "Creating .env from .env.example (with a generated JWT_SECRET)..."
    cp .env.example .env

    if command -v openssl >/dev/null 2>&1; then
        SECRET="$(openssl rand -base64 48)"
    else
        SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))' 2>/dev/null \
            || head -c 48 /dev/urandom | base64)"
    fi
    # Backslash-safe replacement (macOS sed allows \n in the pattern).
    sed -i.bak -e "s|^JWT_SECRET=.*|JWT_SECRET=${SECRET}|" .env 2>/dev/null \
        || sed -i '' -e "s|^JWT_SECRET=.*|JWT_SECRET=${SECRET}|" .env
    rm -f .env.bak
    echo "  -> JWT_SECRET written. Edit .env if you want a different one."
fi

echo "Building and starting services (first build takes a while)..."
docker compose up -d --build

echo "Waiting for the backend to become healthy..."
for _ in $(seq 1 120); do
    if curl -fsS http://localhost:8080/api/health >/dev/null 2>&1; then
        echo "Backend is up."
        break
    fi
    sleep 1
done

if [ "${1:-}" = "--pull-model" ]; then
    MODEL="$(grep -E '^OLLAMA_MODEL=' .env | tail -1 | cut -d= -f2)"
    MODEL="${MODEL:-llama3.2:3b}"
    echo "Pulling Ollama model '${MODEL}' (first run only; may take minutes)..."
    docker exec -it data-ollama ollama pull "${MODEL}"
fi

echo
echo "InsightHub is running:"
echo "  Frontend     http://localhost:4200"
echo "  Backend API  http://localhost:8080/api"
echo "  phpMyAdmin   http://localhost:8081"
echo
echo "Next step: create an account in the UI, then upload the demo CSVs from samples/."
echo "You can also seed everything automatically: ./scripts/seed-demo.sh"