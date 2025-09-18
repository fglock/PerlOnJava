package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Interface for handling specific pack format characters.
 */
public interface PackFormatHandler {
    /**
     * Packs values according to the format character.
     *
     * @param values     The list of values to pack from
     * @param valueIndex The current index in the values list
     * @param count      The number of items to pack
     * @param hasStar    Whether the count is '*' (pack all remaining)
     * @param modifiers  The parsed modifiers for this format
     * @param output     The output stream to write packed data to
     * @return The new index in the values list after packing
     */
    int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar, 
             ParsedModifiers modifiers, ByteArrayOutputStream output);
}
