package dev.pumpkinmc.patch.jvm;

import dev.pumpkinmc.patch.core.runtime.Runtime.CompileException;
import dev.pumpkinmc.patch.core.runtime.Runtime.CompiledComponent;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentSource;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.RuntimeLimits;
import dev.pumpkinmc.patch.jvm.guests.HostCallsGuest;
import dev.pumpkinmc.patch.jvm.guests.HudBenchGuest;
import dev.pumpkinmc.patch.jvm.guests.PingGuest;
import dev.pumpkinmc.patch.jvm.guests.RadarGuest;
import java.util.Set;
import java.util.function.Function;

/**
 * The benchmark control: runs a Java port of a sample component in place of its Wasm.
 *
 * <p>Everything around the guest is shared with the Wasm engines: the catalog, manifests,
 * capability checks, handshake, event dispatch, host imports, perf keys, and the watchdog. The
 * component bytes are ignored and the port is chosen by mod id. So a comparison against the Endive
 * engines measures what running the same logic as sandboxed Wasm costs over plain JVM code.
 *
 * <p>This is not a sandbox. A port that loops forever cannot be stopped, which is why the trap and
 * spin samples have no port.
 */
public final class JavaComponentRuntime implements ComponentRuntime {
    private static final String VIEW = "pumpkin:client/view@0.1.0";
    private static final String NET = "pumpkin:client/net@0.1.0";
    private static final String HUD = "pumpkin:client/hud@0.1.0";
    private static final String LOG = "pumpkin:client/log@0.1.0";

    private record Port(Set<String> imports, Function<HostImports, JavaGuest> create) {}

    @Override
    public String describe() {
        return "Java (native JVM ports, same host API)";
    }

    @Override
    public CompiledComponent compile(ComponentSource source, RuntimeLimits limits) throws CompileException {
        Port port = port(source.modId());
        if (port == null) {
            throw new CompileException(CompileException.Reason.UNSUPPORTED_WORLD,
                    source.modId() + " has no Java port", null);
        }
        return new CompiledComponent() {
            @Override
            public String world() {
                return source.declaredWorld();
            }

            @Override
            public Set<String> importedInterfaces() {
                return port.imports();
            }

            @Override
            public GuestInstance instantiate(HostImports imports) {
                return port.create().apply(imports);
            }
        };
    }

    /** The port for a mod id, with the same imports its Wasm component has. */
    private static Port port(String modId) {
        if (modId.startsWith("bench:hud-")) {
            return new Port(Set.of(LOG, VIEW), HudBenchGuest::new);
        }
        return switch (modId) {
            case "example:radar" -> new Port(Set.of(LOG, VIEW, NET, HUD), RadarGuest::new);
            case "example:ping" -> new Port(Set.of(LOG, NET), PingGuest::new);
            case "bench:host-calls" -> new Port(Set.of(LOG, NET), HostCallsGuest::new);
            default -> null;
        };
    }
}
