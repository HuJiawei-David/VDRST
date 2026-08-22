package vdrst.align;

import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * Every {@link Aligner} in the main source tree must return exactly the same score as
 * {@link ReferenceGotoh}, the naive three-matrix implementation, for the same input and
 * scoring scheme.
 *
 * <p>This is the test that lets the optimised implementations be trusted. Without it,
 * "4x faster" is indistinguishable from "4x faster and quietly wrong" — which is the
 * failure mode v1's re-ranking stage actually had, in a form nothing was checking for.
 */
public final class AlignerEquivalenceTest {

    private static final int TRIALS = 400;
    private static final long SEED = 0x5EED_1234L;

    /**
     * Every affine-gap implementation in the main source tree. Adding one here is what
     * makes it trustworthy: the optimised versions must produce the identical integer the
     * naive reference does, not merely a similar one.
     *
     * <p>The banded aligner appears with a band wide enough to cover any input these
     * tests generate, because inside its band it is exact. Where the band bites is a
     * separate question, pinned by BandedAlignerTest.
     */
    private static List<Function<ScoringScheme, Aligner>> affineAligners() {
        return List.of(
                GotohAligner::new,
                VectorGotohAligner::new,
                scoring -> new BandedGotohAligner(scoring, 10_000));
    }

    @Test("Gotoh agrees with the reference implementation on random sequences")
    public void gotohMatchesReference() {
        SplittableRandom rng = new SplittableRandom(SEED);
        ScoringScheme scoring = ScoringScheme.prefilter();
        ReferenceGotoh oracle = new ReferenceGotoh(scoring);

        for (Function<ScoringScheme, Aligner> factory : affineAligners()) {
            Aligner aligner = factory.apply(scoring);
            for (int trial = 0; trial < TRIALS; trial++) {
                byte[] q = randomSequence(rng, 1 + rng.nextInt(120));
                byte[] s = randomSequence(rng, 1 + rng.nextInt(120));
                Assert.equal(oracle.score(q, s), aligner.score(q, s),
                        aligner.id() + " disagreed with the reference on trial " + trial
                                + "\n  query   = " + Nucleotides.decode(q)
                                + "\n  subject = " + Nucleotides.decode(s));
            }
        }
    }

    @Test("Gotoh agrees with the reference across several scoring schemes")
    public void gotohMatchesReferenceAcrossSchemes() {
        SplittableRandom rng = new SplittableRandom(SEED + 1);
        List<ScoringScheme> schemes = List.of(
                ScoringScheme.prefilter(),
                new ScoringScheme(2, -3, -5, -2),
                new ScoringScheme(1, -1, -1, -1),
                new ScoringScheme(5, -4, -10, -1));

        for (ScoringScheme scoring : schemes) {
            ReferenceGotoh oracle = new ReferenceGotoh(scoring);
            for (Function<ScoringScheme, Aligner> factory : affineAligners()) {
                Aligner aligner = factory.apply(scoring);
                for (int trial = 0; trial < 120; trial++) {
                    byte[] q = randomSequence(rng, 1 + rng.nextInt(80));
                    byte[] s = randomSequence(rng, 1 + rng.nextInt(80));
                    Assert.equal(oracle.score(q, s), aligner.score(q, s),
                            aligner.id() + " disagreed under " + scoring
                                    + "\n  query   = " + Nucleotides.decode(q)
                                    + "\n  subject = " + Nucleotides.decode(s));
                }
            }
        }
    }

    @Test("alignment is symmetric: swapping query and subject does not change the score")
    public void gotohIsSymmetric() {
        SplittableRandom rng = new SplittableRandom(SEED + 2);
        Aligner aligner = new GotohAligner();
        for (int trial = 0; trial < 200; trial++) {
            byte[] q = randomSequence(rng, 1 + rng.nextInt(90));
            byte[] s = randomSequence(rng, 1 + rng.nextInt(90));
            Assert.equal(aligner.score(q, s), aligner.score(s, q),
                    "score changed when the arguments were swapped");
        }
    }

    @Test("v1's linear-gap Smith-Waterman is the gapOpen=0 case of the reference")
    public void legacyIsTheLinearCaseOfGotoh() {
        SplittableRandom rng = new SplittableRandom(SEED + 3);
        ScoringScheme linear = ScoringScheme.legacyV1();
        ReferenceGotoh oracle = new ReferenceGotoh(linear);
        LegacySmithWaterman legacy = new LegacySmithWaterman(linear);

        for (int trial = 0; trial < TRIALS; trial++) {
            byte[] q = randomSequence(rng, 1 + rng.nextInt(100));
            byte[] s = randomSequence(rng, 1 + rng.nextInt(100));
            Assert.equal(oracle.score(q, s), legacy.score(q, s),
                    "v1's algorithm did not match the linear-gap reference on trial " + trial);
        }
    }

    @Test("identical sequences score length x match under any scheme")
    public void identicalSequencesScoreMaximum() {
        SplittableRandom rng = new SplittableRandom(SEED + 4);
        GotohAligner aligner = new GotohAligner();
        for (int trial = 0; trial < 50; trial++) {
            byte[] q = randomSequence(rng, 1 + rng.nextInt(200));
            Assert.equal(q.length * aligner.scoring().match(), aligner.score(q, q),
                    "a sequence aligned against itself should score length x match");
        }
    }

    @Test("an empty sequence scores zero")
    public void emptyScoresZero() {
        GotohAligner aligner = new GotohAligner();
        byte[] empty = new byte[0];
        byte[] seq = Nucleotides.encode("ACGTACGTAA");
        Assert.equal(0, aligner.score(empty, seq), "empty query");
        Assert.equal(0, aligner.score(seq, empty), "empty subject");
        Assert.equal(0, aligner.score(empty, empty), "both empty");
    }

    @Test("affine and linear gap models genuinely disagree")
    public void affineDiffersFromLinear() {
        // One 4-base deletion. Linear charges 4 x -1 = -4. Affine charges -2 + 4 x -2 = -10.
        byte[] q = Nucleotides.encode("AAAACCCCGGGGTTTT");
        byte[] s = Nucleotides.encode("AAAACCCCTTTT");

        int linear = new LegacySmithWaterman(ScoringScheme.legacyV1()).score(q, s);
        int affine = new GotohAligner(ScoringScheme.prefilter()).score(q, s);
        Assert.isTrue(linear != affine,
                "the two gap models produced the same score, so this test proves nothing");
    }

    private static byte[] randomSequence(SplittableRandom rng, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) rng.nextInt(4);
        return out;
    }
}
