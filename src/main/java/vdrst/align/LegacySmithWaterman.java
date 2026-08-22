package vdrst.align;

/**
 * v1's Smith-Waterman, preserved verbatim in structure.
 *
 * <p>This is deliberately <em>not</em> the algorithm v2 uses. It is kept so that the
 * benchmarks have an honest baseline and the retrospective's claims about v1 can be
 * re-checked by anyone, rather than taken on trust.
 *
 * <p>Faithful to v1 in three respects that matter:
 * <ul>
 *   <li>a full {@code int[qlen+1][slen+1]} matrix is allocated on every call, so cost
 *       is O(mn) in both time and space, and every call produces garbage;</li>
 *   <li>the gap model is linear — one flat penalty, no open/extend distinction;</li>
 *   <li>Java's {@code int[][]} is an array of row pointers, so the inner loop walks
 *       memory that the allocator was free to scatter.</li>
 * </ul>
 *
 * @see GotohAligner for the affine-gap replacement
 */
public final class LegacySmithWaterman implements Aligner {

    private final ScoringScheme scoring;

    public LegacySmithWaterman() {
        this(ScoringScheme.legacyV1());
    }

    public LegacySmithWaterman(ScoringScheme scoring) {
        if (!scoring.isLinearGap()) {
            throw new IllegalArgumentException(
                    "LegacySmithWaterman only models linear gaps; use GotohAligner for affine");
        }
        this.scoring = scoring;
    }

    @Override
    public int score(byte[] query, byte[] subject) {
        final int qlen = query.length, slen = subject.length;
        if (qlen == 0 || slen == 0) return 0;

        int[][] h = new int[qlen + 1][slen + 1];
        final int gap = scoring.gapExtend();
        int max = 0;

        for (int i = 1; i <= qlen; i++) {
            for (int j = 1; j <= slen; j++) {
                int diag = h[i - 1][j - 1] + scoring.substitution(query[i - 1], subject[j - 1]);
                int del = h[i - 1][j] + gap;
                int ins = h[i][j - 1] + gap;
                int best = Math.max(0, Math.max(diag, Math.max(del, ins)));
                h[i][j] = best;
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
        return "legacy-v1";
    }
}
