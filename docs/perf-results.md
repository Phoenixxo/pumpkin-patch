# Performance results

Every number here comes from the real Fabric client against the real Pumpkin server, with the in-game autopilot driving the radar and HUD benchmarks. "Java" is the control: the same mods rewritten in plain Java, run on the game thread the way a normal Java mod would be. Runs from 2026-10-01 were all made with the same build, back to back, with no faults.

## Latest (2026-10-01)

Short version: Wasm mods now cost the game thread less than the same mods written in Java. With the radar tracking 256 entities, the game thread spends about 103-107 µs a tick on it, against 196 µs for the Java version. With 8 small HUD mods, it's 33-35 µs against 106 µs. The radar's own code still uses more CPU than Java in total, but that work happens on another core now.

### What the results say

**The game runs smoother with Wasm mods than with the same mods in Java.** The game thread is what decides your frame rate, because if it's busy, the frame waits. A Wasm mod's own work now runs on another core, and the game thread only takes a snapshot and applies the results. Java mods can't safely do that, because they read and change live game objects directly.

**The Wasm code itself still uses more CPU than Java, just a lot less extra than before.** The radar's own work on Redline is 1.3x Java at 256 entities, down from 4.2x on 2026-09-29. Tiny mods still show big ratios, because they barely do anything and the cost is mostly getting in and out. Either way, that time is spent on another core now, so it doesn't hold up frames.

**Worker threads are what made the difference.** The same Redline build with mods on the game thread is worse than Java: 1.4x for the radar and 4.9x for 8 small mods. With workers, it's about half and about a third of Java.

**Drawing the HUD was the biggest hidden cost, for Java and Wasm alike.** The radar took 180-530 µs a frame to draw, every frame. It's 10-19 µs now, and that helps any mod that draws, whatever it's written in.

**Frame times don't tell the engines apart yet.** Frame p95 sits between 7.4 and 9.9 ms in every setup, Java included, and that's just run-to-run noise. At this size no setup moves the frame rate. The game-thread numbers show where the headroom is, though: with a lot of mods or a slower machine, Wasm on workers degrades the least.

**And Wasm gives you isolation Java can't.** A mod that crashes or loops forever gets stopped without taking the game down. That held in every run, on every engine, and a garbage-collection pause can't trip it by mistake anymore.

### Game-thread time

This is what actually decides whether a mod can hurt your frame rate: the time the game thread spends on mods each tick. Drawing the HUD is counted separately below, because it's the same work whether Java or Wasm produced the commands.

| Scenario | Java | Wasm, compiler (workers) | Wasm, Redline (workers) | Wasm, Redline (game thread) |
| --- | --- | --- | --- | --- |
| Radar off | 47 µs | 37 µs | 34 µs | 168 µs |
| Radar, 256 entities | 196 µs | 103 µs (0.53x) | 107 µs (0.55x) | 282 µs (1.4x) |
| Radar, 256 entities + 200 waypoints + 20 Hz broadcast | 187 µs | 79 µs (0.42x) | 89 µs (0.47x) | 411 µs (2.2x) |
| 8 HUD mods | 106 µs | 33 µs (0.31x) | 35 µs (0.33x) | 519 µs (4.9x) |
| 1 HUD mod | 99 µs | 37 µs (0.37x) | 35 µs (0.35x) | - |
| 1,000 host calls a tick | 215 µs | 30 µs | 34 µs | - |

The last column is the same Redline build with workers switched off, so it shows what moving mods off the game thread bought: about 2.6x for the radar and about 15x for 8 small mods.

What's still on the game thread with workers is mostly Minecraft's own entity lookup for the view snapshot, about 25-55 µs a tick while the radar is on. Java mods pay for that lookup too, once per mod that asks. Here it's done once per tick and shared by every mod.

### Total CPU for the mod's own work

Moving work to another core doesn't make it free, so here's the mod's own update time, wherever it ran:

| Radar phase | Java | Compiler | Redline |
| --- | --- | --- | --- |
| 256 entities | 164 µs | 263 µs (1.6x) | 217 µs (1.3x) |
| + waypoints | 161 µs | 367 µs (2.3x) | 273 µs (1.7x) |
| + 20 Hz broadcast | 162 µs | 458 µs (2.8x) | 368 µs (2.3x) |

On 2026-09-29 the same 256-entity phase was 4.2x Java. For the small HUD mods, the ratio is still big (13-19x for 8 mods), because each one does almost nothing and the cost is mostly getting in and out. That's off the game thread now, though.

### Drawing

Drawing the radar used to cost 180-530 µs a frame, for Java and Wasm alike. Rectangles are now drawn in batches, so it's 10-19 µs a frame with 256 dots and 200 waypoint labels on screen. Every mod benefits from this, Java or Wasm.

### What changed since the first run

- Entity kinds are numbers instead of strings, and Endive CM writes records straight into the mod's memory instead of going through maps. That took the radar's per-tick cost from 134 µs to about 42 µs in the headless test, and allocation from 373 KB to 62 KB a tick.
- Redline, Endive's Cranelift-based compiler, now compiles mods to native code when they load and caches the result. A Redline mod's code runs at 22-24 µs in the headless radar test, against about 39 µs on the bytecode compiler. Getting it working meant fixing three Redline bugs: a new thread per call, memory access rebuilding its layout every time, and compiler threads that stopped the game from exiting.
- `handle-events` and `render` were merged into one `update` call, made at most once a tick. In the game each call starts with cold CPU caches, so dropping one of the two calls a tick saves a whole cold call. Its effect wasn't measured on its own, though.
- Mods run on worker threads, against a snapshot of the player and nearby entities taken once a tick on the game thread. The snapshot only collects entities while some mod is reading them, and follows the radius mods actually ask for.
- The call watchdog no longer counts garbage-collection pauses against a mod. Before that, a GC pause on another thread could stop a perfectly fine mod.

