package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UCharacterDirection;
import com.ibm.icu.util.VersionInfo;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Java implementation of Unicode::BiDiRule's RFC 5893 XS module. */
public final class UnicodeBiDiRule extends PerlModuleBase {
    private static final String NAME = "Unicode::BiDiRule";
    public static final int BIDIRULE_NOTBIDI = 0;
    public static final int BIDIRULE_LTR = 1;
    public static final int BIDIRULE_RTL = 2;
    public static final int BIDIRULE_INVALID = 7;

    private static final int PROP_LTR = 10;
    private static final int PROP_RTL = 11;
    private static final int PROP_AN = 12;
    private static final int PROP_EN = 13;
    private static final int VALID = 14;
    private static final int NSM = 15;
    private static final int AVOIDED = 16;

    public UnicodeBiDiRule() {
        super(NAME, false);
    }

    public static void initialize() {
        UnicodeBiDiRule module = new UnicodeBiDiRule();
        module.defineExportTag("all", "check", "BIDIRULE_RTL", "BIDIRULE_LTR",
                "BIDIRULE_NOTBIDI", "BIDIRULE_INVALID");
        try {
            module.registerMethod("check", null);
            module.registerMethod("UnicodeVersion", null);
            module.registerMethod("BIDIRULE_RTL", null);
            module.registerMethod("BIDIRULE_LTR", null);
            module.registerMethod("BIDIRULE_NOTBIDI", null);
            module.registerMethod("BIDIRULE_INVALID", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + NAME, e);
        }
    }

    public static RuntimeList UnicodeVersion(RuntimeArray args, int ctx) {
        VersionInfo version = UCharacter.getUnicodeVersion();
        return new RuntimeScalar(version.toString()).getList();
    }

    public static RuntimeList BIDIRULE_RTL(RuntimeArray args, int ctx) {
        return scalar(BIDIRULE_RTL);
    }

    public static RuntimeList BIDIRULE_LTR(RuntimeArray args, int ctx) {
        return scalar(BIDIRULE_LTR);
    }

    public static RuntimeList BIDIRULE_NOTBIDI(RuntimeArray args, int ctx) {
        return scalar(BIDIRULE_NOTBIDI);
    }

    public static RuntimeList BIDIRULE_INVALID(RuntimeArray args, int ctx) {
        return scalar(BIDIRULE_INVALID);
    }

    public static RuntimeList check(RuntimeArray args, int ctx) {
        if (args.isEmpty() || args.get(0).type == RuntimeScalarType.UNDEF) return new RuntimeList();
        RuntimeScalar input = args.get(0);
        boolean strict = args.size() < 2 || args.get(1).getBoolean();
        Decoded decoded = decode(input);
        if (decoded.invalid) return result(ctx, BIDIRULE_INVALID, decoded.errorOffset, 0, 0, false);

        int direction = 0;
        int previous = 0;
        int lastCp = 0;
        int lastOffset = 0;
        int lastLength = 0;
        boolean hasAn = false;
        boolean hasEn = false;
        Failure failure = null;

        for (int i = 0; i < decoded.codePoints.length; i++) {
            int cp = decoded.codePoints[i];
            int property = property(cp);
            int offset = decoded.offsets[i];
            int length = decoded.lengths[i];
            if (previous == 0) {
                if (property == PROP_RTL) direction = BIDIRULE_RTL;
                else if (property == PROP_LTR) direction = BIDIRULE_LTR;
                else if (property == PROP_EN || property == VALID) { /* unknown direction */ }
                else if (strict) failure = new Failure(offset, length, cp, property == AVOIDED);
            } else if (property == NSM) {
                if (failure == null) {
                    lastLength += length;
                }
                // RFC 5893 rule 5: an NSM assumes the bidi class of the
                // preceding character, including for the final-character rule.
                continue;
            } else if (failure == null) {
                switch (property) {
                    case PROP_RTL -> { if (direction != BIDIRULE_RTL) failure = new Failure(offset, length, cp, false); }
                    case PROP_AN -> { if (hasEn || direction != BIDIRULE_RTL) failure = new Failure(offset, length, cp, false); else hasAn = true; }
                    case PROP_EN -> { if (hasAn) failure = new Failure(offset, length, cp, false); else hasEn = true; }
                    case VALID -> { }
                    case PROP_LTR -> { if (direction == BIDIRULE_RTL) failure = new Failure(offset, length, cp, false); }
                    default -> {
                        if (direction == BIDIRULE_RTL || strict) failure = new Failure(offset, length, cp, property == AVOIDED);
                        else direction = 0;
                    }
                }
            }
            previous = property;
            lastCp = cp;
            lastOffset = offset;
            lastLength = length;
        }

        if (failure == null) {
            if (direction == BIDIRULE_RTL && previous != PROP_RTL && previous != PROP_AN && previous != PROP_EN)
                failure = new Failure(lastOffset, lastLength, lastCp, false);
            else if (direction == BIDIRULE_LTR && previous != PROP_LTR && previous != PROP_EN)
                failure = new Failure(lastOffset, lastLength, lastCp, false);
            else if (direction == 0) return result(ctx, BIDIRULE_NOTBIDI, decoded.totalLength, 0, 0, false);
        }
        if (failure != null) return result(ctx, failure.unsafe ? AVOIDED : BIDIRULE_INVALID,
                failure.offset, failure.length, failure.cp, failure.unsafe);
        return result(ctx, direction, decoded.totalLength, 0, 0, false);
    }

