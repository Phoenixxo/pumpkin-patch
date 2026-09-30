package dev.pumpkinmc.patch.core.manifest;

/** The subset of semantic versioning the handshake needs: {@code *}, {@code ^x.y[.z]}, {@code =x.y.z}. */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    public static SemVer parse(String text) {
        String core = text.split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        if (parts.length < 1 || parts.length > 3) {
            return null;
        }
        try {
            int[] v = new int[3];
            for (int i = 0; i < parts.length; i++) {
                v[i] = Integer.parseInt(parts[i]);
                if (v[i] < 0) {
                    return null;
                }
            }
            return new SemVer(v[0], v[1], v[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether this version satisfies {@code req}, using Cargo's caret rules for pre-1.0 versions. */
    public boolean satisfies(String req) {
        String r = req.strip();
        if (r.equals("*") || r.isEmpty()) {
            return true;
        }
        if (r.startsWith("=")) {
            SemVer exact = parse(r.substring(1).strip());
            return exact != null && exact.equals(this);
        }
        if (r.startsWith(">=")) {
            SemVer min = parse(r.substring(2).strip());
            return min != null && compareTo(min) >= 0;
        }
        SemVer min = parse(r.startsWith("^") ? r.substring(1).strip() : r);
        if (min == null || compareTo(min) < 0) {
            return false;
        }
        if (min.major > 0) {
            return major == min.major;
        }
        if (min.minor > 0) {
            return major == 0 && minor == min.minor;
        }
        return major == 0 && minor == 0 && patch == min.patch;
    }

    @Override
    public int compareTo(SemVer o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
