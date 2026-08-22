package vdrst.service;

/**
 * One ranked result.
 *
 * <p>v1 returned a single integer it called a "similarity score (percentage)", computed
 * as {@code rawScore / (minLength * 2) * 100}. That number was not comparable between
 * queries, divided by a length that included gap characters, and assumed a match reward
 * of 2 while the prefilter that produced the candidates used 1. It is replaced here by
 * three quantities that each mean something specific. See RETROSPECTIVE.md finding 8.
 *
 * @param subjectId       accession of the matched sequence
 * @param title           description line from the database
 * @param subjectLength   length of the full subject sequence
 * @param alignmentScore  raw Smith-Waterman score under {@link vdrst.align.ScoringScheme#prefilter()}
 * @param normalizedScore alignmentScore as a fraction of the best score this query could
 *                        possibly achieve, in [0, 1]
 * @param bitScore        BLAST's bit score — normalised across scoring systems
 * @param eValue          BLAST's expectation value; lower is more significant
 * @param alignedLength   total aligned length across all HSPs
 */
public record Match(
        String subjectId,
        String title,
        int subjectLength,
        int alignmentScore,
        double normalizedScore,
        double bitScore,
        double eValue,
        int alignedLength) {
}
