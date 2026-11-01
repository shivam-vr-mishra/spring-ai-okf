#!/usr/bin/env bash
#
# Start the OKF + Spring AI application.
#
# Runs preflight checks first, because every one of these has actually broken
# this project at least once:
#   - system Maven is 3.6.3 on Java 17, too old for Spring Boot 4
#   - Ollama must be on the host with both models pulled
#   - Docker must be up for pgvector
#   - port 8085 must be free (8080 was already taken on the dev machine)
#   - low free RAM makes inference 50x slower through swap thrashing
#
# Usage:
#   ./start.sh                 preflight checks, then run
#   ./start.sh --skip-checks   straight to run
#   ./start.sh --check         checks only, don't start
#   ./start.sh --pull          pull any missing Ollama models, then run
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

CHAT_MODEL="llama3.2:3b"
EMBED_MODEL="nomic-embed-text"
PORT=8085
OLLAMA_URL="http://localhost:11434"
REQUIRED_JAVA=21
MIN_FREE_MB=3000

SKIP_CHECKS=false
CHECK_ONLY=false
AUTO_PULL=false
for arg in "$@"; do
  case "$arg" in
    --skip-checks) SKIP_CHECKS=true ;;
    --check)       CHECK_ONLY=true ;;
    --pull)        AUTO_PULL=true ;;
    -h|--help)     sed -n '3,16p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

red()  { printf '\033[31m%s\033[0m\n' "$*"; }
grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()  { printf '\033[33m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

FAILED=0
fail() { bad "$@"; FAILED=1; }

# ---------------------------------------------------------------------------
# Java 21 — must be found explicitly. The system default here is 17, and Maven
# inherits JAVA_HOME, so relying on `java` on PATH silently builds wrong.
# ---------------------------------------------------------------------------
find_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    local v
    v=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
    [[ "$v" == "$REQUIRED_JAVA" ]] && { echo "$JAVA_HOME"; return 0; }
  fi
  local d v
  for d in /usr/lib/jvm/*/; do
    [[ -x "$d/bin/java" ]] || continue
    v=$("$d/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
    [[ "$v" == "$REQUIRED_JAVA" ]] && { echo "${d%/}"; return 0; }
  done
  return 1
}

check_java() {
  if JAVA_HOME_FOUND=$(find_java_home); then
    export JAVA_HOME="$JAVA_HOME_FOUND"
    ok "Java $REQUIRED_JAVA  →  $JAVA_HOME"
  else
    fail "Java $REQUIRED_JAVA not found. Spring AI 2.0 needs 17+, 21 recommended."
    echo "      Install:  sudo apt install openjdk-21-jdk"
  fi
}

# ---------------------------------------------------------------------------
# Docker — pgvector runs in a container started by Spring Boot itself.
# ---------------------------------------------------------------------------
check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    fail "docker not installed — pgvector runs in a container"
    return
  fi
  if ! docker info >/dev/null 2>&1; then
    fail "Docker daemon not running.  sudo systemctl start docker"
    return
  fi
  ok "Docker is running"

  # Informational only: Spring Boot starts the container itself on boot.
  if docker compose ps --status running 2>/dev/null | grep -q pgvector; then
    local p
    p=$(docker compose port pgvector 5432 2>/dev/null || echo "?")
    ok "pgvector already up on $p"
  else
    warn "pgvector not running — Spring Boot will start it from compose.yaml"
  fi
}

# ---------------------------------------------------------------------------
# Ollama — runs natively on the host, NOT in a container (see compose.yaml).
# ---------------------------------------------------------------------------
check_ollama() {
  if ! curl -sf -m 5 "$OLLAMA_URL/api/tags" >/dev/null 2>&1; then
    fail "Ollama not reachable at $OLLAMA_URL"
    echo "      Start it:  ollama serve"
    return
  fi
  ok "Ollama is running at $OLLAMA_URL"

  local installed model
  installed=$(ollama list 2>/dev/null | tail -n +2 | awk '{print $1}')
  for model in "$CHAT_MODEL" "$EMBED_MODEL"; do
    # `ollama list` prints nomic-embed-text as nomic-embed-text:latest
    if grep -qE "^${model}(:latest)?$" <<<"$installed"; then
      ok "model $model"
    elif $AUTO_PULL; then
      ylw "  ↓ pulling $model ..."
      ollama pull "$model" && ok "model $model pulled"
    else
      fail "model $model missing.  ollama pull $model   (or rerun with --pull)"
    fi
  done
}

# ---------------------------------------------------------------------------
# Port — 8080 was already taken on the dev machine, hence 8085.
# ---------------------------------------------------------------------------
check_port() {
  local listeners
  listeners=$( (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep ":$PORT " || true )
  if [[ -n "$listeners" ]]; then
    fail "Port $PORT is already in use:"
    echo "      $listeners"
    echo "      Stop it, or override:  ./start.sh  SERVER_PORT=xxxx"
  else
    ok "Port $PORT is free"
  fi
}

# ---------------------------------------------------------------------------
# Memory — the real performance bottleneck on this machine. Measured prefill
# collapsed to 3.8 tok/s (≈50x slower than the CPU can do) when swap filled up.
# ---------------------------------------------------------------------------
check_memory() {
  local avail swap_used swap_total
  avail=$(free -m | awk '/^Mem:/ {print $7}')
  swap_used=$(free -m | awk '/^Swap:/ {print $3}')
  swap_total=$(free -m | awk '/^Swap:/ {print $2}')

  if (( avail < MIN_FREE_MB )); then
    warn "Only ${avail}MB RAM available (want ≥${MIN_FREE_MB}MB)."
    echo "      The app will run, but LLM answers may take many minutes."
    echo "      Retrieval endpoints (/api/search, /api/retrieve) stay fast."
  else
    ok "${avail}MB RAM available"
  fi

  if (( swap_total > 0 )) && (( swap_used * 100 / swap_total > 90 )); then
    warn "Swap is ${swap_used}/${swap_total}MB — inference will thrash."
    echo "      Free memory by stopping unused services before benchmarking."
  fi
}

# ---------------------------------------------------------------------------

if ! $SKIP_CHECKS; then
  echo
  grn "Preflight checks"
  check_java
  check_docker
  check_ollama
  check_port
  check_memory
  echo

  if (( FAILED )); then
    red "Preflight failed — fix the ✗ items above."
    exit 1
  fi
  $CHECK_ONLY && { grn "All checks passed."; exit 0; }
else
  # Still need JAVA_HOME even when skipping checks.
  JAVA_HOME_FOUND=$(find_java_home) || { red "Java $REQUIRED_JAVA not found"; exit 1; }
  export JAVA_HOME="$JAVA_HOME_FOUND"
fi

grn "Starting on http://localhost:$PORT   (Ctrl-C to stop)"
echo

# ./mvnw, never `mvn`: system Maven is 3.6.3 and too old for Spring Boot 4.
exec ./mvnw -B -ntp spring-boot:run
