package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.backend.bytecode.InterpreterState;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ExecutionRuntimeStateTest {
    @Test
    void nestedBindingsKeepCallerDynamicAndEvalStateIndependent() {
        PerlRuntime outer = new PerlRuntime();
        PerlRuntime inner = new PerlRuntime();
        RuntimeScalar outerValue = new RuntimeScalar("outer");
        RuntimeScalar innerValue = new RuntimeScalar("inner");

        try (PerlRuntime.Binding ignored = outer.bind()) {
            CallerStack.push("Outer", "outer.pl", 10);
            DynamicVariableManager.pushLocalVariable(outerValue);
            outerValue.set("outer-local");
            RuntimeCode.incrementEvalDepth();
            InterpreterState.currentPackage.get().set("Outer");

            try (PerlRuntime.Binding nested = inner.bind()) {
                assertNull(CallerStack.peek(0));
                assertEquals(0, DynamicVariableManager.getLocalLevel());
                assertEquals(0, RuntimeCode.getEvalDepth());
                assertEquals("main", InterpreterState.currentPackage.get().toString());

                CallerStack.push("Inner", "inner.pl", 20);
                DynamicVariableManager.pushLocalVariable(innerValue);
                innerValue.set("inner-local");
                RuntimeCode.incrementEvalDepth();
                InterpreterState.currentPackage.get().set("Inner");
                DynamicVariableManager.popToLocalLevel(0);
                assertEquals("inner", innerValue.toString());
                assertEquals("Inner", CallerStack.pop().packageName());
                RuntimeCode.decrementEvalDepth();
            }

            assertEquals("Outer", CallerStack.peek(0).packageName());
            assertEquals(1, DynamicVariableManager.getLocalLevel());
            assertEquals(1, RuntimeCode.getEvalDepth());
            assertEquals("Outer", InterpreterState.currentPackage.get().toString());
            DynamicVariableManager.popToLocalLevel(0);
            assertEquals("outer", outerValue.toString());
            CallerStack.pop();
            RuntimeCode.decrementEvalDepth();
        }
    }

    @Test
    void outputSeparatorsAndSpecialQueuesBelongToRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();

        try (PerlRuntime.Binding ignored = first.bind()) {
            new OutputFieldSeparator().set("first-field");
            new OutputRecordSeparator().set("first-record");
            SpecialBlock.saveEndBlock(new RuntimeScalar());
            assertEquals(1, SpecialBlock.getEndBlocks().size());
        }
        try (PerlRuntime.Binding ignored = second.bind()) {
            assertEquals("", OutputFieldSeparator.getInternalOFS());
            assertEquals("", OutputRecordSeparator.getInternalORS());
            assertTrue(SpecialBlock.getEndBlocks().isEmpty());
            new OutputFieldSeparator().set("second-field");
        }
        try (PerlRuntime.Binding ignored = first.bind()) {
            assertEquals("first-field", OutputFieldSeparator.getInternalOFS());
            assertEquals("first-record", OutputRecordSeparator.getInternalORS());
            assertEquals(1, SpecialBlock.getEndBlocks().size());
        }
    }

    @Test
    void boundJavaCallbackRestoresRuntimeOnForeignThread() throws Exception {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeCode callback;
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            callback = new RuntimeCode((args, context) -> {
                assertSame(runtime, PerlRuntime.current());
                return new RuntimeList(new RuntimeScalar(42));
            }, null).bindCallbackTo(runtime);
        }

        FutureTask<Integer> task = new FutureTask<>(() -> {
            assertNull(PerlRuntime.currentOrNull());
            int result = callback.apply(new RuntimeArray(), RuntimeContextType.SCALAR).scalar().getInt();
            assertNull(PerlRuntime.currentOrNull());
            return result;
        });
        Thread worker = new Thread(task, "phase5-callback-test");
        worker.start();
        assertEquals(42, task.get(10, TimeUnit.SECONDS));
        worker.join(10_000);
        assertFalse(worker.isAlive());
    }
}
