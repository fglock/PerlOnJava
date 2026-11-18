package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.perlmodule.Attributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeCodeAttributeOwnerTransitionTest {
    @Test
    void attributedClosureMovesItsStashOwnerFromPrototypeToCallableClone() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            String packageName = "RuntimeCodeAttributeOwnerTransition";
            String codeName = packageName + "::installed";
            String attributesName = packageName + "::MODIFY_CODE_ATTRIBUTES";

            RuntimeCode prototype = code();
            prototype.attributes = new ArrayList<>(List.of("OwnerTransition"));
            RuntimeScalar codeSlot = new RuntimeScalar(prototype);
            GlobalVariable.globalCodeRefs.put(codeName, codeSlot);
            GlobalVariable.globalCodeRefs.put(
                    attributesName, new RuntimeScalar(code()));

            PerlOwnerSlot owner = codeSlot.countedOwnerSlot();
            assertSame(prototype, owner.referent());
            assertEquals(1, prototype.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));

            try {
                Attributes.runtimeDispatchModifyCodeAttributes(
                        packageName, codeSlot, true);

                RuntimeCode clone = (RuntimeCode) codeSlot.value;
                assertSame(clone, owner.referent(),
                        "the existing CODE slot identity follows the replacement value");
                assertEquals(0, prototype.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
                assertEquals(1, clone.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            } finally {
                GlobalVariable.globalCodeRefs.remove(codeName);
                GlobalVariable.globalCodeRefs.remove(attributesName);
            }
        }
    }

    private static RuntimeCode code() {
        return new RuntimeCode((args, context) -> new RuntimeList(), null);
    }
}
