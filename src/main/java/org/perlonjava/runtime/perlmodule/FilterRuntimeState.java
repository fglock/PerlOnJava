package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeList;

/** Runtime-owned source-filter stack and in-progress input cursor. */
public final class FilterRuntimeState {
    RuntimeList filterStack = new RuntimeList();
    String[] sourceLines;
    int currentLine;
    boolean inFilterRead;
    boolean installedDuringUse;

    void clear() {
        filterStack = new RuntimeList();
        sourceLines = null;
        currentLine = 0;
        inFilterRead = false;
        installedDuringUse = false;
    }
}
