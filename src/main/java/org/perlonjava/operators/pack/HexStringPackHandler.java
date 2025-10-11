package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for hex string formats 'h' and 'H'.
 */
public class HexStringPackHandler implements PackFormatHandler {
    private final char format;

    public HexStringPackHandler(char format) {
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

        String hexString = value.toString();
        if (hasStar) {
            count = hexString.length();
        }
        PackWriter.writeHexString(output, hexString, count, format);
        return valueIndex;
    }
}
