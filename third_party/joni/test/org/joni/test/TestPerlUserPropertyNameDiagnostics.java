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
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.joni.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;

import org.jcodings.specific.UTF8Encoding;
import org.joni.CharacterPropertyResolver;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;
import org.joni.WarnCallback;
import org.joni.exception.SyntaxException;
import org.junit.Test;

public class TestPerlUserPropertyNameDiagnostics {
    private static void compile(String pattern, Syntax syntax) {
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
        new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE,
                syntax, WarnCallback.NONE);
    }

    private static void assertInvalid(String pattern, String message,
                                      int bytePosition) {
        SyntaxException error = assertThrows(SyntaxException.class,
                () -> compile(pattern, Syntax.PerlNG));
        assertEquals(message, error.getMessage());
        assertEquals(bytePosition, error.getPatternPosition());
    }

    private static Syntax acceptingPropertySyntax() {
        CharacterPropertyResolver resolver =
                (bytes, p, end, encoding, inCharacterClass) ->
                        new CharacterPropertyResolver.Result(
                                new int[] {1, 'A', 'A'}, false);
        return new Syntax("PerlUserPropertyNameDiagnostics", Syntax.PerlNG.op,
                Syntax.PerlNG.op2, Syntax.PerlNG.op3, Syntax.PerlNG.behavior,
                Syntax.PerlNG.options, Syntax.PerlNG.metaCharTable, null,
                resolver);
    }

    @Test
    public void rejectsMalformedPackageQualifiedUserProperties() {
        assertInvalid("\\p{utf8::perl x}",
                "Illegal user-defined property name \"utf8::perl x\"", 16);
        assertInvalid("\\P{Foo::bar}",
                "Illegal user-defined property name \"Foo::bar\"", 12);
        assertInvalid("[\\p{Foo::9bar}]",
                "Illegal user-defined property name \"Foo::9bar\"", 14);
    }

    @Test
    public void preservesValidAndInternalNamesForTheResolver() {
        Syntax syntax = acceptingPropertySyntax();
        compile("\\p{main::IsFoo}", syntax);
        compile("\\p{Foo::::InBar}", syntax);
        compile("\\p{utf8::_perl_surrogate}", syntax);
    }
}
