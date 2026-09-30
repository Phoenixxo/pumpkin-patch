package dev.pumpkinmc.patch.endive;

import dev.pumpkinmc.patch.core.runtime.Runtime.GuestTrap;
import dev.pumpkinmc.patch.core.runtime.Runtime.TrapKind;
import run.endive.runtime.TrapException;
import run.endive.runtime.WasmInterruptedException;

/** The only class that knows the runtime's exception types. */
public final class TrapTranslator {
    private TrapTranslator() {}

    public static GuestTrap translate(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null
                && !(root instanceof TrapException)
                && !(root instanceof WasmInterruptedException)) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage());
        TrapKind kind;
        if (root instanceof WasmInterruptedException) {
            kind = TrapKind.BUDGET_EXHAUSTED;
        } else if (root instanceof TrapException) {
            String m = message.toLowerCase();
            if (m.contains("unreachable")) {
                kind = TrapKind.UNREACHABLE;
            } else if (m.contains("out of bounds")) {
                kind = TrapKind.OUT_OF_BOUNDS;
            } else {
                kind = TrapKind.OTHER;
            }
        } else if (message.contains("grow") && message.contains("memory")) {
            kind = TrapKind.MEMORY_LIMIT;
        } else {
            kind = TrapKind.HOST_PANIC;
            message = root.getClass().getSimpleName() + ": " + message;
        }
        return new GuestTrap(kind, message, t);
    }
}
