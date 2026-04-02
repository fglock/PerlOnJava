package org.perlonjava.backend.jvm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.astnode.CompilerFlagNode;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.runtime.runtimetypes.GlobalContext;

public class EmitCompilerFlag {
    public static void emitCompilerFlag(EmitterContext ctx, CompilerFlagNode node) {
        ScopedSymbolTable currentScope = ctx.symbolTable;

        // Set the warning flags
        currentScope.warningFlagsStack.pop();
        currentScope.warningFlagsStack.push((java.util.BitSet) node.getWarningFlags().clone());

        // Set the fatal warning flags
        currentScope.warningFatalStack.pop();
        currentScope.warningFatalStack.push((java.util.BitSet) node.getWarningFatalFlags().clone());

        // Set the disabled warning flags
        currentScope.warningDisabledStack.pop();
        currentScope.warningDisabledStack.push((java.util.BitSet) node.getWarningDisabledFlags().clone());

        // Set the feature flags
        currentScope.featureFlagsStack.pop();
        currentScope.featureFlagsStack.push(node.getFeatureFlags());

        // Set the strict options
        currentScope.strictOptionsStack.pop();
        currentScope.strictOptionsStack.push(node.getStrictOptions());

        EmitterContext.fixupContext(ctx);

        // Emit runtime code to update per-call-site warning bits.
        // This allows caller()[9] to return accurate warning bits for the current
        // statement, not just the class-level bits captured at compilation time.
        MethodVisitor mv = ctx.mv;
        String newBits = currentScope.getWarningBitsString();
        mv.visitLdcInsn(newBits);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/WarningBitsRegistry",
                "setCallSiteBits",
                "(Ljava/lang/String;)V", false);

        // Emit runtime code to update per-call-site $^H (hints).
        // This allows caller()[8] to return accurate hints for the current statement.
        int hints = node.getStrictOptions();
        mv.visitLdcInsn(hints);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/WarningBitsRegistry",
                "setCallSiteHints",
                "(I)V", false);

        // Emit runtime code to update per-call-site hint hash snapshot ID.
        // This allows caller()[10] to return the compile-time %^H for the call site.
        int hintHashId = node.getHintHashSnapshotId();
        mv.visitLdcInsn(hintHashId);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/HintHashRegistry",
                "setCallSiteHintHashId",
                "(I)V", false);

        // Emit runtime code for warning scope if needed
        int warningScopeId = node.getWarningScopeId();
        if (warningScopeId > 0) {
            emitWarningScopeLocal(ctx, warningScopeId);
        }
    }

    /**
     * Emits bytecode for: local ${^WARNING_SCOPE} = scopeId
     * This uses DynamicVariableManager to save/restore the scope ID on block exit.
     */
    private static void emitWarningScopeLocal(EmitterContext ctx, int scopeId) {
        MethodVisitor mv = ctx.mv;

        // Mark that this subroutine uses local variables
        ctx.javaClassInfo.usesLocal = true;

        // Get the variable name for ${^WARNING_SCOPE}
        String varName = "main::" + GlobalContext.WARNING_SCOPE.substring("main::".length());

        // Call GlobalRuntimeScalar.makeLocal(varName) which:
        // 1. Gets or creates the global variable
        // 2. Saves its current value via DynamicVariableManager
        // 3. Returns the variable for assignment
        mv.visitLdcInsn(varName);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/GlobalRuntimeScalar",
                "makeLocal",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);

        // Assign the scope ID: .set(scopeId)
        mv.visitLdcInsn(scopeId);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                "set",
                "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);

        // Pop the result (void context)
        mv.visitInsn(Opcodes.POP);
    }
}
