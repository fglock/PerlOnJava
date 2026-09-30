package org.perlonjava.runtime.io;

import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of Perl's :encoding() IO layer for character set conversions.
 *
 * <p>This layer provides character encoding and decoding functionality similar to
 * Perl's :encoding() layer. It transforms byte streams to/from character streams
 * using the specified character encoding.</p>
 *
 * <p>In Perl, the :encoding() layer is used like:</p>
 * <pre>
 * open(my $fh, '<:encoding(UTF-8)', 'file.txt');
 * </pre>
 *
 * <p>This implementation handles:</p>
 * <ul>
 *   <li>Decoding input bytes to characters according to the specified charset</li>
 *   <li>Encoding output characters to bytes according to the specified charset</li>
 *   <li>Buffering of incomplete multi-byte sequences</li>
 *   <li>Handling of malformed and unmappable characters (replaced with substitution character)</li>
 * </ul>
 *
 * <p>The layer maintains internal state for handling multi-byte character sequences
 * that may span multiple read operations, similar to Perl's behavior.</p>
 *
 * @see IOLayer
 */
public class EncodingLayer implements IOLayer {

    /**
     * Default buffer size for input processing.
     * This size is chosen to balance memory usage with performance.
     */
    private static final int BUFFER_SIZE = 1024;
    private final String layerName;
    /**
     * The character set used for encoding/decoding operations.
     */
    private final Charset charset;
    /**
     * Decoder for converting bytes to characters.
     * Configured to replace malformed input and unmappable characters
     * with the replacement character (U+FFFD).
     */
    private final CharsetDecoder decoder;
    /**
     * Encoder for converting characters to bytes.
     * Configured to replace malformed input and unmappable characters
     * with the charset's default replacement byte sequence.
     */
    private final CharsetEncoder encoder;
    /**
     * Buffer for accumulating input bytes.
     * This buffer holds incomplete multi-byte sequences between read operations,
     * ensuring proper handling of character boundaries.
     */
    private ByteBuffer inputBuffer;
    private final StringBuilder utf8ValidationBuffer = new StringBuilder();
    private final List<String> utf8InputWarnings = new ArrayList<>();
    private String deferredMalformedUtf8Warning;

    /**
     * Constructs a new encoding layer with the specified character set.
     *
     * <p>The layer is configured to handle encoding errors by replacing
     * problematic characters rather than throwing exceptions, matching
     * Perl's default behavior for the :encoding() layer.</p>
     *
     * @param charset the character set to use for encoding/decoding operations
     * @throws NullPointerException if charset is null
     */
    public EncodingLayer(Charset charset, String layerName) {
        this.charset = charset;
        this.decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.inputBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.layerName = layerName;
    }

    public String getLayerName() {
        return layerName;
    }

    public Charset getCharset() {
        return charset;
    }

    /**
     * Processes input by decoding bytes to characters according to the layer's charset.
     *
     * <p>This method accumulates input bytes and decodes them to characters.
     * Incomplete multi-byte sequences are buffered and combined with subsequent
     * input, ensuring proper handling of character boundaries across multiple
     * read operations.</p>
     *
     * <p>The input string is treated as raw bytes where each character represents
     * a single byte value (0-255). This matches Perl's internal representation
     * when dealing with binary data.</p>
     *
     * @param input a string where each character represents a byte (0-255)
     * @return the decoded character string
     */
    @Override
    public String processInput(String input) {
        validateUtf8Input(input, false);
        // Add new bytes to buffer
        for (int i = 0; i < input.length(); i++) {
            if (!inputBuffer.hasRemaining()) {
                // Grow buffer if needed - double the capacity to avoid frequent reallocations
                ByteBuffer newBuffer = ByteBuffer.allocate(inputBuffer.capacity() * 2);
                inputBuffer.flip();
                newBuffer.put(inputBuffer);
                inputBuffer = newBuffer;
            }
            // Extract byte value from character (0-255 range)
            inputBuffer.put((byte) (input.charAt(i) & 0xFF));
        }

        // Prepare buffer for reading
        inputBuffer.flip();

        // Decode available bytes
        // Allocate output buffer with room for worst-case expansion (1 byte -> 1 char)
        CharBuffer output = CharBuffer.allocate(inputBuffer.remaining() * 2);

        // Decode with false for endOfInput to handle incomplete sequences
        CoderResult result = decoder.decode(inputBuffer, output, false);

        // Compact buffer to keep undecoded bytes for next call
        // This preserves incomplete multi-byte sequences
        inputBuffer.compact();

        // Prepare output for reading and convert to string
        output.flip();
        return output.toString();
    }

    /** Return and clear malformed-UTF8 warnings that belong to this input record. */
    public List<String> drainUtf8InputWarnings() {
        List<String> warnings = List.copyOf(utf8InputWarnings);
        utf8InputWarnings.clear();
        return warnings;
    }

    /** Return and clear a malformed continuation warning deferred until string validation. */
    public String takeDeferredMalformedUtf8Warning() {
        String warning = deferredMalformedUtf8Warning;
        deferredMalformedUtf8Warning = null;
        return warning;
    }

