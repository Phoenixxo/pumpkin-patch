package dev.pumpkinmc.patch.fabric.platform;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.pumpkinmc.patch.core.model.Model.DrawCommand;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * Draws an instance's commands with fewer GUI elements than one per command.
 *
 * <p>Each vanilla {@code fill} makes its own render state, copying the pose and computing bounds,
 * and inserts it into the GUI tree. A radar draws hundreds of dots a frame. Here a run of consecutive
 * rectangles becomes one element that writes all its quads, built once per output and reused every
 * frame until the output changes. Text keeps the vanilla path.
 *
 * <p>The element is submitted through two private fields of {@link GuiGraphicsExtractor}. If they
 * cannot be reached, rectangles fall back to {@code fill}.
 */
final class HudBatcher {
    private static final VarHandle GUI_RENDER_STATE;
    private static final VarHandle SCISSOR_STACK;
    private static final MethodHandle SCISSOR_PEEK;

    static {
        VarHandle state = null;
        VarHandle scissor = null;
        MethodHandle peek = null;
        try {
            var lookup = MethodHandles.privateLookupIn(GuiGraphicsExtractor.class, MethodHandles.lookup());
            state = lookup.findVarHandle(GuiGraphicsExtractor.class, "guiRenderState", GuiRenderState.class);
            Class<?> stack = Class.forName(
                    GuiGraphicsExtractor.class.getName() + "$ScissorStack", false,
                    GuiGraphicsExtractor.class.getClassLoader());
            scissor = lookup.findVarHandle(GuiGraphicsExtractor.class, "scissorStack", stack);
            peek = MethodHandles.privateLookupIn(stack, MethodHandles.lookup())
                    .findVirtual(stack, "peek", MethodType.methodType(ScreenRectangle.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            state = null;
        }
        GUI_RENDER_STATE = state;
        SCISSOR_STACK = scissor;
        SCISSOR_PEEK = peek;
    }

    /** Whether rectangle runs are drawn as one element, rather than falling back to {@code fill}. */
    static boolean batching() {
        return GUI_RENDER_STATE != null;
    }

    /** One step of a prepared output: a run of rectangles, or one text. */
    private sealed interface Op permits RectRun, TextOp {}

    /** Rectangles as x0, y0, x1, y1 quadruples with one colour each, and the area they cover. */
    private record RectRun(int[] coords, int[] colors, int minX, int minY, int maxX, int maxY) implements Op {}

    private record TextOp(DrawCommand.Text text) implements Op {}

    /** Prepared outputs by the list they were prepared from. Lists not drawn last frame are dropped. */
    private Map<List<DrawCommand>, List<Op>> prepared = new IdentityHashMap<>();
    private Map<List<DrawCommand>, List<Op>> drawnThisFrame = new IdentityHashMap<>();

    /** Starts a frame. Outputs not drawn in the previous frame are forgotten. */
    void beginFrame() {
        prepared = drawnThisFrame;
        drawnThisFrame = new IdentityHashMap<>();
    }

    void draw(GuiGraphicsExtractor g, Font font, List<DrawCommand> commands) {
        List<Op> ops = prepared.get(commands);
        if (ops == null) {
            ops = prepare(commands);
        }
        drawnThisFrame.put(commands, ops);
        GuiRenderState state = GUI_RENDER_STATE != null ? (GuiRenderState) GUI_RENDER_STATE.get(g) : null;
        for (Op op : ops) {
            switch (op) {
                case TextOp t -> g.text(font, t.text().text(), t.text().x(), t.text().y(), t.text().argb(),
                        t.text().shadow());
                case RectRun r -> {
                    if (state != null) {
                        state.addGuiElement(new RectRunState(r, new Matrix3x2f(g.pose()), scissor(g)));
                    } else {
                        for (int i = 0; i < r.colors().length; i++) {
                            int[] c = r.coords();
                            g.fill(c[4 * i], c[4 * i + 1], c[4 * i + 2], c[4 * i + 3], r.colors()[i]);
                        }
                    }
                }
            }
        }
    }

    private static ScreenRectangle scissor(GuiGraphicsExtractor g) {
        try {
            return (ScreenRectangle) SCISSOR_PEEK.invoke(SCISSOR_STACK.get(g));
        } catch (Throwable e) {
            return null;
        }
    }

    /** Groups consecutive rectangles. Coordinates are ordered the way vanilla {@code fill} orders them. */
    private static List<Op> prepare(List<DrawCommand> commands) {
        List<Op> ops = new ArrayList<>();
        int i = 0;
        while (i < commands.size()) {
            if (commands.get(i) instanceof DrawCommand.Text t) {
                ops.add(new TextOp(t));
                i++;
                continue;
            }
            int start = i;
            while (i < commands.size() && commands.get(i) instanceof DrawCommand.FillRect) {
                i++;
            }
            int n = i - start;
            int[] coords = new int[4 * n];
            int[] colors = new int[n];
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (int k = 0; k < n; k++) {
                var r = (DrawCommand.FillRect) commands.get(start + k);
                int x0 = Math.min(r.x(), r.x() + r.w());
                int x1 = Math.max(r.x(), r.x() + r.w());
                int y0 = Math.min(r.y(), r.y() + r.h());
                int y1 = Math.max(r.y(), r.y() + r.h());
                coords[4 * k] = x0;
                coords[4 * k + 1] = y0;
                coords[4 * k + 2] = x1;
                coords[4 * k + 3] = y1;
                colors[k] = r.argb();
                minX = Math.min(minX, x0);
                minY = Math.min(minY, y0);
                maxX = Math.max(maxX, x1);
                maxY = Math.max(maxY, y1);
            }
            ops.add(new RectRun(coords, colors, minX, minY, maxX, maxY));
        }
        return ops;
    }

    /** One GUI element drawing a whole run of rectangles, with the vertices vanilla writes for each. */
    private static final class RectRunState implements GuiElementRenderState {
        private final RectRun run;
        private final Matrix3x2fc pose;
        private final ScreenRectangle scissor;
        private final ScreenRectangle bounds;

        RectRunState(RectRun run, Matrix3x2fc pose, ScreenRectangle scissor) {
            this.run = run;
            this.pose = pose;
            this.scissor = scissor;
            var area = new ScreenRectangle(run.minX(), run.minY(), run.maxX() - run.minX(), run.maxY() - run.minY())
                    .transformMaxBounds(pose);
            this.bounds = scissor != null ? scissor.intersection(area) : area;
        }

        @Override
        public void buildVertices(VertexConsumer vertices) {
            int[] c = run.coords();
            int[] colors = run.colors();
            for (int i = 0; i < colors.length; i++) {
                float x0 = c[4 * i];
                float y0 = c[4 * i + 1];
                float x1 = c[4 * i + 2];
                float y1 = c[4 * i + 3];
                int color = colors[i];
                vertices.addVertexWith2DPose(pose, x0, y0).setColor(color);
                vertices.addVertexWith2DPose(pose, x0, y1).setColor(color);
                vertices.addVertexWith2DPose(pose, x1, y1).setColor(color);
                vertices.addVertexWith2DPose(pose, x1, y0).setColor(color);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return scissor;
        }

        @Override
        public ScreenRectangle bounds() {
            return bounds;
        }
    }
}
