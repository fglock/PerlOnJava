package org.perlonjava.runtime.runtimetypes;

import java.io.Serial;

/**
 * Exception used to implement Perl's die semantics.
 * <p>
 * This carries the original die payload (string or reference) so eval can
 * propagate it into $@ without stringifying.
 */
public class PerlDieException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final RuntimeBase payload;
    private final RuntimeScalar warningHandler;
    private final String warningBits;
    private final boolean miscWarningSuppressed;

    public PerlDieException(RuntimeBase payload) {
        this(payload, null);
    }

    public PerlDieException(RuntimeBase payload, RuntimeScalar warningHandler) {
        this(payload, warningHandler, null);
    }

    public PerlDieException(RuntimeBase payload, RuntimeScalar warningHandler, String warningBits) {
        this(payload, warningHandler, warningBits, false);
    }

    public PerlDieException(RuntimeBase payload, RuntimeScalar warningHandler,
            String warningBits, boolean miscWarningSuppressed) {
        super(safeMessage(payload));
        this.payload = payload;
        this.warningHandler = warningHandler;
        this.warningBits = warningBits;
        this.miscWarningSuppressed = miscWarningSuppressed;
    }

    public RuntimeBase getPayload() {
        return payload;
    }

    /** Warning handler that was live at die time, retained across scope unwind. */
    public RuntimeScalar getWarningHandler() {
        return warningHandler;
    }

    /** Lexical warning bits active where the die was raised, before stack unwind. */
    public String getWarningBits() {
        return warningBits;
    }

    /** Whether cleanup's misc warning category was lexically suppressed at die time. */
    public boolean isMiscWarningSuppressed() {
        return miscWarningSuppressed;
    }

    private static String safeMessage(RuntimeBase payload) {
        if (payload == null) return null;

        RuntimeScalar first = payload.getFirst();
        if (first != null && RuntimeScalarType.isReference(first)) {
            return first.toStringNoOverload();
        }

        return payload.toString();
    }
}
