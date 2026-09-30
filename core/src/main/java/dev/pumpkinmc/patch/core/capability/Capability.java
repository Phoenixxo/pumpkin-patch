package dev.pumpkinmc.patch.core.capability;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A grant to use one host interface. */
public enum Capability {
    LOG,
    VIEW,
    NET,
    HUD,
    INPUT;

    public static Optional<Capability> parse(String name) {
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Capabilities implied by the interfaces a component imports. */
    public static Set<Capability> impliedBy(Set<String> importedInterfaces) {
        Set<Capability> out = EnumSet.noneOf(Capability.class);
        for (String id : importedInterfaces) {
            if (id.startsWith("pumpkin:client/view@")) {
                out.add(VIEW);
            } else if (id.startsWith("pumpkin:client/net@")) {
                out.add(NET);
            } else if (id.startsWith("pumpkin:client/hud@")) {
                out.add(HUD);
            }
        }
        return out;
    }
}
