package dev.pumpkinmc.patch.core.model;

/** An expected refusal from a host import. The binder turns it into the WIT {@code host-error}. */
public final class HostError extends Exception {
    public enum Code {
        DENIED,
        INVALID_ARGUMENT,
        UNAVAILABLE,
        STALE,
        LIMIT_EXCEEDED
    }

    private final Code code;
    private final String detail;

    public HostError(Code code, String detail) {
        super(code + (detail.isEmpty() ? "" : ": " + detail), null, false, false);
        this.code = code;
        this.detail = detail;
    }

    public static HostError of(Code code) {
        return new HostError(code, "");
    }

    public Code code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
