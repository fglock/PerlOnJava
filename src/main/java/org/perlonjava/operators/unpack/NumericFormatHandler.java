package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Base class for numeric format handlers.
 * All numeric formats work with bytes, not characters.
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
