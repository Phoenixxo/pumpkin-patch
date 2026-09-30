Environment: `{"os": "Mac OS X 26.6.2", "arch": "aarch64", "cpus": 12, "java": "25.0.2", "jvm": "Java HotSpot(TM) 64-Bit Server VM", "max_heap_mib": 3072, "minecraft": "26.3", "fabric_loader": "0.19.5", "fabric_api": "0.161.0+26.3", "engine": "none (host not installed)", "warmup_s": 30, "measure_s": 60, "fps_limit": 260, "vsync": false, "window": "1488x1818", "gui_scale": 3, "render_distance": 12}`

## Client frame and tick time by scenario

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

## example:radar sweep (Endive CM (compiler, validated))

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

## example:radar sweep (Endive CM (interpreter, validated))

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

## example:radar sweep (Java (native JVM ports, same host API))

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

## Wasm against the Java control

Same samples, same host and timers; only the guest code differs. Guest time is every guest call summed per client tick.

| scenario | Wasm engine | Wasm guest us/tick | Java guest us/tick | Wasm / Java | alloc KiB/tick Wasm / Java | client tick p50 Wasm / Java | frame p95 Wasm / Java |
| --- | --- | --- | --- | --- | --- | --- | --- |
| one | Endive CM (compiler, validated) | 180.0 | 52.3 | 3.4x | 8.9 / 1.9 | 860.9 / 682.3 | 5589.6 / 7258.2 |
| several | Endive CM (compiler, validated) | 652.6 | 33.6 | 19.4x | 66.1 / 13.7 | 1080.0 / 579.9 | 6279.6 / 5549.1 |
| several-interpreter | Endive CM (interpreter, validated) | 2120.0 | 33.6 | 63.0x | 2684.2 / 13.7 | 2180.9 / 579.9 | 6669.0 / 5549.1 |
| calls | Endive CM (compiler, validated) | 169.7 | 106.2 | 1.6x | 583.1 / 168.0 | 803.4 / 677.5 | 6054.0 / 5671.6 |
| rtt | Endive CM (compiler, validated) | 83.0 | 12.9 | 6.4x | 3.0 / 0.2 | 646.0 / 583.0 | 5923.3 / 5397.9 |

## example:radar against the Java control

| phase | Java us/tick | compiler us/tick | compiler / Java | interpreter us/tick | interpreter / Java | alloc per call p50 KiB: Java / compiler / interpreter | frame p95: Java / compiler / interpreter |
| --- | --- | --- | --- | --- | --- | --- | --- |
| radar-off | 12.3 | 93.6 | 7.6x | 152.2 | 12.4x | 0.0 / 1.2 / 34.7 | 5488.7 / 5491.0 / 7076.4 |
| entities-0 | 67.4 | 245.5 | 3.6x | 1048.6 | 15.6x | 6.1 / 34.1 / 450.3 | 5707.4 / 5347.1 / 7439.1 |
| entities-32 | 59.6 | 249.1 | 4.2x | 1045.8 | 17.5x | 13.4 / 86.5 / 1272.3 | 5742.8 / 5545.0 / 5472.8 |
| entities-128 | 67.4 | 269.5 | 4.0x | 2334.1 | 34.6x | 34.8 / 233.6 / 3651.2 | 5606.9 / 5803.6 / 6623.8 |
| entities-256 | 76.9 | 320.8 | 4.2x | 3503.7 | 45.6x | 60.6 / 401.8 / 6475.2 | 5723.9 / 5824.4 / 7972.5 |
| entities-256-waypoints-200 | 85.7 | 384.6 | 4.5x | 4983.7 | 58.2x | 60.7 / 401.8 / 6512.5 | 5913.4 / 5820.3 / 8693.8 |
| entities-256-waypoints-200-broadcast-20hz | 87.4 | 445.2 | 5.1x | 5344.5 | 61.2x | 60.9 / 402.7 / 6579.2 | 5789.4 / 6625.9 / 8683.5 |


## What the Java comparison actually means

