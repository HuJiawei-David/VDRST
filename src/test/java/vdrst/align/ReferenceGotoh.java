package vdrst.align;

/**
 * The most obvious possible affine-gap Smith-Waterman: three full matrices, written
 * straight from the recurrences, optimised for nothing.
 *
 * <p>Test-only. It exists so the fast implementations have an oracle that is easy to
 * read and check by eye. Anything in the main source tree must agree with this on
 * randomised input.
 */
public final class ReferenceGotoh implements Aligner {

    private static final int NEG = Integer.MIN_VALUE / 2;
    private final ScoringScheme scoring;

    public ReferenceGotoh(ScoringScheme scoring) { this.scoring = scoring; }

    @Override
    public int score(byte[] query, byte[] subject) {
        int m = query.length, n = subject.length;
        if (m == 0 || n == 0) return 0;

        int[][] h = new int[m + 1][n + 1];
        int[][] e = new int[m + 1][n + 1];
        int[][] f = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) { java.util.Arrays.fill(e[i], NEG); java.util.Arrays.fill(f[i], NEG); }

        int open = scoring.gapOpen(), extend = scoring.gapExtend();
        int max = 0;

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                e[i][j] = Math.max(e[i][j - 1] + extend, h[i][j - 1] + open + extend);
                f[i][j] = Math.max(f[i - 1][j] + extend, h[i - 1][j] + open + extend);
                int diag = h[i - 1][j - 1] + scoring.substitution(query[i - 1], subject[j - 1]);
                h[i][j] = Math.max(0, Math.max(diag, Math.max(e[i][j], f[i][j])));
                max = Math.max(max, h[i][j]);
            }
        }
        return max;
    }

    @Override public ScoringScheme scoring() { return scoring; }
    @Override public String id() { return "reference-gotoh"; }
}
