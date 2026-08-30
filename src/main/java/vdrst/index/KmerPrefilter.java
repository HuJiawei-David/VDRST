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
        final int genomes = index.store().count();
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(genomes));
    }

    private static final class Scratch {
        int[] kmers = new int[1024];
        final DiagonalAccumulator diagonals = new DiagonalAccumulator(4096);

        // Qualifying hits, dense: hitKeys[i] packs (count, arrival order) for sorting,
        // hitSlots[order] remembers which accumulator slot the order-th hit came from.
        long[] hitKeys = new long[1024];
        int[] hitSlots = new int[1024];

        // Which genomes this query has already returned, without allocating per query:
        // a slot from an older generation reads as unseen, exactly like the accumulator.
        final int[] genomeSeen;
        int genomeGeneration;

        Scratch(int genomes) {
            this.genomeSeen = new int[genomes];
        }

        int[] kmers(int needed) {
            if (kmers.length < needed) kmers = new int[Integer.highestOneBit(needed - 1) << 1];
            return kmers;
        }

        void sizeHits(int needed) {
            if (hitKeys.length < needed) {
                int size = Integer.highestOneBit(needed - 1) << 1;
                hitKeys = new long[size];
                hitSlots = new int[size];
            }
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

        return selectBest(query, local, limit);
    }

    /**
     * Walks the live diagonals, keeps the strongest per genome, and returns the best few.
     *
     * <p>Everything here is a constant factor, and all of them used to be paid per query:
     * a scan over the accumulator's whole capacity rather than its live entries, a record
     * allocated per surviving diagonal, a comparator sort over those records, and a fresh
     * {@code boolean[]} the size of the genome count for de-duplication — 18 KB per query
     * against a real database. Hits are now packed into reused {@code long}s, sorted as
     * primitives, and walked from the top; genome resolution stays a binary search but
     * happens only for the walked prefix, not for every surviving diagonal.
     */
    private List<Candidate> selectBest(byte[] query, Scratch local, int limit) {
        final GenomeStore store = index.store();
        final DiagonalAccumulator diagonals = local.diagonals;
        final int live = diagonals.occupiedSlots();

        local.sizeHits(live);
        final long[] hitKeys = local.hitKeys;
        final int[] hitSlots = local.hitSlots;
        int n = 0;

        for (int i = 0; i < live; i++) {
            int slot = diagonals.liveSlotAt(i);
            if (diagonals.countAt(slot) < MIN_SEEDS) continue;

            // Sorts ascending as (count, then later arrivals first), so the descending
            // walk below sees higher counts first and, within a count, earlier arrivals
            // first — the same order the old stable sort produced.
            hitKeys[n] = ((long) diagonals.countAt(slot) << 32) | (0xFFFF_FFFFL - n);
            hitSlots[n] = slot;
            n++;
        }

        Arrays.sort(hitKeys, 0, n);

        List<Candidate> candidates = new ArrayList<>(Math.min(limit, n));
        final int generation = ++local.genomeGeneration;

        for (int i = n - 1; i >= 0 && candidates.size() < limit; i--) {
            int slot = hitSlots[(int) (0xFFFF_FFFFL - (hitKeys[i] & 0xFFFF_FFFFL))];
            int diagonal = diagonals.diagonalAt(slot);

            // The genome comes from a real seed position, never from the binned diagonal:
            // the bin rounds down by up to 15, and an alignment starting that close to a
            // genome boundary would resolve into the wrong genome. Found, not foreseen —
            // an HIV-1 query against the real database came back attributed to the
            // neighbouring record, because its match begins at position 0 of the genome.
            int genome = store.genomeAt(diagonals.anchorAt(slot));

            if (local.genomeSeen[genome] == generation) continue;
            local.genomeSeen[genome] = generation;

            int genomeStart = store.start(genome);
            int genomeLength = store.length(genome);

            int windowStart = diagonal - genomeStart - WINDOW_MARGIN;
            int windowEnd = windowStart + query.length + 2 * WINDOW_MARGIN;
            windowStart = Math.max(0, windowStart);
            windowEnd = Math.min(genomeLength, windowEnd);
            if (windowEnd <= windowStart) continue;

            byte[] window = Arrays.copyOfRange(
                    store.bases(), genomeStart + windowStart, genomeStart + windowEnd);

            candidates.add(new Candidate(
                    store.id(genome), store.title(genome),
                    genomeLength, windowStart, window, diagonals.countAt(slot)));
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
