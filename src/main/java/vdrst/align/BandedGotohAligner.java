package vdrst.align;

/**
 * Gotoh alignment restricted to a band of diagonals.
 *
 * <p>Full Smith-Waterman evaluates every cell of an m x n matrix because it assumes
 * nothing about where the alignment lies. After a seed-and-extend prefilter that
 * assumption is no longer true: the seeds already say which diagonal the alignment runs
 * along, and the subject window was cut to bracket it. Almost every cell of the full
 * matrix describes an alignment that starts hundreds of bases away from where the
 * evidence points, and computing it is work thrown away.
 *
 * <p>The band spans offsets {@code j - i} in
 * {@code [-band, (subject.length - query.length) + band]}, which covers every alignment
 * that starts anywhere inside the window, plus {@code band} bases of slack for indels.
 * With the windows {@code KmerPrefilter} produces this is roughly a third of the cells.
 *
 * <h2>This is a heuristic, and the tests say so</h2>
 * An alignment that lies outside the band is not found. That is the trade being made,
 * and {@code BandedAlignerTest} pins both halves of it: inside the band the score is
 * identical to {@link GotohAligner}, and outside it the band is shown to miss. A
 * heuristic whose failure mode is documented and tested is an engineering decision; one
 * whose failure mode is undiscovered is a bug waiting for a user to find.
 */
public final class BandedGotohAligner implements Aligner {

    private static final int NEG = Integer.MIN_VALUE / 2;

    /** Slack either side of the window, in bases. Sized to match KmerPrefilter's margin. */
    public static final int DEFAULT_BAND = 64;

    private final ScoringScheme scoring;
    private final int band;

    public BandedGotohAligner() {
        this(ScoringScheme.prefilter(), DEFAULT_BAND);
    }

    public BandedGotohAligner(ScoringScheme scoring, int band) {
        if (band < 0) throw new IllegalArgumentException("band must be >= 0, got " + band);
        this.scoring = scoring;
        this.band = band;
    }

    @Override
    public int score(byte[] query, byte[] subject) {
        final int m = query.length, n = subject.length;
        if (m == 0 || n == 0) return 0;

        final int lo = -band;
        final int hi = Math.max(0, n - m) + band;
        final int width = hi - lo + 1;
        if (width >= n) {
            // The band covers everything; the restriction buys nothing and the general
            // implementation is faster because it has no offset arithmetic.
            return new GotohAligner(scoring).score(query, subject);
        }

        final int open = scoring.gapOpen(), extend = scoring.gapExtend();
        final int firstGap = open + extend;

        // Row buffers indexed by band offset rather than by j, so they stay small.
        int[] h = new int[width + 2];
        int[] f = new int[width + 2];
        java.util.Arrays.fill(f, NEG);

        int[] hPrev = new int[width + 2];
        int[] fPrev = new int[width + 2];
        java.util.Arrays.fill(fPrev, NEG);

        int max = 0;

        for (int i = 1; i <= m; i++) {
            final byte qi = query[i - 1];
            int e = NEG;

            // Columns touched on this row, clamped to the subject.
            final int jFrom = Math.max(1, i + lo);
            final int jTo = Math.min(n, i + hi);

            java.util.Arrays.fill(h, 0);
            java.util.Arrays.fill(f, NEG);

            for (int j = jFrom; j <= jTo; j++) {
                final int slot = j - i - lo + 1;          // 1..width within the band
                final int slotPrev = slot + 1;            // same j, previous row: (j)-(i-1)-lo+1

                final int hLeft = slot >= 2 ? h[slot - 1] : 0;
                final int hUp = slotPrev < hPrev.length ? hPrev[slotPrev] : 0;
                final int hDiag = slot < hPrev.length ? hPrev[slot] : 0;
                final int fUp = slotPrev < fPrev.length ? fPrev[slotPrev] : NEG;

                e = Math.max(e + extend, hLeft + firstGap);
                final int fHere = Math.max(fUp + extend, hUp + firstGap);
                f[slot] = fHere;

                final int diag = hDiag + scoring.substitution(qi, subject[j - 1]);
                final int best = Math.max(0, Math.max(diag, Math.max(e, fHere)));
                h[slot] = best;
                if (best > max) max = best;
            }

            int[] swapH = hPrev; hPrev = h; h = swapH;
            int[] swapF = fPrev; fPrev = f; f = swapF;
        }

        return max;
    }

    public int band() { return band; }

    @Override
    public ScoringScheme scoring() { return scoring; }

    @Override
    public String id() { return "gotoh-banded-" + band; }
}
