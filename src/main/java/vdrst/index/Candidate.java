package vdrst.index;

/**
 * A subject sequence that survived the prefilter, together with the region of it worth
 * aligning against.
 *
 * <p>Carrying the subject bases here is what lets the two prefilters be interchangeable:
 * the in-process index takes a window out of the genome store, and the BLAST prefilter
 * takes the segment BLAST already aligned. The re-ranking stage does not need to know
 * which one produced it.
 *
 * @param subjectId     accession of the matched sequence
 * @param title         description from the database
 * @param subjectLength length of the full subject sequence
 * @param windowStart   offset within the subject where {@code subjectBases} begins
 * @param subjectBases  encoded bases to align against
 * @param seedHits      how much evidence the prefilter found; higher is stronger
 */
public record Candidate(
        String subjectId,
        String title,
        int subjectLength,
        int windowStart,
        byte[] subjectBases,
        int seedHits) {
}
