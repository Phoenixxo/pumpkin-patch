#!/usr/bin/env python3
"""Merges benchmark JSON from fabric/run/pumpkin-patch-results into markdown tables.

    scripts/perf-report.py [results dir] > report.md

Timings are microseconds. p50/p95/p99 are nearest-rank percentiles over every sample in the
measured window. A "-" means the series has no samples in that run.
"""
import glob
import json
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "fabric/run/pumpkin-patch-results"


def pct(s, key="p50"):
    return "-" if not s or s["n"] == 0 else f"{s[key]:.1f}"


def row(cells):
    return "| " + " | ".join(str(c) for c in cells) + " |"


def trio(s):
    return "-" if not s or s["n"] == 0 else f"{s['p50']:.1f} / {s['p95']:.1f} / {s['p99']:.1f}"


def per_tick_guest_us(perf, mod, frames_or_ticks):
    """Sum of every guest call for a mod divided by client ticks measured."""
    total = sum(v["sum"] for k, v in perf["timings_us"].items() if k.startswith("guest.") and k.endswith("/" + mod))
    return total / frames_or_ticks if frames_or_ticks else 0


def first(d, *keys):
    """The first of keys present in d. Runs before update replaced handle-events use the old names."""
    return next((d[k] for k in keys if d.get(k)), None)


out = []
env_printed = False

benches = sorted(glob.glob(os.path.join(root, "bench-*.json")))
if benches:
    out.append("## Client frame and tick time by scenario\n")
    out.append(row(["scenario", "engine", "frames", "frame p50 / p95 / p99 (us)", "client tick p50 / p95 / p99 (us)",
                    "host tick drain p50 / p99", "heap after GC (MiB)"]))
    out.append(row(["---"] * 7))
    for f in benches:
        d = json.load(open(f))
        probe = d["perf"]["probe"]["timings_us"]
        host = (d["perf"]["host"] or {"timings_us": {}})["timings_us"]
        frame = probe.get("client.frame") or host.get("client.frame")
        tick = probe.get("client.tick") or host.get("client.tick")
        drain = host.get("drain.tick")
        if not env_printed:
            out.insert(0, "Environment: `" + json.dumps(d["environment"]) + "`\n")
            env_printed = True
        out.append(row([d["scenario"], d["environment"]["engine"], frame["n"] if frame else 0, trio(frame), trio(tick),
                        f"{pct(drain)} / {pct(drain, 'p99')}", "-"]))
    out.append("")

for f in sorted(glob.glob(os.path.join(root, "radar-*.json"))):
    d = json.load(open(f))
    if not env_printed:
        out.insert(0, "Environment: `" + json.dumps(d["environment"]) + "`\n")
        env_printed = True
    out.append(f"## example:radar sweep ({d['environment']['engine']})\n")
    out.append("Compile (cold, per launch): " + ", ".join(
        f"{c['id']} {c['compile_ms']:.0f} ms ({c['bytes'] // 1024} KiB)" for c in d["catalog"]) + "\n")
    out.append(row(["phase", "entities seen", "draw cmds", "frame p50 / p95 / p99", "update p50 / p99 / max",
                    "render p50 / p99 / max (before update)", "convert p50", "guest us per tick",
                    "alloc per call p50 (B)", "nearby-entities host p50", "net rtt p50", "linear mem (KiB)",
                    "heap after GC (MiB)", "bytes in / out (session total)"]))
    out.append(row(["---"] * 14))
    for p in d["phases"]:
        t = p["perf"]["timings_us"]
        v = p["perf"]["values"]
        m = "example:radar"
        he = first(t, f"guest.update/{m}", f"guest.handle-events/{m}")
        rd = t.get(f"guest.render/{m}")
        ticks = (t.get("client.tick") or {"n": 0})["n"]
        radar = next((i for i in p["instances"] if i["id"] == m), {})
        alloc = first(v, f"alloc-bytes.update/{m}", f"alloc-bytes.handle-events/{m}")
        convert = first(t, f"convert.update/{m}", f"convert.handle-events/{m}")
        out.append(row([
            p["phase"], pct(v.get(f"count.nearby-entities/{m}")), pct(v.get(f"count.draw-commands/{m}")),
            trio(t.get("client.frame")),
            "-" if not he else f"{he['p50']:.1f} / {he['p99']:.1f} / {he['max']:.1f}",
            "-" if not rd else f"{rd['p50']:.1f} / {rd['p99']:.1f} / {rd['max']:.1f}",
            pct(convert),
            f"{per_tick_guest_us(p['perf'], m, ticks):.1f}", pct(alloc), pct(t.get(f"host.view.nearby-entities/{m}")),
            pct(t.get("net.rtt")), "-" if radar.get("linear_memory_bytes", -1) < 0 else radar["linear_memory_bytes"] // 1024, p["heap"]["after_gc_mib"],
            f"{radar.get('bytes_in', 0)} / {radar.get('bytes_out', 0)}",
        ]))
    out.append("")

