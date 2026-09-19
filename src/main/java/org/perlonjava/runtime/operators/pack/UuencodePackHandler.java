package org.perlonjava.runtime.operators.pack;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

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
        // Perl's u format encodes octets.  Do not turn Latin-1 byte strings
        // into UTF-8 here: that changes both their length and their payload.
        // A requested line width may be at most 63; Perl warns and clamps it.
        int lineWidth = count;
        if (lineWidth > 63) {
            WarnDie.warn(new RuntimeScalar("Field too wide in 'u' format in pack"),
                    new RuntimeScalar(""));
            lineWidth = 63;
        }
        // Counts below three (including an omitted count and *) select the
        // default width. Other widths are rounded down to complete triplets.
        lineWidth = hasStar || lineWidth < 3 ? 45 : lineWidth / 3 * 3;
        PackWriter.writeUuencodedString(output, str, lineWidth);
        return valueIndex;
    }
}
