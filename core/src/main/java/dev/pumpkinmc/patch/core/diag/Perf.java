package dev.pumpkinmc.patch.core.diag;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named samples kept for benchmark reports. Timings are nanoseconds and are summarized in
 * microseconds. Values, such as bytes or counts, are summarized in their own unit.
 */
public final class Perf {
    private final Map<String, Samples> timings = new ConcurrentHashMap<>();
    private final Map<String, Samples> values = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** A duration in nanoseconds. */
    public void record(String name, long nanos) {
        if (enabled) {
            timings.computeIfAbsent(name, n -> new Samples()).add(nanos);
        }
    }

    /** A value in its own unit, such as bytes allocated or entities returned. */
    public void value(String name, long v) {
        if (enabled) {
            values.computeIfAbsent(name, n -> new Samples()).add(v);
        }
    }

    public void reset() {
        timings.clear();
        values.clear();
    }

    /** Timings, in microseconds. */
    public Map<String, Summary> summarize() {
        return summarize(timings, 1e3);
    }

    /** Values, in their own unit. */
    public Map<String, Summary> summarizeValues() {
        return summarize(values, 1);
    }

    private static Map<String, Summary> summarize(Map<String, Samples> series, double divisor) {
        Map<String, Summary> out = new LinkedHashMap<>();
        series.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().summary(divisor)));
        return out;
    }

    /** Both maps as a JSON object: {"timings_us": {...}, "values": {...}}. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"timings_us\":");
        appendJson(sb, summarize());
        sb.append(",\"values\":");
        appendJson(sb, summarizeValues());
        return sb.append('}').toString();
    }

    private static void appendJson(StringBuilder sb, Map<String, Summary> m) {
        sb.append('{');
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Summary s = e.getValue();
            sb.append('"').append(e.getKey().replace("\"", "'")).append("\":").append(String.format(Locale.ROOT,
                    "{\"n\":%d,\"mean\":%.3f,\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f,\"max\":%.3f,\"sum\":%.3f}",
                    s.count(), s.mean(), s.p50(), s.p95(), s.p99(), s.max(), s.sum()));
        }
        sb.append('}');
    }

    public record Summary(int count, double mean, double p50, double p95, double p99, double max, double sum) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "n=%d mean=%.1f p50=%.1f p95=%.1f p99=%.1f max=%.1f",
                    count, mean, p50, p95, p99, max);
        }
    }

    private static final class Samples {
        private static final int CAPACITY = 1 << 20;
        private long[] data = new long[1024];
        private int size;

        synchronized void add(long v) {
            if (size == data.length) {
                if (data.length >= CAPACITY) {
                    return;
                }
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        synchronized Summary summary(double divisor) {
            if (size == 0) {
                return new Summary(0, 0, 0, 0, 0, 0, 0);
            }
            long[] sorted = Arrays.copyOf(data, size);
            Arrays.sort(sorted);
            double sum = 0;
            for (long v : sorted) {
                sum += v;
            }
            return new Summary(size, sum / size / divisor, pct(sorted, 50) / divisor, pct(sorted, 95) / divisor,
                    pct(sorted, 99) / divisor, sorted[size - 1] / divisor, sum / divisor);
        }

        /** Nearest-rank percentile. */
        private static long pct(long[] sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            return sorted[Math.max(0, rank - 1)];
        }
    }
}
