package dev.pumpkinmc.patch.fabric.input;

import com.mojang.blaze3d.platform.InputConstants;
import dev.pumpkinmc.patch.core.PumpkinPatch;
import dev.pumpkinmc.patch.core.PumpkinPatch.DeclaredAction;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Registers one key mapping per manifest action and turns key transitions into action events. */
public final class KeyBindingRegistrar {
    public record Bound(DeclaredAction action, KeyMapping mapping) {}

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("pumpkin-patch", "components"));

    private final List<Bound> bound = new ArrayList<>();
    private final boolean[] down;

    private KeyBindingRegistrar(List<DeclaredAction> actions) {
        for (DeclaredAction a : actions) {
            int key = InputConstants.UNKNOWN.getValue();
            if (!a.action().defaultKey().isEmpty()) {
                try {
                    key = InputConstants.getKey(a.action().defaultKey()).getValue();
                } catch (IllegalArgumentException ignored) {
                    // An unknown key name leaves the action unbound. The player can bind it.
                }
            }
            String name = "key.pumpkin-patch." + a.modId().replace(':', '.') + "." + a.action().id();
            var mapping = KeyMappingHelper.registerKeyMapping(
                    new KeyMapping(name, InputConstants.Type.KEYBOARD, key, CATEGORY));
            bound.add(new Bound(a, mapping));
        }
        down = new boolean[bound.size()];
    }

    /** Called during client initialization, which is when Fabric expects key mappings. */
    public static KeyBindingRegistrar register(List<DeclaredAction> actions) {
        return new KeyBindingRegistrar(actions);
    }

    public List<Bound> bound() {
        return bound;
    }

    /** At the start of each tick, enqueue pressed and released transitions. */
    public void poll(PumpkinPatch patch) {
        for (int i = 0; i < bound.size(); i++) {
            Bound b = bound.get(i);
            boolean clicked = false;
            while (b.mapping().consumeClick()) {
                clicked = true;
            }
            boolean isDown = b.mapping().isDown();
            if (clicked || (isDown && !down[i])) {
                patch.enqueueAction(b.action().modId(), b.action().action().id(), true);
            }
            if (!isDown && down[i]) {
                patch.enqueueAction(b.action().modId(), b.action().action().id(), false);
            }
            down[i] = isDown;
        }
    }
}
