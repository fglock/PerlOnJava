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
package org.joni.ast;

import java.util.ArrayList;
import java.util.List;

import org.jcodings.CodeRange;
import org.jcodings.Encoding;
import org.jcodings.IntHolder;
import org.jcodings.constants.CharacterType;
import org.joni.BitSet;
import org.joni.CharacterPropertyResolver;
import org.joni.CodeRangeBuffer;
import org.joni.ScanEnvironment;
import org.joni.WideScalarDomainEnd;
import org.joni.exception.ErrorMessages;
import org.joni.exception.InternalException;
import org.joni.exception.SyntaxException;
import org.joni.exception.ValueException;

public final class CClassNode extends Node {
    public enum DebugDomainShape {
        EMPTY,
        FULL,
        ALL_EXCEPT_NEWLINE,
        OTHER
    }

    public record DebugRange(long from, long to,
            WideScalarDomainEnd domainEnd) {
        public DebugRange {
            if (from < 0 || from > to) {
                throw new IllegalArgumentException("invalid debug range");
            }
            if (domainEnd == WideScalarDomainEnd.PERL_INFINITY
                    && to != Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Perl infinity must terminate the signed domain");
            }
        }

        public DebugRange(long from, long to) {
            this(from, to, WideScalarDomainEnd.HIGHEST_SCALAR);
        }
    }

    public record DebugMembership(boolean storageNegated, boolean caseFolded,
            boolean optimizationSafe, List<DebugRange> ranges) {
        public DebugMembership {
            ranges = List.copyOf(ranges);
        }

        public DebugMembership(boolean storageNegated,
                List<DebugRange> ranges) {
            this(storageNegated, false, false, ranges);
        }
    }

    private static final int FLAG_NCCLASS_NOT = 1 << 0;
    private static final long FIRST_WIDE_SCALAR = 0x110000L;

    private int flags;
    private long[] wideRanges;
    private int wideRangeCount;
    private WideScalarDomainEnd wideDomainEnd =
            WideScalarDomainEnd.HIGHEST_SCALAR;
    private boolean authoritativeWideDomain;
    private boolean debugCaseFolded;
    private boolean debugOptimizationSafe = true;
    private CClassNode propertyFoldMask;
    private List<CharacterPropertyResolver.DeferredProperty> deferredProperties;
    public final BitSet bs = new BitSet();  // conditional creation ?
    public CodeRangeBuffer mbuf;            /* multi-byte info or NULL */

    // node_new_cclass
    public CClassNode() {
        super(CCLASS);
    }

    public CClassNode copy() {
        CClassNode copy = new CClassNode();
        copy.flags = flags;
        copy.bs.copy(bs);
        copy.mbuf = mbuf == null ? null : mbuf.clone();
        copy.wideRanges = wideRanges == null ? null : wideRanges.clone();
        copy.wideRangeCount = wideRangeCount;
        copy.wideDomainEnd = wideDomainEnd;
        copy.authoritativeWideDomain = authoritativeWideDomain;
        copy.debugCaseFolded = debugCaseFolded;
        copy.debugOptimizationSafe = debugOptimizationSafe;
        copy.propertyFoldMask = propertyFoldMask == null
                ? null : propertyFoldMask.copy();
        if (deferredProperties != null) {
            copy.deferredProperties = new ArrayList<>(deferredProperties);
        }
        return copy;
    }

    public void clear() {
        bs.clear();
        flags = 0;
        mbuf = null;
        wideRanges = null;
        wideRangeCount = 0;
        wideDomainEnd = WideScalarDomainEnd.HIGHEST_SCALAR;
        authoritativeWideDomain = false;
        debugCaseFolded = false;
        debugOptimizationSafe = true;
        propertyFoldMask = null;
        deferredProperties = null;
    }

    @Override
    public String getName() {
        return "Character Class";
    }

    @Override
    public String toString(int level) {
        StringBuilder value = new StringBuilder();
        value.append("\n  flags: " + flagsToString());
        value.append("\n  bs: " + pad(bs, level + 1));
        value.append("\n  mbuf: " + pad(mbuf, level + 1));
        return value.toString();
    }

    public String flagsToString() {
        StringBuilder flags = new StringBuilder();
        if (isNot()) flags.append("NOT ");
        return flags.toString();
    }

    public boolean isEmpty() {
        return !hasDeferredProperties()
                && mbuf == null && bs.isEmpty() && wideRangeCount == 0;
    }

    void addCodeRangeToBuf(ScanEnvironment env, int from, int to) {
        addCodeRangeToBuf(env, from, to, true);
    }

    void addCodeRangeToBuf(ScanEnvironment env, int from, int to, boolean checkDup) {
        mbuf = CodeRangeBuffer.addCodeRangeToBuff(mbuf, env, from, to, checkDup);
    }

    // add_code_range, be aware of it returning null!
    public void addCodeRange(ScanEnvironment env, int from, int to) {
        addCodeRange(env, from, to, true);
    }

    public void addCodeRange(ScanEnvironment env, int from, int to, boolean checkDup) {
        // Single-byte opcodes consult the bitset for the complete encoding
        // domain, so retain any byte-sized portion there before recording the
        // remainder in the multibyte range buffer.
        if (env.enc.isSingleByte() && from < BitSet.SINGLE_BYTE_SIZE) {
            bs.setRange(env, Math.max(0, from),
                    Math.min(to, BitSet.SINGLE_BYTE_SIZE - 1));
            if (to < BitSet.SINGLE_BYTE_SIZE) return;
            from = BitSet.SINGLE_BYTE_SIZE;
        }
        mbuf = CodeRangeBuffer.addCodeRange(mbuf, env, from, to, checkDup);
    }

    void addAllMultiByteRange(ScanEnvironment env) {
        mbuf = CodeRangeBuffer.addAllMultiByteRange(env, mbuf);
    }

    public void clearNotFlag(ScanEnvironment env) {
        if (isNot()) {
            bs.invert();
            if (!env.enc.isSingleByte()) {
                mbuf = CodeRangeBuffer.notCodeRangeBuff(env, mbuf);
            }
            setWideRanges(complementWideRanges(wideRangesCopy()),
                    authoritativeWideDomain,
                    complementDomainEnd(wideDomainEnd));
            clearNot();
        }
    }

