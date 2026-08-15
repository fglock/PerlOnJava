package org.perlonjava.runtime.perlmodule;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.PerlOnJavaByteModeEncoder;
import com.google.zxing.qrcode.encoder.QRCode;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Java replacement for Text::QRCode's libqrencode-backed XS function.
 * The Perl wrapper and public API remain the unmodified CPAN implementation.
 * ZXing is Apache-2.0 licensed; Text::QRCode is licensed under the same terms
 * as Perl itself.
 */
public final class TextQRCode extends PerlModuleBase {
    public static final String XS_VERSION = "0.05";
    private static final String MODULE = "Text::QRCode";

    public TextQRCode() {
        super(MODULE, false);
    }

    public static void initialize() {
        TextQRCode module = new TextQRCode();
        try {
            module.registerMethod("_plot", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList _plot(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.get(0) == null || !args.get(0).defined().getBoolean()) {
            throw new PerlCompilerException("Usage: Text::QRCode::_plot(text, params)");
        }

        String text = args.get(0).toString();
        RuntimeHash params = args.get(1).type == RuntimeScalarType.HASHREFERENCE
                ? args.get(1).hashDeref()
                : new RuntimeHash();

        ErrorCorrectionLevel level = errorCorrectionLevel(params);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);

        int version = intParam(params, "version", 0);
        if (version > 0) {
            if (version > 40) {
                throw new PerlCompilerException("Failed to encode the input data: XS error");
            }
            hints.put(EncodeHintType.QR_VERSION, version);
        }

        String mode = stringParam(params, "mode", "8-bit");
        boolean caseSensitive = boolParam(params, "casesensitive", false);
        Charset byteCharset = null;
        switch (mode) {
            case "8-bit" -> byteCharset = containsWideCharacter(text)
                    ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
            case "numerical" -> {
                if (!text.matches("[0-9]*")) {
                    throw new PerlCompilerException("Failed to encode the input data: XS error");
                }
            }
            case "alpha-numerical" -> {
                if (!caseSensitive) {
                    text = text.toUpperCase(Locale.ROOT);
                }
            }
            case "kanji" -> hints.put(EncodeHintType.CHARACTER_SET, "Shift_JIS");
            default -> throw new PerlCompilerException("Invalid mode: XS error");
        }

        try {
            ByteMatrix matrix = encodeWithLibqrencodeMaskSelection(
                    text, level, hints, byteCharset, version);
            RuntimeArray rows = new RuntimeArray();
            for (int y = 0; y < matrix.getHeight(); y++) {
                RuntimeArray row = new RuntimeArray();
                for (int x = 0; x < matrix.getWidth(); x++) {
                    RuntimeArray.push(row, new RuntimeScalar(matrix.get(x, y) == 1 ? "*" : " "));
                }
                RuntimeArray.push(rows, row.createAnonymousReference());
            }
            return rows.createAnonymousReference().getList();
        } catch (WriterException | IllegalArgumentException e) {
            throw new PerlCompilerException("Failed to encode the input data: XS error");
        }
    }

    /**
     * libqrencode and ZXing use slightly different interpretations of the
     * finder-like-pattern mask penalty. Text::QRCode exposes the bitmap, so
     * choose among ZXing's standards-compliant masks using libqrencode's
     * published scoring algorithm to preserve the XS module's output.
     */
    private static ByteMatrix encodeWithLibqrencodeMaskSelection(
            String text, ErrorCorrectionLevel level, Map<EncodeHintType, Object> baseHints,
            Charset byteCharset, int version)
            throws WriterException {
        ByteMatrix best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            Map<EncodeHintType, Object> hints = new EnumMap<>(baseHints);
            hints.put(EncodeHintType.QR_MASK_PATTERN, mask);
            QRCode code = byteCharset == null
                    ? Encoder.encode(text, level, hints)
                    : PerlOnJavaByteModeEncoder.encode(text, byteCharset, level, version, mask);
            ByteMatrix matrix = code.getMatrix();
            int penalty = libqrencodePenalty(matrix);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = matrix;
            }
        }
        return best;
    }

    private static int libqrencodePenalty(ByteMatrix matrix) {
        int width = matrix.getWidth();
        int black = 0;
        int penalty = 0;
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y) == 1) black++;
                if (x > 0 && y > 0) {
                    int value = matrix.get(x, y);
                    if (value == matrix.get(x - 1, y)
                            && value == matrix.get(x, y - 1)
                            && value == matrix.get(x - 1, y - 1)) {
                        penalty += 3;
                    }
                }
            }
            penalty += libqrencodeRunPenalty(matrix, y, true);
        }
        for (int x = 0; x < width; x++) {
            penalty += libqrencodeRunPenalty(matrix, x, false);
        }
        int blackPercent = (200 * black + width * width) / (width * width) / 2;
        return penalty + (Math.abs(blackPercent - 50) / 5) * 10;
    }

    private static int libqrencodeRunPenalty(ByteMatrix matrix, int line, boolean horizontal) {
        int width = matrix.getWidth();
        int[] runs = new int[width + 1];
        int head;
        int first = horizontal ? matrix.get(0, line) : matrix.get(line, 0);
        if (first == 1) {
            runs[0] = -1;
            head = 1;
        } else {
            head = 0;
        }
        runs[head] = 1;
        int previous = first;
        for (int i = 1; i < width; i++) {
            int value = horizontal ? matrix.get(i, line) : matrix.get(line, i);
            if (value != previous) {
                runs[++head] = 1;
                previous = value;
            } else {
                runs[head]++;
            }
        }

        int count = head + 1;
        int penalty = 0;
        for (int i = 0; i < count; i++) {
            if (runs[i] >= 5) penalty += 3 + runs[i] - 5;
            if ((i & 1) != 0 && i >= 3 && i < count - 2 && runs[i] % 3 == 0) {
                int unit = runs[i] / 3;
                if (runs[i - 2] == unit && runs[i - 1] == unit
                        && runs[i + 1] == unit && runs[i + 2] == unit
                        && (i == 3 || runs[i - 3] >= 4 * unit
                        || i + 4 >= count || runs[i + 3] >= 4 * unit)) {
                    penalty += 40;
                }
            }
        }
        return penalty;
    }

    private static ErrorCorrectionLevel errorCorrectionLevel(RuntimeHash params) {
        String level = stringParam(params, "level", "L");
        if (level.isEmpty()) return ErrorCorrectionLevel.L;
        return switch (Character.toUpperCase(level.charAt(0))) {
            case 'M' -> ErrorCorrectionLevel.M;
            case 'Q' -> ErrorCorrectionLevel.Q;
            case 'H' -> ErrorCorrectionLevel.H;
            default -> ErrorCorrectionLevel.L;
        };
    }

    private static String stringParam(RuntimeHash params, String key, String fallback) {
        if (!params.exists(key).getBoolean()) return fallback;
        RuntimeScalar value = params.get(key);
        return value != null && value.defined().getBoolean() ? value.toString() : fallback;
    }

    private static int intParam(RuntimeHash params, String key, int fallback) {
        if (!params.exists(key).getBoolean()) return fallback;
        RuntimeScalar value = params.get(key);
        return value != null && value.defined().getBoolean() ? value.getInt() : fallback;
    }

    private static boolean boolParam(RuntimeHash params, String key, boolean fallback) {
        if (!params.exists(key).getBoolean()) return fallback;
        RuntimeScalar value = params.get(key);
        return value != null && value.defined().getBoolean() ? value.getBoolean() : fallback;
    }

    private static boolean containsWideCharacter(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint > 0xff);
    }
}
