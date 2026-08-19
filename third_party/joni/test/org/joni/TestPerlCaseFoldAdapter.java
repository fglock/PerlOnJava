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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestPerlCaseFoldAdapter {
    @Test
    public void exposesBoundedForwardSimpleAndReverseTables() {
        assertEquals(2, PerlCaseFold.fullFoldLength(0x00df));
        assertEquals('s', PerlCaseFold.fullFoldCodePoint(0x00df, 0));
        assertEquals('s', PerlCaseFold.fullFoldCodePoint(0x00df, 1));

        assertEquals(2, PerlCaseFold.simpleFoldClassLength(0xa7ce));
        assertEquals(0xa7ce, PerlCaseFold.simpleFoldClassCodePoint(0xa7ce, 0));
        assertEquals(0xa7cf, PerlCaseFold.simpleFoldClassCodePoint(0xa7ce, 1));

        int[] sharpS = {'s', 's'};
        assertEquals(2, PerlCaseFold.reverseFullFoldSourceCount(sharpS, 0, 2));
        assertEquals(0x00df, PerlCaseFold.reverseFullFoldSourceAt(sharpS, 0, 2, 0));
        assertEquals(0x1e9e, PerlCaseFold.reverseFullFoldSourceAt(sharpS, 0, 2, 1));
        assertTrue(PerlCaseFold.isMultiFoldComponent('s'));
        assertFalse(PerlCaseFold.isMultiFoldComponent('x'));
        assertTrue(PerlCaseFold.isTurkicSourceExcluded('I'));
    }

    @Test
    public void carriesFoldPolicyInputsWithoutChoosingTheirSemantics() {
        PerlCaseFold.Context context = new PerlCaseFold.Context(
                PerlCaseFold.CharacterSetMode.ASCII_STRICT, true, false);
        assertEquals(PerlCaseFold.CharacterSetMode.ASCII_STRICT,
                context.characterSetMode());
        assertTrue(context.bytePattern());
        assertFalse(context.byteSubject());
        assertTrue(PerlCaseFold.crossesAscii(0x00df, new int[] {'s', 's'}, 0, 2));
        assertTrue(PerlCaseFold.crossesAscii('s', new int[] {0x017f}, 0, 1));
        assertFalse(PerlCaseFold.crossesAscii(0x00df, new int[] {0x1e9e}, 0, 1));
    }
}
