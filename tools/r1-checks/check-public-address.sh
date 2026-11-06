#!/usr/bin/env bash
# The US-1.4 public-address checklist (docs/deployment-guide.md §4-§5) against a real environment.
#
# Required:
#   APIORCH_PUBLIC_BASE_URL   what callers use, e.g. https://apiorch.r1.example.internal
#   APIORCH_NODE_API_URLS     each node's API port, reached directly, comma-separated (AC1 compares against one)
#   APIORCH_NODE_MGMT_URLS    each node's management port, comma-separated, e.g. http://node1:9091,http://node2:9091
#   APIORCH_CHECK_PIPELINE    a published pipeline that is safe to run repeatedly in this environment
# Optional:
#   APIORCH_TOKEN             bearer token with apiorch.run and apiorch.ops; never printed or stored
#   APIORCH_CHECK_INPUT       the run input as JSON (default {})
#   APIORCH_LOCATION_BASE     the public address a 202 must point at, when it differs from the address this
#                             script reaches the load balancer by (default APIORCH_PUBLIC_BASE_URL)
#   APIORCH_SHUTDOWN_SECONDS  with --during-shutdown: how long to send traffic (default 60)
#
# Usage:
#   check-public-address.sh                    AC1, AC2, AC3 routing, AC5, readiness on every node
#   check-public-address.sh --during-shutdown  AC6: sends runs through the load balancer for
#                                              APIORCH_SHUTDOWN_SECONDS while you stop one node gracefully
#                                              (SIGTERM), then reports every response that was not 202
# Exit status: 0 when every check passed, 1 otherwise.
set -uo pipefail

: "${APIORCH_PUBLIC_BASE_URL:?set APIORCH_PUBLIC_BASE_URL}"
: "${APIORCH_NODE_API_URLS:?set APIORCH_NODE_API_URLS}"
: "${APIORCH_NODE_MGMT_URLS:?set APIORCH_NODE_MGMT_URLS}"
: "${APIORCH_CHECK_PIPELINE:?set APIORCH_CHECK_PIPELINE}"
LB="${APIORCH_PUBLIC_BASE_URL%/}"
PUBLIC="${APIORCH_LOCATION_BASE:-$LB}"
PUBLIC="${PUBLIC%/}"
INPUT="${APIORCH_CHECK_INPUT:-}"
[ -n "$INPUT" ] || INPUT='{}'
IFS=',' read -r -a NODES <<< "$APIORCH_NODE_API_URLS"
IFS=',' read -r -a MGMT <<< "$APIORCH_NODE_MGMT_URLS"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0

auth=()
if [ -n "${APIORCH_TOKEN:-}" ]; then
  # kept in a header file so the token never appears in the process list
  printf 'Authorization: Bearer %s\n' "$APIORCH_TOKEN" > "$WORK/auth"
  auth=(-H "@$WORK/auth")
fi

result() {  # result <PASS|FAIL> <check> <detail>
  printf '%-4s  %-46s %s\n' "$1" "$2" "$3"
  [ "$1" = PASS ] || failures=$((failures + 1))
}

start_run() {  # start_run <base> <mode> <headers-file> <body-file> -> prints the status code
  curl -s -o "$4" -D "$3" -w '%{http_code}' "${auth[@]}" -X POST -H 'Content-Type: application/json' \
    -d "$INPUT" "$1/api/v1/pipelines/$APIORCH_CHECK_PIPELINE/runs?mode=$2"
}

apiorch_headers() {  # the X-Apiorch-* header names, sorted
  grep -i '^x-apiorch-' "$1" | cut -d: -f1 | tr 'A-Z' 'a-z' | sort | tr '\n' ' '
}

json_field() {  # json_field <file> <field>: the first "field":"value" in the body (the run's, before its steps')
  grep -o "\"$2\":\"[^\"]*\"" "$1" | head -1 | cut -d'"' -f4
}

