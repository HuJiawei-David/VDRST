package vdrst.blast;

import vdrst.index.BlastPrefilter;
import vdrst.index.GenomeStore;
import vdrst.index.KmerIndex;
import vdrst.index.KmerPrefilter;
import vdrst.index.Prefilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The seeded corpus the integration tests run against.
 *
 * <p>{@code make test} generates it first, so the tests exercise a real index over real
 * sequence data and a real {@code blastn} against a real database, rather than mocks. The
 * corpus is deterministic, so a test that passes here passes identically anywhere.
 *
 * <p>The index is built once and shared. Building it per test would dominate the suite's
 * runtime and would also be wrong: a {@link KmerIndex} is immutable and meant to be
 * shared, and tests that quietly avoid sharing it would stop testing that property.
 */
public final class TestCorpus {

    private static final Path DIRECTORY = Paths.get("bench/corpus-ci");
    private static final Path DATABASE = DIRECTORY.resolve("viruses.fasta");
    private static final Path QUERIES = DIRECTORY.resolve("queries.txt");

    private static volatile KmerIndex sharedIndex;

    private TestCorpus() {}

    public static String database() {
        if (!Files.exists(Paths.get(DATABASE + ".nin"))) {
            throw new IllegalStateException(
                    "the CI corpus is missing — run: make corpus ARGS=\"--scale CI --out bench/corpus-ci\"");
        }
        return DATABASE.toString();
    }

    public static List<String> queries() {
        try {
            return Files.readAllLines(QUERIES);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + QUERIES, e);
        }
    }

    /** The shared, immutable index over the corpus. */
    public static KmerIndex index() {
        KmerIndex local = sharedIndex;
        if (local == null) {
            synchronized (TestCorpus.class) {
                local = sharedIndex;
                if (local == null) {
                    try {
                        local = KmerIndex.build(GenomeStore.load(DATABASE));
                    } catch (IOException e) {
                        throw new IllegalStateException("could not load " + DATABASE, e);
                    }
                    sharedIndex = local;
                }
            }
        }
        return local;
    }

    /** The default prefilter: the in-process index. */
    public static Prefilter prefilter() {
        return new KmerPrefilter(index());
    }

    /** The reference prefilter: blastn in a subprocess. */
    public static Prefilter blastPrefilter() {
        return new BlastPrefilter(runner());
    }

    public static BlastRunner runner() {
        return new BlastRunner("blastn", database());
    }

    /**
     * The corpus plants query {@code i} as a mutated fragment of a known genome, so a
     * correct search has exactly one right answer and a corrupted one is detectable.
     */
    public static String expectedSubjectFor(int queryIndex) {
        return "synthetic_" + (queryIndex * 2);
    }
}
