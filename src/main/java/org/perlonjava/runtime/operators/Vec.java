package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeVecLvalue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Provides operations similar to Perl's vec function, allowing manipulation of
 * strings at the bit level.
 */
public class Vec {

    /**
     * Extracts a bit field from a string and returns it as a RuntimeScalar.
     *
     * @param args A RuntimeList containing the string, offset, and number of bits.
     * @return A RuntimeScalar representing the extracted bit field.
     * @throws PerlCompilerException if the string contains invalid characters or if the bit size is out of range.
     */
    public static RuntimeScalar vec(RuntimeList args) throws PerlCompilerException {
        RuntimeScalar strScalar = (RuntimeScalar) args.elements.get(0);

        // Check if the scalar is undefined - vec should not autovivify on read
        if (!strScalar.getDefinedBoolean()) {
            // Return 0 for undefined values without autovivifying
            int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
            int bits = ((RuntimeScalar) args.elements.get(2)).getInt();
            return new RuntimeVecLvalue(strScalar, offset, bits, 0);
        }

        String str = strScalar.toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

        if (bits <= 0 || bits > 32) {
            throw new PerlCompilerException("BITS must be between 1 and 32");
        }

        // Handle negative offset
        if (offset < 0) {
            return new RuntimeVecLvalue(strScalar, offset, bits, 0);
        }

        // Check for potential overflow in offset * bits calculation
        // Use long arithmetic to detect overflow
        long longByteOffset = ((long) offset * bits) / 8;
        if (longByteOffset > Integer.MAX_VALUE || longByteOffset >= data.length) {
            return new RuntimeVecLvalue(strScalar, offset, bits, 0);
        }

        int byteOffset = (int) longByteOffset;
        int bitOffset = (offset * bits) % 8;

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int value = 0;

        if (bits == 32 && byteOffset + 3 < data.length) {
            value = buffer.getInt(byteOffset);
        } else if (bits == 16 && byteOffset + 1 < data.length) {
            value = buffer.getShort(byteOffset) & 0xFFFF;
        } else if (bits == 8 && byteOffset < data.length) {
            value = buffer.get(byteOffset) & 0xFF;
        } else {
            for (int i = 0; i < bits; i++) {
                int byteIndex = byteOffset + (bitOffset + i) / 8;
                int bitIndex = (bitOffset + i) % 8;
                if (byteIndex < data.length) {
                    value |= ((data[byteIndex] >> bitIndex) & 1) << i;
                }
            }
        }

        return new RuntimeVecLvalue(strScalar, offset, bits, value);
    }

    /**
     * Sets a bit field in a string to a specified value.
     *
     * @param args  A RuntimeList containing the string, offset, and number of bits.
     * @param value The value to set the bit field to.
     * @return The RuntimeScalar representing the value that was set.
     * @throws PerlCompilerException if the string contains invalid characters or if the bit size is out of range.
     */
    public static RuntimeScalar set(RuntimeList args, RuntimeScalar value) throws PerlCompilerException {
        String str = args.elements.get(0).toString();
        int offset = ((RuntimeScalar) args.elements.get(1)).getInt();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

        if (bits <= 0 || bits > 32) {
            throw new PerlCompilerException("BITS must be between 1 and 32");
        }

        // Handle negative offset - this should throw an error for lvalue context
        if (offset < 0) {
            throw new PerlCompilerException("Negative offset to vec in lvalue context");
        }

        // Check for potential overflow in offset * bits calculation
        // Use long arithmetic to detect overflow
        long longByteOffset = ((long) offset * bits) / 8;
        if (longByteOffset > Integer.MAX_VALUE) {
            throw new PerlCompilerException("Out of memory during vec in lvalue context");
        }

        int byteOffset = (int) longByteOffset;
        int bitOffset = (offset * bits) % 8;

        int bytesToWrite = (bits + bitOffset + 7) / 8;
        if (byteOffset + bytesToWrite > data.length) {
            byte[] newData = new byte[byteOffset + bytesToWrite];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }

        int val = value.getInt();
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        if (bits == 32 && byteOffset + 3 < data.length) {
            buffer.putInt(byteOffset, val);
        } else if (bits == 16 && byteOffset + 1 < data.length) {
            buffer.putShort(byteOffset, (short) val);
        } else if (bits == 8 && byteOffset < data.length) {
            buffer.put(byteOffset, (byte) val);
        } else {
            for (int i = 0; i < bits; i++) {
                int byteIndex = byteOffset + (bitOffset + i) / 8;
                int bitIndex = (bitOffset + i) % 8;
                if (byteIndex < data.length) {
                    if ((val & (1 << i)) != 0) {
                        data[byteIndex] |= (byte) (1 << bitIndex);
                    } else {
                        data[byteIndex] &= (byte) ~(1 << bitIndex);
                    }
                }
            }
        }

        ((RuntimeScalar) args.elements.getFirst()).set(new String(data, StandardCharsets.ISO_8859_1));
        return value;
    }
}