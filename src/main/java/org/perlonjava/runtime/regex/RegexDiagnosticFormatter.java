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
        return marked(pattern, characterOffset,
                normalizePerlMessage(pattern, characterOffset, message), true);
    }

    /** Renders Perl diagnostics whose marker describes the accepted name prefix. */
    static String markedAfter(String pattern, int characterOffset, String message) {
        String source = pattern == null ? "" : pattern;
        int offset = Math.max(0, Math.min(characterOffset, source.length()));
        return message + "; marked by <-- HERE after "
                + source.substring(0, offset) + " <-- HERE near column "
                + (offset + 1);
    }

    private static String normalizePerlMessage(
            String pattern, int characterOffset, String message) {
        if ("Sequence (?<... not terminated".equals(message)
                && pattern != null && characterOffset > 0
                && characterOffset <= pattern.length()) {
            char first = pattern.charAt(characterOffset - 1);
            if (first != '_' && !Character.isLetterOrDigit(first)) {
                return "Group name must start with a non-digit word character";
            }
        }
        return expandUselessModifier(message);
    }

    private static String expandUselessModifier(String message) {
        if (message == null || !message.startsWith("Useless (?")
                || !message.endsWith(")")) {
            return message;
        }
        int modifierIndex = message.length() - 2;
        char modifier = message.charAt(modifierIndex);
        if (modifier != 'c' && modifier != 'g' && modifier != 'o') return message;
        boolean negative = modifierIndex > 9
                && message.charAt(modifierIndex - 1) == '-';
        return message + " - " + (negative ? "don't use" : "use") + " /"
                + (modifier == 'c' ? "gc" : String.valueOf(modifier)) + " modifier";
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
