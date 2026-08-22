package vdrst.align;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;

/**
 * Gotoh alignment vectorised along anti-diagonals, using the Java Vector API.
 *
 * <h2>Why anti-diagonals</h2>
 * The dependency that makes Smith-Waterman awkward to vectorise is the one inside a row:
 * {@code E[i][j]} needs {@code E[i][j-1]}, so neighbouring cells of a row cannot be
 * computed together. Farrar's striped layout works around this with a fix-up pass whose
 * cost depends on the data.
 *
 * <p>Cells on one anti-diagonal have no such problem. Every dependency of a cell on
 * anti-diagonal {@code d} — the diagonal predecessor, the cell to its left, the cell
 * above — lies on {@code d-1} or {@code d-2}. So a whole anti-diagonal is computed in
 * one pass of independent lanes, with no fix-up and no data-dependent branch. The
 * arithmetic is identical to the scalar version, which is why
 * {@code AlignerEquivalenceTest} can demand an exact match rather than a tolerance.
 *
 * <h2>Making the loads contiguous</h2>
 * Walking an anti-diagonal means the query index rises while the subject index falls, and
 * a gathered load would give back most of what vectorising won. Reversing the subject once
 * per call turns the falling index into a rising one, so both operands are contiguous
 * loads. The reversal is O(n) against O(mn) of alignment.
 *
 * <h2>What this does not do</h2>
 * Lanes are 32-bit. Scores in this project are bounded well inside that, so there is no
 * saturation handling and no widening pass; a scheme with large rewards over very long
 * sequences would need one. The scalar implementations remain the reference.
 */
public final class VectorGotohAligner implements Aligner {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
    private static final int NEG = Integer.MIN_VALUE / 2;

    private final ScoringScheme scoring;

    /** Reused across calls on the same thread, so a steady-state search allocates nothing. */
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public VectorGotohAligner() {
        this(ScoringScheme.prefilter());
    }

    public VectorGotohAligner(ScoringScheme scoring) {
        this.scoring = scoring;
    }

    /** Lanes per vector on this machine — 8 under AVX2, 16 under AVX-512. */
    public static int laneCount() {
        return SPECIES.length();
    }

    public static String speciesDescription() {
        return SPECIES.toString() + " (" + SPECIES.length() + " lanes)";
    }

    private static final class Scratch {
        int[] hPrev1 = new int[0], hPrev2 = new int[0], ePrev1 = new int[0], fPrev1 = new int[0];
        int[] hCurr = new int[0], eCurr = new int[0], fCurr = new int[0];
        int[] queryLanes = new int[0], reversedSubject = new int[0];

        void sizeFor(int m, int n) {
            int need = m + 2 + SPECIES.length();
            if (hPrev1.length < need) {
                hPrev1 = new int[need]; hPrev2 = new int[need];
                ePrev1 = new int[need]; fPrev1 = new int[need];
                hCurr = new int[need]; eCurr = new int[need]; fCurr = new int[need];
                queryLanes = new int[need];
            }
            // Padded so a masked load at the end of an anti-diagonal stays in bounds.
            if (reversedSubject.length < n + SPECIES.length()) {
                reversedSubject = new int[n + SPECIES.length()];
            }
        }
    }

