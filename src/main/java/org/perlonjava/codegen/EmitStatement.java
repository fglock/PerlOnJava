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
     * @param node The if node representing the if statement.
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
     * @param node The for-loop node representing the loop.
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
                emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                        node.labelName,
                        continueLabel,
                        redoLabel,
                        endLabel);

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
     * Emits bytecode for a foreach loop.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node The foreach node representing the loop.
     */
    static void emitFor1(EmitterVisitor emitterVisitor, For1Node node) {
        emitterVisitor.ctx.logDebug("FOR1 start");

        // Enter a new scope in the symbol table
        if (node.useNewScope) {
            emitterVisitor.ctx.symbolTable.enterScope();
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Create labels for the loop
        Label loopStart = new Label();
        Label loopEnd = new Label();
        Label continueLabel = new Label();

        // Emit the list and create an Iterator<Runtime>
        node.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "iterator", "()Ljava/util/Iterator;", true);

        // Setup 'local' environment if needed
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Start of the loop
        mv.visitLabel(loopStart);

        // Check if the iterator has more elements
        mv.visitInsn(Opcodes.DUP); // Duplicate the iterator on the stack to use it for hasNext and next
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);

        // Retrieve the next element from the iterator
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeScalar"); // Cast the object to the appropriate type

        // Assign it to the loop variable
        node.variable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitInsn(Opcodes.SWAP); // Move the target first
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        mv.visitInsn(Opcodes.POP);  // We don't need the variable in the stack
        // Stack: [iterator]

        emitterVisitor.ctx.javaClassInfo.incrementStackLevel(1);

        // Add redo label
        Label redoLabel = new Label();
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                loopEnd);

        // Visit the body of the loop
        node.body.accept(emitterVisitor.with(RuntimeContextType.VOID));

        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        // Add continue label
        mv.visitLabel(continueLabel);

        // Restore 'local' environment if 'next' was called
        Local.localTeardown(localRecord, mv);

        // Execute continue block if it exists
        if (node.continueBlock != null) {
            node.continueBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
        }

        // Jump back to the start of the loop
        mv.visitJumpInsn(Opcodes.GOTO, loopStart);

        // End of the loop
        mv.visitLabel(loopEnd);

        // Restore 'local' environment if 'last' was called
        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.javaClassInfo.decrementStackLevel(1);

        // Pop the iterator from the stack
        mv.visitInsn(Opcodes.POP);

        // If the context is not VOID, push "undef" to the stack
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", "undef", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }

        // Exit the scope in the symbol table
        if (node.useNewScope) {
            emitterVisitor.ctx.symbolTable.exitScope();
        }

        emitterVisitor.ctx.logDebug("FOR1 end");
    }

    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node The block node representing the block of statements.
     */
    static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        emitterVisitor.ctx.logDebug("generateCodeBlock start");
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
                    nextLabel);
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
     * @param node The for-loop node representing the do-while loop.
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

    static void emitTryCatch(EmitterContext ctx, TryNode node) {
        ctx.logDebug("visit(TryNode) - Placeholder for try-catch-finally emission");

        // Placeholder for emitting try-catch-finally block
        // TODO: Implement the logic to emit bytecode for try-catch-finally

        // For now, just log the presence of the try-catch-finally structure
        if (node.tryBlock != null) {
            ctx.logDebug("Try block present");
        }
        if (node.catchBlock != null) {
            ctx.logDebug("Catch block present");
        }
        if (node.finallyBlock != null) {
            ctx.logDebug("Finally block present");
        }
    }
}
