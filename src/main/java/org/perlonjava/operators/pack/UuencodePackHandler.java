package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.util.List;

/**
 * Handler for uuencoded string format 'u'.
 */
public class UuencodePackHandler implements PackFormatHandler {

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
        String str = value.toString();
        PackWriter.writeUuencodedString(output, str);
        return valueIndex;
    }
}
