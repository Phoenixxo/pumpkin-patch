package dev.pumpkinmc.patch.core.manifest;

import dev.pumpkinmc.patch.core.capability.Capability;
import java.util.List;
import java.util.Set;

/** A parsed {@code pumpkin-mod.toml}, manifest-version 1. */
public record Manifest(
        String id,
        String name,
        String version,
        String component,
        String sha256,
        String world,
        Activation activation,
        Set<Capability> required,
        Set<Capability> optional,
        String protocol,
        List<String> channels,
        List<ActionDecl> actions) {

    public enum Activation {
        ALWAYS,
        PUMPKIN_SERVER
    }

    public record ActionDecl(String id, String title, String defaultKey) {}

    public Set<Capability> declared() {
        var all = java.util.EnumSet.noneOf(Capability.class);
        all.addAll(required);
        all.addAll(optional);
        return all;
    }
}