# ---- Wasm against the Java control ----
# The java engine runs the same samples ported to plain Java through the same host, dispatch and
# timers, so the ratio is what running them as sandboxed Wasm costs over JVM code.


def load(path):
    return json.load(open(path)) if os.path.exists(path) else None


def guest_per_tick(perf, ticks, mod=None):
    """Microseconds of guest calls per client tick, for one mod or all of them."""
    total = sum(v["sum"] for k, v in perf["timings_us"].items()
                if k.startswith("guest.") and "/" in k and (mod is None or k.endswith("/" + mod)))
    return total / ticks if ticks else 0


def alloc_per_tick(perf, ticks, mod=None):
    total = sum(v["sum"] for k, v in perf["values"].items()
                if k.startswith("alloc-bytes.") and (mod is None or k.endswith("/" + mod)))
    return total / ticks if ticks else 0


def ratio(a, b):
    return "-" if not b else f"{a / b:.1f}x"


def kib(b):
    return f"{b / 1024:.1f}"


pairs = [("one", "one-java"), ("several", "several-java"), ("several-interpreter", "several-java"),
         ("calls", "calls-java"), ("rtt", "rtt-java"),
         ("one-redline", "one-java"), ("several-redline", "several-java"),
         ("calls-redline", "calls-java"), ("rtt-redline", "rtt-java")]
rows = []
for wasm, java in pairs:
    w = load(os.path.join(root, f"bench-{wasm}.json"))
    j = load(os.path.join(root, f"bench-{java}.json"))
    if not w or not j:
        continue
    cells = []
    for d in (w, j):
        host = d["perf"]["host"]
        ticks = (host["timings_us"].get("client.tick") or {"n": 0})["n"]
        cells.append((guest_per_tick(host, ticks), alloc_per_tick(host, ticks), host["timings_us"].get("client.tick"),
                      host["timings_us"].get("client.frame")))
    (wg, wa, wt, wf), (jg, ja, jt, jf) = cells
    rows.append(row([wasm, w["environment"]["engine"],
                     f"{wg:.1f}", f"{jg:.1f}", ratio(wg, jg), f"{kib(wa)} / {kib(ja)}",
                     f"{pct(wt)} / {pct(jt)}", f"{pct(wf, 'p95')} / {pct(jf, 'p95')}"]))
if rows:
    out.append("## Wasm against the Java control\n")
    out.append("Same samples, same host and timers; only the guest code differs. Guest time is every guest call "
               "summed per client tick.\n")
    out.append(row(["scenario", "Wasm engine", "Wasm guest us/tick", "Java guest us/tick", "Wasm / Java",
                    "alloc KiB/tick Wasm / Java", "client tick p50 Wasm / Java", "frame p95 Wasm / Java"]))
    out.append(row(["---"] * 8))
    out.extend(rows)
    out.append("")

radar = {e: load(os.path.join(root, f"radar-{e}.json")) for e in ("compiler", "redline", "interpreter", "java")}
if radar["java"] and (radar["compiler"] or radar["redline"] or radar["interpreter"]):
    m = "example:radar"
    engines = [e for e in ("compiler", "redline", "interpreter") if radar[e]]
    out.append("## example:radar against the Java control\n")
    header = ["phase", "Java us/tick"]
    for e in engines:
        header += [f"{e} us/tick", f"{e} / Java"]
    header += ["alloc per call p50 KiB: Java / " + " / ".join(engines),
               "frame p95: Java / " + " / ".join(engines)]
    out.append(row(header))
    out.append(row(["---"] * len(header)))
    by_phase = {e: {p["phase"]: p for p in radar[e]["phases"]} for e in radar if radar[e]}
    for phase in by_phase["java"]:
        cells = {}
        for e in by_phase:
            p = by_phase[e].get(phase)
            if not p:
                continue
            t = p["perf"]["timings_us"]
            ticks = (t.get("client.tick") or {"n": 0})["n"]
            alloc = first(p["perf"]["values"], f"alloc-bytes.update/{m}", f"alloc-bytes.handle-events/{m}")
            cells[e] = (guest_per_tick(p["perf"], ticks, m), alloc["p50"] if alloc and alloc["n"] else 0,
                        t.get("client.frame"))
        jg, ja, jf = cells["java"]
        line = [phase, f"{jg:.1f}"]
        for e in engines:
            g = cells.get(e, (0, 0, None))[0]
            line += [f"{g:.1f}", ratio(g, jg)]
        line += [" / ".join(kib(cells[e][1]) if e in cells else "-" for e in ["java"] + engines),
                 " / ".join(pct(cells[e][2], "p95") if e in cells else "-" for e in ["java"] + engines)]
        out.append(row(line))
    out.append("")

print("\n".join(out) if out else f"no results in {root}")
