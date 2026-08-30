package vdrst.index;

import vdrst.align.Nucleotides;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every genome in the database concatenated into one flat {@code byte[]}, with an
 * offset table marking the boundaries.
 *
 * <p>The obvious shape for this is {@code List<Genome>} where each genome owns its own
 * array. That shape costs a pointer dereference and a probable cache miss per genome,
 * and scatters the data the scan is about to walk across the heap. One contiguous
 * buffer walked front to back is what the hardware prefetcher is built for, and it is
 * the difference between the index build being memory-bound at streaming speed and
 * being bound by pointer chasing.
 *
 * <p>Positions are global offsets into {@link #bases()}. Resolving one back to a genome
 * is a binary search over {@link #starts()}, which is deliberately kept off the hot path:
 * the scan accumulates evidence in global coordinates and resolves only the handful of
 * candidates that survive.
 */
public final class GenomeStore {

    private final byte[] bases;
    private final int[] starts;      // starts[i] .. starts[i+1] is genome i; length count+1
    private final String[] ids;
    private final String[] titles;
    private final long ambiguityCodes;

    private GenomeStore(byte[] bases, int[] starts, String[] ids, String[] titles,
                        long ambiguityCodes) {
        this.bases = bases;
        this.starts = starts;
        this.ids = ids;
        this.titles = titles;
        this.ambiguityCodes = ambiguityCodes;
    }

    public byte[] bases() { return bases; }

    public int[] starts() { return starts; }

    public int count() { return ids.length; }

    public long totalBases() { return bases.length; }

    public String id(int genome) { return ids[genome]; }

    public String title(int genome) { return titles[genome]; }

    public int start(int genome) { return starts[genome]; }

    public int length(int genome) { return starts[genome + 1] - starts[genome]; }

    /** @return the index of the genome containing {@code globalPosition} */
    public int genomeAt(int globalPosition) {
        int low = 0, high = ids.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (starts[mid] <= globalPosition) low = mid; else high = mid - 1;
        }
        return low;
    }

    /**
     * IUPAC ambiguity codes — R, Y, S, W, K, M, B, D, H, V — folded to N, everything the
     * search understands mapped to its code, whitespace marked to skip, and everything
     * else marked invalid. Real reference databases contain ambiguity codes (NCBI's
     * ref_viruses_rep_genomes has a few hundred), so a database-side loader that rejects
     * them cannot load real data; folding them to N keeps them out of the index without
     * pretending to know which base they were. Query-side validation stays strict.
     */
    private static final byte SKIP = -2, INVALID = -1;
    private static final byte[] BASE_CODES = new byte[256];

    static {
        java.util.Arrays.fill(BASE_CODES, INVALID);
        for (String pair : new String[]{"Aa", "Cc", "Gg", "Tt", "Uu"}) {
            byte code = Nucleotides.encode(pair.substring(0, 1))[0];
            BASE_CODES[pair.charAt(0)] = code;
            BASE_CODES[pair.charAt(1)] = code;
        }
        for (char c : "NnRrYySsWwKkMmBbDdHhVv".toCharArray()) BASE_CODES[c] = Nucleotides.N;
        for (char c : new char[]{' ', '\t', '\r', '\n'}) BASE_CODES[c] = SKIP;
    }

    /** IUPAC ambiguity bases folded to N while loading; zero for a clean corpus. */
    public long ambiguityCodes() { return ambiguityCodes; }

    /**
     * Loads a FASTA file by streaming raw bytes through one 256-entry table. An earlier
     * version of this method went through {@code String.valueOf(char)} for every single
     * base, which on a 552-megabase database meant 552 million throwaway strings before
     * the first query could run. Characters that are not nucleotides, ambiguity codes or
     * whitespace are still rejected rather than silently coerced.
     */
    public static GenomeStore load(Path fasta) throws IOException {
        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Integer> startList = new ArrayList<>();

        // Sized generously; a viral database is comfortably under 2 GB of bases.
        byte[] bases = new byte[Math.toIntExact(Math.min(Files.size(fasta), Integer.MAX_VALUE - 8))];
        int written = 0;
        long ambiguous = 0;

        byte[] buffer = new byte[1 << 20];
        java.io.ByteArrayOutputStream header = null;

        try (InputStream in = Files.newInputStream(fasta)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];

                    if (header != null) {                    // inside a header line
                        if (b == '\n') {
                            recordHeader(header, ids, titles);
                            header = null;
                        } else if (b != '\r') {
                            header.write(b);
                        }
                        continue;
                    }
                    if (b == '>') {
                        startList.add(written);
                        header = new java.io.ByteArrayOutputStream(64);
                        continue;
                    }

                    byte code = BASE_CODES[b & 0xFF];
                    if (code == SKIP) continue;
                    if (code == INVALID) {
                        throw new IllegalArgumentException("unreadable character '"
                                + (char) (b & 0xFF) + "' in record " + ids.size() + " of " + fasta);
                    }
                    if (code == Nucleotides.N && b != 'N' && b != 'n') ambiguous++;
                    bases[written++] = code;
                }
            }
        }
        if (header != null) recordHeader(header, ids, titles);   // file ended inside a header

        startList.add(written);
        int[] starts = new int[startList.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = startList.get(i);

        return new GenomeStore(
                java.util.Arrays.copyOf(bases, written),
                starts,
                ids.toArray(new String[0]),
                titles.toArray(new String[0]),
                ambiguous);
    }

    private static void recordHeader(java.io.ByteArrayOutputStream header,
                                     List<String> ids, List<String> titles) {
        String line = header.toString(StandardCharsets.UTF_8);
        int space = line.indexOf(' ');
        ids.add(space < 0 ? line : line.substring(0, space));
        titles.add(space < 0 ? line : line.substring(space + 1));
    }
}
