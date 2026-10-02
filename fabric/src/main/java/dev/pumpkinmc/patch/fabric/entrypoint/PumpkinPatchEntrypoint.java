package dev.pumpkinmc.patch.fabric.entrypoint;

import dev.pumpkinmc.patch.core.PatchConfig;
import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.catalog.CatalogEntry;
import dev.pumpkinmc.patch.core.component.ComponentInstance;
import dev.pumpkinmc.patch.core.diag.Perf;
import dev.pumpkinmc.patch.core.model.Model.FrameInfo;
import dev.pumpkinmc.patch.core.port.Ports;
import dev.pumpkinmc.patch.core.runtime.Runtime.ComponentRuntime;
import dev.pumpkinmc.patch.endive.EndiveComponentRuntime;
import dev.pumpkinmc.patch.fabric.bench.Autopilot;
import dev.pumpkinmc.patch.fabric.bench.PerfProbe;
import dev.pumpkinmc.patch.fabric.input.KeyBindingRegistrar;
import dev.pumpkinmc.patch.fabric.network.FabricMuxTransport;
import dev.pumpkinmc.patch.fabric.network.MuxPayload;
import dev.pumpkinmc.patch.fabric.platform.FabricPorts;
import dev.pumpkinmc.patch.fabric.platform.StatsOverlay;
import dev.pumpkinmc.patch.jvm.JavaComponentRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The composition root. The only place core, the runtimes, and Fabric meet. */
public final class PumpkinPatchEntrypoint implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PumpkinPatch");

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        var perf = new Perf();
        var probe = new PerfProbe(perf);
        var hudId = Identifier.fromNamespaceAndPath("pumpkin-patch", "components");

        if ("baseline".equals(System.getProperty("pumpkinpatch.mode"))) {
            // Measurement only: no host, no payload registration, no components.
            LOG.info("[PumpkinPatch] baseline mode: host not installed");
            var autopilot = Autopilot.fromProperties(null, KeyBindingRegistrar.register(List.of()), probe, gameDir);
            ClientTickEvents.START_CLIENT_TICK.register(mc -> probe.startTick());
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                probe.endTick();
                if (autopilot != null) {
                    autopilot.tick(mc);
                }
            });
            HudElementRegistry.addLast(hudId, (graphics, delta) -> probe.frame());
            return;
        }

        // "java" runs the Java ports of the samples: the benchmark control, not a sandbox.
        String engineName = System.getProperty("pumpkinpatch.engine", "compiler").toLowerCase(Locale.ROOT);
        // "redline" keeps the native code it compiles under the game directory, keyed by mod hash.
        ComponentRuntime runtime = engineName.equals("java")
                ? new JavaComponentRuntime()
                : new EndiveComponentRuntime(EndiveComponentRuntime.Engine.valueOf(engineName.toUpperCase(Locale.ROOT)),
                        true, gameDir.resolve("pumpkin-patch").resolve("redline-cache"));
        var transport = new FabricMuxTransport();
        var canvas = new FabricPorts.HudCanvas();
        var clock = new FabricPorts.Clock();
        var ports = new Ports(new FabricPorts.PlayerView(), canvas, transport, clock, new FabricPorts.FaultReporter());
        // Wasm updates run on workers, off the client thread. The Java control stays on the client
        // thread, as a Java mod would. -Dpumpkinpatch.workers overrides either.
        boolean workers = Boolean.parseBoolean(
                System.getProperty("pumpkinpatch.workers", String.valueOf(!engineName.equals("java"))));
        // Fabric constructs client entrypoints on the client (render) thread.
        var patch = PumpkinPatch.create(PatchConfig.defaults().withWorkers(workers), runtime, ports,
                Thread.currentThread());
        patch.perf().reset();

        Path mods = Path.of(System.getProperty("pumpkinpatch.mods", gameDir.resolve("pumpkin-mods").toString()));
        patch.discover(mods);
        var keys = KeyBindingRegistrar.register(patch.declaredActions());
        patch.compileInBackground();
        LOG.info("[PumpkinPatch] runtime {}, updates on {}, HUD rectangles {}, mods from {}", runtime.describe(),
                workers ? "worker threads" : "the client thread",
                FabricPorts.HudCanvas.batching() ? "batched" : "drawn one by one", mods);

        MuxPayload.register();
        FabricMuxTransport.registerReceivers(patch);
        patch.onRefuse(message -> LOG.error("[PumpkinPatch] server refused the join: {}", message));

        // One probe feeding the host's Perf, so a report holds client and host figures together.
        var hostProbe = new PerfProbe(patch.perf());
        var autopilot = Autopilot.fromProperties(patch, keys, hostProbe, gameDir);
        var overlay = new StatsOverlay(patch);

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            hostProbe.startTick();
            clock.tick++;
            keys.poll(patch);
            patch.tick(clock.tick);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            hostProbe.endTick();
            overlay.tick();
            if (autopilot != null) {
                autopilot.tick(mc);
            }
        });
        HudElementRegistry.addLast(hudId, (graphics, delta) -> {
            hostProbe.frame();
            canvas.bind(graphics);
            patch.renderHud(new FrameInfo(graphics.guiWidth(), graphics.guiHeight(), clock.tick));
            canvas.bind(null);
            overlay.draw(graphics);
        });

        ClientConfigurationConnectionEvents.START.register((listener, mc) -> {
            transport.configurationStarted();
            patch.onConfigurationStart();
        });
        ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
            transport.playStarted();
            patch.onJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
            if (mc.isSameThread()) {
                patch.onDisconnect();
            } else {
                mc.execute(patch::onDisconnect);
            }
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((mc, level) -> {
            if (level != null) {
                patch.enqueueWorldChanged(level.dimension().identifier().toString());
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            patch.onClientStopping();
            patch.close();
        });
        registerCommand(patch, overlay);
    }

    private static void registerCommand(PumpkinPatch patch, StatsOverlay overlay) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("pumpkinpatch")
                        .then(ClientCommands.literal("list").executes(c -> {
                            for (CatalogEntry e : patch.catalog().entries()) {
                                c.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
                                        "%s %s %s", e.id(), e.status(), e.reason())));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("stats").executes(c -> {
                            for (ComponentInstance i : patch.instances()) {
                                c.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
                                        "%s %s calls=%d guest=%.2f ms dropped=%d mem=%d KiB in=%d B out=%d B",
                                        i.id(), i.state(), i.calls, i.guestNanos / 1e6, i.eventsDropped,
                                        i.guest() == null ? 0 : i.guest().linearMemoryBytes() / 1024, i.bytesIn,
                                        i.bytesOut)));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("hud").executes(c -> {
                            c.getSource().sendFeedback(Component.literal(
                                    "Pumpkin Patch stats overlay " + (overlay.toggle() ? "on" : "off")));
                            return 1;
                        }))
                        .then(ClientCommands.literal("faults").executes(c -> {
                            patch.faults().forEach(f -> c.getSource().sendFeedback(Component.literal(
                                    f.modId() + " " + f.kind() + " (" + f.phase() + "): " + f.message())));
                            return 1;
                        }))));
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
