#!/usr/bin/env bash
#
# Stop the application and its pgvector container.
#
# Spring Boot's docker-compose support normally stops the container on a clean
# shutdown (lifecycle-management: start_and_stop), but it is left running if the
# JVM is killed. This cleans up either way.
#
# Usage:
#   ./stop.sh            stop the app and the pgvector container
#   ./stop.sh --purge    also delete the container's volume (drops all embeddings)
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PORT=8085
PURGE=false
[[ "${1:-}" == "--purge" ]] && PURGE=true

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

echo
# Match only this project's Spring Boot process, not every JVM on the machine —
# this dev box runs a dozen unrelated Spring Boot services.
PIDS=$(pgrep -f "spring-boot:run.*okf-rag|okf-rag.*spring-boot:run" 2>/dev/null || true)
if [[ -z "$PIDS" ]]; then
  PIDS=$( (ss -ltnp 2>/dev/null || true) | grep ":$PORT " | grep -oE 'pid=[0-9]+' | cut -d= -f2 || true)
fi

if [[ -n "$PIDS" ]]; then
  echo "$PIDS" | xargs -r kill 2>/dev/null || true
  sleep 2
  # Escalate only if it ignored SIGTERM.
  echo "$PIDS" | xargs -r -I{} sh -c 'kill -0 {} 2>/dev/null && kill -9 {} 2>/dev/null' || true
  ok "Stopped application (pid: $(echo "$PIDS" | tr '\n' ' '))"
else
  warn "No running application found on port $PORT"
fi

if docker compose ps -q pgvector 2>/dev/null | grep -q .; then
  if $PURGE; then
    docker compose down -v >/dev/null 2>&1
    ok "pgvector stopped and volume deleted — embeddings are gone"
    echo "      They are rebuilt from okf-bundle/ on next start."
  else
    docker compose down >/dev/null 2>&1
    ok "pgvector container stopped"
  fi
else
  warn "pgvector container was not running"
fi

echo