### What's left

- Most of what's still on the game thread is Minecraft's own entity lookup for the snapshot, about 25-55 µs a tick while the radar is on. Java mods pay for it too, but there it's once per mod, and here it's shared.
- The broadcast phase allocates a lot more on the Wasm side (about 330 KB a call vs. 91 KB for Java). That hasn't been looked into yet.
- Small mods still pay a lot to get into Wasm and back out. It's harmless on workers, but it's the next thing to cut to get total CPU closer to Java.

### Caveats

- A mod on a worker runs a bit slower than it would on the game thread, because the worker sits idle between ticks and starts cold. It doesn't matter for frames, but it's why the total-CPU ratios are higher for the small mods.
- Frame p95 bounces around between 7.4 and 9.9 ms in every column, Java included. That's normal run-to-run noise.
- The interpreter wasn't rerun. Its last numbers are in the 2026-09-29 tables below.

## Full tables (2026-10-01)

Environment: `{"os": "Mac OS X 26.6.2", "arch": "aarch64", "cpus": 12, "java": "25.0.2", "jvm": "Java HotSpot(TM) 64-Bit Server VM", "max_heap_mib": 3072, "minecraft": "26.3", "fabric_loader": "0.19.5", "fabric_api": "0.161.0+26.3", "engine": "none (host not installed)", "workers": false, "warmup_s": 30, "measure_s": 60, "fps_limit": 260, "vsync": false, "window": "2932x1818", "gui_scale": 3, "render_distance": 12}`

### Client frame and tick time by scenario

| scenario | engine | frames | frame p50 / p95 / p99 (us) | client tick p50 / p95 / p99 (us) | host tick drain p50 / p99 | heap after GC (MiB) |
| --- | --- | --- | --- | --- | --- | --- |
| baseline | none (host not installed) | 12708 | 4181.7 / 9020.2 / 12103.7 | 713.0 / 1911.3 / 2318.6 | - / - | - |
| calls-java | Java (native JVM ports, same host API) | 12212 | 4600.6 / 8951.2 / 12472.5 | 931.8 / 2359.8 / 2727.1 | 200.1 / 483.0 | - |
| calls-redline | Endive CM (redline aarch64-apple-darwin, validated) | 11678 | 4817.2 / 9479.4 / 12308.5 | 759.0 / 2042.0 / 2637.5 | 30.0 / 98.9 | - |
| calls | Endive CM (compiler, validated) | 14728 | 3483.8 / 7813.7 / 11083.8 | 670.6 / 1850.7 / 2597.0 | 25.0 / 97.0 | - |
| idle | Endive CM (compiler, validated) | 11781 | 4785.2 / 9778.1 / 12405.5 | 778.4 / 1965.2 / 2328.4 | 3.4 / 15.5 | - |
| one-java | Java (native JVM ports, same host API) | 11921 | 4687.0 / 8994.8 / 11985.1 | 825.8 / 1898.8 / 2390.1 | 90.2 / 241.8 | - |
| one-redline | Endive CM (redline aarch64-apple-darwin, validated) | 12048 | 4608.0 / 9177.1 / 12390.1 | 756.0 / 2026.2 / 2422.3 | 28.6 / 102.3 | - |
| one | Endive CM (compiler, validated) | 12091 | 4616.6 / 9150.6 / 12188.5 | 742.7 / 1954.1 / 2453.3 | 29.3 / 101.8 | - |
| rtt-java | Java (native JVM ports, same host API) | 11490 | 4817.6 / 9499.5 / 12337.7 | 764.0 / 2017.2 / 2547.6 | 6.1 / 275.1 | - |
| rtt-redline | Endive CM (redline aarch64-apple-darwin, validated) | 12301 | 4470.2 / 8858.6 / 11620.5 | 734.7 / 1763.8 / 2260.9 | 7.2 / 175.5 | - |
| rtt | Endive CM (compiler, validated) | 12160 | 4568.5 / 9486.7 / 11927.9 | 738.0 / 1935.2 / 2343.5 | 6.8 / 183.8 | - |
| several-java | Java (native JVM ports, same host API) | 12579 | 4338.1 / 8860.8 / 12133.5 | 784.4 / 2073.5 / 2780.0 | 94.1 / 273.3 | - |
| several-redline-client | Endive CM (redline aarch64-apple-darwin, validated) | 13128 | 4006.1 / 8720.6 / 12744.0 | 1059.9 / 3194.9 / 3981.5 | 433.8 / 1537.5 | - |
| several-redline | Endive CM (redline aarch64-apple-darwin, validated) | 16085 | 3276.8 / 7441.8 / 11239.2 | 699.5 / 2150.9 / 2758.3 | 28.2 / 105.8 | - |
| several | Endive CM (compiler, validated) | 12340 | 4552.3 / 8897.3 / 11704.9 | 753.8 / 1785.8 / 2437.6 | 28.8 / 94.5 | - |

### example:radar sweep (Endive CM (compiler, validated), updates on workers)

