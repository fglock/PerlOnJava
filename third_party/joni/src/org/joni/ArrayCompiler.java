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
import static org.joni.Option.isDynamic;
import static org.joni.Option.isIgnoreCase;
import static org.joni.Option.isMultiline;
import static org.joni.ast.QuantifierNode.isRepeatInfinite;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.jcodings.constants.CharacterType;
import org.joni.ast.AnchorNode;
import org.joni.ast.BackRefNode;
import org.joni.ast.CClassNode;
import org.joni.ast.CTypeNode;
import org.joni.ast.CallNode;
import org.joni.ast.CalloutNode;
import org.joni.ast.ControlVerbNode;
import org.joni.ast.ListNode;
import org.joni.ast.EncloseNode;
import org.joni.ast.Node;
import org.joni.ast.QuantifierNode;
import org.joni.ast.StringNode;
import org.joni.ast.WideScalarNode;
import org.joni.constants.internal.AnchorType;
import org.joni.constants.internal.EncloseType;
import org.joni.constants.internal.NodeType;
import org.joni.constants.internal.OPCode;
import org.joni.constants.internal.OPSize;
import org.joni.constants.internal.TargetInfo;

final class ArrayCompiler extends Compiler {
    private int[]code;
    private int codeLength;

    private byte[][]templates;
    private int templateNum;
    private final Map<String, Integer> controlVerbLabelIds = new LinkedHashMap<>();
    private final Map<Integer, Integer> debugExactOptions = new LinkedHashMap<>();
    private final Set<Integer> debugSingleSourceMultiFolds =
            new java.util.LinkedHashSet<>();
    private final List<CClassNode> wideScalarClasses = new ArrayList<>();
    private final Map<Integer, CClassNode.DebugClassExpression>
            debugCharacterClassExpressions = new LinkedHashMap<>();
    private final Map<Integer, RegexClassDebugProvenance>
            debugCharacterClassProvenances = new LinkedHashMap<>();
    private final Set<BackRefNode> previousRepeatBackrefs =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BackRefNode> recursiveFrameBackrefs =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<EncloseNode> calledFrameStopBacktracks =
            Collections.newSetFromMap(new IdentityHashMap<>());

    ArrayCompiler(Analyser analyser) {
        super(analyser);
    }

    @Override
    protected final void prepare(Node root) {
        int codeSize = Config.USE_STRING_TEMPLATES ? 8 : ((analyser.getEnd() - analyser.getBegin()) * 2 + 2);
        code = new int[codeSize];
        codeLength = 0;
        collectPreviousRepeatBackrefs(root, 0, 0, new boolean[regex.numMem + 1]);
        collectRecursiveFrameBackrefs(root, false);
    }

    @Override
    protected final void finish() {
        addOpcode(OPCode.END);
        addOpcode(OPCode.FINISH); // for stack bottom

        regex.code = code;
        regex.codeLength = codeLength;
        regex.templates = templates;
        regex.templateNum = templateNum;
        regex.controlVerbLabels = controlVerbLabelIds.keySet().toArray(String[]::new);
        regex.debugExactOptions = Map.copyOf(debugExactOptions);
        regex.debugSingleSourceMultiFolds = Set.copyOf(
                debugSingleSourceMultiFolds);
        regex.wideScalarClasses = wideScalarClasses.toArray(CClassNode[]::new);
        regex.debugCharacterClassExpressions =
                Map.copyOf(debugCharacterClassExpressions);
        regex.debugCharacterClassProvenances =
                Map.copyOf(debugCharacterClassProvenances);
        regex.factory = MatcherFactory.DEFAULT;

        if (Config.USE_SUBEXP_CALL && analyser.env.unsetAddrList != null) {
            analyser.env.unsetAddrList.fix(regex);
            analyser.env.unsetAddrList = null;
        }
    }

    @Override
    protected void compileCalloutNode(CalloutNode node) {
        regex.requireStack = true;
        addOpcode(node.dynamic ? OPCode.DYNAMIC_CALLOUT : OPCode.CALLOUT);
        addInt(node.calloutId);
        if (node.dynamic) addInt(regex.options);
    }

    @Override
    protected void compileControlVerbNode(ControlVerbNode node) {
        switch (node.kind) {
        case ACCEPT:
            regex.requireStack = true;
            addOpcode(OPCode.ACCEPT);
            addInt(controlVerbLabelId(node.name));
            break;
        case FAIL:
            regex.requireStack = true;
            addOpcode(OPCode.CONTROL_FAIL);
            addInt(controlVerbLabelId(node.name));
            break;
        case PRUNE:
            regex.requireStack = true;
            addOpcode(OPCode.PRUNE);
            addInt(controlVerbLabelId(node.name));
            break;
        case SKIP:
            regex.requireStack = true;
            addOpcode(OPCode.SKIP);
            addInt(controlVerbLabelId(node.name));
            break;
        case THEN:
            regex.requireStack = true;
            addOpcode(OPCode.THEN);
            addInt(controlVerbLabelId(node.name));
            break;
        case COMMIT:
            regex.requireStack = true;
            addOpcode(OPCode.COMMIT);
            addInt(controlVerbLabelId(node.name));
            break;
        case MARK:
            regex.requireStack = true;
            addOpcode(OPCode.MARK);
            addInt(controlVerbLabelId(node.name));
            break;
        default:
            newInternalException(PARSER_BUG);
        }
    }

    private int controlVerbLabelId(String name) {
        if (name == null) return -1;
        return controlVerbLabelIds.computeIfAbsent(name, ignored -> controlVerbLabelIds.size());
    }

    @Override
    protected void compileAltNode(ListNode node) {
        ListNode aln = node;
        int len = 0;

        do {
            len += compileLengthTree(aln.value);
            if (aln.tail != null) {
                len += OPSize.PUSH_BRANCH + OPSize.JUMP;
            }
        } while ((aln = aln.tail) != null);

        int pos = codeLength + len;  /* goal position */

        aln = node;
        do {
            len = compileLengthTree(aln.value);
            if (aln.tail != null) {
                regex.requireStack = true;
                addOpcodeRelAddr(OPCode.PUSH_BRANCH, len + OPSize.JUMP);
            }
            compileTree(aln.value);
            if (aln.tail != null) {
                len = pos - (codeLength + OPSize.JUMP);
                addOpcodeRelAddr(OPCode.JUMP, len);
            }
        } while ((aln = aln.tail) != null);
    }

    private boolean isNeedStrLenOpExact(int op) {
        return  op == OPCode.EXACTN         ||
                op == OPCode.EXACTMB2N      ||
                op == OPCode.EXACTMB3N      ||
                op == OPCode.EXACTMBN       ||
                op == OPCode.EXACTN_IC      ||
                op == OPCode.EXACTN_IC_SB;
    }

    private boolean opTemplated(int op) {
        return isNeedStrLenOpExact(op);
    }

    private int selectStrOpcode(int mbLength, int byteLength, boolean ignoreCase) {
        int op;
        int strLength = (byteLength + mbLength - 1) / mbLength;

        if (ignoreCase) {
            switch(strLength) {
            case 1: op = enc.toLowerCaseTable() != null ? OPCode.EXACT1_IC_SB : OPCode.EXACT1_IC; break;
            default:op = enc.toLowerCaseTable() != null ? OPCode.EXACTN_IC_SB : OPCode.EXACTN_IC; break;
            } // switch
        } else {
            switch (mbLength) {
            case 1:
                switch (strLength) {
                case 1: op = OPCode.EXACT1; break;
                case 2: op = OPCode.EXACT2; break;
                case 3: op = OPCode.EXACT3; break;
                case 4: op = OPCode.EXACT4; break;
                case 5: op = OPCode.EXACT5; break;
                default:op = OPCode.EXACTN; break;
                } // inner switch
                break;
            case 2:
                switch (strLength) {
                case 1: op = OPCode.EXACTMB2N1; break;
                case 2: op = OPCode.EXACTMB2N2; break;
                case 3: op = OPCode.EXACTMB2N3; break;
                default:op = OPCode.EXACTMB2N;  break;
                } // inner switch
                break;
            case 3:
                op = OPCode.EXACTMB3N;
                break;
            default:
                op = OPCode.EXACTMBN;
            } // switch
        }
        return op;
    }

    private void compileTreeEmptyCheck(Node node, int emptyInfo) {
        int savedNumNullCheck = regex.numNullCheck;

        if (emptyInfo != 0) {
            regex.requireStack = true;
            addOpcode(OPCode.NULL_CHECK_START);
            addMemNum(regex.numNullCheck); /* NULL CHECK ID */
            regex.numNullCheck++;
        }

        compileTree(node);

        if (emptyInfo != 0) {
            switch(emptyInfo) {
            case TargetInfo.IS_EMPTY:
                addOpcode(OPCode.NULL_CHECK_END);
                break;
            case TargetInfo.IS_EMPTY_MEM:
                addOpcode(OPCode.NULL_CHECK_END_MEMST);
                break;
            case TargetInfo.IS_EMPTY_REC:
                addOpcode(OPCode.NULL_CHECK_END_MEMST_PUSH);
                break;
            } // switch

            addMemNum(savedNumNullCheck); /* NULL CHECK ID */
        }
    }

    private int addCompileStringlength(byte[]bytes, int p, int mbLength, int byteLength, boolean ignoreCase) {
        int op = selectStrOpcode(mbLength, byteLength, ignoreCase);
        int len = OPSize.OPCODE;

        if (Config.USE_STRING_TEMPLATES && opTemplated(op)) {
            // string length, template index, template string pointer
            len += OPSize.LENGTH + OPSize.INDEX + OPSize.INDEX;
        } else {
            if (isNeedStrLenOpExact(op)) len += OPSize.LENGTH;
            len += byteLength;
        }
        if (op == OPCode.EXACTMBN) len += OPSize.LENGTH;
        return len;
    }

