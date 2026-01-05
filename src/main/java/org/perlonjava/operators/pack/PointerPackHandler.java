package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for pointer formats 'p' and 'P'.
 * 
 * - 'p' format: count is a repeat count (p3 packs 3 pointers)
 * - 'P' format: count is minimum string length (P3 packs 1 pointer with min 3 bytes)
 */
public class PointerPackHandler implements PackFormatHandler {
    /**
     * Temporary storage for pointer simulation used by 'p' and 'P' formats.
     * Maps hash codes to their corresponding string values for later retrieval by unpack.
     */
    private static final Map<Integer, String> pointerMap = new HashMap<>();
    
    /** The format character ('p' or 'P') */
    private final char format;

    /**
     * Creates a new PointerPackHandler for the specified format.
     * 
     * @param format The format character ('p' or 'P')
     */
    public PointerPackHandler(char format) {
        this.format = format;
    }

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

    /**
     * Modifies a string value associated with a pointer hash code.
     * This method simulates XS::APItest::modify_pv for testing purposes.
     *
     * @param hashCode The hash code of the pointer to modify
     * @param len The length to set (overwrites with 'y' characters)
     */
    public static void modifyPointer(int hashCode, int len) {
        if (pointerMap.containsKey(hashCode)) {
            String modified = "y".repeat(Math.max(0, len));
            pointerMap.put(hashCode, modified);
        }
    }

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar,
                    ParsedModifiers modifiers, PackBuffer output) {
        // For 'p' format: count is repeat count (p3 packs 3 pointers)
        // For 'P' format: count is minimum string length (P3 packs 1 pointer, string must be >=3 bytes)
        int numPointers;
        if (format == 'p') {
            // 'p' format: count is a repeat count
            numPointers = count;
            if (hasStar) {
                // Pack all remaining values
                numPointers = values.size() - valueIndex;
            }
        } else {
            // 'P' format: always pack exactly 1 pointer
            // The count parameter specifies minimum string length (not used in packing, just in unpacking)
            numPointers = 1;
        }

        // Pack the specified number of pointers
        for (int i = 0; i < numPointers; i++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use empty string as per Perl behavior
                value = new RuntimeScalar("");
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }

            long ptr = 0L;

            // Check if value is defined (not undef)
            if (value.getDefinedBoolean()) {
                String str = value.toString();
                // Use hashCode as a unique identifier, but as a long for 64-bit pointer
                ptr = Integer.toUnsignedLong(str.hashCode());
                pointerMap.put((int) ptr, str);  // Still use int key for the map
            }

            // Write as 8 bytes (64-bit pointer) 
            // Use the already-parsed endianness
            if (modifiers.bigEndian) {
                PackWriter.writeLongBigEndian(output, ptr);
            } else {
                PackWriter.writeLongLittleEndian(output, ptr);
            }
        }

        return valueIndex;
    }
}