    public int isOneChar() {
        if (hasDeferredProperties() || isNot()
                || authoritativeWideDomain || wideRangeCount != 0) return -1;
        int c = -1;
        if (mbuf != null) {
            int[]range = mbuf.getCodeRange();
            c = range[1];
            if (range[0] == 1 && c == range[2]) {
                if (c < BitSet.SINGLE_BYTE_SIZE && bs.at(c)) {
                    c = -1;
                }
            } else {
                return -1;
            }
        }

        for (int i = 0; i < BitSet.BITSET_SIZE; i++) {
            int b1 = bs.bits[i];
            if (b1 != 0) {
                if ((b1 & (b1 - 1)) == 0 && c == -1) {
                    c = BitSet.BITS_IN_ROOM * i + Integer.bitCount(b1 - 1);
                } else {
                    return -1;
                }
            }
        }
        return c;
    }

    // and_cclass
    public void and(CClassNode other, ScanEnvironment env) {
        if (hasDeferredProperties() || other.hasDeferredProperties()) {
            throw deferredSetOperationException(other);
        }
        boolean not1 = isNot();
        BitSet bsr1 = bs;
        CodeRangeBuffer buf1 = mbuf;
        boolean not2 = other.isNot();
        BitSet bsr2 = other.bs;
        CodeRangeBuffer buf2 = other.mbuf;
        long[] wide1 = actualWideRanges(wideRangesCopy(), not1);
        long[] wide2 = actualWideRanges(other.wideRangesCopy(), not2);
        WideScalarDomainEnd end1 = actualDomainEnd(wideDomainEnd, not1);
        WideScalarDomainEnd end2 = actualDomainEnd(other.wideDomainEnd, not2);

        if (not1) {
            BitSet bs1 = new BitSet();
            bsr1.invertTo(bs1);
            bsr1 = bs1;
        }

        if (not2) {
            BitSet bs2 = new BitSet();
            bsr2.invertTo(bs2);
            bsr2 = bs2;
        }

        bsr1.and(bsr2);

        if (bsr1 != bs) {
            bs.copy(bsr1);
            bsr1 = bs;
        }

        if (not1) {
            bs.invert();
        }

        CodeRangeBuffer pbuf = null;

        if (!env.enc.isSingleByte()) {
            if (not1 && not2) {
                pbuf = CodeRangeBuffer.orCodeRangeBuff(env, buf1, false, buf2, false);
            } else {
                pbuf = CodeRangeBuffer.andCodeRangeBuff(buf1, not1, buf2, not2, env);

                if (not1) {
                    pbuf = CodeRangeBuffer.notCodeRangeBuff(env, pbuf);
                }
            }
            mbuf = pbuf;
        }
        long[] actual = intersectWideRanges(wide1, wide2);
        boolean authoritative = authoritativeWideDomain
                || other.authoritativeWideDomain || actual.length != 0;
        WideScalarDomainEnd actualEnd = intersectDomainEnd(end1, end2);
        setWideRanges(not1 ? complementWideRanges(actual) : actual,
                authoritative,
                not1 ? complementDomainEnd(actualEnd) : actualEnd);
        mergePropertyFoldMask(other, env);
        debugCaseFolded |= other.debugCaseFolded;
        debugOptimizationSafe &= other.debugOptimizationSafe;

    }

    // or_cclass
    public void or(CClassNode other, ScanEnvironment env) {
        if (hasDeferredProperties() || other.hasDeferredProperties()) {
            throw deferredSetOperationException(other);
        }
        boolean not1 = isNot();
        BitSet bsr1 = bs;
        CodeRangeBuffer buf1 = mbuf;
        boolean not2 = other.isNot();
        BitSet bsr2 = other.bs;
        CodeRangeBuffer buf2 = other.mbuf;
        long[] wide1 = actualWideRanges(wideRangesCopy(), not1);
        long[] wide2 = actualWideRanges(other.wideRangesCopy(), not2);
        WideScalarDomainEnd end1 = actualDomainEnd(wideDomainEnd, not1);
        WideScalarDomainEnd end2 = actualDomainEnd(other.wideDomainEnd, not2);

        if (not1) {
            BitSet bs1 = new BitSet();
            bsr1.invertTo(bs1);
            bsr1 = bs1;
        }

        if (not2) {
            BitSet bs2 = new BitSet();
            bsr2.invertTo(bs2);
            bsr2 = bs2;
        }

        bsr1.or(bsr2);

        if (bsr1 != bs) {
            bs.copy(bsr1);
            bsr1 = bs;
        }

        if (not1) {
            bs.invert();
        }

        if (!env.enc.isSingleByte()) {
            CodeRangeBuffer pbuf = null;
            if (not1 && not2) {
                pbuf = CodeRangeBuffer.andCodeRangeBuff(buf1, false, buf2, false, env);
            } else {
                pbuf = CodeRangeBuffer.orCodeRangeBuff(env, buf1, not1, buf2, not2);
                if (not1) {
                    pbuf = CodeRangeBuffer.notCodeRangeBuff(env, pbuf);
                }
            }
            mbuf = pbuf;
        }
        long[] actual = unionWideRanges(wide1, wide2);
        boolean authoritative = authoritativeWideDomain
                || other.authoritativeWideDomain || actual.length != 0;
        WideScalarDomainEnd actualEnd = unionDomainEnd(end1, end2);
        setWideRanges(not1 ? complementWideRanges(actual) : actual,
                authoritative,
                not1 ? complementDomainEnd(actualEnd) : actualEnd);
        mergePropertyFoldMask(other, env);
        debugCaseFolded |= other.debugCaseFolded;
        debugOptimizationSafe &= other.debugOptimizationSafe;
    }

