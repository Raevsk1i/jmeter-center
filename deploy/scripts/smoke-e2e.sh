#!/usr/bin/env bash
set -euo pipefail
API="${API:-http://localhost:8080}"
AUTH=(-u admin:admin)

echo "== health =="
curl -sf "${AUTH[@]}" "$API/actuator/health" | grep -q UP

echo "== configure local fixtures =="
curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' -X PUT "$API/api/v1/settings/bitbucket" \
  -d '{"mode":"local","fixtureRoot":"'"${FIXTURE_ROOT:-/workspace/fixtures/bitbucket}"'","workspace":"local","repo":"local"}' >/dev/null

echo "== sync systems =="
curl -sf "${AUTH[@]}" -X POST "$API/api/v1/bitbucket/sync" >/dev/null

SYS=$(curl -sf "${AUTH[@]}" "$API/api/v1/systems" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
GEN=$(curl -sf "${AUTH[@]}" "$API/api/v1/generators" | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
GROUP=$(curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"name":"Smoke"}' "$API/api/v1/test-groups")
GID=$(echo "$GROUP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
TEST=$(curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Smoke Perf\",\"systemId\":\"$SYS\",\"groupId\":\"$GID\",\"jmxPath\":\"perf_test/test.jmx\",\"testType\":\"PERF\",\"defaultProperties\":{}}" \
  "$API/api/v1/tests")
TID=$(echo "$TEST" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

echo "== start run =="
RUN=$(curl -sf "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"testDefinitionId\":\"$TID\",\"masterGeneratorId\":\"$GEN\",\"slaveGeneratorIds\":[],\"properties\":{},\"startNow\":true}" \
  "$API/api/v1/runs")
RID=$(echo "$RUN" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

for i in $(seq 1 30); do
  STATUS=$(curl -sf "${AUTH[@]}" "$API/api/v1/runs/$RID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "status=$STATUS"
  case "$STATUS" in
    COMPLETED) echo "SMOKE OK run=$RID"; exit 0 ;;
    FAILED|CANCELLED) echo "SMOKE FAILED status=$STATUS"; curl -sf "${AUTH[@]}" "$API/api/v1/runs/$RID/events"; exit 1 ;;
  esac
  sleep 1
done
echo "SMOKE TIMEOUT"; exit 1
