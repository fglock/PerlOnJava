/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni;

import java.util.Objects;

/**
 * Bounded access to Perl's pinned Unicode case-fold data.
 *
 * <p>The data deliberately does not decide the Perl {@code /d}, {@code /u},
 * {@code /a}, {@code /aa}, locale, or byte/character-subject policy. Callers
 * must carry those inputs in an immutable {@link Context} before they apply a
 * relation from these tables.</p>
 */
final class PerlCaseFold {
    enum CharacterSetMode {
        DEFAULT,
        UNICODE,
        ASCII,
        ASCII_STRICT,
        LOCALE
    }

    static final class Context {
        private final CharacterSetMode characterSetMode;
        private final boolean bytePattern;
        private final boolean byteSubject;

        Context(CharacterSetMode characterSetMode, boolean bytePattern,
                boolean byteSubject) {
            this.characterSetMode = Objects.requireNonNull(characterSetMode,
                    "characterSetMode");
            this.bytePattern = bytePattern;
            this.byteSubject = byteSubject;
        }

        CharacterSetMode characterSetMode() {
            return characterSetMode;
        }

        boolean bytePattern() {
            return bytePattern;
        }

        boolean byteSubject() {
            return byteSubject;
        }
    }

    static int fullFoldLength(int source) {
        return PerlUnicodeCaseFoldData.fullFoldLength(source);
    }

    static int fullFoldCodePoint(int source, int index) {
        return PerlUnicodeCaseFoldData.fullFoldCodePoint(source, index);
    }

    static int simpleFoldClassLength(int codePoint) {
        return PerlUnicodeCaseFoldData.simpleFoldClassLength(codePoint);
    }

    static int simpleFoldClassCodePoint(int codePoint, int index) {
        return PerlUnicodeCaseFoldData.simpleFoldClassCodePoint(codePoint, index);
    }

    static int reverseFullFoldSourceCount(int[] sequence, int offset, int length) {
        return PerlUnicodeCaseFoldData.reverseFullFoldSourceCount(sequence, offset, length);
    }

    static int reverseFullFoldSourceAt(int[] sequence, int offset, int length,
                                       int sourceIndex) {
        return PerlUnicodeCaseFoldData.reverseFullFoldSourceAt(
                sequence, offset, length, sourceIndex);
    }

    static boolean isMultiFoldComponent(int codePoint) {
        return PerlUnicodeCaseFoldData.isMultiFoldComponent(codePoint);
    }

    static boolean isTurkicSourceExcluded(int source) {
        return PerlUnicodeCaseFoldData.isTurkicSourceExcluded(source);
    }

    static boolean crossesAscii(int source, int[] target, int offset, int length) {
        if (source >= 0 && source < 0x80) return hasNonAscii(target, offset, length);
        return hasAscii(target, offset, length);
    }

    private static boolean hasAscii(int[] codePoints, int offset, int length) {
        checkSlice(codePoints, offset, length);
        for (int index = offset; index < offset + length; index++) {
            if (codePoints[index] >= 0 && codePoints[index] < 0x80) return true;
        }
        return false;
    }

    private static boolean hasNonAscii(int[] codePoints, int offset, int length) {
        checkSlice(codePoints, offset, length);
        for (int index = offset; index < offset + length; index++) {
            if (codePoints[index] < 0 || codePoints[index] >= 0x80) return true;
        }
        return false;
    }

    private static void checkSlice(int[] codePoints, int offset, int length) {
        Objects.requireNonNull(codePoints, "codePoints");
        if (offset < 0 || length < 0 || offset > codePoints.length - length) {
            throw new IndexOutOfBoundsException("Invalid code-point slice");
        }
    }

    private PerlCaseFold() {
    }
}
