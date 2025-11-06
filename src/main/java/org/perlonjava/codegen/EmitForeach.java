package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    // Feature flag to enable/disable exception handlers for non-local control flow in loops
    // Must be true for skip/last/next/redo to work across subroutine boundaries
    private static final boolean ENABLE_LOOP_EXCEPTION_HANDLERS = true;
    
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

        // Create synthetic label for unlabeled loops to unify exception handling
        // This allows us to use a single implementation path for all loops
        String effectiveLabelName = node.labelName;
        if (effectiveLabelName == null) {
            effectiveLabelName = "__ANON_LOOP_" + System.identityHashCode(node);
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Allocate local variable for iterator (will be used throughout the loop)
        // This avoids keeping the iterator on the operand stack, which causes VerifyErrors
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

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check hasNext() - load fresh from local variable
        mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Handle multiple variables case
        if (node.variable instanceof ListNode varList) {
            for (int i = 0; i < varList.elements.size(); i++) {
                // Load iterator fresh from local variable for each variable
                mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
                
                // Check if iterator has more elements
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                Label hasValueLabel = new Label();
                Label endValueLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNE, hasValueLabel);

                // No more elements - assign undef
                EmitOperator.emitUndef(mv);
                mv.visitJumpInsn(Opcodes.GOTO, endValueLabel);

                // Has more elements - get next value
                mv.visitLabel(hasValueLabel);
                mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

                mv.visitLabel(endValueLabel);

                // Assign to variable (consumes value from stack)
                Node varNode = varList.elements.get(i);
                if (varNode instanceof OperatorNode operatorNode) {
                    String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                    int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                    emitterVisitor.ctx.logDebug("FOR1 multi var name:" + varName + " index:" + varIndex);
                    mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                }
            }
        } else {
            // Single variable case - load iterator and get next value
            mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
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

        // Stack is now clean - all values consumed by ASTORE
        // No POP needed because we never kept iterator on stack

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                effectiveLabelName,  // Use synthetic label for unlabeled loops
                continueLabel,
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        // CRITICAL: Exception handlers require absolutely clean stack at tryStart
        // Only add exception handlers if:
        // 1. Feature flag is enabled
        // 2. Loop is labeled (so 'last LABEL' can work across sub boundaries)
        // Unlabeled loops in expression contexts (e.g., $x . do { for ... }) can't have
        // exception handlers because parent expressions leave values on the operand stack
        // This is a fundamental limitation of combining JVM exception handlers with expression evaluation
        boolean needsExceptionHandlers = ENABLE_LOOP_EXCEPTION_HANDLERS && (node.labelName != null);
        
        Label tryStart = null;
        Label tryEnd = null;
        Label catchLast = null;
        Label catchNext = null;
        Label catchRedo = null;
        
        if (needsExceptionHandlers) {
            // UNIFIED exception handling for labeled loops only
            tryStart = new Label();
            tryEnd = new Label();
            catchLast = new Label();
            catchNext = new Label();
            catchRedo = new Label();
            
            // Start try block - stack should be clean for labeled loops
            mv.visitLabel(tryStart);
            mv.visitInsn(Opcodes.NOP); // Ensure valid range
        }
        
        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));
        
        if (needsExceptionHandlers) {
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);
            
            // Catch LastException - jump to loopEnd
            mv.visitLabel(catchLast);
            EmitStatement.emitLoopExceptionHandler(mv, effectiveLabelName, loopEnd);
            
            // Catch NextException - jump to continueLabel
            mv.visitLabel(catchNext);
            EmitStatement.emitLoopExceptionHandler(mv, effectiveLabelName, continueLabel);
            
            // Catch RedoException - jump to redoLabel
            mv.visitLabel(catchRedo);
            EmitStatement.emitLoopExceptionHandler(mv, effectiveLabelName, redoLabel);
            
            // Register exception handlers AFTER body emission (for correct nesting)
            mv.visitTryCatchBlock(tryStart, tryEnd, catchLast, "org/perlonjava/runtime/LastException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchNext, "org/perlonjava/runtime/NextException");
            mv.visitTryCatchBlock(tryStart, tryEnd, catchRedo, "org/perlonjava/runtime/RedoException");
        } else {
            // No exception handlers - just jump to continue
            mv.visitJumpInsn(Opcodes.GOTO, continueLabel);
        }

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

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

        // Note: No need to POP iterator from stack since we're using local variables
        // The iterator is stored in iteratorVar and loaded only when needed

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
        
        // Store iterator in a local variable (same approach as main emitFor1)
        int iteratorVar = emitterVisitor.ctx.symbolTable.allocateLocalVariable();

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);
        mv.visitVarInsn(Opcodes.ASTORE, iteratorVar);

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Load iterator and check if iterator has more elements
        mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Get next value
        mv.visitVarInsn(Opcodes.ALOAD, iteratorVar);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar");

        // Assign to variable $$f
        node.variable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: iteratorValue, dereferenced_var
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar",
                "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        mv.visitInsn(Opcodes.POP);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                new Label(),
                redoLabel,
                loopEnd,
                RuntimeContextType.VOID);

        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        mv.visitLabel(loopEnd);

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
