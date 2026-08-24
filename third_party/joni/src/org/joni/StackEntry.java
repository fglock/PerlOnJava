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

class StackEntry {
    int type;
    private int E1, E2, E3, E4;
    private Object calloutToken;
    private ByteCodeMachine.DynamicContinuation dynamicContinuation;
    private String previousControlMarkName;
    private String controlMarkLabel;
    private int[] callFrameCaptureSnapshot;
    private boolean callFrameRestoreCallerCaptures;
    private boolean callFrameRecursiveVisibility;

    // first union member
    /* byte code position */
    void setStatePCode(int pcode) {
        E1 = pcode;
    }
    int getStatePCode() {
        return E1;
    }
    /* string position */
    void setStatePStr(int pstr) {
        E2 = pstr;
    }
    int getStatePStr() {
        return E2;
    }
    /* previous char position of pstr */
    void setStatePStrPrev(int pstrPrev) {
        E3 = pstrPrev;
    }
    int getStatePStrPrev() {
        return E3;
    }

    void setPKeep(int pkeep) {
        E4 = pkeep;
    }
    int getPKeep() {
        return E4;
    }

    // second union member
    /* for OP_REPEAT_INC, OP_REPEAT_INC_NG */
    void setRepeatCount(int count) {
        E1 = count;
    }
    int getRepeatCount() {
        return E1;
    }
    void decreaseRepeatCount() {
        E1--;
    }
    void increaseRepeatCount() {
        E1++;
    }
    /* byte code position (head of repeated target) */
    void setRepeatPCode(int pcode) {
        E2 = pcode;
    }
    int getRepeatPCode() {
        return E2;
    }
    /* repeat id */
    void setRepeatNum(int num) {
        E3 = num;
    }
    int getRepeatNum() {
        return E3;
    }

    // third union member
    /* index of stack */ /*int repeat_inc struct*/
    void setSi(int si) {
        E1 = si;
    }
    int getSi() {
        return E1;
    }

    // fourth union member
    /* memory num */
    void setMemNum(int num) {
        E1 = num;
    }
    int getMemNum() {
        return E1;
    }
    /* start/end position */
    void setMemPstr(int pstr) {
        E2 = pstr;
    }
    int getMemPStr() {
        return E2;
    }

    /* Following information is set, if this stack type is MEM-START */
    /* prev. info (for backtrack  "(...)*" ) */
    void setMemStart(int start) {
        E3 = start;
    }
    int getMemStart() {
        return E3;
    }
    /* prev. info (for backtrack  "(...)*" ) */
    void setMemEnd(int end) {
        E4 = end;
    }
    int getMemEnd() {
        return E4;
    }

    void setPhysicalNamedCapture(int capture, int begin, int end) {
        E1 = capture;
        E2 = begin;
        E3 = end;
    }

    int getPhysicalNamedCapture() {
        return E1;
    }

    int getPhysicalNamedCaptureBegin() {
        return E2;
    }

    int getPhysicalNamedCaptureEnd() {
        return E3;
    }

    // fifth union member
    /* null check id */
    void setNullCheckNum(int num) {
        E1 = num;
    }
    int getNullCheckNum() {
        return E1;
    }
    /* start position */
    void setNullCheckPStr(int pstr) {
        E2 = pstr;
    }
    int getNullCheckPStr() {
        return E2;
    }

    // sixth union member
    /* byte code position */
    void setCallFrameRetAddr(int addr) {
        E1 = addr;
    }
    int getCallFrameRetAddr() {
        return E1;
    }
    /* null check id */
    void setCallFrameNum(int num) {
        E2 = num;
    }
    int getCallFrameNum() {
        return E2;
    }
    /* string position */
    void setCallFramePStr(int pstr) {
        E3 = pstr;
    }
    int getCallFramePStr() {
        return E3;
    }
    void setCallFrameCaptureSnapshot(int[] snapshot) {
        callFrameCaptureSnapshot = snapshot;
    }
    int[] getCallFrameCaptureSnapshot() {
        return callFrameCaptureSnapshot;
    }
    void setCallFrameRestoreCallerCaptures(boolean restore) {
        callFrameRestoreCallerCaptures = restore;
    }
    boolean getCallFrameRestoreCallerCaptures() {
        return callFrameRestoreCallerCaptures;
    }
    void setCallFrameRecursiveVisibility(boolean recursive) {
        callFrameRecursiveVisibility = recursive;
    }
    boolean getCallFrameRecursiveVisibility() {
        return callFrameRecursiveVisibility;
    }

    /* absent position */
    void setAbsentStr(int pos) {
        E1 = pos;
    }
    int getAbsentStr() {
        return E1;
    }

    void setAbsentEndStr(int pos) {
        E2 = pos;
    }
    int getAbsentEndStr() {
        return E2;
    }

    void setCalloutToken(Object token) {
        calloutToken = token;
    }

    void setCalloutProgress(int position) {
        E1 = position;
    }

    int getCalloutProgress() {
        return E1;
    }

    void setCalloutSamePositionCommit(boolean commit) {
        E2 = commit ? 1 : 0;
    }

    boolean getCalloutSamePositionCommit() {
        return E2 != 0;
    }

    Object takeCalloutToken() {
        Object token = calloutToken;
        calloutToken = null;
        return token;
    }

    void setDynamicContinuation(ByteCodeMachine.DynamicContinuation continuation) {
        dynamicContinuation = continuation;
    }

    ByteCodeMachine.DynamicContinuation takeDynamicContinuation() {
        ByteCodeMachine.DynamicContinuation continuation = dynamicContinuation;
        dynamicContinuation = null;
        return continuation;
    }

    void setControlMark(String previousName, String label, int position) {
        previousControlMarkName = previousName;
        controlMarkLabel = label;
        E1 = position;
    }

    String getPreviousControlMarkName() {
        return previousControlMarkName;
    }

    String getControlMarkLabel() {
        return controlMarkLabel;
    }

    int getControlMarkPosition() {
        return E1;
    }
}

final class SCStackEntry extends StackEntry {
    private int E5;

    void setStateCheck(int check) {
        E5 = check;
    }

    int getStateCheck() {
        return E5;
    }
}
