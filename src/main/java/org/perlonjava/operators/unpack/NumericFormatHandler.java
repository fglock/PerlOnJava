package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Base class for numeric format handlers (s, S, i, I, l, L, q, Q, n, N, v, V, f, d, etc.).
 * 
 * <p><b>Key Principle:</b> All numeric formats operate in <b>byte mode</b>, not character mode.
 * This means they read from the ByteBuffer wrapping the UTF-8 encoded bytes, not from the
 * character code array.</p>
 * 
 * <p><b>Mode Switching Behavior:</b></p>
 * <ul>
 *   <li><b>Before reading:</b> Save current mode and switch to byte mode if in character mode</li>
 *   <li><b>During reading:</b> Read from ByteBuffer using appropriate byte order (endianness)</li>
 *   <li><b>After reading:</b> Restore original mode (if was in character mode, switch back)</li>
 * </ul>
 * 
 * <p><b>Why Byte Mode?</b></p>
 * <p>Numeric formats represent multi-byte values in specific byte orders. For example, the
 * 32-bit integer 0x12345678 in big-endian is stored as bytes: 0x12, 0x34, 0x56, 0x78.
 * Reading from the ByteBuffer ensures correct byte order interpretation according to the
 * format's endianness (N=big-endian, V=little-endian, etc.).</p>
 * 
 * <p><b>UTF-8 String Handling:</b></p>
 * <p>When unpacking from a string with the UTF-8 flag set (characters > 255), the originalBytes
 * array contains UTF-8 encoded bytes. Numeric formats still read from this byte representation:
 * <ul>
 *   <li>Example: Character U+1FFC is stored as UTF-8 bytes: 0xE1, 0x9F, 0xBC</li>
 *   <li>A subsequent 'n' format reads the next 2 bytes: 0x9F, 0xBC as a short</li>
 * </ul>
 * 
 * <p><b>Contrast with Character Mode Formats:</b></p>
 * <ul>
 *   <li><b>C format:</b> Reads character codes (0-255) from codePoints array</li>
 *   <li><b>N format:</b> Reads 4 bytes from ByteBuffer</li>
 * </ul>
 * 
 * <p><b>Format Handlers:</b></p>
 * <ul>
 *   <li><b>ShortHandler:</b> Reads 2 bytes (signed/unsigned)</li>
 *   <li><b>LongHandler:</b> Reads 4 bytes (signed/unsigned)</li>
 *   <li><b>QuadHandler:</b> Reads 8 bytes (signed/unsigned)</li>
 *   <li><b>FloatHandler:</b> Reads 4 bytes as float</li>
 *   <li><b>DoubleHandler:</b> Reads 8 bytes as double</li>
 * </ul>
 * 
 * @see UnpackState#switchToByteMode()
 * @see UnpackState#switchToCharacterMode()
 */
public abstract class NumericFormatHandler implements FormatHandler {

    public static class ShortHandler extends NumericFormatHandler {
        private final boolean signed;

