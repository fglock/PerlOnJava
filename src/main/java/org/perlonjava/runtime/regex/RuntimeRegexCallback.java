package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** A parser-created executable segment in a regex template. */
public final class RuntimeRegexCallback {
    public enum Kind { BLOCK, CONDITION, DYNAMIC }

    final RuntimeCode code;
    final Kind kind;

    private RuntimeRegexCallback(RuntimeCode code, Kind kind) {
        this.code = code;
        this.kind = kind;
    }

    public static RuntimeScalar wrap(RuntimeScalar codeRef, String kindName) {
        if (!(codeRef.value instanceof RuntimeCode code)) {
            throw new IllegalArgumentException("regex callback is not a code reference");
        }
        return new RuntimeScalar(new RuntimeRegexCallback(code, Kind.valueOf(kindName)));
    }
}