    /** Flush an incomplete encoded sequence at EOF, preserving Perl's replacement behavior. */
    public String flushInput() {
        if (inputBuffer.position() == 0) {
            validateUtf8Input("", true);
            return "";
        }
        inputBuffer.flip();
        CharBuffer output = CharBuffer.allocate(Math.max(4, inputBuffer.remaining() * 2));
        decoder.decode(inputBuffer, output, true);
        decoder.flush(output);
        inputBuffer.clear();
        decoder.reset();
        validateUtf8Input("", true);
        output.flip();
        return output.toString();
    }

    private void validateUtf8Input(String input, boolean endOfInput) {
        if (!"utf8".equalsIgnoreCase(layerName)) return;
        for (int i = 0; i < input.length(); i++) {
            utf8ValidationBuffer.append((char) (input.charAt(i) & 0xff));
        }
        int consumed = 0;
        while (consumed < utf8ValidationBuffer.length()) {
            int lead = utf8ValidationBuffer.charAt(consumed) & 0xff;
            if (lead <= 0x7f) {
                consumed++;
                continue;
            }
            if (lead >= 0x80 && lead <= 0xbf) {
                if (deferredMalformedUtf8Warning == null) {
                    deferredMalformedUtf8Warning = "Malformed UTF-8 character: " + escapedByte(lead)
                            + " (unexpected continuation byte 0x" + hexByte(lead)
                            + ", with no preceding start byte)";
                }
                consumed++;
                continue;
            }

            int required = lead >= 0xc2 && lead <= 0xdf ? 2
                    : lead >= 0xe0 && lead <= 0xef ? 3
                    : lead >= 0xf0 && lead <= 0xf4 ? 4 : 1;
            if (required == 1) {
                utf8InputWarnings.add(unicodeMappingWarning(lead));
                consumed++;
                continue;
            }
            boolean valid = true;
            int available = utf8ValidationBuffer.length() - consumed;
            for (int i = 1; i < Math.min(required, available); i++) {
                int continuation = utf8ValidationBuffer.charAt(consumed + i) & 0xff;
                if (continuation < 0x80 || continuation > 0xbf
                        || (i == 1 && ((lead == 0xe0 && continuation < 0xa0)
                        || (lead == 0xed && continuation >= 0xa0)
                        || (lead == 0xf0 && continuation < 0x90)
                        || (lead == 0xf4 && continuation >= 0x90)))) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                utf8InputWarnings.add(unicodeMappingWarning(lead));
                // Consume only the start byte so any following stray
                // continuation is diagnosed independently, like Perl.
                consumed++;
                continue;
            }
            if (available < required) {
                if (!endOfInput) break;
                utf8InputWarnings.add(unicodeMappingWarning(lead));
                consumed = utf8ValidationBuffer.length();
                break;
            }
            consumed += required;
        }
        if (consumed > 0) utf8ValidationBuffer.delete(0, consumed);
    }

    private static String unicodeMappingWarning(int value) {
        return "utf8 \"" + escapedByte(value) + "\" does not map to Unicode";
    }

    private static String escapedByte(int value) {
        return "\\x" + hexByte(value);
    }

    private static String hexByte(int value) {
        return String.format("%02X", value & 0xff);
    }

    /**
     * Processes output by encoding characters to bytes according to the layer's charset.
     *
     * <p>This method encodes the character string to bytes using the specified charset,
     * then converts each byte back to a character in the 0-255 range for transmission
     * through the IO layer stack.</p>
     *
     * <p>This approach maintains compatibility with Perl's internal handling of
     * binary data in strings.</p>
     *
     * @param output the character string to encode
     * @return a string where each character represents a byte (0-255)
     */
    @Override
    public String processOutput(String output) {
        if (!encoder.canEncode(output)) {
            int bad = output.codePoints()
                    .filter(cp -> !encoder.canEncode(new String(Character.toChars(cp))))
                    .findFirst()
                    .orElse(0xFFFD);
            String encoding = charset.equals(StandardCharsets.US_ASCII)
                    ? "ascii"
                    : charset.name().toLowerCase(java.util.Locale.ROOT);
            WarnDie.warnWithCategory(
                    new RuntimeScalar(String.format("\\x{%x} does not map to %s", bad, encoding)),
                    new RuntimeScalar(""),
                    "utf8");
        }
        // Encode string to bytes using the charset
        byte[] bytes = output.getBytes(charset);

        // Convert bytes back to string representation
        // Each byte becomes a character in the 0-255 range
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            result.append((char) (b & 0xFF));
        }
        return result.toString();
    }

    /**
     * Resets the encoding layer to its initial state.
     *
     * <p>This method clears all internal buffers and resets the encoder/decoder
     * state. It should be called when switching between files or when explicitly
     * resetting the IO stream.</p>
     *
     * <p>In Perl, this would typically happen when closing and reopening a filehandle
     * or when explicitly calling binmode() to change the encoding.</p>
     */
    @Override
    public void reset() {
        decoder.reset();
        encoder.reset();
        inputBuffer.clear();
        utf8ValidationBuffer.setLength(0);
        utf8InputWarnings.clear();
        deferredMalformedUtf8Warning = null;
    }
}