The Java engine runs the exact same mods rewritten in plain Java, through the same host code and the same timers, so the only thing that changes between runs is Wasm vs. normal JVM code. That makes Java the control, since that's what Minecraft mods are normally written in.

Short version: plain Java is about 4-5x faster than the compiled Wasm engine for the radar, and way faster than that for tiny mods. But in actual time, the gap is under 0.4 ms a tick. That's less than 1% of the 50 ms a tick gets, and it doesn't show up in frame times at all.

| Radar phase | Java | Wasm (compiled) | Ratio | Extra cost of Wasm | Share of a tick |
| --- | --- | --- | --- | --- | --- |
| Radar off | 12 µs | 94 µs | 7.6x | 0.08 ms | 0.2% |
| 256 entities | 77 µs | 321 µs | 4.2x | 0.24 ms | 0.5% |
| + waypoints and 20 Hz broadcast | 87 µs | 445 µs | 5.1x | 0.36 ms | 0.7% |

Frame p95 was basically the same for Java and compiled Wasm in every phase (5.3 to 6.6 ms), which is just run-to-run noise. The interpreter is the only one you can actually see in frame times. It's 12-61x slower than Java and pushes p95 up to 7-8.7 ms.

### Where the extra Wasm cost comes from

Most of it is a fixed cost every time the game calls into a mod, not the mod's code itself being slow. With the radar turned off, it barely does anything, and it still costs 94 µs a tick in Wasm vs. 12 µs in Java. Ping just returns "unchanged" every tick, and that's 83 µs vs. 13 µs. With 8 warmed-up copies of the tiny HUD mod, one render call is 23 µs in Wasm and 0.1 µs in Java. So every call pays roughly 20-40 µs just to get into the Wasm mod and back out, no matter how much work it does. That's why tiny mods show crazy ratios (19x for 8 mods), while the radar, which does real work, sits at 4-5x.

The mod's own work is only a few times slower. Going from 0 to 256 entities adds about 10 µs in Java and about 75 µs in Wasm, so around 7x. Both of those include the same 15-40 µs the host spends looking up nearby entities.

A mod calling back into the game is pretty much free. 1,000 host calls a tick cost 106 µs in Java and 170 µs in Wasm, so the Wasm crossing only adds around 64 ns per call. Most of that 106 µs is actually our timing code recording every call, and both engines pay for that.

Garbage is the other real difference. At 256 entities, Java makes about 61 KB of short-lived objects per call and Wasm makes about 402 KB, so 6.6x more. Java's 61 KB is mostly the host building the entity records, which both engines pay for. The extra ~340 KB is Wasm copying data across the boundary, so the entity list conversion is the thing to cut there.

### Why 1 mod looks worse than 8 mods

A mod that only runs once every 50 ms finds its code and data out of the CPU cache every time, and that hits both engines. The game's own player lookup, which both engines share, costs 6-8 µs in the 1-mod runs and 0.2 µs when 8 mods call it back to back. So the 1-mod rows mostly show that cold cost, and the 8-mod rows show what a mod really costs once things are warm. A real modpack with a bunch of mods is going to look a lot more like the 8-mod case.

### What to take from this

- Compared to plain Java, the sandbox costs about 4-5x more CPU for a mod doing real work. In real time, that's 0.1-0.4 ms a tick, and frames don't change. What you get for it is that a mod that crashes or loops forever gets stopped without killing the game, and plain Java can't do that.
- The thing to optimize is the fixed cost per call, not the Wasm code. Cutting those 20-40 µs would close the gap more than anything else. Endive's newer Cranelift-based compiler only speeds up the mod's own code, so it wouldn't help much here.
- The interpreter isn't competitive with Java for anything but really light mods.

One caveat: the Wasm HUD, ping, and host call numbers are from the 18:26-18:36 runs, and the Java ones are from 19:05-19:10, so there's a bit of noise between them. The radar comparison ran compiled and Java back to back with the same mods loaded, so that's the one to trust most.
