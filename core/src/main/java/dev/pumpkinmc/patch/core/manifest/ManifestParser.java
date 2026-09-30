package dev.pumpkinmc.patch.core.manifest;

import dev.pumpkinmc.patch.core.capability.Capability;
import dev.pumpkinmc.patch.core.manifest.Manifest.ActionDecl;
import dev.pumpkinmc.patch.core.manifest.Manifest.Activation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Parses and validates {@code pumpkin-mod.toml}. Returns every problem rather than the first. */
public final class ManifestParser {
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+");
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern ACTION = Pattern.compile("[a-z0-9_-]{1,32}");

    private ManifestParser() {}

    public sealed interface Result {
        record Ok(Manifest manifest) implements Result {}

        record Invalid(String reason, List<String> problems) implements Result {}
    }

    public static Result parse(String text) {
        TomlParseResult toml = Toml.parse(text);
        List<String> problems = new ArrayList<>();
        toml.errors().forEach(e -> problems.add(e.toString()));
        if (!problems.isEmpty()) {
            return new Result.Invalid("INVALID_MANIFEST", problems);
        }
        Long mv = toml.getLong("manifest-version");
        if (mv == null || mv != 1) {
            return new Result.Invalid("UNSUPPORTED_MANIFEST", List.of("manifest-version must be 1, got " + mv));
        }
        if (toml.contains("dependencies") || toml.contains("mod.dependencies")) {
            return new Result.Invalid("UNSUPPORTED_FEATURE", List.of("the dependencies key is reserved"));
        }
        String id = required(toml, "mod.id", problems);
        String name = required(toml, "mod.name", problems);
        String version = required(toml, "mod.version", problems);
        if (id != null && !MOD_ID.matcher(id).matches()) {
            problems.add("mod.id must be namespace:path using [a-z0-9_.-], got " + id);
        }
        if (version != null && SemVer.parse(version) == null) {
            problems.add("mod.version must be semver, got " + version);
        }
        String component = required(toml, "client.component", problems);
        String sha256 = required(toml, "client.sha256", problems);
        String world = required(toml, "client.world", problems);
        String activationText = toml.getString("client.activation", () -> "pumpkin-server");
        Activation activation =
                switch (activationText) {
                    case "always" -> Activation.ALWAYS;
                    case "pumpkin-server" -> Activation.PUMPKIN_SERVER;
                    default -> {
                        problems.add("client.activation must be always or pumpkin-server");
                        yield Activation.PUMPKIN_SERVER;
                    }
                };
        Set<Capability> req = capabilities(toml.getArray("client.capabilities.required"), problems);
        Set<Capability> opt = capabilities(toml.getArray("client.capabilities.optional"), problems);
        String protocol = toml.getString("client.net.protocol", () -> "");
        List<String> channels = new ArrayList<>();
        TomlArray ch = toml.getArray("client.net.channels");
        if (ch != null) {
            for (int i = 0; i < ch.size(); i++) {
                String c = ch.getString(i);
                if (!CHANNEL.matcher(c).matches()) {
                    problems.add("channel name must match [a-z0-9_-]{1,32}, got " + c);
                }
                channels.add(c);
            }
        }
        List<ActionDecl> actions = new ArrayList<>();
        TomlArray acts = toml.getArray("client.actions");
        if (acts != null) {
            for (int i = 0; i < acts.size(); i++) {
                TomlTable t = acts.getTable(i);
                String aid = t.getString("id");
                if (aid == null || !ACTION.matcher(aid).matches()) {
                    problems.add("client.actions[" + i + "].id must match [a-z0-9_-]{1,32}");
                    continue;
                }
                actions.add(new ActionDecl(aid, t.getString("title", () -> aid), t.getString("default-key", () -> "")));
            }
        }
        boolean declaresInput = req.contains(Capability.INPUT) || opt.contains(Capability.INPUT);
        if (!actions.isEmpty() && !declaresInput) {
            problems.add("client.actions requires the input capability");
        }
        if (!problems.isEmpty()) {
            return new Result.Invalid("INVALID_MANIFEST", problems);
        }
        return new Result.Ok(new Manifest(
                id, name, version, component, sha256.toLowerCase(), world, activation, req, opt, protocol,
                List.copyOf(channels), List.copyOf(actions)));
    }

    private static String required(TomlParseResult toml, String key, List<String> problems) {
        String v = toml.getString(key);
        if (v == null || v.isBlank()) {
            problems.add(key + " is required");
        }
        return v;
    }

    private static Set<Capability> capabilities(TomlArray array, List<String> problems) {
        Set<Capability> out = EnumSet.noneOf(Capability.class);
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            String c = array.getString(i);
            Capability.parse(c).ifPresentOrElse(out::add, () -> problems.add("unknown capability " + c));
        }
        return out;
    }
}
