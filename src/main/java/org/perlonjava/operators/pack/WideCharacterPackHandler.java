package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Handler for wide character format 'W'.
 */
public class WideCharacterPackHandler implements PackFormatHandler {

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar, 
                    ParsedModifiers modifiers, ByteArrayOutputStream output) {
        for (int j = 0; j < count; j++) {
            RuntimeScalar value;
            if (valueIndex >= values.size()) {
                // If no more arguments, use 0 as per Perl behavior (empty string converts to 0)
                value = new RuntimeScalar(0);
            } else {
                value = values.get(valueIndex);
                valueIndex++;
            }
            PackHelper.packW(value, output);
        }
        return valueIndex;
    }
}