        public ShortHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 2) {
                    break;
                }
                short value = buffer.getShort();
                if (signed) {
                    output.add(new RuntimeScalar(value));
                } else {
                    output.add(new RuntimeScalar(value & 0xFFFF));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 2;
        }
    }

    public static class LongHandler extends NumericFormatHandler {
        private final boolean signed;

        public LongHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 4) {
                    break;
                }
                // Read as long to avoid sign extension issues with unsigned values
                long value = buffer.getInt();
                if (signed) {
                    output.add(new RuntimeScalar((int) value));
                } else {
                    output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 4;
        }
    }

    /**
     * Handler for 64-bit long formats (j, J, q, Q).
     */
    public static class QuadHandler extends NumericFormatHandler {
        private final boolean signed;

        public QuadHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 8) {
                    break;
                }
                // Read 8 bytes for quad/Perl IV formats
                long value = buffer.getLong();
                if (signed) {
                    output.add(new RuntimeScalar(value));
                } else {
                    // For unsigned Q format, we need to preserve precision
                    // For 32-bit Perl emulation, values > 2^53 lose precision as doubles
                    if (value < 0) {
                        // Negative values represent large unsigned values
                        // Store as string to preserve full unsigned value
                        output.add(new RuntimeScalar(Long.toUnsignedString(value)));
                    } else if (value > 9007199254740992L) { // 2^53
                        // Positive values > 2^53 lose precision as doubles
                        // Store as string to preserve exact value
                        output.add(new RuntimeScalar(Long.toString(value)));
                    } else {
                        // Value can be stored exactly
                        output.add(new RuntimeScalar(value));
                    }
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 8; // j, J, q, Q are all 8-byte formats
        }
    }

    public static class NetworkShortHandler extends NumericFormatHandler {
        private final boolean signed;

        public NetworkShortHandler() {
            this(false); // Default to unsigned
        }

        public NetworkShortHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // For UTF-8 strings, n format reads CHARACTER CODES (masking to 0xFF), not UTF-8 bytes
            if (state.isUTF8Data() && state.isCharacterMode()) {
                // Read 2 character codes and assemble into a short
                for (int i = 0; i < count; i++) {
                    if (state.remainingCodePoints() < 2) {
                        break;
                    }
                    // Network byte order = big-endian
                    int b1 = state.nextCodePoint() & 0xFF;
                    int b2 = state.nextCodePoint() & 0xFF;
                    int value = (b1 << 8) | b2;
                    
                    if (signed) {
                        output.add(new RuntimeScalar((short) value));
                    } else {
                        output.add(new RuntimeScalar(value));
                    }
                }
                return;
            }
            
            // For non-UTF-8 strings, use original byte buffer logic
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 2) {
                    break;
                }
                int b1 = buffer.get() & 0xFF;
                int b2 = buffer.get() & 0xFF;
                int value = (b1 << 8) | b2;

                if (signed) {
                    // Convert to signed short (sign extend from 16 bits)
                    output.add(new RuntimeScalar((short) value));
                } else {
                    // Unsigned
                    output.add(new RuntimeScalar(value));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 2;
        }
    }

    public static class NetworkLongHandler extends NumericFormatHandler {
        private final boolean signed;

        public NetworkLongHandler() {
            this(false); // Default to unsigned
        }

        public NetworkLongHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // For UTF-8 strings, N format reads CHARACTER CODES (masking to 0xFF), not UTF-8 bytes
            // This matches Perl's behavior where unpack("C*", $utf8_string) returns character codes
            if (state.isUTF8Data() && state.isCharacterMode()) {
                // Read 4 character codes and assemble into a long
                for (int i = 0; i < count; i++) {
                    if (state.remainingCodePoints() < 4) {
                        break;
                    }
                    long value = 0;
                    // Network byte order = big-endian
                    for (int j = 0; j < 4; j++) {
                        int charCode = state.nextCodePoint() & 0xFF;  // Mask to byte
                        value = (value << 8) | charCode;
                    }
                    if (signed) {
                        output.add(new RuntimeScalar((int) value));
                    } else {
                        output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
                    }
                }
                return;
            }
            
            // For non-UTF-8 strings, use original byte buffer logic
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 4) {
                    break;
                }
                long value = 0;
                for (int j = 0; j < 4; j++) {
                    value = (value << 8) | (buffer.get() & 0xFF);
                }

                if (signed) {
                    // Convert to signed int (sign extend from 32 bits)
                    output.add(new RuntimeScalar((int) value));
                } else {
                    // Unsigned
                    output.add(new RuntimeScalar(value));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 4;
        }
    }

    public static class VAXShortHandler extends NumericFormatHandler {
        private final boolean signed;

        public VAXShortHandler() {
            this(false); // Default to unsigned
        }

        public VAXShortHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 2) {
                    break;
                }
                int b1 = buffer.get() & 0xFF;
                int b2 = buffer.get() & 0xFF;
                int value = b1 | (b2 << 8);

                if (signed) {
                    // Convert to signed short (sign extend from 16 bits)
                    output.add(new RuntimeScalar((short) value));
                } else {
                    // Unsigned
                    output.add(new RuntimeScalar(value));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 2;
        }
    }

    public static class VAXLongHandler extends NumericFormatHandler {
        private final boolean signed;

        public VAXLongHandler() {
            this(false); // Default to unsigned
        }

        public VAXLongHandler(boolean signed) {
            this.signed = signed;
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // For UTF-8 strings, V format reads CHARACTER CODES (masking to 0xFF), not UTF-8 bytes
            if (state.isUTF8Data() && state.isCharacterMode()) {
                // Read 4 character codes and assemble into a long
                for (int i = 0; i < count; i++) {
                    if (state.remainingCodePoints() < 4) {
                        break;
                    }
                    long value = 0;
                    // VAX byte order = little-endian
                    for (int j = 0; j < 4; j++) {
                        int charCode = state.nextCodePoint() & 0xFF;  // Mask to byte
                        value |= (long) charCode << (j * 8);
                    }
                    if (signed) {
                        output.add(new RuntimeScalar((int) value));
                    } else {
                        output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
                    }
                }
                return;
            }
            
            // For non-UTF-8 strings, use original byte buffer logic
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 4) {
                    break;
                }
                long value = 0;
                for (int j = 0; j < 4; j++) {
                    value |= (long) (buffer.get() & 0xFF) << (j * 8);
                }

                if (signed) {
                    // Convert to signed int (sign extend from 32 bits)
                    output.add(new RuntimeScalar((int) value));
                } else{
                    // Unsigned
                    output.add(new RuntimeScalar(value));
                }
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 4;
        }
    }

    public static class FloatHandler extends NumericFormatHandler {
        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 4) {
                    break;
                }
                output.add(new RuntimeScalar(buffer.getFloat()));
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 4;
        }
    }

    public static class DoubleHandler extends NumericFormatHandler {
        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            // Save current mode
            boolean wasCharacterMode = state.isCharacterMode();

            // Switch to byte mode for numeric reading
            if (wasCharacterMode) {
                state.switchToByteMode();
            }

            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                if (buffer.remaining() < 8) {
                    break;
                }
                output.add(new RuntimeScalar(buffer.getDouble()));
            }

            // Restore original mode
            if (wasCharacterMode) {
                state.switchToCharacterMode();
            }
        }

        @Override
        public int getFormatSize() {
            return 8;
        }
    }
}
