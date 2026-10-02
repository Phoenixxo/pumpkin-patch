package dev.pumpkinmc.patch.endive.binder.v0_1;

import dev.pumpkinmc.patch.core.model.Event;
import dev.pumpkinmc.patch.core.model.HostError;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInitError;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestInstance;
import dev.pumpkinmc.patch.core.runtime.Runtime.GuestTrap;
import dev.pumpkinmc.patch.core.runtime.Runtime.HostImports;
import dev.pumpkinmc.patch.core.runtime.Runtime.LogImports;
import dev.pumpkinmc.patch.endive.TrapTranslator;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.Guest;
import dev.pumpkinmc.patch.endive.binder.v0_1.exports.pumpkin.client.guest.GuestResult30Exception;
import dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.log.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import run.endive.cm.runtime.ComponentStore;
import run.endive.cm.types.WasmComponent;

/** Adapts the generated {@code pumpkin:client@0.1} bindings to the core runtime interface. */
public final class V0_1Binder {
    public static final V0_1Binder INSTANCE = new V0_1Binder();

    private V0_1Binder() {}

    public String minorVersion() {
        return "0.1";
    }

    public GuestInstance instantiate(
            ComponentStore store, WasmComponent component, HostImports core, LongSupplier memoryBytes) {
        ClientModWorld world = ClientModWorld.instantiate(store, component, imports(core));
        return new V0_1Guest(world.guest(), memoryBytes);
    }

    private static ClientModWorld.Imports imports(HostImports core) {
        var log = (dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.log.Host)
                (level, message) -> core.log().log(convert(level), message);

        var net = new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.net.Host() {
            @Override
            public void send(String channel, byte[] payload) {
                try {
                    core.net().send(channel, payload);
                } catch (HostError e) {
                    throw new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.net.HostErrorException(
                            V0_1Convert.error(e));
                }
            }

            @Override
            public Long maxPayload() {
                return (long) core.net().maxPayloadBytes();
            }
        };

        var view = new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.view.Host() {
            private final EntityKinds kinds = new EntityKinds();

            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.PlayerSnapshot localPlayer() {
                try {
                    return V0_1Convert.player(core.view().localPlayer());
                } catch (HostError e) {
                    throw viewError(e);
                }
            }

            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.WorldRef currentWorld() {
                try {
                    return V0_1Convert.world(core.view().currentWorld());
                } catch (HostError e) {
                    throw viewError(e);
                }
            }

            @Override
            public List<dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.EntitySnapshot> nearbyEntities(
                    Double radius, Long max) {
                try {
                    int clamped = (int) Math.min(max, Integer.MAX_VALUE);
                    List<dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.model.EntitySnapshot> out =
                            new ArrayList<>();
                    for (Model.EntitySnapshot e : core.view().nearbyEntities(radius, clamped)) {
                        out.add(V0_1Convert.entity(e, kinds.id(e.kind())));
                    }
                    return out;
                } catch (HostError e) {
                    throw viewError(e);
                }
            }

            @Override
            public String entityKindName(Long kind) {
                return kinds.name(kind);
            }
        };

        var hud = (dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.hud.Host) texts -> {
            try {
                return core.hud().measureText(texts).stream()
                        .map(Integer::longValue)
                        .toList();
            } catch (HostError e) {
                throw new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.hud.HostErrorException(
                        V0_1Convert.error(e));
            }
        };

        return new ClientModWorld.Imports() {
            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.log.Host log() {
                return log;
            }

            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.view.Host view() {
                return view;
            }

            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.net.Host net() {
                return net;
            }

            @Override
            public dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.hud.Host hud() {
                return hud;
            }
        };
    }

    private static RuntimeException viewError(HostError e) {
        return new dev.pumpkinmc.patch.endive.binder.v0_1.pumpkin.client.view.HostErrorException(
                V0_1Convert.error(e));
    }

    private static LogImports.Level convert(Level level) {
        return switch (level) {
            case TRACE -> LogImports.Level.TRACE;
            case DEBUG -> LogImports.Level.DEBUG;
            case INFO -> LogImports.Level.INFO;
            case WARN -> LogImports.Level.WARN;
            case ERROR -> LogImports.Level.ERROR;
        };
    }

    /** The generated exports as a core {@link GuestInstance}. */
    private static final class V0_1Guest implements GuestInstance {
        private final Guest guest;
        private final LongSupplier memoryBytes;
        private long conversionNanos;

        V0_1Guest(Guest guest, LongSupplier memoryBytes) {
            this.guest = guest;
            this.memoryBytes = memoryBytes;
        }

        /** Times one conversion, so the report can separate it from the whole call. */
        private <T> T timed(java.util.function.Supplier<T> conversion) {
            long t = System.nanoTime();
            T out = conversion.get();
            conversionNanos += System.nanoTime() - t;
            return out;
        }

        @Override
        public Model.InitResult init(Model.InitInfo info) throws GuestTrap, GuestInitError {
            try {
                var in = timed(() -> V0_1Convert.initInfo(info));
                var out = guest.init(in);
                return timed(() -> V0_1Convert.initResult(out));
            } catch (GuestResult30Exception e) {
                throw new GuestInitError(e.getMessage());
            } catch (RuntimeException | StackOverflowError e) {
                throw TrapTranslator.translate(e);
            }
        }

        @Override
        public Model.FrameOutput update(List<Event> events, Model.FrameInfo frame) throws GuestTrap {
            try {
                var out = guest.update(
                        timed(() -> V0_1Convert.events(events)), timed(() -> V0_1Convert.frameInfo(frame)));
                return timed(() -> V0_1Convert.frameOutput(out));
            } catch (RuntimeException | StackOverflowError e) {
                throw TrapTranslator.translate(e);
            }
        }

        @Override
        public void shutdown() throws GuestTrap {
            try {
                guest.shutdown();
            } catch (RuntimeException | StackOverflowError e) {
                throw TrapTranslator.translate(e);
            }
        }

        @Override
        public long takeConversionNanos() {
            long n = conversionNanos;
            conversionNanos = 0;
            return n;
        }

        @Override
        public long linearMemoryBytes() {
            return memoryBytes.getAsLong();
        }

        @Override
        public void close() {
            // The store holds no native resources. Dropping the last reference releases it.
        }
    }
}
