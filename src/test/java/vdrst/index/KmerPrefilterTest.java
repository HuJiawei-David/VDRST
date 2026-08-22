package vdrst.index;

import vdrst.align.Nucleotides;
import vdrst.blast.TestCorpus;
import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * The in-process index replaced a subprocess that has had thirty years of work put into
 * it. The bar for that is not "it returns something plausible" — it is that it finds what
 * BLAST finds, on real data, and that it finds the answers the corpus planted.
 */
public final class KmerPrefilterTest {

    @Test("every planted query ranks its source genome first")
    public void findsPlantedSource() {
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        List<String> queries = TestCorpus.queries();
        List<String> wrong = new ArrayList<>();

        for (int i = 0; i < queries.size(); i++) {
            byte[] query = Nucleotides.encode(queries.get(i));
            List<Candidate> candidates = prefilter.candidates(query, 20);
            String actual = candidates.isEmpty() ? "(none)" : candidates.get(0).subjectId();
            if (!TestCorpus.expectedSubjectFor(i).equals(actual)) {
                wrong.add("query " + i + " -> " + actual
                        + " (expected " + TestCorpus.expectedSubjectFor(i) + ")");
            }
        }
        Assert.equal(0, wrong.size(), "the index missed planted sources: " + wrong);
    }

    @Test("the index agrees with blastn on the top candidate")
    public void agreesWithBlast() {
        List<String> queries = TestCorpus.queries();
        List<String> disagreements = new ArrayList<>();

        try (Prefilter mine = new KmerPrefilter(TestCorpus.index());
             Prefilter blast = TestCorpus.blastPrefilter()) {

            for (int i = 0; i < Math.min(15, queries.size()); i++) {
                byte[] query = Nucleotides.encode(queries.get(i));
                List<Candidate> theirs = blast.candidates(query, 5);
                List<Candidate> ours = mine.candidates(query, 5);

                if (theirs.isEmpty()) continue;         // nothing to agree about
                if (ours.isEmpty() || !ours.get(0).subjectId().equals(theirs.get(0).subjectId())) {
                    disagreements.add("query " + i
                            + ": blast=" + theirs.get(0).subjectId()
                            + " index=" + (ours.isEmpty() ? "(none)" : ours.get(0).subjectId()));
                }
            }
        }
        Assert.equal(0, disagreements.size(),
                "the in-process index disagreed with blastn: " + disagreements);
    }

    @Test("candidates come back in descending order of evidence")
    public void candidatesAreRanked() {
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        List<Candidate> candidates = prefilter.candidates(
                Nucleotides.encode(TestCorpus.queries().get(0)), 20);

        for (int i = 1; i < candidates.size(); i++) {
            Assert.isTrue(candidates.get(i - 1).seedHits() >= candidates.get(i).seedHits(),
                    "candidate " + i + " had more seed hits than the one ranked above it");
        }
    }

    @Test("no genome appears twice in one candidate list")
    public void candidatesAreDistinct() {
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        List<Candidate> candidates = prefilter.candidates(
                Nucleotides.encode(TestCorpus.queries().get(3)), 20);

        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates) {
            Assert.isTrue(seen.add(candidate.subjectId()),
                    candidate.subjectId() + " was returned more than once");
        }
    }

    @Test("the candidate limit is honoured")
    public void limitHonoured() {
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        for (int limit : new int[]{1, 3, 10}) {
            Assert.isTrue(
                    prefilter.candidates(Nucleotides.encode(TestCorpus.queries().get(0)), limit).size() <= limit,
                    "asked for " + limit + " candidates and got more");
        }
    }

    @Test("a query shorter than k returns nothing rather than failing")
    public void queryShorterThanK() {
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        Assert.equal(0, prefilter.candidates(Nucleotides.encode("ACGT"), 10).size(),
                "a query with no complete k-mer cannot be searched");
    }

    @Test("one index serves many threads without interfering with itself")
    public void sharedIndexIsThreadSafe() throws Exception {
        // The scratch buffers are per-thread by construction. This checks that claim
        // rather than trusting it: the same index, hammered concurrently, must give every
        // thread the same answer it gets alone.
        Prefilter prefilter = new KmerPrefilter(TestCorpus.index());
        List<String> queries = TestCorpus.queries();

        String[] expected = new String[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            List<Candidate> c = prefilter.candidates(Nucleotides.encode(queries.get(i)), 5);
            expected[i] = c.isEmpty() ? "(none)" : c.get(0).subjectId();
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();

            for (int repeat = 0; repeat < 4; repeat++) {
                for (int i = 0; i < queries.size(); i++) {
                    final int index = i;
                    futures.add(pool.submit(() -> {
                        gate.await();
                        List<Candidate> c = prefilter.candidates(
                                Nucleotides.encode(queries.get(index)), 5);
                        return c.isEmpty() ? "(none)" : c.get(0).subjectId();
                    }));
                }
            }
            gate.countDown();

            for (int f = 0; f < futures.size(); f++) {
                Assert.equal(expected[f % queries.size()], futures.get(f).get(60, TimeUnit.SECONDS),
                        "concurrent lookups disagreed with the single-threaded answer");
            }
        }
    }
}
