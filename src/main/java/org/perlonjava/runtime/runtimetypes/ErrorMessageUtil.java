package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.ExceptionFormatter.findInnermostCause;

/**
 * Utility class for generating error messages with context from a list of tokens.
 */
public class ErrorMessageUtil {
    private List<LexerToken> tokens;
    private final String originalFileName;
    private String fileName;
    private int tokenIndex;
    private int lastLineNumber;

    /**
     * Constructs an ErrorMessageUtil with the specified file name and list of tokens.
     *
     * @param fileName the name of the file
     * @param tokens   the list of tokens
     */
    public ErrorMessageUtil(String fileName, List<LexerToken> tokens) {
        this.originalFileName = fileName;
        this.fileName = fileName;
        this.tokens = tokens;
        this.tokenIndex = -1;
        this.lastLineNumber = 1;
    }

    /**
     * Updates the token list after source filtering has modified the source.
     * This is called when a source filter is applied after the initial tokenization.
     *
     * @param newTokens the new list of tokens after filtering
     */
    public void updateTokens(List<LexerToken> newTokens) {
        this.tokens = newTokens;
    }

    /**
     * Quotes the specified string for inclusion in an error message.
     * Escapes special characters such as newlines, tabs, and backslashes.
     *
     * @param str the string to quote
     * @return the quoted and escaped string
     */
    private static String errorMessageQuote(String str) {
        StringBuilder escaped = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\"':
                    escaped.append("\\\"");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return "\"" + escaped + "\"";
    }

    /**
     * Stringifies a runtime exception
     *
     * @param e the Exception object
     */
    public static String stringifyException(Exception e) {
        return stringifyException((Throwable) e);
    }

    public static String stringifyException(Throwable t) {
        return stringifyException(t, 0);
    }

    /**
     * Regex matching a leading Java exception class name followed by ": ", e.g.
     * "java.lang.NullPointerException: foo" or "org.perlonjava.runtime.RuntimeException: bar".
     * Matches fully-qualified class names composed of dot-separated identifier segments,
     * with at least two segments to avoid stripping legitimate "Foo: bar" Perl messages.
     */
    private static final java.util.regex.Pattern JAVA_EXCEPTION_PREFIX =
            java.util.regex.Pattern.compile("^([a-zA-Z_$][\\w$]*\\.)+([A-Z][\\w$]*(?:Exception|Error|Throwable))" +
                    "(?::\\s*|\\s+-\\s+)");

    /**
     * Strip a leading Java-style exception class prefix from a message. For example,
     * "java.lang.NullPointerException: Cannot invoke ..." becomes "Cannot invoke ...".
     * If no such prefix is present, the message is returned unchanged.
     * <p>
     * This keeps our error output readable for end users, who should not need to know
     * about JVM internals when their Perl code fails.
     */
    public static String stripJavaExceptionPrefix(String message) {
        if (message == null) return null;
        var m = JAVA_EXCEPTION_PREFIX.matcher(message);
        if (m.find()) {
            String remainder = message.substring(m.end());
            // If what's left is still something useful, return it; otherwise keep original.
            if (!remainder.isEmpty()) return remainder;
        }
        return message;
    }

