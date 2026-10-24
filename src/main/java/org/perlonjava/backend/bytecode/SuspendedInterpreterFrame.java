package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RegexState;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.DynamicVariableManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
    final int frameCleanupMark;

    int pc;
    int virtualEvalFrameDepth;
    Throwable propagatingException;
    Throwable resumeException;
    Set<RuntimeCode> returnedClosures;

    final ArrayDeque<Integer> evalCatchStack = new ArrayDeque<>();
    final ArrayDeque<Boolean> evalCallerFrameStack = new ArrayDeque<>();
    final ArrayDeque<int[]> evalControlBlockDepthStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalLocalLevelStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalBaseRegStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalMethodInvocantHoldDepthStack = new ArrayDeque<>();
    final ArrayList<int[]> labeledBlockStack = new ArrayList<>();
    final ArrayList<int[]> controlBlockStack = new ArrayList<>();
    final ArrayDeque<RegexState> regexStateStack = new ArrayDeque<>();
    final ArrayList<RuntimeCode> createdClosures = new ArrayList<>();
    final ArrayList<RuntimeBase> methodInvocantHolds = new ArrayList<>();
    // SET_SCALAR can allocate expression-only cells that own the referent
    // inside a scalar reference. They are not lexical registers, so emitted
    // SCOPE_EXIT_CLEANUP opcodes do not see them.
    final Set<RuntimeScalar> temporaryScalarOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    final ArrayDeque<ArrayList<Integer>> scopeCleanupBatches = new ArrayDeque<>();
    List<DynamicVariableManager.SuspendedState> suspendedDynamicStates;

    boolean suspended;
    RegexState suspendedRegexState;
    String suspendedPackage;
    String suspendedRuntimeWarningBits;
    Set<String> suspendedRuntimeDisabledWarningCategories;

    SuspendedInterpreterFrame(InterpretedCode code, RuntimeBase[] registers,
                              int callContext, String subroutineName,
                              int frameCleanupMark) {
        this.code = code;
        this.registers = registers;
        this.callContext = callContext;
        this.subroutineName = subroutineName;
        this.frameCleanupMark = frameCleanupMark;
    }
}
