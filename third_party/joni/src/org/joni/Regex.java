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

import static org.joni.BitStatus.bsAt;
import static org.joni.Config.USE_SUNDAY_QUICK_SEARCH;
import static org.joni.Option.isCaptureGroup;
import static org.joni.Option.isDontCaptureGroup;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jcodings.CaseFoldCodeItem;
import org.jcodings.Encoding;
import org.jcodings.EncodingDB;
import org.jcodings.constants.CharacterType;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jcodings.util.BytesHash;
import org.joni.constants.internal.AnchorType;
import org.joni.ast.CClassNode;
import org.joni.exception.ErrorMessages;
import org.joni.exception.InternalException;
import org.joni.exception.ValueException;

public final class Regex {
    public enum ParsedProgramFeature {
        INLINE_ASCII_STRICT,
        KEEP,
        POSITIVE_LOOKBEHIND,
        NEGATIVE_LOOKBEHIND,
        NATIVE_EXTENDED_CLASS_LEAF,
        BRANCH_RESET,
        CONDITIONAL,
        ALPHA_ASSERTION,
        SCRIPT_RUN,
        ATOMIC_SCRIPT_RUN,
        SUBEXPRESSION_CALL,
        NAMED_CHARACTER_ESCAPE,
        CALLOUT,
        DYNAMIC_CALLOUT,
        EMPTY_CHARACTER_CLASS,
        G_ASSERTION
    }

    public record ParsedProgramMetadata(Set<ParsedProgramFeature> features) {
        private static final ParsedProgramMetadata EMPTY =
                new ParsedProgramMetadata(Set.of());

        public ParsedProgramMetadata {
            features = features.isEmpty()
                    ? Set.of() : Set.copyOf(features);
        }

        public boolean has(ParsedProgramFeature feature) {
            return features.contains(feature);
        }

        static ParsedProgramMetadata copyOf(
                EnumSet<ParsedProgramFeature> features) {
            return features.isEmpty() ? EMPTY
                    : new ParsedProgramMetadata(EnumSet.copyOf(features));
        }
    }

    int[] code;             /* compiled pattern */
    int codeLength;
    boolean requireStack;
    boolean hasDynamicOptions;
    boolean hasUnicodeCharsetModifier;
    boolean hasCharacterProperty;
    private ParsedProgramMetadata parsedProgramMetadata =
            ParsedProgramMetadata.EMPTY;

    int numMem;             /* used memory(...) num counted from 1 */
    int numPhysicalNamedCaptures;
    int numRepeat;          /* OP_REPEAT/OP_REPEAT_NG id-counter */
    int numNullCheck;       /* OP_NULL_CHECK_START/END id counter */
    int numCombExpCheck;    /* combination explosion check */
    int numCall;            /* number of subexp call */
    int captureHistory;     /* (?@...) flag (1-31) */
    int btMemStart;         /* need backtrack flag */
    int btMemEnd;           /* need backtrack flag */

    int stackPopLevel;

    int[]repeatRangeLo;
    int[]repeatRangeHi;
    int[][] repeatCaptureClearGroups;
    int numRepeatCaptureClearGroups;

    MatcherFactory factory;

    final Encoding enc;
    final boolean perlSyntax;
    int options;
    int userOptions;
    Object userObject;
    final int caseFoldFlag;

    private BytesHash<NameEntry> nameTable; // named entries

    /* optimization info (string search, char-map and anchors) */
    Search.Forward forward;                 /* optimize flag */
    Search.Backward backward;
    int thresholdLength;                    /* search str-length for apply optimize */
    int anchor;                             /* BEGIN_BUF, BEGIN_POS, (SEMI_)END_BUF */
    int anchorDmin;                         /* (SEMI_)END_BUF anchor distance */
    int anchorDmax;                         /* (SEMI_)END_BUF anchor distance */
    int subAnchor;                          /* start-anchor for exact or map */

    byte[]exact;
    int exactP;
    int exactEnd;

    byte[]map;                              /* used as BM skip or char-map */
    int[]intMap;                            /* BM skip for exact_len > 255 */
    int[]intMapBackward;                    /* BM skip for backward search */
    int dMin;                               /* min-distance of exact or map */
    int dMax;                               /* max-distance of exact or map */
    int minimumLength;                      /* minimum match length */
    boolean exactReachEnd;                  /* selected exact reaches pattern end */
    boolean characterMapOptimization;       /* selected search uses the char map */

    byte[][]templates;                      /* fixed pattern strings not embedded in bytecode */
    int templateNum;
    boolean hasControlVerb;
    boolean hasForwardNamedBackreference;
    String[] controlVerbLabels;
    CClassNode[] wideScalarClasses;
    Map<Integer, CClassNode.DebugClassExpression>
            debugCharacterClassExpressions = Map.of();
    Map<Integer, Integer> debugExactOptions = Map.of();
    Set<Integer> debugSingleSourceMultiFolds = Set.of();
    private List<CharacterPropertyResolver.DeferredProperty>
            deferredCharacterProperties;
    final WideScalarCodec wideScalarCodec;
    final CharacterPropertyResolver characterPropertyResolver;

    private static final Encoding DEFAULT_ENCODING;
    static {
        Encoding defaultEncoding;
        EncodingDB.Entry entry = EncodingDB.getEncodings().get(Charset.defaultCharset().name().getBytes());
        if (entry == null) {
            defaultEncoding = UTF8Encoding.INSTANCE;
        } else {
            defaultEncoding = entry.getEncoding();
        }
        DEFAULT_ENCODING = defaultEncoding;
    }

    public Regex(CharSequence cs) {
        this(cs.toString());
    }

    public Regex(CharSequence cs, Encoding enc) {
        this(cs.toString(), enc);
    }

    public Regex(String str) {
        this(str.getBytes(), 0, str.length(), 0, UTF8Encoding.INSTANCE);
    }

    public Regex(String str, Encoding enc) {
        this(str.getBytes(), 0, str.length(), 0, enc);
    }

    public Regex(byte[] bytes) {
        this(bytes, 0, bytes.length, 0, ASCIIEncoding.INSTANCE);
    }

    public Regex(byte[] bytes, int p, int end) {
        this(bytes, p, end, 0, ASCIIEncoding.INSTANCE);
    }

    public Regex(byte[] bytes, int p, int end, int option) {
        this(bytes, p, end, option, ASCIIEncoding.INSTANCE);
    }

    public Regex(byte[]bytes, int p, int end, int option, Encoding enc) {
        this(bytes, p, end, option, enc, Syntax.RUBY, WarnCallback.DEFAULT);
    }

