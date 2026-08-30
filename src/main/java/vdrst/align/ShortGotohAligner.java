package vdrst.align;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Gotoh alignment on 16-bit lanes with no masked operation anywhere on the hot path.
 *
 * <p>Two changes over {@link VectorGotohAligner}, each worth measuring separately.
 *
 * <h2>16-bit lanes</h2>
 * Scores in this pipeline are bounded by {@code min(query, subject) * match} — a few
 * hundred for a typical query, nowhere near what 32 bits hold. Halving the lane width
 * doubles the lanes per vector on every ISA: 8 instead of 4 under NEON, 16 instead of 8
 * under AVX2, 32 instead of 16 under AVX-512. A query long enough to threaten 16-bit
 * range is detected up front and delegated to the 32-bit implementation, so the answer
 * is exact either way; only the speed differs.
 *
 * <h2>No masks</h2>
 * The 32-bit version masks every load and store with {@code indexInRange} so the last,
 * partial vector of an anti-diagonal stays in bounds. On AVX-512 masking is a register
 * bit and close to free. NEON has no predicate registers, so every masked operation
 * falls back to a scalar loop inside the JIT — measured on an Apple M1, that made the
 * "vectorised" aligner slower than the scalar one it was supposed to replace.
 *
 * <p>This version runs every vector at full width and lets the last one overhang the
 * anti-diagonal. The overhang lanes are made harmless rather than skipped:
 * <ul>
 *   <li>the query and the reversed subject are padded with sentinel values that equal
 *       nothing, so an overhang lane always takes the mismatch branch;</li>
 *   <li>after each anti-diagonal, one unmasked vector store writes boundary values
 *       (H=0, E=F=-inf) over the cells past its end, so overhang lanes only ever read
 *       cells holding exactly what the matrix border holds.</li>
 * </ul>
 * From boundary inputs and a guaranteed mismatch, an overhang lane computes
 * {@code H = max(0 + mismatch, gapOpen-ish, 0) = 0} — the border value again, which
 * contributes nothing to the running maximum and is then overwritten by the boundary
 * store anyway. The arithmetic in the real lanes is identical to the scalar version,
 * which is what lets {@code AlignerEquivalenceTest} demand the exact integer.
 *
 * <p>N never matches N. The scalar rule needs three comparisons; here the subject's N
 * is recoded to a value the query's N cannot equal, so one comparison decides.
 */
public final class ShortGotohAligner implements Aligner {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;
    private static final short NEG = Short.MIN_VALUE / 2;

    /**
     * Highest attainable score this kernel accepts before delegating to 32-bit lanes.
     * Leaves 2x headroom under {@code Short.MAX_VALUE} so no intermediate sum can wrap.
     */
    private static final int SCORE_CEILING = 15_000;
    private static final int PENALTY_CEILING = 8_000;

    /** Encoded bases are 0..4 with N = 4; these five values collide with none of them. */
    private static final short SUBJECT_N = 5, QUERY_SENTINEL = 6, SUBJECT_SENTINEL = 7;

    private final ScoringScheme scoring;
    private final VectorGotohAligner wideLanes;

    /** Reused across calls on the same thread, so a steady-state search allocates nothing. */
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public ShortGotohAligner() {
        this(ScoringScheme.prefilter());
    }

    public ShortGotohAligner(ScoringScheme scoring) {
        this.scoring = scoring;
        this.wideLanes = new VectorGotohAligner(scoring);
    }

    /** Lanes per vector on this machine — 8 under NEON, 16 under AVX2, 32 under AVX-512. */
    public static int laneCount() {
        return SPECIES.length();
    }

    public static String speciesDescription() {
        return SPECIES.toString() + " (" + SPECIES.length() + " lanes)";
    }

    private static final class Scratch {
        short[] hPrev1 = new short[0], hPrev2 = new short[0], ePrev1 = new short[0], fPrev1 = new short[0];
        short[] hCurr = new short[0], eCurr = new short[0], fCurr = new short[0];
        short[] queryLanes = new short[0], reversedSubject = new short[0];

        void sizeFor(int m, int n) {
            int need = m + 2 + SPECIES.length();
            if (hPrev1.length < need) {
                hPrev1 = new short[need]; hPrev2 = new short[need];
                ePrev1 = new short[need]; fPrev1 = new short[need];
                hCurr = new short[need]; eCurr = new short[need]; fCurr = new short[need];
                queryLanes = new short[need];
            }
            if (reversedSubject.length < n + SPECIES.length()) {
                reversedSubject = new short[n + SPECIES.length()];
            }
        }
    }

