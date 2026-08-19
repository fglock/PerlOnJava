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
import static org.joni.Config.USE_CEC;

import java.lang.ref.WeakReference;
import java.util.Arrays;

import org.joni.constants.internal.StackPopLevel;
import org.joni.constants.internal.StackType;

abstract class StackMachine extends Matcher implements StackType {
    protected static final int INVALID_INDEX = -1;

    protected StackEntry[]stack;
    protected int stk;  // stkEnd
    protected final int[]repeatStk;
    protected final int[] physicalNamedCaptureBeg;
    protected final int[] physicalNamedCaptureEnd;
    protected final int[] committedPhysicalNamedCaptureBeg;
    protected final int[] committedPhysicalNamedCaptureEnd;
    protected final int memStartStk, memEndStk;
    protected byte[] stateCheckBuff; // CEC, move to int[] ?
    protected int stateCheckBuffSize;

    protected StackMachine(Regex regex, Region region, byte[]bytes, int p , int end) {
        super(regex, region, bytes, p, end);
        stack = regex.requireStack ? fetchStack() : null;
        final int n;
        if (Config.USE_SUBEXP_CALL) {
            n = regex.numRepeat + ((regex.numMem + 1) << 1);
            memStartStk = regex.numRepeat;
            memEndStk   = memStartStk + regex.numMem + 1;
        } else {
            n = regex.numRepeat + (regex.numMem << 1);
            memStartStk = regex.numRepeat - 1;
            memEndStk   = memStartStk + regex.numMem;
            /* for index start from 1, mem_start_stk[1]..mem_start_stk[num_mem] */
            /* for index start from 1, mem_end_stk[1]..mem_end_stk[num_mem] */
        }
        repeatStk = n > 0 ? new int[n] : null;
        int physicalCount = regex.numPhysicalNamedCaptures;
        physicalNamedCaptureBeg = physicalCount == 0 ? null : new int[physicalCount + 1];
        physicalNamedCaptureEnd = physicalCount == 0 ? null : new int[physicalCount + 1];
        committedPhysicalNamedCaptureBeg = physicalCount == 0 ? null : new int[physicalCount + 1];
        committedPhysicalNamedCaptureEnd = physicalCount == 0 ? null : new int[physicalCount + 1];
    }

    protected final void stackInit() {
        if (stack != null) pushEnsured(ALT, regex.codeLength - 1); /* bottom stack */
        if (repeatStk != null) {
            for (int i = (Config.USE_SUBEXP_CALL ? 0 : 1); i <= regex.numMem; i++) {
                repeatStk[i + memStartStk] = repeatStk[i + memEndStk] = INVALID_INDEX;
            }
        }
        if (physicalNamedCaptureBeg != null) {
            Arrays.fill(physicalNamedCaptureBeg, INVALID_INDEX);
            Arrays.fill(physicalNamedCaptureEnd, INVALID_INDEX);
            Arrays.fill(committedPhysicalNamedCaptureBeg, INVALID_INDEX);
            Arrays.fill(committedPhysicalNamedCaptureEnd, INVALID_INDEX);
        }
    }

    protected final void pushPhysicalNamedCapture(int capture, int position) {
        StackEntry e = ensure1();
        e.type = PHYSICAL_NAMED_CAPTURE;
        e.setPhysicalNamedCapture(capture,
                physicalNamedCaptureBeg[capture], physicalNamedCaptureEnd[capture]);
        physicalNamedCaptureBeg[capture] = position;
        physicalNamedCaptureEnd[capture] = INVALID_INDEX;
        stk++;
    }

    private void restorePhysicalNamedCapture(StackEntry e) {
        int capture = e.getPhysicalNamedCapture();
        physicalNamedCaptureBeg[capture] = e.getPhysicalNamedCaptureBegin();
        physicalNamedCaptureEnd[capture] = e.getPhysicalNamedCaptureEnd();
    }

    private static StackEntry[] allocateStack() {
        StackEntry[]stack = new StackEntry[Config.INIT_MATCH_STACK_SIZE];
        stack[0] = USE_CEC ? new SCStackEntry() : new StackEntry();
        return stack;
    }

