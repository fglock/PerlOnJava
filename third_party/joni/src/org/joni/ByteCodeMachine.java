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

import java.util.ArrayDeque;

import static org.joni.BitStatus.bsAt;
import static org.joni.Config.USE_CEC;
import static org.joni.Option.isFindCondition;
import static org.joni.Option.isFindLongest;
import static org.joni.Option.isFindNotEmpty;
import static org.joni.Option.isNotBol;
import static org.joni.Option.isNotEol;

import org.jcodings.CodeRange;
import org.jcodings.Encoding;
import org.jcodings.IntHolder;
import org.jcodings.unicode.UnicodeCodeRange;
import org.joni.constants.internal.OPCode;
import org.joni.constants.internal.OPSize;
import org.joni.exception.ErrorMessages;
import org.joni.exception.InternalException;
import org.joni.exception.ValueException;

class ByteCodeMachine extends StackMachine implements MatchView {
    private static final int MAX_INTERRUPT_CHECK_EVERY = 256 << 7; // 32768
    private static final int MAX_SUBEXP_CALL_DEPTH = 1000;
    private static final int CONTROL_NONE = 0;
    private static final int CONTROL_PRUNE = 1;
    private static final int CONTROL_SKIP = 2;
    private static final int CONTROL_THEN = 3;
    private static final int CONTROL_COMMIT = 4;
    int interruptCheckEvery = 256;     // << 1 after each check up to  ^^^
    volatile boolean interrupted = false;

    private int bestLen;          // return value
    private int s = 0;            // current char

    private int range;            // right range
    private int sprev;
    private int sstart;
    private int sbegin;
    private int pkeep;
    private int currentRegexOptions;
    private int pendingControlAction;

    private final int[]code;        // byte code
    private int ip;                 // instruction pointer

    ByteCodeMachine(Regex regex, Region region, byte[]bytes, int p, int end) {
        super(regex, region, bytes, p, end);
        this.code = regex.code;
    }

    @Override
    public void interrupt() {
        interrupted = true;
        // might have no effect on the executing thread but worth a try
        // we might not succeed interrupting on next loop but will eventually
        synchronized (this) { interruptCheckEvery = 0; }
    }

    protected int stkp; // a temporary
    private boolean makeCaptureHistoryTree(CaptureTreeNode node) {
        //CaptureTreeNode child;
        int k = stkp;
        //int k = kp;

        while (k < stk) {
            StackEntry e = stack[k];
            if (e.type == MEM_START) {
                int n = e.getMemNum();
                if (n <= Config.MAX_CAPTURE_HISTORY_GROUP && bsAt(regex.captureHistory, n)) {
                    CaptureTreeNode child = new CaptureTreeNode();
                    child.group = n;
                    child.beg = e.getMemPStr() - str;
                    node.addChild(child);
                    stkp = k + 1;
                    if (makeCaptureHistoryTree(child)) return true;

                    k = stkp;
                    child.end = e.getMemPStr() - str;
                }
            } else if (e.type == MEM_END) {
                if (e.getMemNum() == node.group) {
                    node.end = e.getMemPStr() - str;
                    stkp = k;
                    return false;
                }
            }
        }
        return true; /* 1: root node ending. */
    }

    private void checkCaptureHistory(Region region) {
        CaptureTreeNode node;
        if (region.getCaptureTree() == null) {
            node = region.setCaptureTree(new CaptureTreeNode());
        } else {
            node = region.getCaptureTree();
            node.clear();
        }

        // was clear ???
        node.group = 0;
        node.beg = ((pkeep > s) ? s : pkeep) - str;
        node.end = s      - str;

        stkp = 0;
        makeCaptureHistoryTree(region.getCaptureTree());
    }

    private byte[]cfbuf;
    private byte[]cfbuf2;

