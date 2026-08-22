package vdrst.http;

/**
 * Just enough JSON to serve this API, written out rather than pulled in.
 *
 * <p>Adding Jackson for two request fields and one response shape would mean a
 * dependency tree, a version to keep current, and a CVE feed to watch, in exchange for
 * work this file does in eighty lines. v1 carried iText, Apache POI, jsoup, org.json and
 * a JWT library to support endpoints its own frontend never called; three of them had
 * known vulnerabilities by the time anyone looked.
 *
 * <p>This is not a general JSON library and does not pretend to be. It escapes strings
 * correctly and reads one string field out of a flat object, which is the whole contract.
 */
public final class Json {

    private Json() {}

    /** Escapes a string and wraps it in quotes, per RFC 8259. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * Reads one string field from a flat JSON object.
     *
     * @return the field's value, or null when absent
     * @throws MalformedJsonException if the document is not a flat object or the field is
     *                                not a string
     */
    public static String readString(String document, String field) {
        String needle = quote(field);
        int keyAt = document.indexOf(needle);
        if (keyAt < 0) return null;

        int cursor = keyAt + needle.length();
        cursor = skipWhitespace(document, cursor);
        if (cursor >= document.length() || document.charAt(cursor) != ':') {
            throw new MalformedJsonException("expected ':' after \"" + field + "\"");
        }
        cursor = skipWhitespace(document, cursor + 1);
        if (cursor >= document.length() || document.charAt(cursor) != '"') {
            throw new MalformedJsonException("\"" + field + "\" must be a string");
        }

        StringBuilder value = new StringBuilder();
        for (int i = cursor + 1; i < document.length(); i++) {
            char c = document.charAt(i);
            if (c == '\\') {
                if (++i >= document.length()) break;
                char escaped = document.charAt(i);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 >= document.length()) throw new MalformedJsonException("truncated \\u escape");
                        value.append((char) Integer.parseInt(document.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> value.append(escaped);
                }
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        throw new MalformedJsonException("unterminated string for \"" + field + "\"");
    }

    public static final class MalformedJsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        MalformedJsonException(String message) { super(message); }
    }

    private static int skipWhitespace(String document, int from) {
        int i = from;
        while (i < document.length() && Character.isWhitespace(document.charAt(i))) i++;
        return i;
    }
}
