package vdrst.blast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Locates the seeded CI corpus that the integration tests run against.
 *
 * <p>{@code make test} generates it first, so the tests exercise a real {@code blastn}
 * against a real database rather than a mock. The corpus is deterministic, so a test
 * that passes here passes identically on any machine.
 */
public final class TestCorpus {

    private static final Path DIRECTORY = Paths.get("bench/corpus-ci");
    private static final Path DATABASE = DIRECTORY.resolve("viruses.fasta");
    private static final Path QUERIES = DIRECTORY.resolve("queries.txt");

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
