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

import static org.joni.BitStatus.bsAll;
import static org.joni.BitStatus.bsAt;
import static org.joni.BitStatus.bsClear;
import static org.joni.BitStatus.bsOnAt;
import static org.joni.BitStatus.bsOnAtSimple;
import static org.joni.Option.isCaptureGroup;
import static org.joni.Option.isFindCondition;
import static org.joni.Option.isIgnoreCase;
import static org.joni.Option.isMultiline;
import static org.joni.ast.ListNode.newAlt;
import static org.joni.ast.ListNode.newList;
import static org.joni.ast.QuantifierNode.isRepeatInfinite;

import java.util.IllegalFormatConversionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jcodings.CaseFoldCodeItem;
import org.jcodings.Encoding;
import org.jcodings.ObjPtr;
import org.jcodings.Ptr;
import org.jcodings.constants.CharacterType;
import org.joni.ast.AnchorNode;
import org.joni.ast.BackRefNode;
import org.joni.ast.CClassNode;
import org.joni.ast.CTypeNode;
import org.joni.ast.CallNode;
import org.joni.ast.CalloutNode;
import org.joni.ast.ControlVerbNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.ListNode;
import org.joni.ast.Node;
import org.joni.ast.QuantifierNode;
import org.joni.ast.StringNode;
import org.joni.constants.internal.AnchorType;
import org.joni.constants.internal.EncloseType;
import org.joni.constants.internal.NodeType;
import org.joni.constants.internal.StackPopLevel;
import org.joni.constants.internal.TargetInfo;
import org.joni.exception.ValueException;

final class Analyser extends Parser {

    protected Analyser(Regex regex, Syntax syntax, byte[]bytes, int p, int end, WarnCallback warnings) {
        super(regex, syntax, bytes, p, end, warnings);
    }

    protected final void compile() {
        if (Config.DEBUG) Config.log.println(encStringToString(bytes, getBegin(), getEnd()));
        reset();

        regex.numMem = 0;
        regex.numRepeat = 0;
        regex.numNullCheck = 0;
        //regex.repeatRangeAlloc = 0;
        regex.repeatRangeLo = null;
        regex.repeatRangeHi = null;
        regex.numCombExpCheck = 0;

        if (Config.USE_CEC) regex.numCombExpCheck = 0;


        Node root = parseRegexp(); // onig_parse_make_tree
        regex.numMem = env.numMem;
        resolveForwardNamedBackrefs(root);

        if (Config.USE_NAMED_GROUP) {
            /* mixed use named group and no-named group */
            if (env.numNamed > 0 && syntax.captureOnlyNamedGroup() && !isCaptureGroup(regex.options)) {
                if (env.numNamed != env.numMem) {
                    root = disableNoNameGroupCapture(root);
                } else {
                    numberedRefCheck(root);
                }
            }
        } // USE_NAMED_GROUP

        if (Config.USE_NAMED_GROUP) {
            if (env.numCall > 0) {
                env.unsetAddrList = new UnsetAddrList(env.numCall);
                setupSubExpCall(root);
                // r != 0 ???
                subexpRecursiveCheckTrav(root);
                // r < 0 -< err, FOUND_CALLED_NODE = 1
                subexpInfRecursiveCheckTrav(root);
                // r != 0  recursion infinite ???
                regex.numCall = env.numCall;
            } else {
                regex.numCall = 0;
            }
        } // USE_NAMED_GROUP

        if (Config.DEBUG_PARSE_TREE && Config.DEBUG_PARSE_TREE_RAW) Config.log.println("<RAW TREE>\n" + root + "\n");

        Node.TopNode top = Node.newTop(root);
        setupTree(root, 0);
        root = top.getRoot();

        if (Config.DEBUG_PARSE_TREE) Config.log.println("<TREE>\n" + root + "\n");

        regex.captureHistory = env.captureHistory;
        regex.btMemStart = env.btMemStart;
        regex.hasControlVerb = env.hasControlVerb;
        if (env.hasControlVerb) {
            // ACCEPT needs stack markers to distinguish captures opened inside
            // its nearest assertion or call boundary from captures outside it.
            regex.btMemStart = bsAll();
        }

        if (isFindCondition(regex.options) || env.hasCallout) {
            regex.btMemEnd = bsAll();
        } else {
            regex.btMemEnd = env.btMemEnd;
            regex.btMemEnd |= regex.captureHistory;
        }

        if (Config.USE_CEC) {
            if (env.backrefedMem == 0 &&
                    (!Config.USE_SUBEXP_CALL || env.numCall == 0) &&
                    !env.hasCallout) {
                setupCombExpCheck(root, 0);

                if (Config.USE_SUBEXP_CALL && env.hasRecursion) {
                    env.numCombExpCheck = 0;
                } else { // USE_SUBEXP_CALL
                    if (env.combExpMaxRegNum > 0) {
                        for (int i=1; i<env.combExpMaxRegNum; i++) {
                            if (bsAt(env.backrefedMem, i)) {
                                env.numCombExpCheck = 0;
                                break;
                            }
                        }
                    }
                }

            } // USE_SUBEXP_CALL
            regex.numCombExpCheck = env.numCombExpCheck;
        } // USE_COMBINATION_EXPLOSION_CHECK

        regex.clearOptimizeInfo();

        // A control verb can make the remainder of its current matcher program
        // unreachable. The ordinary optimizer assumes every concatenated node
        // remains mandatory, so its minimum-length and literal-search filters
        // are not sound for these programs.
        if (Config.OPTIMIZE && !env.hasControlVerb && !env.hasCallout
                && !regex.hasDynamicOptions) {
            setOptimizedInfoFromTree(root);
        }

        env.memNodes = null;

        new ArrayCompiler(this).compile(root);
        regex.publishParsedProgramMetadata(env.parsedProgramMetadata());

        if (regex.numRepeat != 0 || regex.btMemEnd != 0) {
            regex.stackPopLevel = StackPopLevel.ALL;
        } else {
            if (regex.btMemStart != 0) {
                regex.stackPopLevel = StackPopLevel.MEM_START;
            } else {
                regex.stackPopLevel = StackPopLevel.FREE;
            }
        }

        if (Config.DEBUG_COMPILE) {
            if (Config.USE_NAMED_GROUP) Config.log.print(regex.nameTableToString());
            Config.log.println("stack used: " + regex.requireStack);
            if (Config.USE_STRING_TEMPLATES) Config.log.print("templates: " + regex.templateNum + "\n");
            Config.log.println(new ByteCodePrinter(regex).byteCodeListToString());
        } // DEBUG_COMPILE

        regex.options &= ~syntax.options;
    }

    Regex.ParsedProgramMetadata parsedProgramMetadata() {
        return env.parsedProgramMetadata();
    }

