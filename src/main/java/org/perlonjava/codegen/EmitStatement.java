package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BlockNode;
import org.perlonjava.astnode.CompilerFlagNode;
import org.perlonjava.astnode.For3Node;
import org.perlonjava.astnode.IfNode;
import org.perlonjava.astnode.LabelNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.TryNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * The EmitStatement class is responsible for handling various control flow statements
 * and generating the corresponding bytecode using ASM.
 */
public class EmitStatement {

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
                emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                        node.labelName,
                        continueLabel,
                        redoLabel,
                        endLabel,
                        RuntimeContextType.VOID);

                // Check if body has any runtime code (not just subroutine definitions or labels)
                // Bare blocks with only compile-time constructs don't need exception handlers
                boolean hasRuntimeCode = false;
                if (node.body instanceof BlockNode blockBody) {
                    for (Node element : blockBody.elements) {
                        if (element != null && 
                            !(element instanceof LabelNode) && 
                            !(element instanceof CompilerFlagNode)) {
                            // Check if it's an empty/trivial node or compile-time block
                            if (element instanceof ListNode listNode) {
                                if (listNode.elements != null && !listNode.elements.isEmpty()) {
                                    hasRuntimeCode = true;
                                    break;
                                }
                                // Empty ListNode - continue checking
                            } else if (element instanceof OperatorNode opNode) {
                                // Check if it's a special block (BEGIN, INIT, END, CHECK, UNITCHECK)
                                // These don't emit runtime code in the main method
                                String op = opNode.operator;
                                if (!"BEGIN".equals(op) && !"INIT".equals(op) && !"END".equals(op) && 
                                    !"CHECK".equals(op) && !"UNITCHECK".equals(op) && !"undef".equals(op)) {
                                    hasRuntimeCode = true;
                                    break;
                                }
                                // Special blocks or undef - continue checking
                            } else {
                                hasRuntimeCode = true;
                                break;
                            }
                        }
                    }
                } else {
                    hasRuntimeCode = true;  // Non-BlockNode bodies always have runtime code
                }

                // Only wrap loop body in try-catch if there's actual runtime code
                // (bare blocks with only subroutine definitions don't need exception handlers)
                if (hasRuntimeCode) {
                    Label tryStart = new Label();
                    Label tryEnd = new Label();
                    Label catchLast = new Label();
                    Label catchNext = new Label();
                    Label catchRedo = new Label();

                    // Register exception handlers
                    mv.visitTryCatchBlock(tryStart, tryEnd, catchLast, "org/perlonjava/runtime/LastException");
                    mv.visitTryCatchBlock(tryStart, tryEnd, catchNext, "org/perlonjava/runtime/NextException");
                    mv.visitTryCatchBlock(tryStart, tryEnd, catchRedo, "org/perlonjava/runtime/RedoException");

                    // Try block
                    mv.visitLabel(tryStart);
                    // Emit NOP to ensure try-catch range is valid even if body emits no bytecode
                    // (e.g., INIT blocks, BEGIN blocks, or bare blocks with only subroutine definitions)
                    mv.visitInsn(Opcodes.NOP);
                    node.body.accept(voidVisitor);
                    mv.visitLabel(tryEnd);
                    mv.visitJumpInsn(Opcodes.GOTO, continueLabel);

                    // Catch LastException - jump to endLabel
                    mv.visitLabel(catchLast);
                    emitLoopExceptionHandler(mv, node.labelName, endLabel);

                    // Catch NextException - jump to continueLabel
                    mv.visitLabel(catchNext);
                    emitLoopExceptionHandler(mv, node.labelName, continueLabel);

                    // Catch RedoException - jump to redoLabel
                    mv.visitLabel(catchRedo);
                    emitLoopExceptionHandler(mv, node.labelName, redoLabel);
                } else {
                    // No runtime code, just emit the body without exception handling
                    node.body.accept(voidVisitor);
                    mv.visitJumpInsn(Opcodes.GOTO, continueLabel);
                }

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

        // Start of the loop body
        mv.visitLabel(startLabel);

        // Check for pending signals (alarm, etc.) at loop entry
        emitSignalCheck(mv);

        // Visit the loop body
        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

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

        // Labels for try-catch-finally
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchBlock = new Label();
        Label finallyStart = new Label();
        Label finallyEnd = new Label();

        // Start of try block
        mv.visitLabel(tryStart);
        node.tryBlock.accept(emitterVisitor);
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
                "(Ljava/lang/Exception;)Ljava/lang/String;", false);
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

        // Finally block
        mv.visitLabel(finallyStart);
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }
        mv.visitLabel(finallyEnd);

        // Define the try-catch block
        mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception");

        // If the context is VOID, clear the stack
        EmitOperator.handleVoidContext(emitterVisitor);

        emitterVisitor.ctx.logDebug("emitTryCatch end");
    }

    /**
     * Emits bytecode for handling control flow exceptions (next/last/redo) in blocks.
     * This helper method generates code that checks if an exception matches the given label
     * and either jumps to the target label or re-throws the exception.
     *
     * @param mv          The MethodVisitor to emit bytecode to
     * @param labelName   The label name to match against (or null for unlabeled loops)
     * @param targetLabel The ASM label to jump to if the exception matches
     */
    private static void emitExceptionHandler(MethodVisitor mv, String labelName, Label targetLabel) {
        // Stack: [exception]
        mv.visitInsn(Opcodes.DUP);  // [exception, exception]
        
        // Call exception.matchesLabel(labelName)
        if (labelName != null) {
            mv.visitLdcInsn(labelName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
            "org/perlonjava/runtime/PerlControlFlowException", 
            "matchesLabel", 
            "(Ljava/lang/String;)Z", 
            false);
        
        Label rethrow = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, rethrow);  // if false, rethrow
        
        // Match! Pop exception and jump to target
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, targetLabel);
        
        // Re-throw for outer frames
        mv.visitLabel(rethrow);
        mv.visitInsn(Opcodes.ATHROW);
    }

    /**
     * Emits bytecode for handling control flow exceptions (next/last/redo) in loops.
     * Similar to emitExceptionHandler, but for loop contexts.
     *
     * @param mv          The MethodVisitor to emit bytecode to
     * @param labelName   The label name to match against (or null for unlabeled loops)
     * @param targetLabel The ASM label to jump to if the exception matches
     */
    public static void emitLoopExceptionHandler(MethodVisitor mv, String labelName, Label targetLabel) {
        // Stack: [exception]
        mv.visitInsn(Opcodes.DUP);  // [exception, exception]
        
        // Call exception.matchesLabel(labelName)
        if (labelName != null) {
            mv.visitLdcInsn(labelName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
            "org/perlonjava/runtime/PerlControlFlowException", 
            "matchesLabel", 
            "(Ljava/lang/String;)Z", 
            false);
        
        Label rethrow = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, rethrow);  // if false, rethrow
        
        // Match! Pop exception and jump to target
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, targetLabel);
        
        // Re-throw for outer frames
        mv.visitLabel(rethrow);
        mv.visitInsn(Opcodes.ATHROW);
    }
}
