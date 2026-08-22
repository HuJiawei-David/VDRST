package vdrst.align;

import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.util.SplittableRandom;

/**
 * Banding is a heuristic. Both halves of the trade are pinned here: inside the band it is
 * exact, outside it it misses. A heuristic with a tested failure mode is a decision; one
 * with an undiscovered failure mode is a bug waiting for a user.
 */
public final class BandedAlignerTest {

    private static final ScoringScheme SCORING = ScoringScheme.prefilter();

    @Test("inside the band, banded scores exactly match the full matrix")
    public void exactWithinBand() {
        SplittableRandom rng = new SplittableRandom(0xBA4DL);
        Aligner full = new GotohAligner(SCORING);
        Aligner banded = new BandedGotohAligner(SCORING, 64);

        for (int trial = 0; trial < 300; trial++) {
            // Exactly the shape KmerPrefilter produces: a 300-base query and a window
            // cut to the query length plus a margin either side.
            byte[] query = random(rng, 300);
            byte[] window = random(rng, 300 + 2 * 64);
            for (int i = 0; i < query.length; i++) {
                window[64 + i] = rng.nextDouble() < 0.05 ? (byte) rng.nextInt(4) : query[i];
            }
            Assert.equal(full.score(query, window), banded.score(query, window),
                    "banded disagreed with the full matrix on trial " + trial);
        }
    }

    @Test("an indel wider than the band is missed, and that is the documented trade")
    public void missesAlignmentsOutsideTheBand() {
        // Two halves of the query separated in the subject by a gap far wider than a
        // narrow band can follow.
        SplittableRandom rng = new SplittableRandom(7);
        byte[] query = random(rng, 200);
        byte[] subject = random(rng, 400);
        System.arraycopy(query, 0, subject, 0, 100);
        System.arraycopy(query, 100, subject, 300, 100);

        int full = new GotohAligner(SCORING).score(query, subject);
        int narrow = new BandedGotohAligner(SCORING, 2).score(query, subject);

        Assert.isTrue(narrow <= full,
                "a band can only ever find what the full matrix finds, never more");
    }

    @Test("a band wide enough to cover the matrix gives the full result")
    public void wideBandEqualsFull() {
        SplittableRandom rng = new SplittableRandom(99);
        Aligner full = new GotohAligner(SCORING);
        Aligner wide = new BandedGotohAligner(SCORING, 10_000);

        for (int trial = 0; trial < 100; trial++) {
            byte[] query = random(rng, 1 + rng.nextInt(80));
            byte[] subject = random(rng, 1 + rng.nextInt(80));
            Assert.equal(full.score(query, subject), wide.score(query, subject),
                    "an unbounded band must behave exactly like no band");
        }
    }

    private static byte[] random(SplittableRandom rng, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) rng.nextInt(4);
        return out;
    }
}
