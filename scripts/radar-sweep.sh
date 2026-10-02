#!/usr/bin/env bash
# Runs the example:radar sweep once per engine, with only the radar installed so every engine
# carries the same load. Needs the Pumpkin server running on localhost:25565 with PatchTester
# opped. Results land in fabric/run/pumpkin-patch-results/radar-<engine>.json.
#   scripts/radar-sweep.sh compiler redline java interpreter
# Wasm updates run on worker threads. A "-client" suffix, such as redline-client, runs them on the
# client thread instead, and saves the result as radar-redline-client.json.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
mods="$root/fabric/run/bench-mods/radar"
scripts/install-mods.sh "$mods" radar > /dev/null
. scripts/progress.sh
[ $# -gt 0 ] || set -- compiler redline java
i=0 failed=""
for label in "$@"; do
  i=$((i + 1))
  engine="${label%-client}"
  workers=()
  [ "$engine" != "$label" ] && workers=(-Dpumpkinpatch.workers=false)
  watch_client "[$i/$#] radar $label" "run/client-radar-$label.log" radar -- \
    ./gradlew -q :fabric:runClient --no-configuration-cache \
    -Dpumpkinpatch.autopilot=radar -Dpumpkinpatch.engine="$engine" -Dpumpkinpatch.mods="$mods" \
    -Dpumpkinpatch.radar.label="$label" ${workers[@]+"${workers[@]}"} \
    || failed="$failed $label"
done
if [ -n "$failed" ]; then
  echo "exited with an error:$failed" >&2
  exit 1
fi