    @Override
    protected final void addCompileString(byte[]bytes, int p, int mbLength,
            int byteLength, boolean ignoreCase,
            boolean singleSourceMultiFold) {
        int op = selectStrOpcode(mbLength, byteLength, ignoreCase);
        debugExactOptions.put(codeLength, regex.options);
        if (singleSourceMultiFold) {
            debugSingleSourceMultiFolds.add(codeLength);
        }
        addOpcode(op);

        if (op == OPCode.EXACTMBN) addLength(mbLength);

        if (isNeedStrLenOpExact(op)) {
            if (op == OPCode.EXACTN_IC || op == OPCode.EXACTN_IC_SB) {
                addLength(byteLength);
            } else {
                addLength(byteLength / mbLength);
            }
        }

        if (Config.USE_STRING_TEMPLATES && opTemplated(op)) {
            addInt(templateNum);
            addInt(p);
            addTemplate(bytes);
        } else {
            addBytes(bytes, p, byteLength);
        }
    }

    private int compileLengthStringNode(Node node) {
        StringNode sn = (StringNode)node;
        if (sn.length() <= 0) return 0;
        boolean ambig = sn.isAmbig();

        int p, prev;
        p = prev = sn.p;
        int end = sn.end;
        byte[]bytes = sn.bytes;
        int prevLen = enc.length(bytes, p, end);
        p += prevLen;
        int blen = prevLen;
        int rlen = 0;

        while (p < end) {
            int len = enc.length(bytes, p, end);
            if (len == prevLen || ambig) {
                blen += len;
            } else {
                int r = addCompileStringlength(bytes, prev, prevLen, blen, ambig);
                rlen += r;
                prev = p;
                blen = len;
                prevLen = len;
            }
            p += len;
        }
        int r = addCompileStringlength(bytes, prev, prevLen, blen, ambig);
        rlen += r;
        return rlen;
    }

    private int compileLengthStringRawNode(StringNode sn) {
        if (sn.length() <= 0) return 0;
        return addCompileStringlength(sn.bytes, sn.p, 1 /*sb*/, sn.length(), false);
    }

    private void addMultiByteCClass(CodeRangeBuffer mbuf) {
        addLength(mbuf.getUsed());
        addInts(mbuf.getCodeRange(), mbuf.getUsed());
    }

    private int compileLengthCClassNode(CClassNode cc) {
        if (regex.wideScalarCodec != null || cc.hasDeferredProperties()
                || hasRuntimeLocaleClass(cc)) {
            return OPSize.WIDE_SCALAR_CLASS;
        }
        int len;
        if (cc.mbuf == null) {
            len = OPSize.OPCODE + BitSet.BITSET_SIZE;
        } else {
            if (enc.minLength() > 1 || cc.bs.isEmpty()) {
                len = OPSize.OPCODE;
            } else {
                len = OPSize.OPCODE + BitSet.BITSET_SIZE;
            }

            len += OPSize.LENGTH + cc.mbuf.getUsed();
        }
        return len;
    }

    @Override
    protected void compileCClassNode(CClassNode cc) {
        debugCharacterClassProvenances.put(codeLength,
                RegexClassDebugProvenance.snapshot(cc, regex.enc,
                        analyser.syntax.characterPropertyResolver != null
                        && analyser.syntax.characterPropertyResolver
                                .hasAuthoritativePerlClassSemantics()));
        CClassNode.DebugClassExpression debugExpression =
                cc.debugClassExpression();
        if (debugExpression != null) {
            debugCharacterClassExpressions.put(codeLength, debugExpression);
        }
        if (regex.wideScalarCodec != null || cc.hasDeferredProperties()
                || hasRuntimeLocaleClass(cc)) {
            addOpcode(OPCode.WIDE_SCALAR_CLASS);
            addInt(wideScalarClasses.size());
            wideScalarClasses.add(cc);
            return;
        }
        if (cc.mbuf == null) {
            if (cc.isNot()) {
                addOpcode(OPCode.CCLASS_NOT);
            } else {
                addOpcode(OPCode.CCLASS);
            }
            addInts(cc.bs.bits, BitSet.BITSET_SIZE); // add_bitset
        } else {
            if (enc.minLength() > 1 || cc.bs.isEmpty()) {
                if (cc.isNot()) {
                    addOpcode(OPCode.CCLASS_MB_NOT);
                } else {
                    addOpcode(OPCode.CCLASS_MB);
                }
                addMultiByteCClass(cc.mbuf);
            } else {
                if (cc.isNot()) {
                    addOpcode(OPCode.CCLASS_MIX_NOT);
                } else {
                    addOpcode(OPCode.CCLASS_MIX);
                }
                // store the bit set and mbuf themself!
                addInts(cc.bs.bits, BitSet.BITSET_SIZE); // add_bitset
                addMultiByteCClass(cc.mbuf);
            }
        }
    }

    private static boolean hasRuntimeLocaleClass(CClassNode cc) {
        CClassNode.DebugClassExpression expression = cc.debugClassExpression();
        if (expression == null || !expression.authoritative()) return false;
        for (CClassNode.DebugClassTerm term : expression.terms()) {
            if (Option.isPerlLocale(term.lexicalOption())) return true;
        }
        return false;
    }

    @Override
    protected void compileWideScalarNode(WideScalarNode node) {
        addOpcode(OPCode.WIDE_SCALAR);
        addInt((int)(node.value >>> 32));
        addInt((int)node.value);
    }

    @Override
    protected void compileCTypeNode(CTypeNode node) {
        CTypeNode cn = node;
        int op;
        switch (cn.ctype) {
        case CharacterType.WORD:
            if (cn.not) {
                if (cn.asciiRange) {
                    op = OPCode.ASCII_NOT_WORD;
                } else {
                    op = OPCode.NOT_WORD;
                }
            } else {
                if (cn.asciiRange) {
                    op = OPCode.ASCII_WORD;
                } else {
                    op = OPCode.WORD;
                }
            }
            break;

        default:
            newInternalException(PARSER_BUG);
            return; // not reached
        } // inner switch
        addOpcode(op);
    }

    @Override
    protected void compileAnyCharNode() {
        if (isMultiline(regex.options)) {
            addOpcode(OPCode.ANYCHAR_ML);
        } else {
            addOpcode(OPCode.ANYCHAR);
        }
    }

    @Override
    protected void compileCallNode(CallNode node) {
        addOpcode(OPCode.CALL);
        node.unsetAddrList.add(codeLength, node.target);
        addAbsAddr(0); /*dummy addr.*/
        // Keep recursion and Perl capture-publication policy separate from the
        // group number. Ordinary Perl subroutine calls preserve the caller's
        // captures but must not be treated as recursive by the analyser.
        int callFlags = (node.isRecursion() ? 0x40000000 : 0)
                | (node.preserveCallerCaptures ? 0x80000000 : 0);
        addMemNum((node.groupNum & 0x3fffffff) | callFlags);
    }

    @Override
    protected void compileBackrefNode(BackRefNode node) {
        BackRefNode br = node;
        if (usesPreviousRepeatCapture(br) || usesPreviousRecursiveFrameCapture(br)) {
            addOpcode(isIgnoreCase(regex.options) ? OPCode.BACKREFN_PREV_IC : OPCode.BACKREFN_PREV);
            addMemNum(br.back[0]);
            return;
        }
        if (Config.USE_BACKREF_WITH_LEVEL && br.isNestLevel()) {
            addOpcode(OPCode.BACKREF_WITH_LEVEL);
            addOption(regex.options & Option.IGNORECASE);
            addLength(br.nestLevel);
            // !goto add_bacref_mems;!
            addLength(br.backNum);
            for (int i=br.backNum-1; i>=0; i--) addMemNum(br.back[i]);
            return;
        } else { // USE_BACKREF_AT_LEVEL
            if (br.backNum == 1) {
                if (isIgnoreCase(regex.options)) {
                    addOpcode(OPCode.BACKREFN_IC);
                    addMemNum(br.back[0]);
                } else {
                    switch (br.back[0]) {
                    case 1:
                        addOpcode(OPCode.BACKREF1);
                        break;
                    case 2:
                        addOpcode(OPCode.BACKREF2);
                        break;
                    default:
                        addOpcode(OPCode.BACKREFN);
                        addOpcode(br.back[0]);
                        break;
                    } // switch
                }
            } else {
                if (isIgnoreCase(regex.options)) {
                    addOpcode(OPCode.BACKREF_MULTI_IC);
                } else {
                    addOpcode(OPCode.BACKREF_MULTI);
                }
                // !add_bacref_mems:!
                addLength(br.backNum);
                for (int i=br.backNum-1; i>=0; i--) addMemNum(br.back[i]);
            }
        }
    }

    private boolean usesPreviousRepeatCapture(BackRefNode backref) {
        return previousRepeatBackrefs.contains(backref);
    }

    private boolean usesPreviousRecursiveFrameCapture(BackRefNode backref) {
        return recursiveFrameBackrefs.contains(backref);
    }

