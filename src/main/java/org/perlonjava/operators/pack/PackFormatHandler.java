package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Interface for handling pack format characters
 */
public interface PackFormatHandler {
    /**
     * Pack values according to this format
     *
     * @param output The output stream to write to
     * @param values The list of values to pack
     * @param valueIndex Current position in values list
     * @param count Repeat count (Integer.MAX_VALUE for *)
     * @param modifiers Format modifiers (endianness, etc.)
     * @return number of values consumed from the values list
     */
    int pack(ByteArrayOutputStream output, List<RuntimeScalar> values,
             int valueIndex, int count, PackModifiers modifiers);
}