package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.PerlOwnerSlot;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    private int unassociatedOwnerCount;
    private PerlOwnerSlot codeOwnerSlot;
    /** Regex owners are provenance only; the callback must not keep them alive. */
    private final List<RegexOwnerReference> regexOwners = new ArrayList<>();

    private static final class RegexOwnerReference {
        final WeakReference<RuntimeRegex> owner;
        int references = 1;

        RegexOwnerReference(RuntimeRegex owner) {
            this.owner = new WeakReference<>(owner);
        }
    }

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
        retainOwnerReference(null);
        unassociatedOwnerCount++;
    }

    synchronized void retainOwner(RuntimeRegex owner) {
        if (owner == null) return;
        retainOwnerReference(owner);
    }

    private void retainOwnerReference(RuntimeRegex owner) {
        if (ownerCount == 0 && code.refCount != Integer.MIN_VALUE) {
            PerlOwnerSlot slot = new PerlOwnerSlot(PerlOwnerSlot.Kind.REGEX_CALLBACK);
            slot.acquireCountedNativeOwner(code, "RuntimeRegexCallback.retainOwner");
            codeOwnerSlot = slot;
        }
        ownerCount++;
        if (owner != null) {
            RegexOwnerReference ownerReference = findOwner(owner);
            if (ownerReference == null) {
                regexOwners.add(new RegexOwnerReference(owner));
            } else {
                ownerReference.references++;
            }
            if (codeOwnerSlot != null) {
                codeOwnerSlot.associateEndBoundaryOwnerSource(owner);
            }
        }
    }

    synchronized void releaseOwner() {
        if (unassociatedOwnerCount <= 0) return;
        unassociatedOwnerCount--;
        releaseOwnerReference(null);
    }

    synchronized void releaseOwner(RuntimeRegex owner) {
        if (ownerCount <= 0 || owner == null) return;
        RegexOwnerReference ownerReference = findOwner(owner);
        if (ownerReference == null) return;
        if (--ownerReference.references == 0) regexOwners.remove(ownerReference);
        releaseOwnerReference(owner);
    }

    private void releaseOwnerReference(RuntimeRegex owner) {
        ownerCount--;
        PerlOwnerSlot slot = codeOwnerSlot;
        if (slot != null && owner != null) slot.releaseEndBoundaryOwnerSource(owner);
        if (ownerCount != 0) return;
        if (slot != null) {
            codeOwnerSlot = null;
            slot.releaseRegexCallbackOwner("RuntimeRegexCallback.releaseOwner");
        }
        if (code.refCount != Integer.MIN_VALUE
                && !code.hasPerlOwnerForCaptureOtherThan(null)) {
            code.releaseCaptures();
        }
    }

    /** Find an owner's weak provenance record, pruning owners already collected. */
    private RegexOwnerReference findOwner(RuntimeRegex owner) {
        RegexOwnerReference found = null;
        for (Iterator<RegexOwnerReference> iterator = regexOwners.iterator();
                iterator.hasNext();) {
            RegexOwnerReference reference = iterator.next();
            RuntimeRegex current = reference.owner.get();
            if (current == null) iterator.remove();
            else if (current == owner) found = reference;
        }
        return found;
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
