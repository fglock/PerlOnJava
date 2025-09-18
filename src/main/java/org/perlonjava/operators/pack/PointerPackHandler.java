package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for pointer formats 'p' and 'P'.
 */
public class PointerPackHandler implements PackFormatHandler {
    /** 
     * Temporary storage for pointer simulation used by 'p' and 'P' formats.
     * Maps hash codes to their corresponding string values for later retrieval by unpack.
     */
    private static final Map<Integer, String> pointerMap = new HashMap<>();

    /**
     * Retrieves a string value associated with a pointer hash code.
     * This method is used by the unpack operation to retrieve strings
     * that were stored during pack operations with 'p' or 'P' formats.
     * 
     * @param hashCode The hash code of the string to retrieve
     * @return The string associated with the hash code, or null if not found
     */
    public static String getPointerString(int hashCode) {
        return pointerMap.get(hashCode);
    }

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar, 
                    ParsedModifiers modifiers, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use empty string as per Perl behavior
                value = new RuntimeScalar("");
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }

            int ptr = 0;

            // Check if value is defined (not undef)
            if (value.getDefinedBoolean()) {
                String str = value.toString();
                ptr = str.hashCode();
                pointerMap.put(ptr, str);
            }

            // Use the already-parsed endianness
            if (modifiers.bigEndian) {
                PackWriter.writeIntBigEndian(output, ptr);
            } else {
                PackWriter.writeIntLittleEndian(output, ptr);
            }
        }
        return valueIndex;
    }
}
