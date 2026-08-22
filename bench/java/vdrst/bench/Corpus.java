package vdrst.bench;

import vdrst.align.Nucleotides;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The corpus, loaded into memory so that the no-prefilter baseline measures alignment
 * cost rather than disk throughput.
 *
 * <p>Loading everything up front is deliberate: it makes the B0 baseline as favourable
 * as it can honestly be. If the baseline had to stream from disk, the speedup this
 * project claims would be partly a measurement of I/O, and the comparison would flatter
 * the prefilter for the wrong reason.
 */
public final class Corpus {

    public record Genome(String id, byte[] bases) {}

    private final List<Genome> genomes;
    private final long totalBases;

    private Corpus(List<Genome> genomes, long totalBases) {
        this.genomes = List.copyOf(genomes);
        this.totalBases = totalBases;
    }

    public List<Genome> genomes() { return genomes; }

    public long totalBases() { return totalBases; }

    public int size() { return genomes.size(); }

    public static Corpus load(Path fasta) throws IOException {
        List<Genome> genomes = new ArrayList<>();
        long total = 0;

        try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
            String id = null;
            StringBuilder current = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith(">")) {
                    if (id != null) {
                        byte[] bases = Nucleotides.encode(current.toString());
                        genomes.add(new Genome(id, bases));
                        total += bases.length;
                    }
                    int space = line.indexOf(' ');
                    id = space < 0 ? line.substring(1) : line.substring(1, space);
                    current.setLength(0);
                } else {
                    current.append(line.trim());
                }
            }
            if (id != null) {
                byte[] bases = Nucleotides.encode(current.toString());
                genomes.add(new Genome(id, bases));
                total += bases.length;
            }
        }
        return new Corpus(genomes, total);
    }
}