    // onig_new
    public Regex(byte[]bytes, int p, int end, int option, Encoding enc, Syntax syntax) {
        this(bytes, p, end, option, Config.ENC_CASE_FOLD_DEFAULT, enc, syntax, WarnCallback.DEFAULT);
    }

    public Regex(byte[]bytes, int p, int end, int option, Encoding enc, WarnCallback warnings) {
        this(bytes, p, end, option, enc, Syntax.RUBY, warnings);
    }

    // onig_new
    public Regex(byte[]bytes, int p, int end, int option, Encoding enc, Syntax syntax, WarnCallback warnings) {
        this(bytes, p, end, option, Config.ENC_CASE_FOLD_DEFAULT, enc, syntax, warnings);
    }

    // onig_alloc_init
    public Regex(byte[]bytes, int p, int end, int option, int caseFoldFlag, Encoding enc, Syntax syntax, WarnCallback warnings) {
        if (Config.REGEX_MAX_LENGTH > 0 && (end - p) > Config.REGEX_MAX_LENGTH) {
            throw new ValueException(ErrorMessages.REGEX_TOO_LONG);
        }

        if ((option & (Option.DONT_CAPTURE_GROUP | Option.CAPTURE_GROUP)) ==
            (Option.DONT_CAPTURE_GROUP | Option.CAPTURE_GROUP)) {
            throw new ValueException(ErrorMessages.INVALID_COMBINATION_OF_OPTIONS);
        }

        if ((option & Option.NEGATE_SINGLELINE) != 0) {
            option |= syntax.options;
            option &= ~Option.SINGLELINE;
        } else {
            option |= syntax.options;
        }

        this.enc = enc;
        this.perlSyntax = syntax.op2OptionPerl();
        this.wideScalarCodec = syntax.wideScalarCodec;
        this.characterPropertyResolver = syntax.characterPropertyResolver;
        this.options = option;
        this.caseFoldFlag = caseFoldFlag;
        Analyser analyser = new Analyser(this, syntax, bytes, p, end, warnings);
        try {
            analyser.compile();
        } catch (org.joni.exception.SyntaxException error) {
            throw error.withParsedProgramMetadata(
                    analyser.parsedProgramMetadata());
        }
    }

    final int caseFoldFlagFor(int option) {
        return Option.isPerlAsciiStrict(option)
                ? caseFoldFlag & ~Config.INTERNAL_ENC_CASE_FOLD_MULTI_CHAR
                : caseFoldFlag;
    }

    void publishParsedProgramMetadata(ParsedProgramMetadata metadata) {
        parsedProgramMetadata = Objects.requireNonNull(metadata);
    }

    public ParsedProgramMetadata getParsedProgramMetadata() {
        return parsedProgramMetadata;
    }

    public Matcher matcher(byte[]bytes) {
        return matcher(bytes, 0, bytes.length);
    }

    public Matcher matcherNoRegion(byte[]bytes) {
        return matcherNoRegion(bytes, 0, bytes.length);
    }

    public Matcher matcher(byte[]bytes, int p, int end) {
        return factory.create(this, numMem == 0 ? null : Region.newRegion(numMem + 1), bytes, p, end);
    }

    public Matcher matcher(byte[]bytes, int p, int end, long timeout) {
        return factory.create(this, numMem == 0 ? null : Region.newRegion(numMem + 1), bytes, p, end, timeout);
    }

    public Matcher matcherNoRegion(byte[]bytes, int p, int end) {
        return factory.create(this, null, bytes, p, end);
    }

    public Matcher matcherNoRegion(byte[]bytes, int p, int end, long timeout) {
        return factory.create(this, null, bytes, p, end, timeout);
    }

    public int numberOfCaptures() {
        return numMem;
    }

    /**
     * Whether every compiled character class explicitly defines its signed-wide
     * domain, with at least one such class present.
     */
    public boolean hasOnlyAuthoritativeWideCharacterClasses() {
        if (wideScalarClasses == null || wideScalarClasses.length == 0) return false;
        for (CClassNode characterClass : wideScalarClasses) {
            if (characterClass.hasDeferredProperties()
                    || !characterClass.hasAuthoritativeWideDomain()) return false;
        }
        return true;
    }

    /**
     * Whether every non-authoritative wide class is matcher-deferred. The host
     * may use this with callback-free knowledge about each deferred result.
     */
    public boolean hasOnlyAuthoritativeOrDeferredWideCharacterClasses() {
        if (wideScalarClasses == null || wideScalarClasses.length == 0) return false;
        for (CClassNode characterClass : wideScalarClasses) {
            if (!characterClass.hasAuthoritativeWideDomain()
                    && !characterClass.hasDeferredProperties()) return false;
        }
        return true;
    }

    /** Whether the compiled program has matcher-resolved property terms. */
    public boolean hasDeferredCharacterProperties() {
        return deferredCharacterProperties != null
                && !deferredCharacterProperties.isEmpty();
    }

    void addDeferredCharacterProperty(
            CharacterPropertyResolver.DeferredProperty property) {
        if (deferredCharacterProperties == null) {
            deferredCharacterProperties = new ArrayList<>();
        }
        deferredCharacterProperties.add(property);
    }

    /** Matcher-resolved property facts in parser/source order. */
    public List<CharacterPropertyResolver.DeferredProperty>
            deferredCharacterProperties() {
        return deferredCharacterProperties == null
                ? List.of() : List.copyOf(deferredCharacterProperties);
    }

    /** Whether the compiled program contains at least one real control verb. */
    public boolean hasControlVerbs() {
        return hasControlVerb;
    }

    /** Whether parsing encountered a real positive inline Perl /u, /a, or /aa. */
    public boolean hasUnicodeCharsetModifier() {
        return hasUnicodeCharsetModifier;
    }

    void markCharacterProperty() {
        hasCharacterProperty = true;
    }

    /** Whether parsing accepted a real character-property token. */
    public boolean hasCharacterProperty() {
        return hasCharacterProperty;
    }

    public int numberOfCaptureHistories() {
        if (Config.USE_CAPTURE_HISTORY) {
            int n = 0;
            for (int i=0; i<=Config.MAX_CAPTURE_HISTORY_GROUP; i++) {
                if (bsAt(captureHistory, i)) n++;
            }
            return n;
        } else {
            return 0;
        }
    }

    private NameEntry nameFind(byte[]name, int nameP, int nameEnd) {
        if (nameTable != null) return nameTable.get(name, nameP, nameEnd);
        return null;
    }

    void renumberNameTable(int[]map) {
        if (nameTable != null) {
            for (NameEntry e : nameTable) {
                if (e.backNum > 1) {
                    for (int i=0; i<e.backNum; i++) {
                        e.backRefs[i] = map[e.backRefs[i]];
                    }
                } else if (e.backNum == 1) {
                    e.backRef1 = map[e.backRef1];
                }
            }
        }
    }