    @Override
    public int score(byte[] query, byte[] subject) {
        final int m = query.length, n = subject.length;
        if (m == 0 || n == 0) return 0;

        // Anything that could push a 16-bit lane near wrapping goes to the 32-bit kernel
        // instead. Same arithmetic, same answer; this class only claims the fast path.
        if ((long) Math.min(m, n) * scoring.match() > SCORE_CEILING
                || -scoring.mismatch() > PENALTY_CEILING
                || -(scoring.gapOpen() + scoring.gapExtend()) > PENALTY_CEILING) {
            return wideLanes.score(query, subject);
        }

        final int lanes = SPECIES.length();
        Scratch s = scratch.get();
        s.sizeFor(m, n);

        // Reversed so both anti-diagonal operands become contiguous ascending loads,
        // widened to short so the inner loop never converts, N recoded so one comparison
        // decides a match, and sentinel-padded so overhang lanes always mismatch.
        final short[] reversed = s.reversedSubject;
        for (int i = 0; i < n; i++) {
            byte base = subject[n - 1 - i];
            reversed[i] = base == Nucleotides.N ? SUBJECT_N : base;
        }
        java.util.Arrays.fill(reversed, n, n + lanes, SUBJECT_SENTINEL);

        final short[] queryLanes = s.queryLanes;
        for (int i = 0; i < m; i++) queryLanes[i] = query[i];
        java.util.Arrays.fill(queryLanes, m, Math.min(queryLanes.length, m + lanes), QUERY_SENTINEL);

        short[] hPrev1 = s.hPrev1, hPrev2 = s.hPrev2, ePrev1 = s.ePrev1, fPrev1 = s.fPrev1;
        short[] hCurr = s.hCurr, eCurr = s.eCurr, fCurr = s.fCurr;

        final int filled = m + 2 + lanes;
        java.util.Arrays.fill(hPrev1, 0, filled, (short) 0);
        java.util.Arrays.fill(hPrev2, 0, filled, (short) 0);
        java.util.Arrays.fill(ePrev1, 0, filled, NEG);
        java.util.Arrays.fill(fPrev1, 0, filled, NEG);

        final ShortVector vExtend = ShortVector.broadcast(SPECIES, (short) scoring.gapExtend());
        final ShortVector vFirstGap = ShortVector.broadcast(SPECIES,
                (short) (scoring.gapOpen() + scoring.gapExtend()));
        final ShortVector vMatch = ShortVector.broadcast(SPECIES, (short) scoring.match());
        final ShortVector vMismatch = ShortVector.broadcast(SPECIES, (short) scoring.mismatch());
        final ShortVector vZero = ShortVector.zero(SPECIES);
        final ShortVector vNeg = ShortVector.broadcast(SPECIES, NEG);
        ShortVector vMax = vZero;

        for (int d = 2; d <= m + n; d++) {
            final int iFrom = Math.max(1, d - n);
            final int iTo = Math.min(m, d - 1);
            if (iFrom > iTo) continue;

            // subject[j-1] with j = d-i lives at reversed[n-d+i].
            final int base = n - d;

            for (int i = iFrom; i <= iTo; i += lanes) {
                ShortVector q = ShortVector.fromArray(SPECIES, queryLanes, i - 1);
                ShortVector t = ShortVector.fromArray(SPECIES, reversed, base + i);

                VectorMask<Short> equal = q.eq(t);
                ShortVector substitution = vMismatch.blend(vMatch, equal);

                ShortVector hLeft = ShortVector.fromArray(SPECIES, hPrev1, i);      // H[i][j-1]
                ShortVector eLeft = ShortVector.fromArray(SPECIES, ePrev1, i);      // E[i][j-1]
                ShortVector hUp = ShortVector.fromArray(SPECIES, hPrev1, i - 1);    // H[i-1][j]
                ShortVector fUp = ShortVector.fromArray(SPECIES, fPrev1, i - 1);    // F[i-1][j]
                ShortVector hDiag = ShortVector.fromArray(SPECIES, hPrev2, i - 1);  // H[i-1][j-1]

                ShortVector e = eLeft.add(vExtend).max(hLeft.add(vFirstGap));
                ShortVector f = fUp.add(vExtend).max(hUp.add(vFirstGap));
                ShortVector h = hDiag.add(substitution).max(e).max(f).max(vZero);

                e.intoArray(eCurr, i);
                f.intoArray(fCurr, i);
                h.intoArray(hCurr, i);
                vMax = vMax.max(h);
            }

            // Left border of the next anti-diagonal.
            hCurr[iFrom - 1] = 0;
            eCurr[iFrom - 1] = NEG;
            fCurr[iFrom - 1] = NEG;

            // Right border, one full vector wide: every cell an overhang lane can read
            // next pass holds exactly the boundary values, so overhang lanes reproduce
            // the border instead of reading whatever the last query left behind.
            vZero.intoArray(hCurr, iTo + 1);
            vNeg.intoArray(eCurr, iTo + 1);
            vNeg.intoArray(fCurr, iTo + 1);

            short[] swap = hPrev2; hPrev2 = hPrev1; hPrev1 = hCurr; hCurr = swap;
            short[] swapE = ePrev1; ePrev1 = eCurr; eCurr = swapE;
            short[] swapF = fPrev1; fPrev1 = fCurr; fCurr = swapF;
        }

        return Math.max(0, vMax.reduceLanes(VectorOperators.MAX));
    }

    @Override
    public ScoringScheme scoring() { return scoring; }

    @Override
    public String id() { return "gotoh-simd16-" + SPECIES.length() + "lane"; }
}
