package org.perlonjava.runtime.operators;

import java.util.Locale;

/**
 * Perl-style logical characters on top of Java {@link String} (UTF-16).
 *
 * <p>Beyond-Unicode UVs ({@code > U+10FFFF}) are stored as {@code U+FFFD + '<' + HEX + '>'} (one
 * logical Perl character). Surrogate scalars ({@code U+D800..U+DFFF}) from {@code chr()} and
 * {@code \\x{}} escapes are stored as a single UTF-16 code unit so identifier rules match stock
 * Perl ({@code uni/variables.t}). Valid supplementary scalars (e.g. {@code \\x{10000}}) use normal
 * UTF-16 pairs.
 *
 * <p>Used by escapes, {@code chr()}, {@code ord}, {@code length}, {@code substr}, and
 * {@link UnpackState} for {@code unpack("U*", ...)}.
 */
public final class PerlUtfString {

    public static final char MARKER_LEAD = '\uFFFD';

    public record PerlStep(int nextJavaIndex, long codePoint) {}

    private PerlUtfString() {}

    /** Encode a Perl UV not representable as a single Java code point ({@code > 0x10FFFF}). */
    public static String encodeBeyondUnicode(long unsignedCodePoint) {
        String hex = Long.toUnsignedString(unsignedCodePoint, 16).toUpperCase(Locale.ROOT);
        return String.valueOf(MARKER_LEAD) + "<" + hex + ">";
    }

    /**
     * If {@code s} has an internal marker at {@code index}, returns the exclusive end index in Java
     * UTF-16 units; otherwise {@code -1}.
     */
    public static int markerEndExclusive(String s, int index) {
        if (index >= s.length() || s.charAt(index) != MARKER_LEAD) {
            return -1;
        }
        if (index + 1 >= s.length() || s.charAt(index + 1) != '<') {
            return -1;
        }
        int j = index + 2;
        boolean any = false;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '>') {
                return any ? j + 1 : -1;
            }
            if (isHexDigit(c)) {
                any = true;
                j++;
            } else {
                return -1;
            }
        }
        return -1;
    }

    public static long markerCodePoint(String s, int index) {
        int end = markerEndExclusive(s, index);
        if (end < 0) {
            throw new IllegalArgumentException("not a Perl internal marker at index " + index);
        }
        return Long.parseUnsignedLong(s.substring(index + 2, end - 1), 16);
    }

    /** Read one Perl logical character starting at UTF-16 index {@code i}. */
    public static PerlStep readOnePerlLogical(String s, int i) {
        if (i >= s.length()) {
            throw new IllegalArgumentException("readOnePerlLogical past end");
        }
        int markerEnd = markerEndExclusive(s, i);
        if (markerEnd > 0) {
            return new PerlStep(markerEnd, markerCodePoint(s, i));
        }
        char c0 = s.charAt(i);
        if (i + 1 < s.length()
                && Character.isHighSurrogate(c0)
                && Character.isLowSurrogate(s.charAt(i + 1))) {
            int cp = Character.toCodePoint(c0, s.charAt(i + 1));
            return new PerlStep(i + 2, Integer.toUnsignedLong(cp));
        }
        return new PerlStep(i + 1, c0 & 0xFFFFL);
    }

    public static int codePointCountPerl(String s) {
        int count = 0;
        int i = 0;
        while (i < s.length()) {
            i = readOnePerlLogical(s, i).nextJavaIndex();
            count++;
        }
        return count;
    }

    /** Index in Java UTF-16 units after the logical character starting at {@code javaIndex}. */
    public static int nextJavaBoundary(String s, int javaIndex) {
        return readOnePerlLogical(s, javaIndex).nextJavaIndex();
    }

    public static int offsetByPerlCodePoints(String s, int startJava, int perlOffset) {
        int j = startJava;
        for (int k = 0; k < perlOffset && j < s.length(); k++) {
            j = readOnePerlLogical(s, j).nextJavaIndex();
        }
        return j;
    }

    /** First logical character as unsigned (Perl {@code ord} UV semantics for the first char). */
    public static long firstCodePointPerlUnsigned(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        return readOnePerlLogical(s, 0).codePoint();
    }

    /**
     * Perl-style string compare: successive logical characters compared as unsigned integers
     * (matches Perl's default Unicode string ordering for these scalars).
     */
    public static int comparePerlLogical(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            PerlStep sa = readOnePerlLogical(a, i);
            PerlStep sb = readOnePerlLogical(b, j);
            int c = Long.compareUnsigned(sa.codePoint(), sb.codePoint());
            if (c != 0) {
                return c < 0 ? -1 : 1;
            }
            i = sa.nextJavaIndex();
            j = sb.nextJavaIndex();
        }
        if (i >= a.length() && j >= b.length()) {
            return 0;
        }
        return i >= a.length() ? -1 : 1;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
