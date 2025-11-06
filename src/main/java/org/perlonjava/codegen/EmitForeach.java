package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.RuntimeContextType;

public class EmitForeach {
    // Feature flags for control flow implementation
    //
    // WHAT THIS WOULD DO IF ENABLED:
    // After a foreach loop body executes, if a subroutine call returned a RuntimeControlFlowList
    // (marked with last/next/redo/goto), execution jumps to a controlFlowHandler that:
    // 1. Checks the control flow type (LAST/NEXT/REDO/GOTO/TAILCALL)
    // 2. Checks if the label matches this loop
    // 3. If match: jumps to the appropriate loop label (lastLabel/nextLabel/redoLabel/gotoLabel)
    // 4. If no match: propagates to parent loop's handler (for nested loops)
    // 5. If no parent: returns to caller with the marked list
    //
    // WHY IT'S DISABLED:
    // Loop handlers are only reachable via call-site checks (ENABLE_CONTROL_FLOW_CHECKS).
    // Without call-site checks jumping to the handler, this code is unreachable (dead code).
    // Dead code can confuse ASM's frame computation and cause verification errors.
    //
    // DEPENDENCY:
    // This flag MUST remain false until ENABLE_CONTROL_FLOW_CHECKS in EmitSubroutine.java
    // is fixed and enabled. See comments there for investigation steps.
    //
    // CURRENT WORKAROUND:
    // Marked returns propagate through normal return paths all the way to the top level.
    // Local control flow (within the same loop) still uses fast JVM GOTO instructions.
    private static final boolean ENABLE_LOOP_HANDLERS = false;
    
    // Set to true to enable debug output for loop control flow
    private static final boolean DEBUG_LOOP_CONTROL_FLOW = false;
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

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

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

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        // Create control flow handler label
        Label controlFlowHandler = new Label();

