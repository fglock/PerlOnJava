package org.perlonjava.operators.unpack;

import org.perlonjava.operators.UnpackState;
import org.perlonjava.runtime.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Contains handlers for numeric format characters.
 */
public class NumericFormatHandler {

    /**
     * Base class for numeric handlers.
     */
    private static abstract class BaseNumericHandler implements FormatHandler {
        protected final int size;
        protected final boolean signed;

        protected BaseNumericHandler(int size, boolean signed) {
            this.size = size;
            this.signed = signed;
        }

        @Override
        public int getFormatSize() {
            return size;
        }

        protected void ensureByteMode(UnpackState state) {
            if (state.isCharacterMode()) {
                state.switchToByteMode();
            }
        }

        protected void checkBuffer(ByteBuffer buffer, boolean isStarCount) {
            if (buffer == null || buffer.remaining() < size) {
                if (!isStarCount) {
                    throw new PerlCompilerException("unpack: not enough data");
                }
            }
        }
    }

    /**
     * Handles 'S' and 's' formats - 16-bit short (unsigned/signed).
     */
    public static class ShortHandler extends BaseNumericHandler {
        public ShortHandler(boolean signed) {
            super(2, signed);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                short value = buffer.getShort();
                if (signed) {
                    output.add(new RuntimeScalar(value));
                } else {
                    output.add(new RuntimeScalar(value & 0xFFFF));
                }
            }
        }
    }

    /**
     * Handles 'L' and 'l' formats - 32-bit long (unsigned/signed).
     */
    public static class LongHandler extends BaseNumericHandler {
        public LongHandler(boolean signed) {
            super(4, signed);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                int value = buffer.getInt();
                if (signed) {
                    output.add(new RuntimeScalar(value));
                } else {
                    output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
                }
            }
        }
    }

    /**
     * Handles 'N' format - 32-bit network byte order (big-endian).
     */
    public static class NetworkLongHandler extends BaseNumericHandler {
        public NetworkLongHandler() {
            super(4, false);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                int value = readIntBigEndian(buffer);
                output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
            }
        }

        private int readIntBigEndian(ByteBuffer buffer) {
            return (buffer.get() & 0xFF) << 24 |
                   (buffer.get() & 0xFF) << 16 |
                   (buffer.get() & 0xFF) << 8 |
                   (buffer.get() & 0xFF);
        }
    }

    /**
     * Handles 'n' format - 16-bit network byte order (big-endian).
     */
    public static class NetworkShortHandler extends BaseNumericHandler {
        public NetworkShortHandler() {
            super(2, false);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                int value = readShortBigEndian(buffer);
                output.add(new RuntimeScalar(value));
            }
        }

        private int readShortBigEndian(ByteBuffer buffer) {
            return (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF);
        }
    }

    /**
     * Handles 'V' format - 32-bit VAX byte order (little-endian).
     */
    public static class VAXLongHandler extends BaseNumericHandler {
        public VAXLongHandler() {
            super(4, false);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                int value = readIntLittleEndian(buffer);
                output.add(new RuntimeScalar(value & 0xFFFFFFFFL));
            }
        }

        private int readIntLittleEndian(ByteBuffer buffer) {
            return (buffer.get() & 0xFF) |
                   (buffer.get() & 0xFF) << 8 |
                   (buffer.get() & 0xFF) << 16 |
                   (buffer.get() & 0xFF) << 24;
        }
    }

    /**
     * Handles 'v' format - 16-bit VAX byte order (little-endian).
     */
    public static class VAXShortHandler extends BaseNumericHandler {
        public VAXShortHandler() {
            super(2, false);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                int value = readShortLittleEndian(buffer);
                output.add(new RuntimeScalar(value));
            }
        }

        private int readShortLittleEndian(ByteBuffer buffer) {
            return (buffer.get() & 0xFF) | (buffer.get() & 0xFF) << 8;
        }
    }

    /**
     * Handles 'f' format - single-precision float.
     */
    public static class FloatHandler extends BaseNumericHandler {
        public FloatHandler() {
            super(4, true);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                float value = buffer.getFloat();
                output.add(new RuntimeScalar(value));
            }
        }
    }

    /**
     * Handles 'd' format - double-precision float.
     */
    public static class DoubleHandler extends BaseNumericHandler {
        public DoubleHandler() {
            super(8, true);
        }

        @Override
        public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
            ensureByteMode(state);
            ByteBuffer buffer = state.getBuffer();

            for (int i = 0; i < count; i++) {
                checkBuffer(buffer, isStarCount);
                if (buffer.remaining() < size) break;

                double value = buffer.getDouble();
                output.add(new RuntimeScalar(value));
            }
        }
    }
}