    /**
     * Returns true when {@code outer} is effectively the result of calling
     * {@code Throwable.toString()} on {@code inner} (or one of its ancestors in the
     * cause chain) — i.e. it is a re-wrapping that adds no information beyond the
     * Java class name and the inner message. In that case we suppress it so the
     * user only sees the real message.
     * <p>
     * Also treats the slashed vs dotted representations of the same class name as
     * equivalent — the JVM uses slashes for class-loading errors
     * ({@code NoClassDefFoundError}) and dots for {@code ClassNotFoundException},
     * which frequently wrap one another.
     */
    public static boolean isJavaToStringOf(String outer, Throwable inner) {
        if (outer == null || inner == null) return false;
        String outerDotted = outer.replace('/', '.');
        Throwable cur = inner;
        while (cur != null) {
            if (outer.equals(cur.toString()) || outerDotted.equals(cur.toString())) return true;
            String innerMsg = cur.getMessage();
            if (innerMsg != null) {
                String asToString = cur.getClass().getName() + ": " + innerMsg;
                if (outer.equals(asToString) || outerDotted.equals(asToString)) return true;
                // NoClassDefFoundError uses '/' while ClassNotFoundException uses '.'
                // for the same class name; treat them as equivalent.
                if (outerDotted.equals(innerMsg.replace('/', '.'))) return true;
            }
            Throwable next = cur.getCause();
            if (next == cur || next == null) break;
            cur = next;
        }
        return false;
    }

    public static String stringifyException(Throwable t, int skipLevels) {
        // Check if this is a PerlParserException that should have clean output
        if (t instanceof PerlParserException) {
            String message = t.getMessage();
            if (message != null && !message.endsWith("\n")) {
                message += "\n";
            }
            return message != null ? message : "\n";
        }

        // Check if the innermost cause is a PerlCompilerException — these already
        // contain fully-formatted error messages with file/line/near context and
        // never have Perl stack frames, so they'd get JVM stack traces appended.
        Throwable innermostCause = findInnermostCause(t);
        if (innermostCause instanceof PerlCompilerException) {
            String message = innermostCause.getMessage();
            if (message != null && !message.endsWith("\n")) {
                message += "\n";
            }
            return message != null ? message : "\n";
        }

        // Use the custom formatter to print the Perl message and stack trace
        StringBuilder sb = new StringBuilder();

        String message = innermostCause.getMessage();

        // Use this for debugging
        // t.printStackTrace();

        String message1 = t.getMessage();
        // Check if the original message ends with \n - Perl skips stack trace in that case
        boolean suppressStackTrace = (message != null && message.endsWith("\n"))
                || (message1 != null && message1.endsWith("\n"));

        // Avoid printing the outer message when it is just a re-wrapping of the
        // innermost cause. This commonly happens when the wrapping exception's
        // message was built from Throwable.toString() of the inner exception —
        // which surfaces cryptic Java class names (e.g. "java.lang.NoClassDefFoundError:
        // org/perlonjava/runtime/operators/KillOperator") above the real message.
        if (message1 != null && !message1.equals(message) && !isJavaToStringOf(message1, innermostCause)) {
            sb.append(stripJavaExceptionPrefix(message1));
            if (!message1.endsWith("\n")) {
                sb.append("\n");
            }
        }

        // Handle null or empty messages
        if (message == null || message.isEmpty()) {
            // Use the exception class name as a fallback
            message = innermostCause.getClass().getSimpleName();
        }

        sb.append(stripJavaExceptionPrefix(message));
        if (!message.endsWith("\n")) {
            sb.append("\n");
        }

        // When die message ends with \n, Perl doesn't print stack trace
        if (suppressStackTrace) {
            return sb.toString();
        }

        ArrayList<ArrayList<String>> formattedLines = ExceptionFormatter.formatException(t);

        // If no Perl stack trace information is available, include JVM stack trace
        if (formattedLines.isEmpty()) {
            sb.append("JVM Stack Trace:\n");

            // Find the root cause
            Throwable rootCause = t;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }

            StackTraceElement[] stackTrace = rootCause.getStackTrace();
            int firstPerlOnJava = -1;
            for (int i = 0; i < stackTrace.length; i++) {
                if (stackTrace[i] != null && stackTrace[i].getClassName().startsWith("org.perlonjava")) {
                    firstPerlOnJava = i;
                    break;
                }
            }

            int start = 0;
            int end = Math.min(stackTrace.length, 80);
            if (firstPerlOnJava >= 0) {
                start = Math.max(0, firstPerlOnJava - 10);
                end = Math.min(stackTrace.length, firstPerlOnJava + 80);
            }

            for (int i = start; i < end; i++) {
                StackTraceElement element = stackTrace[i];
                sb.append("        ")
                        .append(element.getClassName())
                        .append(".")
                        .append(element.getMethodName())
                        .append(" at ")
                        .append(element.getFileName())
                        .append(" line ")
                        .append(element.getLineNumber())
                        .append("\n");
            }
        } else {
            int level = 0;
            for (ArrayList<String> line : formattedLines) {
                if (level >= skipLevels) {
                    sb.append("        ").append(line.get(0)).append(" at ").append(line.get(1)).append(" line ").append(line.get(2)).append("\n");
                }
                level++;
            }
        }