        LoopLabels currentLoopLabels = new LoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd,
                emitterVisitor.ctx.javaClassInfo.stackLevelManager.getStackLevel(),
                RuntimeContextType.VOID);
        currentLoopLabels.controlFlowHandler = controlFlowHandler;

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(currentLoopLabels);

        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        LoopLabels poppedLabels = emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }

        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        mv.visitLabel(loopEnd);
        
        // Emit control flow handler (if enabled)
        if (ENABLE_LOOP_HANDLERS) {
            // Get parent loop labels (if any)
            LoopLabels parentLoopLabels = emitterVisitor.ctx.javaClassInfo.getParentLoopLabels();
            
            // Get goto labels from current scope (TODO: implement getGotoLabels in EmitterContext)
            java.util.Map<String, org.objectweb.asm.Label> gotoLabels = null;  // For now
            
            // Check if this is the outermost loop in the main program
            boolean isMainProgramOutermostLoop = emitterVisitor.ctx.compilerOptions.isMainProgram 
                    && parentLoopLabels == null;
            
            // Emit the handler
            emitControlFlowHandler(
                    mv,
                    currentLoopLabels,
                    parentLoopLabels,
                    emitterVisitor.ctx.javaClassInfo.returnLabel,
                    gotoLabels,
                    isMainProgramOutermostLoop);
        }
        
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

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);
        mv.visitInsn(Opcodes.POP);

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

    /**
     * Emits control flow handler for a foreach loop.
     * Handles marked RuntimeList objects (last/next/redo/goto) that propagate from nested calls.
     * 
     * @param mv The MethodVisitor to emit bytecode to
     * @param loopLabels The loop labels (controlFlowHandler, next, redo, last)
     * @param parentLoopLabels The parent loop's labels (null if this is the outermost loop)
     * @param returnLabel The subroutine's return label
     * @param gotoLabels Map of goto label names to ASM Labels in current scope
     * @param isMainProgramOutermostLoop True if this is the outermost loop in main program (should throw error instead of returning)
     */
    private static void emitControlFlowHandler(
            MethodVisitor mv,
            LoopLabels loopLabels,
            LoopLabels parentLoopLabels,
            org.objectweb.asm.Label returnLabel,
            java.util.Map<String, org.objectweb.asm.Label> gotoLabels,
            boolean isMainProgramOutermostLoop) {
        
        if (!ENABLE_LOOP_HANDLERS) {
            return;  // Feature not enabled yet
        }
        
        if (DEBUG_LOOP_CONTROL_FLOW) {
            System.out.println("[DEBUG] Emitting control flow handler for loop: " + loopLabels.labelName);
        }
        
        // Handler label
        mv.visitLabel(loopLabels.controlFlowHandler);
        
        // Stack: [RuntimeControlFlowList] - guaranteed clean by call site or parent handler
        
        // Cast to RuntimeControlFlowList (we know it's marked)
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
        
        // Get control flow type (enum)
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeControlFlowList",
                "getControlFlowType",
                "()Lorg/perlonjava/runtime/ControlFlowType;",
                false);
        
        // Convert enum to ordinal for switch
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/ControlFlowType",
                "ordinal",
                "()I",
                false);
        
        // Tableswitch on control flow type
        Label handleLast = new Label();
        Label handleNext = new Label();
        Label handleRedo = new Label();
        Label handleGoto = new Label();
        Label propagateToParent = new Label();
        
        mv.visitTableSwitchInsn(
                0,  // min (LAST.ordinal())
                3,  // max (GOTO.ordinal())
                propagateToParent,  // default (TAILCALL or unknown)
                handleLast,   // 0: LAST
                handleNext,   // 1: NEXT
                handleRedo,   // 2: REDO
                handleGoto    // 3: GOTO
        );
        
        // Handle LAST
        mv.visitLabel(handleLast);
        emitLabelCheck(mv, loopLabels.labelName, loopLabels.lastLabel, propagateToParent);
        
        // Handle NEXT
        mv.visitLabel(handleNext);
        emitLabelCheck(mv, loopLabels.labelName, loopLabels.nextLabel, propagateToParent);
        
        // Handle REDO
        mv.visitLabel(handleRedo);
        emitLabelCheck(mv, loopLabels.labelName, loopLabels.redoLabel, propagateToParent);
        
        // Handle GOTO
        mv.visitLabel(handleGoto);
        if (gotoLabels != null && !gotoLabels.isEmpty()) {
            // Check each goto label in current scope
            for (java.util.Map.Entry<String, org.objectweb.asm.Label> entry : gotoLabels.entrySet()) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeControlFlowList",
                        "getControlFlowLabel",
                        "()Ljava/lang/String;",
                        false);
                mv.visitLdcInsn(entry.getKey());
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "equals",
                        "(Ljava/lang/Object;)Z",
                        false);
                Label notThisGoto = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, notThisGoto);
                
                // Match! Pop marked RuntimeList and jump to goto label
                mv.visitInsn(Opcodes.POP);
                mv.visitJumpInsn(Opcodes.GOTO, entry.getValue());
                
                mv.visitLabel(notThisGoto);
            }
        }
        // Fall through to propagateToParent if no goto label matched
        
        // Propagate to parent handler or return
        mv.visitLabel(propagateToParent);
        if (parentLoopLabels != null && parentLoopLabels.controlFlowHandler != null) {
            // Chain to parent loop's handler
            mv.visitJumpInsn(Opcodes.GOTO, parentLoopLabels.controlFlowHandler);
        } else if (isMainProgramOutermostLoop) {
            // Outermost loop in main program - throw error immediately
            // Stack: [RuntimeControlFlowList]
            
            // Cast to RuntimeControlFlowList
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            
            // Get the marker field
            mv.visitFieldInsn(Opcodes.GETFIELD,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "marker",
                    "Lorg/perlonjava/runtime/ControlFlowMarker;");
            
            // Call marker.throwError() - this method never returns
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/ControlFlowMarker",
                    "throwError",
                    "()V",
                    false);
            
            // Should never reach here (method throws), but add RETURN for verifier
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
        } else {
            // Outermost loop in subroutine - return to caller (will be caught by Phase 4 or parent)
            mv.visitJumpInsn(Opcodes.GOTO, returnLabel);
        }
    }
    
    /**
     * Emits bytecode to check if a label matches the current loop, and jump if it does.
     * If the label is null (unlabeled) or matches, POPs the marked RuntimeList and jumps to target.
     * Otherwise, falls through to next handler.
     * 
     * @param mv The MethodVisitor
     * @param loopLabelName The name of the current loop (null if unlabeled)
     * @param targetLabel The ASM Label to jump to if this label matches
     * @param noMatch The label to jump to if the label doesn't match
     */
    private static void emitLabelCheck(
            MethodVisitor mv,
            String loopLabelName,
            Label targetLabel,
            Label noMatch) {
        
        // Stack: [RuntimeControlFlowList]
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeControlFlowList",
                "getControlFlowLabel",
                "()Ljava/lang/String;",
                false);
        
        // Stack: [RuntimeControlFlowList] [String label]
        
        Label doJump = new Label();
        
        // Check if unlabeled (null)
        mv.visitInsn(Opcodes.DUP);
        Label notNull = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        
        // Unlabeled - matches if loop is also unlabeled or always matches (Perl semantics)
        mv.visitInsn(Opcodes.POP);  // Pop null
        mv.visitJumpInsn(Opcodes.GOTO, doJump);
        
        // Not null - check if it matches this loop's label
        mv.visitLabel(notNull);
        if (loopLabelName != null) {
            mv.visitLdcInsn(loopLabelName);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFNE, doJump);
        } else {
            // This loop has no label, but the control flow is labeled - doesn't match
            mv.visitInsn(Opcodes.POP);  // Pop label string
        }
        
        // No match - fall through to next handler
        mv.visitJumpInsn(Opcodes.GOTO, noMatch);
        
        // Match!
        mv.visitLabel(doJump);
        mv.visitInsn(Opcodes.POP);  // Pop RuntimeControlFlowList
        mv.visitJumpInsn(Opcodes.GOTO, targetLabel);
    }

    private static void emitFor1AsWhileLoop(EmitterVisitor emitterVisitor, For1Node node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

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

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

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

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);
        mv.visitInsn(Opcodes.POP);

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
