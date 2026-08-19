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
package org.joni;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.jcodings.specific.UTF8Encoding;

/**
 * Compiled Perl regular expression used to select Unicode property values.
 *
 * <p>Perl property-value wildcard patterns use search semantics and are
 * case-insensitive by default. Anchors and inline option changes remain part of
 * the supplied pattern, so the same Joni parser and matcher semantics apply as
 * in an ordinary Perl pattern.</p>
 */
public final class PerlPropertyValueMatcher {
    private final Regex regex;

    private PerlPropertyValueMatcher(Regex regex) {
        this.regex = regex;
    }

    public static PerlPropertyValueMatcher compile(String pattern) {
        Objects.requireNonNull(pattern, "pattern");
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        Regex regex = new Regex(bytes, 0, bytes.length, Option.IGNORECASE,
                UTF8Encoding.INSTANCE, Syntax.PerlNG, WarnCallback.NONE);
        return new PerlPropertyValueMatcher(regex);
    }

    public boolean matchesPropertyValue(String value) {
        Objects.requireNonNull(value, "value");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        Matcher matcher = regex.matcher(bytes);
        return matcher.search(0, bytes.length, Option.NONE) >= 0;
    }
}
