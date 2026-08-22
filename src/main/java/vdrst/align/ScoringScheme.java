package vdrst.align;

/**
 * A nucleotide scoring scheme with affine gap costs.
 *
 * <p>Convention: a gap of length {@code k} costs {@code gapOpen + k * gapExtend},
 * with both stored as negative numbers. This matches NCBI BLAST's model, where the
 * same two quantities are supplied as positive costs via {@code -gapopen} and
 * {@code -gapextend}.
 *
 * <p>v1 did not have this type. The prefilter was invoked with one set of scores and
 * the re-ranking Smith-Waterman was hard-coded with a different, incompatible set, so
 * the two stages disagreed about what "similar" meant. See RETROSPECTIVE.md finding 4.
 */
public record ScoringScheme(int match, int mismatch, int gapOpen, int gapExtend) {

    public ScoringScheme {
        if (match <= 0) throw new IllegalArgumentException("match must be > 0, got " + match);
        if (mismatch >= 0) throw new IllegalArgumentException("mismatch must be < 0, got " + mismatch);
        if (gapOpen > 0) throw new IllegalArgumentException("gapOpen must be <= 0, got " + gapOpen);
        if (gapExtend >= 0) throw new IllegalArgumentException("gapExtend must be < 0, got " + gapExtend);
    }

    /**
     * The scheme the prefilter is invoked with, and — in v2 — the scheme the re-ranking
     * stage uses too: {@code -reward 1 -penalty -2 -gapopen 2 -gapextend 2}.
     * These are the same values v1 passed to blastn; what v1 did not do was use them
     * for its own Smith-Waterman as well.
     */
    public static ScoringScheme prefilter() {
        return new ScoringScheme(1, -2, -2, -2);
    }

    /**
     * The scheme v1's own Smith-Waterman used: match 2, mismatch -1, and a flat
     * linear gap penalty of -1 with no distinction between opening and extending.
     *
     * <p>Retained so the benchmarks and the differential tests can reproduce v1's
     * behaviour exactly. It is not used by the v2 search path.
     */
    public static ScoringScheme legacyV1() {
        return new ScoringScheme(2, -1, 0, -1);
    }

    /** True when opening a gap costs nothing beyond extending it, i.e. the gap model is linear. */
    public boolean isLinearGap() {
        return gapOpen == 0;
    }

    public int substitution(byte a, byte b) {
        return a == b && a != Nucleotides.N ? match : mismatch;
    }
}
