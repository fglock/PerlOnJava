package org.perlonjava.runtime;

import org.perlonjava.runtime.regex.PerlUnicodeNamedSequenceData;
import org.perlonjava.runtime.regex.UnicodeResolver;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
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

    /** Whether a lexical charnames hint supplies user-defined regex semantics. */
    public static boolean usesCustomTranslator(RuntimeScalar translator) {
        RuntimeScalar callable = unwrapCallable(translator);
        return callable != null && !isCoreCharnamesTranslator(callable);
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
                if ((name == null || name.isEmpty())
                        && isCoreCharnamesTranslator(callable)) {
                    return resolveStandard(name);
                }
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
                    "Unknown charname '" + name + "'");
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
        if (("_charnames".equals(code.packageName)
                    || "_charnames".equals(code.sourcePackage))
                && "charnames".equals(code.subName)
                || "_charnames::charnames".equals(code.referenceOriginFqn)) {
            return true;
        }
        RuntimeScalar registered = unwrapCallable(
                GlobalVariable.getGlobalCodeRef("_charnames::charnames"));
        return registered != null && registered.value == callable.value;
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
