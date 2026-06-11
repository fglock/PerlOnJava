package org.perlonjava.backend.bytecode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.backend.jvm.EmitterContext;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
public class BytecodeCompilerEvalContextTest {

    @Test
    void evalRuntimeContextLookupUsesPackedRuntimeValueIndex() throws Exception {
        String[] capturedEnv = new String[42];
        capturedEnv[0] = "this";
        capturedEnv[1] = "@_";
        capturedEnv[2] = "wantarray";
        for (int i = 3; i < 41; i++) {
            capturedEnv[i] = "$pad" + i;
        }
        capturedEnv[41] = "$target";

        Object[] runtimeValues = new Object[41];
        RuntimeScalar target = new RuntimeScalar("captured");
        runtimeValues[41 - 3] = target;

        RuntimeCode.EvalRuntimeContext evalCtx =
                new RuntimeCode.EvalRuntimeContext(runtimeValues, capturedEnv, "eval-test");

        Method push = RuntimeCode.class.getDeclaredMethod(
                "pushEvalRuntimeContext", RuntimeCode.EvalRuntimeContext.class);
        Method pop = RuntimeCode.class.getDeclaredMethod(
                "popEvalRuntimeContext", RuntimeCode.EvalRuntimeContext.class);
        push.setAccessible(true);
        pop.setAccessible(true);

        push.invoke(null, evalCtx);
        try {
            BytecodeCompiler compiler = new BytecodeCompiler("eval-test", 1);
            Method lookup = BytecodeCompiler.class.getDeclaredMethod(
                    "getVariableValueFromContext", String.class, EmitterContext.class);
            lookup.setAccessible(true);

            RuntimeBase value = (RuntimeBase) lookup.invoke(compiler, "$target", (EmitterContext) null);
            assertSame(target, value);
        } finally {
            pop.invoke(null, evalCtx);
        }
    }
}
