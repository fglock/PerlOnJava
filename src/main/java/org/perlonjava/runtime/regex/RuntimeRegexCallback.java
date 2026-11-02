package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

import java.util.function.Function;

/** A parser-created executable segment in a regex template. */
public final class RuntimeRegexCallback {
    public enum Kind {
        BLOCK,
        OPTIMISTIC,
        CONDITION,
        OPTIMISTIC_CONDITION,
        DYNAMIC;

        boolean isBlock() {
            return this == BLOCK || this == OPTIMISTIC;
        }
    }

    final RuntimeCode code;
    final Kind kind;
    final String sourceLocation;
    final String lexicalPackage;
    final String source;
    final boolean uninitializedWarningsEnabled;
    private int ownerCount;
    private PerlOwnerSlot codeOwnerSlot;

    private RuntimeRegexCallback(
            RuntimeCode code, Kind kind, String sourceLocation, String lexicalPackage,
            String source, boolean uninitializedWarningsEnabled) {
        this.code = code;
        this.kind = kind;
        this.sourceLocation = sourceLocation;
        this.lexicalPackage = lexicalPackage;
        this.source = source;
        this.uninitializedWarningsEnabled = uninitializedWarningsEnabled;
    }

    synchronized void retainOwner() {
        if (ownerCount != 0) {
            ownerCount++;
            return;
        }
        if (code.refCount != Integer.MIN_VALUE) {
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.REGEX_CALLBACK);
            slot.acquireNativeOwner(code);
            if (code.refCount >= 0) {
                if (code.refCount == Integer.MAX_VALUE) {
                    slot.release();
                    throw new IllegalStateException("regex callback owner count overflow");
                }
                code.traceRefCount(+1, "RuntimeRegexCallback.retainOwner");
                code.refCount++;
                code.hadCountedReference = true;
                slot.markCountedNativeOwner();
            }
            codeOwnerSlot = slot;
        }
        ownerCount = 1;
    }

    synchronized void releaseOwner() {
        if (ownerCount <= 0) return;
        ownerCount--;
        if (ownerCount != 0) return;
        PerlOwnerSlot slot = codeOwnerSlot;
        boolean countedOwner = slot != null && slot.isCountedNativeOwner();
        if (slot != null) {
            slot.release();
            codeOwnerSlot = null;
        }
        if (countedOwner && code.refCount > 0) {
            code.traceRefCount(-1, "RuntimeRegexCallback.releaseOwner");
            code.refCount--;
        }
        if (code.refCount != Integer.MIN_VALUE && code.stashRefCount <= 0
                && !code.hasPerlOwnerForCaptureOtherThan(null)) {
            code.releaseCaptures();
        }
    }

    /** Exact metadata copy for an ithread graph clone with a child-owned CODE pad. */
    public RuntimeRegexCallback cloneForThread(
            Function<RuntimeCode, RuntimeCode> codeCloner) {
        return new RuntimeRegexCallback(
                codeCloner.apply(code), kind, sourceLocation, lexicalPackage,
                source, uninitializedWarningsEnabled);
    }

    public static RuntimeScalar wrap(RuntimeScalar codeRef, String kindName) {
        return wrap(codeRef, kindName, null);
    }

    public static RuntimeScalar wrap(
            RuntimeScalar codeRef, String kindName, String lexicalPackage) {
        return wrap(codeRef, kindName, lexicalPackage, null);
    }

    public static RuntimeScalar wrap(
            RuntimeScalar codeRef, String kindName, String lexicalPackage, String source) {
        return wrap(codeRef, kindName, lexicalPackage, source, true);
    }

    public static RuntimeScalar wrap(
            RuntimeScalar codeRef, String kindName, String lexicalPackage, String source,
            boolean uninitializedWarningsEnabled) {
        if (!(codeRef.value instanceof RuntimeCode code)) {
            throw new IllegalArgumentException("regex callback is not a code reference");
        }
        code.isRegexCallbackPseudoBlock = true;
        if (lexicalPackage != null && !lexicalPackage.isEmpty()) {
            code.packageName = lexicalPackage;
        }
        String sourceLocation = code.cvStartFile != null && !code.cvStartFile.isEmpty()
                && code.cvStartLine > 0
                ? " at " + code.cvStartFile + " line " + code.cvStartLine
                : WarnDie.getPerlLocationFromStack();
        return new RuntimeScalar(new RuntimeRegexCallback(
                code, Kind.valueOf(kindName), sourceLocation,
                lexicalPackage != null ? lexicalPackage : code.packageName,
                source, uninitializedWarningsEnabled));
    }
}
