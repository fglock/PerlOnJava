package org.perlonjava.runtime;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.parser.TokenUtils;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.ExceptionFormatter.findInnermostCause;

/**
 * Utility class for generating error messages with context from a list of tokens.
 */
public class ErrorMessageUtil {
    private String fileName;
    private final List<LexerToken> tokens;
    private int tokenIndex;
    private int lastLineNumber;

    /**
     * Constructs an ErrorMessageUtil with the specified file name and list of tokens.
     *
     * @param fileName the name of the file
     * @param tokens   the list of tokens
     */
    public ErrorMessageUtil(String fileName, List<LexerToken> tokens) {
        this.fileName = fileName;
        this.tokens = tokens;
        this.tokenIndex = -1;
        this.lastLineNumber = 1;
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
        // Use the custom formatter to print the Perl message and stack trace
        StringBuilder sb = new StringBuilder();

        Throwable innermostCause = findInnermostCause(t);
        String message = innermostCause.getMessage();
        sb.append(message);
        if (!message.endsWith("\n")) {
            sb.append("\n");
        }

        for (ArrayList<String> line : ExceptionFormatter.formatException(t)) {
            sb.append("        ").append(line.get(0)).append(" at ").append(line.get(1)).append(" line ").append(line.get(2)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Generates an error message with context from the token list.
     *
     * @param index   the index of the token where the error occurred
     * @param message the error message
     * @return the formatted error message with context
     */
    public String errorMessage(int index, String message) {
        // Retrieve the line number by counting newlines up to the specified index
        int line = getLineNumber(index);

        // Retrieve the string context around the error by collecting tokens near the specified index
        String nearString = TokenUtils.toText(tokens, index - 4, index + 2);

        // Return the formatted error message with the file name, line number, and context
        return message + " at " + fileName + " line " + line + ", near " + errorMessageQuote(nearString) + "\n";
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
}

