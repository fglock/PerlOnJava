package org.perlonjava.runtime.operators.unpack;

import org.perlonjava.runtime.operators.UnpackState;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Handles 'U' format - Unicode character.
 * U format reads based on current mode but does NOT change the mode.
 */
public class UFormatHandler implements FormatHandler {
    private final boolean startsWithU;

    public UFormatHandler(boolean startsWithU) {
        this.startsWithU = startsWithU;
    }

    @Override
    public void unpack(UnpackState state, List<RuntimeBase> output, int count, boolean isStarCount) {
        for (int i = 0; i < count; i++) {
            // The U format reads one Unicode code point.  Real-Perl
            // semantics:
            //
            //   * Template starts with bare `U` (e.g. `"U*"`) or has
            //     switched to Perl "character mode" via `U0`: read one
            //     code point directly from the string.  Default for
            //     most templates used by Perl code (e.g. JSON::PP's
            //     `_encode_ascii`: `unpack("U*", $str)` on a character
            //     string yields the string's code points one by one).
            //   * After `C0` (or default when template does NOT start
            //     with bare `U`): bytes are interpreted as UTF-8 and one
            //     character is decoded per consumed group.
            //
            // `UnpackState`'s internal `isCharacterMode()` keys off a
            // naming calibrated for the numerous other format handlers
            // (`StringFormatHandler`, `NumericFormatHandler`, …) and
            // cannot be changed without auditing all of them.  Here we
            // combine `startsWithU` (set when the template begins with
            // bare `U`) with `!isCharacterMode()` (set after `U0`).
            //
            // When we're in byte mode but reading code points (the
            // `U0…U` case), we advance both `codePointIndex` AND
            // `buffer.position` so a later `switchToCharacterMode`
            // (triggered by e.g. `C0`) recomputes `codePointIndex`
            // correctly from the byte offset.  Without that, the
            // code-point progress inside this handler would be lost the
            // moment the template switches back to character mode.
            //
            // Covered by `op/utf8decode.t` (the `C0U*` path),
            // `op/pack.t` (the `U0U C0 W` sequence), and the JSON::PP
            // `_encode_ascii` round-trip in `unpack.t` (the starts-with-
            // U path).
            boolean readCodePoints = startsWithU || !state.isCharacterMode();
            if (readCodePoints) {
                if (!state.hasMoreCodePoints()) {
                    break;
                }
                int cp = state.nextCodePoint();
                output.add(new RuntimeScalar(cp));
                // Keep the byte buffer in sync so subsequent mode
                // switches don't rewind us to the start.
                if (!state.isCharacterMode()) {
                    ByteBuffer buf = state.getBuffer();
                    if (buf != null) {
                        // `originalBytes` layout mirrors what
                        // `UnpackState.switchToByteMode` uses: ISO-8859-1
                        // (1 byte per code point) when `!isUTF8Data`,
                        // UTF-8 (variable length) when `isUTF8Data`.
                        int advance = state.isUTF8Data()
                                ? (cp <= 0x7F ? 1
                                   : cp <= 0x7FF ? 2
                                   : cp <= 0xFFFF ? 3
                                   : cp <= 0x10FFFF ? 4
                                   : 5)
                                : 1;
                        int newPos = Math.min(buf.position() + advance, buf.limit());
                        buf.position(newPos);
                    }
                }
            } else {
                ByteBuffer buffer = state.getBuffer();
                if (!buffer.hasRemaining()) {
                    break; // Just stop unpacking
                }
                long codePoint = readUTF8Character(buffer);
                output.add(new RuntimeScalar(codePoint));
            }
        }
    }

    /**
     * Read one Perl UTF-8 character from the byte stream.
     *
     * <p>Perl's C0U decoder deliberately retains its historical extended UTF-8
     * representation: it accepts up to six-byte sequences, including surrogate
     * and beyond-Unicode scalar values.  Java's UTF-8 decoder cannot be used
     * here because it rejects all of those values.</p>
     */
    private long readUTF8Character(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            throw new PerlCompilerException("unpack: no data for UTF-8 character");
        }

        int startPos = buffer.position();
        int firstByte = buffer.get(startPos) & 0xFF;

        // 0x80..0xBF are continuation bytes — report and consume them singly.
        if (firstByte >= 0x80 && firstByte < 0xC0) {
            warn("Malformed UTF-8 character: unexpected continuation byte 0x" + hexByte(firstByte));
            buffer.position(startPos + 1);
            return firstByte;
        }

        // ASCII fast path: consume one byte and return
        if ((firstByte & 0x80) == 0) {
            buffer.position(startPos + 1);
            return firstByte;
        }

        int bytesNeeded;
        long codePoint;

