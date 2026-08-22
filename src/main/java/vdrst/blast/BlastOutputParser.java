package vdrst.blast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses BLAST tabular output (outfmt 6) into {@link BlastHit}s.
 *
 * <p>v1 parsed the <em>pairwise</em> human-readable report instead, by scanning for
 * lines beginning "Query", "Sbjct" and "Score =" and splitting on whitespace. NCBI
 * documents that format as being for human consumption and does not guarantee its
 * stability across releases; the tabular formats are the machine-readable ones. The
 * v1 parser was also silently lossy — it kept only the last HSP of each block in
 * several branches. Replacing it removed about 150 lines of string handling.
 * See RETROSPECTIVE.md finding 6.
 *
 * <p>Expected column order, which {@link BlastRunner} requests explicitly:
 * {@code sseqid stitle slen qstart qend sstart send bitscore evalue qseq sseq}
 */
public final class BlastOutputParser {

    /** The outfmt specification this parser is written against. */
    public static final String OUTPUT_FORMAT =
            "6 sseqid stitle slen qstart qend sstart send bitscore evalue qseq sseq";

    private static final int COLUMNS = 11;

    private BlastOutputParser() {}

    /**
     * @param tabular raw stdout from blastn; may be empty when nothing matched
     * @return hits in first-seen order, each carrying every HSP reported for it
     */
    public static List<BlastHit> parse(String tabular) {
        Map<String, Builder> bySubject = new LinkedHashMap<>();

        for (String line : tabular.split("\n")) {
            if (line.isBlank() || line.charAt(0) == '#') continue;

            String[] col = line.split("\t", -1);
            if (col.length < COLUMNS) {
                throw new MalformedOutputException(
                        "expected " + COLUMNS + " tab-separated columns, found " + col.length
                                + ": " + abbreviate(line));
            }

            String subjectId = col[0];
            Builder builder = bySubject.computeIfAbsent(subjectId,
                    id -> new Builder(id, col[1], parseInt(col[2], "slen", line)));

            builder.hsps.add(new Hsp(
                    col[9], col[10],
                    parseInt(col[3], "qstart", line), parseInt(col[4], "qend", line),
                    parseInt(col[5], "sstart", line), parseInt(col[6], "send", line),
                    parseDouble(col[7], "bitscore", line), parseDouble(col[8], "evalue", line)));
        }

        List<BlastHit> hits = new ArrayList<>(bySubject.size());
        for (Builder b : bySubject.values()) hits.add(new BlastHit(b.id, b.title, b.subjectLength, b.hsps));
        return hits;
    }

    public static final class MalformedOutputException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        MalformedOutputException(String message) { super(message); }
        MalformedOutputException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class Builder {
        final String id; final String title; final int subjectLength;
        final List<Hsp> hsps = new ArrayList<>();
        Builder(String id, String title, int subjectLength) {
            this.id = id; this.title = title; this.subjectLength = subjectLength;
        }
    }

    private static int parseInt(String value, String column, String line) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new MalformedOutputException(
                    "column " + column + " was not an integer: \"" + value + "\" in " + abbreviate(line), e);
        }
    }

    private static double parseDouble(String value, String column, String line) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new MalformedOutputException(
                    "column " + column + " was not a number: \"" + value + "\" in " + abbreviate(line), e);
        }
    }

    private static String abbreviate(String line) {
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }
}
