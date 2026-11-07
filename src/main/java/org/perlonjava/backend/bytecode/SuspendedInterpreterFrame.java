package org.perlonjava.backend.bytecode;

import org.perlonjava.runtime.runtimetypes.RegexState;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.DynamicVariableManager;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.WeakRefRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

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
    final RuntimeScalar selfReference;

    int pc;
    int virtualEvalFrameDepth;
    Throwable propagatingException;
    Throwable resumeException;
    Set<RuntimeCode> returnedClosures;

    final ArrayDeque<Integer> evalCatchStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalMortalMarkDepthStack = new ArrayDeque<>();
    final ArrayDeque<Integer> evalScopeCleanupBatchDepthStack = new ArrayDeque<>();
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
    final ArrayDeque<ArrayList<Integer>> scopeCleanupBatches = new ArrayDeque<>();
    private final Map<Integer, PerlOwnerSlot> suspendedLexicalOwners = new HashMap<>();
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
        // ACTIVE_CODE owns this invocation. Borrow its CV value independently
        // of a mutable glob slot, which Perl may undefine during the call.
        this.selfReference = code.__SUB__ == null ? null : new RuntimeScalar(code.__SUB__);
    }

    /** Retain the current strong values in lexical registers across an await. */
    void retainSuspendedLexicalOwners() {
        BitSet myVars = code.myVarRegisters;
        int firstMyVarReg = 3 + (code.capturedVars != null ? code.capturedVars.length : 0);
        BitSet retainedRegisters = new BitSet(registers.length);
        for (int i = myVars.nextSetBit(firstMyVarReg);
             i >= 0 && i < registers.length; i = myVars.nextSetBit(i + 1)) {
            RuntimeBase registerValue = registers[i];
            if (registerValue == null) continue;
            PerlOwnerSlot owner = suspendedLexicalOwners.get(i);
            if (registerValue instanceof RuntimeScalar scalar) {
                if (owner != null && owner.suspendedScalarOwner() != scalar) {
                    releaseSuspendedOwnerForRegister(i);
                    owner = null;
                }
                if (owner == null) {
                    owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SUSPENDED_FRAME);
                    suspendedLexicalOwners.put(i, owner);
                }
                if (owner.suspendedScalarOwner() != scalar) {
                    scalar.attachSuspendedFrameOwner(owner);
                }
            } else {
                RuntimeBase referent = suspendedLexicalReferent(registerValue);
                if (referent == null) continue;
                if (owner != null && owner.suspendedScalarOwner() != null) {
                    releaseSuspendedOwnerForRegister(i);
                    owner = null;
                }
                if (owner == null) {
                    owner = new PerlOwnerSlot(PerlOwnerSlot.Kind.SUSPENDED_FRAME);
                    suspendedLexicalOwners.put(i, owner);
                }
                owner.acquireSuspendedFrameIdentity(referent);
            }
            retainedRegisters.set(i);
        }
        for (Integer register : new ArrayList<>(suspendedLexicalOwners.keySet())) {
            if (!retainedRegisters.get(register)) releaseSuspendedOwnerForRegister(register);
        }
    }

    /** Retire the suspended owner for one lexical as its scope exits. */
    void releaseSuspendedOwnerForRegister(int register) {
        PerlOwnerSlot owner = suspendedLexicalOwners.remove(register);
        if (owner == null) return;
        RuntimeBase previousReferent = owner.referent();
        RuntimeScalar scalarOwner = owner.suspendedScalarOwner();
        if (scalarOwner != null) scalarOwner.detachSuspendedFrameOwner(owner);
        else owner.release();
        WeakRefRegistry.onSuspendedFrameOwnerReleased(previousReferent);
    }

    /** Retire all remaining suspended-frame owners after completion or cancellation. */
    void releaseSuspendedLexicalOwners() {
        for (Integer register : new ArrayList<>(suspendedLexicalOwners.keySet())) {
            releaseSuspendedOwnerForRegister(register);
        }
    }

    private static RuntimeBase suspendedLexicalReferent(RuntimeBase value) {
        if (value instanceof RuntimeArray || value instanceof RuntimeHash) {
            return value.refCount == Integer.MIN_VALUE ? null : value;
        }
        return null;
    }
}
