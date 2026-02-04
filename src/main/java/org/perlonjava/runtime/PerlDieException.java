package org.perlonjava.runtime;

import java.io.Serial;

/**
 * Exception used to implement Perl's die semantics.
 *
 * This carries the original die payload (string or reference) so eval can
 * propagate it into $@ without stringifying.
 */
public class PerlDieException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final RuntimeBase payload;

    public PerlDieException(RuntimeBase payload) {
        super(payload == null ? null : payload.toString());
        this.payload = payload;
    }

    public RuntimeBase getPayload() {
        return payload;
    }
}
