package org.perlonjava.backend.jvm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.ErrorMessageUtil;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class EvalBlockInterpreterFallbackTest extends PerlRuntimeTestBase {
    @Test
    void fallbackRetainsTheEvalCatcherAndResultContext() {
        for (int context : new int[]{RuntimeContextType.SCALAR, RuntimeContextType.LIST}) {
            EmitterContext ctx = new EmitterContext(new JavaClassInfo(),
                    new ScopedSymbolTable(), null, null, context, true,
                    new ErrorMessageUtil("fallback.t", List.of()),
                    new CompilerOptions(), new RuntimeArray());
            BlockNode body = new BlockNode(List.of(new OperatorNode("die",
                    new StringNode("fallback body failure\n", 0), 0)), 0);

            // Exercise the fallback factory directly, independent of whether a
            // particular generated JVM method currently requires fallback.
            var code = EmitterMethodCreator.compileToInterpreter(body, ctx, true);
            RuntimeList result = assertDoesNotThrow(() -> code.apply(new RuntimeArray(), context));
            assertEquals(context == RuntimeContextType.LIST ? 0 : 1, result.size());
            assertEquals("fallback body failure\n",
                    GlobalVariable.getGlobalVariable("main::@").toString());

            var successful = EmitterMethodCreator.compileToInterpreter(
                    new BlockNode(List.of(new StringNode("success", 0)), 0), ctx, true);
            assertEquals("success", successful.apply(new RuntimeArray(), context).getFirst().toString());
            assertEquals("", GlobalVariable.getGlobalVariable("main::@").toString());
        }
    }
}
