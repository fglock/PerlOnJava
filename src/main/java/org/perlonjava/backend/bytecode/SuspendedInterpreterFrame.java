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

    // R45: evalCatchStack is null until EVAL_TRY executes; null means empty.
    // Invariant: when evalCatchStack is non-null and non-empty, all five EVAL-TRY stacks are non-null.
    ArrayDeque<Integer> evalCatchStack;
    // R44: stacks below are only needed when eval BLOCK (EVAL_TRY opcode) appears in the
    // eval body, or for scopeCleanupBatches when `my` vars are declared (MORTAL_PUSH_MARK).
    // Allocating them lazily on first push eliminates 6 ArrayDeque+backing-array pairs
    // (~960 bytes) for common eval-string calls that never use these features.
    ArrayDeque<Boolean> evalCallerFrameStack;
    ArrayDeque<int[]> evalControlBlockDepthStack;
    ArrayDeque<Integer> evalLocalLevelStack;
    ArrayDeque<Integer> evalBaseRegStack;
    ArrayDeque<Integer> evalMethodInvocantHoldDepthStack;
    ArrayDeque<ArrayList<Integer>> scopeCleanupBatches;
    // R45: remaining collections are null until first use. Simple evals that contain no
    // labeled loops, method calls, regex matches, or closures never touch these.
    ArrayList<int[]> labeledBlockStack;
    ArrayList<int[]> controlBlockStack;
    ArrayDeque<RegexState> regexStateStack;
    ArrayList<RuntimeCode> createdClosures;
    ArrayList<RuntimeBase> methodInvocantHolds;
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
