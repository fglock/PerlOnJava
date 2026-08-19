package org.perlonjava.runtime;

import org.perlonjava.runtime.regex.PerlUnicodeNamedSequenceData;
import org.perlonjava.runtime.regex.UnicodeResolver;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;

/**
 * Immutable compile-time result of expanding one Perl {@code \N{...}} escape.
 *
 * <p>The sequence is intentionally not reduced to one code point: lexical
 * {@code %^H{charnames}} translators may return an empty string or an arbitrary
 * named sequence. Both string and regex consumers use this contract so callback
 * dispatch, validation, byte provenance, and diagnostics cannot diverge.</p>
 */
public record NamedCharacterExpansion(
        String sequence,
        SourceMode sourceMode,
        boolean promotesUnicode,
        Status status,
        String diagnostic) {

    public enum SourceMode { BYTE, UNICODE }
    public enum Status { RESOLVED, UNRESOLVED, INVALID }

    public NamedCharacterExpansion {
        sequence = sequence == null ? "" : sequence;
        sourceMode = sourceMode == null ? SourceMode.UNICODE : sourceMode;
        status = status == null ? Status.UNRESOLVED : status;
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }

    public boolean empty() {
        return resolved() && sequence.isEmpty();
    }

    private static final String REGEX_TOKEN_PREFIX = "=POJSEQ=";

    /** Encodes a resolved lexical expansion without exposing it as regex syntax. */
    public static String encodeRegexToken(String originalName, String sequence) {
        return REGEX_TOKEN_PREFIX + hex(sequence) + "=" + hex(originalName);
    }

    /** Returns the code-point sequence from an encoded regex token, or null. */
    public static int[] decodeRegexToken(String token) {
        if (token == null || !token.startsWith(REGEX_TOKEN_PREFIX)) return null;
        int separator = token.indexOf('=', REGEX_TOKEN_PREFIX.length());
        if (separator < 0) return null;
        String sequence = unhex(token.substring(REGEX_TOKEN_PREFIX.length(), separator));
        return sequence == null ? null : sequence.codePoints().toArray();
    }

    /** Restores encoded lexical tokens for qr// stringification and diagnostics. */
    public static String restoreRegexTokens(String pattern) {
        if (pattern == null || !pattern.contains("\\N{" + REGEX_TOKEN_PREFIX)) return pattern;
        StringBuilder restored = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            if (!pattern.startsWith("\\N{" + REGEX_TOKEN_PREFIX, i)) {
                restored.append(pattern.charAt(i));
                continue;
            }
            int close = pattern.indexOf('}', i + 3);
            int separator = close < 0 ? -1
                    : pattern.lastIndexOf('=', close - 1);
            String name = separator < 0 ? null
                    : unhex(pattern.substring(separator + 1, close));
            if (name == null) {
                restored.append(pattern.charAt(i));
                continue;
            }
            restored.append("\\N{").append(name).append('}');
            i = close;
        }
        return restored.toString();
    }

    private static String hex(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte valueByte : bytes) {
            encoded.append(Character.forDigit((valueByte >>> 4) & 0xf, 16));
            encoded.append(Character.forDigit(valueByte & 0xf, 16));
        }
        return encoded.toString();
    }

    private static String unhex(String value) {
        if ((value.length() & 1) != 0) return null;
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte)((high << 4) | low);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Resolve using the callback in the currently active lexical {@code %^H}. */
    public static NamedCharacterExpansion resolve(String name, SourceMode inputMode) {
        return resolve(name, HintHashRegistry.getCompileTimeHint("charnames"), inputMode);
    }

    /**
     * Resolve with an explicitly captured translator. This overload is also the
     * hand-off point for regex consumers that capture lexical policy earlier
     * than backend pattern compilation.
     */
    public static NamedCharacterExpansion resolve(
            String name, RuntimeScalar translator, SourceMode inputMode) {
        if (name != null && name.matches("(?i)U\\+[0-9A-F]+")) {
            return resolveStandard(name);
        }
        RuntimeScalar callable = unwrapCallable(translator);
        if (callable != null) {
            String validationError = validationError(name);
            if (validationError != null) {
                return new NamedCharacterExpansion(
                        "", inputMode, true, Status.INVALID, validationError);
            }

            RuntimeScalar argument = inputMode == SourceMode.BYTE && isLatin1(name)
                    ? new RuntimeScalar(name.getBytes(StandardCharsets.ISO_8859_1))
                    : new RuntimeScalar(name);
            RuntimeList values = RuntimeCode.apply(
                    callable, new RuntimeArray(argument), RuntimeContextType.SCALAR);
            RuntimeScalar value = values.scalar();
            if (value.type == RuntimeScalarType.UNDEF) {
                if (isCoreCharnamesTranslator(callable)) {
                    return resolveStandard(name);
                }
                return new NamedCharacterExpansion(
                        "", SourceMode.UNICODE, true, Status.UNRESOLVED,
                        "Unknown charname '" + name + "'");
            }
            SourceMode resultMode = value.type == RuntimeScalarType.BYTE_STRING
                    ? SourceMode.BYTE : SourceMode.UNICODE;
            return new NamedCharacterExpansion(
                    value.toString(), resultMode, true, Status.RESOLVED, null);
        }

        return resolveStandard(name);
    }

    private static NamedCharacterExpansion resolveStandard(String name) {
        String namedSequence = PerlUnicodeNamedSequenceData.sequence(name);
        if (namedSequence != null) {
            return new NamedCharacterExpansion(
                    namedSequence, SourceMode.UNICODE,
                    true, Status.RESOLVED, null);
        }
        try {
            int codePoint = UnicodeResolver.getCodePointFromName(name);
            return new NamedCharacterExpansion(
                    new String(Character.toChars(codePoint)), SourceMode.UNICODE,
                    true, Status.RESOLVED, null);
        } catch (IllegalArgumentException failure) {
            return new NamedCharacterExpansion(
                    "", SourceMode.UNICODE, true, Status.UNRESOLVED,
                    failure.getMessage());
        }
    }

    private static RuntimeScalar unwrapCallable(RuntimeScalar translator) {
        if (translator == null) return null;
        if (translator.type == RuntimeScalarType.CODE) return translator;
        if (translator.type == RuntimeScalarType.REFERENCE
                && translator.value instanceof RuntimeScalar referent
                && referent.type == RuntimeScalarType.CODE) {
            return referent;
        }
        return null;
    }

    private static boolean isCoreCharnamesTranslator(RuntimeScalar callable) {
        if (callable == null || !(callable.value instanceof RuntimeCode code)) return false;
        return "_charnames".equals(code.packageName) && "charnames".equals(code.subName);
    }

    private static boolean isLatin1(String value) {
        return value.codePoints().allMatch(codePoint -> codePoint <= 0xff);
    }

    /** Perl's {@code _Perl_Charname_Begin/Continue} policy for custom names. */
    static String validationError(String name) {
        if (name == null || name.isEmpty()) {
            return "Zero length \\N{}";
        }
        if (name.contains("  ")) {
            return "charnames alias definitions may not contain a sequence of multiple spaces";
        }
        int offset = 0;
        int codePoint = name.codePointAt(offset);
        if (!Character.isLetter(codePoint)) {
            return "Invalid character in \\N{...}";
        }
        offset += Character.charCount(codePoint);
        while (offset < name.length()) {
            codePoint = name.codePointAt(offset);
            if (!(Character.isLetterOrDigit(codePoint)
                    || codePoint == ' ' || codePoint == '-'
                    || codePoint == '_' || codePoint == '('
                    || codePoint == ')')) {
                return "Invalid character in \\N{...}";
            }
            offset += Character.charCount(codePoint);
        }
        if (name.endsWith(" ")) {
            return "Invalid character in \\N{...}";
        }
        return null;
    }
}