    /** Nested matchers on the same thread must not overwrite their caller's stack. */
    protected final void useIndependentStack() {
        if (stack != null) stack = allocateStack();
    }

    private void doubleStack() {
        StackEntry[] newStack = new StackEntry[stack.length << 1];
        System.arraycopy(stack, 0, newStack, 0, stack.length);
        stack = newStack;
    }

    static final ThreadLocal<WeakReference<StackEntry[]>> stacks
            = new ThreadLocal<>();

    private static StackEntry[] fetchStack() {
        WeakReference<StackEntry[]> ref = stacks.get();
        StackEntry[] stack;
        if (ref == null) {
            stacks.set( new WeakReference<>(stack = allocateStack()) );
        }
        else {
            stack = ref.get();
            if (stack == null) {
                stacks.set( new WeakReference<>(stack = allocateStack()) );
            }
        }
        return stack;
    }

    private final StackEntry ensure1() {
        if (stk >= stack.length) doubleStack();
        StackEntry e = stack[stk];
        if (e == null) stack[stk] = e = USE_CEC ? new SCStackEntry() : new StackEntry();
        return e;
    }

    private final void pushType(int type) {
        ensure1().type = type;
        stk++;
    }

    // CEC

    // STATE_CHECK_POS
    private int stateCheckPos(int s, int snum) {
        return (s - str) * regex.numCombExpCheck + (snum - 1);
    }

    // STATE_CHECK_VAL
    protected final boolean stateCheckVal(int s, int snum) {
        if (stateCheckBuff != null) {
            int x = stateCheckPos(s, snum);
            return (stateCheckBuff[x / 8] & (1 << (x % 8))) != 0;
        }
        return false;
    }

    // ELSE_IF_STATE_CHECK_MARK
    private void stateCheckMark() {
        StackEntry e = stack[stk];
        int x = stateCheckPos(e.getStatePStr(), ((SCStackEntry)e).getStateCheck());
        stateCheckBuff[x / 8] |= (1 << (x % 8));
    }

    // STATE_CHECK_BUFF_INIT
    private static final int STATE_CHECK_BUFF_MALLOC_THRESHOLD_SIZE = 16;
    @Override
    protected final void stateCheckBuffInit(int strLength, int offset, int stateNum) {
        if (stateNum > 0 && strLength >= Config.CHECK_STRING_THRESHOLD_LEN) {
            int size = ((strLength + 1) * stateNum + 7) >>> 3;
            offset = (offset * stateNum) >>> 3;

            if (size > 0 && offset < size && size < Config.CHECK_BUFF_MAX_SIZE) {
                if (size >= STATE_CHECK_BUFF_MALLOC_THRESHOLD_SIZE) {
                    stateCheckBuff = new byte[size];
                } else {
                    // same impl, reduce...
                    stateCheckBuff = new byte[size];
                }
                Arrays.fill(stateCheckBuff, offset, size, (byte)0);
                stateCheckBuffSize = size;
            } else {
                stateCheckBuff = null; // reduce
                stateCheckBuffSize = 0;
            }
        } else {
            stateCheckBuff = null; // reduce
            stateCheckBuffSize = 0;
        }
    }

    @Override
    protected final void stateCheckBuffClear() {
        stateCheckBuff = null;
        stateCheckBuffSize = 0;
    }

    private void push(int type, int pat, int s, int prev, int pkeep) {
        StackEntry e = ensure1();
        e.type = type;
        e.setStatePCode(pat);
        e.setStatePStr(s);
        e.setStatePStrPrev(prev);
        if (USE_CEC) ((SCStackEntry)e).setStateCheck(0);
        e.setPKeep(pkeep);
        stk++;
    }

    private final void pushEnsured(int type, int pat) {
        StackEntry e = stack[stk];
        e.type = type;
        e.setStatePCode(pat);
        if (USE_CEC) ((SCStackEntry)e).setStateCheck(0);
        stk++;
    }

    protected final void pushAltWithStateCheck(int pat, int s, int sprev, int snum, int pkeep) {
        StackEntry e = ensure1();
        e.type = ALT;
        e.setStatePCode(pat);
        e.setStatePStr(s);
        e.setStatePStrPrev(sprev);
        if (USE_CEC) ((SCStackEntry)e).setStateCheck(stateCheckBuff != null ? snum : 0);
        e.setPKeep(pkeep);
        stk++;
    }

