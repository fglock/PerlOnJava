package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for bit string formats 'b' and 'B'.
 */
public class BitStringPackHandler implements PackFormatHandler {
    private final char format;

    public BitStringPackHandler(char format) {
        this.format = format;
    }

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar,
                    ParsedModifiers modifiers, PackBuffer output) {
        RuntimeScalar value;
        if (valueIndex >= values.size()) {
            // If no more arguments, use empty string as per Perl behavior
            value = new RuntimeScalar("");
        } else {
            value = values.get(valueIndex);
            valueIndex++;
        }

        String bitString = value.toString();
        if (hasStar) {
            count = bitString.length();
        }
        PackWriter.writeBitString(output, bitString, count, format);
        return valueIndex;
    }
}
