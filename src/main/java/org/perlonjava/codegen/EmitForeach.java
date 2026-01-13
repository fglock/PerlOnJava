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

        Node variableNode = node.variable;

        // Check if the loop variable is a complex lvalue expression like $$f
        // If so, emit as while loop with explicit assignment
        if (variableNode instanceof OperatorNode opNode &&
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
        if (variableNode instanceof OperatorNode opNode && opNode.operator.equals("$")) {
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
        if (variableNode instanceof OperatorNode opNode &&
                (opNode.operator.equals("my") || opNode.operator.equals("our"))) {
            boolean isWarningEnabled = Warnings.warningManager.isWarningEnabled("redefine");
            if (isWarningEnabled) {
                // turn off "masks earlier declaration" warning
                Warnings.warningManager.setWarningState("redefine", false);
            }
            // emit the variable declarations
            variableNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
            // Use the variable node without the declaration for codegen, but do not mutate the AST.
            variableNode = opNode.operand;

            if (opNode.operator.equals("my") && variableNode instanceof OperatorNode declVar
                    && declVar.operator.equals("$") && declVar.operand instanceof IdentifierNode declId) {
                String varName = declVar.operator + declId.name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                if (varIndex == -1) {
                    varIndex = emitterVisitor.ctx.symbolTable.addVariable(varName, "my", declVar);
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                            "org/perlonjava/runtime/RuntimeScalar",
                            "<init>",
                            "()V",
                            false);
                    mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                }
            }

            if (isWarningEnabled) {
                // restore warnings
                Warnings.warningManager.setWarningState("redefine", true);
            }

            // Reset global variable check after rewriting
            loopVariableIsGlobal = false;
        }

        if (variableNode instanceof OperatorNode opNode &&
                opNode.operator.equals("state") && opNode.operand instanceof OperatorNode declVar
                && declVar.operator.equals("$") && declVar.operand instanceof IdentifierNode declId) {
            variableNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
            variableNode = opNode.operand;
            String varName = declVar.operator + declId.name;
            int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
            if (varIndex == -1) {
                varIndex = emitterVisitor.ctx.symbolTable.addVariable(varName, "state", declVar);
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeScalar");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        "<init>",
                        "()V",
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, varIndex);
            }
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

        int iteratorIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();

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
            mv.visitVarInsn(Opcodes.ASTORE, iteratorIndex);
        } else if (isGlobalUnderscore) {
            // Global $_ as loop variable: evaluate list to array of aliases first
            // This preserves aliasing semantics while ensuring list is evaluated before any
            // parent block's local $_ takes effect (e.g., in nested for loops)
            node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));

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

            // IMPORTANT: avoid materializing huge ranges.
            // PerlRange.setArrayOfAlias() currently expands to a full list, which can OOM
            // in Benchmark.pm (for (1..$n) with large $n).
            Label notRangeLabel = new Label();
            Label afterIterLabel = new Label();
            mv.visitInsn(Opcodes.DUP);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "org/perlonjava/runtime/PerlRange");
            mv.visitJumpInsn(Opcodes.IFEQ, notRangeLabel);

            // Range: iterate directly.
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);
            mv.visitVarInsn(Opcodes.ASTORE, iteratorIndex);
            mv.visitJumpInsn(Opcodes.GOTO, afterIterLabel);

            // Non-range: preserve aliasing semantics by iterating an array-of-alias.
            mv.visitLabel(notRangeLabel);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "iterator", "()Ljava/util/Iterator;", false);
            mv.visitVarInsn(Opcodes.ASTORE, iteratorIndex);

            mv.visitLabel(afterIterLabel);
        } else {
            // Standard path: obtain iterator for the list
            node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);
            mv.visitVarInsn(Opcodes.ASTORE, iteratorIndex);
        }

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Handle multiple variables case
        if (variableNode instanceof ListNode varList) {
            for (int i = 0; i < varList.elements.size(); i++) {
                Label hasValueLabel = new Label();
                Label endValueLabel = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                mv.visitJumpInsn(Opcodes.IFNE, hasValueLabel);

                // No more elements - assign undef
                EmitOperator.emitUndef(mv);
                mv.visitJumpInsn(Opcodes.GOTO, endValueLabel);

                // Has more elements - get next value
                mv.visitLabel(hasValueLabel);
                mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
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
            mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
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
            } else if (variableNode instanceof OperatorNode operatorNode) {
                // Local variable case
                String varName = operatorNode.operator + ((IdentifierNode) operatorNode.operand).name;
                int varIndex = emitterVisitor.ctx.symbolTable.getVariableIndex(varName);
                emitterVisitor.ctx.logDebug("FOR1 single var name:" + varName + " index:" + varIndex);
                mv.visitVarInsn(Opcodes.ASTORE, varIndex);
            }
        }

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
        
        // Check RuntimeControlFlowRegistry for non-local control flow
        emitRegistryCheck(mv, currentLoopLabels, redoLabel, continueLabel, loopEnd);

        LoopLabels poppedLabels = emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        mv.visitLabel(continueLabel);

        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
            // Check registry again after continue block
            emitRegistryCheck(mv, currentLoopLabels, redoLabel, loopStart, loopEnd);
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
        // emitLabelCheck never returns (either matches and jumps to target, or jumps to noMatch)
        
        // Handle NEXT
        mv.visitLabel(handleNext);
        emitLabelCheck(mv, loopLabels.labelName, loopLabels.nextLabel, propagateToParent);
        // emitLabelCheck never returns
        
        // Handle REDO
        mv.visitLabel(handleRedo);
        emitLabelCheck(mv, loopLabels.labelName, loopLabels.redoLabel, propagateToParent);
        // emitLabelCheck never returns
        
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
        
        // SIMPLIFIED PATTERN to avoid ASM frame computation issues:
        // Use a helper method that returns boolean instead of complex branching
        
        // Stack: [RuntimeControlFlowList]
        
        // Call helper method: RuntimeControlFlowList.matchesLabel(String loopLabel)
        // This returns true if the control flow label matches the loop label
        mv.visitInsn(Opcodes.DUP);  // Duplicate for use after check
        if (loopLabelName != null) {
            mv.visitLdcInsn(loopLabelName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeControlFlowList",
                "matchesLabel",
                "(Ljava/lang/String;)Z",
                false);
        
        // Stack: [RuntimeControlFlowList] [boolean]
        
        // If match, pop and jump to target
        mv.visitJumpInsn(Opcodes.IFEQ, noMatch);
        
        // Match! Pop RuntimeControlFlowList and jump to target
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, targetLabel);
        
        // No match label is handled by caller (falls through)
    }

    private static void emitFor1AsWhileLoop(EmitterVisitor emitterVisitor, For1Node node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label loopStart = new Label();
        Label loopEnd = new Label();

        // Obtain the iterator for the list
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "iterator", "()Ljava/util/Iterator;", false);

        int iteratorIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, iteratorIndex);

        mv.visitLabel(loopStart);

        // Check for pending signals (alarm, etc.) at loop entry
        EmitStatement.emitSignalCheck(mv);

        // Check if iterator has more elements
        mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Get next value
        mv.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
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
    
    /**
     * Emit bytecode to check RuntimeControlFlowRegistry and handle any registered control flow.
     * This is called after loop body execution to catch non-local control flow markers.
     * 
     * @param mv The MethodVisitor
     * @param loopLabels The current loop's labels
     * @param redoLabel The redo target
     * @param nextLabel The next/continue target  
     * @param lastLabel The last/exit target
     */
    private static void emitRegistryCheck(MethodVisitor mv, LoopLabels loopLabels, 
                                         Label redoLabel, Label nextLabel, Label lastLabel) {
        // ULTRA-SIMPLE pattern to avoid ASM issues:
        // Call a single helper method that does ALL the checking and returns an action code
        
        String labelName = loopLabels.labelName;
        if (labelName != null) {
            mv.visitLdcInsn(labelName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        
        // Call: int action = RuntimeControlFlowRegistry.checkLoopAndGetAction(String labelName)
        // Returns: 0=none, 1=last, 2=next, 3=redo
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                "checkLoopAndGetAction",
                "(Ljava/lang/String;)I",
                false);
        
        // Use TABLESWITCH for clean bytecode
        mv.visitTableSwitchInsn(
                1,  // min (LAST)
                3,  // max (REDO)
                nextLabel,  // default (0=none or out of range)
                lastLabel,  // 1: LAST
                nextLabel,  // 2: NEXT  
                redoLabel   // 3: REDO
        );
        
        // No label needed - all paths are handled by switch
    }
}
