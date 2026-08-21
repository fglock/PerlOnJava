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
}
