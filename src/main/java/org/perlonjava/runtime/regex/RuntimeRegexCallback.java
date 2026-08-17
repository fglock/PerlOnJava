package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.operators.WarnDie;

/** A parser-created executable segment in a regex template. */
public final class RuntimeRegexCallback {
    public enum Kind { BLOCK, CONDITION, DYNAMIC }

    final RuntimeCode code;
    final Kind kind;
    final String sourceLocation;
    final String lexicalPackage;
    final String source;
    private int ownerCount;

    private RuntimeRegexCallback(
            RuntimeCode code, Kind kind, String sourceLocation, String lexicalPackage,
            String source) {
        this.code = code;
        this.kind = kind;
        this.sourceLocation = sourceLocation;
        this.lexicalPackage = lexicalPackage;
        this.source = source;
    }

    synchronized void retainOwner() {
        if (code.refCount < 0) return;
        if (ownerCount++ == 0) {
            code.refCount++;
        }
    }

    synchronized void releaseOwner() {
        if (code.refCount < 0) return;
        if (ownerCount <= 0) return;
        ownerCount--;
        if (ownerCount != 0) return;
        if (code.refCount > 0) code.refCount--;
        if (code.refCount == 0 && code.stashRefCount <= 0) {
            code.releaseCaptures();
        }
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
                source));
    }
}
