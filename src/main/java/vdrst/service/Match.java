package vdrst.service;

/**
 * One ranked result.
 *
 * <p>v1 returned a single integer it called a similarity percentage, computed as
 * {@code rawScore / (minLength * 2) * 100}. Three things were wrong with it: the length
 * it divided by was measured on strings that still contained gap characters; the 2
 * assumed a match reward of 2 while the prefilter that produced the candidate used 1; and
 * the result was not comparable between two queries of different lengths, so the number a
 * user saw meant nothing on its own. See RETROSPECTIVE.md finding 8.
 *
 * @param subjectId       accession of the matched sequence
 * @param title           description from the database
 * @param subjectLength   length of the full subject sequence
 * @param alignmentScore  raw Smith-Waterman score under the prefilter's scoring scheme
 * @param normalizedScore alignmentScore as a fraction of the best score this query could
 *                        attain against a perfect copy of itself, in [0, 1]
 * @param subjectOffset   where in the subject the aligned region begins
 * @param seedHits        how much evidence the prefilter found for this candidate
 */
public record Match(
        String subjectId,
        String title,
        int subjectLength,
        int alignmentScore,
        double normalizedScore,
        int subjectOffset,
        int seedHits) {
}
