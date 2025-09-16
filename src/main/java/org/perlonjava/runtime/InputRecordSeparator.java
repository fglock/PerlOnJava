package org.perlonjava.runtime;

import static org.perlonjava.runtime.RuntimeScalarType.*;

/**
 * Represents the special Perl variable $/ (input record separator).
 * This variable has special validation rules and behavior that differ from normal scalars.
 *
 * <p>The input record separator supports several modes:
 * <ul>
 *   <li>String mode: Normal line/record separation using the string value</li>
 *   <li>Reference to positive integer: Fixed-length record reading</li>
 *   <li>undef: Slurp entire file</li>
 *   <li>Empty string: Paragraph mode (records separated by blank lines)</li>
 * </ul>
 */
public class InputRecordSeparator extends RuntimeScalar {

    /**
     * Constructor with initial value
     */
    public InputRecordSeparator(String value) {
        super(value);
    }

    @Override
    public RuntimeScalar set(RuntimeScalar value) {
        if (value.type == TIED_SCALAR) {
            return set(value.tiedFetch());
        }

        // Store current value in case validation fails
        int oldType = this.type;
        Object oldValue = this.value;

        try {
            // Validate the assignment
            validateInputRecordSeparator(value);

            // If validation passes, set the value
            this.type = value.type;
            this.value = value.value;
            return this;
        } catch (PerlCompilerException e) {
            // Restore original value and re-throw
            this.type = oldType;
            this.value = oldValue;
            throw e;
        }
    }

    @Override
    public RuntimeScalar set(String value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(int value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(boolean value) {
        return set(new RuntimeScalar(value));
    }

    @Override
    public RuntimeScalar set(Object value) {
        return set(new RuntimeScalar(value));
    }

    /**
     * Validates that the given value is acceptable for $/.
     * Throws appropriate exceptions for invalid values.
     */
    private void validateInputRecordSeparator(RuntimeScalar value) {
        switch (value.type) {
            case UNDEF:
            case STRING:
            case BYTE_STRING:
            case INTEGER:
            case DOUBLE:
            case BOOLEAN:
                // These are all valid
                break;

            case REFERENCE:
                // Reference to scalar - check what it points to
                RuntimeScalar referenced = (RuntimeScalar) value.value;

                // Check for REF reference (reference to a reference)
                if (referenced.type == REFERENCE) {
                    throw new PerlCompilerException("Setting $/ to a REF reference is forbidden");
                }

                if (referenced.type == INTEGER) {
                    int intVal = referenced.getInt();
                    if (intVal < 0) {
                        throw new PerlCompilerException("Setting $/ to a reference to a negative integer is forbidden");
                    }
                    if (intVal == 0) {
                        throw new PerlCompilerException("Setting $/ to a reference to zero is forbidden");
                    }
                } else if (referenced.type == STRING || referenced.type == BYTE_STRING) {
                    // Reference to string containing a number
                    String strVal = referenced.toString();
                    try {
                        int intVal = Integer.parseInt(strVal);
                        if (intVal < 0) {
                            throw new PerlCompilerException("Setting $/ to a reference to a negative integer is forbidden");
                        }
                        if (intVal == 0) {
                            throw new PerlCompilerException("Setting $/ to a reference to zero is forbidden");
                        }
                    } catch (NumberFormatException e) {
                        // Non-numeric strings in references are not allowed for record length mode
                        throw new PerlCompilerException("Setting $/ to a reference to a non-numeric string is forbidden");
                    }
                } else {
                    // Other types referenced are not allowed
                    throw new PerlCompilerException("Setting $/ to a reference to an invalid type is forbidden");
                }
                break;

            case ARRAYREFERENCE:
                throw new PerlCompilerException("Setting $/ to an ARRAY reference is forbidden");

            case HASHREFERENCE:
                throw new PerlCompilerException("Setting $/ to a HASH reference is forbidden");

            case CODE:
                throw new PerlCompilerException("Setting $/ to a CODE reference is forbidden");

            case GLOBREFERENCE:
            case GLOB:
                throw new PerlCompilerException("Setting $/ to a GLOB reference is forbidden");

            case REGEX:
                throw new PerlCompilerException("Setting $/ to a REGEXP reference is forbidden");

            default:
                throw new PerlCompilerException("Invalid value for $/");
        }
    }

    /**
     * Returns true if $/ is set to paragraph mode (empty string)
     */
    public boolean isParagraphMode() {
        return type == STRING && "".equals(value);
    }

    /**
     * Returns true if $/ is set to slurp mode (undef)
     */
    public boolean isSlurpMode() {
        return type == UNDEF;
    }

    /**
     * Returns true if $/ is set to record length mode (reference to positive integer)
     */
    public boolean isRecordLengthMode() {
        if (type == REFERENCE) {
            RuntimeScalar referenced = (RuntimeScalar) value;
            if (referenced.type == INTEGER) {
                return referenced.getInt() > 0;
            }
            if (referenced.type == STRING || referenced.type == BYTE_STRING) {
                try {
                    int intVal = Integer.parseInt(referenced.toString());
                    return intVal > 0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Gets the record length when in record length mode
     */
    public int getRecordLength() {
        if (!isRecordLengthMode()) {
            throw new IllegalStateException("$/ is not in record length mode");
        }

        RuntimeScalar referenced = (RuntimeScalar) value;
        if (referenced.type == INTEGER) {
            return referenced.getInt();
        }
        if (referenced.type == STRING || referenced.type == BYTE_STRING) {
            return Integer.parseInt(referenced.toString());
        }

        throw new IllegalStateException("Invalid record length reference");
    }

    /**
     * Handle array dereference for special cases like $/->[1] = 3
     */
    @Override
    public RuntimeArray arrayDeref() {
        return new RuntimeArray();
    }

    /**
     * Handle hash dereference for special cases
     */
    @Override
    public RuntimeHash hashDeref() {
        return new RuntimeHash();
    }
}