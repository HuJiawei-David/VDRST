package vdrst.bench;

import vdrst.align.*;
import vdrst.blast.BlastHit;
import vdrst.blast.BlastRunner;
import vdrst.blast.Hsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures the search pipeline stage by stage, so that a claimed speedup can be
 * attributed to a specific change rather than asserted as a single number.
 *
 * <pre>
 *   B0  no prefilter, v1's Smith-Waterman over every genome in the database
 *   B1  BLAST prefilter, then v1's Smith-Waterman over the concatenated HSPs
 *   B2  BLAST prefilter, then affine-gap Gotoh per HSP
 * </pre>
 *
 * <p>B0 is the honest baseline: it is what "search the database with Smith-Waterman"
 * costs when nothing narrows the candidate set first. It is the number the prefilter
 * has to beat, and it is the one v1 never wrote down.
 *
 * <p>Every stage returns a checksum that is accumulated and printed. Without it the JIT
 * is entitled to notice that nothing reads the alignment scores and delete the work
 * being timed.
 */
public final class BenchmarkMain {

    private static final int DEFAULT_WARMUP = 3;
    private static final int DEFAULT_ITERATIONS = 20;
    /** B0 costs tens of seconds per query; a handful of samples is all that is affordable. */
    private static final int BASELINE_ITERATIONS = 3;

    public static void main(String[] args) throws Exception {
        Path corpusDir = Paths.get(argument(args, "--corpus", "bench/corpus"));
        String database = argument(args, "--db", corpusDir.resolve("viruses.fasta").toString());
        int warmup = Integer.parseInt(argument(args, "--warmup", String.valueOf(DEFAULT_WARMUP)));
        int iterations = Integer.parseInt(argument(args, "--iterations", String.valueOf(DEFAULT_ITERATIONS)));
        boolean skipBaseline = List.of(args).contains("--skip-baseline");

        List<String> queries = Files.readAllLines(corpusDir.resolve("queries.txt"));
        if (queries.isEmpty()) throw new IllegalStateException("no queries in " + corpusDir);

        printEnvironment(database, queries);

        Corpus corpus = null;
        if (!skipBaseline) {
            long started = System.nanoTime();
            corpus = Corpus.load(corpusDir.resolve("viruses.fasta"));
            System.out.printf("  corpus       %,d genomes, %,d bases, loaded in %.1f s%n",
                    corpus.size(), corpus.totalBases(), (System.nanoTime() - started) / 1e9);
        }
        System.out.println();

        List<Samples> results = new ArrayList<>();

        try (BlastRunner runner = new BlastRunner("blastn", database)) {
            runner.verifyConfiguration();

            if (corpus != null) {
                results.add(measureBaseline(corpus, queries, warmup > 0 ? 1 : 0, BASELINE_ITERATIONS));
            }
            results.add(measurePipeline("B1  prefilter + v1 Smith-Waterman",
                    runner, queries, warmup, iterations, Mode.LEGACY));
            results.add(measurePipeline("B2  prefilter + Gotoh affine",
                    runner, queries, warmup, iterations, Mode.GOTOH));

            printLatencyBudget(runner, queries, warmup, iterations);
        }

        System.out.println("\n  results");
        System.out.println("  " + "-".repeat(96));
        for (Samples s : results) System.out.println("  " + s.describe());

        if (results.size() > 1) {
            Samples baseline = results.get(0);
            System.out.println("\n  speedup vs " + baseline.label().trim().split(" ")[0] + " (median)");
            for (int i = 1; i < results.size(); i++) {
                Samples s = results.get(i);
                System.out.printf("    %-28s %.1fx%n",
                        s.label().trim(), baseline.medianMillis() / s.medianMillis());
            }
        }
        System.out.println();
    }

    private enum Mode { LEGACY, GOTOH }

    /**
     * B0: what v1's algorithm costs with no prefilter at all — the query aligned against
     * every genome in the database.
     */
    private static Samples measureBaseline(Corpus corpus, List<String> queries, int warmup, int iterations) {
        System.out.println("  B0  no prefilter, v1 Smith-Waterman over the whole database");
        System.out.printf("      %,d genomes x %,d bases per query — this is the slow one%n",
                corpus.size(), corpus.totalBases());

        Aligner aligner = new LegacySmithWaterman(ScoringScheme.legacyV1());
        long checksum = 0;

        for (int i = 0; i < warmup; i++) {
            checksum += scanEverything(aligner, corpus, queries.get(i % queries.size()));
            System.out.println("      warmup " + (i + 1) + "/" + warmup + " complete");
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            byte[] query = Nucleotides.encode(queries.get(i % queries.size()));
            long started = System.nanoTime();
            checksum += scanEverythingEncoded(aligner, corpus, query);
            samples[i] = System.nanoTime() - started;
            System.out.printf("      sample %d/%d  %.1f s%n", i + 1, iterations, samples[i] / 1e9);
        }

        consume(checksum);
        return Samples.of("B0  no prefilter (v1 SW)", samples);
    }

    private static long scanEverything(Aligner aligner, Corpus corpus, String query) {
        return scanEverythingEncoded(aligner, corpus, Nucleotides.encode(query));
    }

