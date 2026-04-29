package org.perlonjava.runtime.perlmodule.storable;

/**
 * Thrown when a Storable byte stream is malformed, truncated, or uses
 * an unsupported feature (e.g. older binary major version, 32-bit IV,
 * unrecognized opcode). Messages should mirror upstream's
 * {@code CROAK(("..."))} text where practical so users searching for
 * Storable diagnostics find the same phrase.
 */
public class StorableFormatException extends RuntimeException {
    public StorableFormatException(String message) { super(message); }
    public StorableFormatException(String message, Throwable cause) { super(message, cause); }
}
