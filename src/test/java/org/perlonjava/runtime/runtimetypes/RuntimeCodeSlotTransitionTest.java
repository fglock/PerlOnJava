package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeCodeSlotTransitionTest {
    @Test
    void lexicalForwardPlaceholderPromotionRecordsTheScalarOwner() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeScalar lexicalCell = new RuntimeScalar();
            lexicalCell.lexicalSubName = "forward";
            lexicalCell.lexicalSubPackageName = "CodeOwner";
            MyVarCleanupStack.register(lexicalCell);

            try {
                RuntimeScalar reference = RuntimeCode.createCodeReference(lexicalCell, "CodeOwner");
                RuntimeCode placeholder = (RuntimeCode) reference.value;

                assertSame(lexicalCell, reference);
                assertNotNull(lexicalCell.untrackedReferenceOwnerSlot());
                assertSame(placeholder, lexicalCell.untrackedReferenceOwnerSlot().referent());
                assertEquals(1, placeholder.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));

                lexicalCell.set(new RuntimeScalar());
                assertEquals(0, placeholder.ownerSlotCount(PerlOwnerSlot.Kind.UNTRACKED_REFERENCE));
            } finally {
                MyVarCleanupStack.unregister(lexicalCell);
            }
        }
    }

    @Test
    void forwardGlobAliasMovesTheSourceCellOwnerToTheTargetPlaceholder() {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            GlobalVariable.resetAllGlobals();
            String sourceName = "CodeOwner::forward";
            String targetName = "CodeOwner::target";
            RuntimeCode sourceCode = undefinedCode();
            sourceCode.lexicalForwardGlobPlaceholder = true;
            sourceCode.hasForwardGlobAlias = true;
            sourceCode.requiresForwardGlobAliasGroup = true;
            sourceCode.forwardGlobAliases.add(sourceCode);
            RuntimeScalar sourceSlot = new RuntimeScalar(sourceCode);
            GlobalVariable.globalCodeRefs.put(sourceName, sourceSlot);

            RuntimeCode targetCode = undefinedCode();
            RuntimeScalar targetSlot = new RuntimeScalar(targetCode);
            GlobalVariable.globalCodeRefs.put(targetName, targetSlot);

            assertEquals(1, sourceCode.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            new RuntimeGlob(targetName).set(sourceSlot);

            assertSame(targetCode, sourceSlot.value);
            assertSame(targetCode, sourceSlot.countedOwnerSlot().referent());
            assertEquals(0, sourceCode.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            assertEquals(2, targetCode.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            GlobalVariable.resetAllGlobals();
        }
    }

    @Test
    void constructorPredeclarationTransitionsAnExistingEmptyCodeCell() throws Exception {
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            GlobalVariable.resetAllGlobals();
            String name = "CodeOwner::Widget::new";
            RuntimeScalar codeCell = new RuntimeScalar();
            GlobalVariable.globalCodeRefs.put(name, codeCell);

            Method predeclare = org.perlonjava.frontend.parser.ClassTransformer.class
                    .getDeclaredMethod("predeclareConstructor", String.class);
            predeclare.setAccessible(true);
            predeclare.invoke(null, "CodeOwner::Widget");

            RuntimeCode constructor = (RuntimeCode) codeCell.value;
            assertNotNull(codeCell.countedOwnerSlot());
            assertSame(constructor, codeCell.countedOwnerSlot().referent());
            assertEquals(1, constructor.ownerSlotCount(PerlOwnerSlot.Kind.CODE_SLOT));
            GlobalVariable.resetAllGlobals();
        }
    }

    private static RuntimeCode undefinedCode() {
        return new RuntimeCode((String) null, null);
    }
}
