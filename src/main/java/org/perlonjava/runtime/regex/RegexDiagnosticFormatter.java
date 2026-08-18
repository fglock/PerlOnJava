package org.perlonjava.runtime.regex;

/** Renders engine byte/character positions using Perl's regex diagnostic form. */
final class RegexDiagnosticFormatter {
    private RegexDiagnosticFormatter() {
    }

    static String marked(String pattern, int characterOffset, String message) {
        String source = pattern == null ? "" : pattern;
        int offset = Math.max(0, Math.min(characterOffset, source.length()));
        String before = source.substring(0, offset);
        String after = source.substring(offset);
        String marker = after.isEmpty() ? " <-- HERE" : " <-- HERE ";
        return message + " in regex; marked by <-- HERE in m/"
                + before + marker + after + "/";
    }
}
