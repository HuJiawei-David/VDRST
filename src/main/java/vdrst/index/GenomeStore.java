package vdrst.index;

import vdrst.align.Nucleotides;

import java.io.BufferedReader;
import java.io.IOException;
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

    private GenomeStore(byte[] bases, int[] starts, String[] ids, String[] titles) {
        this.bases = bases;
        this.starts = starts;
        this.ids = ids;
        this.titles = titles;
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
     * Loads a FASTA file. Sequences containing characters outside A/C/G/T/U/N are
     * rejected rather than silently coerced.
     */
    public static GenomeStore load(Path fasta) throws IOException {
        List<String> ids = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Integer> startList = new ArrayList<>();

        // Sized generously; a viral database is comfortably under 2 GB of bases.
        byte[] bases = new byte[Math.toIntExact(Math.min(Files.size(fasta), Integer.MAX_VALUE - 8))];
        int written = 0;

        try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '>') {
                    startList.add(written);
                    String header = line.substring(1);
                    int space = header.indexOf(' ');
                    ids.add(space < 0 ? header : header.substring(0, space));
                    titles.add(space < 0 ? header : header.substring(space + 1));
                } else {
                    for (int i = 0; i < line.length(); i++) {
                        char c = line.charAt(i);
                        if (c == ' ' || c == '\t' || c == '\r') continue;
                        bases[written++] = encodeOrThrow(c, fasta, ids.size());
                    }
                }
            }
        }

        startList.add(written);
        int[] starts = new int[startList.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = startList.get(i);

        return new GenomeStore(
                java.util.Arrays.copyOf(bases, written),
                starts,
                ids.toArray(new String[0]),
                titles.toArray(new String[0]));
    }

    private static byte encodeOrThrow(char c, Path fasta, int record) {
        byte[] single = Nucleotides.encode(String.valueOf(c));
        if (single.length != 1) {
            throw new IllegalArgumentException(
                    "unreadable character '" + c + "' in record " + record + " of " + fasta);
        }
        return single[0];
    }
}
