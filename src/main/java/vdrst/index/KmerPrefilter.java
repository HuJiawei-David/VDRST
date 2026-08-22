package vdrst.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Seed-and-extend prefilter running inside the process.
 *
 * <p>The replacement for launching {@code blastn} per request. Measured on the reference
 * corpus, that subprocess cost about 40 ms of which roughly 29 ms was the operating
 * system loading the blastn binary and its libraries and about 9 ms was reopening the
 * database — work repeated identically on every single search, and none of it the
 * similarity search itself.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Encode every k-mer of the query.</li>
 *   <li>Look each one up in {@link KmerIndex} and record its hits by diagonal.</li>
 *   <li>Diagonals carrying the most seeds become candidates.</li>
 *   <li>Take a window of the genome around each surviving diagonal, wide enough to
 *       contain the alignment plus room for indels.</li>
 * </ol>
 *
 * <h2>Bounded work</h2>
 * A k-mer occurring more than {@link #maxOccurrences} times in the database is a repeat:
 * it appears everywhere, so it distinguishes nothing, and following its hit list is the
 * one way this scan could degrade badly. Skipping those k-mers costs a little sensitivity
 * on low-complexity queries and buys a hard ceiling on per-query work — which is what
 * makes the tail latency predictable rather than merely usually good.
 *
 * <h2>Allocation</h2>
 * The per-query scratch — the k-mer buffer and the diagonal table — is held per thread
 * and reused, so a steady-state search allocates only its result list. Allocation on the
 * hot path does not slow down the request that allocates; it slows down some later
 * request that happens to be running when the collector does. That is exactly the shape
 * of tail latency that averages hide.
 *
 * <p>Thread-safe: the index is immutable and every thread gets its own scratch.
 */
public final class KmerPrefilter implements Prefilter {

    /** k-mers more common than this are treated as repeats and skipped. */
    public static final int DEFAULT_MAX_OCCURRENCES = 512;

    /** Extra subject bases either side of a diagonal, to leave room for indels. */
    public static final int WINDOW_MARGIN = 64;

    /** A diagonal needs at least this many seeds before it is worth aligning. */
    public static final int MIN_SEEDS = 2;

    private final KmerIndex index;
    private final int maxOccurrences;

    private final ThreadLocal<Scratch> scratch;

    public KmerPrefilter(KmerIndex index) {
        this(index, DEFAULT_MAX_OCCURRENCES);
    }

    public KmerPrefilter(KmerIndex index, int maxOccurrences) {
        this.index = index;
        this.maxOccurrences = maxOccurrences;
        this.scratch = ThreadLocal.withInitial(Scratch::new);
    }

    private static final class Scratch {
        int[] kmers = new int[1024];
        final DiagonalAccumulator diagonals;

        Scratch() {
            // Sized for the worst case a bounded scan can produce: every query k-mer
            // landing on its own diagonal, at the occurrence ceiling.
            this.diagonals = new DiagonalAccumulator(4096);
        }

        int[] kmers(int needed) {
            if (kmers.length < needed) kmers = new int[Integer.highestOneBit(needed - 1) << 1];
            return kmers;
        }
    }

    @Override
    public List<Candidate> candidates(byte[] query, int limit) {
        final int k = index.k();
        if (query.length < k) return List.of();

        Scratch local = scratch.get();
        int kmerCount = query.length - k + 1;
        int[] kmers = local.kmers(kmerCount);
        index.encodeQueryKmers(query, kmers);

        DiagonalAccumulator diagonals = local.diagonals;
        diagonals.reset();

        final int[] positions = index.positionsArray();
        for (int q = 0; q < kmerCount; q++) {
            int code = kmers[q];
            if (code < 0) continue;                        // spans an ambiguous base

            int from = index.bucketFrom(code), to = index.bucketTo(code);
            if (to - from > maxOccurrences) continue;      // repeat; carries no signal

            for (int i = from; i < to; i++) {
                diagonals.record(positions[i], q);
            }
        }

        return selectBest(query, diagonals, limit);
    }

    /**
     * Walks the accumulator, keeps the strongest diagonal per genome, and returns the
     * best few. Genome resolution is a binary search, so it happens here — once per
     * surviving diagonal — rather than once per seed hit.
     */
    private List<Candidate> selectBest(byte[] query, DiagonalAccumulator diagonals, int limit) {
        GenomeStore store = index.store();

        record Hit(int genome, int diagonal, int count, int queryOffset) {}
        List<Hit> hits = new ArrayList<>();

        for (int slot = 0; slot < diagonals.capacity(); slot++) {
            if (!diagonals.isLive(slot) || diagonals.countAt(slot) < MIN_SEEDS) continue;

            int diagonal = diagonals.diagonalAt(slot);
            int anchor = diagonal + diagonals.queryOffsetAt(slot);
            if (anchor < 0 || anchor >= store.totalBases()) continue;

            hits.add(new Hit(store.genomeAt(anchor), diagonal,
                    diagonals.countAt(slot), diagonals.queryOffsetAt(slot)));
        }

        hits.sort((a, b) -> Integer.compare(b.count(), a.count()));

        List<Candidate> candidates = new ArrayList<>(Math.min(limit, hits.size()));
        boolean[] seen = new boolean[store.count()];

        for (Hit hit : hits) {
            if (candidates.size() == limit) break;
            if (seen[hit.genome()]) continue;
            seen[hit.genome()] = true;

            int genomeStart = store.start(hit.genome());
            int genomeLength = store.length(hit.genome());

            int windowStart = hit.diagonal() - genomeStart - WINDOW_MARGIN;
            int windowEnd = windowStart + query.length + 2 * WINDOW_MARGIN;
            windowStart = Math.max(0, windowStart);
            windowEnd = Math.min(genomeLength, windowEnd);
            if (windowEnd <= windowStart) continue;

            byte[] window = Arrays.copyOfRange(
                    store.bases(), genomeStart + windowStart, genomeStart + windowEnd);

            candidates.add(new Candidate(
                    store.id(hit.genome()), store.title(hit.genome()),
                    genomeLength, windowStart, window, hit.count()));
        }

        return candidates;
    }

    public KmerIndex index() { return index; }

    @Override
    public String id() { return "kmer-index-k" + index.k(); }

    @Override
    public void close() {
        // Nothing to release: the index is plain heap and the scratch dies with its thread.
    }
}