    private void resolveForwardNamedBackrefs(Node node) {
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode list = (ListNode)node;
            do {
                resolveForwardNamedBackrefs(list.value);
            } while ((list = list.tail) != null);
            break;
        case NodeType.QTFR:
            resolveForwardNamedBackrefs(((QuantifierNode)node).target);
            break;
        case NodeType.ENCLOSE:
            EncloseNode enclose = (EncloseNode)node;
            if (enclose.assertionCondition != null) {
                resolveForwardNamedBackrefs(enclose.assertionCondition);
            }
            if (enclose.target != null) resolveForwardNamedBackrefs(enclose.target);
            break;
        case NodeType.ANCHOR:
            AnchorNode anchor = (AnchorNode)node;
            if (anchor.target != null) resolveForwardNamedBackrefs(anchor.target);
            break;
        case NodeType.BREF:
            BackRefNode backref = (BackRefNode)node;
            if (backref.unresolvedName != null) {
                regex.hasForwardNamedBackreference = true;
                NameEntry entry = regex.nameToGroupNumbers(backref.unresolvedName,
                        backref.unresolvedNameP, backref.unresolvedNameEnd);
                if (entry == null) {
                    throw new ValueException(PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP,
                            backref.unresolvedNameEnd - getBegin());
                }
                backref.resolve(entry.getBackRefs(), env);
            }
            break;
        default:
            break;
        }
    }

    private String encStringToString(byte[]bytes, int p, int end) {
        StringBuilder sb = new StringBuilder("\nPATTERN: /");

        if (enc.minLength() > 1) {
            int p_ = p;
            while (p_ < end) {
                int code = enc.mbcToCode(bytes, p_, end);
                if (code >= 0x80) {
                    try {
                        sb.append(String.format(" 0x%04x ", code));
                    } catch (IllegalFormatConversionException ifce) {
                        sb.append(code);
                    }
                } else {
                    sb.append((char)code);
                }
                p_ += enc.length(bytes, p_, end);
            }
        } else {
            while (p < end) {
                sb.append(new String(bytes, p, 1));
                p++;
            }
        }
        return sb.append("/").toString();
    }

    private void noNameDisableMapFor_listAlt(Node node, int[]map, Ptr counter) {
        ListNode can = (ListNode)node;
        do {
            can.setValue(noNameDisableMap(can.value, map, counter));
        } while ((can = can.tail) != null);
    }

    private void noNameDisableMapFor_quantifier(Node node, int[]map, Ptr counter) {
        QuantifierNode qn = (QuantifierNode)node;
        Node target = qn.target;
        Node old = target;
        target = noNameDisableMap(target, map, counter);

        if (target != old) {
            qn.setTarget(target);
            if (target.getType() == NodeType.QTFR) qn.reduceNestedQuantifier((QuantifierNode)target);
        }
    }

    private Node noNameDisableMapFor_enclose(Node node, int[]map, Ptr counter) {
        EncloseNode en = (EncloseNode)node;
        if (en.type == EncloseType.MEMORY) {
            if (en.isNamedGroup()) {
                counter.p++;
                map[en.regNum] = counter.p;
                en.regNum = counter.p;
                en.setTarget(noNameDisableMap(en.target, map, counter));
            } else {
                node = en.target;
                en.target = null; // remove first enclose: /(a)(?<b>c)/
                node = noNameDisableMap(node, map, counter);
            }
        } else {
            en.setTarget(noNameDisableMap(en.target, map, counter));
        }
        return node;
    }

    private void noNameDisableMapFor_anchor(Node node, int[]map, Ptr counter) {
        AnchorNode an = (AnchorNode)node;
        if (an.target != null) an.setTarget(noNameDisableMap(an.target, map, counter));
    }

    private Node noNameDisableMap(Node node, int[]map, Ptr counter) {
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            noNameDisableMapFor_listAlt(node, map, counter);
            break;
        case NodeType.QTFR:
            noNameDisableMapFor_quantifier(node, map, counter);
            break;
        case NodeType.ENCLOSE:
            node = noNameDisableMapFor_enclose(node, map, counter);
            break;
        case NodeType.ANCHOR:
            noNameDisableMapFor_anchor(node, map, counter);
            break;
        } // switch
        return node;
    }

    private void renumberByMap(Node node, int[]map) {
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                renumberByMap(can.value, map);
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            renumberByMap(((QuantifierNode)node).target, map);
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (en.type == EncloseType.CONDITION && en.calloutConditionId < 0
                    && en.assertionCondition == null && en.recursionConditionGroup < 0) {
                en.regNum = map[en.regNum];
            }
            if (en.recursionConditionGroup > 0) {
                en.recursionConditionGroup = map[en.recursionConditionGroup];
            }
            if (en.assertionCondition != null) renumberByMap(en.assertionCondition, map);
            renumberByMap(en.target, map);
            break;

        case NodeType.BREF:
            ((BackRefNode)node).renumber(map);
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            if (an.target != null) renumberByMap(an.target, map);
            break;
        } // switch
    }

    protected final void numberedRefCheck(Node node) {
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                numberedRefCheck(can.value);
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            numberedRefCheck(((QuantifierNode)node).target);
            break;

        case NodeType.ENCLOSE:
            numberedRefCheck(((EncloseNode)node).target);
            break;

        case NodeType.BREF:
            BackRefNode br = (BackRefNode)node;
            if (!br.isNameRef()) newValueException(NUMBERED_BACKREF_OR_CALL_NOT_ALLOWED);
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            if (an.target != null) numberedRefCheck(an.target);
            break;
        } // switch
    }

    protected final Node disableNoNameGroupCapture(Node root) {
        int[]map = new int[env.numMem + 1];

        root = noNameDisableMap(root, map, new Ptr(0));
        renumberByMap(root, map);

        for (int i=1, pos=1; i<=env.numMem; i++) {
            if (map[i] > 0) {
                env.memNodes[pos] = env.memNodes[i];
                pos++;
            }
        }

        int loc = env.captureHistory;
        env.captureHistory = bsClear();

        for (int i=1; i<=Config.MAX_CAPTURE_HISTORY_GROUP; i++) {
            if (bsAt(loc, i)) {
                env.captureHistory = bsOnAtSimple(env.captureHistory, map[i]);
            }
        }

        env.numMem = env.numNamed;
        regex.numMem = env.numNamed;

        regex.renumberNameTable(map);

        return root;
    }

    // USE_INFINITE_REPEAT_MONOMANIAC_MEM_STATUS_CHECK
    private int quantifiersMemoryInfo(Node node) {
        int info = 0;

        switch(node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                int v = quantifiersMemoryInfo(can.value);
                if (v > info) info = v;
            } while ((can = can.tail) != null);
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (cn.isRecursion()) {
                    return TargetInfo.IS_EMPTY_REC; /* tiny version */
                } else {
                    info = quantifiersMemoryInfo(cn.target);
                }
            } // USE_SUBEXP_CALL
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.upper != 0) {
                info = quantifiersMemoryInfo(qn.target);
            }
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch (en.type) {
            case EncloseType.MEMORY:
                return TargetInfo.IS_EMPTY_MEM;

            case EncloseType.OPTION:
            case EncloseNode.STOP_BACKTRACK:
            case EncloseNode.CONDITION:
            case EncloseNode.ABSENT:
                info = quantifiersMemoryInfo(en.target);
                break;

            case EncloseType.DEFINE:
                break;

            default:
                break;
            } // inner switch
            break;

        case NodeType.BREF:
        case NodeType.STR:
        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY:
        case NodeType.ANCHOR:
        default:
            break;
        } // switch

        return info;
    }

    private int getMinMatchLength(Node node) {
        int min = 0;

        switch (node.getType()) {
        case NodeType.BREF:
            BackRefNode br = (BackRefNode)node;
            if (br.isRecursion()) break;

            if (invalidBackrefNode(br.back[0])) {
                if (!syntax.op3OptionECMAScript()) newValueException(INVALID_BACKREF);
            } else {
                min = getMinMatchLength(env.memNodes[br.back[0]]);
            }

            for (int i=1; i<br.backNum; i++) {
                if (invalidBackrefNode(br.back[i])) {
                    if (!syntax.op3OptionECMAScript()) newValueException(INVALID_BACKREF);
                } else {
                    int tmin = getMinMatchLength(env.memNodes[br.back[i]]);
                    if (min > tmin) min = tmin;
                }
            }
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (cn.isRecursion()) {
                    EncloseNode en = cn.target;
                    if (en.isMinFixed()) min = en.minLength;
                } else {
                    min = getMinMatchLength(cn.target);
                }
            } // USE_SUBEXP_CALL
            break;

        case NodeType.LIST:
            ListNode can = (ListNode)node;
            do {
                min += getMinMatchLength(can.value);
            } while ((can = can.tail) != null);
            break;

        case NodeType.ALT:
            ListNode y = (ListNode)node;
            do {
                Node x = y.value;
                int tmin = getMinMatchLength(x);
                if (y == node) {
                    min = tmin;
                } else if (min > tmin) {
                    min = tmin;
                }
            } while ((y = y.tail) != null);
            break;

        case NodeType.STR:
            min = node instanceof CalloutNode callout && callout.dynamic
                    ? 0 : ((StringNode)node).length();
            break;

        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY:
            min = 1;
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.lower > 0) {
                min = getMinMatchLength(qn.target);
                min = MinMaxLen.distanceMultiply(min, qn.lower);
            }
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch (en.type) {
            case EncloseType.MEMORY:
                if (Config.USE_SUBEXP_CALL) {
                    if (en.isMinFixed()) {
                        min = en.minLength;
                    } else {
                        if (en.isMark1()) {
                            min = 0; /* recursive */
                        } else {
                            en.setMark1();
                            min = getMinMatchLength(en.target);
                            en.clearMark1();
                            en.minLength = min;
                            en.setMinFixed();
                        }
                    }
                } // USE_SUBEXP_CALL
                break;

            case EncloseType.OPTION:
            case EncloseType.STOP_BACKTRACK:
            case EncloseNode.CONDITION:
                min = getMinMatchLength(en.target);
                break;

            case EncloseType.ABSENT:
            case EncloseType.DEFINE:
                break;
            } // inner switch
            break;

        case NodeType.ANCHOR:
        default:
            break;
        } // switch

        return min;
    }

    private int getMaxMatchLength(Node node) {
        int max = 0;

        switch (node.getType()) {
        case NodeType.LIST:
            ListNode ln = (ListNode)node;
            do {
                int tmax = getMaxMatchLength(ln.value);
                max = MinMaxLen.distanceAdd(max, tmax);
            } while ((ln = ln.tail) != null);
            break;

        case NodeType.ALT:
            ListNode an = (ListNode)node;
            do {
                int tmax = getMaxMatchLength(an.value);
                if (max < tmax) max = tmax;
            } while ((an = an.tail) != null);
            break;

        case NodeType.STR:
            max = node instanceof CalloutNode callout && callout.dynamic
                    ? MinMaxLen.INFINITE_DISTANCE : ((StringNode)node).length();
            break;

        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY:
            max = enc.maxLength();
            break;

        case NodeType.BREF:
            BackRefNode br = (BackRefNode)node;
            if (br.isRecursion()) {
                max = MinMaxLen.INFINITE_DISTANCE;
                break;
            }

            for (int i=0; i<br.backNum; i++) {
                if (invalidBackrefNode(br.back[i])) {
                    if(!syntax.op3OptionECMAScript()) newValueException(INVALID_BACKREF);
                } else {
                    int tmax = getMaxMatchLength(env.memNodes[br.back[i]]);
                    if (max < tmax) max = tmax;
                }
            }
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (!cn.isRecursion()) {
                    max = getMaxMatchLength(cn.target);
                } else {
                    max = MinMaxLen.INFINITE_DISTANCE;
                }
            } // USE_SUBEXP_CALL
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.upper != 0) {
                max = getMaxMatchLength(qn.target);
                if (max != 0) {
                    if (!isRepeatInfinite(qn.upper)) {
                        max = MinMaxLen.distanceMultiply(max, qn.upper);
                    } else {
                        max = MinMaxLen.INFINITE_DISTANCE;
                    }
                }
            }
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch (en.type) {
            case EncloseType.MEMORY:
                if (Config.USE_SUBEXP_CALL) {
                    if (en.isMaxFixed()) {
                        max = en.maxLength;
                    } else {
                        if (en.isMark1()) {
                            max = MinMaxLen.INFINITE_DISTANCE;
                        } else {
                            en.setMark1();
                            max = getMaxMatchLength(en.target);
                            en.clearMark1();
                            en.maxLength = max;
                            en.setMaxFixed();
                        }
                    }
                } // USE_SUBEXP_CALL
                break;

            case EncloseType.OPTION:
            case EncloseType.STOP_BACKTRACK:
            case EncloseNode.CONDITION:
                max = getMaxMatchLength(en.target);
                break;

            case EncloseType.ABSENT:
            case EncloseType.DEFINE:
                break;
            } // inner switch
            break;

        case NodeType.ANCHOR:
        default:
            break;
        } // switch

        return max;
    }

    private static final int GET_CHAR_LEN_VARLEN            = -1;
    private static final int GET_CHAR_LEN_TOP_ALT_VARLEN    = -2;
    protected final int getCharLengthTree(Node node) {
        return getCharLengthTree(node, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private int getCharLengthTree(Node node, int level, Set<Node> activeCalls) {
        level++;

        int len = 0;
        returnCode = 0;

        switch(node.getType()) {
        case NodeType.LIST:
            ListNode ln = (ListNode)node;
            do {
                int tlen = getCharLengthTree(ln.value, level, activeCalls);
                if (returnCode == 0) len = MinMaxLen.distanceAdd(len, tlen);
            } while (returnCode == 0 && (ln = ln.tail) != null);
            break;

        case NodeType.ALT:
            ListNode an = (ListNode)node;
            boolean varLen = false;

            int tlen = getCharLengthTree(an.value, level, activeCalls);
            while (returnCode == 0 && (an = an.tail) != null) {
                int tlen2 = getCharLengthTree(an.value, level, activeCalls);
                if (returnCode == 0) {
                    if (tlen != tlen2) varLen = true;
                }
            }

            if (returnCode == 0) {
                if (varLen) {
                    if (level == 1) {
                        returnCode = GET_CHAR_LEN_TOP_ALT_VARLEN;
                    } else {
                        returnCode = GET_CHAR_LEN_VARLEN;
                    }
                } else {
                    len = tlen;
                }
            }
            break;

        case NodeType.STR:
            StringNode sn = (StringNode)node;
            len = sn.length(enc);
            if (sn.isAmbig() && sn.isDontGetOptInfo() && len > 1) {
                returnCode = GET_CHAR_LEN_VARLEN;
            }
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.lower == qn.upper) {
                tlen = getCharLengthTree(qn.target, level, activeCalls);
                if (returnCode == 0) len = MinMaxLen.distanceMultiply(tlen, qn.lower);
            } else {
                returnCode = GET_CHAR_LEN_VARLEN;
            }
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (!activeCalls.add(cn.target)) {
                    returnCode = GET_CHAR_LEN_VARLEN;
                } else {
                    try {
                        len = getCharLengthTree(cn.target, level, activeCalls);
                    } finally {
                        activeCalls.remove(cn.target);
                    }
                }
            } // USE_SUBEXP_CALL
            break;

        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY:
            len = 1;
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch(en.type) {
            case EncloseType.MEMORY:
                if (Config.USE_SUBEXP_CALL) {
                    if (en.isCLenFixed()) {
                        len = en.charLength;
                    } else {
                        len = getCharLengthTree(en.target, level, activeCalls);
                        if (returnCode == 0) {
                            en.charLength = len;
                            en.setCLenFixed();
                        }
                    }
                } // USE_SUBEXP_CALL
                break;

            case EncloseType.OPTION:
            case EncloseType.STOP_BACKTRACK:
            case EncloseNode.CONDITION:
                len = getCharLengthTree(en.target, level, activeCalls);
                break;

            case EncloseType.ABSENT:
            case EncloseType.DEFINE:
                break;
            } // inner switch
            break;

        case NodeType.ANCHOR:
            break;

        default:
            returnCode = GET_CHAR_LEN_VARLEN;
        } // switch
        return len;
    }

    /* x is not included y ==>  1 : 0 */
    private boolean isNotIncluded(Node x, Node y) {
        if (x.getType() == NodeType.CCLASS
                && ((CClassNode)x).hasDeferredProperties()) return false;
        if (y.getType() == NodeType.CCLASS
                && ((CClassNode)y).hasDeferredProperties()) return false;
        Node tmp;

        retry: while(true) {
        int yType = y.getType();

        switch(x.getType()) {
        case NodeType.CTYPE:
            switch(yType) {
            case NodeType.CTYPE:
            {
                CTypeNode cny = (CTypeNode)y;
                CTypeNode cnx = (CTypeNode)x;
                return cny.ctype == cnx.ctype && cny.not != cnx.not && cny.asciiRange == cnx.asciiRange;
            }
            case NodeType.CCLASS:
                // !swap:!
                tmp = x;
                x = y;
                y = tmp;
                // !goto retry;!
                continue retry;

            case NodeType.STR:
                // !goto swap;!
                tmp = x;
                x = y;
                y = tmp;
                continue retry;

            default:
                break;
            } // inner switch
            break;

        case NodeType.CCLASS:
            CClassNode xc = (CClassNode)x;

            switch(yType) {
            case NodeType.CTYPE:
            {
                CTypeNode yc = (CTypeNode)y;
                switch(yc.ctype) {
                case CharacterType.WORD:
                    if (!yc.not) {
                        if (xc.mbuf == null && !xc.isNot()) {
                            for (int i=0; i<BitSet.SINGLE_BYTE_SIZE; i++) {
                                if (xc.bs.at(i)) {
                                    if (yc.asciiRange) {
                                        if (enc.isSbWord(i)) return false;
                                    } else {
                                        if (enc.isWord(i)) return false;
                                    }
                                }
                            }
                            return true;
                        }
                        return false;
                    } else {
                        if (xc.mbuf != null) return false;
                        for (int i=0; i<BitSet.SINGLE_BYTE_SIZE; i++) {
                            boolean isWord;
                            if (yc.asciiRange) {
                                isWord = enc.isSbWord(i);
                            } else {
                                isWord = enc.isWord(i);
                            }

                            if (!isWord) {
                                if (!xc.isNot()) {
                                    if (xc.bs.at(i)) return false;
                                } else {
                                    if (!xc.bs.at(i)) return false;
                                }
                            }
                        }
                        return true;
                    }
                    // break; not reached
                default:
                    break;
                } // inner switch
                break;
            }

            case NodeType.CCLASS:
            {
                CClassNode yc = (CClassNode)y;

                for (int i=0; i<BitSet.SINGLE_BYTE_SIZE; i++) {
                    boolean v = xc.bs.at(i);
                    if ((v && !xc.isNot()) || (!v && xc.isNot())) {
                        v = yc.bs.at(i);
                        if ((v && !yc.isNot()) || (!v && yc.isNot())) return false;
                    }
                }
                if ((xc.mbuf == null && !xc.isNot()) || yc.mbuf == null && !yc.isNot()) return true;
                return false;
                // break; not reached
            }

            case NodeType.STR:
                // !goto swap;!
                tmp = x;
                x = y;
                y = tmp;
                continue retry;

            default:
                break;

            } // inner switch
            break; // case NodeType.CCLASS

        case NodeType.STR:
        {
            StringNode xs = (StringNode)x;
            if (xs.length() == 0) break;

            switch (yType) {
            case NodeType.CTYPE:
                CTypeNode cy = ((CTypeNode)y);
                switch (cy.ctype) {
                case CharacterType.WORD:
                    if (cy.asciiRange) {
                        if (Matcher.isMbcAsciiWord(enc, xs.bytes, xs.p, xs.end)) {
                            return cy.not;
                        } else {
                            return !cy.not;
                        }
                    } else {
                        if (enc.isMbcWord(xs.bytes, xs.p, xs.end)) {
                            return cy.not;
                        } else {
                            return !cy.not;
                        }
                    }

                default:
                    break;

                } // inner switch
                break;

            case NodeType.CCLASS:
                CClassNode cc = (CClassNode)y;
                int code = enc.mbcToCode(xs.bytes, xs.p, xs.p + enc.maxLength());
                return !cc.isCodeInCC(enc, code);

            case NodeType.STR:
                StringNode ys = (StringNode)y;
                int len = xs.length();
                if (len > ys.length()) len = ys.length();
                if (xs.isAmbig() || ys.isAmbig()) {
                    /* tiny version */
                    return false;
                } else {
                    for (int i=0, p=ys.p, q=xs.p; i<len; i++, p++, q++) {
                        if (ys.bytes[p] != xs.bytes[q]) return true;
                    }
                }
                break;

            default:
                break;
            } // inner switch

            break; // case NodeType.STR
        }
        } // switch

        break;
        } // retry: while
        return false;
    }

    private Node getHeadValueNode(Node node, boolean exact) {
        Node n = null;

        switch(node.getType()) {
        case NodeType.BREF:
        case NodeType.ALT:
        case NodeType.CANY:
            break;

        case NodeType.CALL:
            break; // if (Config.USE_SUBEXP_CALL)

        case NodeType.CTYPE:
        case NodeType.CCLASS:
            if (!exact) n = node;
            break;

        case NodeType.LIST:
            n = getHeadValueNode(((ListNode)node).value, exact);
            break;

        case NodeType.STR:
            StringNode sn = (StringNode)node;
            if (sn.end <= sn.p) break; // ???

            if (exact && !sn.isRaw() && isIgnoreCase(regex.options)){
                // nothing
            } else {
                n = node;
            }
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.lower > 0) {
                if (qn.headExact != null) {
                    n = qn.headExact;
                } else {
                    n = getHeadValueNode(qn.target, exact);
                }
            }
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;

            switch (en.type) {
            case EncloseType.OPTION:
                int options = regex.options;
                regex.options = en.option;
                n = getHeadValueNode(en.target, exact);
                regex.options = options;
                break;

            case EncloseType.MEMORY:
            case EncloseType.STOP_BACKTRACK:
            case EncloseNode.CONDITION:
                n = getHeadValueNode(en.target, exact);
                break;

            case EncloseType.ABSENT:
            case EncloseType.DEFINE:
                break;
            } // inner switch
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            if (an.type == AnchorType.PREC_READ) n = getHeadValueNode(an.target, exact);
            break;

        default:
            break;
        } // switch

        return n;
    }

    // true: invalid
    private boolean checkTypeTree(Node node, int typeMask, int encloseMask, int anchorMask) {
        if ((node.getType2Bit() & typeMask) == 0) return true;

        boolean invalid = false;

        switch(node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                invalid = checkTypeTree(can.value, typeMask, encloseMask, anchorMask);
            } while (!invalid && (can = can.tail) != null);
            break;

        case NodeType.QTFR:
            invalid = checkTypeTree(((QuantifierNode)node).target, typeMask, encloseMask, anchorMask);
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if ((en.type & encloseMask) == 0) return true;
            invalid = checkTypeTree(en.target, typeMask, encloseMask, anchorMask);
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            if ((an.type & anchorMask) == 0) return true;

            boolean perlLookAhead = syntax.op2OptionPerl()
                    && (an.type == AnchorType.PREC_READ
                            || an.type == AnchorType.PREC_READ_NOT);
            if (!perlLookAhead && an.target != null) {
                invalid = checkTypeTree(an.target, typeMask, encloseMask, anchorMask);
            }
            break;

        default:
            break;

        } // switch

        return invalid;
    }

    private static final int RECURSION_EXIST       = 1;
    private static final int RECURSION_INFINITE    = 2;
    private int subexpInfRecursiveCheck(Node node, boolean head) {
        int r = 0;

        switch (node.getType()) {
        case NodeType.LIST:
            int min;
            ListNode x = (ListNode)node;
            do {
                int ret = subexpInfRecursiveCheck(x.value, head);
                if (ret == RECURSION_INFINITE) return ret;
                r |= ret;
                if (head) {
                    min = getMinMatchLength(x.value);
                    if (min != 0) head = false;
                }
            } while ((x = x.tail) != null);
            break;

        case NodeType.ALT:
            ListNode can = (ListNode)node;
            r = RECURSION_EXIST;
            do {
                int ret = subexpInfRecursiveCheck(can.value, head);
                if (ret == RECURSION_INFINITE) return ret;
                r &= ret;
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            r = subexpInfRecursiveCheck(qn.target, head);
            if (r == RECURSION_EXIST) {
                if (qn.lower == 0) r = 0;
            }
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
            case AnchorType.PREC_READ_NOT:
            case AnchorType.LOOK_BEHIND:
            case AnchorType.LOOK_BEHIND_NOT:
                r = subexpInfRecursiveCheck(an.target, head);
                break;
            } // inner switch
            break;

        case NodeType.CALL:
            r = subexpInfRecursiveCheck(((CallNode)node).target, head);
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (en.isMark2()) {
                return 0;
            } else if (en.isMark1()) {
                return !head ? RECURSION_EXIST : RECURSION_INFINITE;
                // throw exception here ???
            } else {
                en.setMark2();
                r = subexpInfRecursiveCheck(en.target, head);
                en.clearMark2();
            }
            break;

        default:
            break;
        } // switch
        return r;
    }

    protected final int subexpInfRecursiveCheckTrav(Node node) {
        int r = 0;

        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                r = subexpInfRecursiveCheckTrav(can.value);
            } while (r == 0 && (can = can.tail) != null);
            break;

        case NodeType.QTFR:
            r = subexpInfRecursiveCheckTrav(((QuantifierNode)node).target);
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
            case AnchorType.PREC_READ_NOT:
            case AnchorType.LOOK_BEHIND:
            case AnchorType.LOOK_BEHIND_NOT:
                r = subexpInfRecursiveCheckTrav(an.target);
                break;
            } // inner switch
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (en.isRecursion()) {
                en.setMark1();
                r = subexpInfRecursiveCheck(en.target, true);
                if (r > 0) newValueException(NEVER_ENDING_RECURSION);
                en.clearMark1();
            }
            r = subexpInfRecursiveCheckTrav(en.target);
            break;

        default:
            break;
        } // switch

        return r;
    }

    private int subexpRecursiveCheck(Node node) {
        int r = 0;

        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                r |= subexpRecursiveCheck(can.value);
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            r = subexpRecursiveCheck(((QuantifierNode)node).target);
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
            case AnchorType.PREC_READ_NOT:
            case AnchorType.LOOK_BEHIND:
            case AnchorType.LOOK_BEHIND_NOT:
                r = subexpRecursiveCheck(an.target);
                break;
            } // inner switch
            break;

        case NodeType.CALL:
            CallNode cn = (CallNode)node;
            r = subexpRecursiveCheck(cn.target);
            if (r != 0) cn.setRecursion();
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (en.isMark2()) {
                return 0;
            } else if (en.isMark1()) {
                return 1; /* recursion */
            } else {
                en.setMark2();
                r = subexpRecursiveCheck(en.target);
                en.clearMark2();
            }
            break;

        default:
            break;
        } // switch

        return r;
    }

    private static final int FOUND_CALLED_NODE  = 1;
    protected final int subexpRecursiveCheckTrav(Node node) {
        int r = 0;

        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                int ret = subexpRecursiveCheckTrav(can.value);
                if (ret == FOUND_CALLED_NODE) {
                    r = FOUND_CALLED_NODE;
                }
                // else if (ret < 0) return ret; ???
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            r = subexpRecursiveCheckTrav(qn.target);
            if (qn.upper == 0) {
                if (r == FOUND_CALLED_NODE) qn.isRefered = true;
            }
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
            case AnchorType.PREC_READ_NOT:
            case AnchorType.LOOK_BEHIND:
            case AnchorType.LOOK_BEHIND_NOT:
                r = subexpRecursiveCheckTrav(an.target);
                break;
            } // inner switch
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (!en.isRecursion()) {
                if (en.isCalled()) {
                    en.setMark1();
                    r = subexpRecursiveCheck(en.target);
                    if (r != 0) en.setRecursion();
                    en.clearMark1();
                }
            }
            r = subexpRecursiveCheckTrav(en.target);
            if (en.isCalled()) r |= FOUND_CALLED_NODE;
            break;

        default:
            break;
        } // switch

        return r;
    }

    private void setCallAttr(CallNode cn) {
        EncloseNode en = cn.lexicalTarget != null
                ? cn.lexicalTarget
                : env.memNodes[cn.groupNum];
        if (en == null) {
            if (syntax.op2OptionPerl()) {
                newValueException(PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
            }
            newValueException(UNDEFINED_NAME_REFERENCE, cn.nameP, cn.nameEnd);
        }
        // PerlOnJava subroutine calls do not replace captures already visible
        // in the caller.  Generic Joni Perl-like syntaxes retain Joni's
        // established capture-publication behavior.
        cn.preserveCallerCaptures = "PERLONJAVA".equals(syntax.name);
        if ((cn.lexicalTarget == null && env.isMultiplexMemNode(cn.groupNum))
                || (isInsideDefine(en) && !isInsideDefine(cn))) {
            cn.setRecursion();
        }
        en.setCalled();
        cn.setTarget(en);
        env.btMemStart = BitStatus.bsOnAt(env.btMemStart, cn.groupNum);
        cn.unsetAddrList = env.unsetAddrList;
    }

    private static boolean isInsideDefine(Node node) {
        for (Node current = node.parent; current != null; current = current.parent) {
            if (current instanceof EncloseNode en && en.type == EncloseType.DEFINE) {
                return true;
            }
        }
        return false;
    }

    protected final void setupSubExpCall(Node node) {

        switch(node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode can = (ListNode)node;
            do {
                setupSubExpCall(can.value);
            } while ((can = can.tail) != null);
            break;

        case NodeType.QTFR:
            setupSubExpCall(((QuantifierNode)node).target);
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            if (en.recursionConditionNameP >= 0) {
                NameEntry ne = regex.nameToGroupNumbers(bytes,
                        en.recursionConditionNameP, en.recursionConditionNameEnd);
                if (ne == null) {
                    newValueException(UNDEFINED_NAME_REFERENCE,
                            en.recursionConditionNameP, en.recursionConditionNameEnd);
                }
                int[] refs = ne.getBackRefs();
                if (refs.length != 1 && !syntax.allowMultiplexDefinitionNameCall()) {
                    newValueException(MULTIPLEX_DEFINITION_NAME_CALL,
                            en.recursionConditionNameP, en.recursionConditionNameEnd);
                }
                en.recursionConditionGroup = refs[0];
            }
            if (en.assertionCondition != null) setupSubExpCall(en.assertionCondition);
            setupSubExpCall(en.target);
            break;

        case NodeType.CALL:
            CallNode cn = (CallNode)node;
            if (cn.groupNum != 0) {
                int gNum = cn.groupNum;

                if (Config.USE_NAMED_GROUP) {
                    if (env.numNamed > 0 && syntax.captureOnlyNamedGroup() && !isCaptureGroup(env.option)) {
                        newValueException(NUMBERED_BACKREF_OR_CALL_NOT_ALLOWED);
                    }
                } // USE_NAMED_GROUP
                if (gNum > env.numMem) newValueException(UNDEFINED_GROUP_REFERENCE, cn.nameP, cn.nameEnd);
                setCallAttr(cn);
            } else {
                if (Config.USE_NAMED_GROUP) {
                    if (Config.USE_PERL_SUBEXP_CALL && cn.nameP == cn.nameEnd) {
                        setCallAttr(cn);
                    } else {
                        NameEntry ne = regex.nameToGroupNumbers(cn.name, cn.nameP, cn.nameEnd);

                        if (ne == null) {
                            if (syntax.op2OptionPerl()) {
                                newValueException(PERL_REFERENCE_TO_NONEXISTENT_NAMED_GROUP);
                            }
                            newValueException(UNDEFINED_NAME_REFERENCE, cn.nameP, cn.nameEnd);
                        } else if (ne.backNum > 1 && !syntax.allowMultiplexDefinitionNameCall()) {
                            newValueException(MULTIPLEX_DEFINITION_NAME_CALL, cn.nameP, cn.nameEnd);
                        } else {
                            cn.groupNum = ne.backRef1; // ne.backNum == 1 ? ne.backRef1 : ne.backRefs[0]; // ??? need to check ?
                            if (ne.backNum == 1) {
                                cn.lexicalTarget = env.physicalNamedMemNode(
                                        ne.getPhysicalBackRefs()[0]);
                            }
                            if (ne.backNum > 1) cn.setRecursion();
                            setCallAttr(cn);
                        }
                    }
                }
            }
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
            case AnchorType.PREC_READ_NOT:
            case AnchorType.LOOK_BEHIND:
            case AnchorType.LOOK_BEHIND_NOT:
                setupSubExpCall(an.target);
                break;
            }
            break;

        } // switch
    }

    /* divide different length alternatives in look-behind.
    (?<=A|B) ==> (?<=A)|(?<=B)
    (?<!A|B) ==> (?<!A)(?<!B)
     */
    private Node divideLookBehindAlternatives(Node node) {
        AnchorNode an = (AnchorNode)node;
        int anchorType = an.type;
        Node head = an.target;
        Node np = ((ListNode)head).value;

        node.replaceWith(head);

        Node tmp = node;
        node = head;
        head = tmp;

        ((ListNode)node).setValue(head);
        ((AnchorNode)head).setTarget(np);
        np = node;

        while ((np = ((ListNode)np).tail) != null) {
            AnchorNode insert = new AnchorNode(anchorType);
            insert.setTarget(((ListNode)np).value);
            ((ListNode)np).setValue(insert);
        }

        if (anchorType == AnchorType.LOOK_BEHIND_NOT) {
            np = node;
            do {
                ((ListNode)np).toListNode(); /* alt -> list */
            } while ((np = ((ListNode)np).tail) != null);
        }

        return node;
    }

    private Node setupLookBehind(AnchorNode node) {
        AcceptLengthInfo acceptLengths = getAcceptLengthInfo(node.target);
        if (acceptLengths != null && acceptLengths.hasAccept()) {
            int min = acceptLengths.acceptMin;
            int max = acceptLengths.acceptMax;
            if (acceptLengths.hasNormal()) {
                min = Math.min(min, acceptLengths.normalMin);
                max = Math.max(max, acceptLengths.normalMax);
            }
            if (syntax.op2OptionPerl() && max > 255) {
                newSyntaxException(PERL_LOOK_BEHIND_LONGER_THAN_255);
            }
            if (max <= 255) {
                node.variableLookBehindMin = min;
                node.variableLookBehindMax = max;
                node.variableLookBehindTargetLength = -1;
                warnVariableLookBehindCapture(node, min, max);
                returnCode = 0;
                return node;
            }
        }

        int len = getCharLengthTree(node.target);
        switch(returnCode) {
        case 0:
            if (syntax.op2OptionPerl() && len > 255) {
                newSyntaxException(PERL_LOOK_BEHIND_LONGER_THAN_255);
            }
            node.charLength = len;
            break;
        case GET_CHAR_LEN_VARLEN:
            if (!setupBoundedQuantifierLookBehind(node)) {
                CharLengthRange range = syntax.op2OptionPerl()
                        ? getCharLengthRange(node.target) : null;
                if (syntax.op2OptionPerl() && (range == null || range.max > 255)) {
                    newSyntaxException(PERL_LOOK_BEHIND_LONGER_THAN_255);
                }
                newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
            }
            break;
        case GET_CHAR_LEN_TOP_ALT_VARLEN:
            if (syntax.differentLengthAltLookBehind()) {
                CharLengthRange range = getCharLengthRange(node.target);
                if (range != null) {
                    warnVariableLookBehindCapture(node, range.min, range.max);
                }
                return divideLookBehindAlternatives(node);
            } else {
                newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
            }
        }
        return node;
    }

    private boolean setupBoundedQuantifierLookBehind(AnchorNode node) {
        if (!syntax.op2OptionPerl()) {
            return false;
        }
        if (node.target instanceof QuantifierNode quantifier && !isRepeatInfinite(quantifier.upper)) {
            int targetLength = getCharLengthTree(quantifier.target);
            if (returnCode == 0 && targetLength >= 0
                    && MinMaxLen.distanceMultiply(targetLength, quantifier.upper) <= 255) {
                node.variableLookBehindMin = quantifier.lower;
                node.variableLookBehindMax = quantifier.upper;
                node.variableLookBehindTargetLength = targetLength;
                returnCode = 0;
                return true;
            }
        }

        CharLengthRange range = getCharLengthRange(node.target);
        if (range == null || range.max > 255 || range.min == range.max) {
            return false;
        }
        node.variableLookBehindMin = range.min;
        node.variableLookBehindMax = range.max;
        node.variableLookBehindTargetLength = -1;
        warnVariableLookBehindCapture(node, range.min, range.max);
        returnCode = 0;
        return true;
    }

    private void warnVariableLookBehindCapture(AnchorNode node, int min, int max) {
        if (node.variableLookBehindCaptureWarned || min == max || !containsCapture(node.target,
                Collections.newSetFromMap(new IdentityHashMap<>()))) {
            return;
        }
        node.variableLookBehindCaptureWarned = true;
        String message = node.type == AnchorType.LOOK_BEHIND_NOT
                ? PERL_NEGATIVE_VARIABLE_LOOKBEHIND_CAPTURE
                : PERL_POSITIVE_VARIABLE_LOOKBEHIND_CAPTURE;
        env.warnings.warn(message, getEnd() - getBegin());
    }

    private boolean containsCapture(Node node, Set<Node> active) {
        if (node == null || !active.add(node)) return false;
        try {
            return switch (node.getType()) {
            case NodeType.LIST, NodeType.ALT -> {
                ListNode list = (ListNode)node;
                boolean found = false;
                do {
                    found |= containsCapture(list.value, active);
                } while (!found && (list = list.tail) != null);
                yield found;
            }
            case NodeType.QTFR -> containsCapture(((QuantifierNode)node).target, active);
            case NodeType.ANCHOR -> containsCapture(((AnchorNode)node).target, active);
            case NodeType.ENCLOSE -> {
                EncloseNode enclose = (EncloseNode)node;
                yield enclose.type == EncloseType.MEMORY
                        || containsCapture(enclose.target, active);
            }
            case NodeType.CALL -> containsCapture(((CallNode)node).target, active);
            default -> false;
            };
        } finally {
            active.remove(node);
        }
    }

    private static final class CharLengthRange {
        final int min;
        final int max;

        CharLengthRange(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    /**
     * Character widths for paths that complete normally and paths terminated
     * by ACCEPT. ACCEPT is a matcher-boundary exit, so nodes after it in a list
     * do not contribute to that path's effective lookbehind width.
     */
    private static final class AcceptLengthInfo {
        int normalMin = MinMaxLen.INFINITE_DISTANCE;
        int normalMax;
        int acceptMin = MinMaxLen.INFINITE_DISTANCE;
        int acceptMax;

        boolean hasNormal() {
            return normalMin != MinMaxLen.INFINITE_DISTANCE;
        }

        boolean hasAccept() {
            return acceptMin != MinMaxLen.INFINITE_DISTANCE;
        }

        void addNormal(int min, int max) {
            normalMin = Math.min(normalMin, min);
            normalMax = Math.max(normalMax, max);
        }

        void addAccept(int min, int max) {
            acceptMin = Math.min(acceptMin, min);
            acceptMax = Math.max(acceptMax, max);
        }
    }

    private AcceptLengthInfo getAcceptLengthInfo(Node node) {
        return getAcceptLengthInfo(node,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private AcceptLengthInfo getAcceptLengthInfo(Node node, Set<Node> activeCalls) {
        if (node instanceof ControlVerbNode control) {
            AcceptLengthInfo info = new AcceptLengthInfo();
            if (control.kind == ControlVerbNode.Kind.ACCEPT) info.addAccept(0, 0);
            else info.addNormal(0, 0);
            return info;
        }
        if (node instanceof CalloutNode) return null;

        switch (node.getType()) {
        case NodeType.LIST: {
            AcceptLengthInfo result = new AcceptLengthInfo();
            result.addNormal(0, 0);
            ListNode list = (ListNode)node;
            do {
                AcceptLengthInfo item = getAcceptLengthInfo(list.value, activeCalls);
                if (item == null) return null;
                AcceptLengthInfo next = new AcceptLengthInfo();
                if (result.hasAccept()) {
                    next.addAccept(result.acceptMin, result.acceptMax);
                }
                if (result.hasNormal() && item.hasAccept()) {
                    next.addAccept(
                            MinMaxLen.distanceAdd(result.normalMin, item.acceptMin),
                            MinMaxLen.distanceAdd(result.normalMax, item.acceptMax));
                }
                if (result.hasNormal() && item.hasNormal()) {
                    next.addNormal(
                            MinMaxLen.distanceAdd(result.normalMin, item.normalMin),
                            MinMaxLen.distanceAdd(result.normalMax, item.normalMax));
                }
                result = next;
            } while ((list = list.tail) != null);
            return result;
        }
        case NodeType.ALT: {
            AcceptLengthInfo result = new AcceptLengthInfo();
            ListNode alt = (ListNode)node;
            do {
                AcceptLengthInfo branch = getAcceptLengthInfo(alt.value, activeCalls);
                if (branch == null) return null;
                if (branch.hasNormal()) result.addNormal(branch.normalMin, branch.normalMax);
                if (branch.hasAccept()) result.addAccept(branch.acceptMin, branch.acceptMax);
            } while ((alt = alt.tail) != null);
            return result;
        }
        case NodeType.STR: {
            StringNode string = (StringNode)node;
            int length = string.length(enc);
            AcceptLengthInfo info = new AcceptLengthInfo();
            info.addNormal(string.isAmbig() && string.isDontGetOptInfo() && length > 1 ? 1 : length,
                    length);
            return info;
        }
        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY: {
            AcceptLengthInfo info = new AcceptLengthInfo();
            info.addNormal(1, 1);
            return info;
        }
        case NodeType.QTFR: {
            QuantifierNode quantifier = (QuantifierNode)node;
            if (isRepeatInfinite(quantifier.upper)) return null;
            AcceptLengthInfo target = getAcceptLengthInfo(quantifier.target, activeCalls);
            if (target == null) return null;
            AcceptLengthInfo info = new AcceptLengthInfo();
            if (target.hasNormal()) {
                info.addNormal(
                        MinMaxLen.distanceMultiply(target.normalMin, quantifier.lower),
                        MinMaxLen.distanceMultiply(target.normalMax, quantifier.upper));
            } else if (quantifier.lower == 0) {
                info.addNormal(0, 0);
            }
            if (target.hasAccept() && quantifier.upper > 0) {
                int repeatsBeforeAccept = target.hasNormal() ? quantifier.upper - 1 : 0;
                info.addAccept(target.acceptMin,
                        MinMaxLen.distanceAdd(
                                MinMaxLen.distanceMultiply(target.normalMax, repeatsBeforeAccept),
                                target.acceptMax));
            }
            return info;
        }
        case NodeType.ENCLOSE: {
            EncloseNode enclose = (EncloseNode)node;
            if (enclose.type == EncloseType.ABSENT) return null;
            if (enclose.type == EncloseType.DEFINE) {
                AcceptLengthInfo info = new AcceptLengthInfo();
                info.addNormal(0, 0);
                return info;
            }
            return getAcceptLengthInfo(enclose.target, activeCalls);
        }
        case NodeType.ANCHOR: {
            // Nested assertions are their own ACCEPT boundary.
            AcceptLengthInfo info = new AcceptLengthInfo();
            info.addNormal(0, 0);
            return info;
        }
        case NodeType.CALL: {
            CallNode call = (CallNode)node;
            if (!activeCalls.add(call.target)) return null;
            try {
                AcceptLengthInfo target = getAcceptLengthInfo(call.target, activeCalls);
                if (target == null) return null;

                // ACCEPT terminates the called subexpression rather than the
                // containing lookbehind, so both target exit kinds are normal
                // completions at the call site.
                AcceptLengthInfo info = new AcceptLengthInfo();
                if (target.hasNormal()) {
                    info.addNormal(target.normalMin, target.normalMax);
                }
                if (target.hasAccept()) {
                    info.addNormal(target.acceptMin, target.acceptMax);
                }
                return info;
            } finally {
                activeCalls.remove(call.target);
            }
        }
        default:
            return null;
        }
    }

    private CharLengthRange getCharLengthRange(Node node) {
        return getCharLengthRange(node,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private CharLengthRange getCharLengthRange(Node node, Set<Node> activeCalls) {
        switch (node.getType()) {
        case NodeType.LIST: {
            int min = 0;
            int max = 0;
            ListNode list = (ListNode)node;
            do {
                CharLengthRange item = getCharLengthRange(list.value, activeCalls);
                if (item == null) return null;
                min = MinMaxLen.distanceAdd(min, item.min);
                max = MinMaxLen.distanceAdd(max, item.max);
            } while ((list = list.tail) != null);
            return new CharLengthRange(min, max);
        }
        case NodeType.ALT: {
            int min = MinMaxLen.INFINITE_DISTANCE;
            int max = 0;
            ListNode alt = (ListNode)node;
            do {
                CharLengthRange branch = getCharLengthRange(alt.value, activeCalls);
                if (branch == null) return null;
                min = Math.min(min, branch.min);
                max = Math.max(max, branch.max);
            } while ((alt = alt.tail) != null);
            return new CharLengthRange(min, max);
        }
        case NodeType.STR: {
            StringNode string = (StringNode)node;
            int length = string.length(enc);
            return new CharLengthRange(
                    string.isAmbig() && string.isDontGetOptInfo() && length > 1 ? 1 : length,
                    length);
        }
        case NodeType.CTYPE:
        case NodeType.CCLASS:
        case NodeType.CANY:
            return new CharLengthRange(1, 1);
        case NodeType.QTFR: {
            QuantifierNode quantifier = (QuantifierNode)node;
            if (isRepeatInfinite(quantifier.upper)) return null;
            CharLengthRange target = getCharLengthRange(quantifier.target, activeCalls);
            if (target == null) return null;
            return new CharLengthRange(
                    MinMaxLen.distanceMultiply(target.min, quantifier.lower),
                    MinMaxLen.distanceMultiply(target.max, quantifier.upper));
        }
        case NodeType.ENCLOSE: {
            EncloseNode enclose = (EncloseNode)node;
            if (enclose.type == EncloseType.ABSENT) return null;
            if (enclose.type == EncloseType.DEFINE) return new CharLengthRange(0, 0);
            return getCharLengthRange(enclose.target, activeCalls);
        }
        case NodeType.ANCHOR:
            return new CharLengthRange(0, 0);
        case NodeType.CALL: {
            CallNode call = (CallNode)node;
            if (!activeCalls.add(call.target)) return null;
            try {
                return getCharLengthRange(call.target, activeCalls);
            } finally {
                activeCalls.remove(call.target);
            }
        }
        default:
            return null;
        }
    }

    private void nextSetup(Node node, Node nextNode) {
        retry: while(true) {

        int type = node.getType();
        if (type == NodeType.QTFR) {
            QuantifierNode qn = (QuantifierNode)node;
            if (qn.greedy && isRepeatInfinite(qn.upper)) {
                if (Config.USE_QTFR_PEEK_NEXT) {
                    StringNode n = (StringNode)getHeadValueNode(nextNode, true);
                    /* '\0': for UTF-16BE etc... */
                    if (n != null && n.bytes[n.p] != 0) {
                        qn.nextHeadExact = n;
                    }
                } // USE_QTFR_PEEK_NEXT

                /* automatic possessification a*b ==> (?>a*)b */
                if (qn.lower <= 1) {
                    if (qn.target.isSimple()) {
                        Node x = getHeadValueNode(qn.target, false);
                        if (x != null) {
                            Node y = getHeadValueNode(nextNode, false);
                            if (y != null && isNotIncluded(x, y)) {
                                EncloseNode en = new EncloseNode(EncloseType.STOP_BACKTRACK);
                                en.setStopBtSimpleRepeat();
                                node.replaceWith(en);
                                en.setTarget(node);
                            }
                        }
                    }
                }
            }
        } else if (type == NodeType.ENCLOSE) {
            EncloseNode en = (EncloseNode)node;
            if (en.isMemory()) {
                node = en.target;
                continue retry;
            }
        }

        break;
        } // while
    }

    private void updateStringNodeCaseFoldSingleByte(StringNode sn, byte[]toLower) {
        int end = sn.end;
        byte[]bytes = sn.bytes;
        int sp = 0;
        int p = sn.p;

        while (p < end) {
            byte lower = toLower[bytes[p] & 0xff];
            if (lower != bytes[p]) {
                byte[]sbuf = new byte[end - sn.p];
                System.arraycopy(bytes, sn.p, sbuf, 0, sp);

                while (p < end) sbuf[sp++] = toLower[bytes[p++] & 0xff];

                sn.set(sbuf, 0, sp);
                break;
            } else {
                sp++;
                p++;
            }
        }
    }

    private void updateStringNodeCaseFoldMultiByte(StringNode sn) {
        byte[]bytes = sn.bytes;
        int end = sn.end;
        value = sn.p;
        int sp = 0;
        byte[]buf = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN];

        while (value < end) {
            int ovalue = value;
            int len = enc.mbcCaseFold(regex.caseFoldFlagFor(regex.options), bytes, this, end, buf);

            for (int i = 0; i < len; i++) {
                if (bytes[ovalue + i] != buf[i]) {

                    byte[]sbuf = new byte[sn.length() << 1];
                    System.arraycopy(bytes, sn.p, sbuf, 0, ovalue - sn.p);
                    value = ovalue;
                    while (value < end) {
                        len = enc.mbcCaseFold(regex.caseFoldFlagFor(regex.options), bytes, this, end, buf);
                        for (i = 0; i < len; i++) {
                            if (sp >= sbuf.length) {
                                byte[]tmp = new byte[sbuf.length << 1];
                                System.arraycopy(sbuf, 0, tmp, 0, sbuf.length);
                                sbuf = tmp;
                            }
                            sbuf[sp++] = buf[i];
                        }
                    }
                    sn.set(sbuf, 0, sp);
                    return;
                }
            }
            sp += len;
        }
    }

    private void updateStringNodeCaseFold(Node node) {
        StringNode sn = (StringNode)node;
        byte[] toLower = enc.toLowerCaseTable();
        if (toLower != null) {
            updateStringNodeCaseFoldSingleByte(sn, toLower);
        } else {
            updateStringNodeCaseFoldMultiByte(sn);
        }
    }

    private Node expandCaseFoldMakeRemString(byte[]bytes, int p, int end) {
        StringNode node = new StringNode(bytes, p, end);

        boolean singleSourceCharacter = enc.strLength(bytes, p, end) == 1;

        updateStringNodeCaseFold(node);
        if (singleSourceCharacter && node.length(enc) > 1) {
            node.setDebugSingleSourceMultiFold();
        }
        node.setAmbig();
        node.setDontGetOptInfo();
        return node;
    }

    private boolean isCaseFoldVariableLength(int itemNum, CaseFoldCodeItem[] items, int slen) {
        for(int i = 0; i < itemNum; i++) {
            if (items[i].byteLen != slen || items[i].code.length != 1) return true;
        }
        return false;
    }

    private boolean expandCaseFoldStringAlt(int itemNum, CaseFoldCodeItem[]items,
                                              byte[]bytes, int p, int slen, int end, ObjPtr<Node> node) {
        boolean varlen = false;
        for (int i=0; i<itemNum; i++) {
            if (items[i].byteLen != slen) {
                varlen = true;
                break;
            }
        }

        ListNode varANode = null, altNode, listNode;
        if (varlen) {
            node.p = varANode = newAlt(null, null);

            listNode = newList(null, null);
            varANode.setValue(listNode);

            altNode = newAlt(null, null);
            listNode.setValue(altNode);
        } else {
            node.p = altNode = newAlt(null, null);
        }

        StringNode snode = new StringNode(bytes, p, p + slen);
        altNode.setValue(snode);

        for (int i=0; i<itemNum; i++) {
            snode = new StringNode();

            for (int j = 0; j < items[i].code.length; j++) snode.catCode(items[i].code[j], enc);

            ListNode an = newAlt(null, null);
            if (items[i].byteLen != slen) {
                int q = p + items[i].byteLen;
                if (q < end) {
                    Node rem = expandCaseFoldMakeRemString(bytes, q, end);

                    listNode = ListNode.listAdd(null, snode);
                    ListNode.listAdd(listNode, rem);
                    an.setValue(listNode);
                } else {
                    an.setValue(snode);
                }
                varANode.setTail(an);
                varANode = an;
            } else {
                an.setValue(snode);
                altNode.setTail(an);
                altNode = an;
            }
        }
        return varlen;
    }

    private static final int THRESHOLD_CASE_FOLD_ALT_FOR_EXPANSION = 8;

    private boolean perlAsciiStrictSourceCrossesIntoAscii(byte[] bytes, int p, int end,
                                                           int codePoint) {
        if (Encoding.isAscii(codePoint)) return false;

        int firstEnd = p + enc.length(bytes, p, end);
        CaseFoldCodeItem[] items = enc.caseFoldCodesByString(
                regex.caseFoldFlag, bytes, p, end);
        for (CaseFoldCodeItem item : items) {
            for (int foldedCodePoint : item.code) {
                if (Encoding.isAscii(foldedCodePoint)) return true;
            }
            int itemEnd = p + item.byteLen;
            if (itemEnd > firstEnd && itemEnd <= end
                    && !perlAsciiStrictAllNonAscii(bytes, p, itemEnd)) {
                return true;
            }
        }
        return false;
    }

    private Node perlAsciiStrictProtectedFoldNode(byte[] bytes, int p, int end) {
        StringNode exact = new StringNode(bytes, p, end);
        exact.setRaw();
        ListNode alternatives = newAlt(exact, null);
        ListNode tail = alternatives;

        CaseFoldCodeItem[] items = enc.caseFoldCodesByString(
                regex.caseFoldFlag, bytes, p, end);
        for (CaseFoldCodeItem item : items) {
            if (item.byteLen != end - p) continue;
            boolean allNonAscii = true;
            for (int foldedCodePoint : item.code) {
                if (Encoding.isAscii(foldedCodePoint)) {
                    allNonAscii = false;
                    break;
                }
            }
            if (!allNonAscii) continue;

            StringNode folded = new StringNode();
            for (int foldedCodePoint : item.code) folded.catCode(foldedCodePoint, enc);
            folded.setRaw();
            ListNode alternative = newAlt(folded, null);
            tail.setTail(alternative);
            tail = alternative;
        }
        return alternatives.tail == null ? exact : alternatives;
    }

    private boolean perlAsciiStrictAllNonAscii(byte[] bytes, int p, int end) {
        while (p < end) {
            if (Encoding.isAscii(enc.mbcToCode(bytes, p, end))) return false;
            p += enc.length(bytes, p, end);
        }
        return true;
    }

    private int perlAsciiStrictSafeMultiSourceEnd(byte[] bytes, int p, int end,
                                                   int singleEnd) {
        int safeEnd = singleEnd;
        CaseFoldCodeItem[] items = enc.caseFoldCodesByString(
                regex.caseFoldFlag, bytes, p, end);
        for (CaseFoldCodeItem item : items) {
            int itemEnd = p + item.byteLen;
            if (itemEnd <= singleEnd || itemEnd > end ||
                    !perlAsciiStrictAllNonAscii(bytes, p, itemEnd)) continue;

            boolean allNonAscii = true;
            for (int foldedCodePoint : item.code) {
                if (Encoding.isAscii(foldedCodePoint)) {
                    allNonAscii = false;
                    break;
                }
            }
            if (allNonAscii && itemEnd > safeEnd) safeEnd = itemEnd;
        }
        return safeEnd;
    }

    private Node protectPerlAsciiStrictCrossings(StringNode source, int state) {
        byte[] bytes = source.bytes;
        int segmentStart = source.p;
        int p = source.p;
        ListNode root = null;
        ListNode tail = null;

        while (p < source.end) {
            int codePoint = enc.mbcToCode(bytes, p, source.end);
            int next = p + enc.length(bytes, p, source.end);
            if (perlAsciiStrictSourceCrossesIntoAscii(
                    bytes, p, source.end, codePoint)) {
                next = perlAsciiStrictSafeMultiSourceEnd(
                        bytes, p, source.end, next);
                if (segmentStart < p) {
                    ListNode segment = ListNode.newList(
                            new StringNode(bytes, segmentStart, p), null);
                    if (root == null) root = segment;
                    else tail.setTail(segment);
                    tail = segment;
                }

                ListNode segment = ListNode.newList(
                        perlAsciiStrictProtectedFoldNode(bytes, p, next), null);
                if (root == null) root = segment;
                else tail.setTail(segment);
                tail = segment;
                segmentStart = next;
            }
            p = next;
        }

        if (root == null) return source;
        if (segmentStart < source.end) {
            tail.setTail(ListNode.newList(
                    new StringNode(bytes, segmentStart, source.end), null));
        }
        source.replaceWith(root);
        setupTree(root, state);
        return root;
    }

    private Node expandCaseFoldString(Node node) {
        StringNode sn = (StringNode)node;

        if (sn.isAmbig() || sn.length() <= 0) return node;

        byte[]bytes = sn.bytes;
        int p = sn.p;
        int end = sn.end;
        int altNum = 1;

        ListNode topRoot = null, root = null;
        ObjPtr<Node> prevNode = new ObjPtr<>();
        StringNode stringNode = null;

        while (p < end) {
            CaseFoldCodeItem[]items = enc.caseFoldCodesByString(
                    regex.caseFoldFlagFor(regex.options), bytes, p, end);
            int len = enc.length(bytes, p, end);

            if (items.length == 0 || !isCaseFoldVariableLength(items.length, items, len)) {
                if (stringNode == null) {
                    if (root == null && prevNode.p != null) {
                        topRoot = root = ListNode.listAdd(null, prevNode.p);
                    }

                    prevNode.p = stringNode = new StringNode(); // onig_node_new_str(NULL, NULL);

                    if (root != null) ListNode.listAdd(root, stringNode);

                }

                stringNode.catBytes(bytes, p, p + len);
            } else {
                altNum *= (items.length + 1);
                if (altNum > THRESHOLD_CASE_FOLD_ALT_FOR_EXPANSION) break;
                if (stringNode != null) {
                    updateStringNodeCaseFold(stringNode);
                    stringNode.setAmbig();
                }

                if (root == null && prevNode.p != null) {
                    topRoot = root = ListNode.listAdd(null, prevNode.p);
                }

                if (expandCaseFoldStringAlt(items.length, items, bytes, p, len, end, prevNode)) { // if (r == 1)
                    if (root == null) {
                        topRoot = (ListNode)prevNode.p;
                    } else {
                        ListNode.listAdd(root, prevNode.p);
                    }

                    root = (ListNode)((ListNode)prevNode.p).value;
                } else { /* r == 0 */
                    if (root != null) ListNode.listAdd(root, prevNode.p);
                }
                stringNode = null;
            }
            p += len;
        }

        if (stringNode != null) {
            updateStringNodeCaseFold(stringNode);
            stringNode.setAmbig();
        }

        if (p < end) {
            Node srem = expandCaseFoldMakeRemString(bytes, p, end);

            if (prevNode.p != null && root == null) {
                topRoot = root = ListNode.listAdd(null, prevNode.p);
            }

            if (root == null) {
                prevNode.p = srem;
            } else {
                ListNode.listAdd(root, srem);
            }
        }
        /* ending */
        Node xnode = topRoot != null ? topRoot : prevNode.p;
        xnode = addPerlReverseFoldPartitions(sn, xnode);
        xnode = addPerlSimpleFoldClassPartitions(sn, xnode);

        node.replaceWith(xnode);
        return xnode;
    }

    private Node addPerlSimpleFoldClassPartitions(StringNode source, Node expanded) {
        if (!syntax.op2OptionPerl() || Option.isPerlAsciiStrict(regex.options)
                || Option.isPerlBytePattern(regex.options)) {
            return expanded;
        }

        ListNode root = null;
        ListNode tail = null;
        int literalStart = source.p;
        boolean hasPerlOnlySibling = false;

        for (int p = source.p; p < source.end;) {
            int next = p + enc.length(source.bytes, p, source.end);
            int codePoint = enc.mbcToCode(source.bytes, p, source.end);
            int foldLength = PerlCaseFold.simpleFoldClassLength(codePoint);
            // JCodings already supplies ordinary two-member case pairs.  Only
            // materialize a class when Perl's pinned relation contributes an
            // extra sibling such as Kelvin or Angstrom; otherwise this would
            // needlessly discard an exact-literal optimizer candidate.
            if (foldLength <= 2 && !perlSimpleFoldNeedsPartition(codePoint)
                    && perlSimpleFoldClassIsNative(source.bytes, p, next,
                    codePoint, foldLength)) {
                p = next;
                continue;
            }

            boolean hasSibling = false;
            for (int index = 0; index < foldLength; index++) {
                int folded = PerlCaseFold.simpleFoldClassCodePoint(codePoint, index);
                if (folded != codePoint && !PerlCaseFold.isTurkicSourceExcluded(folded)) {
                    hasSibling = true;
                    break;
                }
            }
            if (!hasSibling || PerlCaseFold.isTurkicSourceExcluded(codePoint)) {
                p = next;
                continue;
            }

            if (literalStart < p) {
                ListNode literal = ListNode.newList(
                        new StringNode(source.bytes, literalStart, p), null);
                if (root == null) root = literal;
                else tail.setTail(literal);
                tail = literal;
            }

            CClassNode closure = new CClassNode();
            for (int index = 0; index < foldLength; index++) {
                int folded = PerlCaseFold.simpleFoldClassCodePoint(codePoint, index);
                if (!PerlCaseFold.isTurkicSourceExcluded(folded)) {
                    closure.addCodeRange(env, folded, folded, false);
                }
            }
            ListNode closureNode = ListNode.newList(closure, null);
            if (root == null) root = closureNode;
            else tail.setTail(closureNode);
            tail = closureNode;

            literalStart = next;
            p = next;
            hasPerlOnlySibling = true;
        }

        if (!hasPerlOnlySibling) return expanded;
        if (literalStart < source.end) {
            ListNode literal = ListNode.newList(
                    new StringNode(source.bytes, literalStart, source.end), null);
            tail.setTail(literal);
        }
        ListNode alternatives = newAlt(expanded, null);
        alternatives.setTail(newAlt(root, null));
        return alternatives;
    }

    private boolean perlSimpleFoldNeedsPartition(int codePoint) {
        // JCodings exposes the case-fold query for these pinned pairs but does
        // not make its literal matcher accept the reverse source.  Keep this
        // exceptional relation explicit rather than broadening every ordinary
        // two-member literal and losing the exact-literal optimizer.
        return codePoint == 0x023a || codePoint == 0x023e
                || codePoint == 0x2c65 || codePoint == 0x2c66;
    }

    private boolean perlSimpleFoldClassIsNative(byte[] bytes, int p, int next,
                                                int codePoint, int foldLength) {
        if (foldLength <= 1) return true;

        CaseFoldCodeItem[] items = enc.caseFoldCodesByString(
                regex.caseFoldFlagFor(regex.options), bytes, p, next);
        for (int index = 0; index < foldLength; index++) {
            int sibling = PerlCaseFold.simpleFoldClassCodePoint(codePoint, index);
            if (sibling == codePoint) continue;

            boolean found = false;
            for (CaseFoldCodeItem item : items) {
                if (item.byteLen == next - p && item.code.length == 1
                        && item.code[0] == sibling) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private Node addPerlReverseFoldPartitions(StringNode source, Node expanded) {
        if (!syntax.op2OptionPerl() || Option.isPerlAsciiStrict(regex.options)
                || Option.isPerlBytePattern(regex.options)) {
            return expanded;
        }

        int sourceLength = 0;
        int canonicalLength = 0;
        boolean hasFullFold = false;
        for (int p = source.p; p < source.end;) {
            int codePoint = enc.mbcToCode(source.bytes, p, source.end);
            int fullLength = PerlCaseFold.fullFoldLength(codePoint);
            canonicalLength += fullLength == 0 ? 1 : fullLength;
            hasFullFold |= fullLength > 1;
            sourceLength++;
            p += enc.length(source.bytes, p, source.end);
        }
        if (!hasFullFold || sourceLength < 2 || canonicalLength <= sourceLength) {
            return expanded;
        }

        int[] canonical = new int[canonicalLength];
        int offset = 0;
        for (int p = source.p; p < source.end;) {
            int codePoint = enc.mbcToCode(source.bytes, p, source.end);
            int fullLength = PerlCaseFold.fullFoldLength(codePoint);
            if (fullLength == 0) {
                canonical[offset++] = codePoint < 0x80
                        ? Character.toLowerCase(codePoint) : codePoint;
            } else {
                for (int index = 0; index < fullLength; index++) {
                    canonical[offset++] = PerlCaseFold.fullFoldCodePoint(
                            codePoint, index);
                }
            }
            p += enc.length(source.bytes, p, source.end);
        }

        ArrayList<int[]> variants = new ArrayList<>();
        collectPerlReverseFoldPartitions(canonical, 0, new int[canonical.length],
                0, false, variants);
        if (variants.isEmpty()) return expanded;

        ListNode alternatives = newAlt(expanded, null);
        ListNode tail = alternatives;
        for (int[] variant : variants) {
            StringNode candidate = new StringNode();
            for (int codePoint : variant) candidate.catCode(codePoint, enc);
            candidate.setRaw();
            ListNode alternative = newAlt(candidate, null);
            tail.setTail(alternative);
            tail = alternative;
        }
        return alternatives;
    }

    private void collectPerlReverseFoldPartitions(int[] canonical, int offset,
            int[] path, int pathLength, boolean usedReverse,
            ArrayList<int[]> variants) {
        if (variants.size() >= THRESHOLD_CASE_FOLD_ALT_FOR_EXPANSION) return;
        if (offset == canonical.length) {
            if (usedReverse) {
                variants.add(java.util.Arrays.copyOf(path, pathLength));
            }
            return;
        }

        path[pathLength] = canonical[offset];
        collectPerlReverseFoldPartitions(canonical, offset + 1, path,
                pathLength + 1, usedReverse, variants);
        for (int length = 2; length <= 3 && offset + length <= canonical.length;
                length++) {
            int count = PerlCaseFold.reverseFullFoldSourceCount(
                    canonical, offset, length);
            for (int index = 0; index < count; index++) {
                path[pathLength] = PerlCaseFold.reverseFullFoldSourceAt(
                        canonical, offset, length, index);
                collectPerlReverseFoldPartitions(canonical, offset + length,
                        path, pathLength + 1, true, variants);
                if (variants.size() >= THRESHOLD_CASE_FOLD_ALT_FOR_EXPANSION) {
                    return;
                }
            }
        }
    }

    private Node expandPerlByteAsciiFoldString(StringNode source, int state) {
        ListNode root = null;
        ListNode sequenceTail = null;
        boolean expanded = false;

        for (int p = source.p; p < source.end;) {
            int next = p + enc.length(source.bytes, p, source.end);
            int codePoint = enc.mbcToCode(source.bytes, p, source.end);
            StringNode exact = new StringNode(source.bytes, p, next);
            exact.setRaw();
            ListNode alternatives = newAlt(exact, null);
            ListNode tail = alternatives;

            if (Encoding.isAscii(codePoint)) {
                int foldLength = PerlCaseFold.simpleFoldClassLength(codePoint);
                for (int index = 0; index < foldLength; index++) {
                    int folded = PerlCaseFold.simpleFoldClassCodePoint(codePoint, index);
                    if (folded == codePoint || !Encoding.isAscii(folded)) continue;
                    StringNode sibling = new StringNode();
                    sibling.catCode(folded, enc);
                    sibling.setRaw();
                    ListNode alternative = newAlt(sibling, null);
                    tail.setTail(alternative);
                    tail = alternative;
                    expanded = true;
                }
            }

            Node part = alternatives.tail == null ? exact : alternatives;
            ListNode segment = ListNode.newList(part, null);
            if (root == null) root = segment;
            else sequenceTail.setTail(segment);
            sequenceTail = segment;
            p = next;
        }

        if (!expanded) {
            source.setRaw();
            return source;
        }
        source.replaceWith(root);
        setupTree(root, state);
        return root;
    }

    private boolean isUnsafePerlMultiFoldOptimizationBoundary(StringNode node) {
        if (!syntax.op2OptionPerl() || node.length() == 0) return false;

        int first = enc.mbcToCode(node.bytes, node.p, node.end);
        if (PerlCaseFold.isMultiFoldComponent(first)) return true;

        int last = node.p;
        int p = node.p;
        while (p < node.end) {
            last = p;
            p += enc.length(node.bytes, p, node.end);
        }
        return PerlCaseFold.isMultiFoldComponent(
                enc.mbcToCode(node.bytes, last, node.end));
    }

    private void disableUnsafePerlMultiFoldOptimization(Node node) {
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            ListNode list = (ListNode)node;
            do {
                disableUnsafePerlMultiFoldOptimization(list.value);
            } while ((list = list.tail) != null);
            break;
        case NodeType.STR:
            StringNode string = (StringNode)node;
            if (isUnsafePerlMultiFoldOptimizationBoundary(string)) {
                string.setDontGetOptInfo();
            }
            break;
        default:
            break;
        }
    }

    private static final int CEC_THRES_NUM_BIG_REPEAT       = 512;
    private static final int CEC_INFINITE_NUM               = 0x7fffffff;

    private static final int CEC_IN_INFINITE_REPEAT         = (1<<0);
    private static final int CEC_IN_FINITE_REPEAT           = (1<<1);
    private static final int CEC_CONT_BIG_REPEAT            = (1<<2);

    protected final int setupCombExpCheck(Node node, int state) {
        int r = state;
        int ret;

        switch (node.getType()) {
        case NodeType.LIST:
            ListNode ln = (ListNode)node;

            do {
                r = setupCombExpCheck(ln.value, r);
                //prev = ((ConsAltNode)node).value;
            } while (r >= 0 && (ln = ln.tail) != null);
            break;

        case NodeType.ALT:
            ListNode an = (ListNode)node;
            do {
                ret = setupCombExpCheck(an.value, state);
                r |= ret;
            } while (ret >= 0 && (an = an.tail) != null);
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            int childState = state;
            int addState = 0;
            int varNum;

            if (!isRepeatInfinite(qn.upper)) {
                if (qn.upper > 1) {
                    /* {0,1}, {1,1} are allowed */
                    childState |= CEC_IN_FINITE_REPEAT;

                    /* check (a*){n,m}, (a+){n,m} => (a*){n,n}, (a+){n,n} */
                    if (env.backrefedMem == 0) {
                        if (qn.target.getType() == NodeType.ENCLOSE) {
                            EncloseNode en = (EncloseNode)qn.target;
                            if (en.type == EncloseType.MEMORY) {
                                if (en.target.getType() == NodeType.QTFR) {
                                    QuantifierNode q = (QuantifierNode)en.target;
                                    if (isRepeatInfinite(q.upper) && q.greedy == qn.greedy) {
                                        qn.upper = qn.lower == 0 ? 1 : qn.lower;
                                        if (qn.upper == 1) childState = state;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if ((state & CEC_IN_FINITE_REPEAT) != 0) {
                qn.combExpCheckNum = -1;
            } else {
                if (isRepeatInfinite(qn.upper)) {
                    varNum = CEC_INFINITE_NUM;
                    childState |= CEC_IN_INFINITE_REPEAT;
                } else {
                    varNum = qn.upper - qn.lower;
                }

                if (varNum >= CEC_THRES_NUM_BIG_REPEAT) addState |= CEC_CONT_BIG_REPEAT;

                if (((state & CEC_IN_INFINITE_REPEAT) != 0 && varNum != 0) ||
                   ((state & CEC_CONT_BIG_REPEAT) != 0 && varNum >= CEC_THRES_NUM_BIG_REPEAT)) {
                    if (qn.combExpCheckNum == 0) {
                        env.numCombExpCheck++;
                        qn.combExpCheckNum = env.numCombExpCheck;
                        if (env.currMaxRegNum > env.combExpMaxRegNum) {
                            env.combExpMaxRegNum = env.currMaxRegNum;
                        }
                    }
                }
            }
            r = setupCombExpCheck(qn.target, childState);
            r |= addState;
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch( en.type) {
            case EncloseNode.MEMORY:
                if (env.currMaxRegNum < en.regNum) {
                    env.currMaxRegNum = en.regNum;
                }
                r = setupCombExpCheck(en.target, state);
                break;

            default:
                r = setupCombExpCheck(en.target, state);
            } // inner switch
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (cn.isRecursion()) {
                    env.hasRecursion = true;
                } else {
                    r = setupCombExpCheck(cn.target, state);
                }
            } // USE_SUBEXP_CALL
            break;

        default:
            break;

        } // switch

        return r;
    }

    private static final int IN_ALT                     = (1<<0);
    private static final int IN_NOT                     = (1<<1);
    private static final int IN_REPEAT                  = (1<<2);
    private static final int IN_VAR_REPEAT              = (1<<3);
    private static final int IN_CALL                    = (1<<4);
    private static final int IN_RECCALL                 = (1<<5);
    private static final int IN_LOOKAROUND              = (1<<6);
    private static final int EXPAND_STRING_MAX_LENGTH   = 100;

    /* setup_tree does the following work.
    1. check empty loop. (set qn->target_empty_info)
    2. expand ignore-case in char class.
    3. set memory status bit flags. (reg->mem_stats)
    4. set qn->head_exact for [push, exact] -> [push_or_jump_exact1, exact].
    5. find invalid patterns in look-behind.
    6. expand repeated string.
    */
    protected final Node setupTree(Node node, int state) {
        restart: while (true) {
        switch (node.getType()) {
        case NodeType.LIST:
            ListNode lin = (ListNode)node;
            Node prev = null;
            do {
                setupTree(lin.value, state);
                if (prev != null) {
                    nextSetup(prev, lin.value);
                }
                prev = lin.value;
            } while ((lin = lin.tail) != null);
            break;

        case NodeType.ALT:
            ListNode aln = (ListNode)node;
            do {
                setupTree(aln.value, (state | IN_ALT));
            } while ((aln = aln.tail) != null);
            break;

        case NodeType.CCLASS:
            break;

        case NodeType.STR:
            if (isIgnoreCase(regex.options) && !((StringNode)node).isRaw()) {
                if (Option.isPerlBytePattern(regex.options)) {
                    node = expandPerlByteAsciiFoldString((StringNode)node, state);
                } else if (Option.isPerlAsciiStrict(regex.options)) {
                    Node protectedNode = protectPerlAsciiStrictCrossings(
                            (StringNode)node, state);
                    node = protectedNode == node
                            ? expandCaseFoldString(node) : protectedNode;
                } else {
                    node = expandCaseFoldString(node);
                }
                disableUnsafePerlMultiFoldOptimization(node);
            }
            break;

        case NodeType.CTYPE:
        case NodeType.CANY:
            break;

        case NodeType.CALL: // if (Config.USE_SUBEXP_CALL) ?
            break;

        case NodeType.BREF:
            BackRefNode br = (BackRefNode)node;
            for (int i=0; i<br.backNum; i++) {
                if (invalidBackrefNode(br.back[i])) {
                    if (!syntax.op3OptionECMAScript()) newValueException(INVALID_BACKREF);
                } else {
                    env.backrefedMem = bsOnAt(env.backrefedMem, br.back[i]);
                    env.btMemStart = bsOnAt(env.btMemStart, br.back[i]);
                    if (Config.USE_BACKREF_WITH_LEVEL) {
                        if (br.isNestLevel()) {
                            env.btMemEnd = bsOnAt(env.btMemEnd, br.back[i]);
                        }
                    } // USE_BACKREF_AT_LEVEL
                    env.memNodes[br.back[i]].setMemBackrefed();
                }
            }
            break;

        case NodeType.QTFR:
            QuantifierNode qn = (QuantifierNode)node;
            Node target = qn.target;

            int perlTargetMin = -1;
            if (syntax.op2OptionPerl() && qn.isByNumber()) {
                perlTargetMin = getMinMatchLength(target);
                if (perlTargetMin == 0) {
                    perlSyntaxWarn(PERL_QUANTIFIER_ON_ZERO_LENGTH);
                }
            }

            if ((state & IN_REPEAT) != 0) qn.setInRepeat();

            if (isRepeatInfinite(qn.upper) || qn.lower >= 1) {
                int d = perlTargetMin >= 0
                        ? perlTargetMin : getMinMatchLength(target);
                if (d == 0) {
                    qn.targetEmptyInfo = TargetInfo.IS_EMPTY;
                    if (Config.USE_MONOMANIAC_CHECK_CAPTURES_IN_ENDLESS_REPEAT) {
                        int info = quantifiersMemoryInfo(target);
                        if (info > 0) qn.targetEmptyInfo = info;
                    } // USE_INFINITE_REPEAT_MONOMANIAC_MEM_STATUS_CHECK
                    // strange stuff here (turned off)
                }
            }

            state |= IN_REPEAT;
            if (qn.lower != qn.upper) state |= IN_VAR_REPEAT;

            target = setupTree(target, state);

            /* expand string */
            if (target.getType() == NodeType.STR
                    && !(target instanceof CalloutNode)
                    && !(target instanceof ControlVerbNode)) {
                StringNode sn = (StringNode)target;
                if (qn.lower > 1) {
                    StringNode str = new StringNode(sn.bytes, sn.p, sn.end);
                    str.flag = sn.flag;

                    int i;
                    int n = qn.lower;
                    int len = sn.length();
                    for (i = 1; i < n && (i + 1) * len <= EXPAND_STRING_MAX_LENGTH; i++) {
                        str.catBytes(sn.bytes, sn.p, sn.end);
                    }

                    if (i < qn.upper || isRepeatInfinite(qn.upper)) {
                        qn.lower -= i;
                        if (!isRepeatInfinite(qn.upper)) qn.upper -= i;
                        ListNode list = ListNode.newList(str, null);
                        qn.replaceWith(list);
                        ListNode.listAdd(list, qn);
                    } else {
                        qn.replaceWith(str);
                    }
                    break;
                }
            }

            if (Config.USE_OP_PUSH_OR_JUMP_EXACT) {
                if (qn.greedy && qn.targetEmptyInfo != 0) {
                    if (target.getType() == NodeType.QTFR) {
                        QuantifierNode tqn = (QuantifierNode)target;
                        if (tqn.headExact != null) {
                            qn.headExact = tqn.headExact;
                            tqn.headExact = null;
                        }
                    } else {
                        qn.headExact = getHeadValueNode(qn.target, true);
                    }
                }
            } // USE_OP_PUSH_OR_JUMP_EXACT
            break;

        case NodeType.ENCLOSE:
            EncloseNode en = (EncloseNode)node;
            switch (en.type) {
            case EncloseType.OPTION:
                int options = regex.options;
                regex.options = en.option;
                setupTree(en.target, state);
                regex.options = options;
                break;

            case EncloseType.MEMORY:
                if ((state & (IN_ALT | IN_NOT | IN_VAR_REPEAT | IN_CALL)) != 0) {
                    env.btMemStart = bsOnAt(env.btMemStart, en.regNum);
                    /* SET_ENCLOSE_STATUS(node, NST_MEM_IN_ALT_NOT); */
                }
                if (en.isCalled()) state |= IN_CALL;
                if (en.isRecursion()) {
                    state |= IN_RECCALL;
                } else if ((state & IN_RECCALL) != 0){
                    en.setRecursion();
                }

                setupTree(en.target, state);
                break;

            case EncloseType.STOP_BACKTRACK:
                setupTree(en.target, state);
                if (en.target.getType() == NodeType.QTFR) {
                    QuantifierNode tqn = (QuantifierNode)en.target;
                    if (isRepeatInfinite(tqn.upper) && tqn.lower <= 1 && tqn.greedy) {
                        /* (?>a*), a*+ etc... */
                        if (tqn.target.isSimple()) en.setStopBtSimpleRepeat();
                    }
                }
                break;

            case EncloseNode.CONDITION:
                if (Config.USE_NAMED_GROUP && en.assertionCondition == null
                        && en.calloutConditionId < 0 && en.recursionConditionGroup < 0) {
                    if (!en.isNameRef() && env.numNamed > 0 && syntax.captureOnlyNamedGroup() && !isCaptureGroup(env.option)) {
                        newValueException(NUMBERED_BACKREF_OR_CALL_NOT_ALLOWED);
                    }
                }
                if (en.assertionCondition == null && en.calloutConditionId < 0
                        && en.recursionConditionGroup < 0 && en.regNum > env.numMem
                        && !syntax.op2OptionPerl()) {
                    newValueException(INVALID_BACKREF);
                }
                if (en.recursionConditionGroup > env.numMem) newValueException(INVALID_BACKREF);
                if (en.assertionCondition != null) setupTree(en.assertionCondition, state);
                setupTree(en.target, state);
                break;

            case EncloseType.ABSENT:
                setupTree(en.target, state);
                break;

            case EncloseType.DEFINE:
                setupTree(en.target, state);
                break;
            } // inner switch
            break;

        case NodeType.ANCHOR:
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.PREC_READ:
                setupTree(an.target, (state | IN_LOOKAROUND));
                break;

            case AnchorType.PREC_READ_NOT:
                setupTree(an.target, (state | IN_NOT | IN_LOOKAROUND));
                break;

            case AnchorType.LOOK_BEHIND:
                int allowedInLookBehind = syntax.op2OptionPerl()
                        ? AnchorType.ALLOWED_IN_PERL_LB
                        : AnchorType.ALLOWED_IN_LB;
                if (checkTypeTree(an.target, NodeType.ALLOWED_IN_LB, EncloseType.ALLOWED_IN_LB, allowedInLookBehind)) newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
                node = setupLookBehind(an);
                if (node.getType() != NodeType.ANCHOR) continue restart;
                setupTree(((AnchorNode)node).target, (state | IN_LOOKAROUND));
                node = setupLookBehind(an);
                break;

            case AnchorType.LOOK_BEHIND_NOT:
                int allowedInNegativeLookBehind = syntax.op2OptionPerl()
                        ? AnchorType.ALLOWED_IN_PERL_LB_NOT
                        : AnchorType.ALLOWED_IN_LB_NOT;
                int allowedEnclosuresInNegativeLookBehind = syntax.op2OptionPerl()
                        ? EncloseType.ALLOWED_IN_LB
                        : EncloseType.ALLOWED_IN_LB_NOT;
                if (checkTypeTree(an.target, NodeType.ALLOWED_IN_LB, allowedEnclosuresInNegativeLookBehind, allowedInNegativeLookBehind)) newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
                node = setupLookBehind(an);
                if (node.getType() != NodeType.ANCHOR) continue restart;
                setupTree(((AnchorNode)node).target, (state | IN_NOT | IN_LOOKAROUND));
                node = setupLookBehind(an);
                break;

            case AnchorType.KEEP:
                if (syntax.op2OptionPerl() && (state & IN_LOOKAROUND) != 0) {
                    newSyntaxException(PERL_KEEP_NOT_PERMITTED_IN_LOOKAROUND);
                }
                break;

            } // inner switch
            break;
        } // switch
        return node;
        } // restart: while
    }

    private static final int MAX_NODE_OPT_INFO_REF_COUNT   = 5;
    private void optimizeNodeLeft(Node node, NodeOptInfo opt, OptEnvironment oenv) { // oenv remove, pass mmd
        opt.clear();
        opt.setBoundNode(oenv.mmd);

        switch (node.getType()) {
        case NodeType.LIST: {
            OptEnvironment nenv = new OptEnvironment();
            NodeOptInfo nopt = new NodeOptInfo();
            nenv.copy(oenv);
            ListNode lin = (ListNode)node;
            do {
                optimizeNodeLeft(lin.value, nopt, nenv);
                nenv.mmd.add(nopt.length);
                opt.concatLeftNode(nopt, enc);
            } while ((lin = lin.tail) != null);
            break;
        }

        case NodeType.ALT: {
            NodeOptInfo nopt = new NodeOptInfo();
            ListNode aln = (ListNode)node;
            do {
                optimizeNodeLeft(aln.value, nopt, oenv);
                if (aln == node) {
                    opt.copy(nopt);
                } else {
                    opt.altMerge(nopt, oenv);
                }
            } while ((aln = aln.tail) != null);
            break;
        }

        case NodeType.STR: {
            StringNode sn = (StringNode)node;

            if (sn instanceof CalloutNode callout && callout.dynamic) {
                opt.length.set(0, MinMaxLen.INFINITE_DISTANCE);
                break;
            }

            int slen = sn.length();

            if (!sn.isAmbig()) {
                opt.exb.concatStr(sn.bytes, sn.p, sn.end, sn.isRaw(), enc);
                opt.exb.ignoreCase = 0;

                if (slen > 0) {
                    opt.map.addChar(sn.bytes[sn.p], enc);
                }

                opt.length.set(slen, slen);
            } else {
                int min = slen;
                int max;
                if (sn.isDontGetOptInfo()) {
                    int n = sn.length(enc);
                    max = enc.maxLength() * n;
                    // A multi-character canonical fold can match one encoded
                    // source character (for example U+1F80 can match U+1F88).
                    // Its canonical byte length is therefore not a sound
                    // lower bound for a following optimizer candidate.
                    min = enc.minLength();
                } else {
                    opt.exb.concatStr(sn.bytes, sn.p, sn.end, sn.isRaw(), enc);
                    opt.exb.ignoreCase = 1;

                    if (slen > 0) {
                        opt.map.addCharAmb(sn.bytes, sn.p, sn.end, enc, oenv.caseFoldFlag);
                    }

                    max = slen;
                }
                opt.length.set(min, max);
            }

            if (opt.exb.length == slen) {
                opt.exb.reachEnd = true;
            }
            break;
        }

        case NodeType.CCLASS: {
            CClassNode cc = (CClassNode)node;
            /* no need to check ignore case. (setted in setup_tree()) */
            if (cc.hasDeferredProperties()) {
                // The unresolved class still consumes exactly one character,
                // but cannot contribute a speculative class-map candidate.
                // A host wide-scalar codec may encode that one logical value
                // in more bytes than Encoding.maxLength(), so its distance to
                // a following exact literal must remain unbounded.
                opt.length.set(enc.minLength(),
                        env.syntax.wideScalarCodec == null
                                ? enc.maxLength()
                                : MinMaxLen.INFINITE_DISTANCE);
            } else if (cc.mbuf != null || cc.isNot()) {
                int min = enc.minLength();
                int max = enc.maxLength();
                addPositiveSingletonClassMap(cc, opt.map);
                opt.length.set(min, max);
            } else {
                for (int i=0; i<BitSet.SINGLE_BYTE_SIZE; i++) {
                    boolean z = cc.bs.at(i);
                    if ((z && !cc.isNot()) || (!z && cc.isNot())) {
                        opt.map.addChar((byte)i, enc);
                    }
                }
                opt.length.set(1, 1);
            }
            break;
        }

        case NodeType.CTYPE: {
            int min;
            int max = enc.maxLength();
            if (max == 1) {
                min = 1;
                CTypeNode cn = (CTypeNode)node;

                int maxCode = cn.asciiRange ? 0x80 : BitSet.SINGLE_BYTE_SIZE;
                switch (cn.ctype) {
                case CharacterType.WORD:
                    if (cn.not) {
                        for (int i=0; i<BitSet.SINGLE_BYTE_SIZE; i++) {
                            if (!enc.isWord(i) || i >= maxCode) {
                                opt.map.addChar((byte)i, enc);
                            }
                        }
                    } else {
                        for (int i=0; i<maxCode; i++) {
                            if (enc.isWord(i)) {
                                opt.map.addChar((byte)i, enc);
                            }
                        }
                    }
                    break;
                } // inner switch
            } else {
                min = enc.minLength();
            }
            opt.length.set(min, max);
            break;
        }

        case NodeType.CANY: {
            opt.length.set(enc.minLength(), enc.maxLength());
            break;
        }

        case NodeType.ANCHOR: {
            AnchorNode an = (AnchorNode)node;
            switch (an.type) {
            case AnchorType.BEGIN_BUF:
            case AnchorType.BEGIN_POSITION:
            case AnchorType.BEGIN_LINE:
            case AnchorType.END_BUF:
            case AnchorType.SEMI_END_BUF:
            case AnchorType.END_LINE:
            case AnchorType.LOOK_BEHIND:        /* just for (?<=x).* */
            case AnchorType.PREC_READ_NOT:      /* just for (?!x).* */
                opt.anchor.add(an.type);
                break;

            case AnchorType.PREC_READ:
                NodeOptInfo nopt = new NodeOptInfo();
                optimizeNodeLeft(an.target, nopt, oenv);
                if (nopt.exb.length > 0) {
                    opt.expr.copy(nopt.exb);
                } else if (nopt.exm.length > 0) {
                    opt.expr.copy(nopt.exm);
                }
                opt.expr.reachEnd = false;
                if (nopt.map.value > 0) opt.map.copy(nopt.map);
                break;

            case AnchorType.LOOK_BEHIND_NOT:
                break;

            } // inner switch
            break;
        }

        case NodeType.BREF: {
            BackRefNode br = (BackRefNode)node;

            if (br.isRecursion()) {
                opt.length.set(0, MinMaxLen.INFINITE_DISTANCE);
                break;
            }

            Node[]nodes = oenv.scanEnv.memNodes;

            int min = 0;
            int max = 0;

            if (nodes != null && nodes[br.back[0]] != null) {
                min = getMinMatchLength(nodes[br.back[0]]);
                max = getMaxMatchLength(nodes[br.back[0]]);
            }

            for (int i=1; i<br.backNum; i++) {
                if (nodes[br.back[i]] != null) {
                    int tmin = getMinMatchLength(nodes[br.back[i]]);
                    int tmax = getMaxMatchLength(nodes[br.back[i]]);
                    if (min > tmin) min = tmin;
                    if (max < tmax) max = tmax;
                }
            }
            opt.length.set(min, max);
            break;
        }

        case NodeType.CALL: {
            if (Config.USE_SUBEXP_CALL) {
                CallNode cn = (CallNode)node;
                if (cn.isRecursion()) {
                    opt.length.set(0, MinMaxLen.INFINITE_DISTANCE);
                } else {
                    int safe = oenv.options;
                    int safeCaseFoldFlag = oenv.caseFoldFlag;
                    oenv.options = cn.target.option;
                    oenv.caseFoldFlag = regex.caseFoldFlagFor(cn.target.option);
                    optimizeNodeLeft(cn.target, opt, oenv);
                    oenv.options = safe;
                    oenv.caseFoldFlag = safeCaseFoldFlag;
                }
            } // USE_SUBEXP_CALL
            break;
        }

        case NodeType.QTFR: {
            NodeOptInfo nopt = new NodeOptInfo();
            QuantifierNode qn = (QuantifierNode)node;
            if (!isRepeatInfinite(qn.upper) && qn.lower > qn.upper) {
                // Perl accepts descending intervals after warning and treats
                // them as impossible. Do not publish an optimizer distance
                // with min > max: concatenating two such atoms can otherwise
                // index MinMaxLen's distance table with a negative value.
                break;
            }
            optimizeNodeLeft(qn.target, nopt, oenv);
            if (/*qn.lower == 0 &&*/ isRepeatInfinite(qn.upper)) {
                if (oenv.mmd.max == 0 && qn.target.getType() == NodeType.CANY) {
                    if (isMultiline(oenv.options)) {
                        opt.anchor.add(AnchorType.ANYCHAR_STAR_ML);
                    } else {
                        opt.anchor.add(AnchorType.ANYCHAR_STAR);
                    }
                }
            } /*else*/ {
                if (qn.lower > 0) {
                    opt.copy(nopt);
                    if (nopt.exb.length > 0) {
                        if (nopt.exb.reachEnd) {
                            int i;
                            for (i = 2; i <= qn.lower && !opt.exb.isFull(); i++) {
                                opt.exb.concat(nopt.exb, enc);
                            }
                            if (i < qn.lower) {
                                opt.exb.reachEnd = false;
                            }
                        }
                    }
                    if (qn.lower != qn.upper) {
                        opt.exb.reachEnd = false;
                        opt.exm.reachEnd = false;
                    }
                    if (qn.lower > 1) {
                        opt.exm.reachEnd = false;
                    }

                }
            }
            int min = MinMaxLen.distanceMultiply(nopt.length.min, qn.lower);
            int max;
            if (isRepeatInfinite(qn.upper)) {
                max = nopt.length.max > 0 ? MinMaxLen.INFINITE_DISTANCE : 0;
            } else {
                max = MinMaxLen.distanceMultiply(nopt.length.max, qn.upper);
            }
            opt.length.set(min, max);
            break;
        }

        case NodeType.ENCLOSE: {
            EncloseNode en = (EncloseNode)node;
            switch (en.type) {
            case EncloseType.OPTION:
                int save = oenv.options;
                int saveCaseFoldFlag = oenv.caseFoldFlag;
                oenv.options = en.option;
                oenv.caseFoldFlag = regex.caseFoldFlagFor(en.option);
                optimizeNodeLeft(en.target, opt, oenv);
                oenv.options = save;
                oenv.caseFoldFlag = saveCaseFoldFlag;
                break;

            case EncloseType.MEMORY:
                if (Config.USE_SUBEXP_CALL && ++en.optCount > MAX_NODE_OPT_INFO_REF_COUNT) {
                    int min = 0;
                    int max = MinMaxLen.INFINITE_DISTANCE;
                    if (en.isMinFixed()) min = en.minLength;
                    if (en.isMaxFixed()) max = en.maxLength;
                    opt.length.set(min, max);
                } else { // USE_SUBEXP_CALL
                    optimizeNodeLeft(en.target, opt, oenv);
                    if (opt.anchor.isSet(AnchorType.ANYCHAR_STAR_MASK)) {
                        if (bsAt(oenv.scanEnv.backrefedMem, en.regNum)) {
                            opt.anchor.remove(AnchorType.ANYCHAR_STAR_MASK);
                        }
                    }
                }
                break;

            case EncloseType.STOP_BACKTRACK:
            case EncloseType.CONDITION:
                optimizeNodeLeft(en.target, opt, oenv);
                break;

            case EncloseType.ABSENT:
                opt.length.set(0, MinMaxLen.INFINITE_DISTANCE);
                break;

            case EncloseType.DEFINE:
                opt.length.set(0, 0);
                break;
            } // inner switch
            break;
        }

        default:
            newInternalException(PARSER_BUG);
        } // switch
    }

    /**
     * Retain a start-byte map for a positive class whose multibyte membership is
     * a bounded set of singleton code points.  Perl fold expansion produces
     * exactly this shape for the extra literal/class alternatives that JCodings
     * cannot represent as one ignore-case exact string.  Dropping the map from
     * one such alternative makes {@link OptMapInfo#altMerge} discard the safe
     * common start-class search for the whole fold.
     *
     * <p>Ranges, negation, deferred terms, and the host wide-scalar domain stay
     * deliberately unoptimized: adding only part of any of those memberships
     * would let the search skip a valid match.</p>
     */
    private void addPositiveSingletonClassMap(CClassNode cc, OptMapInfo map) {
        if (cc.isNot() || cc.hasDeferredProperties()
                || cc.hasWideScalarRanges() || cc.mbuf == null) return;

        int[] ranges = cc.mbuf.getCodeRange();
        int count = ranges[0];
        for (int index = 0; index < count; index++) {
            int from = ranges[index * 2 + 1];
            int to = ranges[index * 2 + 2];
            if (from != to || from < 0 || from > 0x10ffff
                    || enc.codeToMbcLength(from) <= 0) return;
        }

        for (int code = 0; code < BitSet.SINGLE_BYTE_SIZE; code++) {
            if (cc.bs.at(code)) map.addChar((byte)code, enc);
        }
        byte[] encoded = new byte[Config.ENC_CODE_TO_MBC_MAXLEN];
        for (int index = 0; index < count; index++) {
            int codePoint = ranges[index * 2 + 1];
            enc.codeToMbc(codePoint, encoded, 0);
            map.addChar(encoded[0], enc);
        }
    }

    protected final void setOptimizedInfoFromTree(Node node) {
        NodeOptInfo opt = new NodeOptInfo();
        OptEnvironment oenv = new OptEnvironment();

        oenv.enc = regex.enc;
        oenv.options = regex.options;
        oenv.caseFoldFlag = regex.caseFoldFlagFor(regex.options);
        oenv.scanEnv = env;
        oenv.mmd.clear(); // ??

        optimizeNodeLeft(node, opt, oenv);
        regex.minimumLength = opt.length.min;

        regex.anchor = opt.anchor.leftAnchor & (AnchorType.BEGIN_BUF |
                                                AnchorType.BEGIN_POSITION |
                                                AnchorType.ANYCHAR_STAR |
                                                AnchorType.ANYCHAR_STAR_ML |
                                                AnchorType.LOOK_BEHIND);

        if ((opt.anchor.leftAnchor & (AnchorType.LOOK_BEHIND | AnchorType.PREC_READ_NOT)) != 0) regex.anchor &= ~AnchorType.ANYCHAR_STAR_ML;

        regex.anchor |= opt.anchor.rightAnchor & (AnchorType.END_BUF |
                                                  AnchorType.SEMI_END_BUF |
                                                  AnchorType.PREC_READ_NOT);

        if ((regex.anchor & (AnchorType.END_BUF | AnchorType.SEMI_END_BUF)) != 0) {
            regex.anchorDmin = opt.length.min;
            regex.anchorDmax = opt.length.max;
        }

        if (opt.exb.length > 0 || opt.exm.length > 0) {
            opt.exb.select(opt.exm, enc);
            if (opt.map.value > 0 && opt.exb.compare(opt.map) > 0) {
                // !goto set_map;!
                regex.setOptimizeMapInfo(opt.map);
                regex.setSubAnchor(opt.map.anchor);
            } else {
                regex.setOptimizeExactInfo(opt.exb);
                regex.setSubAnchor(opt.exb.anchor);
            }
        } else if (opt.map.value > 0) {
            // !set_map:!
            regex.setOptimizeMapInfo(opt.map);
            regex.setSubAnchor(opt.map.anchor);
        } else {
            regex.subAnchor |= opt.anchor.leftAnchor & AnchorType.BEGIN_LINE;
            if (opt.length.max == 0) regex.subAnchor |= opt.anchor.rightAnchor & AnchorType.END_LINE;
        }

        if (Config.DEBUG_COMPILE || Config.DEBUG_MATCH) {
            Config.log.println(regex.optimizeInfoToString());
        }
    }

    private boolean invalidBackrefNode(int number) {
        return number <= 0 || number > env.numMem || env.memNodes == null
                || number >= env.memNodes.length || env.memNodes[number] == null;
    }
}
