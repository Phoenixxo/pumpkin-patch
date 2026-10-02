package dev.pumpkinmc.patch.fabric.platform;

import dev.pumpkinmc.patch.core.diag.FaultRecord;
import dev.pumpkinmc.patch.core.model.Model;
import dev.pumpkinmc.patch.core.port.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** The Minecraft side of the core ports. Every method runs on the client thread. */
public final class FabricPorts {
    private FabricPorts() {}

    public static final class PlayerView implements Ports.PlayerView {
        @Override
        public Optional<Model.PlayerSnapshot> localPlayer(int epoch) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) {
                return Optional.empty();
            }
            return Optional.of(new Model.PlayerSnapshot(p.getUUID(), p.getName().getString(),
                    new Model.Vec3(p.getX(), p.getY(), p.getZ()), p.getYRot(), p.getXRot(), p.getHealth(),
                    new Model.WorldRef(dimension().orElse("unknown"), epoch)));
        }

        @Override
        public Optional<String> dimension() {
            var level = Minecraft.getInstance().level;
            return level == null ? Optional.empty() : Optional.of(level.dimension().identifier().toString());
        }

        @Override
        public List<Model.EntitySnapshot> entitiesNear(double radius, int max) {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return List.of();
            }
            List<Model.EntitySnapshot> out = new ArrayList<>();
            AABB box = mc.player.getBoundingBox().inflate(radius);
            for (Entity e : mc.level.getEntities(mc.player, box)) {
                if (out.size() >= max) {
                    break;
                }
                out.add(new Model.EntitySnapshot(e.getUUID(), e.getType().builtInRegistryHolder().key().identifier().toString(),
                        new Model.Vec3(e.getX(), e.getY(), e.getZ())));
            }
            return out;
        }
    }

    /** Draws into the graphics context of the HUD pass currently running. */
    public static final class HudCanvas implements Ports.HudCanvas {
        private final HudBatcher batcher = new HudBatcher();

        /** Whether HUD rectangles are batched, for the startup log. */
        public static boolean batching() {
            return HudBatcher.batching();
        }
        private GuiGraphicsExtractor graphics;

        /** Binds the frame's graphics for the HUD pass, or unbinds with {@code null} after it. */
        public void bind(GuiGraphicsExtractor g) {
            if (g != null) {
                batcher.beginFrame();
            }
            graphics = g;
        }

        @Override
        public void draw(List<Model.DrawCommand> commands) {
            var g = graphics;
            if (g == null) {
                return;
            }
            batcher.draw(g, Minecraft.getInstance().font, commands);
        }

        @Override
        public int measureText(String text) {
            return Minecraft.getInstance().font.width(text);
        }
    }

    public static final class Clock implements Ports.Clock {
        public long tick;

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long gameTick() {
            return tick;
        }
    }

    /** One toast per fault. The log line comes from core. */
    public static final class FaultReporter implements Ports.FaultReporter {
        @Override
        public void report(FaultRecord f) {
            var mc = Minecraft.getInstance();
            mc.execute(() -> SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(5000L),
                    Component.literal(f.modId() + " stopped"), Component.literal(f.kind() + ": " + shorten(f.message()))));
        }

        private static String shorten(String s) {
            return s.length() > 60 ? s.substring(0, 60) + "..." : s;
        }
    }
}