        return sb.toString();
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setLineNumber(int lineNumber) {
        this.lastLineNumber = lineNumber;
    }

    public void setTokenIndex(int index) {
        this.tokenIndex = index;
    }

    /**
     * Generates an error message with context from the token list.
     *
     * @param index   the index of the token where the error occurred
     * @param message the error message
     * @return the formatted error message with context
     */
    public String errorMessage(int index, String message) {
        SourceLocation loc = getSourceLocationAccurate(index);

        String nearString = buildNearString(index);

        return message + " at " + loc.fileName() + " line " + loc.lineNumber() + ", near " + errorMessageQuote(nearString) + "\n";
    }

    /**
     * Gets a simple warning location string in the format " at filename line N".
     * This matches system Perl's warning format without the ", near" suffix.
     *
     * @param index the index of the token where the warning occurred
     * @return the location string (e.g., " at script.pl line 42")
     */
    public String warningLocation(int index) {
        SourceLocation loc = getSourceLocationAccurate(index);
        return " at " + loc.fileName() + " line " + loc.lineNumber();
    }

    private String buildNearString(int index) {
        int end = Math.min(tokens.size() - 1, index + 5);
        StringBuilder sb = new StringBuilder();
        int nonWsCount = 0;
        for (int i = index; i <= end; i++) {
            LexerToken tok = tokens.get(i);
            if (tok.type == LexerTokenType.EOF || tok.type == LexerTokenType.NEWLINE) break;
            if (tok.text.equals("{") || tok.text.equals("}")) break;
            if (tok.type != LexerTokenType.WHITESPACE) {
                nonWsCount++;
                if (nonWsCount > 3) break;
            }
            sb.append(tok.text);
        }
        String near = sb.toString();
        near = near.replaceAll("^\\s+", "");
        return near;
    }

    /**
     * Retrieves the line number by counting newlines up to the specified index.
     * Uses a simple cache to avoid recalculating line numbers for previously processed indexes.
     *
     * @param index the index of the token
     * @return the line number
     */
    public int getLineNumber(int index) {
        // Start from the last processed index and line number

        if (index <= tokenIndex) {
            return lastLineNumber;
        }

        // Count newlines from the last processed index to the current index
        for (int i = tokenIndex + 1; i <= index; i++) {
            LexerToken tok = tokens.get(i);
            if (tok.type == LexerTokenType.EOF) {
                break;
            }
            if (tok.type == LexerTokenType.NEWLINE) {
                lastLineNumber++;
            }
        }

        // Update the cache with the current index and line number
        tokenIndex = index;
        return lastLineNumber;
    }

