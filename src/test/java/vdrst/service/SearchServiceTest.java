package vdrst.service;

import vdrst.align.GotohAligner;
import vdrst.align.ScoringScheme;
import vdrst.blast.TestCorpus;
import vdrst.index.Prefilter;
import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.util.List;

public final class SearchServiceTest {

    @Test("a planted query finds the genome it was taken from")
    public void plantedQueryFindsItsSource() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);
            List<String> queries = TestCorpus.queries();

            for (int i = 0; i < 5; i++) {
                List<Match> matches = service.search(queries.get(i));
                Assert.isTrue(!matches.isEmpty(), "query " + i + " returned nothing");
                Assert.equal(TestCorpus.expectedSubjectFor(i), matches.get(0).subjectId(),
                        "query " + i + " should rank its source genome first");
            }
        }
    }

    @Test("results come back in descending score order")
    public void resultsAreSorted() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);
            List<Match> matches = service.search(TestCorpus.queries().get(0));

            for (int i = 1; i < matches.size(); i++) {
                Assert.isTrue(matches.get(i - 1).alignmentScore() >= matches.get(i).alignmentScore(),
                        "result " + i + " scored higher than the one before it");
            }
        }
    }

    @Test("the result limit is respected")
    public void resultLimitRespected() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(
                    runner, new GotohAligner(ScoringScheme.prefilter()), 2, 20);
            Assert.isTrue(service.search(TestCorpus.queries().get(0)).size() <= 2,
                    "more results came back than the configured limit");
        }
    }

    @Test("the normalised score is a fraction in [0,1], unlike v1's percentage")
    public void normalisedScoreIsBounded() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);
            for (Match match : service.search(TestCorpus.queries().get(0))) {
                Assert.isTrue(match.normalizedScore() >= 0.0 && match.normalizedScore() <= 1.0,
                        "normalised score out of range: " + match.normalizedScore()
                                + " for " + match.subjectId());
            }
        }
    }

    @Test("a query planted with 5% mutations scores well above chance")
    public void mutatedQueryScoresHighly() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);
            Match top = service.search(TestCorpus.queries().get(0)).get(0);
            Assert.isTrue(top.normalizedScore() > 0.6,
                    "a 5% mutated fragment should still align strongly, got " + top.normalizedScore());
        }
    }

    @Test("invalid sequences are rejected before any subprocess starts")
    public void invalidSequencesRejected() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);

            Assert.throwsException(SequenceValidator.InvalidRequestException.class,
                    () -> service.search(""), "empty sequence");
            Assert.throwsException(SequenceValidator.InvalidRequestException.class,
                    () -> service.search("ACGT"), "shorter than the minimum length");
            Assert.throwsException(SequenceValidator.InvalidRequestException.class,
                    () -> service.search("ACGTACGTACGTXYZ"), "contains non-nucleotide characters");
        }
    }

    @Test("FASTA headers in the submitted text are stripped, not rejected")
    public void fastaHeaderAccepted() {
        try (Prefilter runner = TestCorpus.prefilter()) {
            SearchService service = new SearchService(runner);
            String query = TestCorpus.queries().get(0);
            List<Match> withHeader = service.search(">my sample\n" + query + "\n");
            List<Match> without = service.search(query);
            Assert.equal(without.get(0).subjectId(), withHeader.get(0).subjectId(),
                    "a pasted FASTA record should behave like the bare sequence");
        }
    }
}