    private void mergePropertyFoldMask(CClassNode other, ScanEnvironment env) {
        CClassNode merged = propertyFoldMask == null
                ? null : propertyFoldMask.copy();
        if (other.propertyFoldMask != null) {
            if (merged == null) merged = other.propertyFoldMask.copy();
            else merged.or(other.propertyFoldMask, env);
        }
        if (merged == null) return;

        // Provenance is meaningful only for members that survive the set
        // operation. This removes a property source after subtraction and
        // avoids lending its fold policy to an unrelated literal survivor.
        CClassNode membership = copy();
        membership.propertyFoldMask = null;
        merged.and(membership, env);
        propertyFoldMask = merged;
    }

    private CharacterPropertyResolver.ResolutionException
            deferredSetOperationException(CClassNode other) {
        CClassNode owner = hasDeferredProperties() ? this : other;
        CharacterPropertyResolver.DeferredProperty property =
                owner.deferredProperties.get(0);
        String name = new String(property.name(),
                java.nio.charset.StandardCharsets.US_ASCII);
        return new CharacterPropertyResolver.ResolutionException(
                "Unknown user-defined property name \"" + name + "\"",
                property.position());
    }

    public CClassNode propertyFoldMask() {
        return propertyFoldMask;
    }

    public void includeCode(ScanEnvironment env, int code) {
        or(singleton(env, code), env);
    }

    public void excludeCode(ScanEnvironment env, int code) {
        CClassNode excluded = singleton(env, code);
        excluded.setNot();
        and(excluded, env);
    }

    private static CClassNode singleton(ScanEnvironment env, int code) {
        CClassNode singleton = new CClassNode();
        if (code < BitSet.SINGLE_BYTE_SIZE
                && env.enc.codeToMbcLength(code) == 1) {
            singleton.bs.set(env, code);
        } else {
            singleton.addCodeRange(env, code, code, false);
        }
        return singleton;
    }

    public void addWideScalarRange(long from, long to) {
        addWideScalarRange(from, to, WideScalarDomainEnd.HIGHEST_SCALAR);
    }

    public void addWideScalarRange(long from, long to,
            WideScalarDomainEnd domainEnd) {
        if (from < FIRST_WIDE_SCALAR || from > to) {
            throw new ValueException(ErrorMessages.ERR_INVALID_CODE_POINT_VALUE);
        }
        if (domainEnd == WideScalarDomainEnd.PERL_INFINITY
                && to != Long.MAX_VALUE) {
            throw new ValueException(ErrorMessages.ERR_INVALID_CODE_POINT_VALUE);
        }
        long[] added = {from, to};
        setWideRanges(unionWideRanges(wideRangesCopy(), added), true,
                unionDomainEnd(wideDomainEnd, domainEnd));
    }

    public boolean hasWideScalarRanges() {
        return wideRangeCount != 0;
    }

    public boolean hasAuthoritativeWideDomain() {
        return authoritativeWideDomain;
    }

    public boolean isWideScalarInCC(long value) {
        boolean found = containsWideScalar(value);
        return isNot() ? !found : found;
    }

    public boolean isScalarInCC(Encoding enc, long value) {
        if (value <= 0x10ffffL) return isCodeInCC(enc, (int)value);
        return isWideScalarInCC(value);
    }

    public void addDeferredProperty(
            CharacterPropertyResolver.DeferredProperty property) {
        if (deferredProperties == null) deferredProperties = new ArrayList<>();
        deferredProperties.add(property);
        debugOptimizationSafe = false;
    }

    public boolean hasDeferredProperties() {
        return deferredProperties != null && !deferredProperties.isEmpty();
    }

    public int deferredPropertyCount() {
        return deferredProperties == null ? 0 : deferredProperties.size();
    }

    public CharacterPropertyResolver.DeferredProperty deferredProperty(int index) {
        return deferredProperties.get(index);
    }

    /** Matches the static and resolved terms, then applies outer class NOT. */
    public boolean isScalarInDeferredCC(Encoding enc, long value,
            CharacterPropertyResolver.Result[] resolved) {
        return isScalarInDeferredCC(enc, value, resolved, null);
    }

    public boolean isScalarInDeferredCC(Encoding enc, long value,
            CharacterPropertyResolver.Result[] resolved, int[] foldedValues) {
        boolean member = isNot() ? !isScalarInCC(enc, value)
                : isScalarInCC(enc, value);
        for (int index = 0; index < resolved.length; index++) {
            boolean termMember = resultContains(resolved[index], value);
            if (!termMember && foldedValues != null
                    && resolved[index].caseFold
                    && org.joni.Option.isIgnoreCase(
                            deferredProperties.get(index).option())) {
                for (int foldedValue : foldedValues) {
                    if (resultContains(resolved[index], foldedValue)) {
                        termMember = true;
                        break;
                    }
                }
            }
            if (deferredProperties.get(index).negated()) {
                termMember = !termMember;
            }
            member |= termMember;
        }
        return isNot() ? !member : member;
    }

    private static boolean resultContains(CharacterPropertyResolver.Result result,
                                          long value) {
        if (value <= 0x10ffffL && result.ranges != null) {
            int count = result.ranges[0];
            for (int index = 0; index < count; index++) {
                if (value >= result.ranges[index * 2 + 1]
                        && value <= result.ranges[index * 2 + 2]) return true;
            }
        }
        if (result.wideRanges != null) {
            int count = (int)result.wideRanges[0];
            for (int index = 0; index < count; index++) {
                if (value >= result.wideRanges[index * 2 + 1]
                        && value <= result.wideRanges[index * 2 + 2]) return true;
            }
        }
        return false;
    }

