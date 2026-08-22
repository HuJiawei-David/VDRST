package vdrst.blast;

/**
 * A high-scoring segment pair: one locally aligned stretch of query against subject.
 *
 * <p>{@code queryAligned} and {@code subjectAligned} are equal-length strings that may
 * contain '-' for gaps, exactly as BLAST emits them.
 */
public record Hsp(
        String queryAligned,
        String subjectAligned,
        int queryStart, int queryEnd,
        int subjectStart, int subjectEnd,
        double bitScore,
        double eValue) {

    public Hsp {
        if (queryAligned.length() != subjectAligned.length()) {
            throw new IllegalArgumentException(
                    "aligned segments must be the same length: query=" + queryAligned.length()
                            + " subject=" + subjectAligned.length());
        }
    }

    /** The aligned query with gap characters removed, ready for re-scoring. */
    public String queryUngapped() {
        return queryAligned.replace("-", "");
    }

    public String subjectUngapped() {
        return subjectAligned.replace("-", "");
    }
}
