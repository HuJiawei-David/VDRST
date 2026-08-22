package vdrst.align;

/**
 * Computes the optimal local (Smith-Waterman) alignment score between two encoded
 * nucleotide sequences.
 *
 * <p>Every implementation in this package must return an identical score for identical
 * input under the same {@link ScoringScheme}; {@code AlignerEquivalenceTest} enforces
 * that against randomised input. The implementations differ only in time and space.
 */
public interface Aligner {

    /**
     * @param query   encoded query, see {@link Nucleotides#encode}
     * @param subject encoded subject
     * @return the highest-scoring local alignment score, never negative
     */
    int score(byte[] query, byte[] subject);

    ScoringScheme scoring();

    default String id() {
        return getClass().getSimpleName();
    }
}
