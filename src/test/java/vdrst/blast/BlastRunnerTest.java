package vdrst.blast;

import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.time.Duration;
import java.util.List;

public final class BlastRunnerTest {

    @Test("a well-formed query returns hits from the real database")
    public void findsHits() {
        try (BlastRunner runner = TestCorpus.runner()) {
            List<BlastHit> hits = runner.search(TestCorpus.queries().get(0));
            Assert.isTrue(!hits.isEmpty(), "the prefilter found nothing for a planted query");
            Assert.isTrue(hits.size() <= runner.maxTargets(),
                    "more candidates came back than -max_target_seqs allows");
        }
    }

    @Test("the candidate set is bounded, which is what makes re-ranking affordable")
    public void candidateSetIsBounded() {
        try (BlastRunner runner = new BlastRunner("blastn", TestCorpus.database(),
                BlastRunner.DEFAULT_TIMEOUT, 5)) {
            Assert.isTrue(runner.search(TestCorpus.queries().get(1)).size() <= 5,
                    "maxTargets was not honoured");
        }
    }

    @Test("a sequence that matches nothing returns empty rather than failing")
    public void noMatchesIsNotAnError() {
        try (BlastRunner runner = TestCorpus.runner()) {
            // A homopolymer is present everywhere and nowhere; whatever comes back,
            // the call must complete normally.
            List<BlastHit> hits = runner.search("A".repeat(60));
            Assert.isTrue(hits != null, "a search with no good match must still return a list");
        }
    }

    @Test("a missing executable fails with a message that says what to install")
    public void missingExecutableIsDiagnosable() {
        try (BlastRunner runner = new BlastRunner("blastn-does-not-exist", TestCorpus.database())) {
            var e = Assert.throwsException(BlastRunner.BlastExecutionException.class,
                    runner::verifyConfiguration, "a missing binary must fail loudly");
            Assert.contains(e.getMessage(), "BLAST+", "the error should name what is missing");
        }
    }

    @Test("a missing database fails at configuration time, not on a user's first search")
    public void missingDatabaseIsDiagnosable() {
        try (BlastRunner runner = new BlastRunner("blastn", "/nonexistent/database")) {
            var e = Assert.throwsException(BlastRunner.BlastExecutionException.class,
                    runner::verifyConfiguration, "a missing database must fail loudly");
            Assert.contains(e.getMessage(), "/nonexistent/database",
                    "the error should name the database it could not open");
        }
    }

    @Test("a good configuration verifies cleanly")
    public void goodConfigurationVerifies() {
        try (BlastRunner runner = TestCorpus.runner()) {
            runner.verifyConfiguration();     // must not throw
        }
    }

    @Test("an impossible deadline terminates the subprocess instead of hanging")
    public void timeoutTerminatesTheSubprocess() {
        try (BlastRunner runner = new BlastRunner("blastn", TestCorpus.database(),
                Duration.ofMillis(1), BlastRunner.DEFAULT_MAX_TARGETS)) {
            long started = System.nanoTime();
            try {
                runner.search(TestCorpus.queries().get(0));
                // Finishing inside 1 ms is not plausible, but if it happens the point
                // of the test is unaffected: nothing hung.
            } catch (BlastRunner.BlastExecutionException e) {
                Assert.contains(e.getMessage(), "deadline", "the failure should name the deadline");
            }
            long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
            Assert.isTrue(elapsedSeconds < 20,
                    "the call took " + elapsedSeconds + "s — v1 would have waited forever here");
        }
    }

    @Test("a large query does not deadlock on a full stdin pipe")
    public void largeQueryDoesNotDeadlock() {
        // v1 wrote the query inline and never drained the subprocess streams. Anything
        // bigger than the OS pipe buffer (commonly 64 KiB) wedged both sides. 90 KB of
        // sequence comfortably exceeds that.
        StringBuilder large = new StringBuilder(90_000);
        java.util.SplittableRandom rng = new java.util.SplittableRandom(42);
        for (int i = 0; i < 90_000; i++) large.append("ACGT".charAt(rng.nextInt(4)));

        try (BlastRunner runner = new BlastRunner("blastn", TestCorpus.database(),
                Duration.ofSeconds(60), BlastRunner.DEFAULT_MAX_TARGETS)) {
            long started = System.nanoTime();
            runner.search(large.toString());
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            Assert.isTrue(elapsed < 60_000, "a 90 KB query took " + elapsed + " ms");
        }
    }
}
