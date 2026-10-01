#!/usr/bin/env bash
# Runs the example:radar sweep once per engine, with only the radar installed so every engine
# carries the same load. Needs the Pumpkin server running on localhost:25565 with PatchTester
# opped. Results land in fabric/run/pumpkin-patch-results/radar-<engine>.json.
#   scripts/radar-sweep.sh compiler redline java interpreter
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
mods="$root/fabric/run/bench-mods/radar"
scripts/install-mods.sh "$mods" radar > /dev/null
[ $# -gt 0 ] || set -- compiler redline java
for engine in "$@"; do
  echo "== radar sweep ($engine)"
  ./gradlew -q :fabric:runClient --no-configuration-cache \
    -Dpumpkinpatch.autopilot=radar -Dpumpkinpatch.engine="$engine" -Dpumpkinpatch.mods="$mods" \
    > "run/client-radar-$engine.log" 2>&1
done
