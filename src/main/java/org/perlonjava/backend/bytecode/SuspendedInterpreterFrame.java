package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RegexState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.DynamicVariableManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;

/**
 * Heap-owned execution state for an interpreter invocation that may cross an
 * {@code await} suspension point.
 *
 * <p>Ordinary interpreted calls also use this object. Keeping a single state
 * representation avoids a second async-only dispatch loop and makes every
 * newly-added interpreter stack explicit.</p>
 */
public final class SuspendedInterpreterFrame {
    final InterpretedCode code;
    final RuntimeBase[] registers;
    final int callContext;
    final String subroutineName;

    int pc;
    int virtualEvalFrameDepth;
    Throwable propagatingException;
    Throwable resumeException;
    Set<RuntimeCode> returnedClosures;

    // Eval and method-chain support are uncommon in ordinary interpreted
    // calls. Keep their stacks on the resumable frame, but allocate them only
    // when the corresponding opcode executes.
    ArrayDeque<Integer> evalCatchStack;
    ArrayDeque<Integer> evalLocalLevelStack;
    ArrayDeque<Integer> evalBaseRegStack;
    ArrayDeque<Integer> evalMethodInvocantHoldDepthStack;
    // Most interpreter frames never enter a labeled block or loop. Defer the
    // corresponding control-flow stacks until their PUSH opcode executes.
    ArrayList<int[]> labeledBlockStack;
    ArrayList<int[]> controlBlockStack;
    ArrayDeque<RegexState> regexStateStack;
    // Most interpreted calls do not create a closure. Allocate this ownership
    // tracker only for CREATE_CLOSURE so ordinary interpreter frames do not
    // carry an unused ArrayList.
    ArrayList<RuntimeCode> createdClosures;
    ArrayList<RuntimeBase> methodInvocantHolds;
    ArrayDeque<ArrayList<Integer>> scopeCleanupBatches;
    List<DynamicVariableManager.SuspendedState> suspendedDynamicStates;

    boolean suspended;
    RegexState suspendedRegexState;
    String suspendedPackage;
    String suspendedRuntimeWarningBits;
    Set<String> suspendedRuntimeDisabledWarningCategories;

    SuspendedInterpreterFrame(InterpretedCode code, RuntimeBase[] registers,
                              int callContext, String subroutineName) {
        this.code = code;
        this.registers = registers;
        this.callContext = callContext;
        this.subroutineName = subroutineName;
    }
}
