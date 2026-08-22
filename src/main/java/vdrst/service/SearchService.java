package vdrst.service;

import vdrst.align.Aligner;
import vdrst.align.GotohAligner;
import vdrst.align.Nucleotides;
import vdrst.align.ScoringScheme;
import vdrst.blast.BlastHit;
import vdrst.blast.BlastRunner;
import vdrst.blast.Hsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The two-stage search: a BLAST prefilter narrows the database to a handful of
 * candidates, then every candidate is re-scored with an exact Smith-Waterman alignment
 * and the best are returned.
 *
 * <p>The shape is v1's. What changed is that both stages now use the same scoring
 * scheme, the re-ranking respects HSP boundaries, and the class holds no mutable state.
 */
public final class SearchService {

    /** How many results the caller gets back. v1 hard-coded 3. */
    public static final int DEFAULT_RESULT_LIMIT = 3;

    private final BlastRunner blast;
    private final Aligner aligner;
    private final int resultLimit;

    public SearchService(BlastRunner blast) {
        this(blast, new GotohAligner(ScoringScheme.prefilter()), DEFAULT_RESULT_LIMIT);
    }

    public SearchService(BlastRunner blast, Aligner aligner, int resultLimit) {
        this.blast = java.util.Objects.requireNonNull(blast, "blast");
        this.aligner = java.util.Objects.requireNonNull(aligner, "aligner");
        if (resultLimit < 1) throw new IllegalArgumentException("resultLimit must be >= 1");
        this.resultLimit = resultLimit;
    }

    /**
     * @param rawSequence the sequence exactly as submitted
     * @return up to {@code resultLimit} matches, best first
     * @throws SequenceValidator.InvalidRequestException if the sequence is not usable
     */
    public List<Match> search(String rawSequence) {
        String sequence = SequenceValidator.validate(rawSequence);
        byte[] query = Nucleotides.encode(sequence);

        List<BlastHit> candidates = blast.search(sequence);
        List<Match> matches = new ArrayList<>(candidates.size());

        // The best score this query could attain against a perfect copy of itself.
        // Dividing by this makes scores comparable between queries of different lengths,
        // which v1's percentage was not.
        int bestPossible = query.length * aligner.scoring().match();

        for (BlastHit hit : candidates) {
            int best = 0;
            double bestBits = 0, bestEValue = Double.MAX_VALUE;

            // Each HSP is a separate local alignment. v1 concatenated the HSP fragments
            // of a hit and aligned the concatenations, which manufactures alignments
            // across junctions that exist in neither sequence. See finding 9.
            for (Hsp hsp : hit.hsps()) {
                byte[] q = Nucleotides.encode(hsp.queryUngapped());
                byte[] s = Nucleotides.encode(hsp.subjectUngapped());
                int score = aligner.score(q, s);
                if (score > best) {
                    best = score;
                    bestBits = hsp.bitScore();
                    bestEValue = hsp.eValue();
                }
            }

            matches.add(new Match(
                    hit.subjectId(), hit.title(), hit.subjectLength(),
                    best,
                    bestPossible == 0 ? 0 : (double) best / bestPossible,
                    bestBits,
                    bestEValue == Double.MAX_VALUE ? Double.NaN : bestEValue,
                    hit.alignedLength()));
        }

        matches.sort(Comparator
                .comparingInt(Match::alignmentScore).reversed()
                .thenComparing(Match::eValue, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Match::subjectId));

        return matches.size() <= resultLimit ? List.copyOf(matches) : List.copyOf(matches.subList(0, resultLimit));
    }

    public Aligner aligner() { return aligner; }
}
