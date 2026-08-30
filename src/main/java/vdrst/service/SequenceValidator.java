package vdrst.service;

import vdrst.align.Nucleotides;

/**
 * Validates a submitted sequence before anything else touches it.
 *
 * <p>v1 had no validation at all: the request body went straight into a FASTA file that
 * was handed to a subprocess. See RETROSPECTIVE.md finding 3.
 */
public final class SequenceValidator {

    /**
     * Below this, a search is not meaningful and the interface should say so rather than
     * return something.
     *
     * <p>Two separate reasons, and the statistical one is the important one. A query
     * shorter than the index's k-mer length produces no seeds at all, so it can only ever
     * return nothing — accepting it and reporting "no matches" describes a limitation as
     * though it were a result. And well above that threshold, chance still dominates: in a
     * database of tens of millions of bases, a random 20-mer finds something scoring
     * around 70% of its maximum, purely because there is that much sequence to draw from.
     * Returning those alongside a real hit, with nothing to separate them, is the same
     * mistake v1 made with its percentage — see RETROSPECTIVE.md finding 8.
     *
     * <p>30 is where a random query stops reliably scoring high on this corpus. It is a
     * threshold, not a significance test; the honest version of that is an E-value, which
     * this project does not yet compute. README.md says so under Limitations.
     */
    public static final int MIN_LENGTH = 30;

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
        return validate(raw, MAX_LENGTH);
    }

    /**
     * @param maxLength the ceiling this caller enforces, which a public deployment sets
     *                  below {@link #MAX_LENGTH}. Alignment cost grows with the square of
     *                  the query — a 16,000-base query is about ten times the work of a
     *                  5,000-base one — so the ceiling is what keeps a single request
     *                  from being a denial-of-service primitive. It is checked against
     *                  the cleaned sequence, because that is what gets aligned; checking
     *                  the raw string with slop for headers lets a query through at
     *                  whatever the slop is, which is how this was wrong before.
     */
    public static String validate(String raw, int maxLength) {
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
            throw new InvalidRequestException("sequence must be at least " + MIN_LENGTH
                    + " bases, got " + sequence.length()
                    + " — shorter queries match this much sequence by chance");
        }
        if (sequence.length() > maxLength) {
            throw new InvalidRequestException(
                    "sequence must be at most " + maxLength + " bases, got " + sequence.length());
        }

        try {
            Nucleotides.encode(sequence);      // rejects anything that is not A/C/G/T/U/N
        } catch (Nucleotides.InvalidSequenceException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        return sequence;
    }
}