Compile (cold, per launch): example:radar 774 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | update p50 / p99 / max | render p50 / p99 / max (before update) | convert p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 3624.5 / 8288.1 / 11914.3 | 116.0 / 1220.2 / 2620.0 | - | 9.2 | 165.9 | 2360.0 | - | - | 1152 | 315.3 | 2193 / 0 |
| entities-0 | 24.0 | 29.0 | 4200.9 / 9159.1 / 12337.7 | 289.7 / 1202.8 / 2000.7 | - | 7.8 | 354.4 | 13528.0 | 6.0 | - | 1152 | 315.4 | 3819 / 0 |
| entities-32 | 56.0 | 61.0 | 3936.0 / 8332.4 / 11373.7 | 227.1 / 1079.3 / 3171.0 | - | 7.4 | 279.1 | 19816.0 | 3.8 | - | 1152 | 315.4 | 5407 / 0 |
| entities-128 | 152.0 | 157.0 | 4425.8 / 8745.8 / 11893.1 | 214.2 / 1295.2 / 3048.7 | - | 6.5 | 270.2 | 39688.0 | 4.5 | - | 1152 | 316.5 | 7036 / 0 |
| entities-256 | 256.0 | 261.0 | 4870.1 / 8967.2 / 11689.4 | 218.0 / 708.6 / 1994.0 | - | 5.5 | 262.5 | 62496.0 | 4.3 | - | 1152 | 318.0 | 8666 / 0 |
| entities-256-waypoints-200 | 256.0 | 471.0 | 3646.5 / 7498.3 / 10338.6 | 256.5 / 1696.1 / 14220.9 | - | 4.8 | 366.8 | 64024.0 | 3.8 | - | 1216 | 317.9 | 18361 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 470.0 | 5063.8 / 8959.5 / 11490.2 | 397.4 / 1205.0 / 2355.7 | - | 5.7 | 457.6 | 337992.0 | 4.7 | - | 1216 | 318.1 | 49983 / 0 |

### example:radar sweep (Java (native JVM ports, same host API), updates on client thread)

Compile (cold, per launch): example:radar 2 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | update p50 / p99 / max | render p50 / p99 / max (before update) | convert p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 3948.7 / 8464.9 / 11788.5 | 14.8 / 45.9 / 73.9 | - | - | 17.5 | 160.0 | - | - | - | 291.9 | 2193 / 0 |
| entities-0 | 24.0 | 29.0 | 4282.8 / 8892.4 / 11910.6 | 92.8 / 280.0 / 990.5 | - | - | 104.5 | 10864.0 | 18.0 | - | - | 291.9 | 3780 / 0 |
| entities-32 | 56.0 | 61.0 | 4787.8 / 9844.7 / 12308.4 | 101.2 / 252.8 / 572.2 | - | - | 108.0 | 18416.0 | 23.1 | - | - | 292.2 | 5407 / 0 |
| entities-128 | 152.0 | 157.0 | 5217.0 / 9875.1 / 13219.2 | 105.7 / 376.8 / 1208.0 | - | - | 135.5 | 43552.0 | 34.8 | - | - | 293.2 | 7036 / 0 |
| entities-256 | 256.0 | 261.0 | 4904.5 / 9044.8 / 12267.2 | 117.1 / 509.8 / 1335.8 | - | - | 164.0 | 73088.0 | 49.1 | - | - | 294.6 | 8627 / 0 |
| entities-256-waypoints-200 | 256.0 | 471.0 | 5135.0 / 9275.0 / 12336.3 | 127.4 / 493.4 / 1635.2 | - | - | 160.7 | 92960.0 | 46.6 | - | - | 294.5 | 18361 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 471.0 | 5146.9 / 9374.1 / 12432.8 | 129.2 / 551.4 / 1045.9 | - | - | 162.4 | 93016.0 | 46.2 | - | - | 294.7 | 50022 / 0 |

### example:radar sweep (Endive CM (redline aarch64-apple-darwin, validated), updates on client thread)

Compile (cold, per launch): example:radar 692 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | update p50 / p99 / max | render p50 / p99 / max (before update) | convert p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 3819.6 / 8719.3 / 12090.0 | 122.7 / 346.1 / 679.2 | - | 8.0 | 136.3 | 2544.0 | - | - | 1152 | 345.0 | 2193 / 0 |
| entities-0 | 24.0 | 29.0 | 3824.5 / 9090.4 / 12656.3 | 236.6 / 892.6 / 1033.8 | - | 6.8 | 295.4 | 18512.0 | 15.9 | - | 1152 | 345.3 | 3819 / 0 |
| entities-32 | 56.0 | 61.0 | 4022.9 / 8486.0 / 12239.8 | 206.2 / 672.2 / 1709.4 | - | 6.4 | 239.9 | 29696.0 | 19.9 | - | 1152 | 345.7 | 5407 / 0 |
| entities-128 | 152.0 | 157.0 | 4980.1 / 9460.8 / 12708.8 | 210.6 / 680.8 / 1186.8 | - | 6.2 | 244.5 | 62832.0 | 30.5 | - | 1152 | 346.6 | 7036 / 0 |
| entities-256 | 256.0 | 261.0 | 4328.4 / 8742.0 / 12361.2 | 202.9 / 891.2 / 1603.5 | - | 5.1 | 253.1 | 102480.0 | 40.5 | - | 1152 | 347.9 | 8666 / 0 |
| entities-256-waypoints-200 | 256.0 | 471.0 | 4209.6 / 8643.8 / 12212.1 | 248.1 / 765.4 / 1080.7 | - | 5.3 | 286.7 | 105008.0 | 45.6 | - | 1216 | 347.3 | 18361 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 471.0 | 4991.5 / 9053.6 / 12113.9 | 328.2 / 987.0 / 1233.2 | - | 4.7 | 384.9 | 378776.0 | 42.4 | - | 1216 | 346.8 | 49983 / 0 |