    @Override
    public int score(byte[] query, byte[] subject) {
        final int m = query.length, n = subject.length;
        if (m == 0 || n == 0) return 0;

        Scratch s = scratch.get();
        s.sizeFor(m, n);

        // Reversed and widened in one pass, once per call. Reversing turns the falling
        // subject index of an anti-diagonal into a rising one, so both operands become
        // contiguous loads; widening to int means the inner loop never converts. Both are
        // O(m + n) against O(mn) of alignment.
        final int[] reversed = s.reversedSubject;
        for (int i = 0; i < n; i++) reversed[i] = subject[n - 1 - i];

        final int[] queryLanes = s.queryLanes;
        for (int i = 0; i < m; i++) queryLanes[i] = query[i];

        int[] hPrev1 = s.hPrev1, hPrev2 = s.hPrev2, ePrev1 = s.ePrev1, fPrev1 = s.fPrev1;
        int[] hCurr = s.hCurr, eCurr = s.eCurr, fCurr = s.fCurr;

        java.util.Arrays.fill(hPrev1, 0, m + 2, 0);
        java.util.Arrays.fill(hPrev2, 0, m + 2, 0);
        java.util.Arrays.fill(ePrev1, 0, m + 2, NEG);
        java.util.Arrays.fill(fPrev1, 0, m + 2, NEG);

        final int match = scoring.match(), mismatch = scoring.mismatch();
        final int extend = scoring.gapExtend(), firstGap = scoring.gapOpen() + scoring.gapExtend();
        final int ambiguous = Nucleotides.N;

        final IntVector vExtend = IntVector.broadcast(SPECIES, extend);
        final IntVector vFirstGap = IntVector.broadcast(SPECIES, firstGap);
        final IntVector vMatch = IntVector.broadcast(SPECIES, match);
        final IntVector vMismatch = IntVector.broadcast(SPECIES, mismatch);
        final IntVector vAmbiguous = IntVector.broadcast(SPECIES, ambiguous);
        final IntVector vZero = IntVector.zero(SPECIES);
        IntVector vMax = vZero;

        for (int d = 2; d <= m + n; d++) {
            final int iFrom = Math.max(1, d - n);
            final int iTo = Math.min(m, d - 1);
            if (iFrom > iTo) continue;

            // subject[j-1] with j = d-i lives at reversed[n-d+i], so the lane load is a
            // plain contiguous read at this offset. An earlier version copied the
            // anti-diagonal into a scratch array first; that copy was O(mn) of scalar
            // work and cost most of what vectorising had bought.
            final int base = n - d;

            int i = iFrom;
            final int upperBound = iTo + 1;

            for (; i < upperBound; i += SPECIES.length()) {
                VectorMask<Integer> mask = SPECIES.indexInRange(i, upperBound);

                IntVector q = IntVector.fromArray(SPECIES, queryLanes, i - 1, mask);
                IntVector t = IntVector.fromArray(SPECIES, reversed, base + i, mask);

                // A match scores `match` only when both bases are known and equal.
                VectorMask<Integer> equal = q.eq(t)
                        .and(q.eq(vAmbiguous).not())
                        .and(t.eq(vAmbiguous).not());
                IntVector substitution = vMismatch.blend(vMatch, equal);

                IntVector hLeft = IntVector.fromArray(SPECIES, hPrev1, i, mask);      // H[i][j-1]
                IntVector eLeft = IntVector.fromArray(SPECIES, ePrev1, i, mask);      // E[i][j-1]
                IntVector hUp = IntVector.fromArray(SPECIES, hPrev1, i - 1, mask);    // H[i-1][j]
                IntVector fUp = IntVector.fromArray(SPECIES, fPrev1, i - 1, mask);    // F[i-1][j]
                IntVector hDiag = IntVector.fromArray(SPECIES, hPrev2, i - 1, mask);  // H[i-1][j-1]

                IntVector e = eLeft.add(vExtend).max(hLeft.add(vFirstGap));
                IntVector f = fUp.add(vExtend).max(hUp.add(vFirstGap));
                IntVector h = hDiag.add(substitution).max(e).max(f).max(vZero);

                e.intoArray(eCurr, i, mask);
                f.intoArray(fCurr, i, mask);
                h.intoArray(hCurr, i, mask);
                vMax = vMax.max(h.blend(vZero, mask.not()));
            }

            // Cells just outside this anti-diagonal must read as empty on the next pass.
            hCurr[iFrom - 1] = 0;
            eCurr[iFrom - 1] = NEG;
            fCurr[iFrom - 1] = NEG;
            if (iTo + 1 < m + 2) { hCurr[iTo + 1] = 0; eCurr[iTo + 1] = NEG; fCurr[iTo + 1] = NEG; }

            int[] swap = hPrev2; hPrev2 = hPrev1; hPrev1 = hCurr; hCurr = swap;
            int[] swapE = ePrev1; ePrev1 = eCurr; eCurr = swapE;
            int[] swapF = fPrev1; fPrev1 = fCurr; fCurr = swapF;
        }

        return Math.max(0, vMax.reduceLanes(jdk.incubator.vector.VectorOperators.MAX));
    }

    @Override
    public ScoringScheme scoring() { return scoring; }

    @Override
    public String id() { return "gotoh-simd-" + SPECIES.length() + "lane"; }
}