    int nameAdd(byte[]name, int nameP, int nameEnd, int backRef, Syntax syntax) {
        if (nameEnd - nameP <= 0) throw new ValueException(ErrorMessages.EMPTY_GROUP_NAME);

        NameEntry e = null;
        if (nameTable == null) {
            nameTable = new BytesHash<>(); // 13, oni defaults to 5
        } else {
            e = nameFind(name, nameP, nameEnd);
        }

        if (e == null) {
            // dup the name here as oni does ?, what for ? (it has to manage it, we don't)
            e = new NameEntry(name, nameP, nameEnd);
            nameTable.putDirect(name, nameP, nameEnd, e);
        } else if (e.backNum >= 1 && !syntax.allowMultiplexDefinitionName()) {
            throw new ValueException(ErrorMessages.MULTIPLEX_DEFINED_NAME, new String(name, nameP, nameEnd - nameP));
        }

        int physicalRef = ++numPhysicalNamedCaptures;
        e.addBackref(backRef, physicalRef);
        return physicalRef;
    }

    public int numberOfPhysicalNamedCaptures() {
        return numPhysicalNamedCaptures;
    }

    NameEntry nameToGroupNumbers(byte[]name, int nameP, int nameEnd) {
        return nameFind(name, nameP, nameEnd);
    }

    public int nameToBackrefNumber(byte[]name, int nameP, int nameEnd, Region region) {
        return nameToBackrefNumber(name, nameP, nameEnd, DEFAULT_ENCODING, region);
    }

    public int nameToBackrefNumber(byte[]name, int nameP, int nameEnd, Encoding nameEncoding, Region region) {
        NameEntry e = nameToGroupNumbers(name, nameP, nameEnd);
        if (e == null) throw new ValueException(ErrorMessages.UNDEFINED_NAME_REFERENCE,
                                                new String(name, nameP, nameEnd - nameP, nameEncoding.getCharset()));

        switch(e.backNum) {
        case 0:
            throw new InternalException(ErrorMessages.PARSER_BUG);
        case 1:
            return e.backRef1;
        default:
            if (region != null) {
                for (int i = e.backNum - 1; i >= 0; i--) {
                    if (region.getBeg(e.backRefs[i]) != Region.REGION_NOTPOS) return e.backRefs[i];
                }
            }
            return e.backRefs[e.backNum - 1];
        }
    }

