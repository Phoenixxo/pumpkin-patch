#!/usr/bin/env bash
# Builds the sample client components into guests/out/*.wasm.
set -euo pipefail
cd "$(dirname "$0")/../guests"
cargo build --release --target wasm32-unknown-unknown
mkdir -p out
for g in ping trap spin hud_bench host_calls radar; do
  wasm-tools component new "target/wasm32-unknown-unknown/release/$g.wasm" -o "out/$g.wasm"
done
ls -l out
