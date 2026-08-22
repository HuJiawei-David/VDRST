package vdrst.service;

import vdrst.align.Aligner;
import vdrst.align.Nucleotides;
import vdrst.align.VectorGotohAligner;
import vdrst.index.Candidate;
import vdrst.index.Prefilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The two-stage search: a prefilter narrows the database to a handful of candidates, then
 * each candidate is re-scored with an exact alignment and the best are returned.
 *
 * <p>That shape is v1's, and it was the right idea — the speedup this project is named
 * for comes from it. What changed underneath is which prefilter runs, that both stages
 * now agree on what "similar" means, and that the numbers handed back have definitions.
 *
 * <p>The class holds no mutable state; the prefilter and aligner it delegates to are each
 * documented as safe to share. v1's equivalent held a hard-coded working file, which is
 * why two people searching at once got each other's results.
 */
public final class SearchService {

    /** Results returned to the caller. v1 hard-coded 3. */
    public static final int DEFAULT_RESULT_LIMIT = 3;

    /**
     * Candidates the prefilter is asked for. Everything past this is never aligned, so it
     * is the single knob that trades recall against latency — and the reason the whole
     * pipeline is fast. v1 used 10, then 20.
     */
    public static final int DEFAULT_CANDIDATE_LIMIT = 20;

    private final Prefilter prefilter;
    private final Aligner aligner;
    private final int resultLimit;
    private final int candidateLimit;

    public SearchService(Prefilter prefilter) {
        this(prefilter, new VectorGotohAligner(), DEFAULT_RESULT_LIMIT, DEFAULT_CANDIDATE_LIMIT);
    }

    public SearchService(Prefilter prefilter, Aligner aligner, int resultLimit, int candidateLimit) {
        this.prefilter = java.util.Objects.requireNonNull(prefilter, "prefilter");
        this.aligner = java.util.Objects.requireNonNull(aligner, "aligner");
        if (resultLimit < 1) throw new IllegalArgumentException("resultLimit must be >= 1");
        if (candidateLimit < resultLimit) {
            throw new IllegalArgumentException("candidateLimit must be at least resultLimit");
        }
        this.resultLimit = resultLimit;
        this.candidateLimit = candidateLimit;
    }

    /**
     * @param rawSequence the sequence exactly as submitted
     * @return up to {@code resultLimit} matches, best first
     * @throws SequenceValidator.InvalidRequestException if the sequence is not usable
     */
    public List<Match> search(String rawSequence) {
        String sequence = SequenceValidator.validate(rawSequence);
        byte[] query = Nucleotides.encode(sequence);

        List<Candidate> candidates = prefilter.candidates(query, candidateLimit);
        List<Match> matches = new ArrayList<>(candidates.size());

        // The score this query would earn against a perfect copy of itself. Dividing by it
        // makes results comparable between queries of different lengths, which is what
        // v1's "percentage" was not: that one divided by a length counted from strings
        // containing gap characters, using a match reward the prefilter never used.
        final int bestPossible = query.length * aligner.scoring().match();

        for (Candidate candidate : candidates) {
            int score = aligner.score(query, candidate.subjectBases());
            matches.add(new Match(
                    candidate.subjectId(),
                    candidate.title(),
                    candidate.subjectLength(),
                    score,
                    bestPossible == 0 ? 0 : (double) score / bestPossible,
                    candidate.windowStart(),
                    candidate.seedHits()));
        }

        matches.sort(Comparator
                .comparingInt(Match::alignmentScore).reversed()
                .thenComparing(Match::subjectId));

        return matches.size() <= resultLimit
                ? List.copyOf(matches)
                : List.copyOf(matches.subList(0, resultLimit));
    }

    public Aligner aligner() { return aligner; }

    public Prefilter prefilter() { return prefilter; }
}
