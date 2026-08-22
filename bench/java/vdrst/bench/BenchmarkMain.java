package vdrst.bench;

import vdrst.align.*;
import vdrst.blast.BlastRunner;
import vdrst.index.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures the search pipeline stage by stage, so a claimed speedup can be attributed to
 * a specific change instead of asserted as one number.
 *
 * <pre>
 *   B0   no prefilter          v1's Smith-Waterman against every genome in the database
 *   B1   blastn subprocess     + v1's Smith-Waterman   (v1's pipeline, as shipped)
 *   B2   blastn subprocess     + affine-gap Gotoh
 *   B3   in-process k-mer      + affine-gap Gotoh
 *   B4   in-process k-mer      + banded Gotoh
 *   B5   in-process k-mer      + vectorised Gotoh      (v2, as shipped)
 * </pre>
 *
 * <p>B0 is the honest baseline: what "search the database with Smith-Waterman" costs when
 * nothing narrows the candidate set first. It is the number the prefilter has to beat and
 * the denominator v1 never wrote down. Each later stage changes exactly one thing from
 * the stage above it, so the difference between two rows has a single cause.
 *
 * <p>Every stage accumulates a checksum that is consumed at the end. Without it the JIT is
 * free to notice that nothing reads the scores and delete the work being timed.
 */
public final class BenchmarkMain {

    private static final int DEFAULT_WARMUP = 200;
    private static final int DEFAULT_ITERATIONS = 2000;
    /** B0 costs tens of seconds per query, so a handful of samples is all there is time for. */
    private static final int BASELINE_ITERATIONS = 3;

    private static long checksum;

    public static void main(String[] args) throws Exception {
        Path corpusDir = Paths.get(argument(args, "--corpus", "bench/corpus"));
        String database = argument(args, "--db", corpusDir.resolve("viruses.fasta").toString());
        int warmup = Integer.parseInt(argument(args, "--warmup", String.valueOf(DEFAULT_WARMUP)));
        int iterations = Integer.parseInt(argument(args, "--iterations", String.valueOf(DEFAULT_ITERATIONS)));
        boolean skipBaseline = List.of(args).contains("--skip-baseline");
        boolean skipBlast = List.of(args).contains("--skip-blast");

        List<String> queries = Files.readAllLines(corpusDir.resolve("queries.txt"));
        if (queries.isEmpty()) throw new IllegalStateException("no queries in " + corpusDir);
        byte[][] encoded = new byte[queries.size()][];
        for (int i = 0; i < queries.size(); i++) encoded[i] = Nucleotides.encode(queries.get(i));

        printEnvironment(database, queries);

        long t0 = System.nanoTime();
        GenomeStore store = GenomeStore.load(corpusDir.resolve("viruses.fasta"));
        long t1 = System.nanoTime();
        KmerIndex index = KmerIndex.build(store);
        long t2 = System.nanoTime();

        System.out.printf("  corpus       %,d genomes, %,d bases, read in %.2f s%n",
                store.count(), store.totalBases(), (t1 - t0) / 1e9);
        System.out.printf("  index        %,d positions, %,.0f MB, built in %.2f s%n%n",
                index.indexedPositions(), index.approximateBytes() / 1048576.0, (t2 - t1) / 1e9);

        List<Samples> results = new ArrayList<>();

        if (!skipBaseline) {
            results.add(measureBaseline(store, encoded));
        }

        if (!skipBlast) {
            try (Prefilter blast = new BlastPrefilter(new BlastRunner("blastn", database))) {
                results.add(measure("B1  blastn + v1 Smith-Waterman", blast,
                        new LegacySmithWaterman(ScoringScheme.legacyV1()), encoded, warmup / 20, iterations / 40));
                results.add(measure("B2  blastn + Gotoh affine", blast,
                        new GotohAligner(ScoringScheme.prefilter()), encoded, warmup / 20, iterations / 40));
            }
        }

        try (Prefilter kmer = new KmerPrefilter(index)) {
            results.add(measure("B3  k-mer index + Gotoh affine", kmer,
                    new GotohAligner(ScoringScheme.prefilter()), encoded, warmup, iterations));
            results.add(measure("B4  k-mer index + Gotoh banded", kmer,
                    new BandedGotohAligner(ScoringScheme.prefilter(), 64), encoded, warmup, iterations));
            results.add(measure("B5  k-mer index + Gotoh SIMD", kmer,
                    new VectorGotohAligner(ScoringScheme.prefilter()), encoded, warmup, iterations));

            printLatencyBudget(kmer, new VectorGotohAligner(ScoringScheme.prefilter()), encoded, warmup, iterations);
        }

        System.out.println("\n  results");
        System.out.println("  " + "-".repeat(100));
        for (Samples s : results) System.out.println("  " + s.describe());

        if (results.size() > 1) {
            Samples baseline = results.get(0);
            System.out.println("\n  speedup over " + baseline.label().trim() + " (median)");
            for (int i = 1; i < results.size(); i++) {
                Samples s = results.get(i);
                System.out.printf("    %-34s %9.1fx%n",
                        s.label().trim(), baseline.medianMillis() / s.medianMillis());
            }
        }

        if (checksum == Long.MIN_VALUE) System.out.println("unreachable " + checksum);
        System.out.println();
    }

