package dev.pumpkinmc.patch.core.catalog;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry.Status;
import dev.pumpkinmc.patch.core.manifest.Manifest;
import dev.pumpkinmc.patch.core.manifest.ManifestParser;
import dev.pumpkinmc.patch.core.runtime.Runtime.CompileException;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentSource;
import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mods discovered at launch. Status is published by swapping an immutable snapshot, which is
 * the only launch-scope handoff between the compile thread and the client thread.
 */
public final class Catalog {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");
    private static final long MAX_COMPONENT_BYTES = 32L << 20;

    private final AtomicReference<List<CatalogEntry>> entries = new AtomicReference<>(List.of());
    private final CompletableFuture<Void> compiled = new CompletableFuture<>();

    public List<CatalogEntry> entries() {
        return entries.get();
    }

    public Optional<CatalogEntry> get(String id) {
        return entries.get().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Reads manifests and bytes synchronously, because key bindings are registered at init. */
    public void discover(Path modsDir) {
        List<CatalogEntry> found = new ArrayList<>();
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> dirs = Files.list(modsDir)) {
                dirs.filter(Files::isDirectory).sorted().forEach(dir -> found.add(load(dir)));
            } catch (IOException e) {
                LOG.error("[PumpkinPatch] cannot list {}", modsDir, e);
            }
        } else {
            LOG.info("[PumpkinPatch] {} does not exist; no components installed", modsDir);
        }
        Map<String, Integer> counts = new HashMap<>();
        found.forEach(e -> counts.merge(e.id(), 1, Integer::sum));
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry e : found) {
            out.add(counts.get(e.id()) > 1 && e.status() != Status.REJECTED
                    ? e.withStatus(Status.REJECTED, "DUPLICATE_ID")
                    : e);
        }
        out.sort(Comparator.comparing(CatalogEntry::id));
        entries.set(List.copyOf(out));
        for (CatalogEntry e : out) {
            LOG.info("[PumpkinPatch] {} {} {}", e.id(), e.status(), e.reason());
        }
    }

    private static CatalogEntry load(Path dir) {
        Path tomlPath = dir.resolve("pumpkin-mod.toml");
        try {
            var result = ManifestParser.parse(Files.readString(tomlPath, StandardCharsets.UTF_8));
            if (result instanceof ManifestParser.Result.Invalid invalid) {
                return rejected(dir, null, invalid.reason() + ": " + String.join("; ", invalid.problems()));
            }
            Manifest m = ((ManifestParser.Result.Ok) result).manifest();
            Path file = dir.resolve(m.component()).normalize();
            if (!file.startsWith(dir)) {
                return rejected(dir, m, "INVALID_MANIFEST: component path leaves the mod folder");
            }
            if (Files.size(file) > MAX_COMPONENT_BYTES) {
                return rejected(dir, m, "TOO_LARGE: component is above 32 MiB");
            }
            byte[] bytes = Files.readAllBytes(file);
            String actual = sha256(bytes);
            if (!actual.equals(m.sha256())) {
                return rejected(dir, m, "HASH_MISMATCH: manifest " + m.sha256() + ", file " + actual);
            }
            return new CatalogEntry(dir, m, Status.RESOLVED, "", bytes, Set.of(), null, 0);
        } catch (IOException e) {
            return rejected(dir, null, "INVALID_MANIFEST: " + e.getMessage());
        }
    }

    private static CatalogEntry rejected(Path dir, Manifest m, String reason) {
        return new CatalogEntry(dir, m, Status.REJECTED, reason, null, Set.of(), null, 0);
    }

    /** Compiles every resolved entry on one background thread. */
    public void compileInBackground(ComponentRuntime runtime, RuntimeLimits limits) {
        Thread t = new Thread(() -> compileAll(runtime, limits), "PumpkinPatch-compile");
        t.setDaemon(true);
        t.start();
    }

    /** Compiles on the calling thread. Used by tests and the benchmark harness. */
    public void compileAll(ComponentRuntime runtime, RuntimeLimits limits) {
        try {
            for (CatalogEntry e : entries.get()) {
                if (e.status() != Status.RESOLVED) {
                    continue;
                }
                long start = System.nanoTime();
                CatalogEntry next;
                try {
                    var c = runtime.compile(
                            new ComponentSource(e.id(), e.bytes(), e.manifest().sha256(), e.manifest().world()),
                            limits);
                    next = resolveCapabilities(e, c.importedInterfaces())
                            .map(problem -> e.withStatus(Status.REJECTED, problem))
                            .orElseGet(() -> e.compiledAs(c, System.nanoTime() - start));
                } catch (CompileException ex) {
                    next = e.withStatus(Status.REJECTED, ex.getMessage());
                } catch (RuntimeException | LinkageError ex) {
                    next = e.withStatus(Status.REJECTED, "COMPILE_ERROR: " + ex);
                }
                if (next.status() == Status.COMPILED) {
                    next = new CatalogEntry(
                            next.dir(), next.manifest(), next.status(), "", next.bytes(),
                            grant(next.manifest()), next.compiled(), next.compileNanos());
                }
                LOG.info("[PumpkinPatch] {} {} in {} ms {}", next.id(), next.status(),
                        (System.nanoTime() - start) / 1_000_000, next.reason());
                replace(next);
            }
        } finally {
            compiled.complete(null);
        }
    }

    /** Every requested capability except log must be declared. */
    private static Optional<String> resolveCapabilities(CatalogEntry e, Set<String> imports) {
        Set<Capability> requested = Capability.impliedBy(imports);
        Set<Capability> declared = e.manifest().declared();
        requested.removeAll(declared);
        requested.remove(Capability.LOG);
        return requested.isEmpty()
                ? Optional.empty()
                : Optional.of("UNDECLARED_CAPABILITY: " + requested);
    }

    /** The PoC policy allows every non-sensitive capability, so granted equals declared. */
    private static Set<Capability> grant(Manifest m) {
        Set<Capability> g = EnumSet.of(Capability.LOG);
        g.addAll(m.declared());
        return Set.copyOf(g);
    }

    private void replace(CatalogEntry next) {
        entries.updateAndGet(list -> list.stream().map(e -> e.dir().equals(next.dir()) ? next : e).toList());
    }

    /** Waits for the compile thread, at most {@code millis}. */
    public boolean awaitCompiled(long millis) {
        try {
            compiled.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