    protected final void pushStateCheck(int s, int snum) {
        if (stateCheckBuff != null) {
            StackEntry e = ensure1();
            e.type = STATE_CHECK_MARK;
            e.setStatePStr(s);
            ((SCStackEntry)e).setStateCheck(snum);
            stk++;
        }
    }

    protected final void pushAlt(int pat, int s, int prev, int pkeep) {
        push(ALT, pat, s, prev, pkeep);
    }

    protected final void pushBranchAlt(int pat, int s, int prev, int pkeep) {
        push(BRANCH_ALT, pat, s, prev, pkeep);
    }

    protected final void pushPos(int target, int s, int prev, int pkeep) {
        push(POS, target, s, prev, pkeep);
    }

    protected final void pushPosNot(int pat, int s, int prev, int pkeep) {
        push(POS_NOT, pat, s, prev, pkeep);
    }

    protected final void pushStopBT() {
        pushType(STOP_BT);
    }

    protected final void pushLookBehindNot(int pat, int s, int sprev, int pkeep) {
        push(LOOK_BEHIND_NOT, pat, s, sprev, pkeep);
    }

    protected final void pushRepeat(int id, int pat) {
        StackEntry e = ensure1();
        e.type = REPEAT;
        e.setRepeatNum(id);
        e.setRepeatPCode(pat);
        e.setRepeatCount(0);
        stk++;
    }

    protected final void pushRepeatInc(int sindex) {
        StackEntry e = ensure1();
        e.type = REPEAT_INC;
        e.setSi(sindex);
        stk++;
    }

    protected final void pushMemStart(int mnum, int s) {
        StackEntry e = ensure1();
        e.type = MEM_START;
        e.setMemNum(mnum);
        e.setMemPstr(s);
        e.setMemStart(repeatStk[memStartStk + mnum]);
        e.setMemEnd(repeatStk[memEndStk + mnum]);
        repeatStk[memStartStk + mnum] = stk;
        repeatStk[memEndStk + mnum] = INVALID_INDEX;
        stk++;
    }

    protected final void pushMemEnd(int mnum, int s) {
        StackEntry e = ensure1();
        e.type = MEM_END;
        e.setMemNum(mnum);
        e.setMemPstr(s);
        e.setMemStart(repeatStk[memStartStk + mnum]);
        e.setMemEnd(repeatStk[memEndStk + mnum]);
        repeatStk[memEndStk + mnum] = stk;
        stk++;
    }

    protected final void pushMemEndMark(int mnum) {
        StackEntry e = ensure1();
        e.type = MEM_END_MARK;
        e.setMemNum(mnum);
        stk++;
    }

    protected final void beginRepeatCaptureIteration(int id, int[] groups) {
        StackEntry marker = ensure1();
        marker.type = REPEAT_CAPTURE_BEGIN;
        marker.setMemNum(id);
        stk++;

        for (int mnum : groups) {
            int oldStart = repeatStk[memStartStk + mnum];
            int oldEnd = repeatStk[memEndStk + mnum];
            StackEntry e = ensure1();
            e.type = REPEAT_CAPTURE_SNAPSHOT;
            e.setMemNum(mnum);
            e.setMemPstr(id);
            e.setMemStart(oldStart);
            e.setMemEnd(oldEnd);
            stk++;
        }
    }

    protected final void endRepeatCaptureIteration(int id) {
        for (int index = stk - 1; index >= 0; index--) {
            StackEntry e = stack[index];
            if (e.type == REPEAT_CAPTURE_BEGIN && e.getMemNum() == id) {
                return;
            }
            if (e.type != REPEAT_CAPTURE_SNAPSHOT || e.getMemPStr() != id) continue;

            int mnum = e.getMemNum();
            if (repeatStk[memStartStk + mnum] == e.getMemStart()
                    && repeatStk[memEndStk + mnum] == e.getMemEnd()) {
                pushRepeatCaptureClear(mnum, e.getMemStart(), e.getMemEnd());
                repeatStk[memStartStk + mnum] = INVALID_INDEX;
                repeatStk[memEndStk + mnum] = INVALID_INDEX;
            }
        }
        // A combination-explosion guard can unwind the bookkeeping marker
        // while preserving a continuation inside the optimized repeat body.
        // In that path there is no surviving entry snapshot to finalize.
    }

