package vdrst.align;

/**
 * Encoding and validation for nucleotide sequences.
 *
 * <p>v1 wrote whatever string arrived in the request body straight into a FASTA file
 * and handed it to a subprocess, with no validation of any kind. See RETROSPECTIVE.md
 * finding 3.
 */
public final class Nucleotides {

    public static final byte A = 0, C = 1, G = 2, T = 3, N = 4;

    private static final byte[] LOOKUP = new byte[128];
    private static final char[] DECODE = {'A', 'C', 'G', 'T', 'N'};

    static {
        java.util.Arrays.fill(LOOKUP, (byte) -1);
        LOOKUP['A'] = A; LOOKUP['a'] = A;
        LOOKUP['C'] = C; LOOKUP['c'] = C;
        LOOKUP['G'] = G; LOOKUP['g'] = G;
        LOOKUP['T'] = T; LOOKUP['t'] = T;
        LOOKUP['U'] = T; LOOKUP['u'] = T;   // RNA: uracil pairs as thymine
        LOOKUP['N'] = N; LOOKUP['n'] = N;
    }

    private Nucleotides() {}

    /** Thrown when a sequence contains something that is not a nucleotide. */
    public static final class InvalidSequenceException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final int position;
        public final char offending;

        InvalidSequenceException(int position, char offending) {
            super("invalid nucleotide '" + offending + "' at position " + position
                  + "; expected one of A, C, G, T, U, N");
            this.position = position;
            this.offending = offending;
        }
    }

    /**
     * Encodes a sequence, ignoring ASCII whitespace so that pasted multi-line FASTA
     * payloads work.
     *
     * @throws InvalidSequenceException on the first character that is not a nucleotide
     */
    public static byte[] encode(String sequence) {
        byte[] out = new byte[sequence.length()];
        int n = 0;
        for (int i = 0; i < sequence.length(); i++) {
            char c = sequence.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            byte code = c < 128 ? LOOKUP[c] : -1;
            if (code < 0) throw new InvalidSequenceException(i, c);
            out[n++] = code;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    public static String decode(byte[] encoded) {
        char[] out = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) out[i] = DECODE[encoded[i]];
        return new String(out);
    }
}
