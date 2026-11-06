package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class TailCallCodeRefOwnerSlotTest {
    @Test
    void tailCallTransferAcquiresAndReleasesAnArgumentOwner() {
        PerlRuntime runtime = new PerlRuntime();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            boolean previousMortalListState = MortalList.isActive();
            try {
                MortalList.setActive(true);
                RuntimeCode target = new RuntimeCode((args, context) -> new RuntimeList(), null);
                target.refCount = 0;
                RuntimeScalar source = new RuntimeScalar();
                source.type = RuntimeScalarType.CODE;
                source.value = target;

                RuntimeControlFlowList transfer = new RuntimeControlFlowList(
                        source, new RuntimeArray(), "tailcall-owner-test", 1);
                RuntimeScalar retainedCodeRef = transfer.getTailCallCodeRef();

                assertEquals(1, target.refCount);
                assertEquals(1, target.ownerSlotCount(PerlOwnerSlot.Kind.ARGUMENT_VALUE));
                assertEquals(0, target.ownerSlotCount(PerlOwnerSlot.Kind.SCALAR_CELL));

                MortalList.releaseTailCallCodeRef(retainedCodeRef);

                assertFalse(retainedCodeRef.refCountOwned);
                assertEquals(0, target.countedOwnerSlotCount());
            } finally {
                MortalList.setActive(previousMortalListState);
            }
        }
    }
}