    private static long scanEverythingEncoded(Aligner aligner, Corpus corpus, byte[] query) {
        long best = 0;
        for (Corpus.Genome genome : corpus.genomes()) {
            best = Math.max(best, aligner.score(query, genome.bases()));
        }
        return best;
    }

    /** B1 and B2: prefilter, then re-rank the candidates it returned. */
    private static Samples measurePipeline(String label, BlastRunner runner, List<String> queries,
                                           int warmup, int iterations, Mode mode) {
        Aligner aligner = mode == Mode.LEGACY
                ? new LegacySmithWaterman(ScoringScheme.legacyV1())
                : new GotohAligner(ScoringScheme.prefilter());

        long checksum = 0;
        for (int i = 0; i < warmup; i++) {
            checksum += runPipeline(runner, aligner, queries.get(i % queries.size()), mode);
        }

        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            String query = queries.get(i % queries.size());
            long started = System.nanoTime();
            checksum += runPipeline(runner, aligner, query, mode);
            samples[i] = System.nanoTime() - started;
        }

        consume(checksum);
        System.out.println("  " + label + "  done (" + iterations + " samples)");
        return Samples.of(label, samples);
    }

    private static long runPipeline(BlastRunner runner, Aligner aligner, String query, Mode mode) {
        long best = 0;
        for (BlastHit hit : runner.search(query)) {
            if (mode == Mode.LEGACY) {
                // v1 concatenated every HSP fragment and aligned the concatenations.
                StringBuilder q = new StringBuilder(), s = new StringBuilder();
                for (Hsp hsp : hit.hsps()) {
                    q.append(hsp.queryAligned().replace("-", ""));
                    s.append(hsp.subjectAligned().replace("-", ""));
                }
                if (q.length() > 0 && s.length() > 0) {
                    best = Math.max(best, aligner.score(
                            Nucleotides.encode(q.toString()), Nucleotides.encode(s.toString())));
                }
            } else {
                for (Hsp hsp : hit.hsps()) {
                    best = Math.max(best, aligner.score(
                            Nucleotides.encode(hsp.queryUngapped()),
                            Nucleotides.encode(hsp.subjectUngapped())));
                }
            }
        }
        return best;
    }

    /**
     * Splits the prefiltered pipeline into the subprocess and the alignment, because
     * knowing which half dominates decides what is worth optimising next. Optimising the
     * half that is already cheap is the most common way to spend a week on nothing.
     */
    private static void printLatencyBudget(BlastRunner runner, List<String> queries,
                                           int warmup, int iterations) {
        Aligner aligner = new GotohAligner(ScoringScheme.prefilter());
        long checksum = 0;

        for (int i = 0; i < warmup; i++) checksum += runPipeline(runner, aligner, queries.get(i % queries.size()), Mode.GOTOH);

        long[] blastNanos = new long[iterations];
        long[] alignNanos = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            String query = queries.get(i % queries.size());

            long t0 = System.nanoTime();
            List<BlastHit> hits = runner.search(query);
            long t1 = System.nanoTime();

            long best = 0;
            for (BlastHit hit : hits) {
                for (Hsp hsp : hit.hsps()) {
                    best = Math.max(best, aligner.score(
                            Nucleotides.encode(hsp.queryUngapped()),
                            Nucleotides.encode(hsp.subjectUngapped())));
                }
            }
            long t2 = System.nanoTime();

            blastNanos[i] = t1 - t0;
            alignNanos[i] = t2 - t1;
            checksum += best;
        }

        consume(checksum);

        Samples blast = Samples.of("  blast subprocess", blastNanos);
        Samples align = Samples.of("  smith-waterman re-rank", alignNanos);
        double total = blast.medianMillis() + align.medianMillis();

        System.out.println("\n  latency budget (median of " + iterations + ")");
        System.out.println("  " + "-".repeat(96));
        System.out.printf("    blast subprocess        %8.2f ms   %5.1f%%%n",
                blast.medianMillis(), 100 * blast.medianMillis() / total);
        System.out.printf("    smith-waterman re-rank  %8.2f ms   %5.1f%%%n",
                align.medianMillis(), 100 * align.medianMillis() / total);
    }

    private static void printEnvironment(String database, List<String> queries) {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("\n  VDRST benchmark");
        System.out.println("  " + "=".repeat(96));
        System.out.printf("  jvm          %s %s%n",
                System.getProperty("java.vm.name"), System.getProperty("java.version"));
        System.out.printf("  os           %s %s (%s)%n",
                System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"));
        System.out.printf("  cpus         %d available to the JVM%n", runtime.availableProcessors());
        System.out.printf("  heap         %,d MB max%n", runtime.maxMemory() / (1024 * 1024));
        System.out.printf("  database     %s%n", database);
        System.out.printf("  queries      %d, %d bases each%n", queries.size(), queries.get(0).length());
    }

    /** Keeps the JIT from deleting work whose result nothing observes. */
    private static void consume(long checksum) {
        if (checksum == Long.MIN_VALUE) System.out.println("unreachable " + checksum);
    }

    private static String argument(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(flag)) return args[i + 1];
        return fallback;
    }
}