### example:radar sweep (Endive CM (redline aarch64-apple-darwin, validated), updates on workers)

Compile (cold, per launch): example:radar 690 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | update p50 / p99 / max | render p50 / p99 / max (before update) | convert p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 4061.0 / 8157.4 / 11370.4 | 135.6 / 719.0 / 4282.9 | - | 8.2 | 172.1 | 2544.0 | - | - | 1152 | 344.8 | 2193 / 0 |
| entities-0 | 24.0 | 29.0 | 4454.7 / 8959.1 / 11902.8 | 250.0 / 1536.6 / 4424.8 | - | 7.3 | 322.1 | 14320.0 | 5.5 | - | 1152 | 344.4 | 3819 / 0 |
| entities-32 | 56.0 | 61.0 | 4235.1 / 8876.0 / 12279.9 | 200.9 / 1211.4 / 4921.6 | - | 6.5 | 250.0 | 20864.0 | 3.7 | - | 1152 | 344.9 | 5446 / 0 |
| entities-128 | 152.0 | 157.0 | 5065.1 / 9718.4 / 12711.6 | 173.2 / 560.1 / 798.2 | - | 6.2 | 203.9 | 40480.0 | 4.7 | - | 1152 | 345.8 | 7036 / 0 |
| entities-256 | 256.0 | 261.0 | 5137.1 / 9484.0 / 12490.2 | 180.0 / 810.6 / 3500.5 | - | 5.1 | 217.3 | 63144.0 | 5.4 | - | 1152 | 347.2 | 8666 / 0 |
| entities-256-waypoints-200 | 256.0 | 471.0 | 5353.2 / 9604.1 / 12616.8 | 227.5 / 1044.0 / 3124.2 | - | 5.2 | 272.5 | 65472.0 | 5.3 | - | 1216 | 347.4 | 18400 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 471.0 | 5188.9 / 9456.8 / 12562.2 | 308.5 / 1713.0 / 3723.9 | - | 6.1 | 368.4 | 339360.0 | 4.9 | - | 1216 | 347.4 | 50061 / 0 |

### Wasm against the Java control

Same samples, same host and timers; only the guest code differs. Guest time is every guest call summed per client tick, on whichever thread ran it. Tick work is what the client thread spent in the host's tick: the guest updates when they run there, or with workers only the view snapshot and applying results. Drawing is the HUD output per frame, the same work for both. The Java control runs on the client thread, as a Java mod would.

| scenario | Wasm engine | Wasm updates on | Wasm guest us/tick | Java guest us/tick | guest Wasm / Java | tick work us/tick Wasm / Java | tick work Wasm / Java | draw us/frame Wasm / Java | alloc KiB/tick Wasm / Java | client tick p50 Wasm / Java | frame p95 Wasm / Java |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| one | Endive CM (compiler, validated) | workers | 279.1 | 66.0 | 4.2x | 36.5 / 98.9 | 0.4x | 13.1 / 12.4 | 6.5 / 2.1 | 742.7 / 825.8 | 9150.6 / 8994.8 |
| several | Endive CM (compiler, validated) | workers | 1179.4 | 61.3 | 19.2x | 32.7 / 105.7 | 0.3x | 22.9 / 22.2 | 48.4 / 14.5 | 753.8 / 784.4 | 8897.3 / 8860.8 |
| calls | Endive CM (compiler, validated) | workers | 338.7 | 188.4 | 1.8x | 29.8 / 215.2 | 0.1x | 1.3 / 0.6 | 623.3 / 168.1 | 670.6 / 931.8 | 7813.7 / 8951.2 |
| rtt | Endive CM (compiler, validated) | workers | 87.3 | 12.3 | 7.1x | 23.8 / 37.6 | 0.6x | 18.8 / 18.5 | 1.4 / 0.2 | 738.0 / 764.0 | 9486.7 / 9499.5 |
| one-redline | Endive CM (redline aarch64-apple-darwin, validated) | workers | 278.7 | 66.0 | 4.2x | 34.9 / 98.9 | 0.4x | 12.6 / 12.4 | 7.2 / 2.1 | 756.0 / 825.8 | 9177.1 / 8994.8 |
| several-redline | Endive CM (redline aarch64-apple-darwin, validated) | workers | 778.9 | 61.3 | 12.7x | 34.6 / 105.7 | 0.3x | 19.9 / 22.2 | 51.7 / 14.5 | 699.5 / 784.4 | 7441.8 / 8860.8 |
| several-redline-client | Endive CM (redline aarch64-apple-darwin, validated) | client thread | 477.7 | 61.3 | 7.8x | 518.9 / 105.7 | 4.9x | 20.2 / 22.2 | 54.4 / 14.5 | 1059.9 / 784.4 | 8720.6 / 8860.8 |
| calls-redline | Endive CM (redline aarch64-apple-darwin, validated) | workers | 532.2 | 188.4 | 2.8x | 34.3 / 215.2 | 0.2x | 1.9 / 0.6 | 646.9 / 168.1 | 759.0 / 931.8 | 9479.4 / 8951.2 |
| rtt-redline | Endive CM (redline aarch64-apple-darwin, validated) | workers | 99.0 | 12.3 | 8.1x | 23.2 / 37.6 | 0.6x | 19.6 / 18.5 | 1.7 / 0.2 | 734.7 / 764.0 | 8858.6 / 9499.5 |

