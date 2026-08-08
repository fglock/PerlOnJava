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

    final ArrayDeque<Integer> evalCatchStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalLocalLevelStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalBaseRegStack = new ArrayDeque<>();
    final ArrayList<int[]> labeledBlockStack = new ArrayList<>();
    final ArrayDeque<RegexState> regexStateStack = new ArrayDeque<>();
    final ArrayList<RuntimeCode> createdClosures = new ArrayList<>();
    final ArrayDeque<ArrayList<Integer>> scopeCleanupBatches = new ArrayDeque<>();
    List<DynamicVariableManager.SuspendedState> suspendedDynamicStates;

    boolean suspended;
    RegexState suspendedRegexState;
    String suspendedPackage;
    String suspendedRuntimeWarningBits;

    SuspendedInterpreterFrame(InterpretedCode code, RuntimeBase[] registers,
                              int callContext, String subroutineName) {
        this.code = code;
        this.registers = registers;
        this.callContext = callContext;
        this.subroutineName = subroutineName;
    }
}
