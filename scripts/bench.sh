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
    -Dpumpkinpatch.bench.pingEvery="$ping" ${workers[@]+"${workers[@]}"} \
    || failed="$failed $label"
}
. scripts/progress.sh
scenarios="${*:-baseline idle one several rtt calls several-interpreter}"
set -- $scenarios
total=$# i=0 failed=""
for s in $scenarios; do
  # A "-client" suffix runs the scenario with updates on the client thread instead of workers.
  base="${s%-client}"
  workers=()
  [ "$base" != "$s" ] && workers=(-Dpumpkinpatch.workers=false)
  case "$base" in
    baseline) run "$s" compiler baseline 0 ;;
    idle) run "$s" compiler host 0 ;;
    one) run "$s" compiler host 0 hud-bench:1 ;;
    several) run "$s" compiler host 0 hud-bench:8 ;;
    rtt) run "$s" compiler host 10 ping ;;
    calls) run "$s" compiler host 0 host-calls ;;
    several-interpreter) run "$s" interpreter host 0 hud-bench:8 ;;
    # Redline: the same samples as native code. The first launch compiles and caches it.
    one-redline) run "$s" redline host 0 hud-bench:1 ;;
    several-redline) run "$s" redline host 0 hud-bench:8 ;;
    rtt-redline) run "$s" redline host 10 ping ;;
    calls-redline) run "$s" redline host 0 host-calls ;;
    # The Java control: the same samples ported to plain Java, through the same host.
    one-java) run "$s" java host 0 hud-bench:1 ;;
    several-java) run "$s" java host 0 hud-bench:8 ;;
    rtt-java) run "$s" java host 10 ping ;;
    calls-java) run "$s" java host 0 host-calls ;;
    *) echo "unknown scenario $s" >&2; exit 1 ;;
  esac
done
if [ -n "$failed" ]; then
  echo "exited with an error:$failed" >&2
  exit 1
fi
