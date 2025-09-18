package org.perlonjava.operators.pack;

import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Handler for Unicode format 'U'.
 * Note: This handler requires special state management for byteMode and hasUnicodeInNormalMode
 * which are maintained in the main Pack.java class.
 */
public class UnicodePackHandler implements PackFormatHandler {

    @Override
    public int pack(List<RuntimeScalar> values, int valueIndex, int count, boolean hasStar, 
                    ParsedModifiers modifiers, ByteArrayOutputStream output) {
        // Note: This handler is not used directly due to state management requirements.
        // The Unicode format is handled specially in Pack.java to manage byteMode and 
        // hasUnicodeInNormalMode state variables.
        throw new UnsupportedOperationException("Unicode format requires special handling in Pack.java");
    }
}
