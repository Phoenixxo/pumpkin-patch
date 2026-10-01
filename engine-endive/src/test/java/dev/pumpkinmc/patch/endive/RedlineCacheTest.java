package dev.pumpkinmc.patch.endive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.pumpkinmc.patch.core.catalog.Catalog;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentSource;
import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;
import dev.pumpkinmc.patch.endive.EndiveComponentRuntime.Engine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native code is compiled once per mod and machine, then read back from the cache. */
class RedlineCacheTest {
    @TempDir
    Path cache;

    private static ComponentSource ping() throws Exception {
        byte[] bytes = Files.readAllBytes(Harness.GUESTS.resolve("ping.wasm"));
        return new ComponentSource("example:ping", bytes, Catalog.sha256(bytes), "pumpkin:client/client-mod@0.1.0");
    }

    private List<Path> entries() throws Exception {
        try (Stream<Path> files = Files.list(cache)) {
            return files.filter(f -> f.toString().endsWith(".cl4j")).toList();
        }
    }

    @Test
    void compiledCodeIsReusedAndABadEntryIsCompiledAgain() throws Exception {
        var runtime = new EndiveComponentRuntime(Engine.REDLINE, true, cache);
        assumeTrue(runtime.engine() == Engine.REDLINE, "Redline has no backend for this machine");

        long cold = System.nanoTime();
        runtime.compile(ping(), RuntimeLimits.defaults());
        cold = System.nanoTime() - cold;
        List<Path> written = entries();
        assertEquals(1, written.size(), "one native module, the one owning memory");
        long size = Files.size(written.getFirst());

        long warm = System.nanoTime();
        runtime.compile(ping(), RuntimeLimits.defaults());
        warm = System.nanoTime() - warm;
        assertTrue(warm < cold / 2, "cached compile " + warm / 1_000_000 + " ms, cold " + cold / 1_000_000 + " ms");

        Files.write(written.getFirst(), new byte[] {1, 2, 3});
        runtime.compile(ping(), RuntimeLimits.defaults());
        assertEquals(size, Files.size(written.getFirst()), "the corrupt entry is replaced");
    }
}
