package org.perlonjava.codegen;

import org.perlonjava.astnode.CompilerFlagNode;
import org.perlonjava.symbols.ScopedSymbolTable;

public class EmitCompilerFlag {
    public static void emitCompilerFlag(EmitterContext ctx, CompilerFlagNode node) {
        ScopedSymbolTable currentScope = ctx.symbolTable;

        // Set the warning flags
        currentScope.warningFlagsStack.pop();
        currentScope.warningFlagsStack.push((java.util.BitSet) node.getWarningFlags().clone());

        // Set the feature flags
        currentScope.featureFlagsStack.pop();
        currentScope.featureFlagsStack.push(node.getFeatureFlags());

        // Set the strict options
        currentScope.strictOptionsStack.pop();
        currentScope.strictOptionsStack.push(node.getStrictOptions());

        EmitterContext.fixupContext(ctx);
    }
}
