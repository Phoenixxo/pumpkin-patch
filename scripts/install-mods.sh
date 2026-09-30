#!/usr/bin/env bash
# Installs sample client components into a pumpkin-mods directory.
#   scripts/install-mods.sh <pumpkin-mods dir> ping trap spin hud-bench:4
# The directory is emptied first. hud-bench:N installs N copies under distinct ids.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$1"; shift
rm -rf "$dest"; mkdir -p "$dest"
install_one() { # name wasm dir-suffix n
  local name="$1" wasm="$2" dir="$dest/$3" n="$4"
  mkdir -p "$dir"
  cp "$root/guests/out/$wasm.wasm" "$dir/client.wasm"
  local sha; sha=$(shasum -a 256 "$dir/client.wasm" | cut -d' ' -f1)
  sed -e "s/@SHA256@/$sha/" -e "s/@N@/$n/g" "$root/examples/$name/pumpkin-mod.toml" > "$dir/pumpkin-mod.toml"
}
for spec in "$@"; do
  case "$spec" in
    ping) install_one ping ping example-ping 0 ;;
    trap) install_one trap trap example-trap 0 ;;
    spin) install_one spin spin example-spin 0 ;;
    host-calls) install_one host-calls host_calls bench-host-calls 0 ;;
    radar) install_one radar radar example-radar 0 ;;
    hud-bench:*) for i in $(seq 1 "${spec#*:}"); do install_one hud-bench hud_bench "bench-hud-$i" "$i"; done ;;
    *) echo "unknown mod $spec" >&2; exit 1 ;;
  esac
done
ls "$dest"
