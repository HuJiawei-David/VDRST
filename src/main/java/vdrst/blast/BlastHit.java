package vdrst.blast;

import java.util.List;

/**
 * One subject sequence returned by the prefilter, with the high-scoring segment pairs
 * BLAST found against it.
 */
public record BlastHit(String subjectId, String title, int subjectLength, List<Hsp> hsps) {

    public BlastHit {
        hsps = List.copyOf(hsps);
    }

    /** Total aligned length across all HSPs — the input size the re-ranking stage actually sees. */
    public int alignedLength() {
        int total = 0;
        for (Hsp hsp : hsps) total += hsp.queryAligned().length();
        return total;
    }

    public double bestBitScore() {
        double best = 0;
        for (Hsp hsp : hsps) best = Math.max(best, hsp.bitScore());
        return best;
    }
}