### example:radar against the Java control

A "-client" engine ran its updates on the client thread, as Java does. The others ran them on workers, so their tick work leaves the radar's own update out. Drawing per frame is the same work whatever produced the commands.

| phase | Java us/tick | compiler us/tick | compiler / Java | redline us/tick | redline / Java | redline-client us/tick | redline-client / Java | tick work us/tick: Java / compiler / redline / redline-client | draw us/frame: Java / compiler / redline / redline-client | alloc per call p50 KiB: Java / compiler / redline / redline-client | frame p95: Java / compiler / redline / redline-client |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | 17.5 | 165.9 | 9.5x | 172.1 | 9.8x | 136.3 | 7.8x | 46.7 / 36.9 / 33.9 / 168.2 | 0.7 / 1.9 / 1.8 / 0.7 | 0.2 / 2.3 / 2.5 / 2.5 | 8464.9 / 8288.1 / 8157.4 / 8719.3 |
| entities-0 | 104.5 | 354.4 | 3.4x | 322.1 | 3.1x | 295.4 | 2.8x | 133.3 / 65.4 / 64.1 / 326.0 | 13.3 / 17.6 / 17.1 / 13.3 | 10.6 / 13.2 / 14.0 / 18.1 | 8892.4 / 9159.1 / 8959.1 / 9090.4 |
| entities-32 | 108.0 | 279.1 | 2.6x | 250.0 | 2.3x | 239.9 | 2.2x | 138.7 / 67.9 / 60.8 / 268.6 | 13.2 / 15.5 / 15.2 / 15.3 | 18.0 / 19.4 / 20.4 / 29.0 | 9844.7 / 8332.4 / 8876.0 / 8486.0 |
| entities-128 | 135.5 | 270.2 | 2.0x | 203.9 | 1.5x | 244.5 | 1.8x | 165.7 / 79.8 / 84.4 / 273.1 | 12.0 / 14.1 / 13.5 / 10.9 | 42.5 / 38.8 / 39.5 / 61.4 | 9875.1 / 8745.8 / 9718.4 / 9460.8 |
| entities-256 | 164.0 | 262.5 | 1.6x | 217.3 | 1.3x | 253.1 | 1.5x | 196.0 / 103.0 / 106.9 / 282.2 | 12.0 / 14.6 / 12.1 / 9.7 | 71.4 / 61.0 / 61.7 / 100.1 | 9044.8 / 8967.2 / 9484.0 / 8742.0 |
| entities-256-waypoints-200 | 160.7 | 366.8 | 2.3x | 272.5 | 1.7x | 286.7 | 1.8x | 190.9 / 77.1 / 99.1 / 314.7 | 15.8 / 15.0 / 18.1 / 16.5 | 90.8 / 62.5 / 63.9 / 102.5 | 9275.0 / 7498.3 / 9604.1 / 8643.8 |
| entities-256-waypoints-200-broadcast-20hz | 162.4 | 457.6 | 2.8x | 368.4 | 2.3x | 384.9 | 2.4x | 187.2 / 78.7 / 88.7 / 410.5 | 16.8 / 19.2 / 18.1 / 17.3 | 90.8 / 330.1 / 331.4 / 369.9 | 9374.1 / 8959.5 / 9456.8 / 9053.6 |

## Earlier results (2026-09-29)

These are from the first proof of concept, before any of the changes above. Two of the conclusions below turned out to be wrong. The extra Wasm cost wasn't a fixed 20-40 µs per call; it was mostly copying data into the mod and cold CPU caches in the game. And Redline, Endive's Cranelift-based compiler, did end up helping. The rest still describes that build accurately.

Environment: `{"os": "Mac OS X 26.6.2", "arch": "aarch64", "cpus": 12, "java": "25.0.2", "jvm": "Java HotSpot(TM) 64-Bit Server VM", "max_heap_mib": 3072, "minecraft": "26.3", "fabric_loader": "0.19.5", "fabric_api": "0.161.0+26.3", "engine": "none (host not installed)", "warmup_s": 30, "measure_s": 60, "fps_limit": 260, "vsync": false, "window": "1488x1818", "gui_scale": 3, "render_distance": 12}`

### Client frame and tick time by scenario