    /**
     * Get line number without relying on cache.
     * Always counts from the beginning of the file.
     * Safe for random access (used by debugger for lazily compiled subroutines).
     *
     * @param index the index of the token
     * @return the line number
     */
    public int getLineNumberAccurate(int index) {
        int lineNumber = 1;
        for (int i = 0; i <= index && i < tokens.size(); i++) {
            LexerToken tok = tokens.get(i);
            if (tok.type == LexerTokenType.EOF) break;
            if (tok.type == LexerTokenType.NEWLINE) {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    public SourceLocation getSourceLocationAccurate(int index) {
        String currentFileName = originalFileName;
        int lineNumber = 1;

        boolean atBeginningOfLine = true;
        int i = 0;
        while (i <= index && i < tokens.size()) {
            LexerToken tok = tokens.get(i);
            if (tok.type == LexerTokenType.EOF) {
                break;
            }

            if (tok.type == LexerTokenType.NEWLINE) {
                lineNumber++;
                atBeginningOfLine = true;
                i++;
                continue;
            }

            if (atBeginningOfLine && tok.type == LexerTokenType.OPERATOR && tok.text.equals("#")) {
                int j = i + 1;
                while (j < tokens.size() && tokens.get(j).type == LexerTokenType.WHITESPACE) {
                    j++;
                }
                if (j < tokens.size() && tokens.get(j).type == LexerTokenType.IDENTIFIER && tokens.get(j).text.equals("line")) {
                    j++;
                    while (j < tokens.size() && tokens.get(j).type == LexerTokenType.WHITESPACE) {
                        j++;
                    }
                    if (j < tokens.size() && tokens.get(j).type == LexerTokenType.NUMBER) {
                        int directiveLine = -1;
                        try {
                            directiveLine = Integer.parseInt(tokens.get(j).text);
                        } catch (NumberFormatException e) {
                            directiveLine = -1;
                        }
                        j++;
                        while (j < tokens.size() && tokens.get(j).type == LexerTokenType.WHITESPACE) {
                            j++;
                        }
                        if (j < tokens.size() && tokens.get(j).type == LexerTokenType.OPERATOR && tokens.get(j).text.equals("\"")) {
                            // Quoted filename: #line N "filename"
                            j++;
                            StringBuilder filenameBuilder = new StringBuilder();
                            while (j < tokens.size() && !(tokens.get(j).type == LexerTokenType.OPERATOR && tokens.get(j).text.equals("\""))) {
                                filenameBuilder.append(tokens.get(j).text);
                                j++;
                            }
                            if (j < tokens.size() && tokens.get(j).type == LexerTokenType.OPERATOR && tokens.get(j).text.equals("\"")) {
                                String directiveFile = filenameBuilder.toString();
                                if (!directiveFile.isEmpty()) {
                                    currentFileName = directiveFile;
                                }
                            }
                        } else if (j < tokens.size() && tokens.get(j).type == LexerTokenType.IDENTIFIER) {
                            // Unquoted filename: #line N filename
                            // Perl allows unquoted bareword filenames in #line directives
                            currentFileName = tokens.get(j).text;
                        }

                        if (directiveLine >= 0) {
                            // The directive applies to the following line.
                            // Perl allows `#line 0` (the next line becomes line 0);
                            // -M/-m import injection relies on this to make caller()
                            // report line 0, matching real Perl behavior.
                            lineNumber = directiveLine - 1;
                        }
                    }
                }
            }

            if (tok.type != LexerTokenType.WHITESPACE) {
                atBeginningOfLine = false;
            }
            i++;
        }

        return new SourceLocation(currentFileName, lineNumber);
    }

    public record SourceLocation(String fileName, int lineNumber) {
    }

    /**
     * Extract source lines from tokens.
     * Reconstructs source code by concatenating token texts, splitting on NEWLINE tokens.
     *
     * @return Array of source lines (1-based indexing, index 0 is empty)
     */
    public String[] extractSourceLines() {
        if (tokens == null || tokens.isEmpty()) {
            return new String[0];
        }

        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("");  // Index 0 unused (1-based line numbers)

        StringBuilder currentLine = new StringBuilder();
        for (LexerToken tok : tokens) {
            if (tok.type == LexerTokenType.EOF) {
                break;
            }
            if (tok.type == LexerTokenType.NEWLINE) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
            } else {
                currentLine.append(tok.text);
            }
        }
        // Add last line if not empty
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines.toArray(new String[0]);
    }
}

