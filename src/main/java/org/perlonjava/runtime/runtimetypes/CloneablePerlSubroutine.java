package org.perlonjava.runtime.runtimetypes;

/** Explicit capture metadata implemented by generated JVM closures. */
public interface CloneablePerlSubroutine extends PerlSubroutine {
    RuntimeBase[] capturedValues();

    CloneablePerlSubroutine cloneWithCaptures(RuntimeBase[] captures);

    void setSelfReference(RuntimeScalar selfReference);
}
