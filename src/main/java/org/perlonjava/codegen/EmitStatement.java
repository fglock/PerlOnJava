package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.For3Node;
import org.perlonjava.astnode.IfNode;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.TryNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * The EmitStatement class is responsible for handling various control flow statements
 * and generating the corresponding bytecode using ASM.
 */
public class EmitStatement {
    // Feature flags for control flow implementation
    // Feature flag for loop control flow handlers (same as EmitForeach.java)
    //
    // WHAT THIS WOULD DO IF ENABLED:
    // Enable control flow handlers for while/until/for loops (same as foreach).
    //
    // WHY IT'S DISABLED:
    // Same reason as EmitForeach.java - depends on ENABLE_CONTROL_FLOW_CHECKS.
    //
    // DEPENDENCY:
    // Must remain false until EmitSubroutine.ENABLE_CONTROL_FLOW_CHECKS is fixed.
    private static final boolean ENABLE_LOOP_HANDLERS = false;
    
    // Set to true to enable debug output for loop control flow
    private static final boolean DEBUG_LOOP_CONTROL_FLOW = false;

    /**
     * Emits bytecode to check for pending signals (like SIGALRM from alarm()).
     * This is a lightweight check - just a volatile boolean read if no signals are pending.
     * Should be called at safe execution points like loop entries.
     *
     * @param mv The MethodVisitor to emit bytecode to.
     */
    public static void emitSignalCheck(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/PerlSignalQueue",
                "checkPendingSignals",
                "()V",
                false);
    }

    /**
     * Emits bytecode for an if statement, including support for 'unless'.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The if node representing the if statement.
     */
    public static void emitIf(EmitterVisitor emitterVisitor, IfNode node) {
        emitterVisitor.ctx.logDebug("IF start: " + node.operator);

        // Enter a new scope in the symbol table
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getBoolean", "()Z", false);

        // Jump to the else label if the condition is false
        emitterVisitor.ctx.mv.visitJumpInsn(node.operator.equals("unless") ? Opcodes.IFNE : Opcodes.IFEQ, elseLabel);

        // Visit the then branch
        node.thenBranch.accept(emitterVisitor);

        // Jump to the end label after executing the then branch
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        emitterVisitor.ctx.mv.visitLabel(elseLabel);

        // Visit the else branch if it exists
        if (node.elseBranch != null) {
            node.elseBranch.accept(emitterVisitor);
        } else {
            // If the context is not VOID, push "undef" to the stack
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                EmitOperator.emitUndef(emitterVisitor.ctx.mv);
            }
        }

        // Visit the end label
        emitterVisitor.ctx.mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        emitterVisitor.ctx.logDebug("IF end");
    }

    /**
     * Emits bytecode for a for-loop with initialization, condition, and increment (C-style for loop).
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The for-loop node representing the loop.
     */
    public static void emitFor3(EmitterVisitor emitterVisitor, For3Node node) {
        if (node.isDoWhile) {
            emitDoWhile(emitterVisitor, node);
        } else {
            emitterVisitor.ctx.logDebug("FOR3 start");
            MethodVisitor mv = emitterVisitor.ctx.mv;

            EmitterVisitor voidVisitor = emitterVisitor.with(RuntimeContextType.VOID); // some parts have context VOID

            // Enter a new scope in the symbol table
            int scopeIndex = -1;
            if (node.useNewScope) {
                scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
            }

            // Create labels for the start of the loop and the end of the loop
            Label startLabel = new Label();
            Label endLabel = new Label();
            Label continueLabel = new Label();

            // Visit the initialization node (executed once at the start)
            if (node.initialization != null) {
                node.initialization.accept(voidVisitor);
            }

            // Visit the start label (this is where the loop condition and body are)
            mv.visitLabel(startLabel);

            // Check for pending signals (alarm, etc.) at loop entry
            emitSignalCheck(mv);

            // Visit the condition node in scalar context
            if (node.condition != null) {
                node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                // Convert the result to a boolean
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getBoolean", "()Z", false);

                // Jump to the end label if the condition is false (exit the loop)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel);
            }

            // Add redo label
            Label redoLabel = new Label();
            mv.visitLabel(redoLabel);

            if (node.useNewScope) {
                // Register next/redo/last labels
                emitterVisitor.ctx.logDebug("FOR3 label: " + node.labelName);
                boolean isUnlabeledTarget = !node.isSimpleBlock;
                emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                        node.labelName,
                        continueLabel,
                        redoLabel,
                        endLabel,
                        emitterVisitor.ctx.javaClassInfo.stackLevelManager.getStackLevel(),
                        RuntimeContextType.VOID,
                        true,
                        isUnlabeledTarget);

                // Visit the loop body
                node.body.accept(voidVisitor);

            } else {
                // Within a `while` modifier, next/redo/last labels are not active
                // Visit the loop body
                node.body.accept(voidVisitor);
            }

            // Add continue label
            mv.visitLabel(continueLabel);

            // Execute continue block if it exists
            if (node.continueBlock != null) {
                node.continueBlock.accept(voidVisitor);
            }

            if (node.useNewScope) {
                // Cleanup loop labels
                // The labels are also active inside the continue block
                emitterVisitor.ctx.javaClassInfo.popLoopLabels();
            }

            // Visit the increment node (executed after the loop body)
            if (node.increment != null) {
                node.increment.accept(voidVisitor);
            }

            if (!node.isSimpleBlock) {
                // Jump back to the start label to continue the loop
                mv.visitJumpInsn(Opcodes.GOTO, startLabel);
            }

            // Visit the end label (this is where the loop ends)
            mv.visitLabel(endLabel);

            // Exit the scope in the symbol table
            if (node.useNewScope) {
                emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
            }

            // If the context is not VOID, push "undef" to the stack
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                EmitOperator.emitUndef(emitterVisitor.ctx.mv);
            }

            emitterVisitor.ctx.logDebug("FOR end");
        }
    }

    /**
     * Emits bytecode for a do-while loop.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The for-loop node representing the do-while loop.
     */
    static void emitDoWhile(EmitterVisitor emitterVisitor, For3Node node) {
        emitterVisitor.ctx.logDebug("DO-WHILE start");
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Enter a new scope in the symbol table
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels
        Label startLabel = new Label();
        Label continueLabel = new Label();
        Label endLabel = new Label();
        Label redoLabel = new Label();

        // Register loop labels as pseudo-loop (isTrueLoop = false)
        // This allows us to throw proper compile errors for last/next/redo in do-while
        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                endLabel,
                emitterVisitor.ctx.javaClassInfo.stackLevelManager.getStackLevel(),
                RuntimeContextType.VOID,
                false); // isTrueLoop = false (do-while is not a true loop)

        // Start of the loop body
        mv.visitLabel(redoLabel);
        mv.visitLabel(startLabel);

        // Check for pending signals (alarm, etc.) at loop entry
        emitSignalCheck(mv);

        // Visit the loop body
        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));
        
        // Check RuntimeControlFlowRegistry for non-local control flow
        // Use the loop labels we created earlier (don't look them up)
        LoopLabels loopLabels = new LoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                endLabel,
                emitterVisitor.ctx.javaClassInfo.stackLevelManager.getStackLevel(),
                RuntimeContextType.VOID,
                false);
        emitRegistryCheck(mv, loopLabels, redoLabel, continueLabel, endLabel);

        // Continue label (for next iteration)
        mv.visitLabel(continueLabel);

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getBoolean", "()Z", false);

        // If condition is true, jump back to start
        mv.visitJumpInsn(Opcodes.IFNE, startLabel);

        // End of loop
        mv.visitLabel(endLabel);

        // Pop loop labels
        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        // Exit the scope in the symbol table
        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        // If the context is not VOID, push "undef" to the stack
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            EmitOperator.emitUndef(emitterVisitor.ctx.mv);
        }

        emitterVisitor.ctx.logDebug("DO-WHILE end");
    }

    public static void emitTryCatch(EmitterVisitor emitterVisitor, TryNode node) {
        emitterVisitor.ctx.logDebug("emitTryCatch start");

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // To keep ASM frame computation stable, ensure try/catch paths merge into finally
        // with identical operand stack state. We do this by storing the result of the
        // try or catch block into a temporary local slot and reloading it after finally.
        int resultSlot = -1;
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            resultSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            EmitOperator.emitUndef(mv);
            mv.visitVarInsn(Opcodes.ASTORE, resultSlot);
        }

        // Labels for try-catch-finally
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchBlock = new Label();
        Label finallyStart = new Label();
        Label finallyEnd = new Label();

        // Define the try-catch block before visiting labels for maximum ASM compatibility
        mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Throwable");

        // Start of try block
        mv.visitLabel(tryStart);
        node.tryBlock.accept(emitterVisitor);
        if (resultSlot >= 0) {
            mv.visitVarInsn(Opcodes.ASTORE, resultSlot);
        }
        mv.visitLabel(tryEnd);

        // Jump to finally block if try completes without exception
        mv.visitJumpInsn(Opcodes.GOTO, finallyStart);

        // Exception handler
        mv.visitLabel(catchBlock);

        // --------- Store the exception in the catch parameter ---------
        // Convert the exception to a string
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/ErrorMessageUtil",
                "stringifyException",
                "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
        // Transform catch parameter to 'my'
        OperatorNode catchParameter = new OperatorNode("my", node.catchParameter, node.tokenIndex);
        // Create the lexical variable for the catch parameter, push it to the stack
        catchParameter.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeScalar",
                "set",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);
        mv.visitInsn(Opcodes.POP);
        // --------- end of store the catch parameter ---------

        node.catchBlock.accept(emitterVisitor);

        if (resultSlot >= 0) {
            mv.visitVarInsn(Opcodes.ASTORE, resultSlot);
        }

        // Finally block
        mv.visitLabel(finallyStart);
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }
        mv.visitLabel(finallyEnd);

        if (resultSlot >= 0) {
            mv.visitVarInsn(Opcodes.ALOAD, resultSlot);
        }

        emitterVisitor.ctx.logDebug("emitTryCatch end");
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
