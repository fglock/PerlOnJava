package org.perlonjava.runtime.runtimetypes;

import java.io.Serial;

/**
 * PerlParserException is a custom exception class for parser errors that need
 * to match Perl's exact error message format without additional context or stack traces.
 * This is used for compatibility with Perl's error message expectations in tests.
 */
public class PerlParserException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String cleanMessage;

    /**
     * Constructs a new PerlParserException with a clean message that matches Perl format.
     * The message should already include file name and line number in Perl format.
     *
     * @param message the complete error message in Perl format
     */
    public PerlParserException(String message) {
        super(message);
        this.cleanMessage = message;
    }

    /**
     * Returns the clean error message without any additional formatting.
     *
     * @return the clean error message
     */
    @Override
    public String getMessage() {
        return cleanMessage;
    }

    /**
     * Override toString to return just the message without exception class name.
     *
     * @return the clean error message
     */
    @Override
    public String toString() {
        return cleanMessage;
    }
}
