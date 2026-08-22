package vdrst.index;

import vdrst.align.Nucleotides;

/**
 * A direct-addressed k-mer index over a {@link GenomeStore}, in CSR layout.
 *
 * <p>Replaces the {@code blastn} subprocess. The measurement that motivated it is in
 * README.md: of roughly 50 ms per prefiltered search, about 2.5 ms was spawning a
 * process, about 29 ms was the operating system loading the blastn binary and its
 * shared libraries <em>again</em>, about 9 ms was reopening the database, and the
 * similarity search itself did not register. Almost all of the latency was the cost of
 * starting a program, paid once per request.
 *
 * <h2>Layout</h2>
 * With {@code k = 11} there are 4^11 = 4,194,304 possible k-mers, so the k-mer itself is
 * the bucket number — no hashing, no collision handling, no {@code HashMap}, no boxing.
 * <pre>
 *   bucketStart[code]   first index in positions[] for this k-mer
 *   bucketStart[code+1] one past the last
 *   positions[]         global offsets into GenomeStore.bases(), ascending within a bucket
 * </pre>
 * Two flat {@code int[]}s. A lookup is one indexed read followed by a sequential walk,
 * which is the access pattern hardware is fastest at. The equivalent
 * {@code Map<Long, List<Integer>>} would be three dereferences and two probable cache
 * misses per lookup, plus a boxed {@code Integer} per position.
 *
 * <h2>Cost</h2>
 * {@code 4 * (4^k + 1)} bytes for the bucket table, plus 4 bytes per indexed position.
 * At {@code k = 11, stride = 1} that is about 16 MB fixed plus 4 bytes per base.
 * Raising {@code stride} indexes every n-th position, trading sensitivity for memory.
 *
 * <p>Instances are immutable once built and safe to query from any number of threads.
 */
public final class KmerIndex {

    /** 4^11 buckets is 16 MB of table — small enough to address directly, long enough to be selective. */
    public static final int DEFAULT_K = 11;

    private final int k;
    private final int stride;
    private final int[] bucketStart;
    private final int[] positions;
    private final GenomeStore store;

    private KmerIndex(int k, int stride, int[] bucketStart, int[] positions, GenomeStore store) {
        this.k = k;
        this.stride = stride;
        this.bucketStart = bucketStart;
        this.positions = positions;
        this.store = store;
    }

    public int k() { return k; }

    public int stride() { return stride; }

    public GenomeStore store() { return store; }

    public long indexedPositions() { return positions.length; }

    /** Approximate resident size of the index structures, in bytes. */
    public long approximateBytes() {
        return 4L * bucketStart.length + 4L * positions.length;
    }

    public static KmerIndex build(GenomeStore store) {
        return build(store, DEFAULT_K, 1);
    }

    /**
     * Two passes: count how many positions land in each bucket, prefix-sum the counts
     * into offsets, then fill. Counting first means {@code positions} is allocated once
     * at exactly the right size — no growth, no copying, and no per-bucket object.
     */
    public static KmerIndex build(GenomeStore store, int k, int stride) {
        if (k < 4 || k > 15) throw new IllegalArgumentException("k must be in [4, 15], got " + k);
        if (stride < 1) throw new IllegalArgumentException("stride must be >= 1, got " + stride);

        final byte[] bases = store.bases();
        final int buckets = 1 << (2 * k);
        final int mask = buckets - 1;
        final int[] starts = store.starts();

        int[] bucketStart = new int[buckets + 1];

        // Pass 1 — count.
        for (int g = 0; g < store.count(); g++) {
            int from = starts[g], to = starts[g + 1];
            int code = 0, valid = 0;
            for (int p = from; p < to; p++) {
                byte base = bases[p];
                if (base == Nucleotides.N) { valid = 0; code = 0; continue; }
                code = ((code << 2) | base) & mask;
                if (++valid >= k && (p - from) % stride == 0) bucketStart[code + 1]++;
            }
        }

        // Prefix sum — bucketStart[c] becomes the first slot for k-mer c.
        for (int c = 0; c < buckets; c++) bucketStart[c + 1] += bucketStart[c];

        int[] positions = new int[bucketStart[buckets]];
        int[] cursor = bucketStart.clone();

        // Pass 2 — fill. Positions land in ascending order within each bucket because
        // the genomes are walked in order, which keeps the query-side walk sequential.
        for (int g = 0; g < store.count(); g++) {
            int from = starts[g], to = starts[g + 1];
            int code = 0, valid = 0;
            for (int p = from; p < to; p++) {
                byte base = bases[p];
                if (base == Nucleotides.N) { valid = 0; code = 0; continue; }
                code = ((code << 2) | base) & mask;
                if (++valid >= k && (p - from) % stride == 0) {
                    positions[cursor[code]++] = p - k + 1;   // start of the k-mer
                }
            }
        }

        return new KmerIndex(k, stride, bucketStart, positions, store);
    }

    /** First index in {@link #positionsArray()} holding an occurrence of {@code code}. */
    public int bucketFrom(int code) { return bucketStart[code]; }

    /** One past the last index for {@code code}. */
    public int bucketTo(int code) { return bucketStart[code + 1]; }

    /** The backing position array. Exposed for the scan; treat as read-only. */
    public int[] positionsArray() { return positions; }

    /**
     * Encodes the k-mers of a query into {@code out}, using -1 where a k-mer spans an
     * ambiguous base and therefore cannot be looked up.
     *
     * @param query encoded query sequence
     * @param out   destination, length {@code query.length - k + 1}
     * @return the number of codes written, which is always {@code out.length}
     */
    public int encodeQueryKmers(byte[] query, int[] out) {
        final int mask = (1 << (2 * k)) - 1;
        int code = 0, valid = 0;

        for (int i = 0; i < query.length; i++) {
            byte base = query[i];
            if (base == Nucleotides.N) { valid = 0; code = 0; }
            else { code = ((code << 2) | base) & mask; valid++; }

            int kmerIndex = i - k + 1;
            if (kmerIndex >= 0) out[kmerIndex] = valid >= k ? code : -1;
        }
        return Math.max(0, query.length - k + 1);
    }
}
