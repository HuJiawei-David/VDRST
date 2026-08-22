package vdrst.index;

/**
 * Counts seed hits per alignment diagonal, using an open-addressed table of primitives.
 *
 * <p>A seed at query offset {@code q} matching subject offset {@code p} lies on diagonal
 * {@code p - q}. An ungapped alignment puts every one of its seeds on the same diagonal,
 * so a diagonal carrying many seeds is a candidate and a scatter of singletons is noise.
 * Diagonals are binned so that a small indel, which shifts later seeds by a few positions,
 * does not split one alignment across two bins.
 *
 * <h2>Why not a HashMap</h2>
 * {@code HashMap<Integer, Integer>} would box two objects per distinct diagonal and chase
 * a pointer per probe. This is two {@code int[]}s and a linear probe, so a lookup touches
 * one or two cache lines and allocates nothing.
 *
 * <h2>Why the generation counter</h2>
 * Clearing the table between queries would cost a full pass over the capacity every time,
 * which for a table sized to the worst case dominates the queries that are not the worst
 * case. Instead each slot records the generation that wrote it; a slot from an older
 * generation is free. {@link #reset()} is one increment.
 *
 * <p>Not thread-safe by design — each searching thread holds its own instance. Sharing one
 * would reintroduce, in miniature, the shared-mutable-state defect that made v1's searches
 * corrupt each other.
 */
public final class DiagonalAccumulator {

    /** Diagonals are binned in groups of 16, so indels up to 15 bases keep their seeds together. */
    public static final int DIAGONAL_BIN_SHIFT = 4;

    private final int mask;
    private final int[] keys;
    private final int[] counts;
    private final int[] generations;
    private final int[] bestQueryOffset;

    private int generation;
    private int occupied;

    /**
     * @param expectedDiagonals upper bound on distinct diagonals per query; the table is
     *                          sized to twice the next power of two above this, keeping
     *                          the load factor under 0.5 where linear probing stays flat
     */
    public DiagonalAccumulator(int expectedDiagonals) {
        int capacity = Integer.highestOneBit(Math.max(16, expectedDiagonals) - 1) << 2;
        this.mask = capacity - 1;
        this.keys = new int[capacity];
        this.counts = new int[capacity];
        this.generations = new int[capacity];
        this.bestQueryOffset = new int[capacity];
    }

    public int capacity() { return keys.length; }

    public int occupiedSlots() { return occupied; }

    /** Discards every entry in constant time. */
    public void reset() {
        generation++;
        occupied = 0;
    }

    /**
     * Records one seed hit.
     *
     * @param subjectPosition global offset of the seed in the genome store
     * @param queryOffset     offset of the seed within the query
     */
    public void record(int subjectPosition, int queryOffset) {
        int diagonal = (subjectPosition - queryOffset) >> DIAGONAL_BIN_SHIFT;
        int slot = mix(diagonal) & mask;

        while (true) {
            if (generations[slot] != generation) {           // free slot
                generations[slot] = generation;
                keys[slot] = diagonal;
                counts[slot] = 1;
                bestQueryOffset[slot] = queryOffset;
                occupied++;
                return;
            }
            if (keys[slot] == diagonal) {
                counts[slot]++;
                if (queryOffset < bestQueryOffset[slot]) bestQueryOffset[slot] = queryOffset;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** True when this slot holds an entry written during the current generation. */
    public boolean isLive(int slot) { return generations[slot] == generation; }

    public int diagonalAt(int slot) { return keys[slot] << DIAGONAL_BIN_SHIFT; }

    public int countAt(int slot) { return counts[slot]; }

    public int queryOffsetAt(int slot) { return bestQueryOffset[slot]; }

    /** Fibonacci hashing — cheap, and spreads sequential diagonals across the table. */
    private static int mix(int value) {
        int h = value * 0x9E3779B9;
        return h ^ (h >>> 15);
    }
}
