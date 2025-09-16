package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Handles 'x' format - null padding
 */
public class NullPadHandler implements PackFormatHandler {
    @Override
    public int pack(ByteArrayOutputStream output, List<RuntimeScalar> values,
                    int valueIndex, int count, PackModifiers modifiers) {
        // 'x' doesn't consume any values, just writes null bytes
        for (int j = 0; j < count; j++) {
            output.write(0);
        }
        return 0; // consumes no values
    }
}