    private void pushRepeatCaptureClear(int mnum, int oldStart, int oldEnd) {
        StackEntry clear = ensure1();
        clear.type = REPEAT_CAPTURE_CLEAR;
        clear.setMemNum(mnum);
        clear.setMemStart(oldStart);
        clear.setMemEnd(oldEnd);
        stk++;
    }

    private void restoreRepeatCaptureClear(StackEntry e) {
        repeatStk[memStartStk + e.getMemNum()] = e.getMemStart();
        repeatStk[memEndStk + e.getMemNum()] = e.getMemEnd();
    }

    protected final int getMemStart(int mnum) {
        int level = 0;
        int stkp = stk;

        while (stkp > 0) {
            stkp--;
            StackEntry e = stack[stkp];
            if ((e.type & MASK_MEM_END_OR_MARK) != 0 && e.getMemNum() == mnum) {
                level++;
            } else if (e.type == MEM_START && e.getMemNum() == mnum) {
                if (level == 0) break;
                level--;
            }
        }
        return stkp;
    }

    protected final void pushNullCheckStart(int cnum, int s) {
        StackEntry e = ensure1();
        e.type = NULL_CHECK_START;
        e.setNullCheckNum(cnum);
        e.setNullCheckPStr(s);
        stk++;
    }

    protected final void pushNullCheckEnd(int cnum) {
        StackEntry e = ensure1();
        e.type = NULL_CHECK_END;
        e.setNullCheckNum(cnum);
        stk++;
    }

    protected final void pushCallFrame(int pat, int groupNum, boolean recursive) {
        StackEntry e = ensure1();
        e.type = CALL_FRAME;
        e.setCallFrameRetAddr(pat);
        e.setCallFrameNum(groupNum);
        e.setCallFrameCaptureSnapshot(recursive ? captureSnapshot() : null);
        stk++;
    }

    private int[] captureSnapshot() {
        int count = regex.numMem + 1;
        int[] snapshot = new int[count << 1];
        System.arraycopy(repeatStk, memStartStk, snapshot, 0, count);
        System.arraycopy(repeatStk, memEndStk, snapshot, count, count);
        return snapshot;
    }

