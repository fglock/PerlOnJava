package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class BoundRuntimeNamedCallExceptionUnwindTest {
    @Test
    void namedCallUnwindsLexicalsInTheRuntimeBoundToItsCode() {
        PerlRuntime callerRuntime = new PerlRuntime();
        PerlRuntime codeRuntime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        RuntimeScalar[] localSlot = new RuntimeScalar[1];
        RuntimeScalar callable;
        int codeRuntimeCleanupMark;

        try (PerlRuntime.Binding ignored = codeRuntime.bind()) {
            codeRuntimeCleanupMark = MyVarCleanupStack.pushMark();
            RuntimeCode code = new RuntimeCode((args, context) -> {
                RuntimeScalar local = new RuntimeScalar();
                local.set(referent.createAnonymousReference());
                localSlot[0] = local;
                MyVarCleanupStack.register(local);
                throw new PerlDieException(new RuntimeScalar("expected failure"));
            }, null).bindCallbackTo(codeRuntime);
            callable = new RuntimeScalar(code);
        }

        try {
            try (PerlRuntime.Binding ignored = callerRuntime.bind()) {
                assertThrows(PerlDieException.class,
                        () -> RuntimeCode.apply(
                                callable, "bound_runtime_named_call",
                                new RuntimeList(), RuntimeContextType.SCALAR));
            }

            try (PerlRuntime.Binding ignored = codeRuntime.bind()) {
                assertNotNull(localSlot[0]);
                assertFalse(MyVarCleanupStack.isRegistered(localSlot[0]),
                        "a named call must unwind lexicals in the code's bound runtime");
                assertEquals(codeRuntimeCleanupMark, MyVarCleanupStack.pushMark(),
                        "exception cleanup must restore the bound runtime's lexical stack");
                assertEquals(0, referent.refCount,
                        "the exceptional lexical must release its reference value");
            }
        } finally {
            try (PerlRuntime.Binding ignored = codeRuntime.bind()) {
                MyVarCleanupStack.unwindTo(codeRuntimeCleanupMark);
            }
        }
    }
}
