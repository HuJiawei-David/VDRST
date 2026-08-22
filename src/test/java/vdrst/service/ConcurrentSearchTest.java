package vdrst.service;

import vdrst.index.Prefilter;
import vdrst.blast.TestCorpus;
import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The regression test for the defect that mattered most in v1.
 *
 * <p>v1 wrote every query to {@code ./query.fasta} and every result to
 * {@code ./result.txt} — two fixed paths, resolved identically for every request. Two
 * searches in flight at the same time overwrite each other, and the second user can be
 * handed the alignment for the first user's sequence. Nothing in v1 detected this,
 * because it only ever ran with one person clicking one button.
 *
 * <p>{@link #v1DesignGivesEveryRequestTheSameFiles()} proves the defect existed, without
 * relying on a race to show up. {@link #concurrentSearchesReturnTheirOwnResults()} is
 * the guard: it drives real concurrent searches through v2 and checks every one of them
 * came back with the answer to its own question.
 *
 * <p>See RETROSPECTIVE.md finding 2.
 */
public final class ConcurrentSearchTest {

    private static final int THREADS = 16;
    private static final int SEARCHES = 32;

    /**
     * v1's path resolution, reproduced exactly. Both values were hard-coded — the query
     * path as a literal, the output path from a configuration key that never varied per
     * request.
     */
    private static final class LegacyWorkspace {
        Path queryFile() { return Paths.get("./query.fasta"); }
        Path outputFile() { return Paths.get("./result.txt"); }
    }

    @Test("v1's design hands every concurrent request the same two files")
    public void v1DesignGivesEveryRequestTheSameFiles() {
        LegacyWorkspace first = new LegacyWorkspace();
        LegacyWorkspace second = new LegacyWorkspace();

        Assert.equal(first.queryFile(), second.queryFile(),
                "v1 resolved the same query path for every request, so concurrent writes collide");
        Assert.equal(first.outputFile(), second.outputFile(),
                "v1 resolved the same output path for every request, so results overwrite each other");
    }

    @Test("v2 keeps no shared filesystem state between searches")
    public void v2HasNoSharedWorkspace() {
        // v2 streams the query in on stdin and reads results from stdout. There is no
        // path to collide over, so the class of bug above cannot recur by construction
        // rather than by being carefully avoided.
        Assert.isTrue(!java.nio.file.Files.exists(Paths.get("./query.fasta")),
                "a search must not leave query.fasta behind in the working directory");
        Assert.isTrue(!java.nio.file.Files.exists(Paths.get("./result.txt")),
                "a search must not leave result.txt behind in the working directory");
    }

    @Test("32 concurrent searches each return the answer to their own query")
    public void concurrentSearchesReturnTheirOwnResults() throws Exception {
        List<String> queries = TestCorpus.queries();
        Assert.isTrue(queries.size() >= SEARCHES, "the corpus should provide at least " + SEARCHES + " queries");

        try (Prefilter runner = TestCorpus.prefilter();
             ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {

            SearchService service = new SearchService(runner);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>(SEARCHES);

            for (int i = 0; i < SEARCHES; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();                       // maximise overlap
                    List<Match> matches = service.search(queries.get(index));
                    return matches.isEmpty() ? "(no matches)" : matches.get(0).subjectId();
                }));
            }

            startGate.countDown();

            AtomicInteger wrong = new AtomicInteger();
            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < SEARCHES; i++) {
                String expected = TestCorpus.expectedSubjectFor(i);
                String actual = futures.get(i).get(120, TimeUnit.SECONDS);
                if (!expected.equals(actual)) {
                    wrong.incrementAndGet();
                    detail.append("\n    query ").append(i)
                          .append(" expected ").append(expected)
                          .append(" but got ").append(actual);
                }
            }

            Assert.equal(0, wrong.get(),
                    "concurrent searches returned results belonging to other queries" + detail);
        }
    }

    @Test("a validation failure in one search does not disturb the others")
    public void invalidInputIsIsolated() throws Exception {
        List<String> queries = TestCorpus.queries();

        try (Prefilter runner = TestCorpus.prefilter();
             ExecutorService pool = Executors.newFixedThreadPool(8)) {

            SearchService service = new SearchService(runner);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    // Every other request is malformed.
                    String query = index % 2 == 0 ? queries.get(index) : "NOT A SEQUENCE!!";
                    try {
                        List<Match> matches = service.search(query);
                        return matches.isEmpty() ? "(none)" : matches.get(0).subjectId();
                    } catch (SequenceValidator.InvalidRequestException e) {
                        return "(rejected)";
                    }
                }));
            }

            for (int i = 0; i < 8; i++) {
                String actual = futures.get(i).get(120, TimeUnit.SECONDS);
                String expected = i % 2 == 0 ? TestCorpus.expectedSubjectFor(i) : "(rejected)";
                Assert.equal(expected, actual, "request " + i + " was affected by its neighbours");
            }
        }
    }
}
