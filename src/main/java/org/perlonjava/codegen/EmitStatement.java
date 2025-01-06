package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.List;

/**
 * The EmitStatement class is responsible for handling various control flow statements
 * and generating the corresponding bytecode using ASM.
 */
public class EmitStatement {

    /**
     * Emits bytecode for an if statement, including support for 'unless'.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The if node representing the if statement.
     */
    static void emitIf(EmitterVisitor emitterVisitor, IfNode node) {
        emitterVisitor.ctx.logDebug("IF start: " + node.operator);

        // Enter a new scope in the symbol table
        emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);

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
                emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        }

        // Visit the end label
        emitterVisitor.ctx.mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        emitterVisitor.ctx.symbolTable.exitScope();

        emitterVisitor.ctx.logDebug("IF end");
    }

    /**
     * Emits bytecode for a for-loop with initialization, condition, and increment (C-style for loop).
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The for-loop node representing the loop.
     */
    static void emitFor3(EmitterVisitor emitterVisitor, For3Node node) {
        if (node.isDoWhile) {
            emitDoWhile(emitterVisitor, node);
        } else {
            emitterVisitor.ctx.logDebug("FOR3 start");
            MethodVisitor mv = emitterVisitor.ctx.mv;

            EmitterVisitor voidVisitor = emitterVisitor.with(RuntimeContextType.VOID); // some parts have context VOID

            // Enter a new scope in the symbol table
            if (node.useNewScope) {
                emitterVisitor.ctx.symbolTable.enterScope();
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

            // Visit the condition node in scalar context
            if (node.condition != null) {
                node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                // Convert the result to a boolean
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);

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

                // Visit the loop body
                node.body.accept(voidVisitor);

                // Cleanup loop labels
                emitterVisitor.ctx.javaClassInfo.popLoopLabels();
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

            // Visit the increment node (executed after the loop body)
            if (node.increment != null) {
                node.increment.accept(voidVisitor);
            }

            // Jump back to the start label to continue the loop
            mv.visitJumpInsn(Opcodes.GOTO, startLabel);

            // Visit the end label (this is where the loop ends)
            mv.visitLabel(endLabel);

            // Exit the scope in the symbol table
            if (node.useNewScope) {
                emitterVisitor.ctx.symbolTable.exitScope();
            }

            // If the context is not VOID, push "undef" to the stack
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }

            emitterVisitor.ctx.logDebug("FOR end");
        }
    }

    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The block node representing the block of statements.
     */
    static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        emitterVisitor.ctx.logDebug("generateCodeBlock start context:" + emitterVisitor.ctx.contextType);
        emitterVisitor.ctx.symbolTable.enterScope();
        EmitterVisitor voidVisitor =
                emitterVisitor.with(RuntimeContextType.VOID); // statements in the middle of the block have context VOID
        List<Node> list = node.elements;

        // Create labels for the loop
        Label redoLabel = new Label();
        Label nextLabel = new Label();

        // Setup 'local' environment if needed
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Add redo label
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                    node.labelName,
                    nextLabel,
                    redoLabel,
                    nextLabel,
                    emitterVisitor.ctx.contextType);
        }

        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);

            DebugInfo.setDebugInfoLineNumber(emitterVisitor.ctx, element.getIndex());

            // Emit the statement with current context
            if (i == list.size() - 1) {
                // Special case for the last element
                emitterVisitor.ctx.logDebug("Last element: " + element);
                element.accept(emitterVisitor);
            } else {
                // General case for all other elements
                emitterVisitor.ctx.logDebug("Element: " + element);
                element.accept(voidVisitor);
            }
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
        }

        // Add 'next', 'last' label
        mv.visitLabel(nextLabel);

        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.symbolTable.exitScope();
        emitterVisitor.ctx.logDebug("generateCodeBlock end");
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
        emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels
        Label startLabel = new Label();
        Label continueLabel = new Label();
        Label endLabel = new Label();

        // Start of the loop body
        mv.visitLabel(startLabel);

        // Visit the loop body
        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        // Continue label (for next iteration)
        mv.visitLabel(continueLabel);

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Convert the result to a boolean
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "getBoolean", "()Z", true);

        // If condition is true, jump back to start
        mv.visitJumpInsn(Opcodes.IFNE, startLabel);

        // End of loop
        mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        emitterVisitor.ctx.symbolTable.exitScope();

        // If the context is not VOID, push "undef" to the stack
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        emitterVisitor.ctx.logDebug("DO-WHILE end");
    }

    static void emitTryCatch(EmitterVisitor emitterVisitor, TryNode node) {
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
        node.tryBlock.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
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

        node.catchBlock.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Finally block
        mv.visitLabel(finallyStart);
        if (node.finallyBlock != null) {
            node.finallyBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }
        mv.visitLabel(finallyEnd);

        // Define the try-catch block
        mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception");

        // If the context is VOID, clear the stack
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }

        emitterVisitor.ctx.logDebug("emitTryCatch end");
    }
}