    protected final boolean isInsideSubexpCall(int groupNum) {
        int returned = 0;
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry e = stack[i];
            if (e.type == RETURN) {
                returned++;
            } else if (e.type == CALL_FRAME) {
                if (returned > 0) {
                    returned--;
                } else if (e.getCallFrameNum() >= 0
                        && (groupNum == 0 || e.getCallFrameNum() == groupNum)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected final void pushReturn() {
        StackEntry e = ensure1();
        e.type = RETURN;
        stk++;
    }

    protected final void pushAbsent() {
        StackEntry e = ensure1();
        e.type = ABSENT;
        stk++;
    }

    protected final void pushAbsentPos(int start, int end) {
        StackEntry e = ensure1();
        e.type = ABSENT_POS;
        e.setAbsentStr(start);
        e.setAbsentEndStr(end);
        stk++;
    }

    protected final void pushCallout(Object token) {
        StackEntry e = ensure1();
        e.type = CALLOUT;
        e.setCalloutToken(token);
        stk++;
    }

    protected final void pushControlMark(String previousName, String label, int position) {
        StackEntry e = ensure1();
        e.type = CONTROL_MARK;
        e.setControlMark(previousName, label, position);
        stk++;
    }

    protected final void pushDynamicAlternative(int returnAddress,
                                                ByteCodeMachine.DynamicContinuation continuation) {
        StackEntry e = ensure1();
        e.type = DYNAMIC_ALT;
        e.setStatePCode(returnAddress);
        e.setDynamicContinuation(continuation);
        stk++;
    }

    protected final void popOne() {
        stk--;
    }

    protected final StackEntry pop() {
        switch (regex.stackPopLevel) {
        case StackPopLevel.FREE:
            return popFree();
        case StackPopLevel.MEM_START:
            return popMemStart();
        default:
            return popDefault();
        }
    }

    private StackEntry popFree() {
        while (true) {
            StackEntry e = stack[--stk];
            if ((e.type & MASK_POP_USED) != 0) {
                return e;
            } else if (e.type == CALLOUT) {
                unwindCallout(e);
            } else if (e.type == CONTROL_MARK) {
                restoreControlMark(e.getPreviousControlMarkName());
            } else if (e.type == PHYSICAL_NAMED_CAPTURE) {
                restorePhysicalNamedCapture(e);
            } else if (e.type == REPEAT_CAPTURE_CLEAR) {
                restoreRepeatCaptureClear(e);
            } else if (USE_CEC) {
                if (e.type == STATE_CHECK_MARK) stateCheckMark();
            }
        }
    }

    private StackEntry popMemStart() {
        while (true) {
            StackEntry e = stack[--stk];
            if ((e.type & MASK_POP_USED) != 0) {
                return e;
            } else if (e.type == CALLOUT) {
                unwindCallout(e);
            } else if (e.type == CONTROL_MARK) {
                restoreControlMark(e.getPreviousControlMarkName());
            } else if (e.type == PHYSICAL_NAMED_CAPTURE) {
                restorePhysicalNamedCapture(e);
            } else if (e.type == REPEAT_CAPTURE_CLEAR) {
                restoreRepeatCaptureClear(e);
            } else if (e.type == MEM_START) {
                repeatStk[memStartStk + e.getMemNum()] = e.getMemStart();
                repeatStk[memEndStk + e.getMemNum()] = e.getMemEnd();
            } else if (USE_CEC) {
                if (e.type == STATE_CHECK_MARK) stateCheckMark();
            }
        }
    }

    private void popRewrite(StackEntry e) {
        if (e.type == CALLOUT) {
            unwindCallout(e);
        } else if (e.type == CONTROL_MARK) {
            restoreControlMark(e.getPreviousControlMarkName());
        } else if (e.type == PHYSICAL_NAMED_CAPTURE) {
            restorePhysicalNamedCapture(e);
        } else if (e.type == REPEAT_CAPTURE_CLEAR) {
            restoreRepeatCaptureClear(e);
        } else if (e.type == MEM_START) {
            repeatStk[memStartStk + e.getMemNum()] = e.getMemStart();
            repeatStk[memEndStk + e.getMemNum()] = e.getMemEnd();
        } else if (e.type == REPEAT_INC) {
            stack[e.getSi()].decreaseRepeatCount();
        } else if (e.type == MEM_END) {
            repeatStk[memStartStk + e.getMemNum()] = e.getMemStart();
            repeatStk[memEndStk + e.getMemNum()] = e.getMemEnd();
        } else if (USE_CEC) {
            if (e.type == STATE_CHECK_MARK) stateCheckMark();
        }
    }

    protected void restoreControlMark(String name) {
    }

    protected final int findControlMarkPosition(String name) {
        if (stack == null) return -1;
        for (int i = stk - 1; i >= 0; i--) {
            StackEntry entry = stack[i];
            if (entry.type == CONTROL_MARK && name.equals(entry.getControlMarkLabel())) {
                return entry.getControlMarkPosition();
            }
        }
        return -1;
    }

    protected final void unwindActiveCallouts() {
        if (stack == null) return;
        for (int i = stk - 1; i >= 0; i--) {
            if (stack[i].type == CALLOUT) unwindCallout(stack[i]);
            else if (stack[i].type == DYNAMIC_ALT) abortDynamic(stack[i]);
        }
    }

    protected final void completeActiveCallouts() {
        if (stack == null) return;
        for (int i = stk - 1; i >= 0; i--) {
            if (stack[i].type == CALLOUT) completeCallout(stack[i]);
            else if (stack[i].type == DYNAMIC_ALT) completeDynamic(stack[i]);
        }
    }

    /**
     * Remove resumable alternatives above the nearest matcher-program boundary.
     * THEN preserves the nearest alternation itself so a later failure enters
     * that branch; PRUNE, SKIP, and COMMIT remove it as well.
     */
    protected final void cutAlternatives(boolean preserveNearest) {
        cutAlternatives(preserveNearest, -1, -1, -1);
    }

    protected final void cutAlternatives(boolean preserveNearest,
                                         int current, int currentPrev, int currentKeep) {
        if (stack == null) return;
        boolean preserved = false;
        int callDepth = 0;
        // stack[0] is the matcher failure sentinel and must remain available
        // so the next failure exits matchAt normally.
        for (int i = stk - 1; i > 0; i--) {
            StackEntry entry = stack[i];
            if (entry.type == RETURN) {
                callDepth++;
                continue;
            }
            if (entry.type == CALL_FRAME) {
                if (callDepth > 0) {
                    callDepth--;
                    continue;
                }
                break;
            }
            if (callDepth == 0 && (entry.type == POS || entry.type == POS_NOT
                    || entry.type == LOOK_BEHIND_NOT)) {
                break;
            }
            if (entry.type != ALT && entry.type != BRANCH_ALT
                    && entry.type != DYNAMIC_ALT) continue;
            if (preserveNearest && !preserved
                    && (entry.type == BRANCH_ALT || entry.type == DYNAMIC_ALT)) {
                preserved = true;
                if (current >= 0) {
                    entry.setStatePStr(current);
                    entry.setStatePStrPrev(currentPrev);
                    entry.setPKeep(currentKeep);
                }
                continue;
            }
            if (entry.type == DYNAMIC_ALT) abortDynamic(entry);
            entry.type = VOID;
        }
    }

    private void completeCallout(StackEntry entry) {
        Object token = entry.takeCalloutToken();
        if (token != null) getCalloutHandler().complete(token);
    }

    private void unwindCallout(StackEntry entry) {
        Object token = entry.takeCalloutToken();
        if (token == null) return;
        if (completeCalloutsOnUnwind()) getCalloutHandler().complete(token);
        else getCalloutHandler().unwind(token);
    }

    /** Whether the current failure path commits callback side effects. */
    protected boolean completeCalloutsOnUnwind() {
        return false;
    }

    private void completeDynamic(StackEntry entry) {
        ByteCodeMachine.DynamicContinuation continuation = entry.takeDynamicContinuation();
        if (continuation != null) continuation.complete();
    }

    private void abortDynamic(StackEntry entry) {
        ByteCodeMachine.DynamicContinuation continuation = entry.takeDynamicContinuation();
        if (continuation != null) continuation.abort();
    }

    private StackEntry popDefault() {
        while (true) {
            StackEntry e = stack[--stk];
            if ((e.type & MASK_POP_USED) != 0) return e; else popRewrite(e);
        }
    }

    protected final StackEntry popTilPosNot() {
        while (true) {
            StackEntry e = stack[--stk];
            if (e.type == POS_NOT) return e;
            popRewrite(e);
        }
    }

    protected final void popTilLookBehindNot() {
        while (true) {
            StackEntry e = stack[--stk];
            if (e.type == LOOK_BEHIND_NOT) break; else popRewrite(e);
        }
    }

    protected final void popTilAbsent() {
        while (true) {
            StackEntry e = stack[--stk];
            if (e.type == ABSENT) break; else popRewrite(e);
        }
    }

    protected final int posEnd() {
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];
            if ((e.type & MASK_TO_VOID_TARGET) != 0) {
                e.type = VOID;
            } else if (e.type == POS) {
                e.type = VOID;
                break;
            }
        }
        return k;
    }

    protected final int savedPosition(int markerType) {
        for (int i = stk - 1; i >= 0; i--) {
            if (stack[i].type == markerType) return stack[i].getStatePStr();
        }
        return -1;
    }

    protected final int posNotEnd() {
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];
            if (e.type == POS_NOT) {
                e.type = VOID;
                break;
            } else if ((e.type & MASK_TO_VOID_TARGET) != 0) {
                e.type = VOID;
            }
        }
        return k;
    }

    protected final void cutAcceptBacktrackingAbove(int boundary) {
        for (int i = boundary + 1; i < stk; i++) {
            StackEntry entry = stack[i];
            if (entry.type == DYNAMIC_ALT) completeDynamic(entry);
            if ((entry.type & MASK_POP_USED) != 0) entry.type = VOID;
        }
    }

    protected final void stopBtEnd() {
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if ((e.type & MASK_TO_VOID_TARGET) != 0) {
                e.type = VOID;
            } else if (e.type == STOP_BT) {
                e.type = VOID;
                break;
            }
        }
    }

    // int for consistency with other null check routines
    protected final int nullCheck(int id, int s) {
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == NULL_CHECK_START) {
                if (e.getNullCheckNum() == id) {
                    return e.getNullCheckPStr() == s ? 1 : 0;
                }
            }
        }
    }

    protected final int nullCheckRec(int id, int s) {
        int level = 0;
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == NULL_CHECK_START) {
                if (e.getNullCheckNum() == id) {
                    if (level == 0) {
                        return e.getNullCheckPStr() == s ? 1 : 0;
                    } else {
                        level--;
                    }
                }
            } else if (e.type == NULL_CHECK_END) {
                level++;
            }
        }
    }

    protected final int nullCheckMemSt(int id, int s) {
        int k = stk;
        int isNull;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == NULL_CHECK_START) {
                if (e.getNullCheckNum() == id) {
                    if (e.getNullCheckPStr() != s) {
                        isNull = 0;
                        break;
                    } else {
                        int endp;
                        isNull = 1;
                        while (k < stk) {
                            e = stack[k++];
                            if (e.type == MEM_START) {
                                if (e.getMemEnd() == INVALID_INDEX) {
                                    isNull = 0;
                                    break;
                                }
                                if (bsAt(regex.btMemEnd, e.getMemNum())) {
                                    endp = stack[e.getMemEnd()].getMemPStr();
                                } else {
                                    endp = e.getMemEnd();
                                }
                                if (stack[e.getMemStart()].getMemPStr() != endp) {
                                    isNull = 0;
                                    break;
                                } else if (endp != s) {
                                    isNull = -1; /* empty, but position changed */
                                }
                            }
                        }
                        break;
                    }
                }
            }
        }
        return isNull;
    }

    protected final int nullCheckMemStRec(int id, int s) {
        int level = 0;
        int k = stk;
        int isNull;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == NULL_CHECK_START) {
                if (e.getNullCheckNum() == id) {
                    if (level == 0) {
                        if (e.getNullCheckPStr() != s) {
                            isNull = 0;
                            break;
                        } else {
                            int endp;
                            isNull = 1;
                            while (k < stk) {
                                if (e.type == MEM_START) {
                                    if (e.getMemEnd() == INVALID_INDEX) {
                                        isNull = 0;
                                        break;
                                    }
                                    if (bsAt(regex.btMemEnd, e.getMemNum())) {
                                        endp = stack[e.getMemEnd()].getMemPStr();
                                    } else {
                                        endp = e.getMemEnd();
                                    }
                                    if (stack[e.getMemStart()].getMemPStr() != endp) {
                                        isNull = 0;
                                        break;
                                    } else if (endp != s) {
                                        isNull = -1; /* empty, but position changed */
                                    }
                                }
                                k++;
                                e = stack[k];
                            }
                            break;
                        }
                    } else {
                        level--;
                    }
                }
            } else if (e.type == NULL_CHECK_END) {
                if (e.getNullCheckNum() == id) level++;
            }
        }
        return isNull;
    }

    protected final int getRepeat(int id) {
        int level = 0;
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == REPEAT) {
                if (level == 0) {
                    if (e.getRepeatNum() == id) return k;
                }
            } else if (e.type == CALL_FRAME) {
                level--;
            } else if (e.type == RETURN) {
                level++;
            }
        }
    }

    protected final int sreturn() {
        return returnFrame().getCallFrameRetAddr();
    }

    protected final StackEntry returnFrame() {
        int level = 0;
        int k = stk;
        while (true) {
            k--;
            StackEntry e = stack[k];

            if (e.type == CALL_FRAME) {
                if (level == 0) {
                    return e;
                } else {
                    level--;
                }
            } else if (e.type == RETURN) {
                level++;
            }
        }
    }
}
