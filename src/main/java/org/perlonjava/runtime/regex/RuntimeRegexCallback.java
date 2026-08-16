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

    private RuntimeRegexCallback(
            RuntimeCode code, Kind kind, String sourceLocation, String lexicalPackage) {
        this.code = code;
        this.kind = kind;
        this.sourceLocation = sourceLocation;
        this.lexicalPackage = lexicalPackage;
    }

    public static RuntimeScalar wrap(RuntimeScalar codeRef, String kindName) {
        return wrap(codeRef, kindName, null);
    }

    public static RuntimeScalar wrap(
            RuntimeScalar codeRef, String kindName, String lexicalPackage) {
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
                lexicalPackage != null ? lexicalPackage : code.packageName));
    }
}
