package dev.pumpkinmc.patch.core.runtime;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model.EntitySnapshot;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.model.Model.FrameOutput;
import dev.pumpkinmc.patch.core.model.Model.InitInfo;
import dev.pumpkinmc.patch.core.model.Model.InitResult;
import dev.pumpkinmc.patch.core.model.Model.PlayerSnapshot;
import dev.pumpkinmc.patch.core.model.Model.WorldRef;
import java.util.List;
import java.util.Set;

/**
 * The boundary between core and a Component Model runtime. There is no generic invoke and no Wasm
 * value model: a compile step, an instantiate step, and a hand-written mirror of the world's
 * exports.
 */
public final class Runtime {
    private Runtime() {}

    /** Compiles component bytes. Called from the catalog compile thread. */
    public interface ComponentRuntime extends AutoCloseable {
        CompiledComponent compile(ComponentSource source, RuntimeLimits limits) throws CompileException;

        /** A short description of the engine, for diagnostics and benchmark reports. */
        String describe();

        @Override
        default void close() {}
    }

    /** Component bytes plus the world the manifest declares. */
    public record ComponentSource(String modId, byte[] bytes, String sha256, String declaredWorld) {}

    public record RuntimeLimits(long callBudgetNanos, long initBudgetNanos, long memoryLimitBytes) {
        public static RuntimeLimits defaults() {
            return new RuntimeLimits(25_000_000L, 500_000_000L, 256L << 20);
        }
    }

    /** A validated, compiled component. Immutable and safe to instantiate many times. */
    public interface CompiledComponent {
        String world();

        Set<String> importedInterfaces();

        GuestInstance instantiate(HostImports imports) throws InstantiationFailure;
    }

    /** One live instance, confined to one thread. Closing releases its store. */
    public interface GuestInstance extends ComponentGuest, AutoCloseable {
        @Override
        void close();

        /**
         * Nanoseconds the binder spent converting between core records and wire types during the
         * last call, then resets. The Canonical ABI lowering inside the runtime is not included.
         */
        default long takeConversionNanos() {
            return 0;
        }

        /** The instance's linear memory in bytes, summed over its core modules, or -1 if unknown. */
        default long linearMemoryBytes() {
            return -1;
        }
    }

    /** Hand-written mirror of {@code pumpkin:client/guest} in core types. */
    public interface ComponentGuest {
        InitResult init(InitInfo info) throws GuestTrap, GuestInitError;

        /** Delivers {@code events}, then returns the HUD for {@code frame}. */
        FrameOutput update(List<Event> events, FrameInfo frame) throws GuestTrap;

        void shutdown() throws GuestTrap;
    }

    /** One core interface per WIT import interface. */
    public interface HostImports {
        LogImports log();

        NetImports net();

        ViewImports view();

        HudImports hud();
    }

    public interface LogImports {
        enum Level {
            TRACE,
            DEBUG,
            INFO,
            WARN,
            ERROR
        }

        void log(Level level, String message);
    }

    public interface NetImports {
        void send(String channel, byte[] payload) throws HostError;

        int maxPayloadBytes();
    }

    public interface ViewImports {
        PlayerSnapshot localPlayer() throws HostError;

        WorldRef currentWorld() throws HostError;

        List<EntitySnapshot> nearbyEntities(double radius, int max) throws HostError;
    }

    public interface HudImports {
        List<Integer> measureText(List<String> texts) throws HostError;
    }

    /** The kind of failure a runtime reports when a call does not return normally. */
    public enum TrapKind {
        UNREACHABLE,
        OUT_OF_BOUNDS,
        BUDGET_EXHAUSTED,
        MEMORY_LIMIT,
        HOST_PANIC,
        OTHER
    }

    public static final class GuestTrap extends Exception {
        private final TrapKind kind;

        public GuestTrap(TrapKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public TrapKind kind() {
            return kind;
        }
    }

    /** {@code init} returned {@code err(message)}. */
    public static final class GuestInitError extends Exception {
        public GuestInitError(String message) {
            super(message);
        }
    }

    public static final class CompileException extends Exception {
        public enum Reason {
            PARSE_ERROR,
            WORLD_MISMATCH,
            MIXED_VERSIONS,
            UNSUPPORTED_WORLD,
            UNSUPPORTED_IMPORT
        }

        private final Reason reason;

        public CompileException(Reason reason, String message, Throwable cause) {
            super(reason + ": " + message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    public static final class InstantiationFailure extends Exception {
        public InstantiationFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