    protected final byte[]cfbuf() {
        return cfbuf == null ? cfbuf = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN] : cfbuf;
    }

    protected final byte[]cfbuf2() {
        return cfbuf2 == null ? cfbuf2 = new byte[Config.ENC_MBC_CASE_FOLD_MAXLEN] : cfbuf2;
    }

    private boolean stringCmpIC(int caseFlodFlag, int s1, IntHolder ps2, int mbLen, int textEnd) {
        byte[]buf1 = cfbuf();
        byte[]buf2 = cfbuf2();

        int s2 = ps2.value;
        int end1 = s1 + mbLen;

        while (s1 < end1) {
            value = s1;
            int len1 = enc.mbcCaseFold(caseFlodFlag, bytes, this, textEnd, buf1);
            s1 = value;
            value = s2;
            int len2 = enc.mbcCaseFold(caseFlodFlag, bytes, this, textEnd, buf2);
            s2 = value;

            if (len1 != len2) return false;
            int p1 = 0;
            int p2 = 0;

            while (len1-- > 0) {
                if (buf1[p1] != buf2[p2]) return false;
                p1++; p2++;
            }
        }
        ps2.value = s2;
        return true;
    }

    @Override
    protected final int matchAt(int _range, int _sstart, int _sprev, boolean interrupt) throws InterruptedException {
        range = _range;
        sstart = _sstart;
        sprev = _sprev;
        stk = 0;
        ip = 0;
        currentRegexOptions = regex.options;
        controlMark = null;

        if (Config.DEBUG_MATCH) debugMatchBegin();
        stackInit();

        bestLen = -1;
        s = _sstart;
        pkeep = _sstart;
        int result = -1;
        try {
            result = enc.isSingleByte() || (msaOptions & Option.CR_7_BIT) != 0
                    ? executeSb(interrupt) : execute(interrupt);
            return result;
        } finally {
            if (result >= 0) {
                controlError = null;
                completeActiveCallouts();
            } else {
                if (controlMark != null) controlError = controlMark;
                controlMark = null;
                unwindActiveCallouts();
            }
        }
    }

    private final int execute(final boolean checkThreadInterrupt) throws InterruptedException {
        final int[] code = this.code;
        int interruptCheckCounter = 0;
        while (true) {
            if (interruptCheckCounter++ >= interruptCheckEvery) {
                if (timeout != -1) handleTimeout();
                handleInterrupted(checkThreadInterrupt);
                interruptCheckCounter = 0;
            }

            if (Config.DEBUG_MATCH) debugMatchLoop();

            sbegin = s;
            checkInstructionPointer();
            switch (code[ip++]) {
                case OPCode.END:    if (opEnd()) return finish();                  break;
                case OPCode.EXACT1:                     opExact1();                break;
                case OPCode.EXACT2:                     opExact2();                continue;
                case OPCode.EXACT3:                     opExact3();                continue;
                case OPCode.EXACT4:                     opExact4();                continue;
                case OPCode.EXACT5:                     opExact5();                continue;
                case OPCode.EXACTN:                     opExactN();                continue;

                case OPCode.EXACTMB2N1:                 opExactMB2N1();            break;
                case OPCode.EXACTMB2N2:                 opExactMB2N2();            continue;
                case OPCode.EXACTMB2N3:                 opExactMB2N3();            continue;
                case OPCode.EXACTMB2N:                  opExactMB2N();             continue;
                case OPCode.EXACTMB3N:                  opExactMB3N();             continue;
                case OPCode.EXACTMBN:                   opExactMBN();              continue;

                case OPCode.EXACT1_IC:                  opExact1IC();              break;
                case OPCode.EXACTN_IC:                  opExactNIC();              continue;

                case OPCode.CCLASS:                     opCClass();                break;
                case OPCode.CCLASS_MB:                  opCClassMB();              break;
                case OPCode.CCLASS_MIX:                 opCClassMIX();             break;
                case OPCode.CCLASS_NOT:                 opCClassNot();             break;
                case OPCode.CCLASS_MB_NOT:              opCClassMBNot();           break;
                case OPCode.CCLASS_MIX_NOT:             opCClassMIXNot();          break;

                case OPCode.ANYCHAR:                    opAnyChar();               break;
                case OPCode.ANYCHAR_ML:                 opAnyCharML();             break;
                case OPCode.ANYCHAR_STAR:               opAnyCharStar();           break;
                case OPCode.ANYCHAR_ML_STAR:            opAnyCharMLStar();         break;
                case OPCode.ANYCHAR_STAR_PEEK_NEXT:     opAnyCharStarPeekNext();   break;
                case OPCode.ANYCHAR_ML_STAR_PEEK_NEXT:  opAnyCharMLStarPeekNext(); break;

                case OPCode.WORD:                       opWord();                  break;
                case OPCode.NOT_WORD:                   opNotWord();               break;
                case OPCode.WORD_BOUND:                 opWordBound();             continue;
                case OPCode.NOT_WORD_BOUND:             opNotWordBound();          continue;
                case OPCode.GRAPHEME_BOUNDARY:          opGraphemeBoundary(false); continue;
                case OPCode.NOT_GRAPHEME_BOUNDARY:      opGraphemeBoundary(true);  continue;
                case OPCode.SENTENCE_BOUNDARY:          opSentenceBoundary(false); continue;
                case OPCode.NOT_SENTENCE_BOUNDARY:      opSentenceBoundary(true);  continue;
                case OPCode.WORD_BEGIN:                 opWordBegin();             continue;
                case OPCode.WORD_END:                   opWordEnd();               continue;

                case OPCode.ASCII_WORD:                 opAsciiWord();             break;
                case OPCode.ASCII_NOT_WORD:             opNotAsciiWord();          break;
                case OPCode.ASCII_WORD_BOUND:           opAsciiWordBound();        break;
                case OPCode.ASCII_NOT_WORD_BOUND:       opNotAsciiWordBound();     continue;
                case OPCode.ASCII_WORD_BEGIN:           opAsciiWordBegin();        continue;
                case OPCode.ASCII_WORD_END:             opAsciiWordEnd();          continue;

                case OPCode.BEGIN_BUF:                  opBeginBuf();              continue;
                case OPCode.END_BUF:                    opEndBuf();                continue;
                case OPCode.BEGIN_LINE:                 opBeginLine();             continue;
                case OPCode.END_LINE:                   opEndLine();               continue;
                case OPCode.SEMI_END_BUF:               opSemiEndBuf();            continue;
                case OPCode.BEGIN_POSITION:             opBeginPosition();         continue;

                case OPCode.MEMORY_START_PUSH:          opMemoryStartPush();       continue;
                case OPCode.MEMORY_START:               opMemoryStart();           continue;
                case OPCode.MEMORY_END_PUSH:            opMemoryEndPush();         continue;
                case OPCode.MEMORY_END:                 opMemoryEnd();             continue; //
                case OPCode.KEEP:                       opKeep();                  continue;
                case OPCode.MEMORY_END_PUSH_REC:        opMemoryEndPushRec();      continue;
                case OPCode.MEMORY_END_REC:             opMemoryEndRec();          continue;

                case OPCode.BACKREF1:                   opBackRef1();              continue;
                case OPCode.BACKREF2:                   opBackRef2();              continue;
                case OPCode.BACKREFN:                   opBackRefN();              continue;
                case OPCode.BACKREFN_IC:                opBackRefNIC();            continue;
                case OPCode.BACKREF_MULTI:              opBackRefMulti();          continue;
                case OPCode.BACKREF_MULTI_IC:           opBackRefMultiIC();        continue;
                case OPCode.BACKREF_WITH_LEVEL:         opBackRefAtLevel();        continue;

                case OPCode.SET_OPTION_PUSH:            opSetOptionPush();         continue;
                case OPCode.SET_OPTION:                 opSetOption();             continue;

                case OPCode.NULL_CHECK_START:           opNullCheckStart();        continue;
                case OPCode.NULL_CHECK_END:             opNullCheckEnd();          continue;
                case OPCode.NULL_CHECK_END_MEMST:       opNullCheckEndMemST();     continue;
                case OPCode.NULL_CHECK_END_MEMST_PUSH:  opNullCheckEndMemSTPush(); continue;

                case OPCode.JUMP:                       opJump();                  continue;
                case OPCode.PUSH:                       opPush();                  continue;

                case OPCode.POP:                        opPop();                   continue;
                case OPCode.PUSH_OR_JUMP_EXACT1:        opPushOrJumpExact1();      continue;
                case OPCode.PUSH_IF_PEEK_NEXT:          opPushIfPeekNext();        continue;

                case OPCode.REPEAT:                     opRepeat();                continue;
                case OPCode.REPEAT_NG:                  opRepeatNG();              continue;
                case OPCode.REPEAT_INC:                 opRepeatInc();             continue;
                case OPCode.REPEAT_INC_SG:              opRepeatIncSG();           continue;
                case OPCode.REPEAT_INC_NG:              opRepeatIncNG();           continue;
                case OPCode.REPEAT_INC_NG_SG:           opRepeatIncNGSG();         continue;

                case OPCode.PUSH_POS:                   opPushPos();               continue;
                case OPCode.POP_POS:                    opPopPos();                continue;
                case OPCode.PUSH_POS_NOT:               opPushPosNot();            continue;
                case OPCode.POP_POS_NOT:                opPopPosNot();             continue;
                case OPCode.FAIL_POS:                   opFailPos();               continue;
                case OPCode.PUSH_STOP_BT:               opPushStopBT();            continue;
                case OPCode.POP_STOP_BT:                opPopStopBT();             continue;

                case OPCode.LOOK_BEHIND:                opLookBehind();            continue;
                case OPCode.PUSH_LOOK_BEHIND_NOT:       opPushLookBehindNot();     continue;
                case OPCode.FAIL_LOOK_BEHIND_NOT:       opFailLookBehindNot();     continue;

                case OPCode.PUSH_ABSENT_POS:            opPushAbsentPos();         continue;
                case OPCode.ABSENT:                     opAbsent();                continue;
                case OPCode.ABSENT_END:                 opAbsentEnd();             continue;

                case OPCode.CALL:                       opCall();                  continue;
                case OPCode.RETURN:                     opReturn();                continue;
                case OPCode.CONDITION:                  opCondition();             continue;
                case OPCode.RECURSION_CONDITION:        opRecursionCondition();    continue;
                case OPCode.CHECK_POS_END:              opCheckPosEnd();           continue;
                case OPCode.CHECK_LOOK_BEHIND_END:      opCheckLookBehindEnd();    continue;
                case OPCode.FINISH:                     return finish();
                case OPCode.FAIL:                       opFail();                  continue;
                case OPCode.CALLOUT:                    opCallout();               continue;
                case OPCode.CALLOUT_CONDITION:          opCalloutCondition();      continue;
                case OPCode.DYNAMIC_CALLOUT:            opDynamicCallout();        continue;
                case OPCode.ACCEPT:             if (opAccept()) return finish();   continue;
                case OPCode.PRUNE:                      opPrune();                  continue;
                case OPCode.SKIP:                       opSkip();                   continue;
                case OPCode.THEN:                       opThen();                   continue;
                case OPCode.COMMIT:                     opCommit();                 continue;
                case OPCode.MARK:                       opMark();                   continue;

                case OPCode.STATE_CHECK_ANYCHAR_STAR:   if (USE_CEC) {opStateCheckAnyCharStar(); break;}
                case OPCode.STATE_CHECK_ANYCHAR_ML_STAR:if (USE_CEC) {opStateCheckAnyCharMLStar();break;}
                case OPCode.STATE_CHECK_PUSH:           if (USE_CEC) {opStateCheckPush();        continue;}
                case OPCode.STATE_CHECK_PUSH_OR_JUMP:   if (USE_CEC) {opStateCheckPushOrJump();  continue;}
                case OPCode.STATE_CHECK:                if (USE_CEC) {opStateCheck();            continue;}

                default:
                    throw new InternalException(ErrorMessages.UNDEFINED_BYTECODE);

            } // main switch
        } // main while
    }

    private void handleTimeout() throws InterruptedException {
        if (System.nanoTime() - startTime > timeout) throw TIMEOUT_EXCEPTION;
    }

    private final int executeSb(final boolean checkThreadInterrupt) throws InterruptedException {
        final int[] code = this.code;
        int interruptCheckCounter = 0;
        while (true) {
            if (interruptCheckCounter++ >= interruptCheckEvery) {
                if (timeout != -1) handleTimeout();
                handleInterrupted(checkThreadInterrupt);
                interruptCheckCounter = 0;
            }

            if (Config.DEBUG_MATCH) debugMatchLoop();

            sbegin = s;
            checkInstructionPointer();
            switch (code[ip++]) {
                case OPCode.END:    if (opEnd()) return finish();                  break;
                case OPCode.EXACT1:                     opExact1();                break;
                case OPCode.EXACT2:                     opExact2();                continue;
                case OPCode.EXACT3:                     opExact3();                continue;
                case OPCode.EXACT4:                     opExact4();                continue;
                case OPCode.EXACT5:                     opExact5();                continue;
                case OPCode.EXACTN:                     opExactN();                continue;

                case OPCode.EXACTMB2N1:                 opExactMB2N1();            break;
                case OPCode.EXACTMB2N2:                 opExactMB2N2();            continue;
                case OPCode.EXACTMB2N3:                 opExactMB2N3();            continue;
                case OPCode.EXACTMB2N:                  opExactMB2N();             continue;
                case OPCode.EXACTMB3N:                  opExactMB3N();             continue;
                case OPCode.EXACTMBN:                   opExactMBN();              continue;

                case OPCode.EXACT1_IC:                  opExact1IC();              break;
                case OPCode.EXACTN_IC:                  opExactNIC();              continue;

                case OPCode.CCLASS:                     opCClassSb();              break;
                case OPCode.CCLASS_MB:                  opCClassMBSb();            break;
                case OPCode.CCLASS_MIX:                 opCClassMIXSb();           break;
                case OPCode.CCLASS_NOT:                 opCClassNotSb();           break;
                case OPCode.CCLASS_MB_NOT:              opCClassMBNotSb();         break;
                case OPCode.CCLASS_MIX_NOT:             opCClassMIXNotSb();        break;

                case OPCode.ANYCHAR:                    opAnyCharSb();               break;
                case OPCode.ANYCHAR_ML:                 opAnyCharMLSb();             break;
                case OPCode.ANYCHAR_STAR:               opAnyCharStarSb();           break;
                case OPCode.ANYCHAR_ML_STAR:            opAnyCharMLStarSb();         break;
                case OPCode.ANYCHAR_STAR_PEEK_NEXT:     opAnyCharStarPeekNextSb();   break;
                case OPCode.ANYCHAR_ML_STAR_PEEK_NEXT:  opAnyCharMLStarPeekNextSb(); break;

                case OPCode.WORD:                       opWordSb();                  break;
                case OPCode.NOT_WORD:                   opNotWordSb();               break;
                case OPCode.WORD_BOUND:                 opWordBoundSb();             continue;
                case OPCode.NOT_WORD_BOUND:             opNotWordBoundSb();          continue;
                case OPCode.GRAPHEME_BOUNDARY:          opGraphemeBoundary(false);   continue;
                case OPCode.NOT_GRAPHEME_BOUNDARY:      opGraphemeBoundary(true);    continue;
                case OPCode.SENTENCE_BOUNDARY:          opSentenceBoundary(false);   continue;
                case OPCode.NOT_SENTENCE_BOUNDARY:      opSentenceBoundary(true);    continue;
                case OPCode.WORD_BEGIN:                 opWordBeginSb();             continue;
                case OPCode.WORD_END:                   opWordEndSb();               continue;

                case OPCode.ASCII_WORD:                 opAsciiWord();             break;
                case OPCode.ASCII_NOT_WORD:             opNotAsciiWord();          break;
                case OPCode.ASCII_WORD_BOUND:           opAsciiWordBound();        break;
                case OPCode.ASCII_NOT_WORD_BOUND:       opNotAsciiWordBound();     continue;
                case OPCode.ASCII_WORD_BEGIN:           opAsciiWordBegin();        continue;
                case OPCode.ASCII_WORD_END:             opAsciiWordEnd();          continue;

                case OPCode.BEGIN_BUF:                  opBeginBuf();              continue;
                case OPCode.END_BUF:                    opEndBuf();                continue;
                case OPCode.BEGIN_LINE:                 opBeginLineSb();           continue;
                case OPCode.END_LINE:                   opEndLineSb();             continue;
                case OPCode.SEMI_END_BUF:               opSemiEndBuf();            continue;
                case OPCode.BEGIN_POSITION:             opBeginPosition();         continue;

                case OPCode.MEMORY_START_PUSH:          opMemoryStartPush();       continue;
                case OPCode.MEMORY_START:               opMemoryStart();           continue;
                case OPCode.MEMORY_END_PUSH:            opMemoryEndPush();         continue;
                case OPCode.MEMORY_END:                 opMemoryEnd();             continue;
                case OPCode.KEEP:                       opKeep();                  continue;
                case OPCode.MEMORY_END_PUSH_REC:        opMemoryEndPushRec();      continue;
                case OPCode.MEMORY_END_REC:             opMemoryEndRec();          continue;

                case OPCode.BACKREF1:                   opBackRef1();              continue;
                case OPCode.BACKREF2:                   opBackRef2();              continue;
                case OPCode.BACKREFN:                   opBackRefN();              continue;
                case OPCode.BACKREFN_IC:                opBackRefNIC();            continue;
                case OPCode.BACKREF_MULTI:              opBackRefMulti();          continue;
                case OPCode.BACKREF_MULTI_IC:           opBackRefMultiIC();        continue;
                case OPCode.BACKREF_WITH_LEVEL:         opBackRefAtLevel();        continue;

                case OPCode.SET_OPTION_PUSH:            opSetOptionPush();         continue;
                case OPCode.SET_OPTION:                 opSetOption();             continue;

                case OPCode.NULL_CHECK_START:           opNullCheckStart();        continue;
                case OPCode.NULL_CHECK_END:             opNullCheckEnd();          continue;
                case OPCode.NULL_CHECK_END_MEMST:       opNullCheckEndMemST();     continue;
                case OPCode.NULL_CHECK_END_MEMST_PUSH:  opNullCheckEndMemSTPush(); continue;

                case OPCode.JUMP:                       opJump();                  continue;
                case OPCode.PUSH:                       opPush();                  continue;

                case OPCode.POP:                        opPop();                   continue;
                case OPCode.PUSH_OR_JUMP_EXACT1:        opPushOrJumpExact1();      continue;
                case OPCode.PUSH_IF_PEEK_NEXT:          opPushIfPeekNext();        continue;

                case OPCode.REPEAT:                     opRepeat();                continue;
                case OPCode.REPEAT_NG:                  opRepeatNG();              continue;
                case OPCode.REPEAT_INC:                 opRepeatInc();             continue;
                case OPCode.REPEAT_INC_SG:              opRepeatIncSG();           continue;
                case OPCode.REPEAT_INC_NG:              opRepeatIncNG();           continue;
                case OPCode.REPEAT_INC_NG_SG:           opRepeatIncNGSG();         continue;

                case OPCode.PUSH_POS:                   opPushPos();               continue;
                case OPCode.POP_POS:                    opPopPos();                continue;
                case OPCode.PUSH_POS_NOT:               opPushPosNot();            continue;
                case OPCode.POP_POS_NOT:                opPopPosNot();             continue;
                case OPCode.FAIL_POS:                   opFailPos();               continue;
                case OPCode.PUSH_STOP_BT:               opPushStopBT();            continue;
                case OPCode.POP_STOP_BT:                opPopStopBT();             continue;

                case OPCode.LOOK_BEHIND:                opLookBehindSb();          continue;
                case OPCode.PUSH_LOOK_BEHIND_NOT:       opPushLookBehindNot();     continue;
                case OPCode.FAIL_LOOK_BEHIND_NOT:       opFailLookBehindNot();     continue;

                case OPCode.PUSH_ABSENT_POS:            opPushAbsentPos();         continue;
                case OPCode.ABSENT:                     opAbsent();                continue;
                case OPCode.ABSENT_END:                 opAbsentEnd();             continue;

                case OPCode.CALL:                       opCall();                  continue;
                case OPCode.RETURN:                     opReturn();                continue;
                case OPCode.CONDITION:                  opCondition();             continue;
                case OPCode.RECURSION_CONDITION:        opRecursionCondition();    continue;
                case OPCode.CHECK_POS_END:              opCheckPosEnd();           continue;
                case OPCode.CHECK_LOOK_BEHIND_END:      opCheckLookBehindEnd();    continue;
                case OPCode.FINISH:                     return finish();
                case OPCode.FAIL:                       opFail();                  continue;
                case OPCode.CALLOUT:                    opCallout();               continue;
                case OPCode.CALLOUT_CONDITION:          opCalloutCondition();      continue;
                case OPCode.DYNAMIC_CALLOUT:            opDynamicCallout();        continue;
                case OPCode.ACCEPT:             if (opAccept()) return finish();   continue;
                case OPCode.PRUNE:                      opPrune();                  continue;
                case OPCode.SKIP:                       opSkip();                   continue;
                case OPCode.THEN:                       opThen();                   continue;
                case OPCode.COMMIT:                     opCommit();                 continue;
                case OPCode.MARK:                       opMark();                   continue;

                case OPCode.EXACT1_IC_SB:               opExact1ICSb();            break;
                case OPCode.EXACTN_IC_SB:               opExactNICSb();            continue;

                case OPCode.STATE_CHECK_ANYCHAR_STAR:   if (USE_CEC) {opStateCheckAnyCharStarSb(); break;}
                case OPCode.STATE_CHECK_ANYCHAR_ML_STAR:if (USE_CEC) {opStateCheckAnyCharMLStarSb();break;}
                case OPCode.STATE_CHECK_PUSH:           if (USE_CEC) {opStateCheckPush();        continue;}
                case OPCode.STATE_CHECK_PUSH_OR_JUMP:   if (USE_CEC) {opStateCheckPushOrJump();  continue;}
                case OPCode.STATE_CHECK:                if (USE_CEC) {opStateCheck();            continue;}

                default:
                    throw new InternalException(ErrorMessages.UNDEFINED_BYTECODE);

            } // main switch
        } // main while
    }

    private void handleInterrupted(final boolean checkThreadInterrupt) throws InterruptedException {
        if (interrupted || (checkThreadInterrupt && Thread.currentThread().isInterrupted())) {
            Thread.interrupted();
            throw INTERRUPTED_EXCEPTION;
        }
        interruptCheckEvery = Math.min(interruptCheckEvery << 1, MAX_INTERRUPT_CHECK_EVERY);
    }

    private void checkInstructionPointer() {
        if (ip < 0 || ip >= code.length) {
            throw new InternalException("invalid bytecode position " + ip
                    + " for program length " + code.length + " at input position " + (s - str));
        }
    }

    private boolean opEnd() {
        int n = s - sstart;

        if (n > bestLen) {
            if (Config.USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE) {
                if (isFindLongest(regex.options | msaOptions)) {
                    if (n > msaBestLen) {
                        msaBestLen = n;
                        msaBestS = sstart;
                    } else {
                        // goto end_best_len;
                        return endBestLength();
                    }
                }
            } // USE_FIND_LONGEST_SEARCH_ALL_OF_RANGE

            bestLen = n;
            final Region region = msaRegion;
            if (region != null) {
                // USE_POSIX_REGION_OPTION ... else ...
                region.setBeg(0, msaBegin = ((pkeep > s) ? s : pkeep) - str);
                region.setEnd(0, msaEnd   = s      - str);
                for (int i = 1; i <= regex.numMem; i++) {
                    int me = repeatStk[memEndStk + i];
                    if (me != INVALID_INDEX) {
                        int ms = repeatStk[memStartStk + i];
                        region.setBeg(i, (bsAt(regex.btMemStart, i) ? stack[ms].getMemPStr() : ms) - str);
                        region.setEnd(i, (bsAt(regex.btMemEnd, i) ? stack[me].getMemPStr() : me) - str);
                    } else {
                        region.setBeg(i, Region.REGION_NOTPOS);
                        region.setEnd(i, Region.REGION_NOTPOS);
                    }
                }

                if (Config.USE_CAPTURE_HISTORY && regex.captureHistory != 0) checkCaptureHistory(region);
            } else {
                msaBegin = ((pkeep > s) ? s : pkeep) - str;
                msaEnd   = s      - str;
            }
        } else {
            Region region = msaRegion;
            if (region != null) {
                region.clear();
            } else {
                msaBegin = msaEnd = 0;
            }
        }
        // end_best_len:
        /* default behavior: return first-matching result. */
        return endBestLength();
    }

    private boolean endBestLength() {
        int effectiveOptions = regex.options | msaOptions;
        if (isFindCondition(effectiveOptions)) {
            if (isFindNotEmpty(effectiveOptions) && s == sstart) {
                bestLen = -1;
                {opFail(); return false;} /* for retry */
            }
            if (isFindLongest(effectiveOptions) && s < range) {
                {opFail(); return false;} /* for retry */
            }
        }
        // goto finish;
        return true;
    }

    private void opExact1() {
        if (s >= range || code[ip] != bytes[s]) {
            opFail();
        } else {
            ip++; s++;
            sprev = sbegin; // break;
        }
    }

    private void opExact2() {
        if (s + 2 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s] ) {
            opFail();
        } else {
            sprev = s;
            ip++; s++;
        }
    }

    private void opExact3() {
        if (s + 3 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s]) {
            opFail();
        } else {
            sprev = s;
            ip++; s++;
        }
    }

    private void opExact4() {
        if (s + 4 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s]) {
            opFail();
        } else {
            sprev = s;
            ip++; s++;
        }
    }

    private void opExact5() {
        if (s + 5 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s]) {
            opFail();
        } else {
            sprev = s;
            ip++; s++;
        }
    }

    private void opExactN() {
        int tlen = code[ip++];
        if (s + tlen > range) {opFail(); return;}

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];

            while (tlen-- > 0) if (bs[ps++] != bytes[s++]) {opFail(); return;}

        } else {
            while (tlen-- > 0) if (code[ip++] != bytes[s++]) {opFail(); return;}
        }
        sprev = s - 1;
    }

    private void opExactMB2N1() {
        if (s + 2 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s]) {
            opFail();
        } else {
            ip++; s++;
            sprev = sbegin; // break;
        }
    }

    private void opExactMB2N2() {
        if (s + 4 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s]) {opFail(); return;}
        ip++; s++;
        sprev = s;
        if (code[ip] != bytes[s] || code[++ip] != bytes[++s]) {opFail(); return;}
        ip++; s++;
   }

    private void opExactMB2N3() {
        if (s + 6 > range || code[ip] != bytes[s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s]) {opFail(); return;}
        ip++; s++;
        sprev = s;
        if (code[ip] != bytes[s] || code[++ip] != bytes[++s]) {opFail(); return;}
        ip++; s++;
    }

    private void opExactMB2N() {
        int tlen = code[ip++];
        if (s + tlen * 2 > range) {opFail(); return;}

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];

            while(tlen-- > 0) {
                if (bs[ps] != bytes[s] || bs[++ps] != bytes[++s]) {opFail(); return;}
                ps++; s++;
            }
        } else {
            while(tlen-- > 0) {
                if (code[ip] != bytes[s] || code[++ip] != bytes[++s]) {opFail(); return;}
                ip++; s++;
            }
        }
        sprev = s - 2;
    }

    private void opExactMB3N() {
        int tlen = code[ip++];
        if (s + tlen * 3 > range) {opFail(); return;}

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];

            while (tlen-- > 0) {
                if (bs[ps] != bytes[s] || bs[++ps] != bytes[++s] || bs[++ps] != bytes[++s]) {opFail(); return;}
                ps++; s++;
            }
        } else {
            while (tlen-- > 0) {
                if (code[ip] != bytes[s] || code[++ip] != bytes[++s] || code[++ip] != bytes[++s]) {opFail(); return;}
                ip++; s++;
            }
        }

        sprev = s - 3;
    }

    private void opExactMBN() {
        int tlen = code[ip++];   /* mb-len */
        int tlen2= code[ip++];   /* string len */

        tlen2 *= tlen;
        if (s + tlen2 > range) {opFail(); return;}

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];

            while (tlen2-- > 0) {
                if (bs[ps] != bytes[s]) {opFail(); return;}
                ps++; s++;
            }
        } else {
            while (tlen2-- > 0) {
                if (code[ip] != bytes[s]) {opFail(); return;}
                ip++; s++;
            }
        }

        sprev = s - tlen;
    }

    private void opExact1IC() {
        if (s >= range) {opFail(); return;}
        if (perlAsciiStrictRejectsFold(s, code[ip])) {opFail(); return;}

        byte[]lowbuf = cfbuf();

        value = s;
        int len = enc.mbcCaseFold(currentCaseFoldFlag(), bytes, this, end, lowbuf);
        s = value;

        if (s > range) {opFail(); return;}

        int q = 0;
        while (len-- > 0) {
            if (code[ip] != lowbuf[q]) {opFail(); return;}
            ip++; q++;
        }
        sprev = sbegin; // break;
    }

    private void opExact1ICSb() {
        if (s >= range || code[ip] != enc.toLowerCaseTable()[bytes[s++] & 0xff]) {opFail(); return;}
        ip++;
        sprev = sbegin; // break;
    }

    private void opExactNIC() {
        int tlen = code[ip++];
        byte[]lowbuf = cfbuf();

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];
            int endp = ps + tlen;

            while (ps < endp) {
                sprev = s;
                if (s >= range) {opFail(); return;}
                if (perlAsciiStrictRejectsFold(s, bs[ps])) {opFail(); return;}

                value = s;
                int len = enc.mbcCaseFold(currentCaseFoldFlag(), bytes, this, end, lowbuf);
                s = value;

                if (s > range) {opFail(); return;}
                int q = 0;
                while (len-- > 0) {
                    if (bs[ps] != lowbuf[q]) {opFail(); return;}
                    ps++; q++;
                }
            }
        } else {
            int endp = ip + tlen;

            while (ip < endp) {
                sprev = s;
                if (s >= range) {opFail(); return;}
                if (perlAsciiStrictRejectsFold(s, code[ip])) {opFail(); return;}

                value = s;
                int len = enc.mbcCaseFold(currentCaseFoldFlag(), bytes, this, end, lowbuf);
                s = value;

                if (s > range) {opFail(); return;}
                int q = 0;
                while (len-- > 0) {
                    if (code[ip] != lowbuf[q]) {opFail(); return;}
                    ip++; q++;
                }
            }
        }

    }

    private boolean perlAsciiStrictRejectsFold(int inputPosition, int targetByte) {
        if (!Option.isPerlAsciiStrict(currentRegexOptions)) return false;
        int inputCodePoint = enc.mbcToCode(bytes, inputPosition, end);
        return !Encoding.isAscii(inputCodePoint) && (targetByte & 0xff) < 0x80;
    }

    private void opExactNICSb() {
        int tlen = code[ip++];
        if (s + tlen > range) {opFail(); return;}
        byte[]toLowerTable = enc.toLowerCaseTable();

        if (Config.USE_STRING_TEMPLATES) {
            byte[]bs = regex.templates[code[ip++]];
            int ps = code[ip++];
            while (tlen-- > 0) if (bs[ps++] != toLowerTable[bytes[s++] & 0xff]) {opFail(); return;}
        } else {
            while (tlen-- > 0) if (code[ip++] != toLowerTable[bytes[s++] & 0xff]) {opFail(); return;}
        }
        sprev = s - 1;
    }

    private void opCondition() {
        int mem = code[ip++];
        int addr = code[ip++];
        if (mem > regex.numMem || repeatStk[memEndStk + mem] == INVALID_INDEX || repeatStk[memStartStk + mem] == INVALID_INDEX) {
            ip += addr;
        }
    }

    private void opRecursionCondition() {
        int groupNum = code[ip++];
        int addr = code[ip++];
        if (!isInsideSubexpCall(groupNum)) ip += addr;
    }

    private void opCheckPosEnd() {
        if (s != savedPosition(POS)) opFail();
    }

    private void opCheckLookBehindEnd() {
        if (s != savedPosition(LOOK_BEHIND_NOT)) opFail();
    }

    private boolean isInBitSet() {
        int c = bytes[s] & 0xff;
        return ((code[ip + (c >>> BitSet.ROOM_SHIFT)] & (1 << c)) != 0);
    }

    private void opCClass() {
        if (s >= range || !isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        s += enc.length(bytes, s, end); /* OP_CCLASS can match mb-code. \D, \S */
        if (s > end) s = end;
        sprev = sbegin; // break;
    }

    private void opCClassSb() {
        if (s >= range || !isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        s++;
        sprev = sbegin; // break;
    }

    private boolean isInClassMB() {
        int tlen = code[ip++];
        if (s >= range) return false;
        int mbLen = enc.length(bytes, s, end);
        if (s + mbLen > range) return false;
        int ss = s;
        s += mbLen;
        int c = enc.mbcToCode(bytes, ss, s);
        if (!CodeRange.isInCodeRange(code, ip, c)) return false;
        ip += tlen;
        return true;
    }

    private void opCClassMB() {
        // beyond string check
        if (s >= range || !enc.isMbcHead(bytes, s, end)) {opFail(); return;}
        if (!isInClassMB()) {opFail(); return;} // not!!!
        sprev = sbegin; // break;
    }

    private void opCClassMBSb() {
        opFail();
    }

    private void opCClassMIX() {
        if (s >= range) {opFail(); return;}
        if (enc.isMbcHead(bytes, s, end)) {
            ip += BitSet.BITSET_SIZE;
            if (!isInClassMB()) {opFail(); return;}
        } else {
            if (!isInBitSet()) {opFail(); return;}
            ip += BitSet.BITSET_SIZE;
            int tlen = code[ip++]; // by code range length
            ip += tlen;
            s++;
        }
        sprev = sbegin; // break;
    }

    private void opCClassMIXSb() {
        if (s >= range || !isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        int tlen = code[ip++];
        ip += tlen;
        s++;
        sprev = sbegin; // break;
    }

    private void opCClassNot() {
        if (s >= range || isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        s += enc.length(bytes, s, end);
        if (s > end) s = end;
        sprev = sbegin; // break;
    }

    private void opCClassNotSb() {
        if (s >= range || isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        s++;
        sprev = sbegin; // break;
    }

    private boolean isNotInClassMB() {
        int tlen = code[ip++];
        int mbLen = enc.length(bytes, s, end);

        if (!(s + mbLen <= range)) {
            if (s >= range) return false;
            s = end;
            ip += tlen;
            return true;
        }

        int ss = s;
        s += mbLen;
        int c = enc.mbcToCode(bytes, ss, s);

        if (CodeRange.isInCodeRange(code, ip, c)) return false;
        ip += tlen;
        return true;
    }

    private void opCClassMBNot() {
        if (s >= range) {opFail(); return;}
        if (!enc.isMbcHead(bytes, s, end)) {
            s++;
            int tlen = code[ip++];
            ip += tlen;
            sprev = sbegin; // break;
            return;
        }
        if (!isNotInClassMB()) {opFail(); return;}
        sprev = sbegin; // break;
    }

    private void opCClassMBNotSb() {
        if (s >= range) {opFail(); return;}
        s++;
        int tlen = code[ip++];
        ip += tlen;
        sprev = sbegin; // break;
    }

    private void opCClassMIXNot() {
        if (s >= range) {opFail(); return;}
        if (enc.isMbcHead(bytes, s, end)) {
            ip += BitSet.BITSET_SIZE;
            if (!isNotInClassMB()) {opFail(); return;}
        } else {
            if (isInBitSet()) {opFail(); return;}
            ip += BitSet.BITSET_SIZE;
            int tlen = code[ip++];
            ip += tlen;
            s++;
        }
        sprev = sbegin; // break;
    }

    private void opCClassMIXNotSb() {
        if (s >= range || isInBitSet()) {opFail(); return;}
        ip += BitSet.BITSET_SIZE;
        s++;
        int tlen = code[ip++];
        ip += tlen;
        sprev = sbegin; // break;
    }

    private void opAnyChar() {
        final int n;
        if (s >= range || s + (n = enc.length(bytes, s, end)) > range || enc.isNewLine(bytes, s, end)) {opFail(); return;}
        s += n;
        sprev = sbegin; // break;
    }

    private void opAnyCharSb() {
        if (s >= range || bytes[s] == Encoding.NEW_LINE) {opFail(); return;}
        s++;
        sprev = sbegin; // break;
    }

    private void opAnyCharML() {
        if (s >= range) {opFail(); return;}
        int n = enc.length(bytes, s, end);
        if (s + n > range) {opFail(); return;}
        s += n;
        sprev = sbegin; // break;
    }

    private void opAnyCharMLSb() {
        if (s >= range) {opFail(); return;}
        s++;
        sprev = sbegin; // break;
    }

    private void opAnyCharStar() {
        final byte[]bytes = this.bytes;
        while (s < range) {
            pushAlt(ip, s, sprev, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range) {opFail(); return;}
            if (enc.isNewLine(bytes, s, end)) {opFail(); return;}
            sprev = s;
            s += n;
        }
    }

    private void opAnyCharStarSb() {
        final byte[]bytes = this.bytes;
        while (s < range) {
            pushAlt(ip, s, sprev, pkeep);
            if (bytes[s] == Encoding.NEW_LINE) {opFail(); return;}
            sprev = s;
            s++;
        }
    }

    private void opAnyCharMLStar() {
        final byte[]bytes = this.bytes;
        while (s < range) {
            pushAlt(ip, s, sprev, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range) {opFail(); return;}
            sprev = s;
            s += n;
        }
    }

    private void opAnyCharMLStarSb() {
        while (s < range) {
            pushAlt(ip, s, sprev, pkeep);
            sprev = s;
            s++;
        }
    }

    private void opAnyCharStarPeekNext() {
        final byte c = (byte)code[ip];
        final byte[]bytes = this.bytes;

        while (s < range) {
            if (c == bytes[s]) pushAlt(ip + 1, s, sprev, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range || enc.isNewLine(bytes, s, end)) {opFail(); return;}
            sprev = s;
            s += n;
        }
        ip++;
        sprev = sbegin; // break;
    }

    private void opAnyCharStarPeekNextSb() {
        final byte c = (byte)code[ip];
        final byte[]bytes = this.bytes;

        while (s < range) {
            byte b = bytes[s];
            if (c == b) pushAlt(ip + 1, s, sprev, pkeep);
            if (b == Encoding.NEW_LINE) {opFail(); return;}
            sprev = s;
            s++;
        }
        ip++;
        sprev = sbegin; // break;
    }

    private void opAnyCharMLStarPeekNext() {
        final byte c = (byte)code[ip];
        final byte[]bytes = this.bytes;

        while (s < range) {
            if (c == bytes[s]) pushAlt(ip + 1, s, sprev, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range) {opFail(); return;}
            sprev = s;
            s += n;
        }
        ip++;
        sprev = sbegin; // break;
    }

    private void opAnyCharMLStarPeekNextSb() {
        final byte c = (byte)code[ip];
        final byte[]bytes = this.bytes;

        while (s < range) {
            if (c == bytes[s]) pushAlt(ip + 1, s, sprev, pkeep);
            sprev = s;
            s++;
        }
        ip++;
        sprev = sbegin; // break;
    }

    // CEC
    private void opStateCheckAnyCharStar() {
        int mem = code[ip++];
        final byte[]bytes = this.bytes;

        while (s < range) {
            if (stateCheckVal(s, mem)) {opFail(); return;}
            pushAltWithStateCheck(ip, s, sprev, mem, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range || enc.isNewLine(bytes, s, end)) {opFail(); return;}
            sprev = s;
            s += n;
        }
    }

    private void opStateCheckAnyCharStarSb() {
        int mem = code[ip++];
        final byte[]bytes = this.bytes;

        while (s < range) {
            if (stateCheckVal(s, mem)) {opFail(); return;}
            pushAltWithStateCheck(ip, s, sprev, mem, pkeep);
            if (bytes[s] == Encoding.NEW_LINE) {opFail(); return;}
            sprev = s;
            s++;
        }
    }

    // CEC
    private void opStateCheckAnyCharMLStar() {
        int mem = code[ip++];

        final byte[]bytes = this.bytes;
        while (s < range) {
            if (stateCheckVal(s, mem)) {opFail(); return;}
            pushAltWithStateCheck(ip, s, sprev, mem, pkeep);
            int n = enc.length(bytes, s, end);
            if (s + n > range) {opFail(); return;}
            sprev = s;
            s += n;
        }
    }

    private void opStateCheckAnyCharMLStarSb() {
        int mem = code[ip++];

        while (s < range) {
            if (stateCheckVal(s, mem)) {opFail(); return;}
            pushAltWithStateCheck(ip, s, sprev, mem, pkeep);
            sprev = s;
            s++;
        }
    }

    private void opWord() {
        if (s >= range || !enc.isMbcWord(bytes, s, end)) {opFail(); return;}
        s += enc.length(bytes, s, end);
        sprev = sbegin; // break;
    }

    private void opWordSb() {
        if (s >= range || !enc.isWord(bytes[s] & 0xff)) {opFail(); return;}
        s++;
        sprev = sbegin; // break;
    }

    private void opAsciiWord() {
        if (s >= range || !isMbcAsciiWord(enc, bytes, s, end)) {opFail(); return;}
        s += enc.length(bytes, s, end);
        sprev = sbegin; // break;
    }

    private void opNotWord() {
        if (s >= range || enc.isMbcWord(bytes, s, end)) {opFail(); return;}
        s += enc.length(bytes, s, end);
        sprev = sbegin; // break;
    }

    private void opNotWordSb() {
        if (s >= range || enc.isWord(bytes[s] & 0xff)) {opFail(); return;}
        s++;
        sprev = sbegin; // break;
    }

    private void opNotAsciiWord() {
        if (s >= range || isMbcAsciiWord(enc, bytes, s, end)) {opFail(); return;}
        s += enc.length(bytes, s, end);
        sprev = sbegin; // break;
    }

    private void opWordBound() {
        if (s == str) {
            if (s >= range || !enc.isMbcWord(bytes, s, end)) {opFail(); return;}
        } else if (s == end) {
            if (sprev >= end || !enc.isMbcWord(bytes, sprev, end)) {opFail(); return;}
        } else {
            if (enc.isMbcWord(bytes, s, end) == enc.isMbcWord(bytes, sprev, end)) {opFail(); return;}
        }
    }

    private void opGraphemeBoundary(boolean negated) {
        if (isGraphemeBoundary() == negated) opFail();
    }

    private void opSentenceBoundary(boolean negated) {
        if (isSentenceBoundary() == negated) opFail();
    }

    private boolean isSentenceBoundary() {
        if (s <= str || s >= end) return true; // SB1, SB2

        int leftPosition = enc.prevCharHead(bytes, str, s, end);
        byte left = sentencePropertyAt(leftPosition);
        byte right = sentencePropertyAt(s);

        if (left == SentenceBreakData.CR && right == SentenceBreakData.LF) return false; // SB3
        if (isSentenceParagraphSeparator(left)) return true; // SB4
        if (isSentenceIgnored(right)) return false; // SB5

        int[] position = {leftPosition};
        byte previous = previousSentenceProperty(position);

        if (previous == SentenceBreakData.ATERM && right == SentenceBreakData.NUMERIC) return false; // SB6
        if (right == SentenceBreakData.UPPER && previous == SentenceBreakData.ATERM) {
            byte beforeTerm = previousSentenceProperty(position);
            if (beforeTerm == SentenceBreakData.UPPER || beforeTerm == SentenceBreakData.LOWER) return false; // SB7
        }

        if (sentenceATermBeforeCloseAndSpace(positionFor(leftPosition))
                && sentenceLowerAhead(s)) return false; // SB8

        if ((right == SentenceBreakData.SCONTINUE
                    || right == SentenceBreakData.STERM
                    || right == SentenceBreakData.ATERM)
                && sentenceTerminalBefore(positionFor(leftPosition), true)) return false; // SB8a

        if ((right == SentenceBreakData.CLOSE
                    || right == SentenceBreakData.SP
                    || isSentenceParagraphSeparator(right))
                && sentenceTerminalBeforeClose(positionFor(leftPosition))) return false; // SB9

        if ((right == SentenceBreakData.SP || isSentenceParagraphSeparator(right))
                && sentenceTerminalBefore(positionFor(leftPosition), true)) return false; // SB10

        // Extend and Format are ignored except after a paragraph separator. A
        // separator followed by ignored characters has already broken at SB4;
        // do not let SB11 scan back through it to an earlier terminal.
        if (isSentenceIgnored(left) && isSentenceParagraphSeparator(previous)) return false;

        if (sentenceBoundaryAfterTerminal(positionFor(leftPosition))) return true; // SB11

        return false; // SB998
    }

    private int[] positionFor(int position) {
        return new int[] {position};
    }

    private byte sentencePropertyAt(int position) {
        return SentenceBreakData.propertyOf(enc.mbcToCode(bytes, position, end));
    }

    private byte previousSentenceProperty(int[] position) {
        while (position[0] >= str) {
            byte property = sentencePropertyAt(position[0]);
            if (position[0] == str) {
                position[0] = -1;
            } else {
                position[0] = enc.prevCharHead(bytes, str, position[0], end);
            }
            if (!isSentenceIgnored(property)) return property;
        }
        return SentenceBreakData.OTHER;
    }

    private boolean sentenceTerminalBefore(int[] position, boolean skipSpaces) {
        byte property = previousSentenceProperty(position);
        if (skipSpaces) {
            while (property == SentenceBreakData.SP) property = previousSentenceProperty(position);
        }
        while (property == SentenceBreakData.CLOSE) property = previousSentenceProperty(position);
        return isSentenceTerminal(property);
    }

    private boolean sentenceATermBeforeCloseAndSpace(int[] position) {
        byte property = previousSentenceProperty(position);
        while (property == SentenceBreakData.SP) property = previousSentenceProperty(position);
        while (property == SentenceBreakData.CLOSE) property = previousSentenceProperty(position);
        return property == SentenceBreakData.ATERM;
    }

    private boolean sentenceLowerAhead(int position) {
        while (position < end) {
            byte property = sentencePropertyAt(position);
            if (property == SentenceBreakData.LOWER) return true;
            if (property != SentenceBreakData.CLOSE
                    && property != SentenceBreakData.SP
                    && !isSentenceIgnored(property)) return false;
            position += enc.length(bytes, position, end);
        }
        return false;
    }

    private boolean sentenceTerminalBeforeClose(int[] position) {
        byte property = previousSentenceProperty(position);
        while (property == SentenceBreakData.CLOSE) property = previousSentenceProperty(position);
        return isSentenceTerminal(property);
    }

    private boolean sentenceBoundaryAfterTerminal(int[] position) {
        byte property = previousSentenceProperty(position);
        if (isSentenceParagraphSeparator(property)) property = previousSentenceProperty(position);
        while (property == SentenceBreakData.SP) property = previousSentenceProperty(position);
        while (property == SentenceBreakData.CLOSE) property = previousSentenceProperty(position);
        return isSentenceTerminal(property);
    }

    private boolean isSentenceIgnored(byte property) {
        return property == SentenceBreakData.EXTEND || property == SentenceBreakData.FORMAT;
    }

    private boolean isSentenceParagraphSeparator(byte property) {
        return property == SentenceBreakData.SEP
                || property == SentenceBreakData.CR
                || property == SentenceBreakData.LF;
    }

    private boolean isSentenceTerminal(byte property) {
        return property == SentenceBreakData.ATERM || property == SentenceBreakData.STERM;
    }

    private boolean isGraphemeBoundary() {
        if (s <= str || s >= end) return true; // GB1, GB2

        int leftPosition = enc.prevCharHead(bytes, str, s, end);
        int left = enc.mbcToCode(bytes, leftPosition, end);
        int right = enc.mbcToCode(bytes, s, end);

        if (isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CR)
                && isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LF)) return false; // GB3
        if (isGcbControl(left) || isGcbControl(right)) return true; // GB4, GB5

        if (isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_L)
                && (isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_L)
                        || isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_V)
                        || isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LV)
                        || isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LVT))) return false; // GB6
        if ((isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LV)
                    || isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_V))
                && (isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_V)
                    || isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_T))) return false; // GB7
        if ((isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LVT)
                    || isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_T))
                && isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_T)) return false; // GB8

        if (isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_EXTEND)
                || isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_ZWJ)) return false; // GB9
        if (isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_SPACINGMARK)) return false; // GB9a
        if (isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_PREPEND)) return false; // GB9b
        if (isIndicConjunctBoundary(leftPosition, right)) return false; // GB9c
        if (isExtendedPictographicBoundary(leftPosition, right)) return false; // GB11
        if (isRegionalIndicatorBoundary(leftPosition, left, right)) return false; // GB12, GB13

        return true; // GB999
    }

    private boolean isGcbControl(int codePoint) {
        return isGcb(codePoint, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CR)
                || isGcb(codePoint, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_LF)
                || isGcb(codePoint, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_CONTROL);
    }

    private boolean isGcb(int codePoint, UnicodeCodeRange range) {
        return enc.isCodeCType(codePoint, range.getCType());
    }

    private boolean isIndicConjunctBoundary(int leftPosition, int right) {
        if (!isGcb(right, UnicodeCodeRange.INCBCONSONANT)) return false;
        boolean sawLinker = false;
        int position = leftPosition;
        while (position >= str) {
            int codePoint = enc.mbcToCode(bytes, position, end);
            if (isGcb(codePoint, UnicodeCodeRange.INCBLINKER)) {
                sawLinker = true;
            } else if (!isGcb(codePoint, UnicodeCodeRange.INCBEXTEND)) {
                return sawLinker && isGcb(codePoint, UnicodeCodeRange.INCBCONSONANT);
            }
            if (position == str) break;
            position = enc.prevCharHead(bytes, str, position, end);
        }
        return false;
    }

    private boolean isExtendedPictographicBoundary(int leftPosition, int right) {
        if (!isGcb(right, UnicodeCodeRange.EXTENDEDPICTOGRAPHIC)
                || !isGcb(
                        enc.mbcToCode(bytes, leftPosition, end),
                        UnicodeCodeRange.GRAPHEMECLUSTERBREAK_ZWJ)) return false;
        if (leftPosition == str) return false;
        int position = enc.prevCharHead(bytes, str, leftPosition, end);
        while (position >= str) {
            int codePoint = enc.mbcToCode(bytes, position, end);
            if (!isGcb(codePoint, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_EXTEND)) {
                return isGcb(codePoint, UnicodeCodeRange.EXTENDEDPICTOGRAPHIC);
            }
            if (position == str) break;
            position = enc.prevCharHead(bytes, str, position, end);
        }
        return false;
    }

    private boolean isRegionalIndicatorBoundary(int leftPosition, int left, int right) {
        if (!isGcb(left, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_REGIONALINDICATOR)
                || !isGcb(right, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_REGIONALINDICATOR)) {
            return false;
        }
        int count = 1;
        int position = leftPosition;
        while (position > str) {
            position = enc.prevCharHead(bytes, str, position, end);
            int codePoint = enc.mbcToCode(bytes, position, end);
            if (!isGcb(codePoint, UnicodeCodeRange.GRAPHEMECLUSTERBREAK_REGIONALINDICATOR)) break;
            count++;
        }
        return (count & 1) == 1;
    }

    private void opWordBoundSb() {
        if (s == str) {
            if (s >= range || !enc.isWord(bytes[s] & 0xff)) {opFail(); return;}
        } else if (s == end) {
            if (sprev >= end || !enc.isWord(bytes[sprev] & 0xff)) {opFail(); return;}
        } else {
            if (enc.isWord(bytes[s] & 0xff) == enc.isWord(bytes[sprev] & 0xff)) {opFail(); return;}
        }
    }

    private void opAsciiWordBound() {
        if (s == str) {
            if (s >= range || !isMbcAsciiWord(enc, bytes, s, end)) {opFail(); return;}
        } else if (s == end) {
            if (sprev >= end || !isMbcAsciiWord(enc, bytes, sprev, end)) {opFail(); return;}
        } else {
            if (isMbcAsciiWord(enc, bytes, s, end) == isMbcAsciiWord(enc, bytes, sprev, end)) {opFail(); return;}
        }
    }

    private void opNotWordBound() {
        if (s == str) {
            if (s < range && enc.isMbcWord(bytes, s, end)) {opFail(); return;}
        } else if (s == end) {
            if (sprev < end && enc.isMbcWord(bytes, sprev, end)) {opFail(); return;}
        } else {
            if (enc.isMbcWord(bytes, s, end) != enc.isMbcWord(bytes, sprev, end)) {opFail(); return;}
        }
    }

    private void opNotWordBoundSb() {
        if (s == str) {
            if (s < range && enc.isWord(bytes[s] & 0xff)) {opFail(); return;}
        } else if (s == end) {
            if (sprev < end && enc.isWord(bytes[sprev] & 0xff)) {opFail(); return;}
        } else {
            if (enc.isWord(bytes[s] & 0xff) != enc.isWord(bytes[sprev] & 0xff)) {opFail(); return;}
        }
    }

    private void opNotAsciiWordBound() {
        if (s == str) {
            if (s < range && isMbcAsciiWord(enc, bytes, s, end)) {opFail(); return;}
        } else if (s == end) {
            if (sprev < end && isMbcAsciiWord(enc, bytes, sprev, end)) {opFail(); return;}
        } else {
            if (isMbcAsciiWord(enc, bytes, s, end) != isMbcAsciiWord(enc, bytes, sprev, end)) {opFail(); return;}
        }
    }

    private void opWordBegin() {
        if (s < range && enc.isMbcWord(bytes, s, end)) {
            if (s == str || !enc.isMbcWord(bytes, sprev, end)) return;
        }
        opFail();
    }

    private void opWordBeginSb() {
        if (s < range && enc.isWord(bytes[s] & 0xff)) {
            if (s == str || !enc.isWord(bytes[sprev] & 0xff)) return;
        }
        opFail();
    }

    private void opAsciiWordBegin() {
        if (s < range && isMbcAsciiWord(enc, bytes, s, end)) {
            if (s == str || !isMbcAsciiWord(enc, bytes, sprev, end)) return;
        }
        opFail();
    }

    private void opWordEnd() {
        if (s != str && enc.isMbcWord(bytes, sprev, end)) {
            if (s == end || !enc.isMbcWord(bytes, s, end)) return;
        }
        opFail();
    }

    private void opWordEndSb() {
        if (s != str && enc.isWord(bytes[sprev] & 0xff)) {
            if (s == end || !enc.isWord(bytes[s] & 0xff)) return;
        }
        opFail();
    }

    private void opAsciiWordEnd() {
        if (s != str && isMbcAsciiWord(enc, bytes, sprev, end)) {
            if (s == end || !isMbcAsciiWord(enc, bytes, s, end)) return;
        }
        opFail();
    }

    private void opBeginBuf() {
        if (s != str) opFail();
    }

    private void opEndBuf() {
        if (s != end) opFail();
    }

    private void opBeginLine() {
        if (s == str) {
            if (isNotBol(msaOptions)) opFail();
            return;
        } else if (enc.isNewLine(bytes, sprev, end) && s != end) {
            return;
        }
        opFail();
    }

    private void opBeginLineSb() {
        if (s == str) {
            if (isNotBol(msaOptions)) opFail();
            return;
        } else if (bytes[sprev] == Encoding.NEW_LINE && s != end) {
            return;
        }
        opFail();
    }

    private void opEndLine()  {
        if (s == end) {
            if (Config.USE_NEWLINE_AT_END_OF_STRING_HAS_EMPTY_LINE) {
                if (str == end || !enc.isNewLine(bytes, sprev, end)) {
                    if (isNotEol(msaOptions)) opFail();
                }
                return;
            } else {
                if (isNotEol(msaOptions)) opFail();
                return;
            }
        } else if (enc.isNewLine(bytes, s, end) || (Config.USE_CRNL_AS_LINE_TERMINATOR && enc.isMbcCrnl(bytes, s, end))) {
            return;
        }
        opFail();
    }

    private void opEndLineSb()  {
        if (s == end) {
            if (Config.USE_NEWLINE_AT_END_OF_STRING_HAS_EMPTY_LINE) {
                if (str == end || !(sprev < end && bytes[sprev] == Encoding.NEW_LINE)) {
                    if (isNotEol(msaOptions)) opFail();
                }
                return;
            } else {
                if (isNotEol(msaOptions)) opFail();
                return;
            }
        } else if (bytes[s] == Encoding.NEW_LINE || (Config.USE_CRNL_AS_LINE_TERMINATOR && enc.isMbcCrnl(bytes, s, end))) {
            return;
        }
        opFail();
    }

    private void opSemiEndBuf() {
        if (s == end) {
            if (Config.USE_NEWLINE_AT_END_OF_STRING_HAS_EMPTY_LINE) {
                if (str == end || !enc.isNewLine(bytes, sprev, end)) {
                    if (isNotEol(msaOptions)) opFail();
                }
                return;
            } else {
                if (isNotEol(msaOptions)) opFail();
                return;
            }
        } else if (enc.isNewLine(bytes, s, end) && (s + enc.length(bytes, s, end)) == end) {
            return;
        } else if (Config.USE_CRNL_AS_LINE_TERMINATOR && enc.isMbcCrnl(bytes, s, end)) {
            int ss = s + enc.length(bytes, s, end);
            ss += enc.length(bytes, ss, end);
            if (ss == end) return;
        }
        opFail();
    }

    private void opBeginPosition() {
        if (s != msaGpos) opFail();
    }

    private void opMemoryStartPush() {
        int mem = code[ip++];
        pushMemStart(mem, s);
    }

    private void opMemoryStart() {
        int mem = code[ip++];
        repeatStk[memStartStk + mem] = s;
        repeatStk[memEndStk + mem] = -1;
    }

    private void opMemoryEndPush() {
        int mem = code[ip++];
        pushMemEnd(mem, s);
    }

    private void opMemoryEnd() {
        int mem = code[ip++];
        repeatStk[memEndStk + mem] = s;
    }

    private void opKeep() {
        pkeep = s;
    }

    private void opMemoryEndPushRec() {
        int mem = code[ip++];
        int stkp = getMemStart(mem); /* should be before push mem-end. */
        pushMemEnd(mem, s);
        repeatStk[memStartStk + mem] = stkp;
    }

    private void opMemoryEndRec() {
        int mem = code[ip++];
        repeatStk[memEndStk + mem] = s;
        int stkp = getMemStart(mem);
        repeatStk[memStartStk + mem] = bsAt(regex.btMemStart, mem) ? stkp : stack[stkp].getMemPStr();
        pushMemEndMark(mem);
    }

    private boolean backrefInvalid(int mem) {
        return repeatStk[memEndStk + mem] == INVALID_INDEX || repeatStk[memStartStk + mem] == INVALID_INDEX;
    }

    private int backrefStart(int mem) {
        int ms = repeatStk[memStartStk + mem];
        return bsAt(regex.btMemStart, mem) ? stack[ms].getMemPStr() : ms;
    }

    private int backrefEnd(int mem) {
        int me = repeatStk[memEndStk + mem];
        return bsAt(regex.btMemEnd, mem) ? stack[me].getMemPStr() : me;
    }

    private void backref(int mem) {
        if (mem > regex.numMem || backrefInvalid(mem)) {opFail(); return;}
        int pstart = backrefStart(mem);
        int pend = backrefEnd(mem);
        int n = pend - pstart;
        if (s + n > range) {opFail(); return;}
        sprev = s;

        while (n-- > 0) if (bytes[pstart++] != bytes[s++]) {opFail(); return;}

        if (sprev < range) { // beyond string check
            int len;
            while (sprev + (len = enc.length(bytes, sprev, end)) < s) sprev += len;
        }
    }

    private void opBackRef1() {
        backref(1);
    }

    private void opBackRef2() {
        backref(2);
    }

    private void opBackRefN() {
        backref(code[ip++]);
    }

    private void opBackRefNIC() {
        int mem = code[ip++];
        if (mem > regex.numMem || backrefInvalid(mem)) {opFail(); return;}
        int pstart = backrefStart(mem);
        int pend = backrefEnd(mem);
        int n = pend - pstart;
        if (s + n > range) {opFail(); return;}
        sprev = s;

        value = s;
        if (!stringCmpIC(currentCaseFoldFlag(), pstart, this, n, end)) {opFail(); return;}
        s = value;

        if (sprev < range) {
            int len;
            while (sprev + (len = enc.length(bytes, sprev, end)) < s) sprev += len;
        }
    }

    private void opBackRefMulti() {
        int tlen = code[ip++];

        int i;
        loop:for (i=0; i<tlen; i++) {
            int mem = code[ip++];
            if (backrefInvalid(mem)) continue;

            int pstart = backrefStart(mem);
            int pend = backrefEnd(mem);

            int n = pend - pstart;
            if (s + n > range) continue;

            sprev = s;
            int swork = s;

            while (n-- > 0) {
                if (bytes[pstart++] != bytes[swork++]) continue loop;
            }

            s = swork;

            int len;

            // beyond string check
            if (sprev < range) {
                while (sprev + (len = enc.length(bytes, sprev, end)) < s) sprev += len;
            }

            ip += tlen - i  - 1; // * SIZE_MEMNUM (1)
            break; /* success */
        }
        if (i == tlen) {opFail(); return;}
    }

    private void opBackRefMultiIC() {
        int tlen = code[ip++];

        int i;
        loop:for (i=0; i<tlen; i++) {
            int mem = code[ip++];
            if (backrefInvalid(mem)) continue;

            int pstart = backrefStart(mem);
            int pend = backrefEnd(mem);

            int n = pend - pstart;
            if (s + n > range) continue;

            sprev = s;

            value = s;
            if (!stringCmpIC(currentCaseFoldFlag(), pstart, this, n, end)) continue loop; // STRING_CMP_VALUE_IC
            s = value;

            int len;
            if (sprev < range) {
                while (sprev + (len = enc.length(bytes, sprev, end)) < s) sprev += len;
            }

            ip += tlen - i  - 1; // * SIZE_MEMNUM (1)
            break;  /* success */
        }
        if (i == tlen) {opFail(); return;}
    }

    private boolean memIsInMemp(int mem, int num, int memp) {
        for (int i=0; i<num; i++) {
            int m = code[memp++];
            if (mem == m) return true;
        }
        return false;
    }

    // USE_BACKREF_AT_LEVEL // (s) and (end) implicit
    private boolean backrefMatchAtNestedLevel(boolean ignoreCase, int caseFoldFlag,
                                              int nest, int memNum, int memp) {
        int pend = -1;
        int level = 0;
        int k = stk - 1;

        while (k >= 0) {
            StackEntry e = stack[k];

            if (e.type == CALL_FRAME) {
                level--;
            } else if (e.type == RETURN) {
                level++;
            } else if (level == nest) {
                if (e.type == MEM_START) {
                    if (memIsInMemp(e.getMemNum(), memNum, memp)) {
                        int pstart = e.getMemPStr();
                        if (pend != -1) {
                            if (pend - pstart > end - s) return false; /* or goto next_mem; */
                            int p = pstart;

                            value = s;
                            if (ignoreCase) {
                                if (!stringCmpIC(caseFoldFlag, pstart, this, pend - pstart, end)) {
                                    return false; /* or goto next_mem; */
                                }
                            } else {
                                while (p < pend) {
                                    if (bytes[p++] != bytes[value++]) return false; /* or goto next_mem; */
                                }
                            }
                            s = value;

                            return true;
                        }
                    }
                } else if (e.type == MEM_END) {
                    if (memIsInMemp(e.getMemNum(), memNum, memp)) {
                        pend = e.getMemPStr();
                    }
                }
            }
            k--;
        }
        return false;
    }

    private void opBackRefAtLevel() {
        int ic      = code[ip++];
        int level   = code[ip++];
        int tlen    = code[ip++];

        sprev = s;
        if (backrefMatchAtNestedLevel(ic != 0, currentCaseFoldFlag(), level, tlen, ip)) { // (s) and (end) implicit
            int len;
            if (sprev < range) {
                while (sprev + (len = enc.length(bytes, sprev, end)) < s) sprev += len;
            }
            ip += tlen; // * SIZE_MEMNUM
        } else {
            {opFail(); return;}
        }
    }

    /* no need: IS_DYNAMIC_OPTION() == 0 */
    @SuppressWarnings("unused")
    private void opSetOptionPush() {
        currentRegexOptions = code[ip++];
        pushAlt(ip, s, sprev, pkeep);
        ip += OPSize.SET_OPTION + OPSize.FAIL;
    }

    @SuppressWarnings("unused")
    private void opSetOption() {
        currentRegexOptions = code[ip++];
    }

    private int currentCaseFoldFlag() {
        return regex.caseFoldFlagFor(currentRegexOptions);
    }

    private void opNullCheckStart() {
        int mem = code[ip++];
        pushNullCheckStart(mem, s);
    }

    private void nullCheckFound() {
        // null_check_found:
        /* empty loop founded, skip next instruction */
        switch(code[ip++]) {
        case OPCode.JUMP:
        case OPCode.PUSH:
            ip++;       // p += SIZE_RELADDR;
            break;
        case OPCode.REPEAT_INC:
        case OPCode.REPEAT_INC_NG:
        case OPCode.REPEAT_INC_SG:
        case OPCode.REPEAT_INC_NG_SG:
            ip++;        // p += SIZE_MEMNUM;
            break;
        default:
            throw new InternalException(ErrorMessages.UNEXPECTED_BYTECODE);
        } // switch
    }

    private void opNullCheckEnd() {
        int mem = code[ip++];
        int isNull = nullCheck(mem, s); /* mem: null check id */

        if (isNull != 0) {
            if (Config.DEBUG_MATCH) {
                Config.log.println("NULL_CHECK_END: skip  id:" + mem + ", s:" + s);
            }

            nullCheckFound();
        }
    }

    // USE_INFINITE_REPEAT_MONOMANIAC_MEM_STATUS_CHECK
    private void opNullCheckEndMemST() {
        int mem = code[ip++];   /* mem: null check id */
        int isNull = nullCheckMemSt(mem, s);

        if (isNull != 0) {
            if (Config.DEBUG_MATCH) {
                Config.log.println("NULL_CHECK_END_MEMST: skip  id:" + mem + ", s:" + s);
            }

            if (isNull == -1) {opFail(); return;}
            nullCheckFound();
        }
    }

    // USE_SUBEXP_CALL
    private void opNullCheckEndMemSTPush() {
        int mem = code[ip++];   /* mem: null check id */

        int isNull;
        if (Config.USE_MONOMANIAC_CHECK_CAPTURES_IN_ENDLESS_REPEAT) {
            isNull = nullCheckMemStRec(mem, s);
        } else {
            isNull = nullCheckRec(mem, s);
        }

        if (isNull != 0) {
            if (Config.DEBUG_MATCH) {
                Config.log.println("NULL_CHECK_END_MEMST_PUSH: skip  id:" + mem + ", s:" + s);
            }

            if (isNull == -1) {opFail(); return;}
            nullCheckFound();
        } else {
            pushNullCheckEnd(mem);
        }
    }

    private void opJump() {
        ip += code[ip] + 1;
    }

    private void opPush() {
        int addr = code[ip++];
        pushAlt(ip + addr, s, sprev, pkeep);
    }

    // CEC
    private void opStateCheckPush() {
        int mem = code[ip++];
        if (stateCheckVal(s, mem)) {opFail(); return;}
        int addr = code[ip++];
        pushAltWithStateCheck(ip + addr, s, sprev, mem, pkeep);
    }

    // CEC
    private void opStateCheckPushOrJump() {
        int mem = code[ip++];
        int addr= code[ip++];

        if (stateCheckVal(s, mem)) {
            ip += addr;
        } else {
            pushAltWithStateCheck(ip + addr, s, sprev, mem, pkeep);
        }
    }

    // CEC
    private void opStateCheck() {
        int mem = code[ip++];
        if (stateCheckVal(s, mem)) {opFail(); return;}
        pushStateCheck(s, mem);
    }

    private void opPop() {
        popOne();
    }

    private void opPushOrJumpExact1() {
        int addr = code[ip++];
        // beyond string check
        if (s < range && code[ip] == bytes[s]) {
            ip++;
            pushAlt(ip + addr, s, sprev, pkeep);
            return;
        }
        ip += addr + 1;
    }

    private void opPushIfPeekNext() {
        int addr = code[ip++];
        // beyond string check
        if (s < range && code[ip] == bytes[s]) {
            ip++;
            pushAlt(ip + addr, s, sprev, pkeep);
            return;
        }
        ip++;
    }

    private void opRepeat() {
        int mem = code[ip++];   /* mem: OP_REPEAT ID */
        int addr= code[ip++];

        // ensure1();
        repeatStk[mem] = stk;
        pushRepeat(mem, ip);

        if (regex.repeatRangeLo[mem] == 0) { // lower
            pushAlt(ip + addr, s, sprev, pkeep);
        }
    }

    private void opRepeatNG() {
        int mem = code[ip++];   /* mem: OP_REPEAT ID */
        int addr= code[ip++];

        // ensure1();
        repeatStk[mem] = stk;
        pushRepeat(mem, ip);

        if (regex.repeatRangeLo[mem] == 0) {
            pushAlt(ip, s, sprev, pkeep);
            ip += addr;
        }
    }

    private void repeatInc(int mem, int si) {
        StackEntry e = stack[si];

        e.increaseRepeatCount();

        if (e.getRepeatCount() >= regex.repeatRangeHi[mem]) {
            /* end of repeat. Nothing to do. */
        } else if (e.getRepeatCount() >= regex.repeatRangeLo[mem]) {
            pushAlt(ip, s, sprev, pkeep);
            ip = e.getRepeatPCode(); /* Don't use stkp after PUSH. */
        } else {
            ip = e.getRepeatPCode();
        }
        pushRepeatInc(si);
    }

    private void opRepeatInc() {
        int mem = code[ip++];   /* mem: OP_REPEAT ID */
        int si = repeatStk[mem];
        repeatInc(mem, si);
    }

    private void opRepeatIncSG() {
        int mem = code[ip++];   /* mem: OP_REPEAT ID */
        int si = getRepeat(mem);
        repeatInc(mem, si);
    }

    private void repeatIncNG(int mem, int si) {
        StackEntry e = stack[si];

        e.increaseRepeatCount();

        if (e.getRepeatCount() < regex.repeatRangeHi[mem]) {
            if (e.getRepeatCount() >= regex.repeatRangeLo[mem]) {
                int pcode = e.getRepeatPCode();
                pushRepeatInc(si);
                pushAlt(pcode, s, sprev, pkeep);
            } else {
                ip = e.getRepeatPCode();
                pushRepeatInc(si);
            }
        } else if (e.getRepeatCount() == regex.repeatRangeHi[mem]) {
            pushRepeatInc(si);
        }
    }

    private void opRepeatIncNG() {
        int mem = code[ip++];
        int si = repeatStk[mem];
        repeatIncNG(mem, si);
    }

    private void opRepeatIncNGSG() {
        int mem = code[ip++];
        int si = getRepeat(mem);
        repeatIncNG(mem, si);
    }

    private void opPushPos() {
        int addr = code[ip++];
        pushPos(ip + addr, s, sprev, pkeep);
    }

    private void opPopPos() {
        StackEntry e = stack[posEnd()];
        s    = e.getStatePStr();
        sprev= e.getStatePStrPrev();
    }

    private void opPushPosNot() {
        int addr = code[ip++];
        pushPosNot(ip + addr, s, sprev, pkeep);
    }

    private void opFailPos() {
        popTilPosNot();
        opFail();
    }

    private void opPopPosNot() {
        StackEntry e = stack[posNotEnd()];
        s = e.getStatePStr();
        sprev = e.getStatePStrPrev();
    }

    private void opPushStopBT() {
        pushStopBT();
    }

    private void opPopStopBT() {
        stopBtEnd();
    }

    private void opLookBehind() {
        int tlen = code[ip++];
        s = enc.stepBack(bytes, str, s, end, tlen);
        if (s == -1) {opFail(); return;}
        sprev = enc.prevCharHead(bytes, str, s, end);
    }

    private void opLookBehindSb() {
        int tlen = code[ip++];
        s -= tlen;
        if (s < str) {opFail(); return;}
        sprev = s == str ? -1 : s - 1;
    }

    private void opPushLookBehindNot() {
        int addr = code[ip++];
        int tlen = code[ip++];
        int q = enc.stepBack(bytes, str, s, end, tlen);
        if (q == -1) {
            /* too short case -> success. ex. /(?<!XXX)a/.match("a")
            If you want to change to fail, replace following line. */
            ip += addr;
            // return FAIL;
        } else {
            pushLookBehindNot(ip + addr, s, sprev, pkeep);
            s = q;
            sprev = enc.prevCharHead(bytes, str, s, end);
        }
    }

    private void opFailLookBehindNot() {
        popTilLookBehindNot();
        opFail();
    }

    private void opPushAbsentPos() {
        pushAbsentPos(s, range);
    }

    private void opAbsent() {
        int aend = range; // use end for USE_MATCH_RANGE_MUST_BE_INSIDE_OF_SPECIFIED_RANGE
        int selfip = ip - 1;
        StackEntry e = stack[--stk];
        int absent = e.getAbsentStr();
        range = e.getAbsentEndStr();
        int addr = code[ip++];

        if (Config.DEBUG_MATCH) System.out.println("ABSENT: s:" + s + " end:" + end + " absent:" + absent + " aend:" + aend);

        if (absent > aend && s > absent) {
            pop();
            opFail();
            return;
        } else if (s >= aend && s > absent) {
            if (s > aend || s > end) {
                opFail();
                return;
            }
            ip += addr;
        } else {

            pushAlt(ip + addr, s, sprev, pkeep);
            int n = (s >= end) ? 1 : enc.length(bytes, s, end);
            pushAbsentPos(absent, range);
            pushAlt(selfip, s + n, s, pkeep);
            pushAbsent();
            range = aend;
        }
    }

    private void opAbsentEnd() {
        if (sprev < range) range = sprev;
        if (Config.DEBUG_MATCH) System.out.println("ABSENT_END: end:" + range);
        popTilAbsent();
        opFail();
        return;
        // sprev = sbegin; // break;
    }

    private void opCall() {
        if (activeSubexpCallDepth() >= MAX_SUBEXP_CALL_DEPTH) {
            throw new ValueException("subpattern recursion limit exceeded");
        }
        int addr = code[ip++];
        int encodedGroupNum = code[ip++];
        boolean recursive = encodedGroupNum <= -2;
        int groupNum = recursive ? -encodedGroupNum - 1 : encodedGroupNum;
        recursive |= isInsideSubexpCall(groupNum);
        pushCallFrame(ip, groupNum, recursive);
        ip = addr; // absolute address
    }

    private int activeSubexpCallDepth() {
        int depth = 0;
        for (int i = 0; i < stk; i++) {
            if (stack[i].type == CALL_FRAME) depth++;
            else if (stack[i].type == RETURN) depth--;
        }
        return depth;
    }

    private void opCallout() {
        int calloutId = code[ip++];
        CalloutHandler handler = getCalloutHandler();
        if (handler == null) throw new IllegalStateException("callout pattern has no handler");
        CalloutResult result = handler.execute(calloutId, this);
        if (result == null) throw new NullPointerException("callout result");
        if (result.getBacktrackToken() != null) pushCallout(result.getBacktrackToken());
        if (result.getAction() == CalloutAction.FAIL) opFail();
    }

    private void opCalloutCondition() {
        int calloutId = code[ip++];
        int addr = code[ip++];
        CalloutHandler handler = getCalloutHandler();
        if (handler == null) throw new IllegalStateException("callout pattern has no handler");
        CalloutResult result = handler.execute(calloutId, this);
        if (result == null) throw new NullPointerException("callout result");
        if (result.getBacktrackToken() != null) pushCallout(result.getBacktrackToken());
        if (result.getAction() == CalloutAction.FAIL) ip += addr;
    }

    private void opDynamicCallout() {
        int calloutId = code[ip++];
        int returnAddress = ip;
        CalloutHandler handler = getCalloutHandler();
        if (handler == null) throw new IllegalStateException("dynamic pattern has no handler");
        DynamicPatternResult result = handler.executeDynamic(calloutId, this);
        if (result == null) throw new NullPointerException("dynamic pattern result");
        if (result.getBacktrackToken() != null) pushCallout(result.getBacktrackToken());

        Matcher nestedMatcher = result.getRegex().matcher(bytes, str, end,
                timeout == -1 ? -1 : Math.max(1, timeout - (System.nanoTime() - startTime)));
        if (!(nestedMatcher instanceof ByteCodeMachine nested)) {
            throw new IllegalStateException("dynamic regex requires the bytecode matcher");
        }
        nested.useIndependentStack();
        CalloutHandler nestedHandler = result.getCalloutHandler();
        nested.setCalloutHandler(nestedHandler);
        DynamicContinuation continuation = new DynamicContinuation(
                nested, nestedHandler, s, range);
        int nestedEnd = continuation.first();
        continuation.propagateControlTo(this);
        if (nestedEnd < 0) {
            continuation.abort();
            opFail();
            return;
        }
        pushDynamicAlternative(returnAddress, continuation);
        s = nestedEnd;
        sprev = enc.prevCharHead(bytes, str, s, end);
    }

    /**
     * Accept the nearest matcher-program boundary. Ordinary groups and repeats
     * are not boundaries; calls and assertions are, matching Perl's behavior.
     */
    private boolean opAccept() {
        int callDepth = 0;
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry entry = stack[i];
            if (entry.type == RETURN) {
                callDepth++;
            } else if (entry.type == CALL_FRAME) {
                if (callDepth > 0) {
                    callDepth--;
                } else {
                    closeOpenCaptures(i);
                    cutAcceptBacktrackingAbove(i);
                    opReturn();
                    return false;
                }
            } else if (entry.type == POS) {
                closeOpenCaptures(i);
                int target = entry.getStatePCode();
                StackEntry position = stack[posEnd()];
                s = position.getStatePStr();
                sprev = position.getStatePStrPrev();
                pkeep = position.getPKeep();
                ip = target;
                return false;
            } else if (entry.type == POS_NOT) {
                closeOpenCaptures(i);
                popTilPosNot();
                opFail();
                return false;
            } else if (entry.type == LOOK_BEHIND_NOT) {
                closeOpenCaptures(i);
                popTilLookBehindNot();
                opFail();
                return false;
            }
        }

        closeOpenCaptures(-1);
        return opEnd();
    }

    private void opPrune() {
        controlVerbEncountered = true;
        String name = controlVerbName(code[ip++]);
        controlError = name == null ? "1" : name;
        cutAlternatives(false);
        pendingControlAction = CONTROL_PRUNE;
    }

    private void opSkip() {
        controlVerbEncountered = true;
        String name = controlVerbName(code[ip++]);
        controlError = name == null ? "1" : name;
        if (name != null) {
            int target = findControlMarkPosition(name);
            if (target < 0) return;
            cutAlternatives(false);
            requestSearchSkip(target);
            pendingControlAction = CONTROL_SKIP;
            return;
        }
        cutAlternatives(false);
        requestSearchSkip(s);
        pendingControlAction = CONTROL_SKIP;
    }

    private void opThen() {
        controlVerbEncountered = true;
        String name = controlVerbName(code[ip++]);
        controlError = name == null ? "1" : name;
        cutAlternatives(true, s, sprev, pkeep);
        pendingControlAction = CONTROL_THEN;
    }

    private void opCommit() {
        controlVerbEncountered = true;
        String name = controlVerbName(code[ip++]);
        controlError = name == null ? "1" : name;
        cutAlternatives(false);
        requestSearchAbort();
        pendingControlAction = CONTROL_COMMIT;
    }

    private void opMark() {
        controlVerbEncountered = true;
        String next = controlVerbName(code[ip++]);
        pushControlMark(controlMark, next, s);
        controlMark = next;
    }

    @Override
    protected void restoreControlMark(String name) {
        if (controlMark != null) controlError = controlMark;
        controlMark = name;
    }

    private String controlVerbName(int labelId) {
        return labelId < 0 ? null : regex.controlVerbLabels[labelId];
    }

    private void closeOpenCaptures(int boundary) {
        for (int mem = 1; mem <= regex.numMem; mem++) {
            if (repeatStk[memStartStk + mem] == INVALID_INDEX
                    || repeatStk[memEndStk + mem] != INVALID_INDEX) {
                continue;
            }
            if (boundary >= 0 && !hasMemoryStartAbove(mem, boundary)) continue;
            if (bsAt(regex.btMemEnd, mem)) pushMemEnd(mem, s);
            else repeatStk[memEndStk + mem] = s;
        }
    }

    private boolean hasMemoryStartAbove(int mem, int boundary) {
        for (int i = stk - 1; i > boundary; i--) {
            StackEntry entry = stack[i];
            if (entry.type == MEM_START && entry.getMemNum() == mem) return true;
        }
        return false;
    }

    @Override
    public int currentBytePosition() {
        return s - str;
    }

    @Override
    public int captureCount() {
        return regex.numMem;
    }

    @Override
    public int captureBegin(int capture) {
        checkCapture(capture);
        if (capture == 0) return sstart - str;
        int value = visibleCapturePointer(capture, true);
        if (value == INVALID_INDEX) return Region.REGION_NOTPOS;
        return (bsAt(regex.btMemStart, capture) ? stack[value].getMemPStr() : value) - str;
    }

    @Override
    public int captureEnd(int capture) {
        checkCapture(capture);
        if (capture == 0) return s - str;
        int value = visibleCapturePointer(capture, false);
        if (value == INVALID_INDEX) return Region.REGION_NOTPOS;
        return (bsAt(regex.btMemEnd, capture) ? stack[value].getMemPStr() : value) - str;
    }

    @Override
    public int lastClosedCapture() {
        CompletedRecursiveCall completed = completedRecursiveCall();
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry entry = stack[i];
            if (entry.type != MEM_END && entry.type != MEM_END_MARK) continue;
            int mem = entry.getMemNum();
            boolean active = entry.type == MEM_END
                    ? repeatStk[memEndStk + mem] == i
                    : repeatStk[memEndStk + mem] != INVALID_INDEX;
            if (!active && entry.type == MEM_END
                    && repeatStk[memEndStk + mem] == INVALID_INDEX
                    && repeatStk[memStartStk + mem] != INVALID_INDEX) {
                active = true;
            }
            if (active && (completed == null || i > completed.returnIndex)) return mem;
        }
        if (completed != null) {
            int[] snapshot = completed.frame.getCallFrameCaptureSnapshot();
            int count = regex.numMem + 1;
            int latest = INVALID_INDEX;
            int latestCapture = -1;
            for (int mem = 1; mem <= regex.numMem; mem++) {
                int end = snapshot[count + mem];
                if (end > latest) {
                    latest = end;
                    latestCapture = mem;
                }
            }
            return latestCapture;
        }
        // Recursive memory-end opcodes may retain only direct offsets after a
        // successful candidate commits. In that representation there is no
        // active MEM_END stack entry to identify, so recover Perl's outermost
        // last close from the visible capture ends. Ties keep the lower-numbered
        // (and therefore enclosing) capture, which closes after its child.
        int latestEnd = Region.REGION_NOTPOS;
        int latestCapture = -1;
        for (int mem = 1; mem <= regex.numMem; mem++) {
            int end = captureEnd(mem);
            if (end > latestEnd) {
                latestEnd = end;
                latestCapture = mem;
            }
        }
        return latestCapture;
    }

    private int visibleCapturePointer(int capture, boolean begin) {
        int current = repeatStk[(begin ? memStartStk : memEndStk) + capture];
        CompletedRecursiveCall completed = completedRecursiveCall();
        if (completed == null) {
            int previous = previousClosedCapturePointer(capture, begin);
            return previous != INVALID_INDEX ? previous : current;
        }
        int[] snapshot = completed.frame.getCallFrameCaptureSnapshot();
        int count = regex.numMem + 1;
        int currentEnd = repeatStk[memEndStk + capture];
        if (currentEnd != INVALID_INDEX && captureClosedAfterReturn(
                capture, completed.returnIndex)) {
            if (begin && snapshot[capture] != INVALID_INDEX
                    && snapshot[count + capture] == INVALID_INDEX) {
                // The caller had opened this enclosing capture before making
                // the recursive call and closed it after return. Its start is
                // from the caller snapshot; its end is the committed current end.
                return snapshot[capture];
            }
            return current;
        }
        return snapshot[(begin ? 0 : count) + capture];
    }

    private int previousClosedCapturePointer(int capture, boolean begin) {
        if (repeatStk[memEndStk + capture] != INVALID_INDEX
                || repeatStk[memStartStk + capture] == INVALID_INDEX) {
            return INVALID_INDEX;
        }
        int endPointer = INVALID_INDEX;
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry entry = stack[i];
            if (endPointer == INVALID_INDEX) {
                if (entry.type == MEM_END && entry.getMemNum() == capture) {
                    if (!begin) return i;
                    endPointer = i;
                }
            } else if (entry.type == MEM_START && entry.getMemNum() == capture) {
                return i;
            }
        }
        return INVALID_INDEX;
    }

    private boolean captureClosedAfterReturn(int capture, int returnIndex) {
        for (int i = stk - 1; i > returnIndex; i--) {
            StackEntry entry = stack[i];
            if ((entry.type == MEM_END || entry.type == MEM_END_MARK)
                    && entry.getMemNum() == capture) return true;
        }
        return false;
    }

    private CompletedRecursiveCall completedRecursiveCall() {
        ArrayDeque<Integer> returns = new ArrayDeque<>();
        CompletedRecursiveCall visible = null;
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry entry = stack[i];
            if (entry.type == RETURN) {
                returns.push(i);
            } else if (entry.type == CALL_FRAME && !returns.isEmpty()) {
                int returnIndex = returns.pop();
                if (entry.getCallFrameCaptureSnapshot() != null
                        && (visible == null || returnIndex > visible.returnIndex)) {
                    visible = new CompletedRecursiveCall(entry, returnIndex);
                }
            }
        }
        return visible;
    }

    private static final class CompletedRecursiveCall {
        final StackEntry frame;
        final int returnIndex;

        CompletedRecursiveCall(StackEntry frame, int returnIndex) {
            this.frame = frame;
            this.returnIndex = returnIndex;
        }
    }

    @Override
    public String controlMark() {
        return controlMark;
    }

    private void checkCapture(int capture) {
        if (capture < 0 || capture > regex.numMem) {
            throw new IndexOutOfBoundsException("capture " + capture);
        }
    }

    private void opReturn() {
        StackEntry frame = returnFrame();
        ip = frame.getCallFrameRetAddr();
        pushReturn();
    }

    private void opFail() {
        if (stack == null) {
            ip = regex.codeLength - 1;
            return;
        }

        while (true) {
            StackEntry e = pop();
            if (e.type == DYNAMIC_ALT) {
                int returnAddress = e.getStatePCode();
                DynamicContinuation continuation = e.takeDynamicContinuation();
                int nestedEnd = continuation.next();
                continuation.propagateControlTo(this);
                if (nestedEnd >= 0) {
                    pushDynamicAlternative(returnAddress, continuation);
                    ip = returnAddress;
                    s = nestedEnd;
                    sprev = enc.prevCharHead(bytes, str, s, end);
                    return;
                }
                continuation.abort();
                continue;
            }

            ip    = e.getStatePCode();
            s     = e.getStatePStr();
            sprev = e.getStatePStrPrev();
            pkeep = e.getPKeep();

            if (USE_CEC && ((SCStackEntry)e).getStateCheck() != 0) {
                e.type = STATE_CHECK_MARK;
                stk++;
            }
            return;
        }
    }

    /** Lazy nested matcher whose remaining alternatives belong to its caller. */
    static final class DynamicContinuation {
        private final ByteCodeMachine machine;
        private final CalloutHandler handler;
        private final int start;
        private final int range;
        private boolean started;
        private boolean closed;

        DynamicContinuation(ByteCodeMachine machine, CalloutHandler handler,
                int start, int range) {
            this.machine = machine;
            this.handler = handler;
            this.start = start;
            this.range = range;
        }

        int first() {
            if (started) throw new IllegalStateException("dynamic continuation already started");
            started = true;
            machine.msaInit(Option.NONE, start, start);
            if (Config.USE_CEC) {
                machine.stateCheckBuffInit(machine.end - machine.str,
                        start - machine.str, machine.regex.numCombExpCheck);
            }
            machine.range = range;
            machine.sstart = start;
            machine.sprev = machine.enc.prevCharHead(machine.bytes,
                    machine.str, start, machine.end);
            machine.stk = 0;
            machine.ip = 0;
            machine.stackInit();
            machine.bestLen = -1;
            machine.s = start;
            machine.pkeep = start;
            if (machine.timeout != -1) machine.startTime = System.nanoTime();
            return run();
        }

        int next() {
            if (!started || closed) return Matcher.FAILED;
            machine.bestLen = -1;
            machine.opFail();
            return run();
        }

        private int run() {
            try {
                int matched = machine.enc.isSingleByte()
                        || (machine.msaOptions & Option.CR_7_BIT) != 0
                        ? machine.executeSb(false) : machine.execute(false);
                return matched < 0 ? Matcher.FAILED : machine.s;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Matcher.FAILED;
            }
        }

        void propagateControlTo(ByteCodeMachine outer) {
            int action = machine.pendingControlAction;
            machine.pendingControlAction = CONTROL_NONE;
            if (machine.controlVerbEncountered) outer.controlVerbEncountered = true;
            if (machine.controlError != null) outer.controlError = machine.controlError;
            switch (action) {
            case CONTROL_PRUNE:
                outer.cutAlternatives(false);
                break;
            case CONTROL_SKIP:
                outer.cutAlternatives(false);
                int target = machine.takeSearchSkipRequest();
                if (target >= 0) outer.requestSearchSkip(target);
                break;
            case CONTROL_THEN:
                // A dynamic program is an alternation boundary in Perl. THEN
                // cannot enter an enclosing branch, so crossing this boundary
                // has the same outer-stack effect as PRUNE.
                outer.cutAlternatives(false);
                break;
            case CONTROL_COMMIT:
                outer.cutAlternatives(false);
                if (machine.takeSearchAbortRequest()) outer.requestSearchAbort();
                break;
            default:
                break;
            }
        }

        void complete() {
            if (closed) return;
            closed = true;
            machine.completeActiveCallouts();
            if (handler != null) handler.finish(true);
        }

        void abort() {
            if (closed) return;
            closed = true;
            machine.unwindActiveCallouts();
            if (handler != null) handler.finish(false);
        }
    }

    private int finish() {
        return bestLen;
    }

    private void debugMatchBegin() {
        Config.log.println("match_at: " + "str: " + str + ", end: " + end + ", start: " + sstart + ", sprev: " + sprev);
        Config.log.println("size: " + (end - str) + ", start offset: " + (sstart - str));
    }

    private void debugMatchLoop() {
        Config.log.printf("%4d", (s - str)).print("> \"");
        int q, i;
        for (i = 0, q = s; i < 7 && q < end && s >= 0; i++) {
            int len = enc.length(bytes, q, end);
            while (len-- > 0) {
                if (q < end) {
                    Config.log.print(new String(bytes, q++, 1));
                }
            }
        }
        String str = q < end ? "...\"" : "\"";
        q += str.length();
        Config.log.print(str);
        for (i = 0; i < 20 - (q - s); i++)
            Config.log.print(" ");
        StringBuilder sb = new StringBuilder();
        new ByteCodePrinter(regex).compiledByteCodeToString(sb, ip);
        Config.log.println(sb.toString());
    }
}