    private static int property(int cp) {
        int d = UCharacter.getDirection(cp);
        return switch (d) {
            case UCharacterDirection.RIGHT_TO_LEFT, UCharacterDirection.RIGHT_TO_LEFT_ARABIC -> PROP_RTL;
            case UCharacterDirection.ARABIC_NUMBER -> PROP_AN;
            case UCharacterDirection.EUROPEAN_NUMBER -> PROP_EN;
            case UCharacterDirection.LEFT_TO_RIGHT -> PROP_LTR;
            case UCharacterDirection.DIR_NON_SPACING_MARK -> NSM;
            case UCharacterDirection.EUROPEAN_NUMBER_SEPARATOR,
                 UCharacterDirection.COMMON_NUMBER_SEPARATOR,
                 UCharacterDirection.EUROPEAN_NUMBER_TERMINATOR,
                 UCharacterDirection.OTHER_NEUTRAL,
                 UCharacterDirection.BOUNDARY_NEUTRAL -> VALID;
            case UCharacterDirection.LEFT_TO_RIGHT_EMBEDDING,
                 UCharacterDirection.LEFT_TO_RIGHT_OVERRIDE,
                 UCharacterDirection.RIGHT_TO_LEFT_EMBEDDING,
                 UCharacterDirection.RIGHT_TO_LEFT_OVERRIDE,
                 UCharacterDirection.POP_DIRECTIONAL_FORMAT -> AVOIDED;
            default -> 7;
        };
    }

    private static RuntimeList result(int ctx, int code, int offset, int length, int cp, boolean unsafe) {
        if (ctx == RuntimeContextType.VOID) return new RuntimeList();
        if (!RuntimeContextType.isListLike(ctx)) {
            return code == BIDIRULE_INVALID || code == AVOIDED ? new RuntimeList() : scalar(code);
        }
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar("result")); result.add(new RuntimeScalar(code == AVOIDED ? BIDIRULE_INVALID : code));
        result.add(new RuntimeScalar("offset")); result.add(new RuntimeScalar(offset));
        if (length != 0 && (code == BIDIRULE_INVALID || code == AVOIDED)) {
            result.add(new RuntimeScalar("length")); result.add(new RuntimeScalar(length));
            result.add(new RuntimeScalar("ord")); result.add(new RuntimeScalar(cp));
            if (unsafe) { result.add(new RuntimeScalar("unsafe")); result.add(new RuntimeScalar(1)); }
        }
        return result;
    }

    private static RuntimeList scalar(int value) { return new RuntimeScalar(value).getList(); }

    private record Failure(int offset, int length, int cp, boolean unsafe) { }
    private record Decoded(int[] codePoints, int[] offsets, int[] lengths, int totalLength, boolean invalid, int errorOffset) { }

    private static Decoded decode(RuntimeScalar scalar) {
        if (scalar.type != RuntimeScalarType.BYTE_STRING) {
            String s = scalar.toString();
            int n = s.codePointCount(0, s.length());
            int[] cps = new int[n], offsets = new int[n], lengths = new int[n];
            int i = 0, charOffset = 0;
            while (charOffset < s.length()) {
                offsets[i] = i;
                cps[i] = s.codePointAt(charOffset);
                int utf16Length = Character.charCount(cps[i]);
                lengths[i] = 1;
                charOffset += utf16Length;
                i++;
            }
            return new Decoded(cps, offsets, lengths, n, false, 0);
        }
        byte[] bytes = scalar.toString().getBytes(StandardCharsets.ISO_8859_1);
        int[] cps = new int[bytes.length];
        int[] offsets = new int[bytes.length];
        int[] lengths = new int[bytes.length];
        int count = 0;
        for (int offset = 0; offset < bytes.length; ) {
            int b0 = bytes[offset] & 0xff;
            int length;
            int cp;
            if (b0 <= 0x7f) {
                length = 1;
                cp = b0;
            } else if (b0 >= 0xc2 && b0 <= 0xdf) {
                length = 2;
                if (!hasContinuation(bytes, offset, length)) return invalid(bytes.length, offset);
                cp = ((b0 & 0x1f) << 6) | (bytes[offset + 1] & 0x3f);
            } else if (b0 >= 0xe0 && b0 <= 0xef) {
                length = 3;
                if (!hasContinuation(bytes, offset, length)) return invalid(bytes.length, offset);
                int b1 = bytes[offset + 1] & 0xff;
                if ((b0 == 0xe0 && b1 < 0xa0) || (b0 == 0xed && b1 >= 0xa0))
                    return invalid(bytes.length, offset);
                cp = ((b0 & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (bytes[offset + 2] & 0x3f);
            } else if (b0 >= 0xf0 && b0 <= 0xf4) {
                length = 4;
                if (!hasContinuation(bytes, offset, length)) return invalid(bytes.length, offset);
                int b1 = bytes[offset + 1] & 0xff;
                if ((b0 == 0xf0 && b1 < 0x90) || (b0 == 0xf4 && b1 > 0x8f))
                    return invalid(bytes.length, offset);
                cp = ((b0 & 0x07) << 18) | ((b1 & 0x3f) << 12)
                        | ((bytes[offset + 2] & 0x3f) << 6) | (bytes[offset + 3] & 0x3f);
            } else {
                return invalid(bytes.length, offset);
            }
            cps[count] = cp;
            offsets[count] = offset;
            lengths[count] = length;
            count++;
            offset += length;
        }
        return new Decoded(Arrays.copyOf(cps, count), Arrays.copyOf(offsets, count),
                Arrays.copyOf(lengths, count), bytes.length, false, 0);
    }

    private static boolean hasContinuation(byte[] bytes, int offset, int length) {
        if (offset + length > bytes.length) return false;
        for (int i = 1; i < length; i++) {
            if ((bytes[offset + i] & 0xc0) != 0x80) return false;
        }
        return true;
    }

    private static Decoded invalid(int totalLength, int offset) {
        return new Decoded(new int[0], new int[0], new int[0], totalLength, true, offset);
    }
}
