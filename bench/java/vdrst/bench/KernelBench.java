package vdrst.bench;

import vdrst.align.*;

import java.util.SplittableRandom;

/**
 * Times the alignment kernels alone, one call at a time, at the exact shape the pipeline
 * hands them: a 300-base query against the 428-base window the prefilter cuts.
 *
 * <p>The per-kernel table in README.md comes from here. It exists as a class in the
 * repository, rather than a harness on somebody's machine, for the same reason the staged
 * benchmark does: a number nobody can regenerate is an anecdote with digits in it.
 *
 * <p>Each score feeds a checksum that is consumed at the end, so the JIT cannot notice
 * the results are unused and delete the work being timed.
 */
public final class KernelBench {

    private static final int QUERY = 300;
    private static final int WINDOW = QUERY + 2 * 64;        // what KmerPrefilter produces
    private static final int WARMUP = 1_500;
    private static final int SAMPLES = 1_500;

    private static long checksum;

    public static void main(String[] args) {
        SplittableRandom rng = new SplittableRandom(0x5EEDC0FFEEL);
        byte[] query = sequence(rng, QUERY);
        byte[][] windows = new byte[64][];
        for (int i = 0; i < windows.length; i++) windows[i] = sequence(rng, WINDOW);

        Aligner[] kernels = {
                new LegacySmithWaterman(ScoringScheme.legacyV1()),
                new GotohAligner(ScoringScheme.prefilter()),
                new BandedGotohAligner(ScoringScheme.prefilter(), 64),
                new VectorGotohAligner(ScoringScheme.prefilter()),
                new ShortGotohAligner(ScoringScheme.prefilter()),
        };

        System.out.printf("%n  alignment kernels, %d x %d, %d samples each after %d warmup%n",
                QUERY, WINDOW, SAMPLES, WARMUP);
        System.out.println("  " + "-".repeat(88));

        for (Aligner kernel : kernels) {
            for (int i = 0; i < WARMUP; i++) checksum += kernel.score(query, windows[i % windows.length]);

            long[] nanos = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                byte[] window = windows[i % windows.length];
                long started = System.nanoTime();
                checksum += kernel.score(query, window);
                nanos[i] = System.nanoTime() - started;
            }

            Samples samples = Samples.of(kernel.id(), nanos);
            double throughput = (double) QUERY * WINDOW / (samples.medianMillis() * 1e3);
            System.out.printf("  %-24s p50=%8.2f us   p99=%8.2f us   %6.0f Mcells/s%n",
                    kernel.id(), samples.medianMillis() * 1e3,
                    samples.percentileMillis(99) * 1e3, throughput);
        }

        if (checksum == Long.MIN_VALUE) System.out.println("unreachable " + checksum);
        System.out.println();
    }

    private static byte[] sequence(SplittableRandom rng, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) rng.nextInt(4);
        return out;
    }
}