    /** Classifies this class over the Unicode and signed-IV scalar domains. */
    public DebugDomainShape debugDomainShape(Encoding enc) {
        boolean allLow = true;
        boolean noLow = true;
        boolean allLowExceptNewline = true;
        for (int code = 0; code < BitSet.SINGLE_BYTE_SIZE; code++) {
            boolean member = isCodeInCC(enc, code);
            allLow &= member;
            noLow &= !member;
            allLowExceptNewline &= code == '\n' ? !member : member;
        }

        boolean rawHighEmpty = !rawRangesIntersect(0x100, 0x10ffff);
        boolean rawHighFull = rawRangesCover(0x100, 0x10ffff);
        boolean highFull = isNot() ? rawHighEmpty : rawHighFull;
        boolean highEmpty = isNot() ? rawHighFull : rawHighEmpty;
        boolean rawWideEmpty = wideRangeCount == 0;
        boolean rawWideFull = wideRangeCount == 1
                && wideRanges[0] == FIRST_WIDE_SCALAR
                && wideRanges[1] == Long.MAX_VALUE;
        boolean executableWideFull = isNot() ? rawWideEmpty : rawWideFull;
        boolean executableWideEmpty = isNot() ? rawWideFull : rawWideEmpty;
        boolean infinityMember = actualDomainEnd(wideDomainEnd, isNot())
                == WideScalarDomainEnd.PERL_INFINITY;
        boolean wideFull = executableWideFull && infinityMember;
        boolean wideEmpty = executableWideEmpty && !infinityMember;

        if (noLow && highEmpty && wideEmpty) return DebugDomainShape.EMPTY;
        if (allLow && highFull && wideFull) return DebugDomainShape.FULL;
        if (allLowExceptNewline && highFull && wideFull) {
            return DebugDomainShape.ALL_EXCEPT_NEWLINE;
        }
        return DebugDomainShape.OTHER;
    }

    /** Returns an immutable snapshot of effective signed-scalar membership. */
    public DebugMembership debugMembership(Encoding enc) {
        List<DebugRange> ranges = new ArrayList<>();
        long start = -1;
        for (int code = 0; code < BitSet.SINGLE_BYTE_SIZE; code++) {
            if (isCodeInCC(enc, code)) {
                if (start < 0) start = code;
            } else if (start >= 0) {
                appendDebugRange(ranges, start, code - 1L);
                start = -1;
            }
        }
        if (start >= 0) {
            appendDebugRange(ranges, start, BitSet.SINGLE_BYTE_SIZE - 1L);
        }

        List<DebugRange> encoded = rawEncodedDebugRanges(0x100, 0x10ffff);
        appendEffectiveDebugRanges(ranges, encoded, 0x100, 0x10ffff,
                isNot());
        List<DebugRange> wide = rawWideDebugRanges();
        appendEffectiveWideDebugRanges(ranges, wide, isNot());
        return new DebugMembership(isNot(), debugCaseFolded,
                debugOptimizationSafe, ranges);
    }

    /** Records that case folding contributed to this final class. */
    public void markDebugCaseFolded() {
        debugCaseFolded = true;
    }

    /** Records a property/POSIX/runtime-dependent class contribution. */
    public void markDebugOptimizationUnsafe() {
        debugOptimizationSafe = false;
    }

    private List<DebugRange> rawEncodedDebugRanges(long minimum, long maximum) {
        if (mbuf == null) return List.of();
        List<DebugRange> ranges = new ArrayList<>();
        int[] raw = mbuf.getCodeRange();
        for (int i = 0; i < raw[0]; i++) {
            long from = Math.max(minimum, raw[i * 2 + 1]);
            long to = Math.min(maximum, raw[i * 2 + 2]);
            if (from <= to) appendDebugRange(ranges, from, to);
        }
        return ranges;
    }

    private List<DebugRange> rawWideDebugRanges() {
        if (wideRangeCount == 0) return List.of();
        List<DebugRange> ranges = new ArrayList<>(wideRangeCount);
        for (int i = 0; i < wideRangeCount; i++) {
            long to = wideRanges[i * 2 + 1];
            WideScalarDomainEnd end = i == wideRangeCount - 1
                    && to == Long.MAX_VALUE ? wideDomainEnd
                    : WideScalarDomainEnd.HIGHEST_SCALAR;
            appendDebugRange(ranges, wideRanges[i * 2], to, end);
        }
        return ranges;
    }

    private void appendEffectiveWideDebugRanges(List<DebugRange> output,
            List<DebugRange> raw, boolean negated) {
        if (!negated) {
            for (DebugRange range : raw) {
                appendDebugRange(output, range.from(), range.to(),
                        range.domainEnd());
            }
            return;
        }
        appendEffectiveDebugRanges(output, raw, FIRST_WIDE_SCALAR,
                Long.MAX_VALUE, true);
        if (actualDomainEnd(wideDomainEnd, true)
                == WideScalarDomainEnd.PERL_INFINITY
                && !output.isEmpty()) {
            int lastIndex = output.size() - 1;
            DebugRange last = output.get(lastIndex);
            if (last.to() == Long.MAX_VALUE) {
                output.set(lastIndex, new DebugRange(last.from(), last.to(),
                        WideScalarDomainEnd.PERL_INFINITY));
            }
        }
    }

    private static void appendEffectiveDebugRanges(List<DebugRange> output,
            List<DebugRange> raw, long minimum, long maximum,
            boolean negated) {
        if (!negated) {
            for (DebugRange range : raw) {
                appendDebugRange(output, range.from(), range.to());
            }
            return;
        }

        long next = minimum;
        for (DebugRange range : raw) {
            if (next < range.from()) {
                appendDebugRange(output, next, range.from() - 1);
            }
            if (range.to() == Long.MAX_VALUE) return;
            next = range.to() + 1;
        }
        if (next <= maximum) appendDebugRange(output, next, maximum);
    }

    private static void appendDebugRange(List<DebugRange> ranges,
            long from, long to) {
        appendDebugRange(ranges, from, to,
                WideScalarDomainEnd.HIGHEST_SCALAR);
    }

    private static void appendDebugRange(List<DebugRange> ranges,
            long from, long to, WideScalarDomainEnd domainEnd) {
        if (from > to) return;
        if (!ranges.isEmpty()) {
            DebugRange previous = ranges.get(ranges.size() - 1);
            if (previous.to() == Long.MAX_VALUE || from <= previous.to() + 1) {
                long mergedTo = Math.max(previous.to(), to);
                WideScalarDomainEnd mergedEnd = mergedTo == Long.MAX_VALUE
                        && (previous.domainEnd()
                                == WideScalarDomainEnd.PERL_INFINITY
                            || domainEnd == WideScalarDomainEnd.PERL_INFINITY)
                        ? WideScalarDomainEnd.PERL_INFINITY
                        : WideScalarDomainEnd.HIGHEST_SCALAR;
                ranges.set(ranges.size() - 1,
                        new DebugRange(previous.from(), mergedTo, mergedEnd));
                return;
            }
        }
        ranges.add(new DebugRange(from, to, domainEnd));
    }

