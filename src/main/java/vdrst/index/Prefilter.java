package vdrst.index;

import java.util.List;

/**
 * Narrows the database to a small set of candidates worth aligning exactly.
 *
 * <p>This is the interface that makes the project's central claim testable. The
 * prefilter is where the speedup comes from — reducing the number of sequences the
 * O(mn) alignment has to touch, from every genome in the database to a couple of dozen.
 * Having two implementations behind one interface means the fast one can be checked
 * against the reference one on real data rather than asserted to be equivalent.
 */
public interface Prefilter extends AutoCloseable {

    /**
     * @param query encoded query sequence
     * @param limit maximum candidates to return
     * @return candidates in descending order of prefilter evidence
     */
    List<Candidate> candidates(byte[] query, int limit);

    /** Short identifier used in benchmark output and logs. */
    String id();

    @Override
    void close();
}
