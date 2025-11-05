package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    public static void emitFor1(EmitterVisitor emitterVisitor, For1Node node) {
        emitterVisitor.ctx.logDebug("FOR1 start");

        // Check if the loop variable is a complex lvalue expression like $$f
        // If so, emit as while loop with explicit assignment
        if (node.variable instanceof OperatorNode opNode &&
                opNode.operand instanceof OperatorNode nestedOpNode &&
                opNode.operator.equals("$") && nestedOpNode.operator.equals("$")) {

            emitterVisitor.ctx.logDebug("FOR1 emitting complex lvalue $$var as while loop");
            emitFor1AsWhileLoop(emitterVisitor, node);
            return;
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Allocate local variable for iterator (will be used throughout the loop)
        int iteratorVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();

        // Check if the variable is global
        boolean loopVariableIsGlobal = false;
        String globalVarName = null;
        if (node.variable instanceof OperatorNode opNode && opNode.operator.equals("$")) {
            if (opNode.operand instanceof IdentifierNode idNode) {
                String varName = opNode.operator + idNode.name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                if (varIndex == -1) {
                    loopVariableIsGlobal = true;
                    globalVarName = idNode.name;
                }
            }
        }

        // First declare the variables if it's a my/our operator
        if (node.variable instanceof OperatorNode opNode &&
                (opNode.operator.equals("my") || opNode.operator.equals("our"))) {
            boolean isWarningEnabled = Warnings.warningManager.isWarningEnabled("redefine");
            if (isWarningEnabled) {
                // turn off "masks earlier declaration" warning
                Warnings.warningManager.setWarningState("redefine", false);
            }
            // emit the variable declarations
            node.variable.accept(emitterVisitor.with(RuntimeContextType.VOID));
            // rewrite the variable node without the declaration
            node.variable = opNode.operand;

            if (isWarningEnabled) {
                // restore warnings
                Warnings.warningManager.setWarningState("redefine", true);
            }

            // Reset global variable check after rewriting
            loopVariableIsGlobal = false;
        }

        // For global $_ as loop variable, we need to:
        // 1. Evaluate the list first (before any localization takes effect)
        // 2. For statement modifiers: localize $_ ourselves
        // 3. Iterate over the pre-evaluated list
        //
        // This handles cases like: for (split(//, $_)) where the outer context
        // may have already localized $_, making it undef when we evaluate the list.
        boolean isStatementModifier = !node.useNewScope;
        boolean isGlobalUnderscore = node.needsArrayOfAlias || 
                                     (loopVariableIsGlobal && globalVarName != null && 
                                      (globalVarName.equals("main::_") || globalVarName.endsWith("::_")));
        boolean needLocalizeUnderscore = isStatementModifier && loopVariableIsGlobal && globalVarName != null && 
                                         (globalVarName.equals("main::_") || globalVarName.endsWith("::_"));
        
        // Allocate variable to track dynamic variable stack level for our localization
        int dynamicIndex = -1;
        if (needLocalizeUnderscore) {
            dynamicIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            // Get the current level of the dynamic variable stack and store it
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "getLocalLevel",
                    "()I",
                    false);
            mv.visitVarInsn(Opcodes.ISTORE, dynamicIndex);
        }
        
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Check if the list was pre-evaluated by EmitBlock (for nested for loops with local $_)
        if (node.preEvaluatedArrayIndex >= 0) {
            // Use the pre-evaluated array that was stored before local $_ was emitted
            mv.visitVarInsn(Opcodes.ALOAD, node.preEvaluatedArrayIndex);
            
            // For statement modifiers, localize $_ ourselves
            if (needLocalizeUnderscore) {
                mv.visitLdcInsn(globalVarName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalRuntimeScalar",
                        "makeLocal",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                mv.visitInsn(Opcodes.POP);  // Discard the returned scalar
            }
            
            // Get iterator from the pre-evaluated array
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "iterator", "()Ljava/util/Iterator;", false);
        } else if (isGlobalUnderscore) {
            // Global $_ as loop variable: evaluate list to array of aliases first
            // This preserves aliasing semantics while ensuring list is evaluated before any
            // parent block's local $_ takes effect (e.g., in nested for loops)
            node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
            
            // For statement modifiers, localize $_ ourselves
            if (needLocalizeUnderscore) {
                mv.visitLdcInsn(globalVarName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalRuntimeScalar",
                        "makeLocal",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                mv.visitInsn(Opcodes.POP);  // Discard the returned scalar
            }
            
            // Get iterator from the array of aliases
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "iterator", "()Ljava/util/Iterator;", false);
        } else {
            // Standard path: obtain iterator for the list
            node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);
        }

        // Store iterator in local variable before entering loop
        mv.visitVarInsn(Opcodes.ASTORE, iteratorVar);

        mv.visitLabel(loopStart);

        // Load iterator from local variable
        mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label normalExit = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, normalExit);

        // Handle multiple variables case
        if (node.variable instanceof ListNode varList) {
            for (int i = 0; i < varList.elements.size(); i++) {
                // Duplicate iterator
                mv.visitInsn(Opcodes.DUP);

                // Check if iterator has more elements
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                Label hasValueLabel = new Label();
                Label endValueLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, hasValueLabel);

                // No more elements - assign undef
                mv.visitInsn(Opcodes.POP); // Pop the iterator copy
                EmitOperator.emitUndef(mv);
                mv.visitJumpInsn(Opcodes.GOTO, endValueLabel);

                // Has more elements - get next value
                mv.visitLabel(hasValueLabel);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

                mv.visitLabel(endValueLabel);

                // Assign to variable
                Node varNode = varList.elements.get(i);
                if (varNode instanceof OperatorNode operatorNode) {
                    String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                    int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                    emitterVisitor.ctx.logDebug("FOR1 multi var name:" + varName + " index:" + varIndex);
                    mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                }
            }
        } else {
            // Original single variable case
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

            if (loopVariableIsGlobal) {
                // Regular global variable assignment
                mv.visitLdcInsn(globalVarName);
                mv.visitInsn(Opcodes.SWAP); // Stack: globalVarName, iteratorValue
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
                        "aliasGlobalVariable",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeScalar;)V",
                        false);
            } else if (node.variable instanceof OperatorNode operatorNode) {
                // Local variable case
                String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                emitterVisitor.ctx.logDebug("FOR1 single var name:" + varName + " index:" + varIndex);
                mv.visitVarInsn(Opcodes.ASTORE, varIndex);
            }
        }

        // Pop the iterator from stack (it was DUPed earlier for next() call)
        mv.visitInsn(Opcodes.POP);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd,  // Local last goes directly to loopEnd (stack is empty)
                RuntimeContextType.VOID);

        // Always wrap loop body in try-catch to handle non-local jumps from subroutines
        // Both labeled and unlabeled loops need this since Perl allows `last;` from a subroutine
        {

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchLast = new Label();
            Label catchNext = new Label();
            Label catchRedo = new Label();

            // Register exception handlers
            mv.visitTryCatchBlock(tryStart, tryEnd, catchLast, "org/perlonjava/runtime/LastException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchNext, "org/perlonjava/runtime/NextException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchRedo, "org/perlonjava/runtime/RedoException");

            // Try block - execute loop body (stack is empty, iterator is in local variable)
            mv.visitLabel(tryStart);
            // Emit NOP to ensure try-catch range is valid even if body emits no bytecode
            mv.visitInsn(Opcodes.NOP);
            node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));
            mv.visitLabel(tryEnd);
            // Jump to continueLabel (no need to load iterator, continueLabel will do it)
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

            // Catch LastException - check if label matches, then pop exception, load iterator, jump to loopEnd
            mv.visitLabel(catchLast);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowLast = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowLast);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Don't load iterator - loopEnd will do it
            mv.visitJumpInsn(Opcodes.GOTO, loopEnd);
            mv.visitLabel(rethrowLast);
            mv.visitInsn(Opcodes.ATHROW);

            // Catch NextException - check if label matches, then pop exception, load iterator, jump to continueLabel
            mv.visitLabel(catchNext);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowNext = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowNext);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Iterator stays in local variable - continueLabel will load it
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);
            mv.visitLabel(rethrowNext);
            mv.visitInsn(Opcodes.ATHROW);

            // Catch RedoException - check if label matches, then pop exception, load iterator, jump to redoLabel
            mv.visitLabel(catchRedo);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowRedo = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowRedo);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Iterator stays in local variable - redoLabel will load it
            mv.visitJumpInsn(Opcodes.GOTO, redoLabel);
            mv.visitLabel(rethrowRedo);
            mv.visitInsn(Opcodes.ATHROW);
        }

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // Normal exit: iterator is on stack, pop it
        mv.visitLabel(normalExit);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(loopEnd);
        
        // Restore dynamic variable stack for our localization
        if (needLocalizeUnderscore && dynamicIndex != -1) {
            mv.visitVarInsn(Opcodes.ILOAD, dynamicIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/DynamicVariableManager",
                    "popToLocalLevel",
                    "(I)V",
                    false);
        }
        
        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        // Iterator is in local variable - no need to pop from stack

        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // Foreach loop returns empty string when it completes normally
            // This is different from an empty list in scalar context (which would be undef)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR || emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                // Return empty string
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarCache",
                        "scalarEmptyString", "Lorg/perlonjava/runtime/RuntimeScalarReadOnly;");
            } else {
                // LIST context: Return empty list
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
            }
        }

        emitterVisitor.ctx.logDebug("FOR1 end");
    }

    private static void emitFor1AsWhileLoop(EmitterVisitor emitterVisitor, For1Node node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();

        // Allocate local variable for iterator
        int iteratorVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);

        // Store iterator in local variable
        mv.visitVarInsn(Opcodes.ASTORE, iteratorVar);

        mv.visitLabel(loopStart);

        // Load iterator from local variable
        mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label normalExitWhile = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, normalExitWhile);

        // Get next value
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

        // Assign to variable $$f
        node.variable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: iteratorValue, dereferenced_var
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        mv.visitInsn(Opcodes.POP);

        // Pop the iterator from stack (it was DUPed earlier for next() call)
        mv.visitInsn(Opcodes.POP);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        Label continueLabel = new Label();
        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        // Always wrap loop body in try-catch to handle non-local jumps from subroutines
        // Both labeled and unlabeled loops need this since Perl allows `last;` from a subroutine
        {

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchLast = new Label();
            Label catchNext = new Label();
            Label catchRedo = new Label();

            // Register exception handlers
            mv.visitTryCatchBlock(tryStart, tryEnd, catchLast, "org/perlonjava/runtime/LastException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchNext, "org/perlonjava/runtime/NextException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchRedo, "org/perlonjava/runtime/RedoException");

            // Try block - execute loop body (stack is empty, iterator is in local variable)
            mv.visitLabel(tryStart);
            // Emit NOP to ensure try-catch range is valid even if body emits no bytecode
            mv.visitInsn(Opcodes.NOP);
            node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));
            mv.visitLabel(tryEnd);
            // Jump to continueLabel (iterator stays in local variable)
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

            // Catch LastException - check if label matches, then pop exception and jump to loopEnd
            mv.visitLabel(catchLast);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowLast = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowLast);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Iterator stays in local variable
            mv.visitJumpInsn(Opcodes.GOTO, loopEnd);
            mv.visitLabel(rethrowLast);
            mv.visitInsn(Opcodes.ATHROW);

            // Catch NextException - check if label matches, then pop exception, load iterator, jump to continueLabel
            mv.visitLabel(catchNext);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowNext = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowNext);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Iterator stays in local variable - continueLabel will load it
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);
            mv.visitLabel(rethrowNext);
            mv.visitInsn(Opcodes.ATHROW);

            // Catch RedoException - check if label matches, then pop exception, load iterator, jump to redoLabel
            mv.visitLabel(catchRedo);
            mv.visitInsn(Opcodes.DUP);
            if (node.labelName != null) {
                mv.visitLdcInsn(node.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/PerlControlFlowException", "matchesLabel", "(Ljava/lang/String;)Z", false);
            Label rethrowRedo = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, rethrowRedo);
            mv.visitInsn(Opcodes.POP);  // Pop exception
            // Iterator stays in local variable - redoLabel will load it
            mv.visitJumpInsn(Opcodes.GOTO, redoLabel);
            mv.visitLabel(rethrowRedo);
            mv.visitInsn(Opcodes.ATHROW);
        }

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // Normal exit: iterator is on stack, pop it
        mv.visitLabel(normalExitWhile);
        mv.visitInsn(Opcodes.POP);

        mv.visitLabel(loopEnd);

        // Iterator is in local variable - no stack cleanup needed

        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            // Foreach loop returns empty string when it completes normally
            // This is different from an empty list in scalar context (which would be undef)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR || emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                // Return empty string
                mv.visitFieldInsn(Opcodes.GETSTATIC, "org/perlonjava/runtime/RuntimeScalarCache",
                        "scalarEmptyString", "Lorg/perlonjava/runtime/RuntimeScalarReadOnly;");
            } else {
                // LIST context: Return empty list
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/RuntimeList", "<init>", "()V", false);
            }
        }
    }
}
