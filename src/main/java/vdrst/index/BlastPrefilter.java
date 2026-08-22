package vdrst.index;

import vdrst.align.Nucleotides;
import vdrst.blast.BlastHit;
import vdrst.blast.BlastRunner;
import vdrst.blast.Hsp;

import java.util.ArrayList;
import java.util.List;

/**
 * The original prefilter — {@code blastn} in a subprocess — behind the {@link Prefilter}
 * interface.
 *
 * <p>Kept for two reasons, neither of them backwards compatibility. First, it is the
 * reference the in-process index is checked against: NCBI BLAST is thirty years of other
 * people's work on this exact problem, and "my index finds what BLAST finds" is a much
 * stronger claim than "my index finds the answers I planted". Second, it is the baseline
 * in the benchmark that shows what removing the subprocess was worth.
 *
 * <p>It is not the default. On the reference corpus it costs roughly 40 ms per search, of
 * which about 29 ms is the operating system loading the blastn binary and its shared
 * libraries and about 9 ms is reopening the database — repeated identically on every
 * request, and none of it the similarity search.
 */
public final class BlastPrefilter implements Prefilter {

    private final BlastRunner runner;

    public BlastPrefilter(BlastRunner runner) {
        this.runner = runner;
    }

    @Override
    public List<Candidate> candidates(byte[] query, int limit) {
        List<BlastHit> hits = runner.search(Nucleotides.decode(query));
        List<Candidate> candidates = new ArrayList<>(Math.min(limit, hits.size()));

        for (BlastHit hit : hits) {
            if (candidates.size() == limit) break;

            // BLAST reports each HSP as an aligned pair. The best one stands for the hit;
            // v1 concatenated them all and aligned the concatenation, which manufactures
            // alignments across junctions present in neither sequence.
            Hsp best = null;
            for (Hsp hsp : hit.hsps()) {
                if (best == null || hsp.bitScore() > best.bitScore()) best = hsp;
            }
            if (best == null) continue;

            candidates.add(new Candidate(
                    hit.subjectId(), hit.title(), hit.subjectLength(),
                    Math.min(best.subjectStart(), best.subjectEnd()) - 1,
                    Nucleotides.encode(best.subjectUngapped()),
                    hit.hsps().size()));
        }
        return candidates;
    }

    public BlastRunner runner() { return runner; }

    @Override
    public String id() { return "blastn-subprocess"; }

    @Override
    public void close() { runner.close(); }
}
