#!/usr/bin/env bash
# Runs the in-client benchmarks against a running Pumpkin server on localhost:25565.
# Each scenario is a fresh client launch. Results land in fabric/run/pumpkin-patch-results/.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
WARMUP="${WARMUP:-30}"; SECONDS_MEASURED="${SECONDS_MEASURED:-60}"
run() { # label engine mode pingEvery mods...
  local label="$1" engine="$2" mode="$3" ping="$4"; shift 4
  local mods="$root/fabric/run/bench-mods/$label"
  scripts/install-mods.sh "$mods" "$@" > /dev/null
  i=$((i + 1))
  watch_client "[$i/$total] bench $label ($engine)" "run/client-bench-$label.log" \
    "bench:$WARMUP:$SECONDS_MEASURED" -- \
    ./gradlew -q :fabric:runClient --no-configuration-cache \
    -Dpumpkinpatch.autopilot=bench -Dpumpkinpatch.mode="$mode" -Dpumpkinpatch.engine="$engine" \
    -Dpumpkinpatch.mods="$mods" -Dpumpkinpatch.bench.label="$label" \
    -Dpumpkinpatch.bench.warmup="$WARMUP" -Dpumpkinpatch.bench.seconds="$SECONDS_MEASURED" \
    -Dpumpkinpatch.bench.pingEvery="$ping" \
    || failed="$failed $label"
}
. scripts/progress.sh
scenarios="${*:-baseline idle one several rtt calls several-interpreter}"
set -- $scenarios
total=$# i=0 failed=""
for s in $scenarios; do
  case "$s" in
    baseline) run baseline compiler baseline 0 ;;
    idle) run idle compiler host 0 ;;
    one) run one compiler host 0 hud-bench:1 ;;
    several) run several compiler host 0 hud-bench:8 ;;
    rtt) run rtt compiler host 10 ping ;;
    calls) run calls compiler host 0 host-calls ;;
    several-interpreter) run several-interpreter interpreter host 0 hud-bench:8 ;;
    # Redline: the same samples as native code. The first launch compiles and caches it.
    one-redline) run one-redline redline host 0 hud-bench:1 ;;
    several-redline) run several-redline redline host 0 hud-bench:8 ;;
    rtt-redline) run rtt-redline redline host 10 ping ;;
    calls-redline) run calls-redline redline host 0 host-calls ;;
    # The Java control: the same samples ported to plain Java, through the same host.
    one-java) run one-java java host 0 hud-bench:1 ;;
    several-java) run several-java java host 0 hud-bench:8 ;;
    rtt-java) run rtt-java java host 10 ping ;;
    calls-java) run calls-java java host 0 host-calls ;;
    *) echo "unknown scenario $s" >&2; exit 1 ;;
  esac
done
if [ -n "$failed" ]; then
  echo "exited with an error:$failed" >&2
  exit 1
fi
