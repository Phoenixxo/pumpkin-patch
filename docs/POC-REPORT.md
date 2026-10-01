# Pumpkin Patch PoC report

State as of 2026-09-29. This implements the proof of concept described in `Architecture.md`: a Fabric client
mod that loads Wasm components with Endive CM, and negotiates them with a real Pumpkin server over `pumpkin:mux`.

## Versions and machine

The numbers in this report and in `docs/perf-results.md` were measured on the first pins: Endive 90ad577e,
Endive CM 5cd51fb plus the fix, and Pumpkin master 4426d1113. That state is pumpkin-patch commit c3fb86e,
and both forks keep it under the tag `poc-2026-09-30`. The table below lists the current pins.

| | |
| --- | --- |
| Machine | Apple M4 Pro, 24 GiB, macOS 26.6.2 |
| Java | 25.0.2 |
| Minecraft / Fabric | 26.3 (protocol 777), Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18.2, Gradle 9.7.1 |
| Endive / Endive CM | `main` at b0835978 / 4a2c4ed plus one fix (see below), submodules in `third_party/`, installed in `~/.m2-pumpkin-patch` |
| Pumpkin | `refactor/split-pumpkin-core` (50a40ce64) plus the mux patch, branch `feat/pumpkin-patch-mux` of github.com/Phoenixxo/Pumpkin, submodule `third_party/pumpkin` |
| Guests | Rust 1.98.0, wit-bindgen 0.62, wasm-tools 1.258.0 |

## Layout

| Path | What |
| --- | --- |
| `wit/` | `pumpkin:client@0.1.0` WIT (one file, see deviations) |
| `core/` | Platform-free host: catalog, manifests, handshake, sessions, dispatch, watchdog, perf |
| `engine-endive/` | Endive CM runtime adapter and generated bindings; tests against real components |
| `endive-bundle/` | Endive and Endive CM merged into one jar for jar-in-jar |
| `fabric/` | The Fabric mod: transport, HUD, keys, `/pumpkinpatch`, benchmark autopilot |
| `guests/` | Rust client components: `ping`, `trap`, `spin`, `hud-bench`, `host-calls`, `radar` |
| `examples/` | Manifests, the `ping` and `radar` server plugins, the radar wire protocol crate |
| `run/server/` | Test Pumpkin server config (offline, peaceful, mux enabled, PatchTester opped) |
| `scripts/` | `build-endive.sh`, `build-guests.sh`, `install-mods.sh`, `bench.sh`, `perf-report.py` |

## Build

```sh
git submodule update --init                   # third_party/endive, endive-cm, pumpkin
scripts/build-endive.sh                       # Endive + patched Endive CM into ~/.m2-pumpkin-patch
scripts/build-guests.sh                       # guests/out/*.wasm
for p in ping radar; do
  (cd examples/$p/server && cargo build --release --target wasm32-wasip2)
  cp examples/$p/server/target/wasm32-wasip2/release/${p}_server.wasm run/server/plugins/
done
(cd third_party/pumpkin && RUST_MIN_STACK=536870912 cargo build --release)  # rustc overflows its stack on pumpkin-data without this
./gradlew build                               # all tests + fabric/build/libs/pumpkin-patch-0.1.0.jar
scripts/install-mods.sh fabric/run/pumpkin-mods radar ping trap spin
```

## Run

Terminal 1, the server:

```sh
cd run/server && ../../third_party/pumpkin/target/release/pumpkin
```

Terminal 2, the client: `./gradlew :fabric:runClient`, then join `localhost`.

What you see in game:

| Key | Mod | Result |
| --- | --- | --- |
| O | `example:ping` | Asks the server plugin for data; the reply appears on the HUD |
| K | `example:trap` | The component traps; a toast reports it, and ping keeps working |
| J | `example:spin` | Loops forever; the watchdog stops it after the call budget, and the game keeps running |
| B / N / M / V | `example:radar` | Drop a waypoint / change zoom / toggle the radar / clear your waypoints |

`/pumpkinpatch list|stats|hud|faults` shows instances, per-mod timings, the stats overlay, and recent faults.
Disconnecting and rejoining creates fresh instances.

The repeatable acceptance run: `./gradlew :fabric:runClient -Dpumpkinpatch.autopilot=demo`.

## Acceptance run (real client, real Pumpkin, compiler engine)

From `fabric/run/pumpkin-patch-results/demo.txt`:

- Handshake: `HELLO`, `REPLY`, `ACCEPT`, then session 1 is `PUMPKIN` with ping, spin, and trap `ACTIVE`.
- Ping: the HUD shows the server's reply. Round trip p50 is 48.6 ms (n=4). Pumpkin handles play packets once per
  player tick (`entity/player.rs`), so about 50 ms is the floor.
