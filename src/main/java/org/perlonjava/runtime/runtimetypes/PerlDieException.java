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

    public PerlDieException(RuntimeBase payload) {
        super(safeMessage(payload));
        this.payload = payload;
    }

    public RuntimeBase getPayload() {
        return payload;
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