| scenario | engine | frames | frame p50 / p95 / p99 (us) | client tick p50 / p95 / p99 (us) | host tick drain p50 / p99 | heap after GC (MiB) |
| --- | --- | --- | --- | --- | --- | --- |
| baseline | none (host not installed) | 18969 | 2809.6 / 5396.6 / 6983.6 | 828.7 / 1075.6 / 1217.5 | - / - | - |
| calls-java | Java (native JVM ports, same host API) | 19012 | 3105.5 / 5671.6 / 7148.2 | 677.5 / 808.1 / 919.5 | 117.8 / 147.2 | - |
| calls | Endive CM (compiler, validated) | 16725 | 3344.0 / 6054.0 / 7563.9 | 803.4 / 1022.5 / 1376.4 | 178.4 / 377.9 | - |
| idle | Endive CM (compiler, validated) | 19951 | 2640.4 / 5341.8 / 6625.2 | 783.2 / 963.7 / 1106.0 | 2.9 / 5.8 | - |
| one-java | Java (native JVM ports, same host API) | 16484 | 3172.8 / 7258.2 / 10696.4 | 682.3 / 1575.5 / 2278.9 | 51.1 / 160.3 | - |
| one | Endive CM (compiler, validated) | 17139 | 3132.0 / 5589.6 / 6894.7 | 860.9 / 1072.9 / 1197.3 | 137.2 / 255.0 | - |
| rtt-java | Java (native JVM ports, same host API) | 19047 | 3163.6 / 5397.9 / 6811.5 | 583.0 / 743.6 / 950.9 | 4.9 / 113.7 | - |
| rtt | Endive CM (compiler, validated) | 17039 | 3119.8 / 5923.3 / 7467.7 | 646.0 / 906.8 / 1071.2 | 5.2 / 297.2 | - |
| several-interpreter | Endive CM (interpreter, validated) | 15665 | 3460.3 / 6669.0 / 8927.6 | 2180.9 / 3053.1 / 5085.3 | 1491.1 / 3756.0 | - |
| several-java | Java (native JVM ports, same host API) | 18989 | 3067.5 / 5549.1 / 6918.8 | 579.9 / 690.2 / 839.6 | 38.2 / 61.5 | - |
| several | Endive CM (compiler, validated) | 16661 | 3192.4 / 6279.6 / 7688.9 | 1080.0 / 1357.7 / 1489.1 | 450.5 / 680.2 | - |

### example:radar sweep (Endive CM (compiler, validated))

Compile (cold, per launch): example:radar 694 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | handle-events p50 / p99 / max | render p50 / p99 / max | convert (events / render) p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 3093.2 / 5491.0 / 6816.3 | 50.8 / 100.9 / 154.0 | 39.6 / 62.7 / 194.0 | 3.3 / 5.3 | 93.6 | 1224.0 | - | - | 1152 | 313.6 | 2285 / 0 |
| entities-0 | 17.0 | 26.0 | 3067.4 / 5347.1 / 6784.9 | 124.4 / 214.8 / 256.2 | 113.0 / 204.1 / 330.2 | 3.3 / 2.2 | 245.5 | 34936.0 | 11.2 | - | 1152 | 313.3 | 3872 / 0 |
| entities-32 | 51.0 | 59.0 | 3283.9 / 5545.0 / 6907.9 | 131.7 / 228.9 / 300.7 | 104.9 / 188.2 / 1519.3 | 3.1 / 2.4 | 249.1 | 88624.0 | 11.5 | - | 1152 | 313.7 | 5499 / 0 |
| entities-128 | 148.0 | 156.0 | 3477.0 / 5803.6 / 7185.3 | 172.2 / 249.1 / 1001.2 | 85.0 / 162.7 / 957.7 | 3.0 / 1.5 | 269.5 | 239160.0 | 18.5 | - | 1152 | 314.6 | 7128 / 0 |
| entities-256 | 256.0 | 264.0 | 3583.5 / 5824.4 / 7201.4 | 233.9 / 314.1 / 337.5 | 79.3 / 148.5 / 184.8 | 3.1 / 1.5 | 320.8 | 411392.0 | 25.8 | - | 1152 | 316.0 | 8719 / 0 |
| entities-256-waypoints-200 | 256.0 | 472.0 | 3558.2 / 5820.3 / 7365.0 | 229.9 / 332.2 / 489.1 | 145.3 / 237.7 / 281.2 | 3.0 / 1.5 | 384.6 | 411392.0 | 25.6 | - | 1216 | 316.0 | 18499 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 472.0 | 3835.3 / 6625.9 / 7790.5 | 237.5 / 324.8 / 1264.5 | 200.2 / 273.9 / 319.4 | 1.3 / 2.7 | 445.2 | 412392.0 | 28.0 | - | 1216 | 316.0 | 50160 / 0 |

### example:radar sweep (Endive CM (interpreter, validated))

