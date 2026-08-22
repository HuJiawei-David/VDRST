package vdrst.service;

import vdrst.align.Nucleotides;

/**
 * Validates a submitted sequence before anything else touches it.
 *
 * <p>v1 had no validation at all: the request body went straight into a FASTA file that
 * was handed to a subprocess. See RETROSPECTIVE.md finding 3.
 */
public final class SequenceValidator {

    /** Long enough to be meaningful, short enough to bound the O(mn) re-ranking stage. */
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 100_000;

    private SequenceValidator() {}

    public static final class InvalidRequestException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public InvalidRequestException(String message) { super(message); }
    }

    /**
     * @return the cleaned sequence, uppercased and stripped of whitespace and FASTA headers
     * @throws InvalidRequestException with a message safe to return to the caller
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("sequence must not be empty");
        }

        StringBuilder cleaned = new StringBuilder(raw.length());
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(">") || trimmed.startsWith(";")) continue;
            cleaned.append(trimmed);
        }

        String sequence = cleaned.toString().toUpperCase(java.util.Locale.ROOT);

        if (sequence.length() < MIN_LENGTH) {
            throw new InvalidRequestException(
                    "sequence must be at least " + MIN_LENGTH + " bases, got " + sequence.length());
        }
        if (sequence.length() > MAX_LENGTH) {
            throw new InvalidRequestException(
                    "sequence must be at most " + MAX_LENGTH + " bases, got " + sequence.length());
        }

        try {
            Nucleotides.encode(sequence);      // rejects anything that is not A/C/G/T/U/N
        } catch (Nucleotides.InvalidSequenceException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        return sequence;
    }
}
