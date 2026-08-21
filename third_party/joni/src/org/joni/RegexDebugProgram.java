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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joni.ast.CClassNode;
import org.joni.ast.CClassNode.DebugDomainShape;
import org.joni.ast.CClassNode.DebugMembership;
import org.joni.constants.internal.OPCode;
import org.joni.constants.internal.OPSize;

final class RegexDebugProgram {
    private RegexDebugProgram() {
    }

    static Regex.DebugProgramFact firstFact(Regex regex) {
        int cursor = skipInitialDynamicOptionWrapper(regex.code, regex.codeLength);
        if (cursor < 0 || cursor >= regex.codeLength) {
            return Regex.DebugProgramFact.other();
        }

        if (regex.code[cursor] != OPCode.WIDE_SCALAR_CLASS) {
            return ordinaryClassFact(regex, cursor);
        }
        if (cursor + OPSize.WIDE_SCALAR_CLASS > regex.codeLength) {
            return Regex.DebugProgramFact.other();
        }

        int classIndex = regex.code[cursor + 1];
        if (regex.wideScalarClasses == null || classIndex < 0
                || classIndex >= regex.wideScalarClasses.length) {
            return Regex.DebugProgramFact.other();
        }
        CClassNode characterClassNode = regex.wideScalarClasses[classIndex];
        DebugMembership membership = characterClassNode.debugMembership(regex.enc);
        List<Regex.DebugRange> publicRanges = new ArrayList<>(
                membership.ranges().size());
        for (org.joni.ast.CClassNode.DebugRange range : membership.ranges()) {
            publicRanges.add(new Regex.DebugRange(range.from(), range.to(),
                    range.domainEnd()));
        }
        Regex.DebugCharacterClassFact characterClass =
                new Regex.DebugCharacterClassFact(membership.storageNegated(),
                        membership.caseFolded(),
                        true,
                        membership.optimizationSafe(),
                        publicRanges);
        if (characterClassNode.hasDeferredProperties()) {
            if (!membership.storageNegated() && isFull(membership)) {
                return new Regex.DebugProgramFact(
                        Regex.DebugProgramKind.FULL_CLASS, characterClass);
            }
            if (membership.storageNegated() && membership.ranges().isEmpty()) {
                return new Regex.DebugProgramFact(
                        Regex.DebugProgramKind.EMPTY_CLASS, characterClass);
            }
            return new Regex.DebugProgramFact(Regex.DebugProgramKind.OTHER,
                    characterClass);
        }

        DebugDomainShape shape = characterClassNode.debugDomainShape(regex.enc);
        return switch (shape) {
            case FULL -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.FULL_CLASS, characterClass);
            case EMPTY -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.EMPTY_CLASS, characterClass);
            case ALL_EXCEPT_NEWLINE -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.ALL_EXCEPT_NEWLINE_CLASS,
                    characterClass);
            case OTHER -> new Regex.DebugProgramFact(
                    Regex.DebugProgramKind.OTHER, characterClass);
        };
    }

    static Optional<Regex.DebugDeferredCharacterClassFact> firstDeferredFact(
            Regex regex) {
        int cursor = skipInitialDynamicOptionWrapper(regex.code, regex.codeLength);
        if (cursor < 0 || cursor + OPSize.WIDE_SCALAR_CLASS > regex.codeLength
                || regex.code[cursor] != OPCode.WIDE_SCALAR_CLASS) {
            return Optional.empty();
        }
        int classIndex = regex.code[cursor + 1];
        if (regex.wideScalarClasses == null || classIndex < 0
                || classIndex >= regex.wideScalarClasses.length) {
            return Optional.empty();
        }
        CClassNode characterClassNode = regex.wideScalarClasses[classIndex];
        if (!characterClassNode.hasDeferredProperties()) return Optional.empty();

        DebugMembership membership = characterClassNode.debugMembership(regex.enc);
        List<Regex.DebugRange> publicRanges = new ArrayList<>(
                membership.ranges().size());
        for (org.joni.ast.CClassNode.DebugRange range : membership.ranges()) {
            publicRanges.add(new Regex.DebugRange(range.from(), range.to()));
        }
        Regex.DebugCharacterClassFact staticMembership =
                new Regex.DebugCharacterClassFact(membership.storageNegated(),
                        membership.caseFolded(), true,
                        membership.optimizationSafe(), publicRanges);

        boolean presentationSafe = true;
        List<Regex.DebugDeferredPropertyFact> terms = new ArrayList<>(
                characterClassNode.deferredPropertyCount());
        for (int index = 0; index < characterClassNode.deferredPropertyCount();
                index++) {
            CharacterPropertyResolver.DeferredProperty property =
                    characterClassNode.deferredProperty(index);
            String rawName = new String(property.name(), regex.enc.getCharset());
            String displayName = new String(property.displayName(),
                    regex.enc.getCharset());
            if (displayName.isEmpty()
                    || property.context()
                    == CharacterPropertyResolver.Context
                            .PERL_EXTENDED_CHARACTER_CLASS) {
                presentationSafe = false;
            }
            terms.add(new Regex.DebugDeferredPropertyFact(rawName, displayName,
                    property.context(), property.option(), property.position(),
                    property.negated()));
        }
        return Optional.of(new Regex.DebugDeferredCharacterClassFact(
                staticMembership, terms, presentationSafe,
                characterClassNode.debugHighUnbounded()));
    }

    private static boolean isFull(DebugMembership membership) {
        return membership.ranges().size() == 1
                && membership.ranges().get(0).from() == 0
                && membership.ranges().get(0).to() == Long.MAX_VALUE;
    }

    private static Regex.DebugProgramFact ordinaryClassFact(Regex regex,
            int cursor) {
        int opcode = regex.code[cursor++];
        boolean negated;
        boolean hasBitmap;
        boolean hasRanges;
        switch (opcode) {
            case OPCode.CCLASS -> {
                negated = false;
                hasBitmap = true;
                hasRanges = false;
            }
            case OPCode.CCLASS_NOT -> {
                negated = true;
                hasBitmap = true;
                hasRanges = false;
            }
            case OPCode.CCLASS_MB -> {
                negated = false;
                hasBitmap = false;
                hasRanges = true;
            }
            case OPCode.CCLASS_MB_NOT -> {
                negated = true;
                hasBitmap = false;
                hasRanges = true;
            }
            case OPCode.CCLASS_MIX -> {
                negated = false;
                hasBitmap = true;
                hasRanges = true;
            }
            case OPCode.CCLASS_MIX_NOT -> {
                negated = true;
                hasBitmap = true;
                hasRanges = true;
            }
            default -> {
                return Regex.DebugProgramFact.other();
            }
        }

        List<Regex.DebugRange> raw = new ArrayList<>();
        if (hasBitmap) {
            if (cursor + BitSet.BITSET_SIZE > regex.codeLength) {
                return Regex.DebugProgramFact.other();
            }
            long start = -1;
            for (int codePoint = 0; codePoint < BitSet.SINGLE_BYTE_SIZE;
                    codePoint++) {
                int word = regex.code[cursor + codePoint / BitSet.BITS_IN_ROOM];
                boolean member = (word & (1 << (codePoint % BitSet.BITS_IN_ROOM)))
                        != 0;
                if (member) {
                    if (start < 0) start = codePoint;
                } else if (start >= 0) {
                    appendRange(raw, start, codePoint - 1L);
                    start = -1;
                }
            }
            if (start >= 0) appendRange(raw, start, 0xff);
            cursor += BitSet.BITSET_SIZE;
        }
        if (hasRanges) {
            if (cursor >= regex.codeLength) return Regex.DebugProgramFact.other();
            int length = regex.code[cursor++];
            if (length < 1 || cursor + length > regex.codeLength) {
                return Regex.DebugProgramFact.other();
            }
            int count = regex.code[cursor];
            if (count < 0 || 1L + count * 2L > length) {
                return Regex.DebugProgramFact.other();
            }
            for (int index = 0; index < count; index++) {
                int from = regex.code[cursor + index * 2 + 1];
                int to = regex.code[cursor + index * 2 + 2];
                if (from < 0 || from > to) return Regex.DebugProgramFact.other();
                appendRange(raw, from, to);
            }
        }

        List<Regex.DebugRange> effective = negated
                ? complement(raw, CodeRangeBuffer.LAST_CODE_POINT)
                : List.copyOf(raw);
        return new Regex.DebugProgramFact(Regex.DebugProgramKind.OTHER,
                new Regex.DebugCharacterClassFact(negated, false, false,
                        false, effective));
    }

    private static List<Regex.DebugRange> complement(
            List<Regex.DebugRange> raw, long maximum) {
        List<Regex.DebugRange> result = new ArrayList<>();
        long next = 0;
        for (Regex.DebugRange range : raw) {
            if (next < range.from()) appendRange(result, next, range.from() - 1);
            next = range.to() + 1;
        }
        if (next <= maximum) appendRange(result, next, maximum);
        return List.copyOf(result);
    }

    private static void appendRange(List<Regex.DebugRange> ranges,
            long from, long to) {
        if (from > to) return;
        if (!ranges.isEmpty()) {
            Regex.DebugRange previous = ranges.get(ranges.size() - 1);
            if (from <= previous.to() + 1) {
                ranges.set(ranges.size() - 1,
                        new Regex.DebugRange(previous.from(),
                                Math.max(previous.to(), to)));
                return;
            }
        }
        ranges.add(new Regex.DebugRange(from, to));
    }

    private static int skipInitialDynamicOptionWrapper(int[] code, int length) {
        if (code == null || length == 0 || code[0] != OPCode.SET_OPTION_PUSH) {
            return 0;
        }
        int cursor = OPSize.SET_OPTION_PUSH;
        if (cursor >= length || code[cursor] != OPCode.SET_OPTION) return -1;
        cursor += OPSize.SET_OPTION;
        if (cursor >= length || code[cursor] != OPCode.FAIL) return -1;
        return cursor + OPSize.FAIL;
    }
}
