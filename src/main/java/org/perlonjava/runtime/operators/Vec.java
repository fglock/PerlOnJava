package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeVecLvalue;

import java.math.BigInteger;
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
        BigInteger offset = ((RuntimeScalar) args.elements.get(1)).getSignedBigint();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        // Check if the scalar is undefined - vec should not autovivify on read
        if (!strScalar.getDefinedBoolean()) {
            // Return 0 for undefined values without autovivifying
            return vecLvalue(strScalar, offset, bits, new RuntimeScalar(0));
        }

        String str = strScalar.toString();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw new PerlCompilerException("Illegal number of bits in vec");
        }

        // Handle negative offset
        if (offset.signum() < 0) {
            return vecLvalue(strScalar, offset, bits, new RuntimeScalar(0));
        }

        // Check for potential overflow in offset * bits calculation
        // Use long arithmetic to detect overflow
        BigInteger bitPosition = offset.multiply(BigInteger.valueOf(bits));
        BigInteger bytePosition = bitPosition.shiftRight(3);
        if (bytePosition.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || bytePosition.compareTo(BigInteger.valueOf(data.length)) >= 0) {
            return vecLvalue(strScalar, offset, bits, new RuntimeScalar(0));
        }

        int byteOffset = bytePosition.intValue();
        int bitOffset = bitPosition.and(BigInteger.valueOf(7)).intValue();

        if (bits >= 8) {
            // Multi-byte vec fields are big-endian and bytes beyond the end of
            // the source are read as zero. Building the value explicitly also
            // handles a 64-bit field with only 1..7 source bytes remaining.
            long value = 0;
            int byteCount = bits / 8;
            for (int i = 0; i < byteCount; i++) {
                value <<= 8;
                if (byteOffset + i < data.length) {
                    value |= data[byteOffset + i] & 0xffL;
                }
            }
            RuntimeScalar result = bits == 64 && value < 0
                    ? new RuntimeScalar(new BigInteger(Long.toUnsignedString(value)))
                    : new RuntimeScalar(value);
            return vecLvalue(strScalar, offset, bits, result);
        } else {
            long value = 0;
            for (int i = 0; i < bits; i++) {
                int byteIndex = byteOffset + (bitOffset + i) / 8;
                int bitIndex = (bitOffset + i) % 8;
                if (byteIndex < data.length) {
                    value |= (long) ((data[byteIndex] >> bitIndex) & 1) << i;
                }
            }
            RuntimeScalar result = bits == 64 && value < 0
                    ? new RuntimeScalar(new BigInteger(Long.toUnsignedString(value)))
                    : new RuntimeScalar(value);
            return vecLvalue(strScalar, offset, bits, result);
        }
    }

    private static RuntimeVecLvalue vecLvalue(RuntimeScalar parent, BigInteger offset,
                                               int bits, RuntimeScalar value) {
        RuntimeVecLvalue result = new RuntimeVecLvalue(parent, offset, bits, value);
        result.tainted = GlobalContext.isTaintModeActive() && parent.isTainted();
        return result;
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
        BigInteger offset = ((RuntimeScalar) args.elements.get(1)).getSignedBigint();
        int bits = ((RuntimeScalar) args.elements.get(2)).getInt();

        byte[] data = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c > 0xFF) {
                throw new PerlCompilerException("Use of strings with code points over 0xFF as arguments to vec is forbidden");
            }
            data[i] = (byte) c;
        }

        if (bits != 1 && bits != 2 && bits != 4 && bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw new PerlCompilerException("Illegal number of bits in vec");
        }
        if (offset.signum() < 0) {
            throw new PerlCompilerException("Negative offset to vec in lvalue context");
        }

        // Check for potential overflow in offset * bits calculation
        // Use long arithmetic to detect overflow
        BigInteger bitPosition = offset.multiply(BigInteger.valueOf(bits));
        BigInteger bytePosition = bitPosition.shiftRight(3);
        if (bytePosition.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new PerlCompilerException("Out of memory during vec in lvalue context");
        }

        int byteOffset = bytePosition.intValue();
        int bitOffset = bitPosition.and(BigInteger.valueOf(7)).intValue();

        int bytesToWrite = (bits + bitOffset + 7) / 8;
        if (byteOffset > Integer.MAX_VALUE - bytesToWrite) {
            throw new PerlCompilerException("Out of memory during vec in lvalue context");
        }
        if (byteOffset + bytesToWrite > data.length) {
            byte[] newData = new byte[byteOffset + bytesToWrite];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        if (bits == 64 && byteOffset + 8 <= data.length) {
            long longVal = value.getLong();
            buffer.putLong(byteOffset, longVal);
        } else if (bits == 32 && byteOffset + 4 <= data.length) {
            // Use getLong() and truncate to int to preserve bit pattern for unsigned
            // 32-bit values (getInt() clamps values > Integer.MAX_VALUE via double→int)
            buffer.putInt(byteOffset, (int) value.getLong());
        } else if (bits == 16 && byteOffset + 1 < data.length) {
            buffer.putShort(byteOffset, (short) value.getInt());
        } else if (bits == 8 && byteOffset < data.length) {
            buffer.put(byteOffset, (byte) value.getInt());
        } else {
            int val = value.getInt();
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