- Trap: `example:trap` became `FAULTED` (`TRAP_UNREACHABLE`), and ping still answered.
- Loop: `example:spin` was stopped with `TIMEOUT` after 26.8 ms. Watchdog stop latency was 98.6 µs, and the
  client thread's interrupt flag was clear afterwards.
- Reconnect: session 2 had new instance identities for all three mods (`@45b3a6b1/s1` became `@545ec6c9/s2`, and
  so on), and all were `ACTIVE`.

Timings from the same run, in µs:

| Series | n | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| `lifecycle.instantiate` | 6 | 523.5 | 14805.5 | 14805.5 |
| `guest.init` | 6 | 306.2 | 3738.8 | 3738.8 |
| `guest.render` | 480 | 64.5 | 105.1 | 135.6 |
| `guest.handle-events` | 211 | 84.3 | 212.0 | 707.1 |
| `host.net.send` | 4 | 6.6 | 108.9 | 108.9 |
| `drain.hud` (whole host per frame) | 2429 | 19.2 | 139.8 | 232.3 |
| `drain.tick` (whole host per tick) | 453 | 11.7 | 147.6 | 747.5 |

The demo run takes screenshots and presses keys, so its frame times are not a benchmark.

## Baseline (vanilla client, host not installed)

Warmup 30 s, 60 s measured, FPS limit 260, vsync off.

| Series | n | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| `client.frame` (µs) | 12638 | 4391.0 | 7132.5 | 8358.0 |
| `client.tick` (µs) | 1201 | 693.5 | 904.5 | 1130.3 |

Heap after GC: 304.9 MiB.

## Not yet measured

These need a client window, so they were left for a manual run:

- **Other `bench.sh` scenarios:** `idle`, `one`, `several`, `rtt`, `calls`, and `several-interpreter`. Run them
  with `scripts/bench.sh idle one several rtt calls several-interpreter` while the server runs.
- **Radar sweep:** the phases are radar off, 0/32/128/256 entities, plus 200 waypoints, plus a 20 Hz broadcast.
  Run it once per engine:
  `./gradlew :fabric:runClient -Dpumpkinpatch.autopilot=radar -Dpumpkinpatch.engine=compiler|interpreter`.

`scripts/perf-report.py > report.md` turns the JSON these runs write into tables. The tables cover:

- cold compile time
- frame and tick time p50/p95/p99
- guest call time, and the time spent converting values across the boundary
- host-call cost
- bytes allocated per call
- linear memory and heap
- round trip time

## Upstream fixes made

- **Endive CM: `ComponentLinker` outer-alias type matching.** The linker compared types from an instance's outer
  alias with raw `Type.equals`. That fails for any record whose fields refer to other types by index, so a
  component that `use`s such a record (the radar's `entity-snapshot`) could not link. The fix compares resolved
  slots with `TypeMatcher.slotsMatch`, and adds two regression tests.
  - Code: branch `fix/outer-alias-structural-type-match` of github.com/Phoenixxo/endive-cm (fix in 3e6e328),
    checked out as `third_party/endive-cm`.
  - The full Endive CM runtime suite passes: 1306 run, 0 failed, 8 skipped.
- **Pumpkin: configuration-phase disconnect.** `CConfigDisconnect` wrote its reason as a string, but 26.3
  clients decode a text component, so every config-phase kick failed to decode on the client. It now writes a
  `TextComponent`.
- **Pumpkin: the mux itself.** The changes are:
  - `pumpkin-config` `networking.pumpkin_mux`
  - `net/java/pumpkin_mux.rs`, with 5 unit tests
  - `HELLO` sent at login acknowledged, with Known Packs as the barrier
  - virtual `pumpkin:mux/<mod>/<channel>` channels delivered to plugins through `PlayerCustomPayloadEvent`

## Deviations from Architecture.md

- The WIT is one file with a nested `package pumpkin:base@0.1.0`. Endive CM's bindgen reads a single file and
  refuses `use` in an exported interface. So the guest interface has its own `world-ref`, and the `net-message`
  record is renamed `net-payload`, because a variant case can't share its payload type's name.
- Endive and Endive CM are nested as one merged jar. Both publish artifacts named `runtime` and `wasm-tools`, and
  jar-in-jar nests by file name, so including them separately silently drops one of each.
- The ping key is O. P opens vanilla Social Interactions.

## Incomplete

- The benchmark scenarios and radar sweep above have not been run.
- The Endive CM fix is not upstreamed. Until it is, `third_party/endive-cm` points at the fork.
- Only the synchronous WIT path exists. There are no blocks, items, or assets, per the brief.
- Nothing is committed in this repository, the Pumpkin worktree, or Endive CM.