Compile (cold, per launch): example:ping 548 ms (44 KiB), example:radar 82 ms (94 KiB), example:spin 20 ms (21 KiB), example:trap 21 ms (26 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | handle-events p50 / p99 / max | render p50 / p99 / max | convert (events / render) p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 2833.0 / 7076.4 / 9824.9 | 112.0 / 420.4 / 658.9 | 18.7 / 55.8 / 114.2 | 3.5 / 0.2 | 152.2 | 35504.0 | - | - | 1152 | 328.3 | 2285 / 0 |
| entities-0 | 15.0 | 24.0 | 3034.8 / 7439.1 / 11029.4 | 466.1 / 1303.9 / 2031.5 | 455.8 / 1057.8 / 1952.6 | 3.6 / 0.3 | 1048.6 | 461064.0 | 17.6 | - | 1152 | 327.6 | 3872 / 0 |
| entities-32 | 48.0 | 57.0 | 3031.2 / 5472.8 / 6539.3 | 630.9 / 869.7 / 2061.8 | 399.1 / 528.9 / 1825.0 | 0.5 / 0.4 | 1045.8 | 1302880.0 | 12.7 | - | 1152 | 351.4 | 5499 / 0 |
| entities-128 | 143.0 | 152.0 | 3493.5 / 6623.8 / 9316.2 | 1595.5 / 4267.0 / 5426.5 | 511.2 / 1162.2 / 2905.2 | 0.4 / 0.0 | 2334.1 | 3738848.0 | 24.9 | - | 1152 | 359.8 | 7128 / 0 |
| entities-256 | 256.0 | 265.0 | 3759.2 / 7972.5 / 11436.2 | 2607.7 / 4691.9 / 8890.4 | 633.9 / 1121.1 / 2200.8 | 0.4 / 0.0 | 3503.7 | 6630656.0 | 39.4 | - | 1152 | 360.9 | 8719 / 0 |
| entities-256-waypoints-200 | 256.0 | 472.0 | 4242.6 / 8693.8 / 11828.1 | 2437.1 / 3551.3 / 4186.2 | 2403.8 / 3367.2 / 4038.8 | 0.4 / 0.0 | 4983.7 | 6668792.0 | 43.2 | - | 1216 | 360.5 | 18499 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 472.0 | 4141.0 / 8683.5 / 10876.2 | 2481.6 / 3681.4 / 4114.5 | 2739.7 / 4005.0 / 4784.3 | 1.2 / 2.4 | 5344.5 | 6737096.0 | 41.8 | - | 1216 | 360.7 | 50160 / 0 |

### example:radar sweep (Java (native JVM ports, same host API))

Compile (cold, per launch): example:radar 2 ms (94 KiB)

| phase | entities seen | draw cmds | frame p50 / p95 / p99 | handle-events p50 / p99 / max | render p50 / p99 / max | convert (events / render) p50 | guest us per tick | alloc per call p50 (B) | nearby-entities host p50 | net rtt p50 | linear mem (KiB) | heap after GC (MiB) | bytes in / out (session total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | - | - | 3051.4 / 5488.7 / 6830.8 | 8.1 / 26.0 / 38.6 | 3.0 / 5.6 / 8.6 | - / - | 12.3 | 32.0 | - | - | - | 290.7 | 2246 / 0 |
| entities-0 | 20.0 | 28.0 | 3187.4 / 5707.4 / 7189.2 | 34.7 / 66.9 / 84.2 | 29.0 / 67.7 / 115.7 | - / - | 67.4 | 6288.0 | 11.2 | - | - | 290.4 | 3872 / 0 |
| entities-32 | 51.0 | 59.0 | 3411.2 / 5742.8 / 7128.5 | 34.1 / 58.9 / 72.4 | 23.0 / 51.6 / 56.3 | - / - | 59.6 | 13752.0 | 11.2 | - | - | 290.8 | 5499 / 0 |
| entities-128 | 148.0 | 156.0 | 3605.7 / 5606.9 / 7169.8 | 43.9 / 75.5 / 203.1 | 20.3 / 49.2 / 57.6 | - / - | 67.4 | 35624.0 | 18.9 | - | - | 292.0 | 7089 / 0 |
| entities-256 | 256.0 | 264.0 | 3712.6 / 5723.9 / 7286.6 | 54.0 / 83.2 / 1288.4 | 18.0 / 39.7 / 48.1 | - / - | 76.9 | 62080.0 | 27.2 | - | - | 293.0 | 8719 / 0 |
| entities-256-waypoints-200 | 256.0 | 472.0 | 3777.1 / 5913.4 / 7510.1 | 54.9 / 85.8 / 102.8 | 27.6 / 51.5 / 61.8 | - / - | 85.7 | 62144.0 | 27.3 | - | - | 293.1 | 18499 / 0 |
| entities-256-waypoints-200-broadcast-20hz | 256.0 | 472.0 | 3687.5 / 5789.4 / 7435.3 | 58.0 / 88.2 / 98.5 | 26.9 / 47.8 / 58.9 | - / - | 87.4 | 62392.0 | 27.8 | - | - | 293.0 | 50121 / 0 |

### Wasm against the Java control

Same samples, same host and timers; only the guest code differs. Guest time is every guest call summed per client tick.

| scenario | Wasm engine | Wasm guest us/tick | Java guest us/tick | Wasm / Java | alloc KiB/tick Wasm / Java | client tick p50 Wasm / Java | frame p95 Wasm / Java |
| --- | --- | --- | --- | --- | --- | --- | --- |
| one | Endive CM (compiler, validated) | 180.0 | 52.3 | 3.4x | 8.9 / 1.9 | 860.9 / 682.3 | 5589.6 / 7258.2 |
| several | Endive CM (compiler, validated) | 652.6 | 33.6 | 19.4x | 66.1 / 13.7 | 1080.0 / 579.9 | 6279.6 / 5549.1 |
| several-interpreter | Endive CM (interpreter, validated) | 2120.0 | 33.6 | 63.0x | 2684.2 / 13.7 | 2180.9 / 579.9 | 6669.0 / 5549.1 |
| calls | Endive CM (compiler, validated) | 169.7 | 106.2 | 1.6x | 583.1 / 168.0 | 803.4 / 677.5 | 6054.0 / 5671.6 |
| rtt | Endive CM (compiler, validated) | 83.0 | 12.9 | 6.4x | 3.0 / 0.2 | 646.0 / 583.0 | 5923.3 / 5397.9 |

### example:radar against the Java control

| phase | Java us/tick | compiler us/tick | compiler / Java | interpreter us/tick | interpreter / Java | alloc per call p50 KiB: Java / compiler / interpreter | frame p95: Java / compiler / interpreter |
| --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | 12.3 | 93.6 | 7.6x | 152.2 | 12.4x | 0.0 / 1.2 / 34.7 | 5488.7 / 5491.0 / 7076.4 |
| entities-0 | 67.4 | 245.5 | 3.6x | 1048.6 | 15.6x | 6.1 / 34.1 / 450.3 | 5707.4 / 5347.1 / 7439.1 |
| entities-32 | 59.6 | 249.1 | 4.2x | 1045.8 | 17.5x | 13.4 / 86.5 / 1272.3 | 5742.8 / 5545.0 / 5472.8 |
| entities-128 | 67.4 | 269.5 | 4.0x | 2334.1 | 34.6x | 34.8 / 233.6 / 3651.2 | 5606.9 / 5803.6 / 6623.8 |
| entities-256 | 76.9 | 320.8 | 4.2x | 3503.7 | 45.6x | 60.6 / 401.8 / 6475.2 | 5723.9 / 5824.4 / 7972.5 |
| entities-256-waypoints-200 | 85.7 | 384.6 | 4.5x | 4983.7 | 58.2x | 60.7 / 401.8 / 6512.5 | 5913.4 / 5820.3 / 8693.8 |
| entities-256-waypoints-200-broadcast-20hz | 87.4 | 445.2 | 5.1x | 5344.5 | 61.2x | 60.9 / 402.7 / 6579.2 | 5789.4 / 6625.9 / 8683.5 |


### What the Java comparison actually means

The Java engine runs the exact same mods rewritten in plain Java, through the same host code and the same timers, so the only thing that changes between runs is Wasm vs. normal JVM code. That makes Java the control, since that's what Minecraft mods are normally written in.

Short version: plain Java is about 4-5x faster than the compiled Wasm engine for the radar, and way faster than that for tiny mods. But in actual time, the gap is under 0.4 ms a tick. That's less than 1% of the 50 ms a tick gets, and it doesn't show up in frame times at all.

| Radar phase | Java | Wasm (compiled) | Ratio | Extra cost of Wasm | Share of a tick |
| --- | --- | --- | --- | --- | --- |
| Radar off | 12 µs | 94 µs | 7.6x | 0.08 ms | 0.2% |
| 256 entities | 77 µs | 321 µs | 4.2x | 0.24 ms | 0.5% |
| + waypoints and 20 Hz broadcast | 87 µs | 445 µs | 5.1x | 0.36 ms | 0.7% |

Frame p95 was basically the same for Java and compiled Wasm in every phase (5.3 to 6.6 ms), which is just run-to-run noise. The interpreter is the only one you can actually see in frame times. It's 12-61x slower than Java and pushes p95 up to 7-8.7 ms.

#### Where the extra Wasm cost comes from

Most of it is a fixed cost every time the game calls into a mod, not the mod's code itself being slow. With the radar turned off, it barely does anything, and it still costs 94 µs a tick in Wasm vs. 12 µs in Java. Ping just returns "unchanged" every tick, and that's 83 µs vs. 13 µs. With 8 warmed-up copies of the tiny HUD mod, one render call is 23 µs in Wasm and 0.1 µs in Java. So every call pays roughly 20-40 µs just to get into the Wasm mod and back out, no matter how much work it does. That's why tiny mods show crazy ratios (19x for 8 mods), while the radar, which does real work, sits at 4-5x.

The mod's own work is only a few times slower. Going from 0 to 256 entities adds about 10 µs in Java and about 75 µs in Wasm, so around 7x. Both of those include the same 15-40 µs the host spends looking up nearby entities.

A mod calling back into the game is pretty much free. 1,000 host calls a tick cost 106 µs in Java and 170 µs in Wasm, so the Wasm crossing only adds around 64 ns per call. Most of that 106 µs is actually our timing code recording every call, and both engines pay for that.

Garbage is the other real difference. At 256 entities, Java makes about 61 KB of short-lived objects per call and Wasm makes about 402 KB, so 6.6x more. Java's 61 KB is mostly the host building the entity records, which both engines pay for. The extra ~340 KB is Wasm copying data across the boundary, so the entity list conversion is the thing to cut there.

#### Why 1 mod looks worse than 8 mods

A mod that only runs once every 50 ms finds its code and data out of the CPU cache every time, and that hits both engines. The game's own player lookup, which both engines share, costs 6-8 µs in the 1-mod runs and 0.2 µs when 8 mods call it back to back. So the 1-mod rows mostly show that cold cost, and the 8-mod rows show what a mod really costs once things are warm. A real modpack with a bunch of mods is going to look a lot more like the 8-mod case.

#### What to take from this

- Compared to plain Java, the sandbox costs about 4-5x more CPU for a mod doing real work. In real time, that's 0.1-0.4 ms a tick, and frames don't change. What you get for it is that a mod that crashes or loops forever gets stopped without killing the game, and plain Java can't do that.
- The thing to optimize is the fixed cost per call, not the Wasm code. Cutting those 20-40 µs would close the gap more than anything else. Endive's newer Cranelift-based compiler only speeds up the mod's own code, so it wouldn't help much here.
- The interpreter isn't competitive with Java for anything but really light mods.

One caveat: the Wasm HUD, ping, and host call numbers are from the 18:26-18:36 runs, and the Java ones are from 19:05-19:10, so there's a bit of noise between them. The radar comparison ran compiled and Java back to back with the same mods loaded, so that's the one to trust most.
