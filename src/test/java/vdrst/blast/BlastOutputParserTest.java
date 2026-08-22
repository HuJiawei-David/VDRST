package vdrst.blast;

import vdrst.harness.Assert;
import vdrst.harness.Test;

import java.util.List;

public final class BlastOutputParserTest {

    private static final String TAB = "\t";

    private static String row(String id, String title, int slen, int qs, int qe, int ss, int se,
                              double bits, double e, String qseq, String sseq) {
        return String.join(TAB, id, title, String.valueOf(slen), String.valueOf(qs),
                String.valueOf(qe), String.valueOf(ss), String.valueOf(se),
                String.valueOf(bits), String.valueOf(e), qseq, sseq);
    }

    @Test("empty output yields no hits rather than throwing")
    public void emptyOutput() {
        Assert.equal(0, BlastOutputParser.parse("").size(), "empty string");
        Assert.equal(0, BlastOutputParser.parse("\n\n").size(), "blank lines only");
    }

    @Test("comment lines are ignored")
    public void commentsIgnored() {
        String out = "# BLASTN 2.12.0+\n# Query: q\n"
                + row("v1", "virus one", 5000, 1, 10, 100, 109, 20.0, 1e-3, "ACGTACGTAC", "ACGTACGTAC") + "\n";
        Assert.equal(1, BlastOutputParser.parse(out).size(), "one hit expected");
    }

    @Test("multiple HSPs against one subject collapse into a single hit")
    public void multipleHspsGrouped() {
        String out = row("v1", "virus one", 5000, 1, 10, 100, 109, 20.0, 1e-3, "ACGTACGTAC", "ACGTACGTAC") + "\n"
                   + row("v1", "virus one", 5000, 40, 49, 800, 809, 18.0, 1e-2, "GGGGCCCCTT", "GGGGCCCCTT") + "\n"
                   + row("v2", "virus two", 900, 1, 5, 10, 14, 12.0, 0.5, "ACGTA", "ACGTA") + "\n";

        List<BlastHit> hits = BlastOutputParser.parse(out);
        Assert.equal(2, hits.size(), "two distinct subjects");
        Assert.equal(2, hits.get(0).hsps().size(), "first subject keeps both HSPs");
        Assert.equal(20, hits.get(0).alignedLength(), "aligned length sums across HSPs");
        Assert.equal("virus one", hits.get(0).title(), "title carried through");
    }

    @Test("first-seen subject order is preserved")
    public void orderPreserved() {
        String out = row("zzz", "last alphabetically", 100, 1, 5, 1, 5, 9.0, 1.0, "ACGTA", "ACGTA") + "\n"
                   + row("aaa", "first alphabetically", 100, 1, 5, 1, 5, 9.0, 1.0, "ACGTA", "ACGTA") + "\n";
        List<BlastHit> hits = BlastOutputParser.parse(out);
        Assert.equal("zzz", hits.get(0).subjectId(), "BLAST's ranking, not alphabetical order");
    }

    @Test("gaps are stripped before re-scoring but kept in the raw alignment")
    public void gapHandling() {
        String out = row("v1", "gapped", 100, 1, 10, 1, 10, 15.0, 0.1, "ACGT--ACGT", "ACGTTTACGT") + "\n";
        Hsp hsp = BlastOutputParser.parse(out).get(0).hsps().get(0);
        Assert.equal("ACGT--ACGT", hsp.queryAligned(), "aligned form keeps gap characters");
        Assert.equal("ACGTACGT", hsp.queryUngapped(), "ungapped form drops them");
        Assert.equal("ACGTTTACGT", hsp.subjectUngapped(), "subject had no gaps");
    }

    @Test("a truncated row is rejected loudly, not skipped silently")
    public void truncatedRowRejected() {
        String truncated = String.join(TAB, "v1", "virus", "5000", "1", "10") + "\n";
        Assert.throwsException(BlastOutputParser.MalformedOutputException.class,
                () -> BlastOutputParser.parse(truncated),
                "a row with too few columns must fail");
    }

    @Test("a non-numeric column names the column and the row")
    public void badNumberRejected() {
        String bad = row("v1", "t", 5000, 1, 10, 100, 109, 20.0, 1e-3, "ACGTACGTAC", "ACGTACGTAC")
                .replace(TAB + "5000" + TAB, TAB + "not-a-number" + TAB) + "\n";
        var e = Assert.throwsException(BlastOutputParser.MalformedOutputException.class,
                () -> BlastOutputParser.parse(bad),
                "a malformed integer must fail");
        Assert.contains(e.getMessage(), "slen", "the error should name the offending column");
    }

    @Test("mismatched aligned lengths are rejected when the HSP is built")
    public void mismatchedAlignmentRejected() {
        String uneven = row("v1", "t", 100, 1, 10, 1, 10, 9.0, 1.0, "ACGTACGTAC", "ACGT") + "\n";
        Assert.throwsException(IllegalArgumentException.class,
                () -> BlastOutputParser.parse(uneven),
                "query and subject alignments must be the same length");
    }
}