if [ "${1:-}" = "--during-shutdown" ]; then
  seconds="${APIORCH_SHUTDOWN_SECONDS:-60}"
  echo "AC6: sending runs through $LB for ${seconds}s. Stop one node gracefully (SIGTERM) now."
  end=$((SECONDS + seconds)); sent=0
  : > "$WORK/codes"
  while [ $SECONDS -lt $end ]; do
    start_run "$LB" async "$WORK/h" "$WORK/b" >> "$WORK/codes"; echo >> "$WORK/codes"
    sent=$((sent + 1)); sleep 0.1
  done
  bad=$(grep -vc '^202$' "$WORK/codes")
  if [ "$bad" -eq 0 ]; then
    result PASS "AC6 no request lost during a graceful stop" "$sent runs, all 202"
  else
    result FAIL "AC6 no request lost during a graceful stop" "$bad of $sent not 202: $(sort "$WORK/codes" | uniq -c | tr '\n' ' ')"
  fi
  echo "Then confirm in GET $LB/api/v1/runs?pipeline=$APIORCH_CHECK_PIPELINE&status=RUNNING that nothing is left running."
  exit $((failures > 0))
fi

# AC1: the same answer through the load balancer as from a node directly
lb_code=$(start_run "$LB" sync "$WORK/lb.h" "$WORK/lb.b")
node_code=$(start_run "${NODES[0]%/}" sync "$WORK/node.h" "$WORK/node.b")
lb_status=$(json_field "$WORK/lb.b" status); node_status=$(json_field "$WORK/node.b" status)
if [ "$lb_code" = "$node_code" ] && [ "$lb_status" = "$node_status" ] \
    && [ "$(apiorch_headers "$WORK/lb.h")" = "$(apiorch_headers "$WORK/node.h")" ]; then
  result PASS "AC1 same response through the load balancer" "$lb_code $lb_status, headers $(apiorch_headers "$WORK/lb.h")"
else
  result FAIL "AC1 same response through the load balancer" "LB $lb_code $lb_status vs node $node_code $node_status"
fi

# AC2: a 202 points at the public address
code=$(start_run "$LB" async "$WORK/a.h" "$WORK/a.b")
location=$(grep -i '^location:' "$WORK/a.h" | cut -d' ' -f2- | tr -d '\r')
run_id=$(json_field "$WORK/a.b" runId)
if [ "$code" = 202 ] && [ "$location" = "$PUBLIC/api/v1/runs/$run_id" ]; then
  result PASS "AC2 Location is the public address" "$location"
else
  result FAIL "AC2 Location is the public address" "status $code, Location '$location', expected $PUBLIC/api/v1/runs/$run_id"
fi

# AC3: a callback path reaches apiorch with its path and query unchanged (the endpoint itself arrives in R2)
cb="/api/v1/callbacks/00000000-0000-0000-0000-000000000001/check?t=abc.def&x=1"
lb_cb=$(curl -s -o "$WORK/cb1" -w '%{http_code}' -X POST -H 'X-Callback-Token: check' -H 'Content-Type: application/json' -d '{}' "$LB$cb")
node_cb=$(curl -s -o "$WORK/cb2" -w '%{http_code}' -X POST -H 'X-Callback-Token: check' -H 'Content-Type: application/json' -d '{}' "${NODES[0]%/}$cb")
if [ "$lb_cb" = "$node_cb" ] && [ "$(json_field "$WORK/cb1" instance)" = "$(json_field "$WORK/cb2" instance)" ]; then
  result PASS "AC3 callback path routed unchanged" "$lb_cb $(json_field "$WORK/cb1" instance)"
else
  result FAIL "AC3 callback path routed unchanged" "LB $lb_cb vs node $node_cb"
fi

# AC5: the actuator is not published through the load balancer
code=$(curl -s -o "$WORK/act" -w '%{http_code}' "$LB/actuator/health")
if [ "$code" = 404 ] && grep -q 'urn:apiorch:problem:not-found' "$WORK/act"; then
  result PASS "AC5 actuator not published" "404 problem+json"
else
  result FAIL "AC5 actuator not published" "status $code"
fi

# L4: every node answers readiness on its management port (what the load balancer health check probes)
for m in "${MGMT[@]}"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "${m%/}/actuator/health/readiness")
  if [ "$code" = 200 ]; then result PASS "L4 readiness ${m%/}" "200"; else result FAIL "L4 readiness ${m%/}" "status $code"; fi
done

echo
if [ $failures -eq 0 ]; then echo "All checks passed. Run --during-shutdown next for AC6."; else echo "$failures check(s) failed."; fi
exit $((failures > 0))