        if ((firstByte & 0xE0) == 0xC0) {
            bytesNeeded = 1;
            codePoint = firstByte & 0x1F;
        } else if ((firstByte & 0xF0) == 0xE0) {
            // 3-byte sequence
            bytesNeeded = 2;
            codePoint = firstByte & 0x0F;
        } else if ((firstByte & 0xF8) == 0xF0) {
            // 4-byte sequence
            bytesNeeded = 3;
            codePoint = firstByte & 0x07;
        } else if ((firstByte & 0xFC) == 0xF8) {
            bytesNeeded = 4;
            codePoint = firstByte & 0x03;
        } else if ((firstByte & 0xFE) == 0xFC) {
            bytesNeeded = 5;
            codePoint = firstByte & 0x01;
        } else {
            // FE and FF are the historical seven- and thirteen-byte prefixes.
            int required = firstByte == 0xFE ? 7 : 13;
            warn("Malformed UTF-8 character: " + (buffer.limit() - startPos)
                    + " byte available, need " + required + "; byte 0x" + hexByte(firstByte));
            if (startPos + 1 < buffer.limit()) {
                int nextByte = buffer.get(startPos + 1) & 0xFF;
                if ((nextByte & 0xC0) != 0x80) {
                    warn("Malformed UTF-8 character: unexpected non-continuation byte 0x"
                            + hexByte(nextByte) + ", immediately after start byte 0x"
                            + hexByte(firstByte));
                }
            }
            buffer.position(startPos + 1);
            return firstByte;
        }

        int available = buffer.limit() - (startPos + 1);

        // Diagnose an available non-continuation byte before diagnosing a
        // truncated sequence.  Perl gives the former precedence.
        int pos = startPos + 1;
        for (int i = 0; i < Math.min(bytesNeeded, available); i++) {
            int nextByte = buffer.get(pos + i) & 0xFF;
            if ((nextByte & 0xC0) != 0x80) {
                warn("Malformed UTF-8 character: unexpected non-continuation byte 0x"
                        + hexByte(nextByte) + ", immediately after start byte 0x" + hexByte(firstByte));
                if (nextByte < 0xC0 && isPotentiallyOverlongLead(firstByte, bytesNeeded)) {
                    warn("Malformed UTF-8 character: overlong UTF-8 sequence");
                }
                // This invalid byte belongs to the failed character; do not
                // diagnose it again as a new start character.
                buffer.position(pos + i + 1);
                return firstByte;
            }
        }

        // Check we have enough bytes after checking the bytes that are
        // present.  A malformed short sequence has one primary diagnostic;
        // old two-to-six byte prefixes also retain Perl's secondary short
        // diagnostic.
        if (available < bytesNeeded) {
            warn("Malformed UTF-8 character: " + (available + 1)
                    + " byte" + (available == 0 ? "" : "s")
                    + " available, need " + (bytesNeeded + 1));
            if (isShortSequenceWithSecondaryDiagnostic(firstByte)) {
                warn("Malformed UTF-8 character: overlong UTF-8 sequence");
            }
            buffer.position(buffer.limit());
            return firstByte;
        }

        for (int i = 0; i < bytesNeeded; i++) {
            int nextByte = buffer.get(pos + i) & 0xFF;
            codePoint = (codePoint << 6) | (nextByte & 0x3F);
        }

        long minimum = switch (bytesNeeded) {
            case 1 -> 0x80L;
            case 2 -> 0x800L;
            case 3 -> 0x10000L;
            case 4 -> 0x200000L;
            case 5 -> 0x4000000L;
            default -> throw new IllegalStateException("unexpected UTF-8 length");
        };
        if (codePoint < minimum) {
            warn("Malformed UTF-8 character: overlong UTF-8 sequence");
        }

        // Advance the buffer position by the full sequence length (1 + bytesNeeded)
        buffer.position(startPos + 1 + bytesNeeded);

        return codePoint;
    }

    private static void warn(String message) {
        // Core unpack diagnostics are observable through $SIG{__WARN__} even
        // when lexical warning categories are not enabled by the caller.
        WarnDie.warn(new RuntimeScalar(message), new RuntimeScalar(""));
    }

    private static String hexByte(int value) {
        return String.format("%02x", value & 0xff);
    }

    private static boolean isPotentiallyOverlongLead(int firstByte, int bytesNeeded) {
        return switch (bytesNeeded) {
            case 1 -> firstByte <= 0xC1;
            case 2 -> firstByte == 0xE0;
            case 3 -> firstByte == 0xF0;
            case 4 -> firstByte <= 0xF9;
            case 5 -> true;
            default -> false;
        };
    }

    private static boolean isShortSequenceWithSecondaryDiagnostic(int firstByte) {
        return firstByte == 0xC0 || firstByte == 0xE0 || firstByte == 0xF0
                || firstByte == 0xF8 || firstByte == 0xFC;
    }

    @Override
    public int getFormatSize() {
        return 1; // Variable length, minimum 1
    }
}