    /** B0: v1's algorithm with no prefilter — the query against every genome. */
    private static Samples measureBaseline(GenomeStore store, byte[][] queries) {
        System.out.println("  B0  no prefilter, v1 Smith-Waterman over the whole database");
        System.out.printf("      %,d genomes, %,d bases per query — this is the one that takes seconds%n",
                store.count(), store.totalBases());

        Aligner aligner = new LegacySmithWaterman(ScoringScheme.legacyV1());
        long[] samples = new long[BASELINE_ITERATIONS];

        for (int i = 0; i < BASELINE_ITERATIONS; i++) {
            byte[] query = queries[i % queries.length];
            long started = System.nanoTime();
            checksum += scanEverything(aligner, store, query);
            samples[i] = System.nanoTime() - started;
            System.out.printf("      sample %d/%d   %.2f s%n", i + 1, BASELINE_ITERATIONS, samples[i] / 1e9);
        }
        return Samples.of("B0  no prefilter (v1 SW)", samples);
    }

    private static long scanEverything(Aligner aligner, GenomeStore store, byte[] query) {
        long best = 0;
        byte[] bases = store.bases();
        for (int g = 0; g < store.count(); g++) {
            byte[] genome = java.util.Arrays.copyOfRange(bases, store.start(g), store.start(g + 1));
            best = Math.max(best, aligner.score(query, genome));
        }
        return best;
    }

    private static Samples measure(String label, Prefilter prefilter, Aligner aligner,
                                   byte[][] queries, int warmup, int iterations) {
        warmup = Math.max(1, warmup);
        iterations = Math.max(3, iterations);

        for (int i = 0; i < warmup; i++) checksum += pipeline(prefilter, aligner, queries[i % queries.length]);

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            byte[] query = queries[i % queries.length];
            long started = System.nanoTime();
            checksum += pipeline(prefilter, aligner, query);
            samples[i] = System.nanoTime() - started;
        }

        System.out.println("  " + label + "   done (" + iterations + " samples)");
        return Samples.of(label, samples);
    }

    private static long pipeline(Prefilter prefilter, Aligner aligner, byte[] query) {
        long best = 0;
        for (Candidate candidate : prefilter.candidates(query, 20)) {
            best = Math.max(best, aligner.score(query, candidate.subjectBases()));
        }
        return best;
    }

    /**
     * Splits the pipeline into prefilter and alignment. Which half dominates decides what
     * is worth optimising next; getting this backwards is the most reliable way to spend a
     * week and move nothing.
     */
    private static void printLatencyBudget(Prefilter prefilter, Aligner aligner,
                                           byte[][] queries, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) checksum += pipeline(prefilter, aligner, queries[i % queries.length]);

        long[] prefilterNanos = new long[iterations];
        long[] alignNanos = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            byte[] query = queries[i % queries.length];

            long t0 = System.nanoTime();
            List<Candidate> candidates = prefilter.candidates(query, 20);
            long t1 = System.nanoTime();

            long best = 0;
            for (Candidate candidate : candidates) best = Math.max(best, aligner.score(query, candidate.subjectBases()));
            long t2 = System.nanoTime();

            prefilterNanos[i] = t1 - t0;
            alignNanos[i] = t2 - t1;
            checksum += best;
        }

        Samples pre = Samples.of("prefilter", prefilterNanos);
        Samples align = Samples.of("alignment", alignNanos);
        double total = pre.medianMillis() + align.medianMillis();

        System.out.println("\n  latency budget, v2 as shipped (median of " + iterations + ")");
        System.out.println("  " + "-".repeat(100));
        System.out.printf("    k-mer prefilter          %8.3f ms   %5.1f%%%n",
                pre.medianMillis(), 100 * pre.medianMillis() / total);
        System.out.printf("    alignment (%d candidates) %8.3f ms   %5.1f%%%n",
                20, align.medianMillis(), 100 * align.medianMillis() / total);
    }

    private static void printEnvironment(String database, List<String> queries) {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("\n  VDRST benchmark");
        System.out.println("  " + "=".repeat(100));
        System.out.printf("  jvm          %s %s%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.printf("  os           %s (%s)%n",
                System.getProperty("os.name"), System.getProperty("os.arch"));
        System.out.printf("  cpus         %d available to the JVM%n", runtime.availableProcessors());
        System.out.printf("  heap         %,d MB max%n", runtime.maxMemory() / (1024 * 1024));
        System.out.printf("  vectors      %s%n", VectorGotohAligner.speciesDescription());
        System.out.printf("  database     %s%n", database);
        System.out.printf("  queries      %d, %d bases each%n", queries.size(), queries.get(0).length());
    }

    private static String argument(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(flag)) return args[i + 1];
        return fallback;
    }
}
