package org.perlonjava.operators.pack;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Handler for numeric formats (c, C, s, S, i, I, l, L, q, Q, j, J, f, F, d, D, n, N, v, V, w).
 */
public class NumericPackHandler implements PackFormatHandler {
    private final char format;

    public NumericPackHandler(char format) {
        this.format = format;
    }

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

            // Check for Inf/NaN values for integer formats
            if (PackHelper.isIntegerFormat(format)) {
                PackHelper.handleInfinity(value, format);
            }

            // DEBUG: NumericPackHandler processing format '" + format + "' with value: " + value.toString()

            switch (format) {
                case 'c':
                    // Signed char
                    int signedChar = value.getInt();
                    output.write(signedChar & 0xFF);
                    break;
                case 'C':
                    // Unsigned char
                    int intValue = value.getInt();
                    output.write(intValue & 0xFF);
                    break;
                case 's':
                    // Signed short - use endianness if specified
                    if (modifiers.bigEndian) {
                        PackWriter.writeShortBigEndian(output, value.getInt());
                    } else {
                        PackWriter.writeShortLittleEndian(output, value.getInt());
                    }
                    break;
                case 'S':
                    // Unsigned short - use endianness if specified
                    if (modifiers.bigEndian) {
                        PackWriter.writeShortBigEndian(output, value.getInt());
                    } else {
                        PackWriter.writeShort(output, value.getInt());
                    }
                    break;
                case 'l':
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'L':
                case 'J':
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'i':
                case 'I':
                    // Native integer (assume 32-bit little-endian)
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'n':
                    // Network short (always big-endian)
                    PackWriter.writeShortBigEndian(output, value.getInt());
                    break;
                case 'N':
                    // Network long (always big-endian)
                    PackWriter.writeIntBigEndian(output, (long) value.getDouble());
                    break;
                case 'v':
                    // VAX short (always little-endian)
                    PackWriter.writeShortLittleEndian(output, value.getInt());
                    break;
                case 'V':
                    // VAX long (always little-endian)
                    PackWriter.writeIntLittleEndian(output, (long) value.getDouble());
                    break;
                case 'w':
                    // BER compressed integer
                    PackWriter.writeBER(output, (long) value.getDouble());
                    break;
                case 'j':
                    // Perl internal signed integer - treat as long
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'q':
                    // Signed 64-bit quad
                    // DEBUG: Processing q (signed quad) format
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'Q':
                    // Unsigned 64-bit quad
                    // DEBUG: Processing Q (unsigned quad) format
                    PackWriter.writeLong(output, (long) value.getDouble());
                    break;
                case 'f':
                    PackWriter.writeFloat(output, (float) value.getDouble());
                    break;
                case 'F':
                    // F is double-precision float in native format (8 bytes)
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                case 'd':
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                case 'D':
                    // Long double - treat as regular double in Java since we don't have long double
                    System.err.println("DEBUG: Processing D (long double) format as regular double");
                    PackWriter.writeDouble(output, value.getDouble());
                    break;
                default:
                    throw new PerlCompilerException("pack: unsupported format character: " + format);
            }
        }
        return valueIndex;
    }
}