    private void collectRecursiveFrameBackrefs(Node node, boolean recursiveFrame) {
        if (node == null) return;
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            for (ListNode item = (ListNode)node; item != null; item = item.tail) {
                collectRecursiveFrameBackrefs(item.value, recursiveFrame);
            }
            break;
        case NodeType.QTFR:
            collectRecursiveFrameBackrefs(((QuantifierNode)node).target, recursiveFrame);
            break;
        case NodeType.ENCLOSE:
            EncloseNode enclosure = (EncloseNode)node;
            if (recursiveFrame && enclosure.isStopBtSimpleRepeat()) {
                calledFrameStopBacktracks.add(enclosure);
            }
            collectRecursiveFrameBackrefs(enclosure.target,
                    recursiveFrame || (enclosure.isMemory() && enclosure.isCalled()));
            break;
        case NodeType.ANCHOR:
            collectRecursiveFrameBackrefs(((AnchorNode)node).target, recursiveFrame);
            break;
        case NodeType.CALL:
            CallNode call = (CallNode)node;
            // A recursive target is normally compiled from its CALL node,
            // rather than from the enclosing occurrence in the main tree.
            // Visit that body once to classify its forward backreferences;
            // do not follow nested calls again, as their targets form cycles.
            if (!recursiveFrame && call.isRecursion()) {
                collectRecursiveFrameBackrefs(call.target.target, true);
            }
            break;
        case NodeType.BREF:
            BackRefNode backref = (BackRefNode)node;
            if (recursiveFrame && backref.isRecursion()) {
                recursiveFrameBackrefs.add(backref);
            }
            break;
        default:
            break;
        }
    }

    private boolean preservesCalledFrameBacktracking(EncloseNode enclosure) {
        return calledFrameStopBacktracks.contains(enclosure);
    }

    private void collectPreviousRepeatBackrefs(Node node, int repeatDepth, int alternativeDepth,
                                                boolean[] activeRepeatedCaptures) {
        if (node == null) return;
        switch (node.getType()) {
        case NodeType.LIST:
            for (ListNode item = (ListNode)node; item != null; item = item.tail) {
                collectPreviousRepeatBackrefs(item.value, repeatDepth, alternativeDepth,
                        activeRepeatedCaptures);
            }
            break;
        case NodeType.ALT:
            for (ListNode item = (ListNode)node; item != null; item = item.tail) {
                collectPreviousRepeatBackrefs(item.value, repeatDepth, alternativeDepth + 1,
                        activeRepeatedCaptures);
            }
            break;
        case NodeType.QTFR:
            QuantifierNode quantifier = (QuantifierNode)node;
            int nestedRepeatDepth = quantifier.upper == QuantifierNode.REPEAT_INFINITE
                    || quantifier.upper > 1 ? repeatDepth + 1 : repeatDepth;
            collectPreviousRepeatBackrefs(quantifier.target, nestedRepeatDepth, alternativeDepth,
                    activeRepeatedCaptures);
            break;
        case NodeType.ENCLOSE:
            EncloseNode enclosure = (EncloseNode)node;
            if (enclosure.isCondition() && enclosure.target instanceof ListNode
                    && enclosure.target.getType() == NodeType.ALT) {
                for (ListNode item = (ListNode)enclosure.target; item != null; item = item.tail) {
                    collectPreviousRepeatBackrefs(item.value, repeatDepth, alternativeDepth,
                            activeRepeatedCaptures);
                }
                break;
            }
            boolean wasActive = false;
            if (enclosure.isMemory() && enclosure.regNum > 0 && repeatDepth > 0) {
                wasActive = activeRepeatedCaptures[enclosure.regNum];
                activeRepeatedCaptures[enclosure.regNum] = true;
            }
            collectPreviousRepeatBackrefs(enclosure.target, repeatDepth, alternativeDepth,
                    activeRepeatedCaptures);
            if (enclosure.isMemory() && enclosure.regNum > 0 && repeatDepth > 0) {
                activeRepeatedCaptures[enclosure.regNum] = wasActive;
            }
            break;
        case NodeType.ANCHOR:
            collectPreviousRepeatBackrefs(((AnchorNode)node).target, repeatDepth, alternativeDepth,
                    activeRepeatedCaptures);
            break;
        case NodeType.BREF:
            BackRefNode backref = (BackRefNode)node;
            if (backref.backNum == 1 && !backref.isNestLevel() && alternativeDepth == 0
                    && backref.back[0] < activeRepeatedCaptures.length
                    && activeRepeatedCaptures[backref.back[0]]) {
                previousRepeatBackrefs.add(backref);
            }
            break;
        default:
            break;
        }
    }

    private static final int REPEAT_RANGE_ALLOC = 8;

    private static void collectCaptureGroups(Node node, boolean[] groups) {
        if (node == null) return;
        switch (node.getType()) {
        case NodeType.LIST:
        case NodeType.ALT:
            for (ListNode item = (ListNode)node; item != null; item = item.tail) {
                collectCaptureGroups(item.value, groups);
            }
            break;
        case NodeType.QTFR:
            collectCaptureGroups(((QuantifierNode)node).target, groups);
            break;
        case NodeType.ENCLOSE:
            EncloseNode enclose = (EncloseNode)node;
            if (enclose.isMemory() && enclose.regNum > 0 && enclose.regNum < groups.length) {
                groups[enclose.regNum] = true;
            }
            collectCaptureGroups(enclose.target, groups);
            break;
        case NodeType.ANCHOR:
            collectCaptureGroups(((AnchorNode)node).target, groups);
            break;
        default:
            break;
        }
    }

    private int[] captureGroups(Node target) {
        boolean[] found = new boolean[regex.numMem + 1];
        collectCaptureGroups(target, found);
        int count = 0;
        for (int group = 1; group < found.length; group++) {
            if (found[group]) count++;
        }
        if (count == 0) return null;

        int[] groups = new int[count];
        int index = 0;
        for (int group = 1; group < found.length; group++) {
            if (found[group]) groups[index++] = group;
        }
        return groups;
    }

    private int entryRepeatCaptureClear(Node target) {
        int[] groups = captureGroups(target);
        if (groups == null) return -1;
        int id = regex.numRepeatCaptureClearGroups++;
        if (regex.repeatCaptureClearGroups == null) {
            regex.repeatCaptureClearGroups = new int[REPEAT_RANGE_ALLOC][];
        } else if (id >= regex.repeatCaptureClearGroups.length) {
            int[][] expanded = new int[regex.repeatCaptureClearGroups.length + REPEAT_RANGE_ALLOC][];
            System.arraycopy(regex.repeatCaptureClearGroups, 0, expanded, 0,
                    regex.repeatCaptureClearGroups.length);
            regex.repeatCaptureClearGroups = expanded;
        }
        regex.repeatCaptureClearGroups[id] = groups;
        return id;
    }

    private int repeatCaptureClearLength(Node target) {
        return captureGroups(target) == null ? 0
                : OPSize.REPEAT_CAPTURE_CLEAR + OPSize.REPEAT_CAPTURE_CLEAR_END;
    }

    private void compileRepeatCaptureClear(int clearId) {
        if (clearId < 0) return;
        addOpcode(OPCode.REPEAT_CAPTURE_CLEAR);
        addMemNum(clearId);
    }

    private void compileRepeatTree(Node target, int clearId) {
        compileRepeatCaptureClear(clearId);
        compileTree(target);
        if (clearId >= 0) {
            addOpcode(OPCode.REPEAT_CAPTURE_CLEAR_END);
            addMemNum(clearId);
        }
    }

    private void compileRepeatTreeNTimes(Node target, int count, int clearId) {
        for (int i = 0; i < count; i++) compileRepeatTree(target, clearId);
    }

    private void compileRepeatTreeEmptyCheck(Node target, int emptyInfo, int clearId) {
        if (emptyInfo == 0) {
            compileRepeatTree(target, clearId);
        } else {
            compileTreeEmptyCheck(target, emptyInfo);
        }
    }

    private void entryRepeatRange(int id, int lower, int upper) {
        if (regex.repeatRangeLo == null) {
            regex.repeatRangeLo = new int[REPEAT_RANGE_ALLOC];
            regex.repeatRangeHi = new int[REPEAT_RANGE_ALLOC];
        } else if (id >= regex.repeatRangeLo.length){
            int[]tmp = new int[regex.repeatRangeLo.length + REPEAT_RANGE_ALLOC];
            System.arraycopy(regex.repeatRangeLo, 0, tmp, 0, regex.repeatRangeLo.length);
            regex.repeatRangeLo = tmp;
            tmp = new int[regex.repeatRangeHi.length + REPEAT_RANGE_ALLOC];
            System.arraycopy(regex.repeatRangeHi, 0, tmp, 0, regex.repeatRangeHi.length);
            regex.repeatRangeHi = tmp;
        }

        regex.repeatRangeLo[id] = lower;
        regex.repeatRangeHi[id] = isRepeatInfinite(upper) ? 0x7fffffff : upper;
    }

    private void compileRangeRepeatNode(QuantifierNode qn, int targetLen, int emptyInfo,
                                        int clearId) {
        regex.requireStack = true;
        int numRepeat = regex.numRepeat;
        addOpcode(qn.greedy ? OPCode.REPEAT : OPCode.REPEAT_NG);
        addMemNum(numRepeat); /* OP_REPEAT ID */
        regex.numRepeat++;
        addRelAddr(targetLen + OPSize.REPEAT_INC);

        entryRepeatRange(numRepeat, qn.lower, qn.upper);

        compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);

        if ((Config.USE_SUBEXP_CALL && regex.numCall > 0) || qn.isInRepeat()) {
            addOpcode(qn.greedy ? OPCode.REPEAT_INC_SG : OPCode.REPEAT_INC_NG_SG);
        } else {
            addOpcode(qn.greedy ? OPCode.REPEAT_INC : OPCode.REPEAT_INC_NG);
        }

        addMemNum(numRepeat); /* OP_REPEAT ID */
    }

    private static final int QUANTIFIER_EXPAND_LIMIT_SIZE   = 50; // was 50

    private static boolean cknOn(int ckn) {
        return ckn > 0;
    }

    private static boolean isImpossibleQuantifier(QuantifierNode quantifier) {
        return !isRepeatInfinite(quantifier.upper)
                && quantifier.lower > quantifier.upper;
    }

    private int compileCECLengthQuantifierNode(QuantifierNode qn) {
        if (isImpossibleQuantifier(qn)) {
            return OPSize.FAIL + (qn.isRefered
                    ? OPSize.JUMP + compileLengthTree(qn.target) : 0);
        }
        boolean infinite = isRepeatInfinite(qn.upper);
        int emptyInfo = qn.targetEmptyInfo;

        int tlen = compileLengthTree(qn.target);
        // Empty and recursive-empty repeats use capture state in Joni's null
        // loop detector. Their reset needs a separate null-check-aware design.
        int repeatClearLen = emptyInfo == 0 ? repeatCaptureClearLength(qn.target) : 0;
        int bodyLen = tlen + repeatClearLen;
        int ckn = regex.numCombExpCheck > 0 ? qn.combExpCheckNum : 0;
        int cklen = cknOn(ckn) ? OPSize.STATE_CHECK_NUM : 0;

        /* anychar repeat */
        if (qn.target.getType() == NodeType.CANY) {
            if (qn.greedy && infinite) {
                if (qn.nextHeadExact != null && !cknOn(ckn)) {
                    return OPSize.ANYCHAR_STAR_PEEK_NEXT + tlen * qn.lower + cklen;
                } else {
                    return OPSize.ANYCHAR_STAR + tlen * qn.lower + cklen;
                }
            }
        }

        int modTLen;
        if (emptyInfo != 0) {
            modTLen = bodyLen + (OPSize.NULL_CHECK_START + OPSize.NULL_CHECK_END);
        } else {
            modTLen = bodyLen;
        }

        int len;
        if (infinite && qn.lower <= 1) {
            if (qn.greedy) {
                if (qn.lower == 1) {
                    len = OPSize.JUMP;
                } else {
                    len = 0;
                }
                len += OPSize.PUSH + cklen + modTLen + OPSize.JUMP;
            } else {
                if (qn.lower == 0) {
                    len = OPSize.JUMP;
                } else {
                    len = 0;
                }
                len += modTLen + OPSize.PUSH + cklen;
            }
        } else if (qn.upper == 0) {
            if (qn.isRefered) { /* /(?<n>..){0}/ */
                len = OPSize.JUMP + tlen;
            } else {
                len = 0;
            }
        } else if (qn.upper == 1 && qn.greedy) {
            if (qn.lower == 0) {
                if (cknOn(ckn)) {
                    len = OPSize.STATE_CHECK_PUSH + bodyLen;
                } else {
                    len = OPSize.PUSH + bodyLen;
                }
            } else {
                len = bodyLen;
            }
        } else if (!qn.greedy && qn.upper == 1 && qn.lower == 0) { /* '??' */
            len = OPSize.PUSH + cklen + OPSize.JUMP + bodyLen;
        } else {
            len = OPSize.REPEAT_INC + modTLen + OPSize.OPCODE + OPSize.RELADDR + OPSize.MEMNUM;

            if (cknOn(ckn)) {
                len += OPSize.STATE_CHECK;
            }
        }
        return len;
    }

    @Override
    protected void compileCECQuantifierNode(QuantifierNode qn) {
        regex.requireStack = true;
        if (isImpossibleQuantifier(qn)) {
            if (qn.isRefered) {
                int targetLength = compileLengthTree(qn.target);
                addOpcodeRelAddr(OPCode.JUMP, targetLength);
                compileTree(qn.target);
            }
            addOpcode(OPCode.FAIL);
            return;
        }
        boolean infinite = isRepeatInfinite(qn.upper);
        int emptyInfo = qn.targetEmptyInfo;

        int tlen = compileLengthTree(qn.target);
        int clearId = emptyInfo == 0 ? entryRepeatCaptureClear(qn.target) : -1;
        int bodyLen = tlen + (clearId < 0 ? 0
                : OPSize.REPEAT_CAPTURE_CLEAR + OPSize.REPEAT_CAPTURE_CLEAR_END);

        int ckn = regex.numCombExpCheck > 0 ? qn.combExpCheckNum : 0;

        if (qn.isAnyCharStar()) {
            compileTreeNTimes(qn.target, qn.lower);
            if (qn.nextHeadExact != null && !cknOn(ckn)) {
                if (isMultiline(regex.options)) {
                    addOpcode(OPCode.ANYCHAR_ML_STAR_PEEK_NEXT);
                } else {
                    addOpcode(OPCode.ANYCHAR_STAR_PEEK_NEXT);
                }
                if (cknOn(ckn)) {
                    addStateCheckNum(ckn);
                }
                StringNode sn = (StringNode)qn.nextHeadExact;
                addBytes(sn.bytes, sn.p, 1);
                return;
            } else {
                if (isMultiline(regex.options)) {
                    if (cknOn(ckn)) {
                        addOpcode(OPCode.STATE_CHECK_ANYCHAR_ML_STAR);
                    } else {
                        addOpcode(OPCode.ANYCHAR_ML_STAR);
                    }
                } else {
                    if (cknOn(ckn)) {
                        addOpcode(OPCode.STATE_CHECK_ANYCHAR_STAR);
                    } else {
                        addOpcode(OPCode.ANYCHAR_STAR);
                    }
                }
                if (cknOn(ckn)) {
                    addStateCheckNum(ckn);
                }
                return;
            }
        }

        int modTLen;
        if (emptyInfo != 0) {
            modTLen = bodyLen + (OPSize.NULL_CHECK_START + OPSize.NULL_CHECK_END);
        } else {
            modTLen = bodyLen;
        }
        if (infinite && qn.lower <= 1) {
            if (qn.greedy) {
                if (qn.lower == 1) {
                    addOpcodeRelAddr(OPCode.JUMP, cknOn(ckn) ? OPSize.STATE_CHECK_PUSH :
                                                                     OPSize.PUSH);
                }
                if (cknOn(ckn)) {
                    addOpcode(OPCode.STATE_CHECK_PUSH);
                    addStateCheckNum(ckn);
                    addRelAddr(modTLen + OPSize.JUMP);
                } else {
                    addOpcodeRelAddr(OPCode.PUSH, modTLen + OPSize.JUMP);
                }
                compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                addOpcodeRelAddr(OPCode.JUMP, -(modTLen + OPSize.JUMP + (cknOn(ckn) ?
                                                                               OPSize.STATE_CHECK_PUSH :
                                                                               OPSize.PUSH)));
            } else {
                if (qn.lower == 0) {
                    addOpcodeRelAddr(OPCode.JUMP, modTLen);
                }
                compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                if (cknOn(ckn)) {
                    addOpcode(OPCode.STATE_CHECK_PUSH_OR_JUMP);
                    addStateCheckNum(ckn);
                    addRelAddr(-(modTLen + OPSize.STATE_CHECK_PUSH_OR_JUMP));
                } else {
                    addOpcodeRelAddr(OPCode.PUSH, -(modTLen + OPSize.PUSH));
                }
            }
        } else if (qn.upper == 0) {
            if (qn.isRefered) { /* /(?<n>..){0}/ */
                addOpcodeRelAddr(OPCode.JUMP, tlen);
                compileTree(qn.target);
            } // else r=0 ???
        } else if (qn.upper == 1 && qn.greedy) {
            if (qn.lower == 0) {
                if (cknOn(ckn)) {
                    addOpcode(OPCode.STATE_CHECK_PUSH);
                    addStateCheckNum(ckn);
                    addRelAddr(bodyLen);
                } else {
                    addOpcodeRelAddr(OPCode.PUSH, bodyLen);
                }
            }
            compileRepeatTree(qn.target, clearId);
        } else if (!qn.greedy && qn.upper == 1 && qn.lower == 0){ /* '??' */
            if (cknOn(ckn)) {
                addOpcode(OPCode.STATE_CHECK_PUSH);
                addStateCheckNum(ckn);
                addRelAddr(OPSize.JUMP);
            } else {
                addOpcodeRelAddr(OPCode.PUSH, OPSize.JUMP);
            }

            addOpcodeRelAddr(OPCode.JUMP, bodyLen);
            compileRepeatTree(qn.target, clearId);
        } else {
            compileRangeRepeatNode(qn, modTLen, emptyInfo, clearId);
            if (cknOn(ckn)) {
                addOpcode(OPCode.STATE_CHECK);
                addStateCheckNum(ckn);
            }
        }
    }

    private int compileNonCECLengthQuantifierNode(QuantifierNode qn) {
        if (isImpossibleQuantifier(qn)) {
            return OPSize.FAIL + (qn.isRefered
                    ? OPSize.JUMP + compileLengthTree(qn.target) : 0);
        }
        boolean infinite = isRepeatInfinite(qn.upper);
        int emptyInfo = qn.targetEmptyInfo;

        int tlen = compileLengthTree(qn.target);
        int repeatClearLen = emptyInfo == 0 ? repeatCaptureClearLength(qn.target) : 0;
        int bodyLen = tlen + repeatClearLen;

        /* anychar repeat */
        if (qn.target.getType() == NodeType.CANY) {
            if (qn.greedy && infinite) {
                if (qn.nextHeadExact != null) {
                    return OPSize.ANYCHAR_STAR_PEEK_NEXT + tlen * qn.lower;
                } else {
                    return OPSize.ANYCHAR_STAR + tlen * qn.lower;
                }
            }
        }

        int modTLen;
        if (emptyInfo != 0) {
            modTLen = bodyLen + (OPSize.NULL_CHECK_START + OPSize.NULL_CHECK_END);
        } else {
            modTLen = bodyLen;
        }

        int len;
        if (infinite && (qn.lower <= 1 || bodyLen * qn.lower <= QUANTIFIER_EXPAND_LIMIT_SIZE)) {
            if (qn.lower == 1 && bodyLen > QUANTIFIER_EXPAND_LIMIT_SIZE) {
                len = OPSize.JUMP;
            } else {
                len = bodyLen * qn.lower;
            }

            if (qn.greedy) {
                if (qn.headExact != null) {
                    len += OPSize.PUSH_OR_JUMP_EXACT1 + modTLen + OPSize.JUMP;
                } else if (qn.nextHeadExact != null && qn.target.getType() != NodeType.CALL) {
                    len += OPSize.PUSH_IF_PEEK_NEXT + modTLen + OPSize.JUMP;
                } else {
                    len += OPSize.PUSH + modTLen + OPSize.JUMP;
                }
            } else {
                len += OPSize.JUMP + modTLen + OPSize.PUSH;
            }

        } else if (qn.upper == 0 && qn.isRefered) { /* /(?<n>..){0}/ */
            len = OPSize.JUMP + tlen;
        } else if (!infinite && qn.greedy &&
                  (qn.upper == 1 || (bodyLen + OPSize.PUSH) * qn.upper <= QUANTIFIER_EXPAND_LIMIT_SIZE )) {
            len = bodyLen * qn.lower;
            len += (OPSize.PUSH + bodyLen) * (qn.upper - qn.lower);
        } else if (!qn.greedy && qn.upper == 1 && qn.lower == 0) { /* '??' */
            len = OPSize.PUSH + OPSize.JUMP + bodyLen;
        } else {
            len = OPSize.REPEAT_INC + modTLen + OPSize.OPCODE + OPSize.RELADDR + OPSize.MEMNUM;
        }
        return len;
    }

    @Override
    protected void compileNonCECQuantifierNode(QuantifierNode qn) {
        regex.requireStack = true;
        if (isImpossibleQuantifier(qn)) {
            if (qn.isRefered) {
                int targetLength = compileLengthTree(qn.target);
                addOpcodeRelAddr(OPCode.JUMP, targetLength);
                compileTree(qn.target);
            }
            addOpcode(OPCode.FAIL);
            return;
        }
        boolean infinite = isRepeatInfinite(qn.upper);
        int emptyInfo = qn.targetEmptyInfo;

        int tlen = compileLengthTree(qn.target);
        int clearId = emptyInfo == 0 ? entryRepeatCaptureClear(qn.target) : -1;
        int bodyLen = tlen + (clearId < 0 ? 0
                : OPSize.REPEAT_CAPTURE_CLEAR + OPSize.REPEAT_CAPTURE_CLEAR_END);

        if (qn.isAnyCharStar()) {
            compileTreeNTimes(qn.target, qn.lower);
            if (qn.nextHeadExact != null) {
                if (isMultiline(regex.options)) {
                    addOpcode(OPCode.ANYCHAR_ML_STAR_PEEK_NEXT);
                } else {
                    addOpcode(OPCode.ANYCHAR_STAR_PEEK_NEXT);
                }
                StringNode sn = (StringNode)qn.nextHeadExact;
                addBytes(sn.bytes, sn.p, 1);
                return;
            } else {
                if (isMultiline(regex.options)) {
                    addOpcode(OPCode.ANYCHAR_ML_STAR);
                } else {
                    addOpcode(OPCode.ANYCHAR_STAR);
                }
                return;
            }
        }

        int modTLen;
        if (emptyInfo != 0) {
            modTLen = bodyLen + (OPSize.NULL_CHECK_START + OPSize.NULL_CHECK_END);
        } else {
            modTLen = bodyLen;
        }
        if (infinite && (qn.lower <= 1 || bodyLen * qn.lower <= QUANTIFIER_EXPAND_LIMIT_SIZE)) {
            if (qn.lower == 1 && bodyLen > QUANTIFIER_EXPAND_LIMIT_SIZE) {
                if (qn.greedy) {
                    if (qn.headExact != null) {
                        addOpcodeRelAddr(OPCode.JUMP, OPSize.PUSH_OR_JUMP_EXACT1);
                    } else if (qn.nextHeadExact != null) {
                        addOpcodeRelAddr(OPCode.JUMP, OPSize.PUSH_IF_PEEK_NEXT);
                    } else {
                        addOpcodeRelAddr(OPCode.JUMP, OPSize.PUSH);
                    }
                } else {
                    addOpcodeRelAddr(OPCode.JUMP, OPSize.JUMP);
                }
            } else {
                compileRepeatTreeNTimes(qn.target, qn.lower, clearId);
            }

            if (qn.greedy) {
                if (qn.headExact != null) {
                    addOpcodeRelAddr(OPCode.PUSH_OR_JUMP_EXACT1, modTLen + OPSize.JUMP);
                    StringNode sn = (StringNode)qn.headExact;
                    addBytes(sn.bytes, sn.p, 1);
                    compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                    addOpcodeRelAddr(OPCode.JUMP, -(modTLen + OPSize.JUMP + OPSize.PUSH_OR_JUMP_EXACT1));
                } else if (qn.nextHeadExact != null && qn.target.getType() != NodeType.CALL) {
                    addOpcodeRelAddr(OPCode.PUSH_IF_PEEK_NEXT, modTLen + OPSize.JUMP);
                    StringNode sn = (StringNode)qn.nextHeadExact;
                    addBytes(sn.bytes, sn.p, 1);
                    compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                    addOpcodeRelAddr(OPCode.JUMP, -(modTLen + OPSize.JUMP + OPSize.PUSH_IF_PEEK_NEXT));
                } else {
                    addOpcodeRelAddr(OPCode.PUSH, modTLen + OPSize.JUMP);
                    compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                    addOpcodeRelAddr(OPCode.JUMP, -(modTLen + OPSize.JUMP + OPSize.PUSH));
                }
            } else {
                addOpcodeRelAddr(OPCode.JUMP, modTLen);
                compileRepeatTreeEmptyCheck(qn.target, emptyInfo, clearId);
                addOpcodeRelAddr(OPCode.PUSH, -(modTLen + OPSize.PUSH));
            }
        } else if (qn.upper == 0 && qn.isRefered) { /* /(?<n>..){0}/ */
            addOpcodeRelAddr(OPCode.JUMP, tlen);
            compileTree(qn.target);
        } else if (!infinite && qn.greedy &&
                  (qn.upper == 1 || (bodyLen + OPSize.PUSH) * qn.upper <= QUANTIFIER_EXPAND_LIMIT_SIZE)) {
            int n = qn.upper - qn.lower;
            compileRepeatTreeNTimes(qn.target, qn.lower, clearId);

            for (int i=0; i<n; i++) {
                addOpcodeRelAddr(OPCode.PUSH, (n - i) * bodyLen + (n - i - 1) * OPSize.PUSH);
                compileRepeatTree(qn.target, clearId);
            }
        } else if (!qn.greedy && qn.upper == 1 && qn.lower == 0) { /* '??' */
            addOpcodeRelAddr(OPCode.PUSH, OPSize.JUMP);
            addOpcodeRelAddr(OPCode.JUMP, bodyLen);
            compileRepeatTree(qn.target, clearId);
        } else {
            compileRangeRepeatNode(qn, modTLen, emptyInfo, clearId);
        }
    }

    private int compileLengthOptionNode(EncloseNode node) {
        int prev = regex.options;
        regex.options = node.option;
        int tlen = compileLengthTree(node.target);
        regex.options = prev;

        if (isDynamic(prev ^ node.option)) {
            return OPSize.SET_OPTION_PUSH + OPSize.SET_OPTION + OPSize.FAIL + tlen + OPSize.SET_OPTION;
        } else {
            return tlen;
        }
    }

    @Override
    protected void compileOptionNode(EncloseNode node) {
        int prev = regex.options;

        if (isDynamic(prev ^ node.option)) {
            regex.requireStack = true;
            addOpcodeOption(OPCode.SET_OPTION_PUSH, node.option);
            addOpcodeOption(OPCode.SET_OPTION, prev);
            addOpcode(OPCode.FAIL);
        }

        regex.options = node.option;
        compileTree(node.target);
        regex.options = prev;

        if (isDynamic(prev ^ node.option)) {
            addOpcodeOption(OPCode.SET_OPTION, prev);
        }
    }

    private int compileLengthEncloseNode(EncloseNode node) {
        if (node.scriptRun) {
            int targetLength = compileLengthTree(node.target);
            return OPSize.PUSH_POS + targetLength + OPSize.SCRIPT_RUN
                    + (node.atomicScriptRun ? OPSize.PUSH_STOP_BT + OPSize.POP_STOP_BT : 0);
        }
        if (node.isOption()) {
            return compileLengthOptionNode(node);
        }

        int tlen;
        if (node.target != null) {
            tlen = compileLengthTree(node.target);
        } else {
            tlen = 0;
        }

        int len;
        switch (node.type) {
        case EncloseType.MEMORY:
            if (Config.USE_SUBEXP_CALL && node.isCalled()) {
                len = OPSize.MEMORY_START_PUSH + tlen + OPSize.CALL + OPSize.JUMP + OPSize.RETURN;
                if (bsAt(regex.btMemEnd, node.regNum)) {
                    len += node.isRecursion() ? OPSize.MEMORY_END_PUSH_REC : OPSize.MEMORY_END_PUSH;
                } else {
                    len += node.isRecursion() ? OPSize.MEMORY_END_REC : OPSize.MEMORY_END;
                }
            } else if (Config.USE_SUBEXP_CALL && node.isRecursion()) { // USE_SUBEXP_CALL
                len = OPSize.MEMORY_START_PUSH; // or OPSize.MEMORY_START;
                len += tlen + (bsAt(regex.btMemEnd, node.regNum) ? OPSize.MEMORY_END_PUSH_REC : OPSize.MEMORY_END_REC);
            } else {
                if (bsAt(regex.btMemStart, node.regNum)) {
                    len = OPSize.MEMORY_START_PUSH;
                } else {
                    len= OPSize.MEMORY_START;
                }
                len += tlen + (bsAt(regex.btMemEnd, node.regNum) ? OPSize.MEMORY_END_PUSH : OPSize.MEMORY_END);
            }
            if (node.physicalNamedCaptureId >= 0) {
                len += OPSize.PHYSICAL_NAMED_CAPTURE_START
                        + OPSize.PHYSICAL_NAMED_CAPTURE_END;
            }
            break;

        case EncloseType.STOP_BACKTRACK:
            if (node.isStopBtSimpleRepeat()) {
                QuantifierNode qn = (QuantifierNode)node.target;
                tlen = compileLengthTree(qn.target);
                len = tlen * qn.lower + OPSize.PUSH + tlen + OPSize.JUMP;
                if (!preservesCalledFrameBacktracking(node)) len += OPSize.POP;
            } else {
                len = OPSize.PUSH_STOP_BT + tlen + OPSize.POP_STOP_BT;
            }
            break;

        case EncloseType.CONDITION:
            if (node.assertionCondition != null) {
                len = OPSize.PUSH_POS_NOT + compileLengthTree(node.assertionCondition.target) + OPSize.POP_POS_NOT;
            } else if (node.recursionConditionGroup >= 0) {
                len = OPSize.RECURSION_CONDITION;
            } else {
                len = node.calloutConditionId >= 0 ? OPSize.CALLOUT_CONDITION : OPSize.CONDITION;
            }
            if (node.target.getType() == NodeType.ALT) {
                ListNode x = (ListNode)node.target;
                tlen = compileLengthTree(x.value); /* yes-node */
                len += tlen + OPSize.JUMP;
                if (x.tail == null) newInternalException(PARSER_BUG);
                x = x.tail;
                tlen = compileLengthTree(x.value); /* no-node */
                len += tlen;
                if (x.tail != null) newSyntaxException(INVALID_CONDITION_PATTERN);
            } else {
                newInternalException(PARSER_BUG);
            }
            break;
        case EncloseType.ABSENT:
            len = OPSize.PUSH_ABSENT_POS + OPSize.ABSENT + tlen + OPSize.ABSENT_END;
            break;
        case EncloseType.DEFINE:
            len = OPSize.JUMP + tlen;
            break;
        default:
            newInternalException(PARSER_BUG);
            return 0; // not reached
        } // switch
        return len;
    }

    @Override
    protected void compileEncloseNode(EncloseNode node) {
        if (node.scriptRun) {
            regex.requireStack = true;
            addOpcodeRelAddr(OPCode.PUSH_POS, 0);
            if (node.atomicScriptRun) addOpcode(OPCode.PUSH_STOP_BT);
            compileTree(node.target);
            if (node.atomicScriptRun) addOpcode(OPCode.POP_STOP_BT);
            addOpcode(OPCode.SCRIPT_RUN);
            return;
        }
        int len;
        switch (node.type) {
        case EncloseType.MEMORY:
            if (Config.USE_SUBEXP_CALL && node.isCalled()) {
                regex.requireStack = true;
                addOpcode(OPCode.CALL);
                node.callAddr = codeLength + OPSize.ABSADDR + OPSize.MEMNUM + OPSize.JUMP;
                node.setAddrFixed();
                addAbsAddr(node.callAddr);
                // This CALL is a compiler implementation detail for a group's
                // ordinary occurrence, not a Perl recursive subpattern call.
                addMemNum(-1);
                len = compileLengthTree(node.target);
                len += OPSize.MEMORY_START_PUSH + OPSize.RETURN;
                if (node.physicalNamedCaptureId >= 0) {
                    len += OPSize.PHYSICAL_NAMED_CAPTURE_START
                            + OPSize.PHYSICAL_NAMED_CAPTURE_END;
                }
                if (bsAt(regex.btMemEnd, node.regNum)) {
                    len += node.isRecursion() ? OPSize.MEMORY_END_PUSH_REC : OPSize.MEMORY_END_PUSH;
                } else {
                    len += node.isRecursion() ? OPSize.MEMORY_END_REC : OPSize.MEMORY_END;
                }
                addOpcodeRelAddr(OPCode.JUMP, len);
            } // USE_SUBEXP_CALL

            if (node.physicalNamedCaptureId >= 0) {
                regex.requireStack = true;
                addOpcode(OPCode.PHYSICAL_NAMED_CAPTURE_START);
                addMemNum(node.physicalNamedCaptureId);
            }

            if (bsAt(regex.btMemStart, node.regNum)) {
                regex.requireStack = true;
                addOpcode(OPCode.MEMORY_START_PUSH);
            } else {
                addOpcode(OPCode.MEMORY_START);
            }

            addMemNum(node.regNum);
            compileTree(node.target);

            if (Config.USE_SUBEXP_CALL && node.isCalled()) {
                if (bsAt(regex.btMemEnd, node.regNum)) {
                    addOpcode(node.isRecursion() ? OPCode.MEMORY_END_PUSH_REC : OPCode.MEMORY_END_PUSH);
                } else {
                    addOpcode(node.isRecursion() ? OPCode.MEMORY_END_REC : OPCode.MEMORY_END);
                }
                addMemNum(node.regNum);
                if (node.physicalNamedCaptureId >= 0) {
                    addOpcode(OPCode.PHYSICAL_NAMED_CAPTURE_END);
                    addMemNum(node.physicalNamedCaptureId);
                }
                addOpcode(OPCode.RETURN);
            } else if (Config.USE_SUBEXP_CALL && node.isRecursion()) { // USE_SUBEXP_CALL
                if (bsAt(regex.btMemEnd, node.regNum)) {
                    addOpcode(OPCode.MEMORY_END_PUSH_REC);
                } else {
                    addOpcode(OPCode.MEMORY_END_REC);
                }
                addMemNum(node.regNum);
                if (node.physicalNamedCaptureId >= 0) {
                    addOpcode(OPCode.PHYSICAL_NAMED_CAPTURE_END);
                    addMemNum(node.physicalNamedCaptureId);
                }
            } else {
                if (bsAt(regex.btMemEnd, node.regNum)) {
                    addOpcode(OPCode.MEMORY_END_PUSH);
                } else {
                    addOpcode(OPCode.MEMORY_END);
                }
                addMemNum(node.regNum);
                if (node.physicalNamedCaptureId >= 0) {
                    addOpcode(OPCode.PHYSICAL_NAMED_CAPTURE_END);
                    addMemNum(node.physicalNamedCaptureId);
                }
            }
            break;

        case EncloseType.STOP_BACKTRACK:
            regex.requireStack = true;
            if (node.isStopBtSimpleRepeat()) {
                QuantifierNode qn = (QuantifierNode)node.target;

                compileTreeNTimes(qn.target, qn.lower);

                len = compileLengthTree(qn.target);
                int tailLen = preservesCalledFrameBacktracking(node)
                        ? OPSize.JUMP : OPSize.POP + OPSize.JUMP;
                addOpcodeRelAddr(OPCode.PUSH, len + tailLen);
                compileTree(qn.target);
                if (!preservesCalledFrameBacktracking(node)) addOpcode(OPCode.POP);
                addOpcodeRelAddr(OPCode.JUMP, -(OPSize.PUSH + len + tailLen));
            } else {
                addOpcode(OPCode.PUSH_STOP_BT);
                compileTree(node.target);
                addOpcode(OPCode.POP_STOP_BT);
            }
            break;

        case EncloseType.CONDITION:
            if (node.calloutConditionId >= 0 || node.assertionCondition != null
                    || node.recursionConditionGroup >= 0) regex.requireStack = true;
            if (node.target.getType() == NodeType.ALT) {
                ListNode x = (ListNode)node.target;
                len = compileLengthTree(x.value); /* yes-node */
                if (x.tail == null) newInternalException(PARSER_BUG);
                x = x.tail;
                int len2 = compileLengthTree(x.value); /* no-node */
                if (x.tail != null) newSyntaxException(INVALID_CONDITION_PATTERN);
                x = (ListNode)node.target;
                if (node.assertionCondition != null) {
                    boolean positive = node.assertionCondition.type == AnchorType.PREC_READ;
                    ListNode yes = x;
                    ListNode no = x.tail;
                    ListNode first = positive ? yes : no;
                    ListNode second = positive ? no : yes;
                    int firstLength = compileLengthTree(first.value);
                    int secondLength = compileLengthTree(second.value);
                    int conditionLength = compileLengthTree(node.assertionCondition.target);
                    addOpcodeRelAddr(OPCode.PUSH_POS_NOT,
                            conditionLength + OPSize.POP_POS_NOT + firstLength + OPSize.JUMP);
                    compileTree(node.assertionCondition.target);
                    addOpcode(OPCode.POP_POS_NOT);
                    compileTree(first.value);
                    addOpcodeRelAddr(OPCode.JUMP, secondLength);
                    compileTree(second.value);
                    break;
                } else {
                    addOpcode(node.calloutConditionId >= 0 ? OPCode.CALLOUT_CONDITION
                            : node.recursionConditionGroup >= 0 ? OPCode.RECURSION_CONDITION
                            : OPCode.CONDITION);
                    addMemNum(node.calloutConditionId >= 0 ? node.calloutConditionId
                            : node.recursionConditionGroup >= 0 ? node.recursionConditionGroup
                            : node.physicalNamedCondition > 0 ? -node.physicalNamedCondition
                            : node.regNum);
                    addRelAddr(len + OPSize.JUMP);
                }
                compileTree(x.value); /* yes-node */
                addOpcodeRelAddr(OPCode.JUMP, len2);
                x = x.tail;
                compileTree(x.value); /* no-node */
            } else {
                newInternalException(PARSER_BUG);
            }
            break;

        case EncloseType.ABSENT:
            regex.requireStack = true;
            len = compileLengthTree(node.target);
            addOpcode(OPCode.PUSH_ABSENT_POS);
            addOpcodeRelAddr(OPCode.ABSENT, len + OPSize.ABSENT_END);
            compileTree(node.target);
            addOpcode(OPCode.ABSENT_END);
            break;

        case EncloseType.DEFINE:
            addOpcodeRelAddr(OPCode.JUMP, compileLengthTree(node.target));
            compileTree(node.target);
            break;

        default:
            newInternalException(PARSER_BUG);
            break;
        } // switch
    }

    private int compileLengthAnchorNode(AnchorNode node) {
        int tlen;
        if (node.target != null) {
            tlen = compileLengthTree(node.target);
        } else {
            tlen = 0;
        }

        int len;
        switch (node.type) {
        case AnchorType.PREC_READ:
            len = OPSize.PUSH_POS + tlen + OPSize.POP_POS;
            break;

        case AnchorType.PREC_READ_NOT:
            len = OPSize.PUSH_POS_NOT + tlen + OPSize.FAIL_POS;
            break;

        case AnchorType.LOOK_BEHIND:
            if (node.variableLookBehindMin >= 0) {
                return compileLengthVariableLookBehind(node, false);
            }
            len = OPSize.LOOK_BEHIND + tlen;
            break;

        case AnchorType.LOOK_BEHIND_NOT:
            if (node.variableLookBehindMin >= 0) {
                return compileLengthVariableLookBehind(node, true);
            }
            len = OPSize.PUSH_LOOK_BEHIND_NOT + tlen + OPSize.FAIL_LOOK_BEHIND_NOT;
            break;

        default:
            len = OPSize.OPCODE;
            break;
        } // switch
        return len;
    }

    private int compileLengthVariableLookBehind(AnchorNode node, boolean negative) {
        if (node.variableLookBehindTargetLength < 0) {
            int targetLength = compileLengthTree(node.target);
            int variants = node.variableLookBehindMax - node.variableLookBehindMin + 1;
            int bodyLength = negative
                    ? OPSize.PUSH_LOOK_BEHIND_NOT + targetLength
                            + OPSize.CHECK_LOOK_BEHIND_END + OPSize.FAIL_LOOK_BEHIND_NOT
                    : OPSize.PUSH_POS + OPSize.LOOK_BEHIND + targetLength
                            + OPSize.CHECK_POS_END + OPSize.POP_POS;
            int length = variants * bodyLength;
            if (!negative && variants > 1) length += (variants - 1) * (OPSize.PUSH + OPSize.JUMP);
            return length;
        }
        QuantifierNode quantifier = (QuantifierNode)node.target;
        int targetLength = compileLengthTree(quantifier.target);
        int variants = node.variableLookBehindMax - node.variableLookBehindMin + 1;
        int length = 0;
        for (int count = node.variableLookBehindMin;
                count <= node.variableLookBehindMax; count++) {
            int bodyLength = targetLength * count;
            length += negative
                    ? OPSize.PUSH_LOOK_BEHIND_NOT + bodyLength + OPSize.FAIL_LOOK_BEHIND_NOT
                    : OPSize.LOOK_BEHIND + bodyLength;
        }
        if (!negative && variants > 1) {
            length += (variants - 1) * (OPSize.PUSH + OPSize.JUMP);
        }
        return length;
    }

    @Override
    protected void compileAnchorNode(AnchorNode node) {
        int len;
        int n;

        switch (node.type) {
        case AnchorType.BEGIN_BUF:          addOpcode(OPCode.BEGIN_BUF);            break;
        case AnchorType.END_BUF:            addOpcode(OPCode.END_BUF);              break;
        case AnchorType.BEGIN_LINE:         addOpcode(OPCode.BEGIN_LINE);           break;
        case AnchorType.END_LINE:           addOpcode(OPCode.END_LINE);             break;
        case AnchorType.SEMI_END_BUF:       addOpcode(OPCode.SEMI_END_BUF);         break;
        case AnchorType.BEGIN_POSITION:     addOpcode(OPCode.BEGIN_POSITION);       break;

        case AnchorType.WORD_BOUND:
            if (node.asciiRange) {
                addOpcode(OPCode.ASCII_WORD_BOUND);
            } else {
                addOpcode(OPCode.WORD_BOUND);
            }
            break;

        case AnchorType.NOT_WORD_BOUND:
            if (node.asciiRange) {
                addOpcode(OPCode.ASCII_NOT_WORD_BOUND);
            } else {
                addOpcode(OPCode.NOT_WORD_BOUND);
            }
            break;

        case AnchorType.GRAPHEME_BOUNDARY:
            addOpcode(OPCode.GRAPHEME_BOUNDARY);
            break;

        case AnchorType.NOT_GRAPHEME_BOUNDARY:
            addOpcode(OPCode.NOT_GRAPHEME_BOUNDARY);
            break;

        case AnchorType.SENTENCE_BOUNDARY:
            addOpcode(OPCode.SENTENCE_BOUNDARY);
            break;

        case AnchorType.NOT_SENTENCE_BOUNDARY:
            addOpcode(OPCode.NOT_SENTENCE_BOUNDARY);
            break;

        case AnchorType.WORD_BREAK_BOUNDARY:
            addOpcode(OPCode.WORD_BREAK_BOUNDARY);
            break;

        case AnchorType.NOT_WORD_BREAK_BOUNDARY:
            addOpcode(OPCode.NOT_WORD_BREAK_BOUNDARY);
            break;

        case AnchorType.LINE_BOUNDARY:
            addOpcode(OPCode.LINE_BOUNDARY);
            break;

        case AnchorType.NOT_LINE_BOUNDARY:
            addOpcode(OPCode.NOT_LINE_BOUNDARY);
            break;

        case AnchorType.WORD_BEGIN:
            if (Config.USE_WORD_BEGIN_END) {
                if (node.asciiRange) {
                    addOpcode(OPCode.ASCII_WORD_BEGIN);
                } else {
                    addOpcode(OPCode.WORD_BEGIN);
                }
            }
            break;

        case AnchorType.WORD_END:
            if (Config.USE_WORD_BEGIN_END) {
                if (node.asciiRange) {
                    addOpcode(OPCode.ASCII_WORD_END);
                } else {
                    addOpcode(OPCode.WORD_END);
                }
            }
            break;

        case AnchorType.KEEP:
            addOpcode(OPCode.KEEP);
            break;

        case AnchorType.PREC_READ:
            regex.requireStack = true;
            len = compileLengthTree(node.target);
            addOpcodeRelAddr(OPCode.PUSH_POS, len + OPSize.POP_POS);
            compileTree(node.target);
            addOpcode(OPCode.POP_POS);
            break;

        case AnchorType.PREC_READ_NOT:
            regex.requireStack = true;
            len = compileLengthTree(node.target);
            addOpcodeRelAddr(OPCode.PUSH_POS_NOT, len + OPSize.FAIL_POS);
            compileTree(node.target);
            addOpcode(OPCode.FAIL_POS);
            break;

        case AnchorType.LOOK_BEHIND:
            if (node.variableLookBehindMin >= 0) {
                compileVariableLookBehind(node, false);
                break;
            }
            addOpcode(OPCode.LOOK_BEHIND);
            if (node.charLength < 0) {
                n = analyser.getCharLengthTree(node.target);
                if (analyser.returnCode != 0) newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
            } else {
                n = node.charLength;
            }
            addLength(n);
            compileTree(node.target);
            break;

        case AnchorType.LOOK_BEHIND_NOT:
            if (node.variableLookBehindMin >= 0) {
                compileVariableLookBehind(node, true);
                break;
            }
            regex.requireStack = true;
            len = compileLengthTree(node.target);
            addOpcodeRelAddr(OPCode.PUSH_LOOK_BEHIND_NOT, len + OPSize.FAIL_LOOK_BEHIND_NOT);
            if (node.charLength < 0) {
                n = analyser.getCharLengthTree(node.target);
                if (analyser.returnCode != 0) newSyntaxException(INVALID_LOOK_BEHIND_PATTERN);
            } else {
                n = node.charLength;
            }
            addLength(n);
            compileTree(node.target);
            addOpcode(OPCode.FAIL_LOOK_BEHIND_NOT);
            break;

        default:
            newInternalException(PARSER_BUG);
        } // switch
    }

    private int compileLengthTree(Node node) {
        if (node instanceof CClassNode) return compileLengthCClassNode((CClassNode)node);
        if (node instanceof WideScalarNode) return OPSize.WIDE_SCALAR;
        if (node instanceof CalloutNode callout) {
            return callout.dynamic ? OPSize.DYNAMIC_CALLOUT : OPSize.CALLOUT;
        }
        if (node instanceof ControlVerbNode control) {
            return switch (control.kind) {
                case ACCEPT -> OPSize.ACCEPT;
                case FAIL -> OPSize.CONTROL_FAIL;
                case PRUNE -> OPSize.PRUNE;
                case SKIP -> OPSize.SKIP;
                case THEN -> OPSize.THEN;
                case COMMIT -> OPSize.COMMIT;
                case MARK -> OPSize.MARK;
            };
        }
        int len = 0;

        switch (node.getType()) {
        case NodeType.LIST:
            ListNode lin = (ListNode)node;
            do {
                len += compileLengthTree(lin.value);
            } while ((lin = lin.tail) != null);
            break;

        case NodeType.ALT:
            ListNode aln = (ListNode)node;
            int n = 0;
            do {
                len += compileLengthTree(aln.value);
                n++;
            } while ((aln = aln.tail) != null);
            len += (OPSize.PUSH + OPSize.JUMP) * (n - 1);
            break;

        case NodeType.STR:
            StringNode sn = (StringNode)node;
            if (sn.isRaw()) {
                len = compileLengthStringRawNode(sn);
            } else {
                len = compileLengthStringNode(sn);
            }
            break;

        case NodeType.CCLASS:
            len = compileLengthCClassNode((CClassNode)node);
            break;

        case NodeType.CTYPE:
        case NodeType.CANY:
            len = OPSize.OPCODE;
            break;

        case NodeType.BREF:
            BackRefNode br = (BackRefNode)node;

            if (usesPreviousRepeatCapture(br) || usesPreviousRecursiveFrameCapture(br)) {
                len = OPSize.OPCODE + OPSize.MEMNUM;
            } else if (Config.USE_BACKREF_WITH_LEVEL && br.isNestLevel()) {
                len = OPSize.OPCODE + OPSize.OPTION + OPSize.LENGTH +
                      OPSize.LENGTH + (OPSize.MEMNUM * br.backNum);
            } else { // USE_BACKREF_AT_LEVEL
                if (br.backNum == 1) {
                    len = ((!isIgnoreCase(regex.options) && br.back[0] <= 2)
                            ? OPSize.OPCODE : (OPSize.OPCODE + OPSize.MEMNUM));
                } else {
                    len = OPSize.OPCODE + OPSize.LENGTH + (OPSize.MEMNUM * br.backNum);
                }
            }
            break;

        case NodeType.CALL:
            if (Config.USE_SUBEXP_CALL) {
                len = OPSize.CALL;
                break;
            } // USE_SUBEXP_CALL
            break;

        case NodeType.QTFR:
            QuantifierNode quantifier = (QuantifierNode)node;
            if (Config.USE_CEC && regex.numCombExpCheck > 0 && quantifier.combExpCheckNum > 0) {
                len = compileCECLengthQuantifierNode(quantifier);
            } else {
                len = compileNonCECLengthQuantifierNode(quantifier);
            }
            break;

        case NodeType.ENCLOSE:
            len = compileLengthEncloseNode((EncloseNode)node);
            break;

        case NodeType.ANCHOR:
            len = compileLengthAnchorNode((AnchorNode)node);
            break;

        default:
            newInternalException(PARSER_BUG);

        } //switch
        return len;
    }

    private void ensure(int size) {
        if (size >= code.length) {
            int length = code.length << 1;
            while (length <= size) length <<= 1;
            int[]tmp = new int[length];
            System.arraycopy(code, 0, tmp, 0, code.length);
            code = tmp;
        }
    }

    private void compileVariableLookBehind(AnchorNode node, boolean negative) {
        if (node.variableLookBehindTargetLength < 0) {
            compileCompoundVariableLookBehind(node, negative);
            return;
        }
        QuantifierNode quantifier = (QuantifierNode)node.target;
        int targetCodeLength = compileLengthTree(quantifier.target);
        if (negative) {
            regex.requireStack = true;
            for (int count = node.variableLookBehindMin;
                    count <= node.variableLookBehindMax; count++) {
                int bodyLength = targetCodeLength * count;
                addOpcodeRelAddr(OPCode.PUSH_LOOK_BEHIND_NOT,
                        bodyLength + OPSize.FAIL_LOOK_BEHIND_NOT);
                addLength(node.variableLookBehindTargetLength * count);
                compileTreeNTimes(quantifier.target, count);
                addOpcode(OPCode.FAIL_LOOK_BEHIND_NOT);
            }
            return;
        }

        int variants = node.variableLookBehindMax - node.variableLookBehindMin + 1;
        int[] counts = new int[variants];
        int[] bodies = new int[variants];
        int remaining = 0;
        for (int i = 0; i < variants; i++) {
            counts[i] = quantifier.greedy
                    ? node.variableLookBehindMax - i : node.variableLookBehindMin + i;
            bodies[i] = OPSize.LOOK_BEHIND + targetCodeLength * counts[i];
            remaining += bodies[i];
            if (i + 1 < variants) remaining += OPSize.PUSH + OPSize.JUMP;
        }
        regex.requireStack = variants > 1 || regex.requireStack;
        for (int i = 0; i < variants; i++) {
            if (i + 1 < variants) {
                addOpcodeRelAddr(OPCode.PUSH, bodies[i] + OPSize.JUMP);
            }
            addOpcode(OPCode.LOOK_BEHIND);
            addLength(node.variableLookBehindTargetLength * counts[i]);
            compileTreeNTimes(quantifier.target, counts[i]);
            remaining -= bodies[i];
            if (i + 1 < variants) {
                remaining -= OPSize.PUSH + OPSize.JUMP;
                addOpcodeRelAddr(OPCode.JUMP, remaining);
            }
        }
    }

    private void compileCompoundVariableLookBehind(AnchorNode node, boolean negative) {
        int targetLength = compileLengthTree(node.target);
        regex.requireStack = true;
        if (negative) {
            for (int length = node.variableLookBehindMin;
                    length <= node.variableLookBehindMax; length++) {
                addOpcodeRelAddr(OPCode.PUSH_LOOK_BEHIND_NOT,
                        targetLength + OPSize.CHECK_LOOK_BEHIND_END
                                + OPSize.FAIL_LOOK_BEHIND_NOT);
                addLength(length);
                compileTree(node.target);
                addOpcode(OPCode.CHECK_LOOK_BEHIND_END);
                addOpcode(OPCode.FAIL_LOOK_BEHIND_NOT);
            }
            return;
        }

        int variants = node.variableLookBehindMax - node.variableLookBehindMin + 1;
        int bodyLength = OPSize.PUSH_POS + OPSize.LOOK_BEHIND + targetLength
                + OPSize.CHECK_POS_END + OPSize.POP_POS;
        int remaining = variants * bodyLength + (variants - 1) * (OPSize.PUSH + OPSize.JUMP);
        for (int i = 0; i < variants; i++) {
            int length = node.variableLookBehindMax - i;
            if (i + 1 < variants) addOpcodeRelAddr(OPCode.PUSH, bodyLength + OPSize.JUMP);
            addOpcodeRelAddr(OPCode.PUSH_POS,
                    OPSize.LOOK_BEHIND + targetLength + OPSize.CHECK_POS_END + OPSize.POP_POS);
            addOpcode(OPCode.LOOK_BEHIND);
            addLength(length);
            compileTree(node.target);
            addOpcode(OPCode.CHECK_POS_END);
            addOpcode(OPCode.POP_POS);
            remaining -= bodyLength;
            if (i + 1 < variants) {
                remaining -= OPSize.PUSH + OPSize.JUMP;
                addOpcodeRelAddr(OPCode.JUMP, remaining);
            }
        }
    }

    private void addInt(int i) {
        if (codeLength >= code.length) {
            int[]tmp = new int[code.length << 1];
            System.arraycopy(code, 0, tmp, 0, code.length);
            code = tmp;
        }
        code[codeLength++] = i;
    }

    void setInt(int i, int offset) {
        ensure(offset);
        regex.code[offset] = i;
    }

    private void addBytes(byte[]bytes, int p ,int length) {
        ensure(codeLength + length);
        int end = p + length;

        while (p < end) code[codeLength++] = bytes[p++];
    }

    private void addInts(int[]ints, int length) {
        ensure(codeLength + length);
        System.arraycopy(ints, 0, code, codeLength, length);
        codeLength += length;
    }

    private void addOpcode(int opcode) {
        addInt(opcode);
    }

    private void addStateCheckNum(int num) {
        addInt(num);
    }

    private void addRelAddr(int addr) {
        addInt(addr);
    }

    private void addAbsAddr(int addr) {
        addInt(addr);
    }

    private void addLength(int length) {
        addInt(length);
    }

    private void addMemNum(int num) {
        addInt(num);
    }

    private void addOption(int option) {
        addInt(option);
    }

    private void addOpcodeRelAddr(int opcode, int addr) {
        addOpcode(opcode);
        addRelAddr(addr);
    }

    private void addOpcodeOption(int opcode, int option) {
        addOpcode(opcode);
        addOption(option);
    }

    private void addTemplate(byte[]bytes) {
        if (templateNum == 0) {
            templates = new byte[2][];
        } else if (templateNum == templates.length) {
            byte[][]tmp = new byte[templateNum * 2][];
            System.arraycopy(templates, 0, tmp, 0, templateNum);
            templates = tmp;
        }
        templates[templateNum++] = bytes;
    }
}