    private boolean rawRangesIntersect(int from, int to) {
        if (mbuf == null) return false;
        int[] ranges = mbuf.getCodeRange();
        for (int i = 0; i < ranges[0]; i++) {
            int rangeFrom = ranges[i * 2 + 1];
            int rangeTo = ranges[i * 2 + 2];
            if (rangeTo >= from && rangeFrom <= to) return true;
            if (rangeFrom > to) return false;
        }
        return false;
    }

    private boolean rawRangesCover(int from, int to) {
        if (mbuf == null) return false;
        int[] ranges = mbuf.getCodeRange();
        long next = from;
        for (int i = 0; i < ranges[0] && next <= to; i++) {
            int rangeFrom = ranges[i * 2 + 1];
            int rangeTo = ranges[i * 2 + 2];
            if (rangeTo < next) continue;
            if (rangeFrom > next) return false;
            next = (long)rangeTo + 1;
        }
        return next > to;
    }

    private boolean containsWideScalar(long value) {
        int low = 0;
        int high = wideRangeCount - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long from = wideRanges[mid * 2];
            long to = wideRanges[mid * 2 + 1];
            if (value < from) high = mid - 1;
            else if (value > to) low = mid + 1;
            else return true;
        }
        return false;
    }

    private long[] wideRangesCopy() {
        if (wideRangeCount == 0) return new long[0];
        return java.util.Arrays.copyOf(wideRanges, wideRangeCount * 2);
    }

    private void setWideRanges(long[] ranges) {
        setWideRanges(ranges, ranges.length != 0,
                WideScalarDomainEnd.HIGHEST_SCALAR);
    }

    private void setWideRanges(long[] ranges, boolean authoritative) {
        setWideRanges(ranges, authoritative,
                WideScalarDomainEnd.HIGHEST_SCALAR);
    }

    private void setWideRanges(long[] ranges, boolean authoritative,
            WideScalarDomainEnd domainEnd) {
        wideRanges = ranges.length == 0 ? null : ranges;
        wideRangeCount = ranges.length / 2;
        wideDomainEnd = domainEnd;
        authoritativeWideDomain = authoritative;
        // A class containing host-defined scalars is opaque to Analyser's
        // Unicode-only class algebra.  CANY preserves its one-character width
        // and prevents unsafe disjointness/possessification decisions;
        // Compiler recognizes the concrete CClassNode and emits the real set.
        type = authoritativeWideDomain ? CANY : CCLASS;
    }

    private static long[] actualWideRanges(long[] ranges, boolean negated) {
        return negated ? complementWideRanges(ranges) : ranges;
    }

    private static WideScalarDomainEnd actualDomainEnd(
            WideScalarDomainEnd domainEnd, boolean negated) {
        return negated ? complementDomainEnd(domainEnd) : domainEnd;
    }

    private static WideScalarDomainEnd complementDomainEnd(
            WideScalarDomainEnd domainEnd) {
        return domainEnd == WideScalarDomainEnd.PERL_INFINITY
                ? WideScalarDomainEnd.HIGHEST_SCALAR
                : WideScalarDomainEnd.PERL_INFINITY;
    }

    private static WideScalarDomainEnd unionDomainEnd(
            WideScalarDomainEnd left, WideScalarDomainEnd right) {
        return left == WideScalarDomainEnd.PERL_INFINITY
                || right == WideScalarDomainEnd.PERL_INFINITY
                ? WideScalarDomainEnd.PERL_INFINITY
                : WideScalarDomainEnd.HIGHEST_SCALAR;
    }

    private static WideScalarDomainEnd intersectDomainEnd(
            WideScalarDomainEnd left, WideScalarDomainEnd right) {
        return left == WideScalarDomainEnd.PERL_INFINITY
                && right == WideScalarDomainEnd.PERL_INFINITY
                ? WideScalarDomainEnd.PERL_INFINITY
                : WideScalarDomainEnd.HIGHEST_SCALAR;
    }

    private static long[] unionWideRanges(long[] left, long[] right) {
        if (left.length == 0) return right.clone();
        if (right.length == 0) return left.clone();
        long[][] pairs = new long[(left.length + right.length) / 2][2];
        int count = 0;
        for (int i = 0; i < left.length; i += 2) {
            pairs[count][0] = left[i];
            pairs[count++][1] = left[i + 1];
        }
        for (int i = 0; i < right.length; i += 2) {
            pairs[count][0] = right[i];
            pairs[count++][1] = right[i + 1];
        }
        java.util.Arrays.sort(pairs, java.util.Comparator.comparingLong(pair -> pair[0]));
        long[] merged = new long[pairs.length * 2];
        int used = 0;
        long from = pairs[0][0];
        long to = pairs[0][1];
        for (int i = 1; i < pairs.length; i++) {
            long nextFrom = pairs[i][0];
            long nextTo = pairs[i][1];
            if (nextFrom <= to || to != Long.MAX_VALUE && nextFrom == to + 1) {
                to = Math.max(to, nextTo);
            } else {
                merged[used++] = from;
                merged[used++] = to;
                from = nextFrom;
                to = nextTo;
            }
        }
        merged[used++] = from;
        merged[used++] = to;
        return java.util.Arrays.copyOf(merged, used);
    }

    private static long[] intersectWideRanges(long[] left, long[] right) {
        long[] result = new long[Math.min(left.length, right.length) * 2];
        int li = 0;
        int ri = 0;
        int used = 0;
        while (li < left.length && ri < right.length) {
            long from = Math.max(left[li], right[ri]);
            long to = Math.min(left[li + 1], right[ri + 1]);
            if (from <= to) {
                result[used++] = from;
                result[used++] = to;
            }
            if (left[li + 1] < right[ri + 1]) li += 2;
            else ri += 2;
        }
        return java.util.Arrays.copyOf(result, used);
    }

    private static long[] complementWideRanges(long[] ranges) {
        long[] result = new long[(ranges.length / 2 + 1) * 2];
        int used = 0;
        long next = FIRST_WIDE_SCALAR;
        for (int i = 0; i < ranges.length; i += 2) {
            long from = Math.max(FIRST_WIDE_SCALAR, ranges[i]);
            long to = ranges[i + 1];
            if (next < from) {
                result[used++] = next;
                result[used++] = from - 1;
            }
            if (to == Long.MAX_VALUE) {
                next = Long.MAX_VALUE;
                return java.util.Arrays.copyOf(result, used);
            }
            next = Math.max(next, to + 1);
        }
        if (next <= Long.MAX_VALUE) {
            result[used++] = next;
            result[used++] = Long.MAX_VALUE;
        }
        return java.util.Arrays.copyOf(result, used);
    }

    // add_ctype_to_cc_by_range // Encoding out!
    public void addCTypeByRange(int ctype, boolean not, ScanEnvironment env, int sbOut, int[] mbr) {
        int n = mbr[0];
        int i;

        if (!not) {
            for (i=0; i<n; i++) {
                for (int j=CR_FROM(mbr, i); j<=CR_TO(mbr, i); j++) {
                    if (j >= sbOut) {
                        if (j > CR_FROM(mbr, i)) {
                            addCodeRangeToBuf(env, j, CR_TO(mbr, i));
                            i++;
                        }
                        // !goto sb_end!, remove duplication!
                        for (; i<n; i++) {
                            addCodeRangeToBuf(env, CR_FROM(mbr, i), CR_TO(mbr, i));
                        }
                        return;
                    }
                    bs.set(env, j);
                }
            }
            // !sb_end:!
            for (; i<n; i++) {
                addCodeRangeToBuf(env, CR_FROM(mbr, i), CR_TO(mbr, i));
            }

        } else {
            int prev = 0;

            for (i=0; i<n; i++) {
                for (int j=prev; j < CR_FROM(mbr, i); j++) {
                    if (j >= sbOut) {
                        // !goto sb_end2!, remove duplication
                        prev = sbOut;
                        for (i=0; i<n; i++) {
                            if (prev < CR_FROM(mbr, i)) addCodeRangeToBuf(env, prev, CR_FROM(mbr, i) - 1);
                            prev = CR_TO(mbr, i) + 1;
                        }
                        if (prev < 0x7fffffff/*!!!*/) addCodeRangeToBuf(env, prev, 0x7fffffff);
                        return;
                    }
                    bs.set(env, j);
                }
                prev = CR_TO(mbr, i) + 1;
            }

            for (int j=prev; j<sbOut; j++) {
                bs.set(env, j);
            }

            // !sb_end2:!
            prev = sbOut;
            for (i=0; i<n; i++) {
                if (prev < CR_FROM(mbr, i)) addCodeRangeToBuf(env, prev, CR_FROM(mbr, i) - 1);
                prev = CR_TO(mbr, i) + 1;
            }
            if (prev < 0x7fffffff/*!!!*/) addCodeRangeToBuf(env, prev, 0x7fffffff);
        }
    }

    /** Adds resolver-provided {@code [count, from, to, ...]} code-point ranges. */
    public void addCodeRanges(int[] ranges, boolean not, ScanEnvironment env) {
        int singleByteLimit = 0;
        while (singleByteLimit < BitSet.SINGLE_BYTE_SIZE
                && env.enc.codeToMbcLength(singleByteLimit) == 1) {
            singleByteLimit++;
        }
        addCTypeByRange(0, not, env, singleByteLimit, ranges);
    }

    /**
     * Adds the union of resolver-provided encoding-domain and signed-IV-domain
     * ranges. The complement, when requested, is applied once to the combined
     * set so mixed normal/wide properties retain exact set semantics.
     */
    public void addCodeRanges(int[] ranges, long[] wideRanges, boolean not,
                              ScanEnvironment env) {
        if (wideRanges == null) {
            addCodeRanges(ranges, not, env);
            return;
        }

        CClassNode resolved = new CClassNode();
        if (ranges != null) resolved.addCodeRanges(ranges, false, env);
        resolved.setWideRanges(new long[0], true);

        int normalCount = 0;
        int rangeCount = (int)wideRanges[0];
        for (int i = 0; i < rangeCount; i++) {
            if (wideRanges[i * 2 + 1] < FIRST_WIDE_SCALAR) normalCount++;
        }
        if (normalCount != 0) {
            int[] normalRanges = new int[normalCount * 2 + 1];
            normalRanges[0] = normalCount;
            int cursor = 1;
            for (int i = 0; i < rangeCount; i++) {
                long from = wideRanges[i * 2 + 1];
                long to = wideRanges[i * 2 + 2];
                if (from >= FIRST_WIDE_SCALAR) continue;
                normalRanges[cursor++] = (int)from;
                normalRanges[cursor++] = (int)Math.min(to, FIRST_WIDE_SCALAR - 1);
            }
            resolved.addCodeRanges(normalRanges, false, env);
        }

        for (int i = 0; i < rangeCount; i++) {
            long from = wideRanges[i * 2 + 1];
            long to = wideRanges[i * 2 + 2];
            if (to >= FIRST_WIDE_SCALAR) {
                resolved.addWideScalarRange(Math.max(from, FIRST_WIDE_SCALAR), to);
            }
        }

        if (not) resolved.setNot();
        or(resolved, env);
    }

    public void markPropertyFoldCodeRanges(int[] ranges, long[] wideRanges,
                                           boolean not, ScanEnvironment env) {
        if (propertyFoldMask == null) propertyFoldMask = new CClassNode();
        propertyFoldMask.addCodeRanges(ranges, wideRanges, not, env);
    }

    private static int CR_FROM(int[] range, int i) {
        return range[(i * 2) + 1];
    }

    private static int CR_TO(int[] range, int i) {
        return range[(i * 2) + 2];
    }

    // add_ctype_to_cc
    public void addCType(int ctype, boolean not, boolean asciiRange, ScanEnvironment env, IntHolder sbOut) {
        Encoding enc = env.enc;
        int[]ranges = enc.ctypeCodeRange(ctype, sbOut);
        if (ranges != null) {
            if (asciiRange) {
                CClassNode ccWork = new CClassNode();
                ccWork.addCTypeByRange(ctype, not, env, sbOut.value, ranges);
                if (not) {
                    ccWork.addCodeRangeToBuf(env, 0x80, CodeRangeBuffer.LAST_CODE_POINT, false);
                } else {
                    CClassNode ccAscii = new CClassNode();
                    if (enc.minLength() > 1) {
                        ccAscii.addCodeRangeToBuf(env, 0x00, 0x7F);
                    } else {
                        ccAscii.bs.setRange(env, 0x00, 0x7F);
                    }
                    ccWork.and(ccAscii, env);
                }
                or(ccWork, env);
            } else {
                addCTypeByRange(ctype, not, env, sbOut.value, ranges);
            }
            return;
        }

        int maxCode = asciiRange ? 0x80 : BitSet.SINGLE_BYTE_SIZE;
        switch(ctype) {
        case CharacterType.ALPHA:
        case CharacterType.BLANK:
        case CharacterType.CNTRL:
        case CharacterType.DIGIT:
        case CharacterType.LOWER:
        case CharacterType.PUNCT:
        case CharacterType.SPACE:
        case CharacterType.UPPER:
        case CharacterType.XDIGIT:
        case CharacterType.ASCII:
        case CharacterType.ALNUM:
            if (not) {
                for (int c=0; c<BitSet.SINGLE_BYTE_SIZE; c++) {
                    if (!enc.isCodeCType(c, ctype) || c >= maxCode) bs.set(env, c);
                }
                if (asciiRange || enc.minLength() > 1) addAllMultiByteRange(env);
            } else {
                for (int c=0; c<maxCode; c++) {
                    if (enc.isCodeCType(c, ctype)) bs.set(env, c);
                }
            }
            break;

        case CharacterType.GRAPH:
        case CharacterType.PRINT:
            if (not) {
                for (int c=0; c<BitSet.SINGLE_BYTE_SIZE; c++) {
                    if (!enc.isCodeCType(c, ctype) || c >= maxCode) bs.set(env, c);
                }
                if (asciiRange) addAllMultiByteRange(env);
            } else {
                for (int c=0; c<maxCode; c++) {
                    if (enc.isCodeCType(c, ctype)) bs.set(env, c);
                }
                if (!asciiRange) addAllMultiByteRange(env);
            }
            break;

        case CharacterType.WORD:
            if (!not) {
                for (int c=0; c<maxCode; c++) {
                    if (enc.isSbWord(c)) bs.set(env, c);
                }
                if (!asciiRange) addAllMultiByteRange(env);
            } else {
                for (int c=0; c<BitSet.SINGLE_BYTE_SIZE; c++) {
                    if (enc.codeToMbcLength(c) > 0 && /* check invalid code point */
                            (!enc.isWord(c) || c >= maxCode)) bs.set(env, c);
                }
                if (asciiRange) addAllMultiByteRange(env);
            }
            break;

        default:
            throw new InternalException(ErrorMessages.PARSER_BUG);
        } // switch
    }

    public void markPropertyFoldCType(int ctype, boolean not,
                                      ScanEnvironment env, IntHolder sbOut) {
        if (propertyFoldMask == null) propertyFoldMask = new CClassNode();
        propertyFoldMask.addCType(ctype, not, false, env, sbOut);
    }

    public enum CCVALTYPE {
        SB,
        CODE_POINT,
        WIDE_SCALAR,
        CLASS
    }

    public enum CCSTATE {
        VALUE,
        RANGE,
        COMPLETE,
        START
    }

    public static final class CCStateArg {
        public long from;
        public long to;
        public WideScalarDomainEnd fromWideDomainEnd =
                WideScalarDomainEnd.HIGHEST_SCALAR;
        public WideScalarDomainEnd toWideDomainEnd =
                WideScalarDomainEnd.HIGHEST_SCALAR;
        public boolean fromIsRaw;
        public boolean toIsRaw;
        public boolean fromEscaped;
        public boolean toEscaped;
        public boolean fromNamedCharacter;
        public boolean toNamedCharacter;
        public boolean fromFalseRangeEligible;
        public boolean toFalseRangeEligible;
        public int fromStart;
        public int fromEnd;
        public int toStart;
        public int toEnd;
        public CCVALTYPE inType;
        public CCVALTYPE type;
        public CCSTATE state;
    }

    public void nextStateClass(CCStateArg arg, CClassNode ascCc,
                               CClassNode foldCc, ScanEnvironment env) {
        if (arg.state == CCSTATE.RANGE) {
            if (!env.usesPerlDiagnostics()) {
                throw new SyntaxException(ErrorMessages.CHAR_CLASS_VALUE_AT_END_OF_RANGE);
            }

            // Perl accepts a character class as a false range endpoint, such
            // as [a-\d].  The hyphen is literal and both operands remain
            // members of the surrounding class.
            arg.state = CCSTATE.VALUE;
            nextStateValue(arg, ascCc, foldCc, env);
            arg.to = '-';
            arg.toIsRaw = false;
            arg.inType = CCVALTYPE.SB;
            nextStateValue(arg, ascCc, foldCc, env);
        }

        if (arg.state == CCSTATE.VALUE && arg.type != CCVALTYPE.CLASS) {
            if (arg.type == CCVALTYPE.SB) {
                bs.set(env, (int)arg.from);
                if (ascCc != null) ascCc.bs.set((int)arg.from);
                if (foldCc != null) foldCc.bs.set((int)arg.from);
            } else if (arg.type == CCVALTYPE.CODE_POINT) {
                addCodeRange(env, (int)arg.from, (int)arg.from);
                if (ascCc != null) ascCc.addCodeRange(env, (int)arg.from, (int)arg.from, false);
                if (foldCc != null) foldCc.addCodeRange(env, (int)arg.from, (int)arg.from, false);
            } else if (arg.type == CCVALTYPE.WIDE_SCALAR) {
                addWideScalarRange(arg.from, arg.from,
                        arg.fromWideDomainEnd);
            }
        }
        arg.state = CCSTATE.VALUE;
        arg.type = CCVALTYPE.CLASS;
        arg.fromStart = arg.toStart;
        arg.fromEnd = arg.toEnd;
        arg.fromNamedCharacter = arg.toNamedCharacter;
        arg.fromFalseRangeEligible = arg.toFalseRangeEligible;
    }

    public void nextStateValue(CCStateArg arg, CClassNode ascCc,
                               CClassNode foldCc, ScanEnvironment env) {
        switch(arg.state) {
        case VALUE:
            if (arg.type == CCVALTYPE.SB) {
                bs.set(env, (int)arg.from);
                if (ascCc != null) ascCc.bs.set((int)arg.from);
                if (foldCc != null) foldCc.bs.set((int)arg.from);
            } else if (arg.type == CCVALTYPE.CODE_POINT) {
                addCodeRange(env, (int)arg.from, (int)arg.from);
                if (ascCc != null) ascCc.addCodeRange(env, (int)arg.from, (int)arg.from, false);
                if (foldCc != null) foldCc.addCodeRange(env, (int)arg.from, (int)arg.from, false);
            } else if (arg.type == CCVALTYPE.WIDE_SCALAR) {
                addWideScalarRange(arg.from, arg.from,
                        arg.fromWideDomainEnd);
            }
            break;

        case RANGE:
            if (arg.inType == arg.type) {
                if (arg.inType == CCVALTYPE.SB) {
                    if (arg.from > 0xff || arg.to > 0xff) throw new ValueException(ErrorMessages.ERR_INVALID_CODE_POINT_VALUE);

                    if (arg.from > arg.to) {
                        if (env.syntax.allowEmptyRangeInCC()) {
                            // goto ccs_range_end
                            arg.state = CCSTATE.COMPLETE;
                            break;
                        } else {
                            throw new ValueException(env.emptyRangeError());
                        }
                    }
                    bs.setRange(env, (int)arg.from, (int)arg.to);
                    if (ascCc != null) ascCc.bs.setRange(null, (int)arg.from, (int)arg.to);
                    if (foldCc != null) foldCc.bs.setRange(null, (int)arg.from, (int)arg.to);
                } else if (arg.inType == CCVALTYPE.WIDE_SCALAR) {
                    if (arg.from > arg.to) {
                        if (env.syntax.allowEmptyRangeInCC()) {
                            arg.state = CCSTATE.COMPLETE;
                            break;
                        }
                        throw new ValueException(env.emptyRangeError());
                    }
                    addWideScalarRange(arg.from, arg.to,
                            arg.toWideDomainEnd);
                } else {
                    addCodeRange(env, (int)arg.from, (int)arg.to);
                    if (ascCc != null) ascCc.addCodeRange(env, (int)arg.from, (int)arg.to, false);
                    if (foldCc != null) foldCc.addCodeRange(env, (int)arg.from, (int)arg.to, false);
                }
            } else {
                if (arg.from > arg.to) {
                    if (env.syntax.allowEmptyRangeInCC()) {
                        // goto ccs_range_end
                        arg.state = CCSTATE.COMPLETE;
                        break;
                    } else {
                        throw new ValueException(env.emptyRangeError());
                    }
                }
                long normalTo = Math.min(arg.to, 0x10ffffL);
                if (arg.from <= normalTo) {
                    int normalFrom = (int)arg.from;
                    int normalEnd = (int)normalTo;
                    if (normalFrom < BitSet.SINGLE_BYTE_SIZE) {
                        bs.setRange(env, normalFrom, Math.min(normalEnd, 0xff));
                    }
                    addCodeRange(env, normalFrom, normalEnd);
                    if (ascCc != null) ascCc.addCodeRange(env, normalFrom, normalEnd, false);
                    if (foldCc != null) foldCc.addCodeRange(env, normalFrom, normalEnd, false);
                }
                if (arg.to >= FIRST_WIDE_SCALAR) {
                    addWideScalarRange(Math.max(arg.from, FIRST_WIDE_SCALAR),
                            arg.to, arg.toWideDomainEnd);
                }
            }
            // ccs_range_end:
            arg.state = CCSTATE.COMPLETE;
            break;

        case COMPLETE:
        case START:
            arg.state = CCSTATE.VALUE;
            break;

        default:
            break;

        } // switch

        arg.fromIsRaw = arg.toIsRaw;
        arg.fromEscaped = arg.toEscaped;
        arg.fromNamedCharacter = arg.toNamedCharacter;
        arg.fromFalseRangeEligible = arg.toFalseRangeEligible;
        arg.fromStart = arg.toStart;
        arg.fromEnd = arg.toEnd;
        arg.from = arg.to;
        arg.fromWideDomainEnd = arg.toWideDomainEnd;
        arg.type = arg.inType;
    }

    // onig_is_code_in_cc_len
    boolean isCodeInCCLength(int encLength, int code) {
        boolean found;

        if (encLength > 1 || code >= BitSet.SINGLE_BYTE_SIZE) {
            if (mbuf == null) {
                found = false;
            } else {
                found = CodeRange.isInCodeRange(mbuf.getCodeRange(), code);
            }
        } else {
            found = bs.at(code);
        }

        if (isNot()) {
            return !found;
        } else {
            return found;
        }
    }

    // onig_is_code_in_cc
    public boolean isCodeInCC(Encoding enc, int code) {
        int len;
        if (enc.minLength() > 1) {
            len = 2;
        } else {
            len = enc.codeToMbcLength(code);
        }
        return isCodeInCCLength(len, code);
    }

    public void setNot() {
        flags |= FLAG_NCCLASS_NOT;
    }

    public void clearNot() {
        flags &= ~FLAG_NCCLASS_NOT;
    }

    public boolean isNot() {
        return (flags & FLAG_NCCLASS_NOT) != 0;
    }
}
