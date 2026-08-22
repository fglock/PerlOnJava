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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.jcodings.Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

public class TestAuthoritativeEmptyWideDomain {
    private static final long FIRST_WIDE_SCALAR = 0x110000L;

    private static final CharacterPropertyResolver RESOLVER =
            (bytes, p, end, encoding, inCharacterClass) -> {
                String name = new String(bytes, p, end - p, StandardCharsets.UTF_8);
                int[] unicode = {1, 0, 0x10ffff};
                return switch (name) {
                    case "Authoritative" -> new CharacterPropertyResolver.Result(
                            unicode, new long[] {0}, false);
                    case "Warns" -> new CharacterPropertyResolver.Result(
                            unicode,
                            new long[] {1, FIRST_WIDE_SCALAR, Long.MAX_VALUE},
                            false, true);
                    case "Legacy" -> new CharacterPropertyResolver.Result(unicode, false);
                    default -> null;
                };
            };

    private static final WideScalarCodec CODEC = new WideScalarCodec() {
        @Override
        public byte[] encode(long value, Encoding encoding) {
            return marker(value);
        }

        @Override
        public Decoded decode(byte[] bytes, int p, int end, Encoding encoding) {
            if (p + 4 > end || bytes[p] != '~' || bytes[p + 1] != '<') return null;
            long value = 0;
            int cursor = p + 2;
            int digits = 0;
            while (cursor < end && bytes[cursor] != '>') {
                int digit = Character.digit((char)(bytes[cursor] & 0xff), 16);
                if (digit < 0 || digits == 16
                        || value > (Long.MAX_VALUE - digit) / 16) return null;
                value = value * 16 + digit;
                cursor++;
                digits++;
            }
            return digits == 0 || cursor >= end
                    ? null : new Decoded(value, cursor + 1);
        }
    };

    private static final Syntax SYNTAX = new Syntax(
            "AuthoritativeEmptyWideDomain", Syntax.PerlNG.op,
            Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
            Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable,
            null, RESOLVER, CODEC);

    private static byte[] marker(long value) {
        return ("~<" + Long.toHexString(value).toUpperCase(Locale.ROOT) + ">")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static Regex compile(String property) {
        return compileSource("\\p{" + property + "}");
    }

    private static Regex compileSource(String source) {
        byte[] pattern = source.getBytes(StandardCharsets.UTF_8);
        return new Regex(pattern, 0, pattern.length, Option.NONE,
                UTF8Encoding.INSTANCE, SYNTAX);
    }

    @Test
    public void distinguishesUnknownFromAuthoritativeEmptyWideMetadata() {
        Regex authoritative = compile("Authoritative");
        Regex legacy = compile("Legacy");

        assertTrue(authoritative.wideScalarClasses[0].hasAuthoritativeWideDomain());
        assertFalse(legacy.wideScalarClasses[0].hasAuthoritativeWideDomain());
        assertTrue(authoritative.hasOnlyAuthoritativeWideCharacterClasses());
        assertFalse(legacy.hasOnlyAuthoritativeWideCharacterClasses());
        assertFalse(authoritative.matcher(marker(FIRST_WIDE_SCALAR))
                .search(0, marker(FIRST_WIDE_SCALAR).length, Option.NONE) >= 0);
    }

    @Test
    public void warnsForTheLeadingStartClassAndExecutedPropertyOpcode() {
        Regex warns = compile("Warns");
        Matcher matcher = warns.matcher(marker(FIRST_WIDE_SCALAR));
        int[] warningCount = {0};
        matcher.setNonUnicodePropertyWarningHandler(codePoint -> {
            assertEquals(FIRST_WIDE_SCALAR, codePoint);
            warningCount[0]++;
        });
        assertTrue(matcher.search(
                0, marker(FIRST_WIDE_SCALAR).length, Option.NONE) >= 0);
        assertEquals(2, warningCount[0]);

        byte[] reached = ("q" + new String(marker(FIRST_WIDE_SCALAR),
                StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
        Matcher prefixed = compileSource("q\\p{Warns}").matcher(reached);
        warningCount[0] = 0;
        prefixed.setNonUnicodePropertyWarningHandler(codePoint -> warningCount[0]++);
        assertTrue(prefixed.search(0, reached.length, Option.NONE) >= 0);
        assertEquals(1, warningCount[0]);

        Matcher blocked = compileSource("z\\p{Warns}").matcher(reached);
        warningCount[0] = 0;
        blocked.setNonUnicodePropertyWarningHandler(codePoint -> warningCount[0]++);
        assertFalse(blocked.search(0, reached.length, Option.NONE) >= 0);
        assertEquals(0, warningCount[0]);
    }
}
