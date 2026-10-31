#!/usr/bin/env bash
# VP-3 / VP-4: checks the local VPN demo (docker-compose.vpn.yml, already up) from the operator's side.
# Exit code 0 only when every check passes.
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite the container paths below

COMPOSE=(docker compose -f docker-compose.vpn.yml)
FAILED=0

check() {   # check <description> <expected: open|closed> <host> <port>
    if "${COMPOSE[@]}" exec -T wg-operator nc -z -w 3 "$3" "$4" >/dev/null 2>&1; then actual=open; else actual=closed; fi
    if [ "$actual" = "$2" ]; then echo "PASS  $1 ($3:$4 $actual)"; else echo "FAIL  $1 ($3:$4 $actual, expected $2)"; FAILED=1; fi
}

echo "tunnel:"
"${COMPOSE[@]}" exec -T wg-operator wg show wg0 latest-handshakes || FAILED=1

check "gateway API through the tunnel"          open   172.30.2.10 8443
check "token server through the tunnel"         open   172.30.2.11 9443
check "public API is not for gateways"          closed 172.30.2.10 8080
check "management port is not for gateways"     closed 172.30.2.10 8081
check "Oracle is never reachable from outside"  closed 172.30.2.20 1521
check "Redis is never reachable from outside"   closed 172.30.2.21 6379

echo "the simulator's test subscriber (once; the backend has created the tables by now):"
"${COMPOSE[@]}" exec -T oracle sqlplus -s telecom/telecom@FREEPDB1 <<'SQL' || FAILED=1
MERGE INTO subscriber s USING (SELECT '94771234567' AS msisdn FROM dual) d ON (s.msisdn = d.msisdn)
WHEN NOT MATCHED THEN INSERT (msisdn, full_name, nic, balance, plan_id)
    VALUES ('94771234567', 'Kamal Perera', '200012345678', 500, (SELECT id FROM plan WHERE code = 'BASIC'));
COMMIT;
SQL

echo "a charging session through the tunnel:"
# the first call to a just-started backend can take longer than the gateway's time budget (cold JVM,
# first token key fetch); the gateway then fails open, as it should, so one more session is allowed
for try in 1 2; do
    session=$("${COMPOSE[@]}" run --rm --build simulator 2>&1)
    echo "$session" | grep -E "TRACE|START|ALLOW|UPDATE|RELEASE|BLOCK|FAIL OPEN|POLL|ERROR"
    echo "$session" | grep -q "RELEASE\|BLOCK  traffic" && break
done
if echo "$session" | grep -q "RELEASE\|BLOCK  traffic"; then
    echo "PASS  simulator charged a session through the tunnel"
else
    echo "FAIL  simulator could not finish a session"; FAILED=1
fi

exit $FAILED
