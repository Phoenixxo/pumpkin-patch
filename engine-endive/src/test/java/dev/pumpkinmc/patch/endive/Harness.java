package dev.pumpkinmc.patch.endive;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.catalog.Catalog;
import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.core.network.MuxCodec;
import dev.pumpkinmc.patch.core.network.MuxFrame;
import dev.pumpkinmc.patch.core.port.Ports;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.endive.EndiveComponentRuntime.Engine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the real core and the real Endive runtime against real components, with fake platform
 * ports standing in for Minecraft. The test thread plays the client thread.
 */
final class Harness implements AutoCloseable {
    static final Path GUESTS = Path.of(System.getProperty("guests.dir", "../guests/out"));

    final Path mods;
    final List<byte[]> sent = new ArrayList<>();
    final List<List<Model.DrawCommand>> drawn = new ArrayList<>();
    final List<FaultRecord> faults = new ArrayList<>();
    final List<Model.EntitySnapshot> entities = new ArrayList<>();
    final PumpkinPatch patch;
    long tick;

    Harness(Engine engine, PatchConfig config) throws IOException {
        this(new EndiveComponentRuntime(engine, true), config);
    }

    Harness(ComponentRuntime runtime, PatchConfig config) throws IOException {
        mods = Files.createTempDirectory("pumpkin-mods");
        var ports = new Ports(
                new Ports.PlayerView() {
                    @Override
                    public Optional<Model.PlayerSnapshot> localPlayer(int epoch) {
                        return Optional.of(new Model.PlayerSnapshot(UUID.randomUUID(), "Tester",
                                new Model.Vec3(1, 64, 2), 0, 0, 20, new Model.WorldRef("minecraft:overworld", epoch)));
                    }

                    @Override
                    public Optional<String> dimension() {
                        return Optional.of("minecraft:overworld");
                    }

                    @Override
                    public List<Model.EntitySnapshot> entitiesNear(double radius, int max) {
                        return entities.subList(0, Math.min(max, entities.size()));
                    }
                },
                new Ports.HudCanvas() {
                    @Override
                    public void draw(List<Model.DrawCommand> commands) {
                        drawn.add(commands);
                    }

                    @Override
                    public int measureText(String text) {
                        return text.length() * 6;
                    }
                },
                new Ports.Transport() {
                    @Override
                    public void sendFrame(byte[] frame) {
                        sent.add(frame);
                    }

                    @Override
                    public int maxOutboundFrameBytes() {
                        return 32_767;
                    }
                },
                new Ports.Clock() {
                    @Override
                    public long nanoTime() {
                        return System.nanoTime();
                    }

                    @Override
                    public long gameTick() {
                        return tick;
                    }
                },
                faults::add);
        patch = PumpkinPatch.create(config, runtime, ports, Thread.currentThread());
    }

    /** Installs a guest under {@code id} with a generated manifest. */
    Harness install(String id, String wasm, String activation, String caps, String extra) {
        return install(id, wasm, activation, caps, "", extra);
    }

    Harness install(String id, String wasm, String activation, String caps, String optional, String extra) {
        try {
            Path dir = mods.resolve(id.replace(':', '-'));
            Files.createDirectories(dir);
            byte[] bytes = Files.readAllBytes(GUESTS.resolve(wasm + ".wasm"));
            Files.write(dir.resolve("client.wasm"), bytes);
            Files.writeString(dir.resolve("pumpkin-mod.toml"), """
                    manifest-version = 1
                    [mod]
                    id = "%s"
                    name = "%s"
                    version = "0.1.0"
                    [client]
                    component = "client.wasm"
                    sha256 = "%s"
                    world = "pumpkin:client/client-mod@0.1.0"
                    activation = "%s"
                    [client.capabilities]
                    required = [%s]
                    optional = [%s]
                    %s
                    """.formatted(id, id, Catalog.sha256(bytes), activation, caps, optional, extra));
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void load() {
        patch.discover(mods);
        patch.compileNow();
    }

    /** Runs the configuration handshake for the given offered mods, then joins. */
    void joinPumpkin(MuxFrame.HelloMod... offered) throws Exception {
        patch.onConfigurationStart();
        patch.onFrameReceived(MuxCodec.encode(new MuxFrame.Hello(1, "test", List.of(offered))));
        var reply = (MuxFrame.Reply) MuxCodec.decode(sent.removeLast());
        List<MuxFrame.Route> routes = new ArrayList<>();
        int next = 1;
        for (MuxFrame.ReplyMod m : reply.mods()) {
            if (m.status() == MuxFrame.Status.AVAILABLE) {
                routes.add(new MuxFrame.Route(m.id(), next++));
            }
        }
        patch.onFrameReceived(MuxCodec.encode(new MuxFrame.Accept(true, "", routes)));
        patch.onJoin();
    }

    void tick() {
        patch.tick(++tick);
    }

    void frame() {
        patch.renderHud(new Model.FrameInfo(480, 270, tick));
    }

    @Override
    public void close() {
        patch.onClientStopping();
        patch.close();
    }
}
