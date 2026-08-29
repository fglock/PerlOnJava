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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class TestPerlUnicodeCaseFoldData {
    @Test
    public void preservesPinnedProvenanceAndExactCardinalities() {
        assertEquals("17.0.0", PerlUnicodeCaseFoldData.UNICODE_VERSION);
        assertEquals("ff8d8fefbf123574205085d6714c36149eb946d717a0c585c27f0f4ef58c4183",
                PerlUnicodeCaseFoldData.CASE_FOLDING_SHA256);
        assertEquals("efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588",
                PerlUnicodeCaseFoldData.SPECIAL_CASING_SHA256);
        assertEquals("b2f896452d2b30da3e04800f478c60c1fd0b03d6b668689b020f1e3cf1f1cdd9",
                PerlUnicodeCaseFoldData.PERL_MULTI_FOLD_GENERATOR_SHA256);
        assertEquals("20a6e3d507a66f4594586485568134873485b08e23383f3dc4e6b3047569267b",
                PerlUnicodeCaseFoldData.PERL_INVERSION_GENERATOR_SHA256);
        assertEquals("e3ac360c03d18779fea6d6497fbbe53798135da55e3764d3c9f90a79bbf7e8b5",
                PerlUnicodeCaseFoldData.PERL_MKTABLES_SHA256);
        assertEquals("c83c6471e7c188f21a20c6285af83f57d0bd09392f3243e4cc3743f0a5d5052c",
                PerlUnicodeCaseFoldData.PERL_INVERSION_DATA_SHA256);
        assertEquals("b6005d471764b31d04063ccd561c88d165a2a30f9bae9b75172eb7b59672754e",
                PerlUnicodeCaseFoldData.PERL_REGCHARCLASS_SHA256);
        assertEquals("852a8a7814f08a155d79fead2656fe2b4450ab17a2bce8a1127016119c9c3bc3",
                PerlUnicodeCaseFoldData.PERL_REGCHARCLASS_GENERATOR_SHA256);
        assertEquals(1585, PerlUnicodeCaseFoldData.fullMappingCount());
        assertEquals(1482, PerlUnicodeCaseFoldData.simpleClassCount());
        assertEquals(73, PerlUnicodeCaseFoldData.reverseSequenceCount());
    }

    @Test
    public void exposesCompleteSortedFullMappingsAndTheirReverseSources() {
        int previousSource = -1;
        int fullCodePoints = 0;
        int multiSources = 0;
        int lengthTwo = 0;
        int lengthThree = 0;
        for (int mappingIndex = 0;
             mappingIndex < PerlUnicodeCaseFoldData.fullMappingCount(); mappingIndex++) {
            int source = PerlUnicodeCaseFoldData.fullSourceAt(mappingIndex);
            assertTrue(source > previousSource);
            previousSource = source;
            int length = PerlUnicodeCaseFoldData.fullFoldLength(source);
            assertTrue(length >= 1 && length <= 3);
            fullCodePoints += length;
            if (length > 1) {
                multiSources++;
                if (length == 2) lengthTwo++;
                else lengthThree++;
                int[] sequence = new int[length];
                for (int index = 0; index < length; index++) {
                    sequence[index] = PerlUnicodeCaseFoldData.fullFoldCodePoint(source, index);
                }
                int reverseCount = PerlUnicodeCaseFoldData.reverseFullFoldSourceCount(
                        sequence, 0, sequence.length);
                assertTrue(reverseCount >= 1 && reverseCount <= 2);
                boolean found = false;
                for (int index = 0; index < reverseCount; index++) {
                    found |= PerlUnicodeCaseFoldData.reverseFullFoldSourceAt(
                            sequence, 0, sequence.length, index) == source;
                }
                assertTrue(found);
            }
        }
        assertEquals(1705, fullCodePoints);
        assertEquals(104, multiSources);
        assertEquals(88, lengthTwo);
        assertEquals(16, lengthThree);
    }

    @Test
    public void exposesDisjointSortedSimpleFoldClosures() {
        Set<Integer> seen = new HashSet<>();
        int memberCount = 0;
        int sizeTwo = 0;
        int sizeThree = 0;
        int sizeFour = 0;
        for (int classIndex = 0;
             classIndex < PerlUnicodeCaseFoldData.simpleClassCount(); classIndex++) {
            int length = PerlUnicodeCaseFoldData.simpleClassLengthAt(classIndex);
            if (length == 2) sizeTwo++;
            else if (length == 3) sizeThree++;
            else if (length == 4) sizeFour++;
            else throw new AssertionError("Unexpected simple-fold class length " + length);
            int previous = -1;
            for (int memberIndex = 0; memberIndex < length; memberIndex++) {
                int member = PerlUnicodeCaseFoldData.simpleClassCodePointAt(
                        classIndex, memberIndex);
                assertTrue(member > previous);
                previous = member;
                assertTrue(seen.add(member));
                assertEquals(length, PerlUnicodeCaseFoldData.simpleFoldClassLength(member));
                for (int siblingIndex = 0; siblingIndex < length; siblingIndex++) {
                    assertEquals(PerlUnicodeCaseFoldData.simpleClassCodePointAt(
                                    classIndex, siblingIndex),
                            PerlUnicodeCaseFoldData.simpleFoldClassCodePoint(
                                    member, siblingIndex));
                }
                memberCount++;
            }
        }
        assertEquals(2994, memberCount);
        assertEquals(1455, sizeTwo);
        assertEquals(24, sizeThree);
        assertEquals(3, sizeFour);
    }

    @Test
    public void exposesSortedReverseSequencesAndEveryMultiFoldComponent() {
        int[] previous = null;
        int sequenceCodePoints = 0;
        int sourceCount = 0;
        Set<Integer> components = new HashSet<>();
        for (int sequenceIndex = 0;
             sequenceIndex < PerlUnicodeCaseFoldData.reverseSequenceCount(); sequenceIndex++) {
            int length = PerlUnicodeCaseFoldData.reverseSequenceLengthAt(sequenceIndex);
            assertTrue(length == 2 || length == 3);
            int[] sequence = new int[length];
            for (int index = 0; index < length; index++) {
                sequence[index] = PerlUnicodeCaseFoldData.reverseSequenceCodePointAt(
                        sequenceIndex, index);
                components.add(sequence[index]);
                assertTrue(PerlUnicodeCaseFoldData.isMultiFoldComponent(sequence[index]));
            }
            if (previous != null) assertTrue(compare(previous, sequence) < 0);
            previous = sequence;
            sequenceCodePoints += length;
            int count = PerlUnicodeCaseFoldData.reverseSourceCountAt(sequenceIndex);
            assertTrue(count == 1 || count == 2);
            int previousSource = -1;
            for (int index = 0; index < count; index++) {
                int source = PerlUnicodeCaseFoldData.reverseSourceAt(sequenceIndex, index);
                assertTrue(source > previousSource);
                previousSource = source;
                sourceCount++;
            }
        }
        assertEquals(160, sequenceCodePoints);
        assertEquals(104, sourceCount);
        assertEquals(65, components.size());
        assertFalse(PerlUnicodeCaseFoldData.isMultiFoldComponent('x'));
    }

    @Test
    public void exposesSharpSGreekLigatureAndUnicode17Anchors() {
        assertFullFold(0x00df, 0x0073, 0x0073);
        assertFullFold(0x1e9e, 0x0073, 0x0073);
        assertFullFold(0x01f0, 0x006a, 0x030c);
        assertFullFold(0x0390, 0x03b9, 0x0308, 0x0301);
        assertFullFold(0xfb03, 0x0066, 0x0066, 0x0069);
        assertSimpleSiblings(0xa7ce, 0xa7cf);
        assertSimpleSiblings(0xa7d2, 0xa7d3);
        assertSimpleSiblings(0xa7d4, 0xa7d5);
        assertSimpleSiblings(0x16ea0, 0x16ebb);

        int[] sharpS = {'s', 's'};
        assertEquals(2, PerlUnicodeCaseFoldData.reverseFullFoldSourceCount(
                sharpS, 0, sharpS.length));
        assertEquals(0x00df, PerlUnicodeCaseFoldData.reverseFullFoldSourceAt(
                sharpS, 0, sharpS.length, 0));
        assertEquals(0x1e9e, PerlUnicodeCaseFoldData.reverseFullFoldSourceAt(
                sharpS, 0, sharpS.length, 1));
    }

    @Test
    public void excludesTurkicMappingsFromDefaultTablesWithoutEncodingModePolicy() {
        assertTrue(PerlUnicodeCaseFoldData.isTurkicSourceExcluded(0x0049));
        assertTrue(PerlUnicodeCaseFoldData.isTurkicSourceExcluded(0x0130));
        assertFalse(PerlUnicodeCaseFoldData.isTurkicSourceExcluded(0x0069));
        assertFullFold(0x0049, 0x0069);
        assertFullFold(0x0130, 0x0069, 0x0307);
        assertEquals(0, PerlUnicodeCaseFoldData.fullFoldLength(0x0131));
    }

    @Test
    public void rejectsUnknownLookupsAndInvalidSlices() {
        assertEquals(0, PerlUnicodeCaseFoldData.fullFoldLength(0x10ffff));
        assertEquals(0, PerlUnicodeCaseFoldData.simpleFoldClassLength(0x10ffff));
        assertEquals(0, PerlUnicodeCaseFoldData.reverseFullFoldSourceCount(
                new int[] {'x', 'y'}, 0, 2));
        expect(IllegalArgumentException.class,
                () -> PerlUnicodeCaseFoldData.fullFoldCodePoint(0x10ffff, 0));
        expect(IllegalArgumentException.class,
                () -> PerlUnicodeCaseFoldData.simpleFoldClassCodePoint(0x10ffff, 0));
        expect(IndexOutOfBoundsException.class,
                () -> PerlUnicodeCaseFoldData.reverseFullFoldSourceCount(
                        new int[] {'s', 's'}, 1, 2));
        expect(IndexOutOfBoundsException.class,
                () -> PerlUnicodeCaseFoldData.reverseSequenceLengthAt(73));
    }

    private static void assertFullFold(int source, int... expected) {
        assertEquals(expected.length, PerlUnicodeCaseFoldData.fullFoldLength(source));
        int[] actual = new int[expected.length];
        for (int index = 0; index < actual.length; index++) {
            actual[index] = PerlUnicodeCaseFoldData.fullFoldCodePoint(source, index);
        }
        assertArrayEquals(expected, actual);
    }

    private static void assertSimpleSiblings(int left, int right) {
        int length = PerlUnicodeCaseFoldData.simpleFoldClassLength(left);
        assertTrue(length >= 2);
        boolean foundLeft = false;
        boolean foundRight = false;
        for (int index = 0; index < length; index++) {
            int member = PerlUnicodeCaseFoldData.simpleFoldClassCodePoint(left, index);
            foundLeft |= member == left;
            foundRight |= member == right;
        }
        assertTrue(foundLeft);
        assertTrue(foundRight);
    }

    private static int compare(int[] left, int[] right) {
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static void expect(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError(failure);
        }
        throw new AssertionError("Expected " + expected.getName());
    }
}
