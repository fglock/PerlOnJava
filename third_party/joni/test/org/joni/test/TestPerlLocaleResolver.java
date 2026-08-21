/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlLocaleResolver {
    @Test
    public void localeWordUsesMatcherLocalClassification() {
        byte[] source = "^\\w$".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(source, 0, source.length,
                Option.PERL_LOCALE | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = {(byte)0xe4};

        Matcher plain = regex.matcher(subject);
        assertEquals(Matcher.FAILED,
                plain.search(0, subject.length, Option.NONE));

        Matcher localized = regex.matcher(subject);
        localized.setLocaleResolver((codePoint, characterType) ->
                codePoint == 0xe4 && characterType == CharacterType.WORD);
        assertEquals(0, localized.search(0, subject.length, Option.NONE));
    }

    @Test
    public void localeWordBoundaryUsesTheSameMatcherSnapshot() {
        byte[] source = "\\b\\w\\b".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(source, 0, source.length,
                Option.PERL_LOCALE | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = {(byte)0xe4};
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver((codePoint, characterType) ->
                codePoint == 0xe4 && characterType == CharacterType.WORD);
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }

    @Test
    public void localePosixAlphaUsesMatcherLocalClassification() {
        byte[] source = "^[[:alpha:]]$".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(source, 0, source.length,
                Option.PERL_LOCALE | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = {(byte)0xe4};
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver((codePoint, characterType) ->
                codePoint == 0xe4 && characterType == CharacterType.ALPHA);
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }

    @Test
    public void pureLocalePosixFamiliesRetainTheirCharacterType() {
        String[] names = {"alpha", "alnum", "blank", "cntrl", "digit",
                "graph", "lower", "print", "punct", "space", "upper",
                "xdigit", "word", "ascii"};
        int[] types = {CharacterType.ALPHA, CharacterType.ALNUM,
                CharacterType.BLANK, CharacterType.CNTRL,
                CharacterType.DIGIT, CharacterType.GRAPH,
                CharacterType.LOWER, CharacterType.PRINT,
                CharacterType.PUNCT, CharacterType.SPACE,
                CharacterType.UPPER, CharacterType.XDIGIT,
                CharacterType.WORD, CharacterType.ASCII};
        byte[] subject = {(byte)0xe4};
        for (int index = 0; index < names.length; index++) {
            byte[] source = ("^[[:" + names[index] + ":]]$")
                    .getBytes(StandardCharsets.ISO_8859_1);
            Regex regex = new Regex(source, 0, source.length,
                    Option.PERL_LOCALE | Option.ASCII_RANGE,
                    ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
            int expectedType = types[index];
            Matcher matcher = regex.matcher(subject);
            matcher.setLocaleResolver((codePoint, characterType) ->
                    codePoint == 0xe4 && characterType == expectedType);
            assertEquals(names[index], 0,
                    matcher.search(0, subject.length, Option.NONE));
        }
    }

    @Test
    public void localeSingleByteIgnoreCaseUsesMatcherLocalFold() {
        byte[] source = "^\\xE4$".getBytes(StandardCharsets.ISO_8859_1);
        Regex regex = new Regex(source, 0, source.length,
                Option.IGNORECASE | Option.PERL_LOCALE | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
        org.junit.Assert.assertTrue(regex.byteCodeDebugDescription(),
                regex.byteCodeDebugDescription().contains("exact1-ic"));
        byte[] subject = {(byte)0xc4};
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(new org.joni.LocaleResolver() {
            @Override
            public boolean isCodeCType(int codePoint, int characterType) {
                return false;
            }

            @Override
            public boolean caseFoldEquals(int left, int right) {
                return left == 0xe4 && right == 0xc4;
            }
        });
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }

    @Test
    public void localeUtf8IgnoreCaseUsesMatcherLocalFold() {
        byte[] source = "^\\xE4$".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length,
                Option.IGNORECASE | Option.PERL_LOCALE | Option.ASCII_RANGE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = "Ä".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(new org.joni.LocaleResolver() {
            @Override
            public boolean isCodeCType(int codePoint, int characterType) {
                return false;
            }

            @Override
            public boolean caseFoldEquals(int left, int right) {
                return left == 0xe4 && right == 0xc4;
            }
        });
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }

    @Test
    public void inlineLocaleUtf8IgnoreCaseUsesMatcherLocalFold() {
        byte[] source = "(?li:^\\xE4$)".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length, Option.ASCII_RANGE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = "Ä".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(new org.joni.LocaleResolver() {
            @Override
            public boolean isCodeCType(int codePoint, int characterType) {
                return false;
            }

            @Override
            public boolean caseFoldEquals(int left, int right) {
                return left == 0xe4 && right == 0xc4;
            }
        });
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }
}
