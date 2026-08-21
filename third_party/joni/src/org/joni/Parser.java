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

import static org.joni.BitStatus.bsOnAtSimple;
import static org.joni.BitStatus.bsOnOff;
import static org.joni.Option.isAsciiRange;
import static org.joni.Option.isDontCaptureGroup;
import static org.joni.Option.isIgnoreCase;
import static org.joni.Option.isPerlReStrict;
import static org.joni.Option.isPosixBracketAllRange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jcodings.ApplyAllCaseFoldFunction;
import org.jcodings.Encoding;
import org.jcodings.ObjPtr;
import org.jcodings.Ptr;
import org.jcodings.constants.CharacterType;
import org.jcodings.constants.PosixBracket;
import org.jcodings.exception.InternalException;
import org.jcodings.unicode.UnicodeCodeRange;
import org.joni.ast.AnchorNode;
import org.joni.ast.AnyCharNode;
import org.joni.ast.BackRefNode;
import org.joni.ast.CClassNode;
import org.joni.ast.CClassNode.CCSTATE;
import org.joni.ast.CClassNode.CCStateArg;
import org.joni.ast.CClassNode.CCVALTYPE;
import org.joni.ast.CTypeNode;
import org.joni.ast.CallNode;
import org.joni.ast.CalloutNode;
import org.joni.ast.ControlVerbNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.ListNode;
import org.joni.ast.Node;
import org.joni.ast.QuantifierNode;
import org.joni.ast.StringNode;
import org.joni.ast.WideScalarNode;
import org.joni.constants.internal.AnchorType;
import org.joni.constants.internal.EncloseType;
import org.joni.constants.internal.NodeType;
import org.joni.constants.internal.TokenType;
import org.joni.exception.ErrorMessages;
import org.joni.exception.SyntaxException;

class Parser extends Lexer {
    protected int returnCode; // return code used by parser methods (they itself return parsed nodes)
                              // this approach will not affect recursive calls
    private EncloseNode[] lexicalMemNodes;
    private boolean perlExtendedClassLeaf;

    private static final class PerlAsciiStrictClassMultiFold
            implements ApplyAllCaseFoldFunction {
        private final Encoding enc;
        private final CClassNode cc;
        private final CClassNode foldCc;
        private final Map<Integer, Set<Integer>> nonAsciiSiblings =
                new LinkedHashMap<>();
        private final List<int[]> crossingFolds = new ArrayList<>();

        PerlAsciiStrictClassMultiFold(Encoding enc, CClassNode cc,
                                      CClassNode foldCc) {
            this.enc = enc;
            this.cc = cc;
            this.foldCc = foldCc;
        }

        @Override
        public void apply(int from, int[] to, int length, Object unused) {
            if (length == 1) {
                collectNonAsciiSibling(from, to[0]);
                return;
            }

            if (cc.isNot() || Encoding.isAscii(from)
                    || !ApplyCaseFold.isEligible(foldCc, enc, from)
                    || !cc.isCodeInCC(enc, from)) {
                return;
            }

            for (int i = 0; i < length; i++) {
                if (!Encoding.isAscii(to[i])) return;
            }
            crossingFolds.add(to.clone());
        }

        private void collectNonAsciiSibling(int from, int to) {
            final int ascii;
            final int nonAscii;
            if (Encoding.isAscii(from) && !Encoding.isAscii(to)) {
                ascii = from;
                nonAscii = to;
            } else if (!Encoding.isAscii(from) && Encoding.isAscii(to)) {
                ascii = to;
                nonAscii = from;
            } else {
                return;
            }
            nonAsciiSiblings.computeIfAbsent(ascii,
                    ignored -> new LinkedHashSet<>()).add(nonAscii);
        }

        ListNode alternatives() {
            ListNode root = null;
            ListNode tail = null;
            Set<String> seen = new LinkedHashSet<>();
            for (int[] crossingFold : crossingFolds) {
                int[] replacement = new int[crossingFold.length];
                List<StringNode> generated = new ArrayList<>();
                generate(crossingFold, replacement, 0, seen, generated);
                for (StringNode node : generated) {
                    ListNode alternative = ListNode.newAlt(node, null);
                    if (root == null) root = alternative;
                    else tail.setTail(alternative);
                    tail = alternative;
                }
            }
            return root;
        }

        private void generate(int[] folded, int[] replacement, int index,
                              Set<String> seen, List<StringNode> generated) {
            if (index == folded.length) {
                StringBuilder key = new StringBuilder();
                StringNode node = new StringNode();
                for (int code : replacement) {
                    key.append(code).append(',');
                    node.catCode(code, enc);
                }
                if (seen.add(key.toString())) {
                    node.setRaw();
                    generated.add(node);
                }
                return;
            }

            Set<Integer> siblings = nonAsciiSiblings.get(folded[index]);
            if (siblings == null) return;
            for (int sibling : siblings) {
                replacement[index] = sibling;
                generate(folded, replacement, index + 1, seen, generated);
            }
        }
    }

    protected Parser(Regex regex, Syntax syntax, byte[]bytes, int p, int end, WarnCallback warnings) {
        super(regex, syntax, bytes, p, end, warnings);
        emitPerlPosixDiagnostics();
    }

    private static final Set<String> PERL_POSIX_NAMES = Set.of(
            "alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph",
            "lower", "print", "punct", "space", "upper", "word", "xdigit");

    private boolean asciiAt(int position, int value) {
        return position >= getBegin() && position < getEnd()
                && (bytes[position] & 0xff) == value;
    }

    private boolean asciiOnly(int start, int end) {
        for (int cursor = start; cursor < end; cursor++) {
            if ((bytes[cursor] & 0x80) != 0) return false;
        }
        return true;
    }

    private String asciiText(int start, int end) {
        return new String(bytes, start, end - start, StandardCharsets.US_ASCII);
    }

    private boolean knownPosixName(String name) {
        if (name.startsWith("^")) name = name.substring(1);
        return PERL_POSIX_NAMES.contains(name);
    }

