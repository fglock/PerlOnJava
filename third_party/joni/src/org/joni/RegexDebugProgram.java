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

    static Regex.DebugProgramFact firstFact(Regex regex,
            boolean includeExact) {
        int cursor = skipInitialDynamicOptionWrapper(regex.code, regex.codeLength);
        if (cursor < 0 || cursor >= regex.codeLength) {
            return Regex.DebugProgramFact.other();
        }
        if (regex.code[cursor] == OPCode.PUSH_BRANCH) {
            int branchBody = cursor + OPSize.PUSH_BRANCH;
            if (branchBody < regex.codeLength
                    && (regex.debugCharacterClassExpressions
                            .containsKey(branchBody)
                        || regex.code[branchBody]
                                == OPCode.WIDE_SCALAR_CLASS)) {
                cursor = branchBody;
            }
        }

        if (includeExact) {
            Regex.DebugProgramFact exact = exactFact(regex, cursor);
            if (exact != null) return exact;
            exact = directClassExactFact(regex, cursor);
            if (exact != null) return exact;
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
        CClassNode.DebugClassExpression expression =
                characterClassNode.debugClassExpression();
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
                        publicRanges,
                        expression);
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

        if (expression != null && expression.provesComplementPair()) {
            return new Regex.DebugProgramFact(expression.outerNegated()
                    ? Regex.DebugProgramKind.EMPTY_CLASS
                    : Regex.DebugProgramKind.FULL_CLASS, characterClass);
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

    private record LogicalExact(List<Integer> bytes, List<Long> codePoints,
            int end, int lexicalOption, boolean ignoreCaseOpcode,
            boolean singleByteFoldOpcode, boolean multiCharacterFoldExpansion,
            int byteWidth, boolean classAlternative) {
    }

    private static Regex.DebugProgramFact exactFact(Regex regex, int cursor) {
        LogicalExact logical = decodeLogicalExact(regex, cursor,
                regex.codeLength);
        if (logical == null || logical.codePoints().isEmpty()) return null;
        boolean folded = logical.ignoreCaseOpcode()
                && !isKnownNonFoldExact(logical.codePoints());
        Regex.DebugExactFact fact = new Regex.DebugExactFact(logical.bytes(),
                logical.codePoints(), folded,
                logical.singleByteFoldOpcode(),
                logical.multiCharacterFoldExpansion(), logical.byteWidth(),
                logical.lexicalOption());
        return new Regex.DebugProgramFact(Regex.DebugProgramKind.EXACT,
                null, fact);
    }

    private static LogicalExact decodeLogicalExact(Regex regex, int cursor,
            int limit) {
        List<Integer> bytes = new ArrayList<>();
        List<Long> codePoints = new ArrayList<>();
        int lexicalOption = regex.options;
        boolean haveOption = false;
        boolean ignoreCase = false;
        boolean singleByteFold = false;
        boolean multiCharacterFoldExpansion = false;
        int byteWidth = -1;
        int start = cursor;

        while (cursor < limit) {
            ExactByteCodeDecoder.Instruction instruction =
                    ExactByteCodeDecoder.decode(regex, cursor);
            if (instruction != null) {
                int option = regex.debugExactOptions.getOrDefault(
                        cursor, regex.options);
                if (haveOption && option != lexicalOption) break;
                if (!haveOption) {
                    lexicalOption = option;
                    haveOption = true;
                }
                byte[] payload = instruction.bytes();
                List<Long> decoded = decodeCodePoints(regex, payload);
                if (decoded == null || decoded.isEmpty()) break;
                for (byte value : payload) bytes.add(value & 0xff);
                codePoints.addAll(decoded);
                ignoreCase |= instruction.ignoreCase();
                singleByteFold |= instruction.singleByteFold();
                multiCharacterFoldExpansion |=
                        regex.debugSingleSourceMultiFolds.contains(cursor);
                if (byteWidth < 0) byteWidth = instruction.byteWidth();
                else if (byteWidth != instruction.byteWidth()) byteWidth = 0;
                cursor = instruction.end();
                continue;
            }

            LogicalExact branch = decodeFoldBranch(regex, cursor, limit);
            if (branch == null) break;
            if (haveOption && branch.lexicalOption() != lexicalOption) break;
            if (!haveOption) {
                lexicalOption = branch.lexicalOption();
                haveOption = true;
            }
            boolean defaultSharpSBoundary = !codePoints.isEmpty()
                    && codePoints.get(codePoints.size() - 1) == (long)'s'
                    && !branch.codePoints().isEmpty()
                    && branch.codePoints().get(0) == (long)'s'
                    && !(branch.codePoints().size() > 1
                            && branch.codePoints().get(1) == (long)'t')
                    && !exactStartsWith(regex, branch.end(), 't')
                    && !Option.isPerlExplicitAscii(lexicalOption)
                    && !Option.isPerlUnicodeCharset(lexicalOption);
            if (defaultSharpSBoundary) {
                byte[] encoded = encodeCodePoint(regex, 's');
                if (encoded == null) break;
                for (byte value : encoded) bytes.add(value & 0xff);
                codePoints.add((long)'s');
                ignoreCase = true;
                cursor = branch.end();
                break;
            }
            bytes.addAll(branch.bytes());
            codePoints.addAll(branch.codePoints());
            ignoreCase |= branch.ignoreCaseOpcode();
            singleByteFold |= branch.singleByteFoldOpcode();
            multiCharacterFoldExpansion |=
                    branch.multiCharacterFoldExpansion();
            if (byteWidth < 0) byteWidth = branch.byteWidth();
            else if (byteWidth != branch.byteWidth()) byteWidth = 0;
            cursor = branch.end();
        }

        if (cursor == start || codePoints.isEmpty()) return null;
        return new LogicalExact(List.copyOf(bytes), List.copyOf(codePoints),
                cursor, lexicalOption, ignoreCase, singleByteFold,
                multiCharacterFoldExpansion, Math.max(0, byteWidth), false);
    }

    private static LogicalExact decodeFoldBranch(Regex regex, int cursor,
            int limit) {
        if (cursor + OPSize.PUSH > limit
                || regex.code[cursor] != OPCode.PUSH_BRANCH) return null;
        List<LogicalExact> alternatives = new ArrayList<>();
        int branch = cursor;
        int join = -1;
        while (branch + OPSize.PUSH <= limit
                && regex.code[branch] == OPCode.PUSH_BRANCH) {
            int nextAlternative = branch + OPSize.PUSH
                    + regex.code[branch + 1];
            if (nextAlternative <= branch || nextAlternative > limit) {
                return null;
            }
            LogicalExact candidate = decodeAlternative(regex,
                    branch + OPSize.PUSH, nextAlternative);
            if (candidate == null || candidate.end() >= nextAlternative
                    || regex.code[candidate.end()] != OPCode.JUMP
                    || candidate.end() + OPSize.JUMP > nextAlternative) {
                return null;
            }
            int candidateJoin = candidate.end() + OPSize.JUMP
                    + regex.code[candidate.end() + 1];
            if (candidateJoin <= nextAlternative || candidateJoin > limit
                    || join >= 0 && join != candidateJoin) return null;
            join = candidateJoin;
            alternatives.add(candidate);
            branch = nextAlternative;
        }
        if (join < 0 || branch >= join) return null;
        LogicalExact last = decodeAlternative(regex, branch, join);
        if (last == null || last.end() != join) return null;
        alternatives.add(last);

        LogicalExact selected = alternatives.get(0);
        for (LogicalExact candidate : alternatives) {
            if (candidate.codePoints().size()
                    > selected.codePoints().size()) selected = candidate;
        }
        if (Option.isPerlLocale(selected.lexicalOption())) {
            for (LogicalExact candidate : alternatives) {
                if (candidate.classAlternative()
                        && candidate.codePoints().size() == 1
                        && candidate.codePoints().get(0) >= 0x1000) {
                    selected = candidate;
                    break;
                }
            }
        }
        boolean expansion = selected.codePoints().size() > 1
                && alternatives.get(0).codePoints().size() == 1;
        return new LogicalExact(selected.bytes(), selected.codePoints(), join,
                alternatives.get(0).lexicalOption(), true,
                selected.singleByteFoldOpcode(), expansion,
                selected.byteWidth(), selected.classAlternative());
    }

    private static LogicalExact decodeAlternative(Regex regex, int cursor,
            int limit) {
        int start = cursor;
        List<Integer> combinedBytes = new ArrayList<>();
        List<Long> combinedCodePoints = new ArrayList<>();
        int option = regex.options;
        boolean haveOption = false;
        boolean ignoreCase = false;
        boolean singleByteFold = false;
        boolean expansion = false;
        int width = -1;
        boolean includesClass = false;

        while (cursor < limit) {
            LogicalExact part = decodeLogicalExact(regex, cursor, limit);
            if (part == null) part = decodeClassAlternative(regex, cursor);
            if (part == null || part.end() <= cursor) break;
            if (!haveOption) {
                option = part.lexicalOption();
                haveOption = true;
            }
            combinedBytes.addAll(part.bytes());
            combinedCodePoints.addAll(part.codePoints());
            ignoreCase |= part.ignoreCaseOpcode();
            singleByteFold |= part.singleByteFoldOpcode();
            expansion |= part.multiCharacterFoldExpansion();
            includesClass |= part.classAlternative();
            if (width < 0) width = part.byteWidth();
            else if (width != part.byteWidth()) width = 0;
            cursor = part.end();
        }
        if (cursor > start) {
            return new LogicalExact(List.copyOf(combinedBytes),
                    List.copyOf(combinedCodePoints), cursor, option,
                    ignoreCase, singleByteFold, expansion,
                    Math.max(0, width), includesClass);
        }
        return null;
    }

    private static LogicalExact decodeClassAlternative(Regex regex,
            int cursor) {
        int limit = regex.codeLength;
        if (cursor + OPSize.WIDE_SCALAR_CLASS > limit
                || regex.code[cursor] != OPCode.WIDE_SCALAR_CLASS) return null;
        int classIndex = regex.code[cursor + 1];
        if (regex.wideScalarClasses == null || classIndex < 0
                || classIndex >= regex.wideScalarClasses.length
                || regex.wideScalarClasses[classIndex]
                        .hasDeferredProperties()) return null;
        DebugMembership membership = regex.wideScalarClasses[classIndex]
                .debugMembership(regex.enc);
        if (membership.storageNegated() || membership.ranges().isEmpty()) {
            return null;
        }
        org.joni.ast.CClassNode.DebugRange range = membership.ranges()
                .get(membership.ranges().size() - 1);
        long codePoint = range.to();
        byte[] encoded = encodeCodePoint(regex, codePoint);
        if (encoded == null) return null;
        List<Integer> bytes = new ArrayList<>(encoded.length);
        for (byte value : encoded) bytes.add(value & 0xff);
        int option = regex.options;
        int end = cursor + OPSize.WIDE_SCALAR_CLASS;
        return new LogicalExact(List.copyOf(bytes), List.of(codePoint),
                end, option,
                Option.isIgnoreCase(option), false, false,
                encoded.length, true);
    }

    private static byte[] encodeCodePoint(Regex regex, long codePoint) {
        if (codePoint > 0x10ffffL) {
            return regex.wideScalarCodec == null ? null
                    : regex.wideScalarCodec.encode(codePoint, regex.enc);
        }
        byte[] encoded = new byte[regex.enc.maxLength()];
        int length = regex.enc.codeToMbc((int)codePoint, encoded, 0);
        if (length <= 0) return null;
        return java.util.Arrays.copyOf(encoded, length);
    }

    private static boolean isKnownNonFoldExact(List<Long> codePoints) {
        if (codePoints.size() != 1) return false;
        long codePoint = codePoints.get(0);
        if (codePoint == 0x2bcL) return false;
        if (codePoint == 0x2b9L || codePoint > Integer.MAX_VALUE) return true;
        int value = (int)codePoint;
        return !Character.isLowerCase(value)
                && !Character.isUpperCase(value)
                && !Character.isTitleCase(value);
    }

    private static Regex.DebugProgramFact directClassExactFact(Regex regex,
            int cursor) {
        if (cursor + OPSize.WIDE_SCALAR_CLASS > regex.codeLength
                || regex.code[cursor] != OPCode.WIDE_SCALAR_CLASS) return null;
        int classIndex = regex.code[cursor + 1];
        if (regex.wideScalarClasses == null || classIndex < 0
                || classIndex >= regex.wideScalarClasses.length
                || regex.wideScalarClasses[classIndex]
                        .hasDeferredProperties()) return null;
        DebugMembership membership = regex.wideScalarClasses[classIndex]
                .debugMembership(regex.enc);
        if (membership.storageNegated() || membership.ranges().isEmpty()) {
            return null;
        }
        Long codePoint = null;
        if (membership.ranges().size() == 1
                && membership.ranges().get(0).from()
                        == membership.ranges().get(0).to()) {
            codePoint = membership.ranges().get(0).from();
        } else if (membership.caseFolded()) {
            codePoint = canonicalLowercaseMember(membership);
        }
        if (codePoint == null) return null;
        byte[] encoded = encodeCodePoint(regex, codePoint);
        if (encoded == null) return null;
        List<Integer> bytes = new ArrayList<>(encoded.length);
        for (byte value : encoded) bytes.add(value & 0xff);
        int option = regex.options;
        boolean folded = membership.caseFolded()
                && codePoint <= Integer.MAX_VALUE
                && (Character.isLowerCase((int)(long)codePoint)
                        || Character.isUpperCase((int)(long)codePoint)
                        || Character.isTitleCase((int)(long)codePoint));
        Regex.DebugExactFact fact = new Regex.DebugExactFact(bytes,
                List.of(codePoint), folded, false, false,
                encoded.length, option);
        return new Regex.DebugProgramFact(Regex.DebugProgramKind.EXACT,
                null, fact);
    }

    private static Long canonicalLowercaseMember(DebugMembership membership) {
        long count = 0;
        for (org.joni.ast.CClassNode.DebugRange range : membership.ranges()) {
            count += range.to() - range.from() + 1;
            if (count > 16 || range.to() > Integer.MAX_VALUE) return null;
        }
        for (org.joni.ast.CClassNode.DebugRange range : membership.ranges()) {
            for (long value = range.from(); value <= range.to(); value++) {
                int lower = Character.toLowerCase((int)value);
                if (lower != value && contains(membership, lower)) {
                    return (long)lower;
                }
            }
        }
        return null;
    }

    private static boolean contains(DebugMembership membership, long value) {
        for (org.joni.ast.CClassNode.DebugRange range : membership.ranges()) {
            if (value < range.from()) return false;
            if (value <= range.to()) return true;
        }
        return false;
    }

    private static boolean exactStartsWith(Regex regex, int cursor,
            long expected) {
        ExactByteCodeDecoder.Instruction instruction =
                ExactByteCodeDecoder.decode(regex, cursor);
        if (instruction == null) return false;
        List<Long> decoded = decodeCodePoints(regex, instruction.bytes());
        return decoded != null && !decoded.isEmpty()
                && decoded.get(0) == expected;
    }

    private static List<Long> decodeCodePoints(Regex regex, byte[] bytes) {
        List<Long> codePoints = new ArrayList<>();
        int cursor = 0;
        while (cursor < bytes.length) {
            if (regex.wideScalarCodec != null) {
                WideScalarCodec.Decoded wide = regex.wideScalarCodec.decode(
                        bytes, cursor, bytes.length, regex.enc);
                if (wide != null) {
                    if (wide.end() <= cursor || wide.end() > bytes.length) {
                        return null;
                    }
                    codePoints.add(wide.value());
                    cursor = wide.end();
                    continue;
                }
            }
            int length = regex.enc.length(bytes, cursor, bytes.length);
            if (length <= 0 || cursor + length > bytes.length) return null;
            codePoints.add((long)regex.enc.mbcToCode(
                    bytes, cursor, cursor + length));
            cursor += length;
        }
        return List.copyOf(codePoints);
    }

    private static Regex.DebugProgramFact ordinaryClassFact(Regex regex,
            int cursor) {
        int instructionCursor = cursor;
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
        org.joni.ast.CClassNode.DebugClassExpression expression =
                regex.debugCharacterClassExpressions.get(instructionCursor);
        Regex.DebugCharacterClassFact characterClass =
                new Regex.DebugCharacterClassFact(negated, false, false,
                        false, effective, expression);
        if (expression != null && expression.provesComplementPair()) {
            return new Regex.DebugProgramFact(expression.outerNegated()
                    ? Regex.DebugProgramKind.EMPTY_CLASS
                    : Regex.DebugProgramKind.FULL_CLASS, characterClass);
        }
        return new Regex.DebugProgramFact(Regex.DebugProgramKind.OTHER,
                characterClass);
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
