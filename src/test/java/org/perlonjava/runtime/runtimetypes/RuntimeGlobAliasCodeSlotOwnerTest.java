package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeGlobAliasCodeSlotOwnerTest {
    @Test
    void aliasedStashNamesShareOneCountedSlotWithoutLegacyCounterMutation() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            GlobalVariable.resetAllGlobals();
            String sourceName = "RuntimeGlobAliasCodeSlotOwnerTest::source";
            String aliasName = "RuntimeGlobAliasCodeSlotOwnerTest::alias";
            RuntimeCode code = new RuntimeCode((args, context) -> new RuntimeList(), null);
            code.stashRefCount = 11;
            RuntimeScalar sourceSlot = new RuntimeScalar(code);
            GlobalVariable.globalCodeRefs.put(sourceName, sourceSlot);

            try {
                assertEquals(1, code.refCount);
                assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));

                RuntimeGlob.aliasCodeSlot(aliasName, sourceSlot);

                assertSame(sourceSlot, GlobalVariable.globalCodeRefs.get(aliasName));
                assertEquals(1, code.refCount,
                        "whole-glob aliases share the existing CODE scalar owner");
                assertEquals(1, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
                assertEquals(11, code.stashRefCount,
                        "the unused legacy counter does not participate in ownership");

                GlobalVariable.globalCodeRefs.remove(sourceName);
                assertEquals(1, code.refCount,
                        "removing one alias leaves the shared stash slot alive");
                GlobalVariable.globalCodeRefs.remove(aliasName);
                assertEquals(0, code.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            } finally {
                GlobalVariable.globalCodeRefs.remove(sourceName);
                GlobalVariable.globalCodeRefs.remove(aliasName);
                GlobalVariable.resetAllGlobals();
            }
        }
    }
}
