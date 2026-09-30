package dev.pumpkinmc.patch.core.catalog;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.manifest.Manifest;
import dev.pumpkinmc.patch.core.runtime.Runtime.CompiledComponent;
import java.nio.file.Path;
import java.util.Set;

/** One discovered mod and its launch-scope status. Immutable; a status change is a new entry. */
public record CatalogEntry(
        Path dir,
        Manifest manifest,
        Status status,
        String reason,
        byte[] bytes,
        Set<Capability> granted,
        CompiledComponent compiled,
        long compileNanos) {

    public enum Status {
        RESOLVED,
        COMPILED,
        REJECTED,
        DISABLED
    }

    public String id() {
        return manifest != null ? manifest.id() : dir.getFileName().toString();
    }

    CatalogEntry withStatus(Status s, String why) {
        return new CatalogEntry(dir, manifest, s, why, bytes, granted, compiled, compileNanos);
    }

    CatalogEntry compiledAs(CompiledComponent c, long nanos) {
        return new CatalogEntry(dir, manifest, Status.COMPILED, "", bytes, granted, c, nanos);
    }
}