    String nameTableToString() {
        StringBuilder sb = new StringBuilder();

        if (nameTable != null) {
            sb.append("name table\n");
            for (NameEntry ne : nameTable) {
                sb.append("  ").append(ne).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public Iterator<NameEntry> namedBackrefIterator() {
        return nameTable == null ? Collections.<NameEntry>emptyIterator() : nameTable.iterator();
    }

    public int numberOfNames() {
        return nameTable == null ? 0 : nameTable.size();
    }

    public boolean noNameGroupIsActive(Syntax syntax) {
        if (isDontCaptureGroup(options)) return false;

        if (Config.USE_NAMED_GROUP) {
            if (numberOfNames() > 0 && syntax.captureOnlyNamedGroup() && !isCaptureGroup(options)) return false;
        }
        return true;
    }

    /* set skip map for Boyer-Moor search */
    boolean setupBMSkipMap(boolean ignoreCase) {
        byte[]bytes = exact;
        int s = exactP;
        int end = exactEnd;
        int len = end - s;
        int clen;
        CaseFoldCodeItem[]items = CaseFoldCodeItem.EMPTY_FOLD_CODES;
        byte[]buf = new byte[Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM * Config.ENC_MBC_CASE_FOLD_MAXLEN];

        final int ilen = USE_SUNDAY_QUICK_SEARCH ? len : len - 1;
        if (Config.USE_BYTE_MAP || len < Config.CHAR_TABLE_SIZE) {
            if (map == null) map = new byte[Config.CHAR_TABLE_SIZE]; // map/skip
            for (int i = 0; i < Config.CHAR_TABLE_SIZE; i++) map[i] = (byte)(USE_SUNDAY_QUICK_SEARCH ? len + 1 : len);

            for (int i = 0; i < ilen; i += clen) {
                if (ignoreCase) items = enc.caseFoldCodesByString(caseFoldFlag, bytes, s + i, end);
                clen = setupBMSkipMapCheck(bytes, s + i, end, items, buf);
                if (clen == 0) return true;

                for (int j = 0; j < clen; j++) {
                    map[bytes[s + i + j] & 0xff] = (byte)(ilen - i - j);
                    for (int k = 0; k < items.length; k++) {
                        map[buf[k * Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM + j] & 0xff] = (byte)(ilen - i - j);
                    }
                }
            }
        } else {
            if (intMap == null) intMap = new int[Config.CHAR_TABLE_SIZE];
            for (int i = 0; i < Config.CHAR_TABLE_SIZE; i++) intMap[i] = (USE_SUNDAY_QUICK_SEARCH ? len + 1 : len);

            for (int i = 0; i < ilen; i += clen) {
                if (ignoreCase) items = enc.caseFoldCodesByString(caseFoldFlag, bytes, s + i, end);
                clen = setupBMSkipMapCheck(bytes, s + i, end, items, buf);
                if (clen == 0) return true;

                for (int j = 0; j < clen; j++) {
                    intMap[bytes[s + i + j] & 0xff] = ilen - i - j;
                    for (int k = 0; k < items.length; k++) {
                        intMap[buf[k * Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM + j] & 0xff] = ilen - i - j;
                    }
                }
            }
        }
        return false;
    }

    private int setupBMSkipMapCheck(byte[]bytes, int p, int end, CaseFoldCodeItem[]items, byte[]buf) {
        int clen = enc.length(bytes, p, end);
        if (p + clen > end) clen = end - p;
        for (int j = 0; j < items.length; j++) {
            if (items[j].code.length != 1 || items[j].byteLen != clen) return 0;
            int flen = enc.codeToMbc(items[j].code[0], buf, j * Config.ENC_GET_CASE_FOLD_CODES_MAX_NUM);
            if (flen != clen) return 0;
        }
        return clen;
    }

    void setOptimizeExactInfo(OptExactInfo e) {
        if (e.length == 0) return;

        // shall we copy that ?
        exact = e.bytes;
        exactP = 0;
        exactEnd = e.length;
        boolean allowReverse = enc.isReverseMatchAllowed(exact, exactP, exactEnd);

        if (e.ignoreCase > 0) {
            if (e.length >= 3 || (e.length >= 2 && allowReverse)) {
                forward = enc.toLowerCaseTable() != null ? Search.SLOW_IC_SB_FORWARD : Search.SLOW_IC_FORWARD;
                if (!setupBMSkipMap(true)) {
                    forward = allowReverse ? (enc.toLowerCaseTable() != null ? Search.SLOW_IC_SB_FORWARD : Search.SLOW_IC_FORWARD) : Search.BM_NOT_REV_IC_FORWARD;
                    // FIXME: put above line in place to work around some failures.  Either BM_IC_FORWARD is broken here or we are choosing it when we shouldn't.
                    //forward = allowReverse ? Search.BM_IC_FORWARD : Search.BM_NOT_REV_IC_FORWARD;
                } else {
                    forward = enc.toLowerCaseTable() != null ? Search.SLOW_IC_SB_FORWARD : Search.SLOW_IC_FORWARD;
                }
            } else {
                forward = enc.toLowerCaseTable() != null ? Search.SLOW_IC_SB_FORWARD : Search.SLOW_IC_FORWARD;
            }
            backward = enc.toLowerCaseTable() != null ? Search.SLOW_IC_SB_BACKWARD : Search.SLOW_IC_BACKWARD;
        } else {
            if (e.length >= 3 || (e.length >= 2 && allowReverse)) {
                if (!setupBMSkipMap(false)) {
                    forward = allowReverse ? Search.BM_FORWARD : Search.BM_NOT_REV_FORWARD;
                } else {
                    forward = enc.isSingleByte() ? Search.SLOW_SB_FORWARD : Search.SLOW_FORWARD;
                }
            } else {
                forward = enc.isSingleByte() ? Search.SLOW_SB_FORWARD : Search.SLOW_FORWARD;
            }
            backward = enc.isSingleByte() ? Search.SLOW_SB_BACKWARD : Search.SLOW_BACKWARD;
        }

        dMin = e.mmd.min;
        dMax = e.mmd.max;
        exactReachEnd = e.reachEnd;
        characterMapOptimization = false;

        if (dMin != MinMaxLen.INFINITE_DISTANCE) {
            thresholdLength = dMin + (exactEnd - exactP);
        }
    }

    void setOptimizeMapInfo(OptMapInfo m) {
        map = m.map;

        if (enc.isSingleByte()) {
            forward = Search.MAP_SB_FORWARD;
            backward = Search.MAP_SB_BACKWARD;
        } else {
            forward = Search.MAP_FORWARD;
            backward = Search.MAP_BACKWARD;
        }

        dMin = m.mmd.min;
        dMax = m.mmd.max;
        exactReachEnd = false;
        characterMapOptimization = true;

        if (dMin != MinMaxLen.INFINITE_DISTANCE) {
            thresholdLength = dMin + 1;
        }
    }

    void setSubAnchor(OptAnchorInfo anc) {
        subAnchor |= anc.leftAnchor & AnchorType.BEGIN_LINE;
        subAnchor |= anc.rightAnchor & AnchorType.END_LINE;
    }

    void clearOptimizeInfo() {
        forward = null;
        backward = null;
        anchor = 0;
        anchorDmax = 0;
        anchorDmin = 0;
        subAnchor = 0;

        exact = null;
        exactP = exactEnd = 0;
        minimumLength = 0;
        exactReachEnd = false;
        characterMapOptimization = false;
    }

    public String optimizeInfoToString() {
        String s = "";
        s += "optimize: " + (forward != null ? forward.getName() : "NONE") + "\n";
        s += "  anchor:     " + OptAnchorInfo.anchorToString(anchor);

        if ((anchor & AnchorType.END_BUF_MASK) != 0) {
            s += MinMaxLen.distanceRangeToString(anchorDmin, anchorDmax);
        }

        s += "\n";

        if (forward != null) {
            s += "  sub anchor: " + OptAnchorInfo.anchorToString(subAnchor) + "\n";
        }

        s += "dmin: " + dMin + " dmax: " + dMax + "\n";
        s += "threshold length: " + thresholdLength + "\n";

        if (exact != null) {
            s += "exact: [" + new String(exact, exactP, exactEnd - exactP) + "]: length: " + (exactEnd - exactP) + "\n";
        } else if (forward == Search.MAP_FORWARD || forward == Search.MAP_SB_FORWARD) {
            int n=0;
            for (int i=0; i<Config.CHAR_TABLE_SIZE; i++) if (map[i] != 0) n++;

            s += "map: n = " + n + "\n";
            if (n > 0) {
                int c=0;
                s += "[";
                for (int i=0; i<Config.CHAR_TABLE_SIZE; i++) {
                    if (map[i] != 0) {
                        if (c > 0) s += ", ";
                        c++;
                        if (enc.maxLength() == 1 && enc.isPrint(i)) s += ((char)i);
                        else s += i;
                    }
                }
                s += "]\n";
            }
        }

        return s;
    }

    public Encoding getEncoding() {
        return enc;
    }

    public int getOptions() {
        return options;
    }

    /** Returns the optimizer anchor flags selected for this compiled regex. */
    public int getAnchor() {
        return anchor;
    }

    /** Immutable view of optimization facts computed for this compiled regex. */
    public static final class OptimizationInfo {
        private final int minimumLength;
        private final String exact;
        private final int minimumOffset;
        private final Integer maximumOffset;
        private final boolean exactReachEnd;
        private final int anchor;
        private final int subAnchor;
        private final String searchAlgorithm;
        private final boolean characterMap;
        private final boolean captures;

        private OptimizationInfo(int minimumLength, String exact,
                int minimumOffset, Integer maximumOffset, boolean exactReachEnd,
                int anchor, int subAnchor, String searchAlgorithm,
                boolean characterMap, boolean captures) {
            this.minimumLength = minimumLength;
            this.exact = exact;
            this.minimumOffset = minimumOffset;
            this.maximumOffset = maximumOffset;
            this.exactReachEnd = exactReachEnd;
            this.anchor = anchor;
            this.subAnchor = subAnchor;
            this.searchAlgorithm = searchAlgorithm;
            this.characterMap = characterMap;
            this.captures = captures;
        }

        public int minimumLength() { return minimumLength; }
        public String exact() { return exact; }
        public int minimumOffset() { return minimumOffset; }
        public Integer maximumOffset() { return maximumOffset; }
        public boolean exactReachEnd() { return exactReachEnd; }
        public int anchor() { return anchor; }
        public int subAnchor() { return subAnchor; }
        public String searchAlgorithm() { return searchAlgorithm; }
        public boolean characterMap() { return characterMap; }
        public boolean hasCaptures() { return captures; }
        public boolean beginBufferAnchored() {
            return (anchor & AnchorType.BEGIN_BUF) != 0;
        }
        public boolean beginPositionAnchored() {
            return (anchor & AnchorType.BEGIN_POSITION) != 0;
        }
        public boolean implicitSingleLineAnchor() {
            return (anchor & AnchorType.ANYCHAR_STAR) != 0;
        }
        public boolean implicitMultiLineAnchor() {
            return (anchor & AnchorType.ANYCHAR_STAR_ML) != 0;
        }
    }

    /** Returns the optimizer's actual selected search and length metadata. */
    public OptimizationInfo getOptimizationInfo() {
        String exactString = null;
        if (exact != null) {
            Charset charset = enc.isSingleByte()
                    ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
            exactString = new String(exact, exactP, exactEnd - exactP, charset);
        }
        Integer maximumOffset = dMax == MinMaxLen.INFINITE_DISTANCE ? null : dMax;
        return new OptimizationInfo(minimumLength, exactString, dMin,
                maximumOffset, exactReachEnd, anchor, subAnchor,
                forward == null ? "NONE" : forward.getName(),
                characterMapOptimization, numMem > 0);
    }

    /** Stable textual view of the actual compiled native instruction stream. */
    public String byteCodeDebugDescription() {
        return new ByteCodePrinter(this).byteCodeListToString();
    }

    /** Whether a named backreference was resolved only after the first parse pass. */
    public boolean hasForwardNamedBackreference() {
        return hasForwardNamedBackreference;
    }

    public enum DebugProgramKind {
        EXACT,
        FULL_CLASS,
        EMPTY_CLASS,
        ALL_EXCEPT_NEWLINE_CLASS,
        OTHER
    }

    /** Inclusive effective class-membership range in [0, Long.MAX_VALUE]. */
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

    /**
     * Canonical sorted/coalesced effective membership and conservative debug
     * provenance. {@code storageNegated} is the compiled representation's NOT
     * flag, not necessarily source spelling. {@code caseFolded} records that
     * folding contributed to the retained class. {@code provenanceAuthoritative}
     * says those source facts survived compilation; {@code optimizationSafe}
     * excludes property, POSIX, and character-type dependencies from compact
     * literal-family rendering.
     */
    public record DebugCharacterClassFact(boolean storageNegated,
            boolean caseFolded, boolean provenanceAuthoritative,
            boolean optimizationSafe,
            List<DebugRange> ranges,
            CClassNode.DebugClassExpression expression) {
        public DebugCharacterClassFact {
            ranges = List.copyOf(ranges);
        }

        public DebugCharacterClassFact(boolean storageNegated,
                List<DebugRange> ranges) {
            this(storageNegated, false, false, false, ranges, null);
        }

        public DebugCharacterClassFact(boolean storageNegated,
                boolean caseFolded, boolean provenanceAuthoritative,
                boolean optimizationSafe, List<DebugRange> ranges) {
            this(storageNegated, caseFolded, provenanceAuthoritative,
                    optimizationSafe, ranges, null);
        }
    }

    /** Immutable payload and compile-time mode for one logical exact node. */
    public record DebugExactFact(List<Integer> bytes, List<Long> codePoints,
            boolean ignoreCaseOpcode, boolean singleByteFoldOpcode,
            boolean multiCharacterFoldExpansion, int byteWidth,
            int lexicalOption) {
        public DebugExactFact {
            bytes = List.copyOf(bytes);
            codePoints = List.copyOf(codePoints);
            if (byteWidth < 0) {
                throw new IllegalArgumentException("negative byte width");
            }
        }
    }

    public record DebugProgramFact(DebugProgramKind kind,
            DebugCharacterClassFact characterClass, DebugExactFact exact) {
        public DebugProgramFact {
            Objects.requireNonNull(kind, "kind");
            if (kind == DebugProgramKind.EXACT) {
                if (exact == null || characterClass != null) {
                    throw new IllegalArgumentException(
                            "exact fact requires only an exact payload");
                }
            } else if (exact != null) {
                throw new IllegalArgumentException(
                        "non-exact fact cannot carry an exact payload");
            } else if (kind != DebugProgramKind.OTHER
                    && characterClass == null) {
                throw new IllegalArgumentException(
                        "semantic class fact requires membership");
            }
        }

        public DebugProgramFact(DebugProgramKind kind,
                DebugCharacterClassFact characterClass) {
            this(kind, characterClass, null);
        }

        public DebugProgramFact(DebugProgramKind kind) {
            this(kind, null, null);
        }

        static DebugProgramFact other() {
            return new DebugProgramFact(DebugProgramKind.OTHER);
        }
    }

    /** Immutable callback-free provenance for one deferred property term. */
    public record DebugDeferredPropertyFact(String rawName, String displayName,
            CharacterPropertyResolver.Context context, int option,
            int position, boolean tokenNegated) {
        public DebugDeferredPropertyFact {
            Objects.requireNonNull(rawName, "rawName");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(context, "context");
        }
    }

    /**
     * Static membership and ordered unresolved terms for a directly compiled
     * deferred character class. The fact is presentation-only and never
     * resolves a property callback.
     */
    public record DebugDeferredCharacterClassFact(
            DebugCharacterClassFact staticMembership,
            List<DebugDeferredPropertyFact> terms,
            boolean presentationSafe, boolean staticHighUnbounded) {
        public DebugDeferredCharacterClassFact {
            Objects.requireNonNull(staticMembership, "staticMembership");
            terms = List.copyOf(terms);
            if (terms.isEmpty()) {
                throw new IllegalArgumentException(
                        "deferred class fact requires at least one term");
            }
        }
    }

    /**
     * Returns a semantic shape and optional immutable membership when the
     * program begins with a class instruction, optionally after one canonical
     * dynamic-option prologue. Unsupported programs return OTHER with no
     * membership; ordinary classes return OTHER with membership.
     */
    public DebugProgramFact firstDebugProgramFact() {
        return RegexDebugProgram.firstFact(this, false);
    }

    /** Like {@link #firstDebugProgramFact()}, including compiled exact nodes. */
    public DebugProgramFact firstCompiledProgramFact() {
        return RegexDebugProgram.firstFact(this, true);
    }

    /**
     * Returns immutable facts for a leading matcher-deferred character class.
     * Empty means the first compiled instruction is not such a class.
     */
    public Optional<DebugDeferredCharacterClassFact>
            firstDeferredCharacterClassFact() {
        return RegexDebugProgram.firstDeferredFact(this);
    }

    /**
     * Presentation-only Perl compatibility name for a proven first compiled
     * class shape. Empty means the caller must use the native debug fallback;
     * these labels do not imply that Joni executes Perl opcodes internally.
     */
    public String perlFirstProgramDebugDescription() {
        return perlFirstProgramDebugDescription(false);
    }

    /** Presentation view optionally including compiled exact-node facts. */
    public String perlFirstProgramDebugDescription(boolean includeExact) {
        DebugProgramFact fact = includeExact
                ? firstCompiledProgramFact() : firstDebugProgramFact();
        return switch (fact.kind()) {
            case EXACT -> renderExact(fact.exact());
            case FULL_CLASS -> "SANY";
            case EMPTY_CLASS -> "OPFAIL";
            case ALL_EXCEPT_NEWLINE_CLASS -> "REG_ANY";
            case OTHER -> {
                String deferred = firstDeferredCharacterClassFact()
                        .map(this::renderDeferredCharacterClass)
                        .orElse("");
                yield deferred.isEmpty()
                        ? renderProvenCharacterClass(fact.characterClass())
                        : deferred;
            }
        };
    }

    private String renderDeferredCharacterClass(
            DebugDeferredCharacterClassFact fact) {
        DebugCharacterClassFact staticMembership = fact.staticMembership();
        if (enc != UTF8Encoding.INSTANCE || !fact.presentationSafe()
                || fact.terms().isEmpty()
                || staticMembership.caseFolded()) {
            return "";
        }
        for (DebugDeferredPropertyFact term : fact.terms()) {
            if (term.displayName().isEmpty()) return "";
        }

        List<DebugRange> staticRanges = staticMembership.ranges();
        if (staticMembership.storageNegated()) {
            List<DebugRange> rawRanges = complementDebugRanges(staticRanges);
            if (fact.staticHighUnbounded()) {
                rawRanges = extendDeferredHighToInfinity(rawRanges);
            }
            StringBuilder rendered = new StringBuilder("ANYOF[^");
            appendDeferredLowRanges(rendered, rawRanges);
            for (DebugDeferredPropertyFact term : fact.terms()) {
                rendered.append('{')
                        .append(term.tokenNegated() ? '!' : '+')
                        .append(term.displayName()).append('}');
            }
            appendDeferredHighRanges(rendered, rawRanges);
            return rendered.append(']').toString();
        }

        if (fact.staticHighUnbounded()) {
            staticRanges = extendDeferredHighToInfinity(staticRanges);
        }

        StringBuilder rendered = new StringBuilder("ANYOF");
        String low = deferredLowRanges(staticRanges);
        if (!low.isEmpty()) rendered.append('[').append(low).append(']');
        rendered.append('[');
        for (int index = 0; index < fact.terms().size(); index++) {
            if (index != 0) rendered.append(' ');
            DebugDeferredPropertyFact term = fact.terms().get(index);
            rendered.append(term.tokenNegated() ? '!' : '+')
                    .append(term.displayName());
        }
        rendered.append(']');
        String high = deferredHighRanges(staticRanges);
        if (!high.isEmpty()) rendered.append('[').append(high).append(']');
        return rendered.toString();
    }

    private static List<DebugRange> complementDebugRanges(
            List<DebugRange> ranges) {
        List<DebugRange> result = new ArrayList<>();
        long next = 0;
        for (DebugRange range : ranges) {
            if (next < range.from()) {
                result.add(new DebugRange(next, range.from() - 1));
            }
            if (range.to() == Long.MAX_VALUE) {
                next = Long.MAX_VALUE;
                return List.copyOf(result);
            }
            next = range.to() + 1;
        }
        result.add(new DebugRange(next, Long.MAX_VALUE));
        return List.copyOf(result);
    }

    private static List<DebugRange> extendDeferredHighToInfinity(
            List<DebugRange> ranges) {
        if (ranges.isEmpty()) return ranges;
        DebugRange last = ranges.get(ranges.size() - 1);
        if (last.to() != 0x10ffff) return ranges;
        List<DebugRange> extended = new ArrayList<>(ranges);
        extended.set(extended.size() - 1,
                new DebugRange(last.from(), Long.MAX_VALUE));
        return List.copyOf(extended);
    }

    private static void appendDeferredLowRanges(StringBuilder output,
            List<DebugRange> ranges) {
        output.append(deferredLowRanges(ranges));
    }

    private static void appendDeferredHighRanges(StringBuilder output,
            List<DebugRange> ranges) {
        output.append(deferredHighRanges(ranges));
    }

    private static String deferredLowRanges(List<DebugRange> ranges) {
        StringBuilder output = new StringBuilder();
        for (DebugRange range : ranges) {
            long from = range.from();
            long to = Math.min(range.to(), 0xff);
            if (from > 0xff || from > to) continue;
            appendDeferredRange(output, from, to, true);
        }
        return output.toString();
    }

    private static String deferredHighRanges(List<DebugRange> ranges) {
        StringBuilder output = new StringBuilder();
        for (DebugRange range : ranges) {
            long from = Math.max(range.from(), 0x100);
            long to = range.to();
            if (from > to) continue;
            if (output.length() != 0) output.append(' ');
            appendDeferredRange(output, from, to, false);
        }
        return output.toString();
    }

    private static void appendDeferredRange(StringBuilder output, long from,
            long to, boolean lowByte) {
        output.append(lowByte ? deferredByte(from) : debugHex(from));
        if (from == to) return;
        output.append('-');
        if (!lowByte && to == Long.MAX_VALUE) {
            output.append("INFTY");
        } else {
            output.append(lowByte ? deferredByte(to) : debugHex(to));
        }
    }

    private static String deferredByte(long value) {
        return switch ((int)value) {
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\f' -> "\\f";
            case '\\', '[', ']', '{', '}', '-', '^' -> "\\" + (char)value;
            default -> value >= 0x20 && value <= 0x7e
                    ? Character.toString((char)value)
                    : String.format(java.util.Locale.ROOT, "\\x%02X", value);
        };
    }

    private String renderProvenCharacterClass(
            DebugCharacterClassFact characterClass) {
        String posix = renderPosixCharacterClass(characterClass);
        return posix.isEmpty()
                ? renderFiniteHighCharacterClass(characterClass) : posix;
    }

    private String renderPosixCharacterClass(
            DebugCharacterClassFact characterClass) {
        if (characterClass == null || characterClass.expression() == null) {
            return "";
        }
        CClassNode.DebugClassExpression expression =
                characterClass.expression();
        if (!expression.authoritative() || expression.terms().isEmpty()) {
            return "";
        }

        List<CClassNode.DebugClassTerm> terms = expression.terms();
        CClassNode.DebugClassTerm digit = findTerm(terms,
                CharacterType.DIGIT, true);
        CClassNode.DebugClassTerm word = findTerm(terms,
                CharacterType.WORD, false);
        if (digit != null && word != null && terms.size() == 2
                && Option.isIgnoreCase(word.lexicalOption())) {
            if (!Option.isPerlLocale(word.lexicalOption())) return "SANY";
            return "ANYOFPOSIXL{i}[\\w\\D][0100-INFTY]";
        }

        if (expression.outerNegated() && terms.size() == 2
                && hasTerm(terms, CharacterType.PRINT, true)
                && hasTerm(terms, CharacterType.ASCII, true)) {
            return "POSIXA[:print:]";
        }

        CClassNode.DebugClassTerm term = dominantTerm(terms);
        if (term == null) return "";

        if (Option.isPerlLocale(term.lexicalOption())
                && term.ctype() == CharacterType.SPACE
                && term.spelling()
                        == CClassNode.DebugClassSpelling.ESCAPE
                && hasNonAsciiLiteral(expression)) {
            return renderLocalePosixComposite(characterClass, expression,
                    term);
        }

        if (!term.tokenNegated() && !expression.outerNegated()
                && hasNonRedundantLiteral(expression, term)) return "";

        boolean negated = term.tokenNegated() ^ expression.outerNegated();
        char domain = posixDomain(term, expression);
        int renderedCtype = term.ctype();
        if (Option.isIgnoreCase(term.lexicalOption())
                && (renderedCtype == CharacterType.LOWER
                        || renderedCtype == CharacterType.UPPER)) {
            renderedCtype = domain == 'A'
                    ? CharacterType.ALPHA : -1;
        }
        String name = renderedCtype == -1 ? ":cased:"
                : posixClassName(renderedCtype);
        return (negated ? "NPOSIX" : "POSIX") + domain + "[" + name + "]";
    }

    private String renderLocalePosixComposite(
            DebugCharacterClassFact characterClass,
            CClassNode.DebugClassExpression expression,
            CClassNode.DebugClassTerm term) {
        StringBuilder rendered = new StringBuilder("ANYOFPOSIXL");
        if (Option.isIgnoreCase(term.lexicalOption())) rendered.append("{i}");
        rendered.append('[');
        if (expression.outerNegated()) rendered.append('^');
        rendered.append(term.tokenNegated() ? "\\S" : "\\s").append("][");
        boolean first = true;
        for (DebugRange range : characterClass.ranges()) {
            long from = Math.max(0x100, range.from());
            long to = Math.min(0x10ffff, range.to());
            if (from > to) continue;
            if (!first) rendered.append(' ');
            first = false;
            rendered.append(debugHex(from));
            if (from != to) rendered.append('-').append(debugHex(to));
        }
        return first ? "" : rendered.append(']').toString();
    }

    private static CClassNode.DebugClassTerm dominantTerm(
            List<CClassNode.DebugClassTerm> terms) {
        CClassNode.DebugClassTerm first = terms.get(0);
        boolean identical = true;
        for (CClassNode.DebugClassTerm term : terms) {
            identical &= term.ctype() == first.ctype()
                    && term.tokenNegated() == first.tokenNegated()
                    && term.charsetOption() == first.charsetOption();
        }
        if (identical) return first;

        CClassNode.DebugClassTerm word = findTerm(terms,
                CharacterType.WORD, false);
        if (word != null) {
            for (CClassNode.DebugClassTerm term : terms) {
                if (term.tokenNegated()
                        || term.ctype() != CharacterType.WORD
                        && term.ctype() != CharacterType.DIGIT) return null;
            }
            return word;
        }
        return null;
    }

    private static CClassNode.DebugClassTerm findTerm(
            List<CClassNode.DebugClassTerm> terms, int ctype,
            boolean negated) {
        for (CClassNode.DebugClassTerm term : terms) {
            if (term.ctype() == ctype && term.tokenNegated() == negated) {
                return term;
            }
        }
        return null;
    }

    private static boolean hasTerm(List<CClassNode.DebugClassTerm> terms,
            int ctype, boolean negated) {
        return findTerm(terms, ctype, negated) != null;
    }

    private static boolean hasNonAsciiLiteral(
            CClassNode.DebugClassExpression expression) {
        for (long codePoint : expression.literalCodePoints()) {
            if (codePoint >= 0x100) return true;
        }
        return false;
    }

    private static boolean hasNonRedundantLiteral(
            CClassNode.DebugClassExpression expression,
            CClassNode.DebugClassTerm term) {
        if (expression.literalCodePoints().isEmpty()) return false;
        if (term.tokenNegated()) return false;
        if (term.ctype() == CharacterType.NEWLINE) return false;
        for (long codePoint : expression.literalCodePoints()) {
            if (codePoint >= 0x100) return true;
            if (term.ctype() == CharacterType.BLANK && codePoint == ' ') {
                continue;
            }
            return true;
        }
        return false;
    }

    private static char posixDomain(CClassNode.DebugClassTerm term,
            CClassNode.DebugClassExpression expression) {
        int option = term.lexicalOption();
        if (term.ctype() == CharacterType.NEWLINE) return 'U';
        if (Option.isPerlLocale(option)) return 'L';
        if (Option.isPerlExplicitAscii(option)) return 'A';
        if (Option.isPerlUnicodeCharset(option)) return 'U';
        if (term.ctype() == CharacterType.DIGIT
                || term.ctype() == CharacterType.XDIGIT
                || hasNonAsciiLiteral(expression)) return 'U';
        return 'D';
    }

    private static String posixClassName(int ctype) {
        return switch (ctype) {
            case CharacterType.NEWLINE -> "\\v";
            case CharacterType.ALPHA -> ":alpha:";
            case CharacterType.BLANK -> ":blank:";
            case CharacterType.CNTRL -> ":cntrl:";
            case CharacterType.DIGIT -> "\\d";
            case CharacterType.GRAPH -> ":graph:";
            case CharacterType.LOWER -> ":lower:";
            case CharacterType.PRINT -> ":print:";
            case CharacterType.PUNCT -> ":punct:";
            case CharacterType.SPACE -> "\\s";
            case CharacterType.UPPER -> ":upper:";
            case CharacterType.XDIGIT -> ":xdigit:";
            case CharacterType.WORD -> "\\w";
            case CharacterType.ALNUM -> ":alnum:";
            case CharacterType.ASCII -> ":ascii:";
            default -> throw new IllegalArgumentException("unknown ctype");
        };
    }

    private String renderExact(DebugExactFact exact) {
        if (exact == null || exact.codePoints().isEmpty()) return "";
        boolean requiresUtf8 = enc == UTF8Encoding.INSTANCE
                && exact.codePoints().stream().anyMatch(value -> value > 0x7f);
        int option = exact.lexicalOption();
        String name;
        if (exact.ignoreCaseOpcode()) {
            if (Option.isPerlLocale(option)) {
                name = requiresLocaleUtf8(exact.codePoints())
                        ? "EXACTFLU8" : "EXACTFL";
            } else if (Option.isPerlAsciiStrict(option)) {
                name = "EXACTFAA";
            } else if (containsSharpSFoldSequence(exact.codePoints())
                    && !exact.multiCharacterFoldExpansion()) {
                name = !Option.isPerlExplicitAscii(option)
                        && !Option.isPerlUnicodeCharset(option)
                        ? "EXACTF" : "EXACTFUP";
            } else {
                name = requiresUtf8 ? "EXACTFU_REQ8" : "EXACTFU";
            }
        } else if (Option.isPerlLocale(option)) {
            name = "EXACTL";
        } else {
            name = requiresUtf8 ? "EXACT_REQ8" : "EXACT";
        }
        StringBuilder rendered = new StringBuilder(name).append(" <");
        for (long codePoint : exact.codePoints()) {
            if (codePoint >= 0x20 && codePoint <= 0x7e
                    && codePoint != '\\' && codePoint != '<'
                    && codePoint != '>') {
                rendered.append((char)codePoint);
            } else {
                rendered.append("\\x{")
                        .append(Long.toHexString(codePoint)).append('}');
            }
        }
        return rendered.append('>').toString();
    }

    private static boolean containsSharpSFoldSequence(List<Long> codePoints) {
        for (int index = 1; index < codePoints.size(); index++) {
            if (codePoints.get(index - 1) == (long)'s'
                    && codePoints.get(index) == (long)'s') return true;
        }
        return false;
    }

    private static boolean requiresLocaleUtf8(List<Long> codePoints) {
        for (long codePoint : codePoints) {
            if (codePoint <= 0x7f) continue;
            int foldLength = codePoint <= Integer.MAX_VALUE
                    ? PerlCaseFold.simpleFoldClassLength((int)codePoint) : 0;
            boolean hasAsciiFold = false;
            for (int index = 0; index < foldLength; index++) {
                if (PerlCaseFold.simpleFoldClassCodePoint(
                        (int)codePoint, index) <= 0x7f) {
                    hasAsciiFold = true;
                    break;
                }
            }
            if (!hasAsciiFold) return true;
        }
        return false;
    }

    private String renderFiniteHighCharacterClass(
            DebugCharacterClassFact characterClass) {
        if (enc != UTF8Encoding.INSTANCE || characterClass == null
                || !characterClass.provenanceAuthoritative()
                || !characterClass.optimizationSafe()
                || characterClass.caseFolded()
                || characterClass.storageNegated()
                || characterClass.ranges().isEmpty()
                || characterClass.ranges().get(0).from() < 0x100) {
            return "";
        }
        List<DebugRange> ranges = characterClass.ranges();
        DebugRange last = ranges.get(ranges.size() - 1);
        if (isCompleteSimpleFoldClass(ranges)) return "";
        if (ranges.size() == 1 && last.from() == Long.MAX_VALUE
                && last.domainEnd() == WideScalarDomainEnd.HIGHEST_SCALAR) {
            return "";
        }

        if (last.to() == Long.MAX_VALUE) {
            StringBuilder rendered = new StringBuilder("ANYOFH[");
            for (int index = 0; index < ranges.size(); index++) {
                if (index != 0) rendered.append(' ');
                DebugRange range = ranges.get(index);
                if (range.from() == Long.MAX_VALUE) {
                    rendered.append("HIGHEST_CP");
                    if (range.domainEnd()
                            == WideScalarDomainEnd.PERL_INFINITY) {
                        rendered.append("-INFTY");
                    }
                } else {
                    rendered.append(debugHex(range.from()));
                    if (range.from() != range.to()) {
                        rendered.append('-');
                        if (range.to() == Long.MAX_VALUE) {
                            rendered.append(range.domainEnd()
                                    == WideScalarDomainEnd.PERL_INFINITY
                                    ? "INFTY" : "HIGHEST_CP");
                        } else {
                            rendered.append(debugHex(range.to()));
                        }
                    }
                }
            }
            return rendered.append(']').toString();
        }

        if (ranges.size() == 1 && ranges.get(0).from() < ranges.get(0).to()) {
            DebugRange range = ranges.get(0);
            if (range.from() >= 0x100000 || range.to() > 0x10ffff
                    || range.to() - range.from() >= 0x1000) return "";
            String suffix = utf8FirstByte(range.from())
                    == utf8FirstByte(range.to()) ? "b" : "";
            return "ANYOFR" + suffix + "[" + debugHex(range.from()) + "-"
                    + debugHex(range.to()) + "]";
        }
        if (ranges.size() == 1) return "";
        long first = ranges.get(0).from();
        if (first < 0x100 || last.to() > 0x7ff
                || utf8FirstByte(first) != utf8FirstByte(last.to())) return "";

        StringBuilder rendered = new StringBuilder("ANYOFHbbm[");
        for (int index = 0; index < ranges.size(); index++) {
            if (index != 0) rendered.append(' ');
            DebugRange range = ranges.get(index);
            rendered.append(debugHex(range.from()));
            if (range.from() != range.to()) {
                rendered.append('-').append(debugHex(range.to()));
            }
        }
        return rendered.append(']').toString();
    }

    private static boolean isCompleteSimpleFoldClass(List<DebugRange> ranges) {
        long memberCount = 0;
        for (DebugRange range : ranges) {
            memberCount += range.to() - range.from() + 1;
            if (memberCount > 16) return false;
        }
        int first = (int)ranges.get(0).from();
        int foldLength = PerlCaseFold.simpleFoldClassLength(first);
        if (foldLength == 0 || foldLength != memberCount) return false;
        for (int index = 0; index < foldLength; index++) {
            long member = PerlCaseFold.simpleFoldClassCodePoint(first, index);
            boolean present = false;
            for (DebugRange range : ranges) {
                if (member >= range.from() && member <= range.to()) {
                    present = true;
                    break;
                }
            }
            if (!present) return false;
        }
        return true;
    }

    private static int utf8FirstByte(long codePoint) {
        if (codePoint <= 0x7f) return (int)codePoint;
        if (codePoint <= 0x7ff) return 0xc0 | (int)(codePoint >>> 6);
        if (codePoint <= 0xffff) return 0xe0 | (int)(codePoint >>> 12);
        return 0xf0 | (int)(codePoint >>> 18);
    }

    private static String debugHex(long value) {
        String hex = Long.toHexString(value).toUpperCase(java.util.Locale.ROOT);
        return "0".repeat(Math.max(0, 4 - hex.length())) + hex;
    }

    public void setUserOptions(int options) {
        this.userOptions = options;
    }

    public int getUserOptions() {
        return userOptions;
    }

    public void setUserObject(Object object) {
        this.userObject = object;
    }

    public Object getUserObject() {
        return userObject;
    }

    public boolean isLinear() {
        return !requireStack;
    }
}
