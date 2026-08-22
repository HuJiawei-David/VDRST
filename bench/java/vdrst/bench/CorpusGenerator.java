package vdrst.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generates a reproducible synthetic virus database and a matching set of query
 * sequences.
 *
 * <p>The real benchmark target is NCBI's {@code ref_viruses_rep_genomes}, which is a
 * multi-gigabyte download. Depending on it would mean nobody could reproduce a number
 * in this repository without first spending an hour fetching data — which is precisely
 * why v1's "48 seconds" claim had no evidence behind it and could not be re-checked.
 *
 * <p>So the default corpus is synthetic and seeded: {@code make corpus} produces byte-identical
 * output on any machine, and every benchmark number in README.md can be reproduced from
 * a clean clone in minutes. Sequence composition and the length distribution are chosen
 * to resemble RefSeq viral rather than to flatter the algorithms — see
 * {@link #sampleLength}. Run against the real database with
 * {@code make bench ARGS="--db /path/to/ref_viruses_rep_genomes"} to confirm the
 * synthetic numbers carry over.
 */
public final class CorpusGenerator {

    /** Fixed. Changing it invalidates every published number. */
    public static final long SEED = 0x5EED_C0FFEEL;

    public enum Scale {
        /** Small enough to run inside the test suite and in CI. */
        CI(100, "~4 Mbp"),
        /** The corpus every published number in README.md was measured on. */
        DEFAULT(500, "~22 Mbp"),
        LARGE(5_000, "~225 Mbp"),
        /** Comparable in total base count to ref_viruses_rep_genomes. */
        REFSEQ(17_000, "~760 Mbp");

        public final int genomes;
        public final String approximateSize;

        Scale(int genomes, String approximateSize) {
            this.genomes = genomes;
            this.approximateSize = approximateSize;
        }
    }

    /** Queries planted per corpus, independent of scale, so percentiles have a stable base. */
    public static final int QUERY_COUNT = 50;

    private static final char[] BASES = {'A', 'C', 'G', 'T'};

    public static void main(String[] args) throws IOException, InterruptedException {
        Scale scale = Scale.DEFAULT;
        Path outputDir = Paths.get("bench/corpus");

        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--scale")) scale = Scale.valueOf(args[i + 1].toUpperCase(java.util.Locale.ROOT));
            if (args[i].equals("--out")) outputDir = Paths.get(args[i + 1]);
        }

        Files.createDirectories(outputDir);
        Path fasta = outputDir.resolve("viruses.fasta");
        Path queries = outputDir.resolve("queries.txt");

        System.out.printf("generating %s corpus (%d genomes, %s) with seed 0x%X%n",
                scale, scale.genomes, scale.approximateSize, SEED);

        SplittableRandom rng = new SplittableRandom(SEED);
        List<String> plantedQueries = new ArrayList<>();
        long totalBases = 0;

        try (BufferedWriter out = Files.newBufferedWriter(fasta, StandardCharsets.UTF_8)) {
            for (int i = 0; i < scale.genomes; i++) {
                int length = sampleLength(rng);
                char[] genome = new char[length];
                for (int j = 0; j < length; j++) genome[j] = BASES[rng.nextInt(4)];
                totalBases += length;

                // A fixed number of genomes donate a mutated fragment as a query, so the
                // benchmark measures the path where the prefilter actually finds hits.
                if (plantedQueries.size() < QUERY_COUNT
                        && i % Math.max(1, scale.genomes / QUERY_COUNT) == 0 && length > 400) {
                    plantedQueries.add(mutate(new String(genome, 100, 300), rng, 0.05));
                }

                out.write(">synthetic_" + i + " synthetic virus genome " + i + ", " + length + " bp\n");
                for (int offset = 0; offset < length; offset += 70) {
                    out.write(genome, offset, Math.min(70, length - offset));
                    out.write('\n');
                }
            }
        }

        Files.write(queries, plantedQueries, StandardCharsets.UTF_8);
        System.out.printf("wrote %s (%,d bases across %,d genomes)%n", fasta, totalBases, scale.genomes);
        System.out.printf("wrote %s (%d queries)%n", queries, plantedQueries.size());

        makeBlastDb(fasta);
    }

    /**
     * Viral genome lengths span three orders of magnitude and are strongly right-skewed:
     * most are a few kilobases, a long tail runs to hundreds. A log-uniform draw over
     * [1 kbp, 250 kbp] reproduces that shape closely enough for the prefilter's selectivity
     * to be realistic, without pretending to model any particular taxon.
     */
    private static int sampleLength(SplittableRandom rng) {
        double logMin = Math.log(1_000), logMax = Math.log(250_000);
        return (int) Math.exp(logMin + rng.nextDouble() * (logMax - logMin));
    }

    private static String mutate(String sequence, SplittableRandom rng, double rate) {
        char[] chars = sequence.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (rng.nextDouble() < rate) chars[i] = BASES[rng.nextInt(4)];
        }
        return new String(chars);
    }

    private static void makeBlastDb(Path fasta) throws IOException, InterruptedException {
        System.out.println("running makeblastdb...");
        Process process = new ProcessBuilder(
                "makeblastdb", "-in", fasta.toString(), "-dbtype", "nucl",
                "-title", "vdrst_synthetic", "-parse_seqids")
                .redirectErrorStream(true).start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("makeblastdb did not finish within 10 minutes");
        }
        if (process.exitValue() != 0) {
            throw new IOException("makeblastdb failed:\n" + output);
        }
        System.out.println("database ready at " + fasta);
    }
}
