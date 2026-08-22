package vdrst.align;

/**
 * Smith-Waterman local alignment with affine gap costs (Gotoh, 1982).
 *
 * <p>Three recurrences are tracked instead of one:
 * <ul>
 *   <li>{@code H} — best score for an alignment ending in a match/mismatch;</li>
 *   <li>{@code E} — best score ending in a gap in the query;</li>
 *   <li>{@code F} — best score ending in a gap in the subject.</li>
 * </ul>
 * Opening a gap costs {@code gapOpen + gapExtend}; each further residue costs
 * {@code gapExtend}. v1 charged a flat penalty per gap residue, which over-penalises
 * the single multi-base indels that dominate real viral variation — and disagreed with
 * the affine costs v1 itself passed to blastn. See RETROSPECTIVE.md finding 5.
 *
 * <p>Space is O(min(m, n)): only the previous row is retained, and the shorter sequence
 * is used as the inner dimension. Time is O(mn).
 */
public final class GotohAligner implements Aligner {

    private final ScoringScheme scoring;

    public GotohAligner() {
        this(ScoringScheme.prefilter());
    }

    public GotohAligner(ScoringScheme scoring) {
        this.scoring = scoring;
    }

    @Override
    public int score(byte[] query, byte[] subject) {
        // Local alignment is symmetric in its score, so orient for the smaller row buffer.
        byte[] outer = query, inner = subject;
        if (inner.length > outer.length) { byte[] t = outer; outer = inner; inner = t; }

        final int m = outer.length, n = inner.length;
        if (m == 0 || n == 0) return 0;

        final int open = scoring.gapOpen(), extend = scoring.gapExtend();
        final int firstGap = open + extend;

        int[] h = new int[n + 1];   // previous row of H
        int[] f = new int[n + 1];   // previous row of F (gap in outer)
        java.util.Arrays.fill(f, Integer.MIN_VALUE / 2);

        int max = 0;
        for (int i = 1; i <= m; i++) {
            int diagPrev = 0;       // H[i-1][j-1]
            int e = Integer.MIN_VALUE / 2;
            final byte oi = outer[i - 1];
            for (int j = 1; j <= n; j++) {
                final int hLeft = h[j - 1];     // H[i][j-1] once written this row
                final int hUp = h[j];           // H[i-1][j]

                e = Math.max(e + extend, hLeft + firstGap);          // gap in inner
                f[j] = Math.max(f[j] + extend, hUp + firstGap);      // gap in outer

                int diag = diagPrev + scoring.substitution(oi, inner[j - 1]);
                int best = Math.max(0, Math.max(diag, Math.max(e, f[j])));

                diagPrev = hUp;
                h[j] = best;
                if (best > max) max = best;
            }
        }
        return max;
    }

    @Override
    public ScoringScheme scoring() {
        return scoring;
    }

    @Override
    public String id() {
        return "gotoh-affine";
    }
}
