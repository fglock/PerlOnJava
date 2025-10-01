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
        // This handler is no longer used - W format is handled specially in Pack.java
        // like U format, to properly track Unicode mode
        throw new RuntimeException("WideCharacterPackHandler should not be called - W format is handled specially");
    }
}
