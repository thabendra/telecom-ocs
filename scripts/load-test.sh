#!/usr/bin/env bash
# TE-2: starts docker-compose.load.yml, fills the database, and runs one load test. Exit code 0 only when the
# test's limits held. The summary goes to load/results/.
#
# Usage: scripts/load-test.sh <charging|rate-limit|overload>   (from the project root)
#   TARGET_RPS=100 P99_MS=50 DURATION=2m   charging: the load and the limit (CH-9)
#   DB_POOL_SIZE=10                        the backend's database pool and slots
#   KEEP=1                                 leave the containers running afterwards
# Needs: scripts/generate-dev-certs.sh, and the jars:
#   mvn -DskipTests package && mvn -f token-server/pom.xml -DskipTests package
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite the container paths below

TEST="${1:?usage: load-test.sh <charging|rate-limit|overload>}"
COMPOSE=(docker compose -f docker-compose.load.yml)

case "$TEST" in
  charging|rate-limit|overload) ;;
  *) echo "Unknown test: $TEST" >&2; exit 2 ;;
esac

# rate-limit tests the real limit. The others send everything through one gateway, far over its share, and measure
# something else: latency and the database slots, not 429s
if [ "$TEST" != rate-limit ]; then
  export RATE_LIMIT_BURST=2000 RATE_LIMIT_REFILL=1000
fi

mkdir -p load/results
"${COMPOSE[@]}" up -d --build backend || exit 1

echo "waiting for the backend ..."
ready=""
for _ in $(seq 1 60); do
  if "${COMPOSE[@]}" exec -T backend curl -s -m 3 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    ready=yes; break
  fi
  sleep 3
done
[ -n "$ready" ] || { echo "The backend did not start" >&2; "${COMPOSE[@]}" logs --tail 30 backend; exit 1; }

"${COMPOSE[@]}" exec -T oracle sqlplus -s telecom/telecom@FREEPDB1 < load/seed.sql || exit 1

k6() {   # k6 <results name> <script> [environment NAME=value ...]
  local name="$1" script="$2"; shift 2
  local env=()
  for pair in "$@"; do env+=(-e "$pair"); done
  "${COMPOSE[@]}" run --rm --no-deps k6 run --quiet "${env[@]}" --summary-export="/results/$name.json" "/load/$script"
}

case "$TEST" in
  charging)
    k6 charging charging.js "TARGET_RPS=${TARGET_RPS:-100}" "P99_MS=${P99_MS:-50}" "DURATION=${DURATION:-2m}"
    result=$? ;;
  rate-limit)
    k6 rate-limit-flood rate-limit.js ROLE=flood GATEWAY=pgw-colombo-01 &
    flood=$!
    k6 rate-limit-steady rate-limit.js ROLE=steady GATEWAY=pgw-kandy-01
    steady=$?
    wait "$flood"
    flooded=$?
    echo "over its limit: exit $flooded; the other gateway: exit $steady"
    result=$(( flooded != 0 || steady != 0 )) ;;
  overload)
    k6 overload overload.js
    result=$?
    echo "--- backend: refused to keep slots, and refused with no slot at all"
    "${COMPOSE[@]}" exec -T backend curl -s http://localhost:8081/actuator/prometheus \
      | grep -E '^ocs_(load_shed|bulkhead_rejected)_total' ;;
esac

[ -n "${KEEP:-}" ] || "${COMPOSE[@]}" --profile run down
exit "$result"
