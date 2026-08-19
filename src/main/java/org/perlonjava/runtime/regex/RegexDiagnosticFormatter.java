package org.perlonjava.runtime.regex;

/** Renders engine byte/character positions using Perl's regex diagnostic form. */
final class RegexDiagnosticFormatter {
    private RegexDiagnosticFormatter() {
    }

    static String marked(String pattern, int characterOffset, String message) {
        return marked(pattern, characterOffset, message, false);
    }

    /** Renders the exact Perl form, including whitespace before the closing delimiter. */
    static String markedPerl(String pattern, int characterOffset, String message) {
        return marked(pattern, characterOffset, message, true);
    }

    private static String marked(String pattern, int characterOffset, String message,
                                 boolean trailingEndSpace) {
        String source = pattern == null ? "" : pattern;
        int offset = Math.max(0, Math.min(characterOffset, source.length()));
        String before = source.substring(0, offset);
        String after = source.substring(offset);
        String marker = after.isEmpty() && !trailingEndSpace
                ? " <-- HERE" : " <-- HERE ";
        return message + " in regex; marked by <-- HERE in m/"
                + before + marker + after + "/";
    }
}
