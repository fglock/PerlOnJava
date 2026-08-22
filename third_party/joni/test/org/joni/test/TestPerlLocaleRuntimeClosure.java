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
import java.util.concurrent.atomic.AtomicBoolean;

import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ISO8859_1Encoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.LocaleResolver;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.junit.Test;

public class TestPerlLocaleRuntimeClosure {
    private static Regex localeRegex(String source) {
        byte[] bytes = source.getBytes(StandardCharsets.ISO_8859_1);
        return new Regex(bytes, 0, bytes.length,
                Option.PERL_LOCALE | Option.ASCII_RANGE,
                ISO8859_1Encoding.INSTANCE, Syntax.PerlNG);
    }

    private static LocaleResolver wordAndAlpha(AtomicBoolean highByteLetter) {
        return (codePoint, characterType) -> codePoint == 0xe4
                && highByteLetter.get()
                && (characterType == CharacterType.WORD
                        || characterType == CharacterType.ALPHA);
    }

    private static int match(Regex regex, byte[] subject,
            LocaleResolver resolver) {
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(resolver);
        return matcher.search(0, subject.length, Option.NONE);
    }

    @Test
    public void mixedLocaleClassUnionsRuntimeTermAndLiteral() {
        Regex regex = localeRegex("^[[:alpha:]_]$");
        AtomicBoolean highByteLetter = new AtomicBoolean(false);
        LocaleResolver resolver = wordAndAlpha(highByteLetter);
        byte[] highByte = {(byte)0xe4};

        assertEquals(Matcher.FAILED, match(regex, highByte, resolver));
        assertEquals(0, match(regex, new byte[]{'_'}, resolver));
        highByteLetter.set(true);
        assertEquals(0, match(regex, highByte, resolver));
    }

    @Test
    public void nestedNegationUsesRuntimeWordMembership() {
        Regex regex = localeRegex("^[^\\W_]$");
        AtomicBoolean highByteLetter = new AtomicBoolean(false);
        LocaleResolver resolver = wordAndAlpha(highByteLetter);
        byte[] highByte = {(byte)0xe4};

        assertEquals(Matcher.FAILED, match(regex, highByte, resolver));
        assertEquals(Matcher.FAILED, match(regex, new byte[]{'_'}, resolver));
        highByteLetter.set(true);
        assertEquals(0, match(regex, highByte, resolver));
    }

    @Test
    public void oneCompiledRegexObservesResolverStateAtMatchTime() {
        Regex regex = localeRegex("^\\w$");
        AtomicBoolean highByteLetter = new AtomicBoolean(false);
        LocaleResolver resolver = wordAndAlpha(highByteLetter);
        byte[] highByte = {(byte)0xe4};

        assertEquals(Matcher.FAILED, match(regex, highByte, resolver));
        highByteLetter.set(true);
        assertEquals(0, match(regex, highByte, resolver));
        highByteLetter.set(false);
        assertEquals(Matcher.FAILED, match(regex, highByte, resolver));
    }

    @Test
    public void nonUtf8LocaleVariantSuppressesMultiCharacterUnicodeFolds() {
        byte[] source = "^\\x{DF}$".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length,
                Option.IGNORECASE | Option.PERL_LOCALE
                        | Option.PERL_LOCALE_NON_UTF8 | Option.ASCII_RANGE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = "ss".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(wordAndAlpha(new AtomicBoolean(false)));
        assertEquals(Matcher.FAILED,
                matcher.search(0, subject.length, Option.NONE));
    }

    @Test
    public void utf8LocaleProgramRetainsMultiCharacterUnicodeFolds() {
        byte[] source = "^\\x{DF}$".getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(source, 0, source.length,
                Option.IGNORECASE | Option.PERL_LOCALE | Option.ASCII_RANGE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG);
        byte[] subject = "ss".getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(subject);
        matcher.setLocaleResolver(wordAndAlpha(new AtomicBoolean(false)));
        assertEquals(0, matcher.search(0, subject.length, Option.NONE));
    }
}