    private boolean plausiblePosixName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (knownPosixName(lower)) return true;
        if (lower.length() < POSIX_BRACKET_NAME_MIN_LEN) return false;
        for (String known : PERL_POSIX_NAMES) {
            if (known.length() != lower.length() + 1) continue;
            for (int omitted = 0; omitted < known.length(); omitted++) {
                if ((known.substring(0, omitted) + known.substring(omitted + 1))
                        .equals(lower)) return true;
            }
        }
        return false;
    }

    private boolean insidePerlCharacterClass(int position) {
        boolean inClass = false;
        for (int cursor = getBegin(); cursor < position; cursor++) {
            if (asciiAt(cursor, '\\')) {
                cursor++;
                continue;
            }
            if (!inClass) {
                if (asciiAt(cursor, '[')) inClass = true;
                continue;
            }
            if (asciiAt(cursor, '[') && cursor + 2 < position
                    && (asciiAt(cursor + 1, ':') || asciiAt(cursor + 1, '.')
                            || asciiAt(cursor + 1, '='))) {
                int delimiter = bytes[cursor + 1] & 0xff;
                int nested = cursor + 2;
                while (nested + 1 < position
                        && !(asciiAt(nested, delimiter)
                                && asciiAt(nested + 1, ']'))) nested++;
                if (nested + 1 < position) {
                    cursor = nested + 1;
                    continue;
                }
            }
            if (asciiAt(cursor, ']')) inClass = false;
        }
        return inClass;
    }

    private void posixWarn(String reason, int position) {
        syntaxWarn("Assuming NOT a POSIX class since " + reason,
                position - getBegin());
    }

    /**
     * Perl diagnoses near-miss POSIX spellings before ordinary character-class
     * parsing. Keep that lexical policy in Joni so consumers receive ordered,
     * byte-positioned metadata without inspecting regex source themselves.
     */
    private void emitPerlPosixDiagnostics() {
        if (!env.usesPerlDiagnostics() || env.warnings == WarnCallback.NONE) return;

        int begin = getBegin();
        int end = getEnd();
        for (int i = begin; i + 2 < end; i++) {
            if (asciiAt(i, '\\')) {
                if (++i < end && (bytes[i] & 0x80) != 0) {
                    i += enc.length(bytes, i, end) - 1;
                }
                continue;
            }
            if (!asciiAt(i, '[')) continue;

            // A single bracket around POSIX-looking text is an ordinary class,
            // but Perl warns that the construct belongs one bracket deeper.
            int delimiter = bytes[i + 1] & 0xff;
            if ((delimiter == ':' || delimiter == '.' || delimiter == '=')
                    && !insidePerlCharacterClass(i)) {
                int cursor = i + 2;
                int close = -1;
                int delimiterEnd = -1;
                while (cursor < end && !asciiAt(cursor, ']')) {
                    if (asciiAt(cursor, delimiter)) delimiterEnd = cursor;
                    cursor++;
                }
                if (cursor < end) close = cursor;
                if (delimiter != ':') {
                    if (delimiterEnd >= i + 2 && delimiterEnd + 1 == close) {
                        char delimiterChar = (char)delimiter;
                        syntaxWarn("POSIX syntax [" + delimiterChar + " "
                                + delimiterChar + "] belongs inside character classes "
                                + "(but this one isn't implemented)", close + 1 - begin);
                    }
                } else if (close >= 0) {
                    boolean terminated = delimiterEnd >= i + 2
                            && delimiterEnd + 1 == close;
                    String name = terminated && asciiOnly(i + 2, delimiterEnd)
                            ? asciiText(i + 2, delimiterEnd) : "";
                    if (!terminated && (!asciiOnly(i + 2, close)
                            || !plausiblePosixName(asciiText(i + 2, close)))) {
                        continue;
                    }
                    boolean valid = terminated && knownPosixName(name.toLowerCase(
                            java.util.Locale.ROOT));
                    String suffix = valid ? ""
                            : " (but this one isn't fully valid)";
                    syntaxWarn("POSIX syntax [: :] belongs inside character classes"
                            + suffix, terminated ? close + 1 - begin : close - begin);
                }
            }
        }

        for (int i = begin; i + 3 < end; i++) {
            if (!asciiAt(i, '[') || !asciiAt(i + 1, '[')) continue;

            if (asciiAt(i + 2, ':')) {
                int nameStart = i + 3;
                int cursor = nameStart;
                while (cursor < end && !asciiAt(cursor, ':')
                        && !asciiAt(cursor, ']')) cursor++;
                if (cursor >= end || !asciiOnly(nameStart, cursor)) continue;
                String name = asciiText(nameStart, cursor);
                if (asciiAt(cursor, ':')) {
                    if (!asciiAt(cursor + 1, ']')
                            && (plausiblePosixName(name) || name.indexOf('#') >= 0)) {
                        posixWarn("there is no terminating ']'", cursor + 1);
                    }
                } else if (plausiblePosixName(name)) {
                    if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                        posixWarn("the name must be all lowercase letters", cursor);
                    }
                    posixWarn("there is no terminating ':'", cursor);
                }
                continue;
            }

            if (asciiAt(i + 2, ' ')) {
                emitSpacedPosixDiagnostics(i, end);
                continue;
            }

            int cursor = i + 2;
            boolean caret = asciiAt(cursor, '^');
            if (caret) cursor++;
            int nameStart = cursor;
            while (cursor < end && !asciiAt(cursor, ']')) cursor++;
            if (cursor >= end || !asciiOnly(nameStart, cursor)) continue;
            String name = asciiText(nameStart, cursor);
            if (!plausiblePosixName(name)) continue;
            if (caret) {
                posixWarn("the '^' must come after the colon", nameStart);
            }
            posixWarn("there must be a starting ':'", nameStart);
            posixWarn("there is no terminating ':'", cursor);
        }

        for (int i = begin; i + 3 < end; i++) {
            if (!asciiAt(i, ':') || asciiAt(i - 1, '[')) continue;
            int nameEnd = i + 1;
            while (nameEnd < end && !asciiAt(nameEnd, ':')
                    && !asciiAt(nameEnd, ']')) nameEnd++;
            if (!asciiAt(nameEnd, ':') || !asciiAt(nameEnd + 1, ']')
                    || !asciiOnly(i + 1, nameEnd)) continue;
            if (knownPosixName(asciiText(i + 1, nameEnd))) {
                posixWarn("it doesn't start with a '['", i);
            }
        }

        for (int i = begin; i + 3 < end; i++) {
            if (!asciiAt(i, ';')) continue;
            int nameEnd = i + 1;
            while (nameEnd < end && !asciiAt(nameEnd, ';')
                    && !asciiAt(nameEnd, ']')) nameEnd++;
            if (!asciiAt(nameEnd, ';') || !asciiAt(nameEnd + 1, ']')
                    || !asciiOnly(i + 1, nameEnd)
                    || !knownPosixName(asciiText(i + 1, nameEnd))) continue;
            if (!asciiAt(i - 1, '[')) {
                posixWarn("it doesn't start with a '['", i);
            }
            posixWarn("a semi-colon was found instead of a colon", i + 1);
            posixWarn("a semi-colon was found instead of a colon", nameEnd + 2);
            i = nameEnd;
        }
    }

    private void emitSpacedPosixDiagnostics(int open, int end) {
        int caret = open + 2;
        while (caret < end && asciiAt(caret, ' ')) caret++;
        if (!asciiAt(caret, '^')) return;
        int colon = caret + 1;
        while (colon < end && asciiAt(colon, ' ')) colon++;
        if (!asciiAt(colon, ':')) return;
        int close = colon + 1;
        while (close < end && !asciiAt(close, ']')) close++;
        if (close >= end) return;
        int outerClose = close + 1;
        while (outerClose < end && asciiAt(outerClose, ' ')) outerClose++;
        if (!asciiAt(outerClose, ']')) return;

        posixWarn("no blanks are allowed in one", caret);
        posixWarn("the '^' must come after the colon", caret + 1);
        if (asciiAt(caret + 1, ' ')) {
            posixWarn("no blanks are allowed in one", colon);
        }
        if (asciiAt(colon + 1, ' ')) {
            int nameStart = colon + 1;
            while (nameStart < close && asciiAt(nameStart, ' ')) nameStart++;
            posixWarn("no blanks are allowed in one", nameStart);
        }
        if (asciiAt(close + 1, ' ')) {
            posixWarn("no blanks are allowed in one", close + 1);
        }
        if (isPerlReStrict(env.option)) {
            syntaxWarn("Unescaped literal ']'", outerClose + 1 - getBegin());
        }
    }

    private void setLexicalMemNode(EncloseNode node) {
        if (lexicalMemNodes == null) {
            lexicalMemNodes = new EncloseNode[Config.SCANENV_MEMNODES_SIZE];
        } else if (node.regNum >= lexicalMemNodes.length) {
            EncloseNode[] expanded = new EncloseNode[lexicalMemNodes.length << 1];
            System.arraycopy(lexicalMemNodes, 0, expanded, 0, lexicalMemNodes.length);
            lexicalMemNodes = expanded;
        }
        lexicalMemNodes[node.regNum] = node;
    }

    private static final int POSIX_BRACKET_NAME_MIN_LEN            = 4;
    private static final int POSIX_BRACKET_CHECK_LIMIT_LENGTH      = 20;
    private static final byte[] BRACKET_END                        = ":]".getBytes();
    private static final byte[] POSIX_ASCII                        = "ascii".getBytes();
    private boolean parsePosixBracket(CClassNode cc, CClassNode ascCc,
                                      CClassNode foldCc) {
        mark();

        boolean not;
        if (peekIs('^')) {
            inc();
            not = true;
        } else {
            not = false;
        }
        int nameStart = p;
        if (enc.strLength(bytes, p, stop) >= POSIX_BRACKET_NAME_MIN_LEN + 3) { // else goto not_posix_bracket
            boolean asciiRange = isAsciiRange(env.option) && !isPosixBracketAllRange(env.option);

            // [:ascii:] is a Perl POSIX extension absent from JCodings' POSIX
            // name table. Represent it as its concrete Unicode scalar range
            // so it composes with the normal extended-class set operators.
            if (enc.strNCmp(bytes, p, stop, POSIX_ASCII, 0, POSIX_ASCII.length) == 0) {
                p = enc.step(bytes, p, stop, POSIX_ASCII.length);
                if (enc.strNCmp(bytes, p, stop, BRACKET_END, 0, BRACKET_END.length) != 0) {
                    if (env.usesPerlDiagnostics()) {
                        restore();
                        return true;
                    }
                    newSyntaxException(INVALID_POSIX_BRACKET_TYPE);
                }
                if (not) {
                    cc.addCodeRange(env, 0x80, 0x10FFFF);
                } else {
                    cc.bs.setRange(env, 0, 0x7F);
                }
                if (foldCc != null) {
                    if (not) {
                        foldCc.addCodeRange(env, 0x80, 0x10FFFF);
                    } else {
                        foldCc.bs.setRange(env, 0, 0x7F);
                    }
                }
                inc();
                inc();
                return false;
            }

            for (int i=0; i<PosixBracket.PBSNamesLower.length; i++) {
                byte[]name = PosixBracket.PBSNamesLower[i];
                // hash lookup here ?
                if (enc.strNCmp(bytes, p, stop, name, 0, name.length) == 0) {
                    if (perlExtendedClassLeaf
                            && name.length < POSIX_BRACKET_NAME_MIN_LEN) {
                        newSyntaxException(INVALID_POSIX_BRACKET_TYPE);
                    }
                    p = enc.step(bytes, p, stop, name.length);
                    if (enc.strNCmp(bytes, p, stop, BRACKET_END, 0, BRACKET_END.length) != 0) {
                        if (env.usesPerlDiagnostics()) {
                            restore();
                            return true;
                        }
                        newSyntaxException(INVALID_POSIX_BRACKET_TYPE);
                    }
                    int ctype = PosixBracket.PBSValues[i];
                    CharacterPropertyResolver.Result resolved = ctype == CharacterType.XDIGIT
                            && !asciiRange && syntax.characterPropertyResolver != null
                            ? syntax.characterPropertyResolver.resolve(
                                    bytes, nameStart, p, enc, true)
                            : null;
                    if (resolved != null) {
                        addCharProperty(cc, ascCc, foldCc,
                                new CharProperty(0, resolved.ranges,
                                        resolved.wideRanges, resolved.caseFold),
                                not);
                    } else {
                        cc.addCType(ctype, not, asciiRange, env, this);
                        if (ascCc != null) {
                            if (ctype != CharacterType.WORD && ctype != CharacterType.ASCII && !asciiRange) {
                                ascCc.addCType(ctype, not, asciiRange, env, this);
                            }
                        }
                        if (foldCc != null) {
                            foldCc.addCType(ctype, not, asciiRange, env, this);
                        }
                    }
                    inc();
                    inc();
                    return false;
                }
            }

        }

        // not_posix_bracket:
        c = 0;
        int i= 0;
        while (left() && ((c=peek()) != ':') && c != ']') {
            inc();
            if (++i > POSIX_BRACKET_CHECK_LIMIT_LENGTH) break;
        }

        if (c == ':' && left()) {
            int nameEnd = p;
            inc();
            if (left()) {
                fetch();
                if (c == ']') {
                    if (env.usesPerlDiagnostics()) {
                        int nameCharacters = enc.strLength(bytes, nameStart, nameEnd);
                        if (!asciiOnly(nameStart, nameEnd)
                                || perlExtendedClassLeaf
                                        && nameCharacters < POSIX_BRACKET_NAME_MIN_LEN) {
                            restore();
                            return true;
                        }
                        if (perlExtendedClassLeaf && left() && peekIs(']')) {
                            int closeEnd = p + enc.length(bytes, p, stop);
                            newSyntaxException(
                                    PERL_EXTENDED_CLASS_UNEXPECTED_OUTER_CLOSE,
                                    closeEnd - getBegin());
                        }
                        if (!isPerlPosixClassName(nameStart, nameEnd)) {
                            addPerlLiteralPosixText(
                                    cc, ascCc, foldCc, nameStart, nameEnd);
                            return false;
                        }
                        String name = new String(bytes, nameStart,
                                nameEnd - nameStart, StandardCharsets.US_ASCII);
                        newSyntaxException("POSIX class [:" + (not ? "^" : "")
                                + name + ":] unknown");
                    }
                    newSyntaxException(INVALID_POSIX_BRACKET_TYPE);
                }
            }
        }
        restore();
        return true; /* 1: is not POSIX bracket, but no error. */
    }

    private boolean isPerlPosixClassName(int start, int end) {
        int length = 0;
        for (int cursor = start; cursor < end; ) {
            int code = enc.mbcToCode(bytes, cursor, end);
            if (code < 'a' || code > 'z') return false;
            cursor += enc.length(bytes, cursor, end);
            length++;
        }
        return length != 1;
    }

    private void addPerlLiteralPosixText(CClassNode cc, CClassNode ascCc,
                                         CClassNode foldCc, int start, int end) {
        cc.addCodeRange(env, ':', ':');
        if (ascCc != null) ascCc.addCodeRange(env, ':', ':');
        if (foldCc != null) foldCc.addCodeRange(env, ':', ':');
        for (int cursor = start; cursor < end; ) {
            int code = enc.mbcToCode(bytes, cursor, end);
            cc.addCodeRange(env, code, code);
            if (ascCc != null) ascCc.addCodeRange(env, code, code);
            if (foldCc != null) foldCc.addCodeRange(env, code, code);
            cursor += enc.length(bytes, cursor, end);
        }
    }

    private boolean codeExistCheck(int code, boolean ignoreEscaped) {
        mark();

        boolean inEsc = false;
        while (left()) {
            if (ignoreEscaped && inEsc) {
                inEsc = false;
            } else {
                fetch();
                if (c == code) {
                    restore();
                    return true;
                }
                if (c == syntax.metaCharTable.esc) inEsc = true;
            }
        }

        restore();
        return false;
    }

    private boolean extendedPosixPrimaryUsesParser() {
        int nameStart = p + 2;
        int cursor = nameStart;
        while (cursor < stop && !asciiAt(cursor, ':')
                && !asciiAt(cursor, ']')) cursor++;
        if (!asciiAt(cursor, ':') || !asciiAt(cursor + 1, ']')) return false;
        if (!asciiOnly(nameStart, cursor)) return false;
        String name = asciiText(nameStart, cursor);
        return name.length() >= POSIX_BRACKET_NAME_MIN_LEN;
    }

    private record ParsedCharClass(CClassNode standard,
                                   List<StringNode> namedSequences) {}

    private record PerlExtendedClassPrimary(CClassNode node,
                                            CClassNode asciiFoldSource,
                                            CClassNode foldSource,
                                            boolean scopedOptionsApplied) {
        private PerlExtendedClassPrimary(
                CClassNode node, boolean scopedOptionsApplied) {
            this(node, node, node, scopedOptionsApplied);
        }
    }

    private final class PerlCharsetOptionState {
        private int asciiModifierCount;
        private boolean sawDefaultCharset;
        private boolean sawLocaleCharset;
        private boolean sawUnicodeCharset;

        int apply(int option, int modifier, boolean neg, boolean rejectLocale) {
            switch (modifier) {
            case 'a':
                if (!(syntax.op2OptionPerl() || syntax.op2OptionRuby()) || neg) {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                if (syntax.op2OptionPerl() && sawDefaultCharset) {
                    newSyntaxException(PERL_MODIFIERS_D_AND_A_MUTUALLY_EXCLUSIVE);
                }
                asciiModifierCount++;
                if (syntax.op2OptionPerl() && asciiModifierCount > 2) {
                    newSyntaxException(PERL_MODIFIER_A_MAXIMUM_TWICE);
                }
                option = bsOnOff(option, Option.ASCII_RANGE, false);
                option = bsOnOff(option, Option.POSIX_BRACKET_ALL_RANGE, true);
                option = bsOnOff(option, Option.WORD_BOUND_ALL_RANGE, true);
                option = bsOnOff(option, Option.PERL_EXPLICIT_ASCII, false);
                return bsOnOff(option, Option.PERL_ASCII_STRICT,
                        syntax.op2OptionPerl() && asciiModifierCount >= 2
                                ? false : true);
            case 'u':
                if (!(syntax.op2OptionPerl() || syntax.op2OptionRuby()) || neg) {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                if (syntax.op2OptionPerl() && sawLocaleCharset) {
                    newSyntaxException(PERL_MODIFIERS_L_AND_U_MUTUALLY_EXCLUSIVE);
                }
                sawUnicodeCharset = true;
                option = bsOnOff(option, Option.ASCII_RANGE, true);
                option = bsOnOff(option, Option.POSIX_BRACKET_ALL_RANGE, true);
                option = bsOnOff(option, Option.WORD_BOUND_ALL_RANGE, true);
                option = bsOnOff(option, Option.PERL_EXPLICIT_ASCII, true);
                return bsOnOff(option, Option.PERL_ASCII_STRICT, true);
            case 'd':
                if (!syntax.op2OptionPerl() || neg) {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                if (asciiModifierCount > 0) {
                    newSyntaxException(PERL_MODIFIERS_D_AND_A_MUTUALLY_EXCLUSIVE);
                }
                sawDefaultCharset = true;
                // Perl /d keeps character classes byte-oriented when both
                // pattern and subject are byte strings, but promotes them for
                // a UTF-8 subject. The adapter selects a dedicated
                // PERL_BYTE_PATTERN variant for the former case.
                option = bsOnOff(option, Option.ASCII_RANGE,
                        !Option.isPerlBytePattern(option));
                option = bsOnOff(option, Option.PERL_EXPLICIT_ASCII, true);
                return bsOnOff(option, Option.PERL_ASCII_STRICT, true);
            case 'l':
                if (rejectLocale) {
                    newSyntaxException(PERL_EXTENDED_CLASS_LOCALE_UNSUPPORTED);
                }
                if (!syntax.op2OptionPerl() || neg) {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                if (sawUnicodeCharset) {
                    newSyntaxException(PERL_MODIFIERS_L_AND_U_MUTUALLY_EXCLUSIVE);
                }
                if (sawLocaleCharset) {
                    newSyntaxException(PERL_MODIFIER_L_MAY_NOT_APPEAR_TWICE);
                }
                sawLocaleCharset = true;
                option = bsOnOff(option, Option.ASCII_RANGE, true);
                option = bsOnOff(option, Option.PERL_EXPLICIT_ASCII, true);
                return bsOnOff(option, Option.PERL_ASCII_STRICT, true);
            default:
                throw new AssertionError(modifier);
            }
        }
    }

    private ParsedCharClass parseCharClass(ObjPtr<CClassNode> ascNode,
                                           ObjPtr<CClassNode> foldNode) {
        int classContentStart = p - getBegin();
        final boolean neg;
        CClassNode cc, prevCc = null, ascCc = null, ascPrevCc = null,
                workCc = null, ascWorkCc = null, foldCc = null,
                foldPrevCc = null, foldWorkCc = null;
        CCStateArg arg = new CCStateArg();
        List<StringNode> namedSequences = new ArrayList<>();

        fetchTokenInCC();
        if (token.type == TokenType.CHAR && token.getC() == '^' && !token.escaped) {
            neg = true;
            fetchTokenInCC();
        } else {
            neg = false;
        }

        if (token.type == TokenType.CC_CLOSE && !syntax.op3OptionECMAScript()) {
            if (!codeExistCheck(']', true)) {
                if (env.usesPerlDiagnostics()) {
                    newSyntaxException(PERL_UNMATCHED_OPEN_BRACKET, classContentStart);
                }
                newSyntaxException(EMPTY_CHAR_CLASS);
            }
            env.ccEscWarn("]");
            token.type = TokenType.CHAR; /* allow []...] */
        }

        cc = new CClassNode();
        if (isIgnoreCase(env.option)) {
            ascCc = ascNode.p = new CClassNode();
            foldCc = foldNode.p = new CClassNode();
        }

        boolean andStart = false;
        arg.state = CCSTATE.START;
        while (token.type != TokenType.CC_CLOSE) {
            boolean fetched = false;
            arg.toEscaped = token.escaped;
            arg.toNamedCharacter = token.namedCharacter;
            arg.toFalseRangeEligible = false;
            arg.toStart = token.backP - getBegin();
            arg.toEnd = p - getBegin();

            switch (token.type) {
            case CHAR:
                final int len;
                if (token.getCode() >= BitSet.SINGLE_BYTE_SIZE || (len = enc.codeToMbcLength(token.getC())) > 1) {
                    arg.inType = CCVALTYPE.CODE_POINT;
                } else {
                    arg.inType = CCVALTYPE.SB; // sb_char:
                }
                arg.to = token.getC();
                arg.toIsRaw = false;
                parseCharClassValEntry2(cc, ascCc, foldCc, arg); // goto val_entry2
                break;

            case RAW_BYTE:
                if (!enc.isSingleByte() && token.base != 0) { /* tok->base != 0 : octal or hexadec. */
                    byte[]buf = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN];
                    int psave = p;
                    int base = token.base;
                    buf[0] = (byte)token.getC();
                    int i;
                    for (i=1; i<enc.maxLength(); i++) {
                        fetchTokenInCC();
                        if (token.type != TokenType.RAW_BYTE || token.base != base) {
                            fetched = true;
                            break;
                        }
                        buf[i] = (byte)token.getC();
                    }
                    if (i < enc.minLength()) newValueException(TOO_SHORT_MULTI_BYTE_STRING);

                    len = enc.length(buf, 0, i);
                    if (i < len) {
                        newValueException(TOO_SHORT_MULTI_BYTE_STRING);
                    } else if (i > len) { /* fetch back */
                        p = psave;
                        for (i=1; i<len; i++) fetchTokenInCC();
                        fetched = false;
                    }
                    if (i == 1) {
                        arg.to = buf[0] & 0xff;
                        arg.inType = CCVALTYPE.SB; // goto raw_single
                    } else {
                        arg.to = enc.mbcToCode(buf, 0, buf.length);
                        arg.inType = CCVALTYPE.CODE_POINT;
                    }
                } else {
                    arg.to = token.getC();
                    arg.inType = CCVALTYPE.SB; // raw_single:
                }
                arg.toIsRaw = true;
                parseCharClassValEntry2(cc, ascCc, foldCc, arg); // goto val_entry2
                break;

            case CODE_POINT:
                arg.to = token.getCode();
                arg.toIsRaw = true;
                parseCharClassValEntry(cc, ascCc, foldCc, arg); // val_entry:, val_entry2
                break;

            case NAMED_STRING:
                int[] namedSequence = token.getNamedCharacterSequence();
                if (perlExtendedClassLeaf && namedSequence.length == 0) {
                    newSyntaxException(PERL_EXTENDED_CLASS_ZERO_LENGTH_NAMED_CHARACTER);
                }
                if (neg && namedSequence.length > 1) {
                    syntaxWarn("Using just the first character returned by \\N{} in character class",
                            p - getBegin());
                    // Perl 5 observably selects the final source-order code
                    // point here, although its warning calls it the "first".
                    arg.to = namedSequence[namedSequence.length - 1];
                    arg.toIsRaw = true;
                    parseCharClassValEntry(cc, ascCc, foldCc, arg);
                } else {
                    cc.nextStateClass(arg, ascCc, foldCc, env);
                }
                if (!neg && namedSequence.length > 0) {
                    namedSequences.add(namedCharacterStringNode(
                            namedSequence));
                }
                break;

            case WIDE_CODE_POINT:
                arg.to = token.getWideCode();
                arg.toIsRaw = true;
                arg.inType = CCVALTYPE.WIDE_SCALAR;
                parseCharClassValEntry2(cc, ascCc, foldCc, arg);
                break;

            case POSIX_BRACKET_OPEN:
                if (parsePosixBracket(cc, ascCc, foldCc)) { /* true: is not POSIX bracket */
                    if (!env.usesPerlDiagnostics()) env.ccEscWarn("[");
                    // Perl recovers a POSIX-looking near miss as literal class
                    // text. Consume the candidate '[' exactly once; rewinding
                    // onto it reparses it as a nested class and can turn a
                    // recoverable spelling into a spurious Unmatched [ error.
                    p = env.usesPerlDiagnostics()
                            ? token.backP + enc.length(bytes, token.backP, stop)
                            : token.backP;
                    arg.to = token.getC();
                    arg.toIsRaw = false;
                    parseCharClassValEntry(cc, ascCc, foldCc, arg); // goto val_entry
                    break;
                }
                arg.toStart = token.backP - getBegin() - 1;
                arg.toEnd = p - getBegin();
                warnFalseRangeBeforeClass(arg, arg.toEnd);
                arg.toFalseRangeEligible = true;
                cc.nextStateClass(arg, ascCc, foldCc, env); // goto next_class
                break;

            case CHAR_TYPE:
                warnFalseRangeBeforeClass(arg, p - getBegin());
                arg.toFalseRangeEligible = true;
                cc.addCType(token.getPropCType(), token.getPropNot(), isAsciiRange(env.option), env, this);
                if (ascCc != null) {
                    if (token.getPropCType() != CharacterType.WORD) {
                        ascCc.addCType(token.getPropCType(), token.getPropNot(), isAsciiRange(env.option), env, this);
                    }
                }
                if (foldCc != null) {
                    foldCc.addCType(token.getPropCType(), token.getPropNot(),
                            isAsciiRange(env.option), env, this);
                }
                cc.nextStateClass(arg, ascCc, foldCc, env); // next_class:
                break;

            case CHAR_PROPERTY:
                CharProperty property = fetchCharProperty(true);
                arg.toEnd = p - getBegin();
                warnFalseRangeBeforeClass(arg, arg.toEnd);
                arg.toFalseRangeEligible = true;
                addCharProperty(cc, ascCc, foldCc, property, token.getPropNot());
                cc.nextStateClass(arg, ascCc, foldCc, env); // goto next_class
                break;

            case CC_RANGE:
                if (arg.state == CCSTATE.VALUE) {
                    if (arg.type == CCVALTYPE.CLASS && env.usesPerlDiagnostics()) {
                        // Perl accepts [\d-z] as a false range: retain the
                        // class and make the hyphen a pending literal value.
                        if (arg.fromFalseRangeEligible) warnFalseRangeAfterClass(arg);
                        arg.state = CCSTATE.COMPLETE;
                        arg.to = '-';
                        arg.toIsRaw = false;
                        arg.toEscaped = false;
                        arg.toNamedCharacter = false;
                        arg.inType = CCVALTYPE.SB;
                        cc.nextStateValue(arg, ascCc, foldCc, env);
                        break;
                    }
                    fetchTokenInCC();
                    fetched = true;
                    if (token.type == TokenType.CC_CLOSE) { /* allow [x-] */
                        parseCharClassRangeEndVal(cc, ascCc, foldCc, arg); // range_end_val:, goto val_entry;
                        break;
                    } else if (token.type == TokenType.CC_AND) {
                        env.ccEscWarn("-");
                        parseCharClassRangeEndVal(cc, ascCc, foldCc, arg); // goto range_end_val
                        break;
                    }
                    if (arg.type == CCVALTYPE.CLASS) newValueException(UNMATCHED_RANGE_SPECIFIER_IN_CHAR_CLASS);
                    arg.state = CCSTATE.RANGE;
                } else if (arg.state == CCSTATE.START) {
                    arg.to = token.getC(); /* [-xa] is allowed */
                    arg.toIsRaw = false;
                    arg.toEscaped = false;
                    arg.toNamedCharacter = false;
                    fetchTokenInCC();
                    fetched = true;
                    if (token.type == TokenType.CC_RANGE || andStart) env.ccEscWarn("-"); /* [--x] or [a&&-x] is warned. */
                    parseCharClassValEntry(cc, ascCc, foldCc, arg); // goto val_entry
                    break;
                } else if (arg.state == CCSTATE.RANGE) {
                    env.ccEscWarn("-");
                    parseCharClassSbChar(cc, ascCc, foldCc, arg); // goto sb_char /* [!--x] is allowed */
                    break;
                } else { /* CCS_COMPLETE */
                    fetchTokenInCC();
                    fetched = true;
                    if (token.type == TokenType.CC_CLOSE) { /* allow [a-b-] */
                        parseCharClassRangeEndVal(cc, ascCc, foldCc, arg); // goto range_end_val
                        break;
                    } else if (token.type == TokenType.CC_AND) {
                        env.ccEscWarn("-");
                        parseCharClassRangeEndVal(cc, ascCc, foldCc, arg); // goto range_end_val
                        break;
                    }

                    if (syntax.allowDoubleRangeOpInCC()) {
                        env.ccEscWarn("-");
                        // parseCharClassSbChar(cc, ascCc, arg); // goto sb_char /* [0-9-a] is allowed as [0-9\-a] */
                        parseCharClassRangeEndVal(cc, ascCc, foldCc, arg); // goto range_end_val
                        break;
                    }
                    newSyntaxException(UNMATCHED_RANGE_SPECIFIER_IN_CHAR_CLASS);
                }
                break;

            case CC_CC_OPEN: /* [ */
                if (perlExtendedClassLeaf && !token.escaped) {
                    // A standard class remains a leaf of (?[...]). Ruby's
                    // class-set lexer tokenizes '[' specially, but Perl still
                    // permits it as an unescaped member here (for example
                    // [^][ \\ ] and [a[]).
                    //
                    // Escapes such as \\h synthesize a nested character class
                    // token sequence. They must still be consumed as that
                    // sequence; treating their opening token as a literal '['
                    // leaves the generated contents in the outer expression.
                    arg.inType = CCVALTYPE.SB;
                    arg.to = '[';
                    arg.toIsRaw = false;
                    parseCharClassValEntry2(cc, ascCc, foldCc, arg);
                    break;
                }
                ObjPtr<CClassNode> ascPtr = new ObjPtr<>();
                ObjPtr<CClassNode> foldPtr = new ObjPtr<>();
                ParsedCharClass nested = parseCharClass(ascPtr, foldPtr);
                if (!nested.namedSequences().isEmpty()) {
                    newSyntaxException(CHAR_CLASS_VALUE_AT_END_OF_RANGE);
                }
                cc.or(nested.standard(), env);
                if (ascPtr.p != null) {
                    ascCc.or(ascPtr.p, env);
                }
                if (foldPtr.p != null) {
                    foldCc.or(foldPtr.p, env);
                }
                break;

            case CC_AND:     /* && */
                if (arg.state == CCSTATE.VALUE) {
                    arg.to = 0;
                    arg.toIsRaw = false;
                    cc.nextStateValue(arg, ascCc, foldCc, env);
                }
                /* initialize local variables */
                andStart = true;
                arg.state = CCSTATE.START;
                if (prevCc != null) {
                    prevCc.and(cc, env);
                    if (ascCc != null) {
                        ascPrevCc.and(ascCc, env);
                    }
                    if (foldCc != null) {
                        foldPrevCc.and(foldCc, env);
                    }
                } else {
                    prevCc = cc;
                    if (workCc == null) workCc = new CClassNode();
                    cc = workCc;
                    if (ascCc != null) {
                        ascPrevCc = ascCc;
                        if (ascWorkCc == null) ascWorkCc = new CClassNode();
                        ascCc = ascWorkCc;
                    }
                    if (foldCc != null) {
                        foldPrevCc = foldCc;
                        if (foldWorkCc == null) foldWorkCc = new CClassNode();
                        foldCc = foldWorkCc;
                    }
                }
                cc.clear();
                if (ascCc != null) ascCc.clear();
                if (foldCc != null) foldCc.clear();
                break;

            case EOT:
                // Perl distinguishes an unfinished range after a non-ASCII
                // class value (for example /[\xdf-/i) from a bare unmatched
                // opening bracket. Preserve the ordinary EOT diagnostic for
                // ASCII ranges such as /[a-/.
                if (arg.state == CCSTATE.RANGE && arg.to >= 0x80
                        && env.usesPerlDiagnostics()) {
                    newSyntaxException(PERL_INVALID_RANGE_IN_CHAR_CLASS);
                }
                newSyntaxException(PREMATURE_END_OF_CHAR_CLASS);

            default:
                newInternalException(PARSER_BUG);
            } // switch

            if (!fetched) fetchTokenInCC();

        } // while

        if (arg.state == CCSTATE.VALUE) {
            arg.to = 0;
            arg.toIsRaw = false;
            arg.toEscaped = false;
            arg.toNamedCharacter = false;
            cc.nextStateValue(arg, ascCc, foldCc, env);
        }

        if (prevCc != null) {
            prevCc.and(cc, env);
            cc = prevCc;
            if (ascCc != null) {
                ascPrevCc.and(ascCc, env);
                ascCc = ascPrevCc;
            }
            if (foldCc != null) {
                foldPrevCc.and(foldCc, env);
                foldCc = foldPrevCc;
            }
        }

        if (neg) {
            cc.setNot();
            if (ascCc != null) ascCc.setNot();
            if (foldCc != null) foldCc.setNot();
        } else {
            cc.clearNot();
            if (ascCc != null) ascCc.clearNot();
            if (foldCc != null) foldCc.clearNot();
        }

        if (cc.isNot() && syntax.notNewlineInNegativeCC()) {
            if (!cc.isEmpty()) { // ???
                final int NEW_LINE = 0x0a;
                if (enc.isNewLine(NEW_LINE)) {
                    if (enc.codeToMbcLength(NEW_LINE) == 1) {
                        cc.bs.set(env, NEW_LINE);
                    } else {
                        cc.addCodeRange(env, NEW_LINE, NEW_LINE);
                    }
                }
            }
        }

        return new ParsedCharClass(cc, namedSequences);
    }

    private StringNode namedCharacterStringNode(int[] sequence) {
        StringNode node = new StringNode();
        for (int codePoint : sequence) node.catCode(codePoint, enc);
        return node;
    }

    private Node addNamedCharacterClassAlternatives(
            Node standard, CClassNode standardClass, List<StringNode> sequences) {
        if (sequences.isEmpty()) return standard;
        ListNode head = null;
        ListNode tail = null;
        for (StringNode sequence : sequences) {
            ListNode next = ListNode.newAlt(sequence, null);
            if (head == null) head = next;
            else tail.setTail(next);
            tail = next;
        }
        if (standardClass.isNot() || !standardClass.isEmpty()) {
            tail.setTail(ListNode.newAlt(standard, null));
        }
        return head.tail == null ? head.value : head;
    }

    private void parseCharClassSbChar(CClassNode cc, CClassNode ascCc,
                                      CClassNode foldCc, CCStateArg arg) {
        arg.inType = CCVALTYPE.SB;
        arg.to = token.getC();
        arg.toIsRaw = false;
        parseCharClassValEntry2(cc, ascCc, foldCc, arg); // goto val_entry2
    }

    private void parseCharClassRangeEndVal(CClassNode cc, CClassNode ascCc,
                                           CClassNode foldCc, CCStateArg arg) {
        arg.to = '-';
        arg.toIsRaw = false;
        arg.toEscaped = false;
        arg.toNamedCharacter = false;
        parseCharClassValEntry(cc, ascCc, foldCc, arg); // goto val_entry
    }

    private void parseCharClassValEntry(CClassNode cc, CClassNode ascCc,
                                        CClassNode foldCc, CCStateArg arg) {
        if (arg.to > 0x10ffffL) {
            arg.inType = CCVALTYPE.WIDE_SCALAR;
            parseCharClassValEntry2(cc, ascCc, foldCc, arg);
            return;
        }
        int len = enc.codeToMbcLength((int)arg.to);
        arg.inType = len == 1 ? CCVALTYPE.SB : CCVALTYPE.CODE_POINT;
        parseCharClassValEntry2(cc, ascCc, foldCc, arg); // val_entry2:
    }

    private void parseCharClassValEntry2(CClassNode cc, CClassNode ascCc,
                                         CClassNode foldCc, CCStateArg arg) {
        if (arg.state == CCSTATE.RANGE && arg.from > arg.to
                && env.usesPerlDiagnostics() && !syntax.allowEmptyRangeInCC()) {
            int sourceStart = getBegin() + arg.fromStart;
            int sourceEnd = getBegin() + arg.toEnd;
            String range = new String(bytes, sourceStart, sourceEnd - sourceStart,
                    enc.getCharset());
            String message = env.emptyRangeError();
            newSyntaxException(message, arg.toEnd,
                    message + " \"" + range + "\"");
        }
        warnPerlExtendedClassRange(arg);
        cc.nextStateValue(arg, ascCc, foldCc, env);
    }

    private void warnFalseRangeBeforeClass(CCStateArg arg, int endpointEnd) {
        if (arg.state != CCSTATE.RANGE || !env.usesPerlDiagnostics()) return;
        warnFalseRange(arg.fromStart, endpointEnd, endpointEnd);
    }

    private void warnFalseRangeAfterClass(CCStateArg arg) {
        warnFalseRange(arg.fromStart, arg.toEnd, arg.toEnd);
    }

    private void warnFalseRange(int sourceStart, int sourceEnd, int warningPosition) {
        if (sourceStart < 0 || sourceEnd < sourceStart
                || getBegin() + sourceEnd > stop) return;
        String range = new String(bytes, getBegin() + sourceStart,
                sourceEnd - sourceStart, enc.getCharset());
        String message = "False [] range \"" + range + "\"";
        if (perlExtendedClassLeaf) {
            newSyntaxException("False [] range", warningPosition, message);
        }
        env.warnings.warn(message, warningPosition);
    }

    private void warnPerlExtendedClassRange(CCStateArg arg) {
        boolean strictOrdinaryClass = !perlExtendedClassLeaf
                && Option.isPerlReStrict(env.option);
        if (!perlExtendedClassLeaf && !strictOrdinaryClass
                || arg.state != CCSTATE.RANGE
                || !env.usesPerlDiagnostics() || arg.from > arg.to) {
            return;
        }

        if (arg.fromEscaped && arg.toEscaped
                && arg.fromNamedCharacter != arg.toNamedCharacter) {
            if (perlExtendedClassLeaf) {
                env.warnings.warn("Both or neither range ends should be Unicode",
                        perlExtendedRangeWarningPosition());
            }
            return;
        }

        int from = (int)arg.from;
        int to = (int)arg.to;
        if (from == to && !rangeEndpointSource(arg.fromStart, arg.fromEnd)
                .equals(rangeEndpointSource(arg.toStart, arg.toEnd))) {
            int warningPosition = perlExtendedRangeWarningPosition();
            String source = rangeEndpointSource(arg.fromStart, warningPosition);
            env.warnings.warn("\"" + source
                    + "\" is more clearly written simply as \""
                    + perlRangeLiteral(from) + "\"", warningPosition);
            return;
        }
        if (isAsciiPrintable(from) || isAsciiPrintable(to)) {
            if (from != to && (arg.fromEscaped || arg.toEscaped
                    || !isSameAsciiRangeGroup(from, to))) {
                env.warnings.warn("Ranges of ASCII printables should be some subset of "
                        + "\"0-9\", \"A-Z\", or \"a-z\"",
                        perlExtendedRangeWarningPosition());
            }
            return;
        }

        int fromDigit = Character.getNumericValue(from);
        int toDigit = Character.getNumericValue(to);
        if (fromDigit >= 0 && toDigit >= 0
                && fromDigit <= 9 && toDigit <= 9
                && from - fromDigit != to - toDigit) {
            env.warnings.warn("Ranges of digits should be from the same group of 10",
                    perlExtendedRangeWarningPosition());
        }
    }

    private String rangeEndpointSource(int start, int end) {
        return new String(bytes, getBegin() + start, end - start, enc.getCharset());
    }

    private static String perlRangeLiteral(int codePoint) {
        return switch (codePoint) {
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\f' -> "\\f";
            default -> isAsciiPrintable(codePoint)
                    ? Character.toString(codePoint)
                    : String.format("\\x{%X}", codePoint);
        };
    }

    private int perlExtendedRangeWarningPosition() {
        int cursor = p;
        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (code != ' ' && code != '\t') break;
            cursor += enc.length(bytes, cursor, stop);
        }
        return cursor - getBegin();
    }

    private static boolean isAsciiPrintable(int codePoint) {
        return codePoint >= 0x20 && codePoint <= 0x7e;
    }

    private static boolean isSameAsciiRangeGroup(int from, int to) {
        return from >= '0' && to <= '9'
                || from >= 'A' && to <= 'Z'
                || from >= 'a' && to <= 'z';
    }

    private Node parseEnclose(TokenType term) {
        Node node = null;
        int conditionalBodyStart = -1;

        if (!left()) newSyntaxException(END_PATTERN_WITH_UNMATCHED_PARENTHESIS);

        int option = env.option;

        if (peekIs('*')) {
            inc();
            return parseControlVerb();
        }

        if (peekIs('?') && syntax.op2QMarkGroupEffect()) {
            inc();
            if (!left()) {
                newSyntaxException(syntax.op2OptionPerl()
                        ? PERL_GROUP_EFFECT_INCOMPLETE
                        : END_PATTERN_IN_GROUP);
            }

            boolean listCapture = false;

            fetch();
            if (syntax.op2OptionPerl() && c == '&') {
                return parsePerlNamedCall();
            }
            if (syntax.op2OptionPerl() && enc.isDigit(c)) {
                return parsePerlNumberedCall(c);
            }
            if (syntax.op2OptionPerl() && (c == '+' || c == '-')
                    && left() && enc.isDigit(peek())) {
                return parsePerlRelativeCall(c);
            }
            switch(c) {
            case ')':
                if (syntax.op2OptionPerl()) {
                    returnCode = 0;
                    return StringNode.EMPTY;
                }
                newSyntaxException(UNDEFINED_GROUP_OPTION);
                break;
            case 'R':
                if (syntax.op2OptionPerl()) {
                    if (!left() || !peekIs(')')) {
                        newSyntaxException(UNDEFINED_GROUP_OPTION);
                    }
                    inc();
                    // group 0 with an empty name span denotes a numbered
                    // whole-pattern call to the analyser, not a named call.
                    CallNode call = new CallNode(bytes, p, p, 0);
                    env.numCall++;
                    returnCode = 0;
                    return call;
                }
                newSyntaxException(UNDEFINED_GROUP_OPTION);
                break;
            case '{':
                return parseInternalCallout();
            case ':':  /* (?:...) grouping only */
                fetchToken(); // group:
                node = parseSubExp(term);
                returnCode = 1; /* group */
                return node;
            case '=':
                node = new AnchorNode(AnchorType.PREC_READ);
                break;
            case '!':  /*         preceding read */
                node = new AnchorNode(AnchorType.PREC_READ_NOT);
                if (syntax.op3OptionECMAScript()) {
                    env.pushPrecReadNotNode(node);
                }
                break;
            case '>':  /* (?>...) stop backtrack */
                node = new EncloseNode(EncloseType.STOP_BACKTRACK); // node_new_enclose
                break;
            case '~': /* (?~...) absent operator */
                if (syntax.op2QMarkTildeAbsent()) {
                    node = new EncloseNode(EncloseType.ABSENT);
                    break;
                } else {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
            case '\'':
                if (Config.USE_NAMED_GROUP) {
                    if (syntax.op2QMarkLtNamedGroup()) {
                        if (!left()) {
                            newSyntaxException(PERL_QUOTE_NAMED_CAPTURE_NOT_TERMINATED);
                        }
                        listCapture = false; // goto named_group1
                        node = parseEncloseNamedGroup2(listCapture);
                        break;
                    } else {
                        newSyntaxException(UNDEFINED_GROUP_OPTION);
                    }
                } // USE_NAMED_GROUP
                break;
            case '<':  /* look behind (?<=...), (?<!...) */
                if (!left()) {
                    newSyntaxException(syntax.op2OptionPerl()
                            ? PERL_PYTHON_NAMED_CAPTURE_NOT_TERMINATED
                            : END_PATTERN_WITH_UNMATCHED_PARENTHESIS);
                }
                fetch();
                if (c == '=') {
                    node = new AnchorNode(AnchorType.LOOK_BEHIND);
                } else if (c == '!') {
                    node = new AnchorNode(AnchorType.LOOK_BEHIND_NOT);
                } else {
                    if (Config.USE_NAMED_GROUP) {
                        if (syntax.op2QMarkLtNamedGroup()) {
                            if (syntax.op2OptionPerl() && c != '>'
                                    && !hasCodePointAhead('>')) {
                                newSyntaxException(PERL_PYTHON_NAMED_CAPTURE_NOT_TERMINATED,
                                        nextClosingParenthesisPosition());
                            }
                            unfetch();
                            c = '<';

                            listCapture = false; // named_group1:
                            node = parseEncloseNamedGroup2(listCapture); // named_group2:
                            break;
                        } else {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }

                    } else { // USE_NAMED_GROUP
                        newSyntaxException(UNDEFINED_GROUP_OPTION);
                    } // USE_NAMED_GROUP
                }
                break;
            case '@':
                if (syntax.op2AtMarkCaptureHistory()) {
                    if (Config.USE_NAMED_GROUP) {
                        if (left() && syntax.op2QMarkLtNamedGroup()) {
                            fetch();
                            if (c == '<' || c == '\'') {
                                listCapture = true;
                                node = parseEncloseNamedGroup2(listCapture); // goto named_group2 /* (?@<name>...) */
                            }
                            unfetch();
                        }
                    } // USE_NAMED_GROUP
                    EncloseNode en = EncloseNode.newMemory(env.option, false);
                    int num = env.addMemEntry();
                    if (num >= BitStatus.BIT_STATUS_BITS_NUM) newValueException(GROUP_NUMBER_OVER_FOR_CAPTURE_HISTORY);
                    en.regNum = num;
                    node = en;
                } else {
                    newSyntaxException(PERL_AT_MARK_GROUP_NOT_IMPLEMENTED);
                }
                break;

            case ';':
                newSyntaxException(PERL_SEMICOLON_GROUP_NOT_RECOGNIZED);
                break;

            case 'P':  /* Perl's Python-style (?P<name>...) and (?P=name) */
                if (!syntax.op2OptionPerl() || !left()) {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                fetch();
                if (c == '<') {
                    if (!left()) {
                        newSyntaxException(PERL_PYTHON_NAMED_CAPTURE_NOT_TERMINATED);
                    }
                    int first = enc.mbcToCode(bytes, p, stop);
                    if (!isPerlGroupNameStart(first)) {
                        newValueException(PERL_GROUP_NAME_MUST_START_WITH_WORD);
                    }
                    if (!hasCodePointAhead('>')) {
                        newSyntaxException(PERL_PYTHON_NAMED_CAPTURE_NOT_TERMINATED);
                    }
                    listCapture = false;
                    node = parseEncloseNamedGroup2(listCapture);
                    break;
                }
                if (c == '=') {
                    if (!left()) {
                        newSyntaxException(PERL_PYTHON_NAMED_BACKREF_NOT_TERMINATED);
                    }
                    int first = enc.mbcToCode(bytes, p, stop);
                    if (!isPerlGroupNameStart(first)) {
                        newValueException(PERL_GROUP_NAME_MUST_START_WITH_WORD);
                    }
                    if (!hasCodePointAhead(')')) {
                        newSyntaxException(PERL_PYTHON_NAMED_BACKREF_NOT_TERMINATED);
                    }
                    c = '(';
                    try {
                        fetchNamedBackrefToken();
                    } catch (SyntaxException e) {
                        if (e.getMessage().startsWith("undefined name <")) {
                            newValueException(PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
                        }
                        throw e;
                    }
                    returnCode = 0;
                    return parseBackref();
                }
                if (c == '>') {
                    return parsePerlNamedCall();
                }
                newValueException(PERL_PYTHON_GROUP_SEQUENCE_NOT_RECOGNIZED,
                        new String(Character.toChars(c)));
                break;

            case '(':   /* conditional expression: (?(cond)yes), (?(cond)yes|no) */
                if (left() && syntax.op2QMarkLParenCondition()) {
                    int num = -1;
                    int name = -1;
                    int physicalNamedCondition = -1;
                    int calloutConditionId = -1;
                    AnchorNode assertionCondition = null;
                    int recursionConditionGroup = -1;
                    int recursionConditionNameP = -1;
                    int recursionConditionNameEnd = -1;
                    fetch();
                    if (c == 'D' && startsWith("EFINE)")) {
                        p += "EFINE)".length();
                        node = new EncloseNode(EncloseType.DEFINE);
                        break;
                    } else if (c == '?' && left() && peekIs('{')) {
                        int unknownConditionPosition = p - getBegin();
                        fetch();
                        // RuntimeRegexTemplate lowers (??{...}) into a trusted
                        // internal DYNAMIC callout.  It is valid as a pattern
                        // atom, but Perl does not permit it as a conditional
                        // operand; retain the source-level diagnostic instead
                        // of falling through to the generic group-option error.
                        if (startsWith("=DYNAMIC:")) {
                            parseInternalCalloutId("=DYNAMIC:");
                            newSyntaxException(PERL_UNKNOWN_SWITCH_CONDITION,
                                    unknownConditionPosition);
                        }
                        calloutConditionId = parseInternalCalloutId();
                    } else if (c == '?' && left() && (peekIs('=') || peekIs('!'))) {
                        fetch();
                        assertionCondition = new AnchorNode(c == '='
                                ? AnchorType.PREC_READ : AnchorType.PREC_READ_NOT);
                        fetchToken();
                        assertionCondition.setTarget(parseSubExp(term));
                    } else if (c == '*') {
                        Node alphaCondition = parseAlphaAssertion();
                        if (!(alphaCondition instanceof AnchorNode)) {
                            newSyntaxException(INVALID_CONDITION_PATTERN);
                        }
                        assertionCondition = (AnchorNode)alphaCondition;
                    } else if (c == 'R') {
                        recursionConditionGroup = 0;
                        if (!left()) newSyntaxException(INVALID_CONDITION_PATTERN);
                        if (peekIs('&')) {
                            inc();
                            recursionConditionNameP = p;
                            while (left() && !peekIs(')')) inc();
                            recursionConditionNameEnd = p;
                            if (recursionConditionNameEnd == recursionConditionNameP || !left()) {
                                newSyntaxException(INVALID_CONDITION_PATTERN);
                            }
                            inc();
                        } else if (enc.isDigit(peek())) {
                            recursionConditionGroup = 0;
                            while (left() && enc.isDigit(peek())) {
                                recursionConditionGroup = recursionConditionGroup * 10 + peek() - '0';
                                inc();
                            }
                            if (!left() || !peekIs(')')) newSyntaxException(INVALID_CONDITION_PATTERN);
                            inc();
                        } else if (peekIs(')')) {
                            inc();
                        } else {
                            newSyntaxException(INVALID_CONDITION_PATTERN);
                        }
                    } else if (enc.isDigit(c)) { /* (n) */
                        unfetch();
                        if (syntax.op2OptionPerl()) {
                            int conditionEnd = p;
                            while (conditionEnd < stop
                                    && enc.isDigit(enc.mbcToCode(bytes, conditionEnd, stop))) {
                                conditionEnd += enc.length(bytes, conditionEnd, stop);
                            }
                            if (conditionEnd >= stop
                                    || enc.mbcToCode(bytes, conditionEnd, stop) != ')') {
                                newSyntaxException(PERL_SWITCH_CONDITION_NOT_RECOGNIZED,
                                        conditionEnd < stop
                                                ? conditionEnd + enc.length(
                                                        bytes, conditionEnd, stop) - getBegin()
                                                : conditionEnd - getBegin());
                            }
                        }
                        try {
                            num = fetchName('(', true);
                        } catch (SyntaxException e) {
                            if (syntax.op2OptionPerl()) {
                                newSyntaxException(PERL_SWITCH_CONDITION_NOT_RECOGNIZED);
                            }
                            throw e;
                        }
                        if (syntax.strictCheckBackref() && !syntax.op2OptionPerl()) {
                            if (num > env.numMem || env.memNodes == null || env.memNodes[num] == null) newValueException(INVALID_BACKREF);
                        }
                    } else {
                        if (Config.USE_NAMED_GROUP) {
                            if (c == '<' || c == '\'') {    /* (<name>), ('name') */
                                if (!left()) {
                                    newSyntaxException(c == '<'
                                            ? PERL_ANGLE_NAMED_CONDITION_NOT_TERMINATED
                                            : PERL_QUOTE_NAMED_CONDITION_NOT_TERMINATED);
                                }
                                name = p;
                                fetchNamedBackrefToken();
                                inc();
                                num = token.getBackrefNum() > 1 ? token.getBackrefRefs()[0] : token.getBackrefRef1();
                                NameEntry named = regex.nameToGroupNumbers(bytes, name, value);
                                if (named != null && named.backNum == 1) {
                                    physicalNamedCondition = named.getPhysicalBackRefs()[0];
                                }
                            } else {
                                newSyntaxException(PERL_UNKNOWN_SWITCH_CONDITION);
                            }
                        } else { // USE_NAMED_GROUP
                            newSyntaxException(PERL_UNKNOWN_SWITCH_CONDITION);
                        }
                    }
                    conditionalBodyStart = p;
                    EncloseNode en = new EncloseNode(EncloseType.CONDITION);
                    en.regNum = num;
                    en.physicalNamedCondition = physicalNamedCondition;
                    en.calloutConditionId = calloutConditionId;
                    en.assertionCondition = assertionCondition;
                    en.recursionConditionGroup = recursionConditionGroup;
                    en.recursionConditionNameP = recursionConditionNameP;
                    en.recursionConditionNameEnd = recursionConditionNameEnd;
                    if (name != -1) en.setNameRef();
                    node = en;
                } else {
                    if (syntax.op2OptionPerl()) {
                        newSyntaxException(PERL_UNKNOWN_SWITCH_CONDITION);
                    }
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }
                break;

            case '|':   /* Perl branch reset: (?|...|...) */
                if (syntax.op2QMarkGroupEffect()) {
                    fetchToken();
                    node = parseBranchReset(term);
                    returnCode = 0;
                    return node;
                }
                newSyntaxException(UNDEFINED_GROUP_OPTION);
                break;

            case '^': /* loads default options */
                if (left() && syntax.op2OptionPerl()) {
                    /* d-imsx */
                    option = bsOnOff(option, Option.ASCII_RANGE, true);
                    option = bsOnOff(option, Option.IGNORECASE, true);
                    option = bsOnOff(option, Option.SINGLELINE, false);
                    option = bsOnOff(option, Option.MULTILINE, true);
                    option = bsOnOff(option, Option.EXTEND, true);
                    option = bsOnOff(option, Option.PERL_EXTEND_MORE, true);
                    option = bsOnOff(option, Option.DONT_CAPTURE_GROUP, true);
                    option = bsOnOff(option, Option.CAPTURE_GROUP, false);
                    option = bsOnOff(option, Option.PERL_ASCII_STRICT, true);
                    option = bsOnOff(option, Option.PERL_EXPLICIT_ASCII, true);
                    fetch();
                    if (c == '-') {
                        newSyntaxException(PERL_CARET_MINUS_OPTION_NOT_RECOGNIZED);
                    }
                    if (c == 'd') {
                        newSyntaxException(PERL_CARET_D_OPTION_NOT_RECOGNIZED);
                    }
                } else {
                    newSyntaxException(UNDEFINED_GROUP_OPTION);
                }

            case '-':
            case 'i':
            case 'm':
            case 's':
            case 'x':
            case 'n':
            case 'p':
            case 'a':
            case 'd':
            case 'l':
            case 'u':
            case 'c':
            case 'g':
            case 'o':
                boolean neg = false;
                int positiveXCount = 0;
                PerlCharsetOptionState charsetOptions = new PerlCharsetOptionState();
                boolean sawContinueModifier = false;
                while (true) {
                    switch(c) {
                    case ':':
                    case ')':
                        break;
                    case '-':
                        neg = true;
                        break;
                    case 'x':
                        if (neg) {
                            option = bsOnOff(option, Option.EXTEND, true);
                            option = bsOnOff(option, Option.PERL_EXTEND_MORE, true);
                        } else {
                            positiveXCount++;
                            option = bsOnOff(option, Option.EXTEND, false);
                            option = bsOnOff(option, Option.PERL_EXTEND_MORE,
                                    positiveXCount < 2);
                        }
                        break;
                    case 'i':
                        option = bsOnOff(option, Option.IGNORECASE, neg);
                        break;
                    case 'n':
                        if (syntax.op2OptionPerl()) {
                            option = bsOnOff(option, Option.DONT_CAPTURE_GROUP, neg);
                            option = bsOnOff(option, Option.CAPTURE_GROUP, !neg);
                        } else {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        break;
                    case 's':
                        if (syntax.op2OptionPerl()) {
                            option = bsOnOff(option, Option.MULTILINE, neg);
                        } else {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        break;
                    case 'm':
                        if (syntax.op2OptionPerl()) {
                            option = bsOnOff(option, Option.SINGLELINE, !neg);
                        } else if (syntax.op2OptionRuby()) {
                            option = bsOnOff(option, Option.MULTILINE, neg);
                        } else {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        break;
                    case 'p':
                        // Perl's /p controls ${^PREMATCH}, ${^MATCH}, and
                        // ${^POSTMATCH} retention in the host runtime. It has
                        // no matcher option bit, but is valid inline syntax.
                        if (!syntax.op2OptionPerl()) {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        break;

                    case 'c':
                        if (!syntax.op2OptionPerl()) {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        env.warnings.warn("Useless (?" + (neg ? "-" : "") + "c)",
                                p - getBegin());
                        sawContinueModifier = true;
                        break;

                    case 'g':
                    case 'o':
                        if (!syntax.op2OptionPerl()) {
                            newSyntaxException(UNDEFINED_GROUP_OPTION);
                        }
                        if (c != 'g' || !sawContinueModifier) {
                            env.warnings.warn("Useless (?" + (neg ? "-" : "")
                                    + (char)c + ")", p - getBegin());
                        }
                        break;

                    case 'a':     /* limits \d, \s, \w and POSIX brackets to ASCII range */
                        option = charsetOptions.apply(option, c, neg, false);
                        break;
                    case 'u':
                        option = charsetOptions.apply(option, c, neg, false);
                        break;

                    case 'd':
                        if (syntax.op2OptionRuby() && !syntax.op2OptionPerl() && !neg) {
                            option = bsOnOff(option, Option.ASCII_RANGE, false);
                            option = bsOnOff(option, Option.POSIX_BRACKET_ALL_RANGE, false);
                            option = bsOnOff(option, Option.WORD_BOUND_ALL_RANGE, false);
                        } else {
                            option = charsetOptions.apply(option, c, neg, false);
                        }
                        break;

                    case 'l':
                        if (neg && syntax.op2OptionPerl()) {
                            newSyntaxException(PERL_LOCALE_MODIFIER_AFTER_MINUS);
                        }
                        option = charsetOptions.apply(option, c, neg, false);
                        break;
                    default:
                        newSyntaxException(UNDEFINED_GROUP_OPTION);
                    } // switch

                    if (c == ')') {
                        if (Option.isDynamic(env.option ^ option)) {
                            regex.hasDynamicOptions = true;
                        }
                        node = EncloseNode.newOption(option);
                        returnCode = 2; /* option only */
                        return node;
                    } else if (c == ':') {
                        int prev = env.option;
                        if (Option.isDynamic(prev ^ option)) {
                            regex.hasDynamicOptions = true;
                        }
                        env.option = option;
                        fetchToken();
                        Node target = parseSubExp(term);
                        env.option = prev;
                        EncloseNode en = EncloseNode.newOption(option);
                        en.setTarget(target);
                        node = en;
                        returnCode = 0;
                        return node;
                    }
                    if (!left()) {
                        newSyntaxException(syntax.op2OptionPerl()
                                ? PERL_OPTION_GROUP_NOT_TERMINATED
                                : END_PATTERN_IN_GROUP);
                    }
                    fetch();
                } // while

            default:
                if (syntax.op2OptionPerl() && c > 0x7f) {
                    newValueException(PERL_GROUP_SEQUENCE_NOT_RECOGNIZED,
                            new String(Character.toChars(c)));
                }
                newSyntaxException(UNDEFINED_GROUP_OPTION);
            } // switch

        } else {
            if (isDontCaptureGroup(env.option)) {
                fetchToken(); // goto group
                node = parseSubExp(term);
                returnCode = 1; /* group */
                return node;
            }
            EncloseNode en = EncloseNode.newMemory(env.option, false);
            en.regNum = env.addMemEntry();
            node = en;
        }

        if (node instanceof EncloseNode en && en.type == EncloseType.MEMORY) {
            setLexicalMemNode(en);
        }

        fetchToken();
        Node target;
        try {
            target = parseSubExp(term);
        } catch (SyntaxException e) {
            if (node instanceof EncloseNode en && en.type == EncloseType.CONDITION
                    && END_PATTERN_WITH_UNMATCHED_PARENTHESIS.equals(e.getMessage())) {
                newSyntaxException(PERL_SWITCH_CONDITION_NOT_TERMINATED);
            }
            if (node instanceof AnchorNode anchor
                    && END_PATTERN_WITH_UNMATCHED_PARENTHESIS.equals(e.getMessage())) {
                String diagnostic = switch (anchor.type) {
                case AnchorType.LOOK_BEHIND -> PERL_POSITIVE_LOOKBEHIND_NOT_TERMINATED;
                case AnchorType.LOOK_BEHIND_NOT -> PERL_NEGATIVE_LOOKBEHIND_NOT_TERMINATED;
                case AnchorType.PREC_READ -> PERL_POSITIVE_LOOKAHEAD_NOT_TERMINATED;
                case AnchorType.PREC_READ_NOT -> PERL_NEGATIVE_LOOKAHEAD_NOT_TERMINATED;
                default -> null;
                };
                if (diagnostic != null) newSyntaxException(diagnostic);
            }
            throw e;
        }

        if (node.getType() == NodeType.ANCHOR) {
            AnchorNode an = (AnchorNode)node;
            an.setTarget(target);
            if (syntax.op3OptionECMAScript() && an.type == AnchorType.PREC_READ_NOT) {
                env.popPrecReadNotNode(an);
            }
        } else {
            EncloseNode en = (EncloseNode)node;
            if (en.type == EncloseType.DEFINE && target.getType() == NodeType.ALT) {
                newSyntaxException(PERL_DEFINE_DOES_NOT_ALLOW_BRANCHES);
            }
            if (en.type == EncloseType.CONDITION && target instanceof ListNode
                    && target.getType() == NodeType.ALT
                    && ((ListNode)target).tail != null
                    && ((ListNode)target).tail.tail != null) {
                if (syntax.op2OptionPerl()) {
                    newSyntaxException(PERL_SWITCH_CONDITION_TOO_MANY_BRANCHES,
                            perlConditionalThirdBranchPosition(
                                    conditionalBodyStart, p));
                }
                newSyntaxException(INVALID_CONDITION_PATTERN);
            }
            en.setTarget(target);
            if (en.type == EncloseType.MEMORY) {
                if (syntax.op3OptionECMAScript()) {
                    en.containingAnchor = env.currentPrecReadNotNode();
                }
                /* Don't move this to previous of parse_subexp() */
                env.setMemNode(en.regNum, en);
            } else if (en.type == EncloseType.CONDITION) {
                if (target.getType() != NodeType.ALT) { /* convert (?(cond)yes) to (?(cond)yes|empty) */
                    en.setTarget(ListNode.newAlt(target, ListNode.newAlt(StringNode.EMPTY, null)));
                }
            }
        }
        returnCode = 0;
        return node; // ??
    }

    private Node parseInternalCallout() {
        final String dynamicPrefix = "=DYNAMIC:";
        boolean dynamic = startsWith(dynamicPrefix);
        return new CalloutNode(parseInternalCalloutId(dynamic ? dynamicPrefix : "=CALL:"), dynamic);
    }

    private Node parseControlVerb() {
        Node alphaAssertion = parseAlphaAssertion();
        if (alphaAssertion != null) return alphaAssertion;

        String missingScriptRunColon = missingControlArgumentDelimiter("script_run");
        if (missingScriptRunColon != null) {
            newSyntaxException(PERL_ALPHA_ASSERTION_REQUIRES_COLON.replace("%n", missingScriptRunColon),
                    p + missingScriptRunColon.length() - getBegin());
        }
        String missingShortScriptRunColon = missingControlArgumentDelimiter("sr");
        if (missingShortScriptRunColon != null) {
            newSyntaxException(PERL_ALPHA_ASSERTION_REQUIRES_COLON.replace("%n", missingShortScriptRunColon),
                    p + missingShortScriptRunColon.length() - getBegin());
        }

        // Script-run groups are scoped, non-capturing constructs.  Parse their
        // body with the same delimiter discipline as the other Perl (*name:)
        // groups; native validation is added by the matcher path.
        boolean scriptRun = startsWith("script_run:") || startsWith("sr:");
        boolean atomicScriptRun = startsWith("atomic_script_run:") || startsWith("asr:");
        if (scriptRun || atomicScriptRun) {
            if (startsWith("script_run:")) p += "script_run:".length();
            else if (startsWith("atomic_script_run:")) p += "atomic_script_run:".length();
            else p += scriptRun ? "sr:".length() : "asr:".length();
            fetchToken();
            try {
                Node target = parseSubExp(TokenType.SUBEXP_CLOSE);
                // Keep a recognised enclosure type so this scoped feature can
                // own native bytecode without extending the shared analyser.
                EncloseNode scope = new EncloseNode(EncloseType.STOP_BACKTRACK);
                scope.scriptRun = true;
                scope.atomicScriptRun = atomicScriptRun;
                scope.setTarget(target);
                return scope;
            } catch (SyntaxException e) {
                if (END_PATTERN_WITH_UNMATCHED_PARENTHESIS.equals(e.getMessage())) {
                    newSyntaxException(PERL_UNTERMINATED_CONTROL_ARGUMENT);
                }
                throw e;
            }
        }

        final ControlVerbNode.Kind kind;
        final String verb;
        if (startsWith("ACCEPT)") || startsWith("ACCEPT:")) {
            kind = ControlVerbNode.Kind.ACCEPT;
            verb = "ACCEPT";
        } else if (startsWith("FAIL)") || startsWith("FAIL:")) {
            kind = ControlVerbNode.Kind.FAIL;
            verb = "FAIL";
        } else if (startsWith("F)") || startsWith("F:")) {
            kind = ControlVerbNode.Kind.FAIL;
            verb = "F";
        } else if (startsWith("PRUNE)") || startsWith("PRUNE:")) {
            kind = ControlVerbNode.Kind.PRUNE;
            verb = "PRUNE";
        } else if (startsWith("SKIP)") || startsWith("SKIP:")) {
            kind = ControlVerbNode.Kind.SKIP;
            verb = "SKIP";
        } else if (startsWith("THEN)") || startsWith("THEN:")) {
            kind = ControlVerbNode.Kind.THEN;
            verb = "THEN";
        } else if (startsWith("COMMIT)") || startsWith("COMMIT:")) {
            kind = ControlVerbNode.Kind.COMMIT;
            verb = "COMMIT";
        } else if (startsWith("MARK)") || startsWith("MARK:")) {
            kind = ControlVerbNode.Kind.MARK;
            verb = "MARK";
        } else if (startsWith(":")) {
            kind = ControlVerbNode.Kind.MARK;
            verb = "";
        } else {
            String construct = controlConstructName();
            int constructEnd = p + construct.getBytes(StandardCharsets.UTF_8).length;
            int diagnosticEnd = constructEnd;
            if (constructEnd < stop && enc.mbcToCode(bytes, constructEnd, stop) == ')') {
                diagnosticEnd += enc.length(bytes, constructEnd, stop);
            }
            if (construct.isEmpty()) {
                newValueException(PERL_UNKNOWN_VERB_PATTERN, construct);
            }
            if (Character.isUpperCase(construct.codePointAt(0))) {
                newSyntaxException(PERL_UNKNOWN_VERB_PATTERN.replace("%n", construct),
                        diagnosticEnd - getBegin());
            }
            newSyntaxException(PERL_UNKNOWN_CONTROL_CONSTRUCT.replace("%n", construct),
                    diagnosticEnd - getBegin());
            return null;
        }
        p += verb.length();
        String name = null;
        if (left() && peekIs(':')) {
            inc();
            int nameStart = p;
            while (left() && !peekIs(')')) inc();
            if (!left() || p == nameStart) newSyntaxException(UNDEFINED_GROUP_OPTION);
            name = new String(bytes, nameStart, p - nameStart, StandardCharsets.UTF_8);
        }
        if (!left() || !peekIs(')')) newSyntaxException(END_PATTERN_IN_GROUP);
        inc();
        returnCode = 0;
        env.hasControlVerb = true;
        return new ControlVerbNode(kind, name);
    }

    private Node parseAlphaAssertion() {
        int nameStart = p;
        int cursor = p;
        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (!Character.isLetter(code) && code != '_') break;
            cursor += enc.length(bytes, cursor, stop);
        }
        String name = new String(bytes, nameStart, cursor - nameStart, StandardCharsets.UTF_8);
        if (!name.equals("pla") && !name.equals("positive_lookahead")
                && !name.equals("plb") && !name.equals("positive_lookbehind")
                && !name.equals("nla") && !name.equals("negative_lookahead")
                && !name.equals("nlb") && !name.equals("negative_lookbehind")
                && !name.equals("atomic")) {
            return null;
        }
        if (cursor >= stop || enc.mbcToCode(bytes, cursor, stop) != ':') {
            newSyntaxException(PERL_ALPHA_ASSERTION_REQUIRES_COLON.replace("%n", name),
                    cursor - getBegin());
        }
        p = cursor + enc.length(bytes, cursor, stop);

        Node node;
        switch (name) {
        case "pla", "positive_lookahead":
            node = new AnchorNode(AnchorType.PREC_READ);
            break;
        case "plb", "positive_lookbehind":
            node = new AnchorNode(AnchorType.LOOK_BEHIND);
            break;
        case "nla", "negative_lookahead":
            node = new AnchorNode(AnchorType.PREC_READ_NOT);
            break;
        case "nlb", "negative_lookbehind":
            node = new AnchorNode(AnchorType.LOOK_BEHIND_NOT);
            break;
        default:
            node = new EncloseNode(EncloseType.STOP_BACKTRACK);
            break;
        }

        fetchToken();
        final Node target;
        try {
            target = parseSubExp(TokenType.SUBEXP_CLOSE);
        } catch (SyntaxException e) {
            if (END_PATTERN_WITH_UNMATCHED_PARENTHESIS.equals(e.getMessage())) {
                newSyntaxException(PERL_UNTERMINATED_CONTROL_ARGUMENT);
            }
            throw e;
        }
        if (node.getType() == NodeType.ANCHOR) {
            ((AnchorNode)node).setTarget(target);
        } else {
            ((EncloseNode)node).setTarget(target);
        }
        returnCode = 0;
        return node;
    }

    private String controlConstructName() {
        int start = p;
        int cursor = p;
        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (code == ':' || code == ')') break;
            cursor += enc.length(bytes, cursor, stop);
        }
        return new String(bytes, start, cursor - start, StandardCharsets.UTF_8);
    }

    private String missingControlArgumentDelimiter(String name) {
        if (!startsWith(name)) return null;
        int afterName = p + name.length();
        if (afterName >= stop) {
            newSyntaxException(PERL_UNTERMINATED_CONTROL_CONSTRUCT,
                    afterName - getBegin());
        }
        return enc.mbcToCode(bytes, afterName, stop) == ')' ? name : null;
    }

    private int parseInternalCalloutId() {
        return parseInternalCalloutId("=CALL:");
    }

    private int parseInternalCalloutId(String prefix) {
        env.hasCallout = true;
        for (int i = 0; i < prefix.length(); i++) {
            if (!left()) newSyntaxException(END_PATTERN_IN_GROUP);
            fetch();
            if (c != prefix.charAt(i)) newSyntaxException(UNDEFINED_GROUP_OPTION);
        }

        if (!left() || !enc.isDigit(peek())) newSyntaxException(UNDEFINED_GROUP_OPTION);
        fetch();
        int calloutId = Encoding.digitVal(c);
        while (left() && enc.isDigit(peek())) {
            fetch();
            int digit = Encoding.digitVal(c);
            if (calloutId > (Integer.MAX_VALUE - digit) / 10) {
                newValueException("callout id is too large");
            }
            calloutId = calloutId * 10 + digit;
        }

        if (!left()) newSyntaxException(END_PATTERN_IN_GROUP);
        fetch();
        if (c != '}') newSyntaxException(UNDEFINED_GROUP_OPTION);
        if (!left()) newSyntaxException(END_PATTERN_WITH_UNMATCHED_PARENTHESIS);
        fetch();
        if (c != ')') newSyntaxException(UNMATCHED_CLOSE_PARENTHESIS);

        returnCode = 0;
        return calloutId;
    }

    private boolean startsWith(String value) {
        if (stop - p < value.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (bytes[p + i] != (byte)value.charAt(i)) return false;
        }
        return true;
    }

    private Node parseEncloseNamedGroup2(boolean listCapture) {
        if (syntax.op2OptionPerl() && left()) {
            int first = enc.mbcToCode(bytes, p, stop);
            if (!isPerlGroupNameStart(first)) {
                newSyntaxException(PERL_GROUP_NAME_MUST_START_WITH_WORD,
                        p + enc.length(bytes, p, stop) - getBegin());
            }
        }
        int nm = p;
        fetchName(c, false);
        int nameEnd = value;
        int num = env.addMemEntry();
        if (listCapture && num >= BitStatus.BIT_STATUS_BITS_NUM) newValueException(GROUP_NUMBER_OVER_FOR_CAPTURE_HISTORY);

        EncloseNode en = EncloseNode.newMemory(env.option, true);
        en.physicalNamedCaptureId = regex.nameAdd(bytes, nm, nameEnd, num, syntax);
        en.regNum = num;

        if (listCapture) env.captureHistory = bsOnAtSimple(env.captureHistory, num);
        env.numNamed++;
        return en;
    }

    private boolean hasCodePointAhead(int target) {
        int cursor = p;
        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (code == target) return true;
            cursor += enc.length(bytes, cursor, stop);
        }
        return false;
    }

    private int nextClosingParenthesisPosition() {
        int cursor = p;
        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (code == ')') return cursor - getBegin();
            cursor += enc.length(bytes, cursor, stop);
        }
        return stop - getBegin();
    }

    private int findStrPosition(int[]s, int n, int from, int to, Ptr nextChar) {
        int x;
        int q;
        int p = from;
        int i;
        while (p < to) {
            x = enc.mbcToCode(bytes, p, to);
            q = p + enc.length(bytes, p, to);
            if (x == s[0]) {
                for (i=1; i<n && q<to; i++) {
                    x = enc.mbcToCode(bytes, q, to);
                    if (x != s[i]) break;
                    q += enc.length(bytes, q, to);
                }
                if (i >= n) {
                    if (bytes[nextChar.p] != 0) nextChar.p = q; // we may need zero term semantics...
                    return p;
                }
            }
            p = q;
        }
        return -1;
    }

    private Node parseExp(TokenType term) {
        if (token.type == term) return StringNode.EMPTY; // goto end_of_token
        int expressionStart = token.backP - (token.escaped ? 1 : 0);
        Node node = null;
        boolean group = false;

        switch(token.type) {
        case ALT:
        case EOT:
            return StringNode.EMPTY; // end_of_token:, node_new_empty

        case SUBEXP_OPEN:
            node = parseEnclose(TokenType.SUBEXP_CLOSE);
            if (returnCode == 1) {
                group = true;
            } else if (returnCode == 2) { /* option only */
                int prev = env.option;
                EncloseNode en = (EncloseNode)node;
                env.option = en.option;
                fetchToken();
                Node target = parseSubExp(term);
                env.option = prev;
                en.setTarget(target);
                return node;
            }
            break;
        case SUBEXP_CLOSE:
            if (!syntax.allowUnmatchedCloseSubexp()) newSyntaxException(UNMATCHED_CLOSE_PARENTHESIS);
            if (token.escaped) {
                return parseExpTkRawByte(group); // goto tk_raw_byte
            } else {
                return parseExpTkByte(group); // goto tk_byte
            }
        case LINEBREAK:
            node = parseLineBreak();
            break;

        case EXTENDED_GRAPHEME_CLUSTER:
            node = parseExtendedGraphemeCluster();
            break;

        case KEEP:
            node = new AnchorNode(AnchorType.KEEP);
            break;

        case STRING:
            return parseExpTkByte(group); // tk_byte:

        case RAW_BYTE:
            return parseExpTkRawByte(group); // tk_raw_byte:

        case CODE_POINT:
            return parseStringLoop(StringNode.fromCodePoint(token.getCode(), enc), group);

        case NAMED_STRING:
            node = namedCharacterStringNode(token.getNamedCharacterSequence());
            fetchToken();
            return parseExpRepeat(node, true);

        case WIDE_CODE_POINT: {
            WideScalarNode wide = new WideScalarNode(token.getWideCode(),
                    syntax.wideScalarCodec.encode(token.getWideCode(), enc));
            fetchToken();
            return parseExpRepeat(wide, group);
        }

        case QUOTE_OPEN:
            node = parseQuoteOpen();
            break;

        case CHAR_TYPE:
            node = parseCharType(node);
            break;

        case CHAR_PROPERTY:
            node = parseCharProperty();
            break;

        case CC_OPEN: {
            ObjPtr<CClassNode> ascPtr = new ObjPtr<>();
            ObjPtr<CClassNode> foldPtr = new ObjPtr<>();
            ParsedCharClass parsedClass = parseCharClass(ascPtr, foldPtr);
            CClassNode cc = parsedClass.standard();
            int code = cc.isOneChar();
            if (parsedClass.namedSequences().isEmpty()
                    && code != -1 && (!isIgnoreCase(env.option)
                    || ApplyCaseFold.isEligible(foldPtr.p, enc, code))) {
                return parseStringLoop(StringNode.fromCodePoint(code, enc), group);
            }

            node = cc;
            if (isIgnoreCase(env.option)) {
                node = cClassCaseFold(node, cc, ascPtr.p, foldPtr.p);
            }
            node = addNamedCharacterClassAlternatives(
                    node, cc, parsedClass.namedSequences());
            break;
            }

        case EXTENDED_CC_OPEN:
            node = parsePerlExtendedCharClass();
            break;

        case ANYCHAR:
            node = new AnyCharNode();
            break;

        case ANYCHAR_ANYTIME:
            node = parseAnycharAnytime();
            break;

        case BACKREF:
            node = parseBackref();
            break;

        case CALL:
            if (Config.USE_SUBEXP_CALL) node = parseCall();
            break;

        case ANCHOR:
            node = new AnchorNode(token.getAnchorSubtype(), token.getAnchorASCIIRange());
            break;

        case OP_REPEAT:
        case INTERVAL:
            if (syntax.contextIndepRepeatOps()) {
                if (syntax.contextInvalidRepeatOps()) {
                    newSyntaxException(env.usesPerlDiagnostics()
                            ? PERL_QUANTIFIER_FOLLOWS_NOTHING
                            : TARGET_OF_REPEAT_OPERATOR_NOT_SPECIFIED);
                } else {
                    node = StringNode.EMPTY; // node_new_empty
                }
            } else {
                return parseExpTkByte(group); // goto tk_byte
            }
            break;

        default:
            newInternalException(PARSER_BUG);
        } //switch

        //targetp = node;

        fetchToken(); // re_entry:

        return parseExpRepeat(node, group, expressionStart); // repeat:
    }

    private CClassNode parsePerlExtendedCharClass() {
        boolean previousExtendedClass = env.inPerlExtendedClass;
        env.inPerlExtendedClass = true;
        try {
            int bodyStart = p;
            skipPerlExtendedClassSpace();
            if (!left() || extendedClassAt(']')) {
                newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);
            }
            CClassNode result;
            try {
                result = parsePerlExtendedClassUnion();
            } catch (SyntaxException error) {
                if (extendedClassBodyStartsWithControlCloseGroup(bodyStart)
                        && PERL_EXTENDED_CLASS_UNEXPECTED_CHARACTER.equals(
                                error.getMessage())) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX,
                            stop - getBegin());
                }
                throw error;
            }
            skipPerlExtendedClassSpace();
            if (!left() || !extendedClassAt(']')) {
                if (extendedClassBodyStartsWithControlCloseGroup(bodyStart)
                        || extendedClassBodyStartsWithSpacedStandardClass(bodyStart)
                        || extendedClassBodyStartsWithPosixLeaf(bodyStart)) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX,
                            stop - getBegin());
                }
                newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);
            }
            inc();
            if (!left() || !extendedClassAt(')')) {
                if (extendedClassBodyStartsWithSpacedStandardClass(bodyStart)) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
                }
                newSyntaxException(PERL_EXTENDED_CLASS_UNEXPECTED_OUTER_CLOSE);
            }
            inc();
            return result;
        } finally {
            env.inPerlExtendedClass = previousExtendedClass;
        }
    }

    private CClassNode parsePerlExtendedClassUnion() {
        CClassNode left = parsePerlExtendedClassIntersection(false);
        while (true) {
            skipPerlExtendedClassSpace();
            if (!left()) return left;
            int operator = extendedClassCode();
            if (operator != '+' && operator != '|' && operator != '-'
                    && operator != '^') {
                if (operator == ']' || operator == ')') return left;
                if (operator == '(') {
                    inc();
                    newSyntaxException(PERL_EXTENDED_CLASS_UNEXPECTED_OPEN_PAREN);
                }
                if (extendedClassStarts("\\]")) {
                    p += 2;
                    newSyntaxException(PERL_EXTENDED_CLASS_UNEXPECTED_OUTER_CLOSE,
                            p - getBegin());
                }
                int operandStart = p;
                parsePerlExtendedClassIntersection(false);
                newSyntaxException(PERL_EXTENDED_CLASS_OPERAND_WITHOUT_OPERATOR,
                        perlExtendedUnexpectedOperandPosition(operandStart, p));
            }
            inc();
            CClassNode right = parsePerlExtendedClassIntersection(true);
            switch (operator) {
            case '+':
            case '|':
                left.or(right, env);
                break;
            case '-':
                right.setNot();
                left.and(right, env);
                break;
            case '^':
                CClassNode onlyLeft = left.copy();
                CClassNode onlyRight = right.copy();
                right.setNot();
                onlyLeft.and(right, env);
                left.setNot();
                onlyRight.and(left, env);
                onlyLeft.or(onlyRight, env);
                left = onlyLeft;
                break;
            default:
                throw new AssertionError(operator);
            }
        }
    }

    private CClassNode parsePerlExtendedClassIntersection(boolean afterOperator) {
        CClassNode left = parsePerlExtendedClassUnary(afterOperator);
        while (true) {
            skipPerlExtendedClassSpace();
            if (!left() || !extendedClassAt('&')) return left;
            inc();
            left.and(parsePerlExtendedClassUnary(true), env);
        }
    }

    private CClassNode parsePerlExtendedClassUnary(boolean afterOperator) {
        skipPerlExtendedClassSpace();
        if (!left()) newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);
        if (extendedClassAt('!')) {
            inc();
            CClassNode result = parsePerlExtendedClassUnary(true);
            if (result.isNot()) result.clearNot();
            else result.setNot();
            return result;
        }
        PerlExtendedClassPrimary primary = parsePerlExtendedClassPrimary(afterOperator);
        CClassNode result = primary.node();
        if (!primary.scopedOptionsApplied() && isIgnoreCase(env.option)) {
            caseFoldPerlExtendedClass(primary);
        }
        return result;
    }

    private void caseFoldPerlExtendedClass(PerlExtendedClassPrimary primary) {
        int previousOption = env.option;
        env.option &= ~Option.PERL_BYTE_PATTERN;
        try {
            cClassCaseFold(primary.node(), primary.node(),
                    primary.asciiFoldSource(), primary.foldSource());
        } finally {
            env.option = previousOption;
        }
    }

    private PerlExtendedClassPrimary parsePerlExtendedClassPrimary(boolean afterOperator) {
        skipPerlExtendedClassSpace();
        if (!left()) newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);

        int codePoint = extendedClassCode();
        if (codePoint == '+' || codePoint == '|' || codePoint == '-'
                || codePoint == '^' || codePoint == '&') {
            inc();
            newSyntaxException(String.format(
                    PERL_EXTENDED_CLASS_BINARY_OPERATOR_WITHOUT_OPERAND,
                    (char) codePoint));
        }
        if (codePoint == ')') {
            inc();
            newSyntaxException(PERL_EXTENDED_CLASS_UNEXPECTED_CLOSE_PAREN);
        }

        if (extendedClassStarts("(?[")) {
            p += 3;
            return new PerlExtendedClassPrimary(parsePerlExtendedCharClass(), true);
        }
        if (extendedClassStarts("(?")) {
            p += 2;
            int previousOption = env.option;
            int nestedOption = previousOption;
            boolean caretOptions = false;
            if (left() && extendedClassAt('^')) {
                caretOptions = true;
                nestedOption = bsOnOff(nestedOption, Option.ASCII_RANGE, true);
                nestedOption = bsOnOff(nestedOption, Option.IGNORECASE, true);
                nestedOption = bsOnOff(nestedOption, Option.SINGLELINE, false);
                nestedOption = bsOnOff(nestedOption, Option.MULTILINE, true);
                nestedOption = bsOnOff(nestedOption, Option.EXTEND, true);
                nestedOption = bsOnOff(nestedOption, Option.PERL_EXTEND_MORE, true);
                nestedOption = bsOnOff(nestedOption, Option.DONT_CAPTURE_GROUP, true);
                nestedOption = bsOnOff(nestedOption, Option.CAPTURE_GROUP, false);
                nestedOption = bsOnOff(nestedOption, Option.PERL_ASCII_STRICT, true);
                nestedOption = bsOnOff(
                        nestedOption, Option.PERL_EXPLICIT_ASCII, true);
                inc();
            }
            boolean negateOption = false;
            int positiveXCount = 0;
            PerlCharsetOptionState charsetOptions = new PerlCharsetOptionState();
            while (left() && !extendedClassAt(':')) {
                int option = extendedClassCode();
                if (option != 'a' && option != 'd' && option != 'i'
                        && option != 'l' && option != 'm' && option != 'n'
                        && option != 'p' && option != 's' && option != 'u'
                        && option != 'x' && option != '-') {
                    if (option == '(' && caretOptions) {
                        newSyntaxException(
                                PERL_CARET_GROUP_SEQUENCE_NOT_RECOGNIZED,
                                p + enc.length(bytes, p, stop) - getBegin());
                    }
                    newSyntaxException(option == '('
                            ? PERL_EXTENDED_CLASS_UNEXPECTED_CHARACTER
                            : PERL_EXTENDED_CLASS_SYNTAX);
                }
                if (option == '-') negateOption = true;
                else if (option == 'i') {
                    nestedOption = bsOnOff(nestedOption, Option.IGNORECASE, negateOption);
                } else if (option == 'x') {
                    if (negateOption) {
                        nestedOption = bsOnOff(nestedOption, Option.EXTEND, true);
                        nestedOption = bsOnOff(
                                nestedOption, Option.PERL_EXTEND_MORE, true);
                    } else {
                        positiveXCount++;
                        nestedOption = bsOnOff(nestedOption, Option.EXTEND, false);
                        nestedOption = bsOnOff(nestedOption,
                                Option.PERL_EXTEND_MORE, positiveXCount < 2);
                    }
                } else if (option == 'a' || option == 'd'
                        || option == 'l' || option == 'u') {
                    nestedOption = charsetOptions.apply(
                            nestedOption, option, negateOption, true);
                }
                inc();
            }
            if (!left()) newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
            inc();
            env.option = nestedOption;
            CClassNode nested;
            try {
                skipPerlExtendedClassSpace();
                if (!extendedClassStarts("(?[")) {
                    newSyntaxException(PERL_EXTENDED_CLASS_EXPECTING_INTERPOLATED);
                }
                p += 3;
                nested = parsePerlExtendedCharClass();
                skipPerlExtendedClassSpace();
                if (!left() || !extendedClassAt(')')) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
                }
                inc();
            } finally {
                env.option = previousOption;
            }
            return new PerlExtendedClassPrimary(nested, true);
        }
        if (extendedClassAt('(')) {
            inc();
            skipPerlExtendedClassSpace();
            if (left() && extendedClassAt(')')) {
                inc();
                newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);
            }
            CClassNode nested = parsePerlExtendedClassUnion();
            skipPerlExtendedClassSpace();
            if (!left() || !extendedClassAt(')')) {
                newSyntaxException(PERL_UNMATCHED_OPEN_PARENTHESIS,
                        p - getBegin());
            }
            inc();
            return new PerlExtendedClassPrimary(nested, true);
        }
        if (extendedClassStarts("[:") && extendedPosixPrimaryUsesParser()) {
            // In (?[...]), a POSIX bracket is itself a primary: [:alpha:]
            // is not the nested standard-class spelling [[:alpha:]].
            p += 2;
            int literalNameStart = p;
            CClassNode result = new CClassNode();
            int literalNameEnd = p;
            while (literalNameEnd < stop
                    && enc.mbcToCode(bytes, literalNameEnd, stop) != ']') {
                literalNameEnd += enc.length(bytes, literalNameEnd, stop);
            }
            if (literalNameEnd >= stop) {
                newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
            }
            int previous = enc.prevCharHead(bytes, literalNameStart,
                    literalNameEnd, stop);
            if (previous < literalNameStart
                    || enc.mbcToCode(bytes, previous, stop) != ':') {
                addPerlLiteralPosixText(result, null, null,
                        literalNameStart, literalNameEnd);
                p = literalNameEnd + enc.length(bytes, literalNameEnd, stop);
                return new PerlExtendedClassPrimary(result, false);
            }
            if (parsePosixBracket(result, null, null)) {
                literalNameEnd = p;
                while (literalNameEnd < stop
                        && enc.mbcToCode(bytes, literalNameEnd, stop) != ']') {
                    literalNameEnd += enc.length(bytes, literalNameEnd, stop);
                }
                if (literalNameEnd >= stop) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
                }
                addPerlLiteralPosixText(result, null, null,
                        literalNameStart, literalNameEnd);
                p = literalNameEnd + enc.length(bytes, literalNameEnd, stop);
            }
            return new PerlExtendedClassPrimary(result, false);
        }
        if (extendedClassAt('[')) {
            inc();
            ObjPtr<CClassNode> ascPtr = new ObjPtr<>();
            ObjPtr<CClassNode> foldPtr = new ObjPtr<>();
            boolean previousExtendedLeaf = perlExtendedClassLeaf;
            int previousOption = env.option;
            perlExtendedClassLeaf = true;
            env.option |= Option.PERL_EXTEND_MORE;
            ParsedCharClass parsed;
            try {
                parsed = parseCharClass(ascPtr, foldPtr);
            } finally {
                env.option = previousOption;
                perlExtendedClassLeaf = previousExtendedLeaf;
            }
            if (!parsed.namedSequences().isEmpty()) {
                newSyntaxException(PERL_EXTENDED_CLASS_MULTI_NAMED_CHARACTER);
            }
            return new PerlExtendedClassPrimary(parsed.standard(),
                    ascPtr.p, foldPtr.p, false);
        }
        if (extendedClassAt('\\')) {
            return parsePerlExtendedClassEscape();
        }
        if (afterOperator) newSyntaxException(PERL_EXTENDED_CLASS_INCOMPLETE);
        newSyntaxException(PERL_EXTENDED_CLASS_UNEXPECTED_CHARACTER,
                p + enc.length(bytes, p, stop) - getBegin());
        return null;
    }

    private PerlExtendedClassPrimary parsePerlExtendedClassEscape() {
        int escapeStart = p;
        int escapeCode = -1;
        if (escapeStart < stop && enc.mbcToCode(bytes, escapeStart, stop) == '\\') {
            int cursor = escapeStart + enc.length(bytes, escapeStart, stop);
            if (cursor < stop) escapeCode = enc.mbcToCode(bytes, cursor, stop);
            if (cursor < stop && enc.mbcToCode(bytes, cursor, stop) == 'x') {
                cursor += enc.length(bytes, cursor, stop);
                if (cursor < stop && enc.mbcToCode(bytes, cursor, stop) == '{') {
                    int close = cursor + enc.length(bytes, cursor, stop);
                    if (close < stop && enc.mbcToCode(bytes, close, stop) == '}') {
                        close += enc.length(bytes, close, stop);
                        newSyntaxException(PERL_EMPTY_HEX_ESCAPE,
                                close - getBegin());
                    }
                } else {
                    int digitCount = 0;
                    while (cursor < stop && digitCount < 3
                            && enc.isXDigit(enc.mbcToCode(bytes, cursor, stop))) {
                        cursor += enc.length(bytes, cursor, stop);
                        digitCount++;
                    }
                    if (digitCount == 3) {
                        newSyntaxException(PERL_HEX_ESCAPE_MORE_THAN_TWO_DIGITS,
                                cursor - getBegin());
                    }
                }
            }
            cursor = escapeStart + enc.length(bytes, escapeStart, stop);
            if (cursor < stop && enc.mbcToCode(bytes, cursor, stop) == '0') {
                int digits = 0;
                while (cursor < stop) {
                    int digit = enc.mbcToCode(bytes, cursor, stop);
                    if (digit < '0' || digit > '7') break;
                    digits++;
                    cursor += enc.length(bytes, cursor, stop);
                }
                if (digits != 3) {
                    while (cursor < stop) {
                        int next = enc.mbcToCode(bytes, cursor, stop);
                        if (next != ' ' && next != '\t' && next != '\n'
                                && next != '\r' && next != '\f') break;
                        cursor += enc.length(bytes, cursor, stop);
                    }
                    newSyntaxException(PERL_EXTENDED_CLASS_OCTAL_WIDTH,
                            cursor - getBegin());
                }
            }
        }
        fetchToken();
        CClassNode result = new CClassNode();
        switch (token.type) {
        case CODE_POINT:
            addPerlExtendedClassCode(result, token.getCode());
            return new PerlExtendedClassPrimary(result, false);
        case WIDE_CODE_POINT:
            result.addWideScalarRange(token.getWideCode(), token.getWideCode());
            return new PerlExtendedClassPrimary(result, false);
        case RAW_BYTE:
        case STRING:
            if (token.escaped && Character.isLetterOrDigit(token.getC())) {
                newValueException(PERL_UNRECOGNIZED_ESCAPE_IN_CHAR_CLASS,
                        Character.toString(token.getC()));
            }
            addPerlExtendedClassCode(result, token.getC());
            return new PerlExtendedClassPrimary(result, false);
        case NAMED_STRING:
            int[] sequence = token.getNamedCharacterSequence();
            if (sequence.length == 0) {
                newSyntaxException(PERL_EXTENDED_CLASS_ZERO_LENGTH_NAMED_CHARACTER);
            }
            if (sequence.length != 1) {
                newSyntaxException(PERL_EXTENDED_CLASS_MULTI_NAMED_CHARACTER);
            }
            addPerlExtendedClassCode(result, sequence[0]);
            return new PerlExtendedClassPrimary(result, false);
        case CHAR_TYPE:
            result.addCType(token.getPropCType(), token.getPropNot(),
                    isAsciiRange(env.option), env, this);
            return new PerlExtendedClassPrimary(result, false);
        case CHAR_PROPERTY:
            CharProperty property = fetchCharProperty(false);
            addCharProperty(result, null, null, property, token.getPropNot());
            return new PerlExtendedClassPrimary(result, !property.caseFold);
        case CC_OPEN:
            ObjPtr<CClassNode> ascPtr = new ObjPtr<>();
            ObjPtr<CClassNode> foldPtr = new ObjPtr<>();
            ParsedCharClass parsed = parseCharClass(ascPtr, foldPtr);
            if (!parsed.namedSequences().isEmpty()) {
                newSyntaxException(PERL_EXTENDED_CLASS_MULTI_NAMED_CHARACTER);
            }
            return new PerlExtendedClassPrimary(parsed.standard(), false);
        default:
            if (Character.isLetterOrDigit(escapeCode)) {
                newValueException(PERL_UNRECOGNIZED_ESCAPE_IN_CHAR_CLASS,
                        Character.toString(escapeCode));
            }
            newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX);
            return null;
        }
    }

    private void addPerlExtendedClassCode(CClassNode cc, int codePoint) {
        int length = enc.codeToMbcLength(codePoint);
        if (codePoint < BitSet.SINGLE_BYTE_SIZE && length == 1) {
            cc.bs.set(env, codePoint);
        } else {
            cc.addCodeRange(env, codePoint, codePoint);
        }
    }

    private int perlExtendedUnexpectedOperandPosition(int start, int end) {
        int cursor = end;
        while (cursor > start) {
            int previous = enc.prevCharHead(bytes, start, cursor, end);
            int codePoint = enc.mbcToCode(bytes, previous, end);
            if (codePoint != ' ' && codePoint != '\t' && codePoint != '\n'
                    && codePoint != '\r' && codePoint != '\f') break;
            cursor = previous;
        }
        int first = start;
        while (first < cursor) {
            int codePoint = enc.mbcToCode(bytes, first, cursor);
            if (codePoint != ' ' && codePoint != '\t' && codePoint != '\n'
                    && codePoint != '\r' && codePoint != '\f') break;
            first += enc.length(bytes, first, cursor);
        }
        if (first < cursor && enc.mbcToCode(bytes, first, cursor) == '[') {
            int previous = enc.prevCharHead(bytes, first, cursor, cursor);
            if (enc.mbcToCode(bytes, previous, cursor) == ']') cursor = previous;
        }
        return cursor - getBegin();
    }

    private int perlConditionalThirdBranchPosition(int start, int end) {
        int depth = 0;
        boolean inClass = false;
        boolean escaped = false;
        int alternatives = 0;
        int cursor = Math.max(start, getBegin());
        while (cursor < end) {
            int codePoint = enc.mbcToCode(bytes, cursor, end);
            int next = cursor + enc.length(bytes, cursor, end);
            if (escaped) {
                escaped = false;
            } else if (codePoint == '\\') {
                escaped = true;
            } else if (inClass) {
                if (codePoint == ']') inClass = false;
            } else if (codePoint == '[') {
                inClass = true;
            } else if (codePoint == '(') {
                depth++;
            } else if (codePoint == ')' && depth > 0) {
                depth--;
            } else if (codePoint == '|' && depth == 0 && ++alternatives == 2) {
                return next - getBegin();
            }
            cursor = next;
        }
        return end - getBegin();
    }

    private void skipPerlExtendedClassSpace() {
        while (left()) {
            int codePoint = extendedClassCode();
            if (extendedClassStarts("(?#")) {
                p += 3;
                while (left() && !extendedClassAt(')')) {
                    p += enc.length(bytes, p, stop);
                }
                if (!left()) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX,
                            stop - getBegin());
                }
                p += enc.length(bytes, p, stop);
                continue;
            }
            if (codePoint == '#') {
                do {
                    int length = enc.length(bytes, p, stop);
                    p += length;
                } while (left() && !enc.isNewLine(extendedClassCode()));
                if (!left()) {
                    newSyntaxException(PERL_EXTENDED_CLASS_SYNTAX,
                            stop - getBegin());
                }
                continue;
            }
            if (!Character.isWhitespace(codePoint) && codePoint != 0x85) return;
            p += enc.length(bytes, p, stop);
        }
    }

    private boolean extendedClassBodyStartsWithSpacedStandardClass(int bodyStart) {
        int cursor = bodyStart;
        while (cursor < stop) {
            int codePoint = enc.mbcToCode(bytes, cursor, stop);
            if (codePoint != ' ' && codePoint != '\t' && codePoint != '\n'
                    && codePoint != '\r' && codePoint != '\f') {
                if (codePoint != '[') return false;
                cursor += enc.length(bytes, cursor, stop);
                if (cursor >= stop) return false;
                int firstLeafCodePoint = enc.mbcToCode(bytes, cursor, stop);
                return firstLeafCodePoint == ' ' || firstLeafCodePoint == '\t'
                        || firstLeafCodePoint == '\n' || firstLeafCodePoint == '\r'
                        || firstLeafCodePoint == '\f';
            }
            cursor += enc.length(bytes, cursor, stop);
        }
        return false;
    }

    private boolean extendedClassBodyStartsWithPosixLeaf(int bodyStart) {
        int cursor = bodyStart;
        while (cursor < stop) {
            int codePoint = enc.mbcToCode(bytes, cursor, stop);
            if (codePoint != ' ' && codePoint != '\t' && codePoint != '\n'
                    && codePoint != '\r' && codePoint != '\f') {
                if (codePoint != '[') return false;
                cursor += enc.length(bytes, cursor, stop);
                if (cursor >= stop || enc.mbcToCode(bytes, cursor, stop) != '[') {
                    return false;
                }
                cursor += enc.length(bytes, cursor, stop);
                return cursor < stop && enc.mbcToCode(bytes, cursor, stop) == ':';
            }
            cursor += enc.length(bytes, cursor, stop);
        }
        return false;
    }

    private boolean extendedClassBodyStartsWithControlCloseGroup(int bodyStart) {
        int cursor = bodyStart;
        while (cursor < stop && Character.isWhitespace(
                enc.mbcToCode(bytes, cursor, stop))) {
            cursor += enc.length(bytes, cursor, stop);
        }
        int[] expected = {'(', '\\', 'c', ']'};
        for (int codePoint : expected) {
            if (cursor >= stop
                    || enc.mbcToCode(bytes, cursor, stop) != codePoint) {
                return false;
            }
            cursor += enc.length(bytes, cursor, stop);
        }
        return true;
    }

    private boolean extendedClassAt(int codePoint) {
        return left() && extendedClassCode() == codePoint;
    }

    private int extendedClassCode() {
        return enc.mbcToCode(bytes, p, stop);
    }

    private boolean extendedClassStarts(String suffix) {
        int cursor = p;
        for (int i = 0; i < suffix.length(); i++) {
            if (cursor >= stop || enc.mbcToCode(bytes, cursor, stop) != suffix.charAt(i)) {
                return false;
            }
            cursor += enc.length(bytes, cursor, stop);
        }
        return true;
    }

    private Node parseLineBreak() {
        byte[]buflb = new byte[Config.ENC_CODE_TO_MBC_MAXLEN * 2];
        int len1 = enc.codeToMbc(0x0D, buflb, 0);
        int len2 = enc.codeToMbc(0x0A, buflb, len1);
        StringNode left = new StringNode(buflb, 0, len1 + len2);
        left.setRaw();
        /* [\x0A-\x0D] or [\x0A-\x0D\x{85}\x{2028}\x{2029}] */
        CClassNode right = new CClassNode();
        if (enc.minLength() > 1) {
            right.addCodeRange(env, 0x0A, 0x0D);
        } else {
            right.bs.setRange(env, 0x0A, 0x0D);
        }

        if (enc.isUnicode()) {
            /* UTF-8, UTF-16BE/LE, UTF-32BE/LE */
            right.addCodeRange(env, 0x85, 0x85);
            right.addCodeRange(env, 0x2028, 0x2029);
        }
        /* (?>...) */
        EncloseNode en = new EncloseNode(EncloseType.STOP_BACKTRACK);
        en.setTarget(ListNode.newAlt(left, ListNode.newAlt(right, null)));
        return en;
    }

    private void addPropertyToCC(CClassNode cc, UnicodeCodeRange range, boolean not) {
        cc.addCType(range.getCType(), not, false, env, this);
    }

    private void createPropertyNode(Node[]nodes, int np, UnicodeCodeRange range) {
        CClassNode cc = new CClassNode();
        addPropertyToCC(cc, range, false);
        nodes[np] = cc;
    }

    private void quantifierNode(Node[]nodes, int np, int lower, int upper) {
        QuantifierNode qnf = new QuantifierNode(lower, upper, false);
        qnf.setTarget(nodes[np]);
        nodes[np] = qnf;
    }

    private void quantifierPropertyNode(Node[]nodes, int np, UnicodeCodeRange range, char repetitions) {
        int lower = 0;
        int upper = QuantifierNode.REPEAT_INFINITE;

        createPropertyNode(nodes, np, range);
        switch (repetitions) {
            case '?':  upper = 1;          break;
            case '+':  lower = 1;          break;
            case '*':                      break;
            case '2':  lower = upper = 2;  break;
            default :  throw new InternalException(ErrorMessages.PARSER_BUG);
        }

        quantifierNode(nodes, np, lower, upper);
    }

    private void createNodeFromArray(boolean list, Node[] nodes, int np, int nodeArray) {
        int i = 0;
        ListNode tmp = null;
        while (nodes[nodeArray + i] != null) i++;
        while (--i >= 0) {
            nodes[np] = list ? ListNode.newList(nodes[nodeArray + i], tmp) : ListNode.newAlt(nodes[nodeArray + i], tmp);
            nodes[nodeArray + i] = null;
            tmp = (ListNode)nodes[np];
        }
    }

    private ListNode createNodeFromArray(Node[]nodes, int nodeArray) {
        int i = 0;
        ListNode np = null, tmp = null;
        while (nodes[nodeArray + i] != null) i++;
        while (--i >= 0) {
            np = ListNode.newAlt(nodes[nodeArray + i], tmp);
            nodes[nodeArray + i] = null;
            tmp = np;
        }
        return np;
    }

    private static final int NODE_COMMON_SIZE = 20;
    private Node parseExtendedGraphemeCluster() {
        final Node[] nodes = new Node[NODE_COMMON_SIZE];
        final int anyTargetPosition;
        int alts = 0;

        StringNode strNode = new StringNode(Config.ENC_CODE_TO_MBC_MAXLEN * 2);
        strNode.setRaw();
        strNode.catCode(0x0D, enc);
        strNode.catCode(0x0A, enc);
        nodes[alts] = strNode;

        if (Config.USE_UNICODE_PROPERTIES && enc.isUnicode()) {
            CClassNode cc;
            cc = new CClassNode();
            nodes[alts + 1] = cc;
            addPropertyToCC(cc, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CONTROL, false);
            if (enc.minLength() > 1) {
                cc.addCodeRange(env, 0x000A, 0x000A);
                cc.addCodeRange(env, 0x000D, 0x000D);
            } else {
                cc.bs.set(0x0A);
                cc.bs.set(0x0D);
            }

            {
                int list = alts + 3;
                quantifierPropertyNode(nodes, list + 0, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_PREPEND, '*');
                {
                    int coreAlts = list + 2;
                    {
                        int HList = coreAlts + 1;
                        quantifierPropertyNode(nodes, HList + 0, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_L, '*');
                        {
                            int HAlt2 = HList + 2;
                            quantifierPropertyNode(nodes, HAlt2 + 0, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_V, '+');
                            {
                                int HList2 = HAlt2 + 2;
                                createPropertyNode(nodes, HList2 + 0, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LV);
                                quantifierPropertyNode(nodes, HList2 + 1, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_V, '*');
                                createNodeFromArray(true, nodes, HAlt2 + 1, HList2);
                            }
                            createPropertyNode(nodes, HAlt2 + 2, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LVT);
                            createNodeFromArray(false, nodes, HList + 1, HAlt2);
                        }
                        quantifierPropertyNode(nodes, HList + 2, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_T, '*');
                        createNodeFromArray(true, nodes, coreAlts + 0, HList);
                    }
                    quantifierPropertyNode(nodes, coreAlts + 1, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_L, '+');
                    quantifierPropertyNode(nodes, coreAlts + 2, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_T, '+');
                    quantifierPropertyNode(nodes, coreAlts + 3, UnicodeCodeRange.REGIONALINDICATOR, '2');
                    {
                        int XPList = coreAlts + 5;
                        createPropertyNode(nodes, XPList + 0, UnicodeCodeRange.EXTENDEDPICTOGRAPHIC);
                        {
                            int ExList = XPList + 2;
                            quantifierPropertyNode(nodes, ExList + 0, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_EXTEND, '*');
                            strNode = new StringNode(Config.ENC_CODE_TO_MBC_MAXLEN);
                            strNode.setRaw();
                            strNode.catCode(0x200D, enc);
                            nodes[ExList + 1] = strNode;
                            createPropertyNode(nodes, ExList + 2, UnicodeCodeRange.EXTENDEDPICTOGRAPHIC);
                            createNodeFromArray(true, nodes, XPList + 1, ExList);
                        }
                        quantifierNode(nodes, XPList + 1, 0, QuantifierNode.REPEAT_INFINITE);
                        createNodeFromArray(true, nodes, coreAlts + 4, XPList);
                    }
                    {
                        int incbList = coreAlts + 8;
                        createPropertyNode(nodes, incbList + 0, UnicodeCodeRange.INCBCONSONANT);
                        int conjunctTail = incbList + 2;
                        createPropertyNode(nodes, conjunctTail + 0, UnicodeCodeRange.INCBEXTEND);
                        quantifierNode(nodes, conjunctTail + 0, 0, QuantifierNode.REPEAT_INFINITE);
                        createPropertyNode(nodes, conjunctTail + 1, UnicodeCodeRange.INCBLINKER);
                        quantifierNode(nodes, conjunctTail + 1, 1, QuantifierNode.REPEAT_INFINITE);
                        createPropertyNode(nodes, conjunctTail + 2, UnicodeCodeRange.INCBEXTEND);
                        addPropertyToCC((CClassNode)nodes[conjunctTail + 2], UnicodeCodeRange.INCBLINKER, false);
                        quantifierNode(nodes, conjunctTail + 2, 0, QuantifierNode.REPEAT_INFINITE);
                        createPropertyNode(nodes, conjunctTail + 3, UnicodeCodeRange.INCBCONSONANT);
                        createNodeFromArray(true, nodes, incbList + 1, conjunctTail);
                        quantifierNode(nodes, incbList + 1, 1, QuantifierNode.REPEAT_INFINITE);
                        createNodeFromArray(true, nodes, coreAlts + 5, incbList);
                    }
                    cc = new CClassNode();
                    nodes[coreAlts + 6] = cc;
                    if (enc.minLength() > 1) {
                        addPropertyToCC(cc, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CONTROL, false);
                        cc.addCodeRange(env, 0x000A, 0x000A);
                        cc.addCodeRange(env, 0x000D, 0x000D);
                        cc.mbuf = CodeRangeBuffer.notCodeRangeBuff(env, cc.mbuf);
                    } else {
                        addPropertyToCC(cc, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CONTROL, true);
                        cc.bs.clear(0x0A);
                        cc.bs.clear(0x0D);
                    }
                    createNodeFromArray(false, nodes, list + 1, coreAlts);
                }
                createPropertyNode(nodes, list + 2, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_EXTEND);
                cc = (CClassNode)nodes[list + 2];
                addPropertyToCC(cc, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_SPACINGMARK, false);
                cc.addCodeRange(env, 0x200D, 0x200D);
                quantifierNode(nodes, list + 2, 0, QuantifierNode.REPEAT_INFINITE);
                createNodeFromArray(true, nodes, alts + 2, list);

            }
            anyTargetPosition = 3;
        } else { // enc.isUnicode()
            anyTargetPosition = 1;
        }

        Node any = new AnyCharNode();
        EncloseNode option = EncloseNode.newOption(bsOnOff(env.option, Option.MULTILINE, false));
        option.setTarget(any);
        nodes[anyTargetPosition] = option;

        Node topAlt = createNodeFromArray(nodes, alts);
        EncloseNode enclose = new EncloseNode(EncloseType.STOP_BACKTRACK);
        enclose.setTarget(topAlt);

        if (Config.USE_UNICODE_PROPERTIES && enc.isUnicode()) {
            option = EncloseNode.newOption(bsOnOff(env.option, Option.IGNORECASE, true));
            option.setTarget(enclose);
            return option;
        } else {
            return enclose;
        }
    }

    private Node parseExpTkByte(boolean group) {
        StringNode node = new StringNode(bytes, token.backP, p); // tk_byte:
        return parseStringLoop(node, group);
    }

    private Node parseStringLoop(StringNode node, boolean group) {
        while (true) {
            fetchToken();
            if (token.type == TokenType.STRING) {
                if (token.backP == node.end) {
                    node.end = p; // non escaped character, remain shared, just increase shared range
                } else {
                    node.catBytes(bytes, token.backP, p); // non continuous string stream, need to COW
                }
            } else if (token.type == TokenType.CODE_POINT) {
                node.catCode(token.getCode(), enc);
            } else {
                break;
            }
        }
        // targetp = node;
        return parseExpRepeat(node, group); // string_end:, goto repeat
    }

    private Node parseExpTkRawByte(boolean group) {
        // tk_raw_byte:
        StringNode node = new StringNode();
        node.setRaw();
        node.catByte((byte)token.getC());

        int len = 1;
        while (true) {
            if (len >= enc.minLength()) {
                if (len == enc.length(node.bytes, node.p, node.end)) {
                    fetchToken();
                    node.clearRaw();
                    // !goto string_end;!
                    return parseExpRepeat(node, group);
                }
            }

            fetchToken();
            if (token.type != TokenType.RAW_BYTE) {
                /* Don't use this, it is wrong for little endian encodings. */
                // USE_PAD_TO_SHORT_BYTE_CHAR ...
                newValueException(TOO_SHORT_MULTI_BYTE_STRING);
            }
            node.catByte((byte)token.getC());
            len++;
        } // while
    }

    private Node parseExpRepeat(Node target, boolean group) {
        return parseExpRepeat(target, group, -1);
    }

    private Node parseExpRepeat(Node target, boolean group, int expressionStart) {
        while (token.type == TokenType.OP_REPEAT || token.type == TokenType.INTERVAL) { // repeat:
            if (isInvalidQuantifier(target)) newSyntaxException(TARGET_OF_REPEAT_OPERATOR_INVALID);

            if (!group && syntax.op3OptionECMAScript() && target.getType() == NodeType.QTFR) {
                newSyntaxException(NESTED_REPEAT_NOT_ALLOWED);
            }
            QuantifierNode qtfr = new QuantifierNode(token.getRepeatLower(),
                                                     token.getRepeatUpper(),
                                                     token.type == TokenType.INTERVAL);

            if (expressionStart >= getBegin()
                    && target.getType() == NodeType.ANCHOR
                    && token.getRepeatUpper() == QuantifierNode.REPEAT_INFINITE
                    && env.usesPerlDiagnostics()) {
                String repeated = new String(bytes, expressionStart,
                        p - expressionStart, enc.getCharset());
                env.warnings.warn(repeated + " matches null string many times",
                        p - getBegin());
            }

            qtfr.greedy = token.getRepeatGreedy();
            int ret = qtfr.setQuantifier(target, group, env, bytes, getBegin(), getEnd(),
                    getPatternPosition());
            Node qn = qtfr;

            if (token.getRepeatPossessive()) {
                // A bare {1} is normally a no-op, but {1}+ still establishes
                // an atomic boundary around its target.  Keep that boundary
                // instead of taking the ordinary no-op return path.
                if (ret == 1) qtfr.setTarget(target);
                EncloseNode en = new EncloseNode(EncloseType.STOP_BACKTRACK); // node_new_enclose
                en.setTarget(qn);
                qn = en;
            }

            if (ret == 2) { /* split case: /abc+/ */
                target = ListNode.newList(target, null);
                ListNode tmp = ListNode.newList(qn, null);
                ((ListNode)target).setTail(tmp);

                fetchToken();
                return parseExpRepeatForCar(target, tmp, group);
            } else if (ret == 0 || token.getRepeatPossessive()
                    || (syntax.op3OptionECMAScript() && ret == 1)) {
                target = qn;
            }
            fetchToken(); // goto re_entry
        }
        return target;
    }

    private Node parseExpRepeatForCar(Node top, ListNode target, boolean group) {
        while (token.type == TokenType.OP_REPEAT || token.type == TokenType.INTERVAL) { // repeat:
            if (isInvalidQuantifier(target.value)) newSyntaxException(TARGET_OF_REPEAT_OPERATOR_INVALID);

            QuantifierNode qtfr = new QuantifierNode(token.getRepeatLower(),
                                                     token.getRepeatUpper(),
                                                     token.type == TokenType.INTERVAL);

            qtfr.greedy = token.getRepeatGreedy();
            int ret = qtfr.setQuantifier(target.value, group, env, bytes, getBegin(), getEnd(),
                    getPatternPosition());
            Node qn = qtfr;

            if (token.getRepeatPossessive()) {
                if (ret == 1) qtfr.setTarget(target.value);
                EncloseNode en = new EncloseNode(EncloseType.STOP_BACKTRACK); // node_new_enclose
                en.setTarget(qn);
                qn = en;
            }

            if (ret == 0 || token.getRepeatPossessive()) {
                target.setValue(qn);
            } else if (ret == 2) { /* split case: /abc+/ */
                assert false;
            }
            fetchToken(); // goto re_entry
        }
        return top;
    }

    private boolean isInvalidQuantifier(Node node) {
        if (Config.USE_NO_INVALID_QUANTIFIER) return false;

        ListNode consAlt;
        switch(node.getType()) {
        case NodeType.ANCHOR:
            return true;

        case NodeType.ENCLOSE:
            /* allow enclosed elements */
            /* return is_invalid_quantifier_target(NENCLOSE(node)->target); */
            break;

        case NodeType.LIST:
            consAlt = (ListNode)node;
            do {
                if (!isInvalidQuantifier(consAlt.value)) return false;
            } while ((consAlt = consAlt.tail) != null);
            return false;

        case NodeType.ALT:
            consAlt = (ListNode)node;
            do {
                if (isInvalidQuantifier(consAlt.value)) return true;
            } while ((consAlt = consAlt.tail) != null);
            break;

        default:
            break;
        }
        return false;
    }

    private Node parseQuoteOpen() {
        int[]endOp = new int[]{syntax.metaCharTable.esc, 'E'};
        int qstart = p;
        Ptr nextChar = new Ptr();
        int qend = findStrPosition(endOp, endOp.length, qstart, stop, nextChar);
        if (qend == -1) nextChar.p = qend = stop;
        Node node = new StringNode(bytes, qstart, qend);
        p = nextChar.p;
        return node;
    }

    private Node parseCharType(Node node) {
        switch(token.getPropCType()) {
        case CharacterType.WORD:
            node = new CTypeNode(token.getPropCType(), token.getPropNot(), isAsciiRange(env.option));
            break;

        case CharacterType.SPACE:
        case CharacterType.DIGIT:
        case CharacterType.XDIGIT:
            CClassNode ccn = new CClassNode();
            ccn.addCType(token.getPropCType(), false, isAsciiRange(env.option), env, this);
            if (token.getPropNot()) ccn.setNot();
            node = ccn;
            break;

        default:
            CClassNode propertyClass = new CClassNode();
            propertyClass.addCType(token.getPropCType(), false, false, env, this);
            if (token.getPropNot()) propertyClass.setNot();
            node = propertyClass;
        } // inner switch
        return node;
    }

    private Node cClassCaseFold(Node node, CClassNode cc, CClassNode ascCc,
                                CClassNode foldCc) {
        return cClassCaseFold(node, cc, ascCc, foldCc, false);
    }

    private Node cClassCaseFold(Node node, CClassNode cc, CClassNode ascCc,
                                CClassNode foldCc, boolean preservePropertyAsciiCrossings) {
        ApplyCaseFoldArg arg = new ApplyCaseFoldArg(
                env, cc, ascCc, foldCc, cc.propertyFoldMask(),
                preservePropertyAsciiCrossings);
        enc.applyAllCaseFold(env.caseFoldFlagFor(env.option), ApplyCaseFold.INSTANCE, arg);
        if (syntax.op2OptionPerl()) {
            ApplyCaseFold.applyPerlSimpleClassClosure(arg);
        }
        if (arg.altRoot != null) {
            node = ListNode.newAlt(node, arg.altRoot);
        }
        if (Option.isPerlAsciiStrict(env.option) && !cc.isNot()) {
            PerlAsciiStrictClassMultiFold strictFolds =
                    new PerlAsciiStrictClassMultiFold(enc, cc, foldCc);
            enc.applyAllCaseFold(regex.caseFoldFlag, strictFolds, null);
            ListNode alternatives = strictFolds.alternatives();
            if (alternatives != null) node = ListNode.newAlt(node, alternatives);
        }
        return node;
    }

    private Node parseCharProperty() {
        CharProperty property = fetchCharProperty(false);
        CClassNode cc = new CClassNode();
        Node node = cc;
        addCharProperty(cc, null, null, property, false);
        if (token.getPropNot()) cc.setNot();

        if (isIgnoreCase(env.option) && property.caseFold) {
            if (property.ranges != null || property.wideRanges != null
                    || property.ctype != CharacterType.ASCII) {
                node = cClassCaseFold(node, cc, cc, cc, true);
            }
        }
        return node;
    }

    private void addCharProperty(CClassNode cc, CClassNode ascCc,
                                 CClassNode foldCc, CharProperty property,
                                 boolean not) {
        if (property.ranges == null && property.wideRanges == null) {
            cc.addCType(property.ctype, not, false, env, this);
            if (ascCc != null && property.ctype != CharacterType.ASCII) {
                ascCc.addCType(property.ctype, not, false, env, this);
            }
            if (foldCc != null) {
                foldCc.addCType(property.ctype, not, false, env, this);
            }
            if (property.caseFold) {
                cc.markPropertyFoldCType(property.ctype, false, env, this);
            }
            return;
        }
        cc.addCodeRanges(property.ranges, property.wideRanges, not, env);
        if (ascCc != null) {
            ascCc.addCodeRanges(property.ranges, property.wideRanges, not, env);
        }
        if (foldCc != null && property.caseFold) {
            foldCc.addCodeRanges(property.ranges, property.wideRanges, not, env);
        }
        if (property.caseFold) {
            cc.markPropertyFoldCodeRanges(
                    property.ranges, property.wideRanges, false, env);
        }
    }

    private Node parseAnycharAnytime() {
        Node node = new AnyCharNode();
        QuantifierNode qn = new QuantifierNode(0, QuantifierNode.REPEAT_INFINITE, false);
        qn.setTarget(node);
        return qn;
    }

    private Node parseBackref() {
        final Node node;
        if (syntax.op3OptionECMAScript() && token.getBackrefNum() == 1 && env.memNodes != null) {
            EncloseNode encloseNode = env.memNodes[token.getBackrefRef1()];
            boolean shouldIgnore = false;
            if (encloseNode != null && encloseNode.containingAnchor != null) {
                shouldIgnore = true;
                for (Node anchorNode : env.precReadNotNodes) {
                    if (anchorNode == encloseNode.containingAnchor) {
                        shouldIgnore = false;
                        break;
                    }
                }
            }
            if (shouldIgnore) {
                node = StringNode.EMPTY;
            } else {
                node = newBackRef(new int[]{token.getBackrefRef1()});
            }
        } else {
            node = newBackRef(token.getBackrefNum() > 1 ? token.getBackrefRefs() : new int[]{token.getBackrefRef1()});
        }
        return node;
    }

    private BackRefNode newBackRef(int[]backRefs) {
        if (token.getBackrefNameP() >= 0) {
            return new BackRefNode(bytes, token.getBackrefNameP(),
                    token.getBackrefNameEnd(), env);
        }
        return new BackRefNode(token.getBackrefNum(),
            backRefs,
            token.getBackrefByName(),
            token.getBackrefExistLevel(),
            token.getBackrefLevel(),
            env);
    }

    private Node parseCall() {
        int gNum = token.getCallGNum();
        boolean backwardRelative = gNum < 0;
        if (gNum < 0 || token.getCallRel()) {
            if (gNum > 0) gNum--;
            gNum = backrefRelToAbs(gNum);
            if (gNum <= 0) newValueException(INVALID_BACKREF);
        }
        CallNode node = new CallNode(bytes, token.getCallNameP(), token.getCallNameEnd(), gNum);
        if (backwardRelative && lexicalMemNodes != null && gNum < lexicalMemNodes.length) {
            node.lexicalTarget = lexicalMemNodes[gNum];
        }
        env.numCall++;
        return node;
    }

    private Node parsePerlRelativeCall(int sign) {
        int nameP = enc.prevCharHead(bytes, getBegin(), p, stop);
        int number = 0;
        boolean overflow = false;
        while (left() && enc.isDigit(peek())) {
            int digit = Encoding.digitVal(peek());
            if (number > (Integer.MAX_VALUE - digit) / 10) {
                overflow = true;
            } else if (!overflow) {
                number = number * 10 + digit;
            }
            inc();
        }
        int nameEnd = p;
        if (!left() || !peekIs(')')) newSyntaxException(UNDEFINED_GROUP_OPTION);
        if (overflow) newValueException(PERL_INVALID_REFERENCE_TO_GROUP);

        inc();
        long absolute = sign == '+'
                ? (long)env.numMem + number
                : (long)env.numMem + 1 - number;
        if (absolute <= 0) {
            newValueException(sign == '+' ? PERL_INVALID_REFERENCE_TO_GROUP
                    : PERL_REFERENCE_TO_NONEXISTENT_GROUP);
        }
        if (absolute > Integer.MAX_VALUE) {
            newValueException(PERL_INVALID_REFERENCE_TO_GROUP);
        }

        CallNode node = new CallNode(bytes, nameP, nameEnd, (int)absolute);
        if (sign == '-' && lexicalMemNodes != null
                && absolute < lexicalMemNodes.length) {
            node.lexicalTarget = lexicalMemNodes[(int)absolute];
        }
        env.numCall++;
        returnCode = 0;
        return node;
    }

    private Node parsePerlNamedCall() {
        int nameP = p;
        if (!left()) {
            newSyntaxException(PERL_NAMED_CALL_NOT_TERMINATED,
                    stop - getBegin());
        }

        int cursor = p;
        int first = enc.mbcToCode(bytes, cursor, stop);
        int firstEnd = cursor + enc.length(bytes, cursor, stop);
        if (first == ')' || !isPerlGroupNameStart(first)) {
            newSyntaxException(PERL_GROUP_NAME_MUST_START_WITH_WORD,
                    firstEnd - getBegin());
        }

        while (cursor < stop) {
            int code = enc.mbcToCode(bytes, cursor, stop);
            if (code == ')') {
                int nameEnd = cursor;
                p = cursor + enc.length(bytes, cursor, stop);
                CallNode node = new CallNode(bytes, nameP, nameEnd, 0);
                env.numCall++;
                returnCode = 0;
                return node;
            }
            if (!enc.isWord(code)) {
                newSyntaxException(PERL_NAMED_CALL_NOT_TERMINATED,
                        cursor - getBegin());
            }
            cursor += enc.length(bytes, cursor, stop);
        }

        newSyntaxException(PERL_NAMED_CALL_NOT_TERMINATED,
                stop - getBegin());
        return null;
    }

    private Node parsePerlNumberedCall(int firstDigit) {
        int nameP = enc.prevCharHead(bytes, getBegin(), p, stop);
        int number = Encoding.digitVal(firstDigit);
        boolean overflow = false;
        while (left() && enc.isDigit(peek())) {
            int digit = Encoding.digitVal(peek());
            if (number > (Integer.MAX_VALUE - digit) / 10) {
                overflow = true;
            } else if (!overflow) {
                number = number * 10 + digit;
            }
            inc();
        }
        int nameEnd = p;
        if (!left() || !peekIs(')')) newSyntaxException(UNDEFINED_GROUP_OPTION);
        if (overflow) newValueException(PERL_INVALID_REFERENCE_TO_GROUP);
        inc();

        CallNode node = new CallNode(bytes, number == 0 ? nameEnd : nameP,
                nameEnd, number);
        env.numCall++;
        returnCode = 0;
        return node;
    }

    private Node parseBranch(TokenType term) {
        Node node = parseExp(term);

        if (token.type == TokenType.EOT || token.type == term || token.type == TokenType.ALT) {
            return node;
        } else {
            ListNode top = ListNode.newList(node, null);
            ListNode t = top;

            while (token.type != TokenType.EOT && token.type != term && token.type != TokenType.ALT) {
                node = parseExp(term);
                if (node.getType() == NodeType.LIST) {
                    t.setTail((ListNode)node);
                    while (((ListNode)node).tail != null ) node = ((ListNode)node).tail;

                    t = ((ListNode)node);
                } else {
                    t.setTail(ListNode.newList(node, null));
                    t = t.tail;
                }
            }
            return top;
        }
    }

    private Node parseBranchReset(TokenType term) {
        int captureBase = env.numMem;
        int captureMax = captureBase;
        Node branch = parseBranch(term);
        captureMax = Math.max(captureMax, env.numMem);

        if (token.type == term) {
            env.numMem = captureMax;
            return branch;
        }
        if (token.type != TokenType.ALT) {
            parseSubExpError(term);
        }

        ListNode top = ListNode.newAlt(branch, null);
        ListNode tail = top;
        while (token.type == TokenType.ALT) {
            env.numMem = captureBase;
            fetchToken();
            branch = parseBranch(term);
            captureMax = Math.max(captureMax, env.numMem);
            tail.setTail(ListNode.newAlt(branch, null));
            tail = tail.tail;
        }
        env.numMem = captureMax;
        if (token.type != term) parseSubExpError(term);
        return top;
    }

    /* term_tok: TK_EOT or TK_SUBEXP_CLOSE */
    private Node parseSubExp(TokenType term) {
        Node node = parseBranch(term);

        if (token.type == term) {
            return node;
        } else if (token.type == TokenType.ALT) {
            ListNode top = ListNode.newAlt(node, null);
            ListNode t = top;
            while (token.type == TokenType.ALT) {
                fetchToken();
                node = parseBranch(term);

                t.setTail(ListNode.newAlt(node, null));
                t = t.tail;
            }

            if (token.type != term) parseSubExpError(term);
            return top;
        } else {
            parseSubExpError(term);
            return null; //not reached
        }
    }

    private void parseSubExpError(TokenType term) {
        if (term == TokenType.SUBEXP_CLOSE) {
            newSyntaxException(END_PATTERN_WITH_UNMATCHED_PARENTHESIS);
        } else {
            newInternalException(PARSER_BUG);
        }
    }

    protected final Node parseRegexp() {
        fetchToken();
        Node top = parseSubExp(TokenType.EOT);
        if (Config.USE_SUBEXP_CALL) {
            if (env.numCall > 0) {
                /* Capture the pattern itself. It is used for (?R), (?0) and \g<0>. */
                EncloseNode np = EncloseNode.newMemory(env.option, false);
                np.regNum = 0;
                np.setTarget(top);
                if (env.memNodes ==  null) env.memNodes = new EncloseNode[Config.SCANENV_MEMNODES_SIZE];
                env.memNodes[0] = np;
                top = np;
            }
        }
        return top;
    }
}
