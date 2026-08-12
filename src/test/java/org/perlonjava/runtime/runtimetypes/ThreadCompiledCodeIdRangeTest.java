package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ThreadCompiledCodeIdRangeTest {
    @Test
    void childCompilationCannotCollideWithLateParentLazyCode() {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child;
        try (PerlRuntime.Binding ignored = parent.bind()) {
            parent.initialize();
            child = parent.snapshotClone();
        }

        RuntimeScalar childCode = new RuntimeScalar("child");
        int childId;
        try (PerlRuntime.Binding ignored = child.bind()) {
            childId = GlobalVariable.registerCompiledCodeRef(childCode);
        }

        RuntimeScalar lateParentCode = new RuntimeScalar("parent");
        int parentId;
        try (PerlRuntime.Binding ignored = parent.bind()) {
            parentId = GlobalVariable.registerCompiledCodeRef(lateParentCode);
        }

        assertNotEquals(parentId, childId);
        try (PerlRuntime.Binding ignored = child.bind()) {
            parent.globalState().snapshotCompiledCodeRefsInto(
                    child.globalState(), new RuntimeGraphCloner(parent, child));
            assertSame(childCode, GlobalVariable.getCompiledCodeRef(childId));
            assertSame(lateParentCode.value,
                    GlobalVariable.getCompiledCodeRef(parentId).value);
        }
    }
}
