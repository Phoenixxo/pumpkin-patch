package dev.pumpkinmc.patch.endive.binder.v0_1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Numbers entity type names for one component instance, so an entity snapshot carries a {@code u32}
 * instead of a string. Ids start at zero and never change meaning for the instance.
 */
final class EntityKinds {
    private final Map<String, Long> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    long id(String name) {
        Long id = ids.get(name);
        if (id == null) {
            id = (long) names.size();
            ids.put(name, id);
            names.add(name);
        }
        return id;
    }

    /** The name behind {@code id}, or {@code null} for an id this instance was never given. */
    String name(long id) {
        return id >= 0 && id < names.size() ? names.get((int) id) : null;
    }
}
