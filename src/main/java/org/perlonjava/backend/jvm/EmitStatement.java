package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.ConstantFoldingVisitor;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The EmitStatement class is responsible for handling various control flow statements
 * and generating the corresponding bytecode using ASM.
 */
public class EmitStatement {

    /**
     * Emits bytecode to null out JVM local variable slots for {@code my} variables
     * going out of scope. This enables the JVM GC to collect objects (like anonymous
     * filehandle globs from {@code open(my $fh, ...)}) that are no longer accessible
     * from Perl code but would otherwise be held alive by the JVM stack frame.
     * <p>
     * Must be called BEFORE {@code exitScope()} so the symbol table still has
     * the variable entries.
     *
     * @param ctx        The emitter context with the MethodVisitor and symbol table
     * @param scopeIndex The scope boundary being exited
     * @param closeIO    If true, also call scopeExitCleanup on scalar variables to
     *                   deterministically close IO on anonymous globs. Only set this
     *                   to true in LOOP bodies where the value is discarded (VOID
     *                   context). Do NOT set true for blocks that return values
     *                   (do blocks, subroutine bodies, if/else blocks) because the
     *                   returned value might be a file handle still in use by the caller.
     */
    static void emitScopeExitNullStores(EmitterContext ctx, int scopeIndex, boolean closeIO) {
        if (closeIO) {
            // For scalar variables in loop bodies, call cleanup to close IO
            // on anonymous globs and fire DESTROY on blessed objects.
            java.util.List<Integer> scalarIndices = ctx.symbolTable.getMyScalarIndicesInScope(scopeIndex);
            for (int idx : scalarIndices) {
                ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        "scopeExitCleanup",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V",
                        false);
            }
        }
        // Null all my variable slots to help GC collect associated objects
        java.util.List<Integer> allIndices = ctx.symbolTable.getMyVariableIndicesInScope(scopeIndex);
        for (int idx : allIndices) {
            ctx.mv.visitInsn(Opcodes.ACONST_NULL);
            ctx.mv.visitVarInsn(Opcodes.ASTORE, idx);
        }
    }

    /** Convenience overload: null stores only, no IO/DESTROY cleanup (safe for all contexts). */
    static void emitScopeExitNullStores(EmitterContext ctx, int scopeIndex) {
        emitScopeExitNullStores(ctx, scopeIndex, false);
    }

    /**
     * Emits bytecode to check for pending signals (like SIGALRM from alarm()).
     * This is a lightweight check - just a volatile boolean read if no signals are pending.
     * Should be called at safe execution points like loop entries.
     *
     * @param mv The MethodVisitor to emit bytecode to.
     */
    public static void emitSignalCheck(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/PerlSignalQueue",
                "checkPendingSignals",
                "()V",
                false);
    }

    /**
     * Emits bytecode for an if statement, including support for 'unless'.
     * Performs dead code elimination when the condition is a compile-time constant.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The if node representing the if statement.
     */
    public static void emitIf(EmitterVisitor emitterVisitor, IfNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("IF start: " + node.operator);

        // Try to evaluate the condition at compile time for dead code elimination
        String currentPackage = emitterVisitor.ctx.symbolTable.getCurrentPackage();
        Boolean constantValue = ConstantFoldingVisitor.getConstantConditionValue(node.condition, currentPackage);

        // For "unless", invert the condition
        if (constantValue != null && "unless".equals(node.operator)) {
            constantValue = !constantValue;
        }

        // If we have a constant condition, we can eliminate dead code
        if (constantValue != null) {
            if (CompilerOptions.DEBUG_ENABLED) {
                emitterVisitor.ctx.logDebug("IF constant folding: condition is " + constantValue);
            }

            if (constantValue) {
                // Condition is constant true - emit only the then branch
                // Still need to set up scope and labels for potential nested constructs
                List<String> branchLabels = new ArrayList<>();
                EmitBlock.collectIfChainLabels(node, branchLabels);
                int branchLabelsPushed = EmitBlock.pushNewGotoLabels(emitterVisitor.ctx.javaClassInfo, branchLabels);

                int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
                node.thenBranch.accept(emitterVisitor);
                emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex);
                emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

                for (int i = 0; i < branchLabelsPushed; i++) {
                    emitterVisitor.ctx.javaClassInfo.popGotoLabels();
                }
            } else {
                // Condition is constant false - emit only the else branch
                if (node.elseBranch != null) {
                    List<String> branchLabels = new ArrayList<>();
                    EmitBlock.collectIfChainLabels(node, branchLabels);
                    int branchLabelsPushed = EmitBlock.pushNewGotoLabels(emitterVisitor.ctx.javaClassInfo, branchLabels);

                    int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
                    node.elseBranch.accept(emitterVisitor);
                    emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex);
                    emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

                    for (int i = 0; i < branchLabelsPushed; i++) {
                        emitterVisitor.ctx.javaClassInfo.popGotoLabels();
                    }
                } else {
                    // No else branch - emit condition value if not void context
                    // Perl returns the condition value when no branch is taken
                    if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    }
                }
            }

            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("IF end (constant folded)");
            return;
        }

        // Non-constant condition - emit normal if/else code
        List<String> branchLabels = new ArrayList<>();
        EmitBlock.collectIfChainLabels(node, branchLabels);
        int branchLabelsPushed = EmitBlock.pushNewGotoLabels(emitterVisitor.ctx.javaClassInfo, branchLabels);

        // Enter a new scope in the symbol table
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels for the else and end branches
        Label elseLabel = new Label();
        Label endLabel = new Label();

        // When there's no else branch and we need a result value, DUP the condition
        // so the condition value is returned when no branch is taken (Perl semantics)
        boolean needConditionValue = (node.elseBranch == null && emitterVisitor.ctx.contextType != RuntimeContextType.VOID);

        // Visit the condition node in scalar context
        node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        if (needConditionValue) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
        }

        // Convert the result to a boolean
        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getBoolean", "()Z", false);

        // Jump to the else label if the condition is false
        emitterVisitor.ctx.mv.visitJumpInsn(node.operator.equals("unless") ? Opcodes.IFNE : Opcodes.IFEQ, elseLabel);

        // Visit the then branch (condition was true for if, false for unless)
        if (needConditionValue) {
            emitterVisitor.ctx.mv.visitInsn(Opcodes.POP); // discard DUPed condition value
        }
        node.thenBranch.accept(emitterVisitor);

        // Jump to the end label after executing the then branch
        emitterVisitor.ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // Visit the else label
        emitterVisitor.ctx.mv.visitLabel(elseLabel);

        // Visit the else branch if it exists
        if (node.elseBranch != null) {
            node.elseBranch.accept(emitterVisitor);
        } else if (!needConditionValue) {
            // VOID context, no value needed on stack
        }
        // else: needConditionValue is true, DUPed condition value is already on stack

        // Visit the end label
        emitterVisitor.ctx.mv.visitLabel(endLabel);

        // Exit the scope in the symbol table
        emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex);
        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        for (int i = 0; i < branchLabelsPushed; i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("IF end");
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
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("FOR3 start");
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

            int regexStateLocal = -1;
            if (!node.isSimpleBlock && node.useNewScope && RegexUsageDetector.containsRegexOperation(node)) {
                regexStateLocal = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RegexState");
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/runtimetypes/RegexState", "<init>", "()V", false);
                mv.visitVarInsn(Opcodes.ASTORE, regexStateLocal);
                if (node.body != null) {
                    node.body.setAnnotation("skipRegexSaveRestore", true);
                }
            }

            // Visit the initialization node (executed once at the start)
            if (node.initialization != null) {
                node.initialization.accept(voidVisitor);
            }

            // For while/for loops in non-void context, allocate a register to save
            // the condition value so the false condition is returned on normal exit.
            boolean needWhileConditionResult = !node.isSimpleBlock
                    && node.condition != null
                    && emitterVisitor.ctx.contextType != RuntimeContextType.VOID;
            int conditionResultReg = -1;
            if (needWhileConditionResult) {
                conditionResultReg = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                EmitOperator.emitUndef(mv);
                mv.visitVarInsn(Opcodes.ASTORE, conditionResultReg);
            }

            // Visit the start label (this is where the loop condition and body are)
            mv.visitLabel(startLabel);

            // Check for pending signals (alarm, etc.) at loop entry
            emitSignalCheck(mv);

            // Visit the condition node in scalar context
            if (node.condition != null) {
                node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                if (needWhileConditionResult) {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, conditionResultReg);
                }

                // Convert the result to a boolean
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getBoolean", "()Z", false);

                // Jump to the end label if the condition is false (exit the loop)
                mv.visitJumpInsn(Opcodes.IFEQ, endLabel);

                if (needWhileConditionResult) {
                    // Clear register to undef so 'last' returns undef, not condition value
                    EmitOperator.emitUndef(mv);
                    mv.visitVarInsn(Opcodes.ASTORE, conditionResultReg);
                }
            }

            // Add redo label
            Label redoLabel = new Label();
            mv.visitLabel(redoLabel);

            // For simple blocks (bare blocks like { ... }) in non-void context,
            // use register spilling to capture the result: allocate a local variable,
            // tell the block to store its last element's value there, then load it after endLabel.
            // This ensures consistent stack state across all code paths (including last/next jumps).
            // Apply for SCALAR/LIST contexts - bare blocks always return their value in Perl.
            // Note: Only apply to UNLABELED bare blocks. Labeled blocks like TODO: { ... } should
            // not return their value (this would break Test::More's TODO handling).
            // RUNTIME context is NOT included because it causes issues with Test2 context handling.
            boolean needsReturnValue = node.isSimpleBlock
                    && node.labelName == null  // Only bare blocks, not labeled blocks
                    && (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR
                        || emitterVisitor.ctx.contextType == RuntimeContextType.LIST);
            int resultReg = -1;

            if (node.useNewScope) {
                // Register next/redo/last labels
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("FOR3 label: " + node.labelName);
                // A simple-block For3Node (isSimpleBlock=true) is used to model bare/labeled
                // blocks like `{ ... }` and `LABEL: { ... }` (including `... } continue { ... }`).
                // In Perl, blocks (labeled or not) are valid targets for unlabeled last/next/redo.
                // They act as loops that execute once. SKIP: { last SKIP; } patterns use labeled
                // last, so making labeled blocks targetable by unlabeled last is safe.
                emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                        node.labelName,
                        continueLabel,
                        redoLabel,
                        endLabel,
                        RuntimeContextType.VOID,
                        true,
                        true);

                // Visit the loop body
                if (needsReturnValue) {
                    // Allocate a local variable for the result
                    resultReg = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    // Initialize it to undef (in case last/next is called before last statement)
                    EmitOperator.emitUndef(mv);
                    mv.visitVarInsn(Opcodes.ASTORE, resultReg);
                    // Tell the block to store its last element's value in this register
                    node.body.setAnnotation("resultRegister", resultReg);
                    // Visit body in VOID context (consistent stack state)
                    node.body.accept(voidVisitor);
                    // NOTE: Don't load the result here! We load it after endLabel so that
                    // all paths (normal, last, next) converge with empty stack, then load.
                } else {
                    node.body.accept(voidVisitor);
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

            if (regexStateLocal >= 0) {
                mv.visitVarInsn(Opcodes.ALOAD, regexStateLocal);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RegexState", "restore", "()V", false);
            }

            // Exit the scope in the symbol table
            if (node.useNewScope) {
                emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, !node.isSimpleBlock);
                emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
            }

            // If the context is not VOID, push a value to the stack
            // For simple blocks with resultReg, load the captured result
            // For while/for loops with conditionResultReg, load the condition value
            // Otherwise, push undef
            if (needsReturnValue && resultReg >= 0) {
                // Load the result from the register (all paths converge here with empty stack)
                mv.visitVarInsn(Opcodes.ALOAD, resultReg);
            } else if (needWhileConditionResult && conditionResultReg >= 0) {
                // Load the false condition value (or undef if 'last' was used)
                mv.visitVarInsn(Opcodes.ALOAD, conditionResultReg);
            } else if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                EmitOperator.emitUndef(emitterVisitor.ctx.mv);
            }

            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("FOR end");
        }
    }

    /**
     * Emits bytecode for a do-while loop.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The for-loop node representing the do-while loop.
     */
    static void emitDoWhile(EmitterVisitor emitterVisitor, For3Node node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("DO-WHILE start");
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Enter a new scope in the symbol table
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        // Create labels
        Label startLabel = new Label();
        Label continueLabel = new Label();
        Label endLabel = new Label();
        Label redoLabel = new Label();

        int regexStateLocal = -1;
        if (RegexUsageDetector.containsRegexOperation(node)) {
            regexStateLocal = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RegexState");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, regexStateLocal);
            if (node.body != null) {
                node.body.setAnnotation("skipRegexSaveRestore", true);
            }
        }

        // Register loop labels as pseudo-loop (isTrueLoop = false)
        // This allows us to throw proper compile errors for last/next/redo in do-while
        emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                node.labelName,
                continueLabel,
                redoLabel,
                endLabel,
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
                RuntimeContextType.VOID,
                false);
        emitRegistryCheck(mv, loopLabels, redoLabel, continueLabel, endLabel);

        // Continue label (for next iteration)
        mv.visitLabel(continueLabel);

        // Check if condition is a compile-time constant (e.g., "do {} until TRUE_CONST")
        String currentPackage = emitterVisitor.ctx.symbolTable.getCurrentPackage();
        Boolean constantCondition = ConstantFoldingVisitor.getConstantConditionValue(node.condition, currentPackage);

        if (constantCondition != null) {
            if (constantCondition) {
                // Condition is constant true — infinite loop, jump back unconditionally
                mv.visitJumpInsn(Opcodes.GOTO, startLabel);
            }
            // else: condition is constant false — don't jump back, body runs exactly once
        } else {
            // Non-constant condition — emit normal runtime evaluation
            node.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "getBoolean", "()Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, startLabel);
        }

        // End of loop
        mv.visitLabel(endLabel);

        if (regexStateLocal >= 0) {
            mv.visitVarInsn(Opcodes.ALOAD, regexStateLocal);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "restore", "()V", false);
        }

        // Pop loop labels
        emitterVisitor.ctx.javaClassInfo.popLoopLabels();

        // Exit the scope in the symbol table
        emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, true);
        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);

        // If the context is not VOID, push "undef" to the stack
        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
            EmitOperator.emitUndef(emitterVisitor.ctx.mv);
        }

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("DO-WHILE end");
    }

    public static void emitTryCatch(EmitterVisitor emitterVisitor, TryNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("emitTryCatch start");

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
                "org/perlonjava/runtime/runtimetypes/ErrorMessageUtil",
                "stringifyException",
                "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
        // Transform catch parameter to 'my'
        OperatorNode catchParameter = new OperatorNode("my", node.catchParameter, node.tokenIndex);
        // Create the lexical variable for the catch parameter, push it to the stack
        catchParameter.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                "set",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
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
            // Track that we're inside a finally block for control flow checks
            emitterVisitor.ctx.javaClassInfo.finallyBlockDepth++;
            try {
                node.finallyBlock.accept(emitterVisitor.with(RuntimeContextType.VOID));
            } finally {
                emitterVisitor.ctx.javaClassInfo.finallyBlockDepth--;
            }
        }
        mv.visitLabel(finallyEnd);

        if (resultSlot >= 0) {
            mv.visitVarInsn(Opcodes.ALOAD, resultSlot);
        }

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("emitTryCatch end");
    }

    /**
     * Emits bytecode for a defer statement.
     *
     * <p>A defer block is compiled as a closure (anonymous subroutine) that captures
     * the current lexical scope. At runtime, the closure is wrapped in a DeferBlock
     * and pushed onto the DynamicVariableManager stack. When the enclosing scope exits
     * (via popToLocalLevel in the finally block), the defer block's code is executed.</p>
     *
     * <p>The defer block captures the enclosing subroutine's {@code @_} at registration
     * time, so the block sees the same {@code @_} as the enclosing scope (per Perl semantics).</p>
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The defer node representing the defer statement.
     */
    public static void emitDefer(EmitterVisitor emitterVisitor, org.perlonjava.frontend.astnode.DeferNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("emitDefer start");

        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Compile the defer block as a closure (anonymous subroutine)
        // This captures lexical variables at the point where defer is encountered
        // The SubroutineNode compilation returns a RuntimeScalar (code reference)
        org.perlonjava.frontend.astnode.SubroutineNode closureNode =
            new org.perlonjava.frontend.astnode.SubroutineNode(
                null, null, null, node.block, false, node.tokenIndex);
        // Mark this subroutine as a defer block - control flow restrictions apply
        closureNode.setAnnotation("isDeferBlock", true);
        closureNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        // Stack: RuntimeScalar (the code reference)

        // Load the current @_ to capture it for the defer block
        // @_ is at local variable slot 1 in subroutines (declared as "our" in symbol table)
        // This ensures the defer block sees the same @_ as the enclosing scope
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // Stack: RuntimeScalar, RuntimeArray (@_)

        // Create a new DeferBlock with the code reference and captured @_
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/DeferBlock");
        mv.visitInsn(Opcodes.DUP_X2);
        mv.visitInsn(Opcodes.DUP_X2);
        mv.visitInsn(Opcodes.POP);
        // Stack: DeferBlock, DeferBlock, RuntimeScalar, RuntimeArray
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/runtimetypes/DeferBlock",
                "<init>",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)V",
                false);
        // Stack: DeferBlock

        // Push the DeferBlock onto the dynamic variable stack
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/DynamicVariableManager",
                "pushLocalVariable",
                "(Lorg/perlonjava/runtime/runtimetypes/DynamicState;)V",
                false);
        // Stack: empty

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("emitDefer end");
    }

    /**
     * Emit bytecode to check RuntimeControlFlowRegistry and handle any registered control flow.
     * This is called after loop body execution to catch non-local control flow markers.
     *
     * @param mv         The MethodVisitor
     * @param loopLabels The current loop's labels
     * @param redoLabel  The redo target
     * @param nextLabel  The next/continue target
     * @param lastLabel  The last/exit target
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
                "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowRegistry",
                "checkLoopAndGetAction",
                "(Ljava/lang/String;)I",
                false);

        // Use TABLESWITCH for clean bytecode.
        // IMPORTANT: action 0 means "no marker" and must *not* jump.
        Label noAction = new Label();
        mv.visitTableSwitchInsn(
                0,  // min (NONE)
                3,  // max (REDO)
                noAction,  // default
                noAction,  // 0: NONE
                lastLabel, // 1: LAST
                nextLabel, // 2: NEXT
                redoLabel  // 3: REDO
        );

        mv.visitLabel(noAction);
    }
}
