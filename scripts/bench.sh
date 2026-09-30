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
  echo "== $label ($engine, $mode, mods: ${*:-none})"
  ./gradlew -q :fabric:runClient --no-configuration-cache \
    -Dpumpkinpatch.autopilot=bench -Dpumpkinpatch.mode="$mode" -Dpumpkinpatch.engine="$engine" \
    -Dpumpkinpatch.mods="$mods" -Dpumpkinpatch.bench.label="$label" \
    -Dpumpkinpatch.bench.warmup="$WARMUP" -Dpumpkinpatch.bench.seconds="$SECONDS_MEASURED" \
    -Dpumpkinpatch.bench.pingEvery="$ping" > "run/client-bench-$label.log" 2>&1
}
scenarios="${*:-baseline idle one several rtt calls several-interpreter}"
for s in $scenarios; do
  case "$s" in
    baseline) run baseline compiler baseline 0 ;;
    idle) run idle compiler host 0 ;;
    one) run one compiler host 0 hud-bench:1 ;;
    several) run several compiler host 0 hud-bench:8 ;;
    rtt) run rtt compiler host 10 ping ;;
    calls) run calls compiler host 0 host-calls ;;
    several-interpreter) run several-interpreter interpreter host 0 hud-bench:8 ;;
    # The Java control: the same samples ported to plain Java, through the same host.
    one-java) run one-java java host 0 hud-bench:1 ;;
    several-java) run several-java java host 0 hud-bench:8 ;;
    rtt-java) run rtt-java java host 10 ping ;;
    calls-java) run calls-java java host 0 host-calls ;;
    *) echo "unknown scenario $s" >&2; exit 1 ;;
  esac
done
