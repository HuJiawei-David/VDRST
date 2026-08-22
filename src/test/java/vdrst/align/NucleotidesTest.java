package vdrst.align;

import vdrst.harness.Assert;
import vdrst.harness.Test;

public final class NucleotidesTest {

    @Test("ACGT encodes and decodes losslessly")
    public void roundTrip() {
        String sequence = "ACGTACGTTTGGCCAA";
        Assert.equal(sequence, Nucleotides.decode(Nucleotides.encode(sequence)), "round trip");
    }

    @Test("lower case is accepted")
    public void lowerCase() {
        Assert.equal(Nucleotides.encode("ACGT"), Nucleotides.encode("acgt"), "case should not matter");
    }

    @Test("RNA uracil is treated as thymine")
    public void uracil() {
        Assert.equal(Nucleotides.encode("ACGT"), Nucleotides.encode("ACGU"),
                "U and T are the same base for alignment purposes");
    }

    @Test("whitespace inside a pasted sequence is ignored")
    public void whitespaceIgnored() {
        Assert.equal(Nucleotides.encode("ACGTACGT"), Nucleotides.encode("ACGT\n  ACGT\t"),
                "multi-line pasted input should work");
    }

    @Test("an invalid character is rejected with its position")
    public void invalidCharacter() {
        var e = Assert.throwsException(Nucleotides.InvalidSequenceException.class,
                () -> Nucleotides.encode("ACGTXACGT"), "X is not a nucleotide");
        Assert.equal(4, e.position, "position of the offending character");
        Assert.equal('X', e.offending, "the offending character itself");
    }

    @Test("shell metacharacters are rejected like any other invalid input")
    public void shellMetacharactersRejected() {
        String[] hostile = {"ACGT; rm -rf /", "ACGT`id`", "ACGT$(whoami)", "ACGT|nc host 1"};
        for (String input : hostile) {
            Assert.throwsException(Nucleotides.InvalidSequenceException.class,
                    () -> Nucleotides.encode(input),
                    "must reject: " + input);
        }
    }

    @Test("N is a valid base but never counts as a match")
    public void ambiguousBase() {
        byte[] n = Nucleotides.encode("NNNNNNNNNN");
        Assert.equal(0, new GotohAligner().score(n, n),
                "runs of N should not accumulate a match score");
    }
}
