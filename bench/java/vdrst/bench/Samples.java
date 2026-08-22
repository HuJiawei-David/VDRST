package vdrst.bench;

import java.util.Arrays;

/**
 * A set of latency measurements, reported as percentiles.
 *
 * <p>Averages are reported nowhere in this project on purpose. A mean hides exactly the
 * behaviour that matters when a service is under load: the mean of a run that is fast
 * 95% of the time and pathological the rest looks fine. v1's "48 seconds to under 500
 * milliseconds" was a pair of single observations with no distribution behind them and
 * no record of how they were taken, which is why it could never be re-checked.
 */
public final class Samples {

    private final String label;
    private final long[] nanos;

    private Samples(String label, long[] nanos) {
        this.label = label;
        this.nanos = nanos;
        Arrays.sort(this.nanos);
    }

    public static Samples of(String label, long[] nanos) {
        if (nanos.length == 0) throw new IllegalArgumentException("no samples for " + label);
        return new Samples(label, nanos.clone());
    }

    public String label() { return label; }

    public int count() { return nanos.length; }

    /**
     * Nearest-rank percentile. With few samples a p99 is not meaningfully different from
     * the maximum, and {@link #reportedPercentiles()} reflects that rather than printing
     * a number that implies more resolution than the data supports.
     */
    public double percentileMillis(double percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * nanos.length) - 1;
        return nanos[Math.max(0, Math.min(nanos.length - 1, rank))] / 1e6;
    }

    public double minMillis() { return nanos[0] / 1e6; }

    public double maxMillis() { return nanos[nanos.length - 1] / 1e6; }

    public double medianMillis() { return percentileMillis(50); }

    /** Percentiles this sample count can actually support, coarsest first. */
    public int[] reportedPercentiles() {
        if (nanos.length >= 1000) return new int[]{50, 90, 99, 999};
        if (nanos.length >= 100) return new int[]{50, 90, 99};
        if (nanos.length >= 20) return new int[]{50, 90};
        return new int[]{50};
    }

    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-28s n=%-5d min=%8.2f", label, nanos.length, minMillis()));
        for (int p : reportedPercentiles()) {
            String name = p == 999 ? "p99.9" : "p" + p;
            out.append(String.format("  %s=%8.2f", name, percentileMillis(p == 999 ? 99.9 : p)));
        }
        out.append(String.format("  max=%8.2f ms", maxMillis()));
        return out.toString();
    }
}
