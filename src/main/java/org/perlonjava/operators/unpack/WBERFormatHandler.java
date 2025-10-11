package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarType;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'w' format - BER compressed integer.
 */
public class WBERFormatHandler implements FormatHandler {
    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            if (!state.isCharacterMode()) {
                ByteBuffer buffer = state.getBuffer();
                if (!buffer.hasRemaining()) {
                    break;
                }

                // Use BigInteger to handle arbitrarily large values
                BigInteger value = BigInteger.ZERO;
                int b;
                do {
                    if (!buffer.hasRemaining()) {
                        throw new RuntimeException("Unterminated compressed integer");
                    }
                    b = buffer.get() & 0xFF;
                    value = value.shiftLeft(7).or(BigInteger.valueOf(b & 0x7F));
                } while ((b & 0x80) != 0);

                // Convert to RuntimeScalar, preserving exact value
                RuntimeScalar scalar;
                if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0 &&
                        value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0) {
                    // Fits in a long, but check if it would lose precision in scientific notation
                    long longValue = value.longValue();
                    String longAsString = String.valueOf(longValue);

                    // If the string representation would be in scientific notation, preserve as string
                    if (longAsString.contains("E") || longAsString.contains("e") || longValue >= 1e15) {
                        // Large number that would be formatted in scientific notation - preserve as string
                        String strValue = value.toString();
                        scalar = new RuntimeScalar(strValue);
                        scalar.type = RuntimeScalarType.STRING;
                        scalar.value = strValue;
                    } else {
                        // Small enough to preserve as long without precision loss
                        scalar = new RuntimeScalar(longValue);
                    }
                } else {
                    // Too large for long - must preserve as numeric string
                    String strValue = value.toString();
                    scalar = new RuntimeScalar(strValue);
                    // Force the scalar to remain a string
                    scalar.type = RuntimeScalarType.STRING;
                    scalar.value = strValue;
                }
                output.add(scalar);
            } else {
                // In character mode, treat each character as a byte
                if (!state.hasMoreCodePoints()) {
                    break;
                }

                // Use BigInteger to handle arbitrarily large values
                BigInteger value = BigInteger.ZERO;
                int b;
                do {
                    if (!state.hasMoreCodePoints()) {
                        throw new RuntimeException("Unterminated compressed integer");
                    }
                    b = state.nextCodePoint() & 0xFF;
                    value = value.shiftLeft(7).or(BigInteger.valueOf(b & 0x7F));
                } while ((b & 0x80) != 0);

                // Convert to RuntimeScalar, preserving exact value
                RuntimeScalar scalar;
                if (value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0 &&
                        value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0) {
                    // Fits in a long, but check if it would lose precision in scientific notation
                    long longValue = value.longValue();
                    String longAsString = String.valueOf(longValue);

                    // If the string representation would be in scientific notation, preserve as string
                    if (longAsString.contains("E") || longAsString.contains("e") || longValue >= 1e15) {
                        // Large number that would be formatted in scientific notation - preserve as string
                        String strValue = value.toString();
                        scalar = new RuntimeScalar(strValue);
                        scalar.type = RuntimeScalarType.STRING;
                        scalar.value = strValue;
                    } else {
                        // Small enough to preserve as long without precision loss
                        scalar = new RuntimeScalar(longValue);
                    }
                } else {
                    // Too large for long - must preserve as numeric string
                    String strValue = value.toString();
                    scalar = new RuntimeScalar(strValue);
                    // Force the scalar to remain a string
                    scalar.type = RuntimeScalarType.STRING;
                    scalar.value = strValue;
                }
                output.add(scalar);
            }
        }
    }

    @Override
    public int getFormatSize() {
        return -1; // Variable size
    }
}