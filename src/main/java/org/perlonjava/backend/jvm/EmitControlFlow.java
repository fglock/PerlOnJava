package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.runtimetypes.ControlFlowType;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

/**
 * Handles the emission of control flow bytecode instructions for Perl-like language constructs.
 * This class manages loop control operators (next, last, redo), subroutine returns, and goto statements.
 */
public class EmitControlFlow {
    // Feature flags for control flow implementation
    // Set to true to enable tagged return values for non-local control flow (Phase 2 - ACTIVE)
    private static final boolean ENABLE_TAGGED_RETURNS = true;

    // Set to true to enable debug output for control flow operations
    private static final boolean DEBUG_CONTROL_FLOW = false;

    /**
     * Handles the 'next', 'last', and 'redo' operators for loop control.
     * - 'next' is equivalent to 'continue' in Java
     * - 'last' is equivalent to 'break' in Java
     * - 'redo' restarts the current loop iteration
     *
     * @param ctx  The current emitter context containing compilation state
     * @param node The operator node representing the control flow statement
     * @throws PerlCompilerException if the operator is used outside a loop block
     */
    static void handleNextOperator(EmitterContext ctx, OperatorNode node) {
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(next)");

        String operator = node.operator;
        
        // Check if we're inside a defer block - control flow out of defer is prohibited
        if (ctx.javaClassInfo.isInDeferBlock) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"" + operator + "\" out of a \"defer\" block",
                    ctx.errorUtil);
        }
        
        // Check if we're inside a finally block - control flow out of finally is prohibited
        if (ctx.javaClassInfo.finallyBlockDepth > 0) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"" + operator + "\" out of a \"finally\" block",
                    ctx.errorUtil);
        }

        // Initialize label string for labeled loops
        String labelStr = null;
        // Defensive: ensure operand is a ListNode (parser should always create ListNode here)
        ListNode labelNode;
        if (node.operand instanceof ListNode) {
            labelNode = (ListNode) node.operand;
        } else {
            // Wrap non-ListNode in a ListNode to handle edge cases
            labelNode = ListNode.makeList(node.operand);
        }
        if (!labelNode.elements.isEmpty()) {
            // Handle 'next' with a label.
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                // Extract the label name.
                labelStr = ((IdentifierNode) arg).name;
            } else {
                // Dynamic label: last EXPR, next EXPR, redo EXPR
                // Fall back to interpreter which supports dynamic label evaluation
                throw new PerlCompilerException(node.tokenIndex,
                        "Dynamic loop control EXPR requires interpreter fallback", ctx.errorUtil);
            }
        }

        // Find loop labels by name.
        LoopLabels loopLabels;
        if (labelStr == null) {
            // Unlabeled next/last/redo target the nearest enclosing true loop.
            // This avoids mis-targeting bare/labeled blocks like SKIP: { ... }.
            loopLabels = ctx.javaClassInfo.findInnermostTrueLoopLabels();
        } else {
            loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
        }
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);

        // Check if we're trying to use next/last/redo in a pseudo-loop (do-while/bare block)
        if (loopLabels != null && !loopLabels.isTrueLoop) {
            throw new PerlCompilerException(node.tokenIndex,
                    "Can't \"" + operator + "\" outside a loop block",
                    ctx.errorUtil);
        }

        if (loopLabels == null) {
            // Non-local control flow: return tagged RuntimeControlFlowList
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(next): Non-local control flow for " + operator + " " + labelStr);

            // Determine control flow type
            ControlFlowType type = operator.equals("next") ? ControlFlowType.NEXT
                    : operator.equals("last") ? ControlFlowType.LAST
                    : ControlFlowType.REDO;

            // Create RuntimeControlFlowList: new RuntimeControlFlowList(type, label, fileName, lineNumber)
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                    type.name(),
                    "Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;");
            if (labelStr != null) {
                ctx.mv.visitLdcInsn(labelStr);
            } else {
                ctx.mv.visitInsn(Opcodes.ACONST_NULL);
            }
            // Push fileName (from CompilerOptions)
            ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
            // Push lineNumber (from errorUtil if available)
            int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.tokenIndex) : 0;
            ctx.mv.visitLdcInsn(lineNumber);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "<init>",
                    "(Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;Ljava/lang/String;Ljava/lang/String;I)V",
                    false);

            // Return the tagged list via returnLabel so that local variable teardown
            // (popToLocalLevel) runs before the method exits. A direct ARETURN would
            // bypass the cleanup at returnLabel, leaving `local` variables un-restored.
            if (ctx.javaClassInfo.returnLabel != null) {
                ctx.mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.returnValueSlot);
                ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
            } else {
                ctx.mv.visitInsn(Opcodes.ARETURN);
            }
            return;
        }

        // Handle return values based on context
        if (loopLabels.context != RuntimeContextType.VOID) {
            if (operator.equals("next") || operator.equals("last")) {
                // For non-void contexts, ensure an 'undef' value is pushed to maintain stack consistency
                EmitOperator.emitUndef(ctx.mv);
            }
        }

        // Select the appropriate jump target based on the operator type
        Label label = operator.equals("next") ? loopLabels.nextLabel
                : operator.equals("last") ? loopLabels.lastLabel
                : loopLabels.redoLabel;
        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    /**
     * Handles the 'return' operator for subroutine exits.
     * Processes both single and multiple return values, ensuring proper stack management.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the return statement
     */
    static void handleReturnOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Check if we're inside a defer block - return out of defer is prohibited
        if (ctx.javaClassInfo.isInDeferBlock) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"return\" out of a \"defer\" block",
                    ctx.errorUtil);
        }
        
        // Check if we're inside a finally block - return out of finally is prohibited
        if (ctx.javaClassInfo.finallyBlockDepth > 0) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"return\" out of a \"finally\" block",
                    ctx.errorUtil);
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(return) in context " + emitterVisitor.ctx.contextType);
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(return) will visit " + node.operand + " in context " + emitterVisitor.ctx.with(RuntimeContextType.RUNTIME).contextType);

        boolean hasOperand = !(node.operand == null || (node.operand instanceof ListNode list && list.elements.isEmpty()));

        if (!hasOperand) {
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "<init>",
                    "()V",
                    false);
        } else if (node.operand instanceof ListNode list && list.elements.size() == 1) {
            list.elements.getFirst().accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        } else {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        }

        // Clone scalar elements to prevent aliasing issues with local variable teardown.
        // Without this, returning a symbolic dereference like ${$name} with local *{$name}
        // would return the restored (empty) value instead of the value at return time.
        // Only needed when the subroutine uses 'local'.
        if (ctx.javaClassInfo.usesLocal) {
            // First ensure we have a RuntimeList (the stack may have RuntimeScalar in some cases),
            // then clone the scalar elements.
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                    "getList",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "cloneScalars",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
        }

        if (ctx.javaClassInfo.isMapGrepBlock) {
            // Non-local return from map/grep block: wrap the return value in a
            // RuntimeControlFlowList(RETURN) marker so it propagates to the enclosing subroutine.
            // Stack: [returnValue : RuntimeBase]
            int tempSlot = ctx.javaClassInfo.acquireSpillSlot();
            boolean pooled = tempSlot >= 0;
            if (!pooled) {
                tempSlot = ctx.symbolTable.allocateLocalVariable();
            }
            ctx.mv.visitVarInsn(Opcodes.ASTORE, tempSlot);

            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitVarInsn(Opcodes.ALOAD, tempSlot);
            // Push fileName
            ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
            // Push lineNumber
            int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.tokenIndex) : 0;
            ctx.mv.visitLdcInsn(lineNumber);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "<init>",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Ljava/lang/String;I)V",
                    false);

            if (pooled) {
                ctx.javaClassInfo.releaseSpillSlot();
            }
        }

        // Defer refCount decrements for blessed my-scalars in scope.
        // Explicit 'return' jumps to returnLabel, bypassing per-scope
        // emitScopeExitNullStores. Without this, local variables holding blessed
        // references keep refCount > 0 after the method returns, preventing DESTROY.
        // Spill the return value, emit cleanup, then reload.
        java.util.List<Integer> scalarIndices = ctx.symbolTable.getMyScalarIndicesInScope(0);
        java.util.List<Integer> hashIndices = ctx.symbolTable.getMyHashIndicesInScope(0);
        java.util.List<Integer> arrayIndices = ctx.symbolTable.getMyArrayIndicesInScope(0);
        if (!scalarIndices.isEmpty() || !hashIndices.isEmpty() || !arrayIndices.isEmpty()) {
            JavaClassInfo.SpillRef spillRef = ctx.javaClassInfo.acquireSpillRefOrAllocate(ctx.symbolTable);
            ctx.javaClassInfo.storeSpillRef(ctx.mv, spillRef);
            for (int idx : scalarIndices) {
                ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/MortalList",
                        "deferDecrementIfNotCaptured",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V",
                        false);
            }
            // Also process hash/array variables — their elements may hold tracked
            // references that need refCount decrements on scope exit.
            for (int idx : hashIndices) {
                ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/MortalList",
                        "scopeExitCleanupHash",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;)V",
                        false);
            }
            for (int idx : arrayIndices) {
                ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/MortalList",
                        "scopeExitCleanupArray",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)V",
                        false);
            }
            ctx.javaClassInfo.loadSpillRef(ctx.mv, spillRef);
            ctx.javaClassInfo.releaseSpillRef(spillRef);
        }

        ctx.mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.returnValueSlot);
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
    }

    /**
     * Handles 'goto &NAME' tail call optimization.
     * Creates a TAILCALL marker with the coderef and arguments.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param subNode        The operator node for the subroutine reference (&NAME)
     * @param argsNode       The node representing the arguments
     * @param tokenIndex     The token index for error reporting
     * @param evalScope      The eval scope type ("eval-block", "eval-string", or null)
     */
    static void handleGotoSubroutine(EmitterVisitor emitterVisitor, OperatorNode subNode, Node argsNode, int tokenIndex, String evalScope) {
        EmitterContext ctx = emitterVisitor.ctx;

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto &sub): Emitting TAILCALL marker");

        subNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        int codeRefSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledCodeRef = codeRefSlot >= 0;
        if (!pooledCodeRef) {
            codeRefSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, codeRefSlot);

        argsNode.accept(emitterVisitor.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                "getList",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                false);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeList",
                "getArrayOfAlias",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
                false);
        int argsSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledArgs = argsSlot >= 0;
        if (!pooledArgs) {
            argsSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, argsSlot);

        // Create TAILCALL marker with eval scope for runtime check.
        // Teardown (defer blocks, local variables) happens at returnLabel
        // before this marker is returned to the caller.
        // The caller's call-site trampoline handles the actual tail call.

        ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
        ctx.mv.visitInsn(Opcodes.DUP);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, argsSlot);
        ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
        int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(tokenIndex) : 0;
        ctx.mv.visitLdcInsn(lineNumber);
        // Push evalScope (null if not in eval)
        if (evalScope != null) {
            ctx.mv.visitLdcInsn(evalScope);
        } else {
            ctx.mv.visitInsn(Opcodes.ACONST_NULL);
        }
        ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                "<init>",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;Ljava/lang/String;ILjava/lang/String;)V",
                false);

        if (pooledArgs) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledCodeRef) {
            ctx.javaClassInfo.releaseSpillSlot();
        }

        // Jump to returnLabel (teardown happens there, then marker returned to caller)
        ctx.mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.returnValueSlot);
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
    }

    /**
     * Handles 'goto &{expr}' tail call with symbolic code dereference.
     * Evaluates the block to get the subroutine name, looks up the CODE reference,
     * and creates a TAILCALL marker.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param blockNode      The block node containing the expression for the subroutine name
     * @param argsNode       The node representing the arguments
     * @param tokenIndex     The token index for error reporting
     * @param evalScope      The eval scope type ("eval-block", "eval-string", or null)
     */
    static void handleGotoSubroutineBlock(EmitterVisitor emitterVisitor, BlockNode blockNode, Node argsNode, int tokenIndex, String evalScope) {
        EmitterContext ctx = emitterVisitor.ctx;

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto &{expr}): Emitting TAILCALL marker");

        // Evaluate the block to get the subroutine name/reference
        blockNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Call codeDerefNonStrict to look up the CODE reference from the string/glob
        emitterVisitor.pushCurrentPackage();
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                "codeDerefNonStrict",
                "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);

        int codeRefSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledCodeRef = codeRefSlot >= 0;
        if (!pooledCodeRef) {
            codeRefSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, codeRefSlot);

        // Build the args array
        argsNode.accept(emitterVisitor.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                "getList",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                false);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeList",
                "getArrayOfAlias",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
                false);
        int argsSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledArgs = argsSlot >= 0;
        if (!pooledArgs) {
            argsSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, argsSlot);

        // Create TAILCALL marker with eval scope for runtime check
        ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
        ctx.mv.visitInsn(Opcodes.DUP);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, argsSlot);
        ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
        int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(tokenIndex) : 0;
        ctx.mv.visitLdcInsn(lineNumber);
        // Push evalScope (null if not in eval)
        if (evalScope != null) {
            ctx.mv.visitLdcInsn(evalScope);
        } else {
            ctx.mv.visitInsn(Opcodes.ACONST_NULL);
        }
        ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                "<init>",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;Ljava/lang/String;ILjava/lang/String;)V",
                false);

        if (pooledArgs) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledCodeRef) {
            ctx.javaClassInfo.releaseSpillSlot();
        }

        // Jump to returnLabel (teardown happens there, then marker returned to caller)
        ctx.mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.returnValueSlot);
        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
    }

    /**
     * Handles goto statements with labels (both static and computed).
     * Validates label existence and manages stack cleanup before jumping.
     * Supports: goto LABEL, goto EXPR (computed labels)
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the goto statement
     * @throws PerlCompilerException if the label is missing or invalid
     */
    static void handleGotoLabel(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Check if we're inside a defer block - goto out of defer is prohibited
        if (ctx.javaClassInfo.isInDeferBlock) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"goto\" out of a \"defer\" block",
                    ctx.errorUtil);
        }
        
        // Check if we're inside a finally block - goto out of finally is prohibited
        if (ctx.javaClassInfo.finallyBlockDepth > 0) {
            throw new PerlCompilerException(node.tokenIndex, 
                    "Can't \"goto\" out of a \"finally\" block",
                    ctx.errorUtil);
        }

        // Parse the goto argument
        String labelName = null;

        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();

            // Check if it's a static label (IdentifierNode)
            if (arg instanceof IdentifierNode) {
                labelName = ((IdentifierNode) arg).name;
            } else {
                // Check if this is goto &NAME or goto &{expr} - a subroutine call form
                // The parser produces: BinaryOperatorNode "(" with left=OperatorNode "&" (for &NAME)
                // or left=BlockNode (for &{expr}) and right=args
                if (arg instanceof BinaryOperatorNode callNode && callNode.operator.equals("(")) {
                    Node callTarget = callNode.left;

                    // Determine eval scope for runtime check (Perl checks undefined sub before eval context)
                    boolean isGotoSub = (callTarget instanceof OperatorNode opNode2 && opNode2.operator.equals("&"))
                            || callTarget instanceof BlockNode
                            || (callTarget instanceof OperatorNode opNode3 && opNode3.operator.equals("$"));
                    String evalScope = null;
                    if (isGotoSub) {
                        if (ctx.javaClassInfo.isInEvalBlock) evalScope = "eval-block";
                        else if (ctx.javaClassInfo.isInEvalString) evalScope = "eval-string";
                    }

                    if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("&")) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto): Detected goto &NAME tail call");
                        handleGotoSubroutine(emitterVisitor, opNode, callNode.right, node.tokenIndex, evalScope);
                        return;
                    }
                    // Handle goto &{expr} - symbolic code dereference
                    // BlockNode contains an expression that evaluates to a subroutine name
                    if (callTarget instanceof BlockNode blockNode) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto): Detected goto &{expr} tail call");
                        handleGotoSubroutineBlock(emitterVisitor, blockNode, callNode.right, node.tokenIndex, evalScope);
                        return;
                    }
                    // Handle goto &$sub - variable code dereference
                    // The parser transforms &$sub into $sub(@_), so callTarget is OperatorNode("$", ...)
                    if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("$")) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto): Detected goto &$var tail call");
                        // Create a BlockNode wrapper so handleGotoSubroutineBlock can handle it
                        java.util.List<Node> elements = new java.util.ArrayList<>();
                        elements.add(callTarget);
                        BlockNode blockWrapper = new BlockNode(elements, node.tokenIndex);
                        handleGotoSubroutineBlock(emitterVisitor, blockWrapper, callNode.right, node.tokenIndex, evalScope);
                        return;
                    }
                }

                // Check if this is goto \&NAME - backslash-reference to a subroutine
                // The parser produces: OperatorNode("\", OperatorNode("&", ...))
                // Perl semantics: goto \&sub passes current @_ to the target
                if (arg instanceof OperatorNode refOp && refOp.operator.equals("\\")) {
                    String evalScope4 = null;
                    if (ctx.javaClassInfo.isInEvalBlock) evalScope4 = "eval-block";
                    else if (ctx.javaClassInfo.isInEvalString) evalScope4 = "eval-string";
                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto): Detected goto \\&NAME tail call");
                    ListNode argsNode = new ListNode(node.tokenIndex);
                    OperatorNode atUnderscore = new OperatorNode("@",
                            new IdentifierNode("_", node.tokenIndex), node.tokenIndex);
                    argsNode.elements.add(atUnderscore);
                    handleGotoSubroutine(emitterVisitor, refOp, argsNode, node.tokenIndex, evalScope4);
                    return;
                }

                // Check if this is a tail call (goto EXPR where EXPR is a coderef)
                // This handles: goto __SUB__, goto $coderef, etc.
                if (arg instanceof OperatorNode opNode && opNode.operator.equals("__SUB__")) {
                    // Determine eval scope for runtime check
                    String evalScope3 = null;
                    if (ctx.javaClassInfo.isInEvalBlock) evalScope3 = "eval-block";
                    else if (ctx.javaClassInfo.isInEvalString) evalScope3 = "eval-string";
                    if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("visit(goto): Detected goto __SUB__ tail call");
                    // Create a ListNode with @_ as the argument
                    ListNode argsNode = new ListNode(opNode.tokenIndex);
                    OperatorNode atUnderscore = new OperatorNode("@",
                            new IdentifierNode("_", opNode.tokenIndex), opNode.tokenIndex);
                    argsNode.elements.add(atUnderscore);
                    handleGotoSubroutine(emitterVisitor, opNode, argsNode, node.tokenIndex, evalScope3);
                    return;
                }

                // Dynamic label (goto EXPR) - expression evaluated at runtime
                // JVM backend can't dispatch dynamic goto labels (the GOTO marker propagates
                // out of all scopes and silently exits). Fall back to interpreter which
                // supports GOTO_DYNAMIC with label-to-PC lookup.
                throw new PerlCompilerException(node.tokenIndex,
                        "Dynamic goto EXPR requires interpreter fallback", ctx.errorUtil);
            }
        }

        // Ensure label is provided for static goto
        if (labelName == null) {
            // Bare `goto` without arguments - emit runtime die like Perl 5
            // Fall through to interpreter which handles this properly via GOTO_DYNAMIC
            throw new PerlCompilerException(node.tokenIndex,
                    "Dynamic goto EXPR requires interpreter fallback", ctx.errorUtil);
        }

        // For static label, check if it's local
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(labelName);
        if (targetLabel == null) {
            // Label not in current JVM scope - use RuntimeControlFlowList to signal
            // goto to the caller, same mechanism as dynamic goto
            String fileName = ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)";
            int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.tokenIndex) : 0;

            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                    "GOTO",
                    "Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;");
            ctx.mv.visitLdcInsn(labelName);
            ctx.mv.visitLdcInsn(fileName);
            ctx.mv.visitLdcInsn(lineNumber);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "<init>",
                    "(Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;Ljava/lang/String;Ljava/lang/String;I)V",
                    false);

            ctx.mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.returnValueSlot);
            ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
            return;
        }

        // Local goto: use fast GOTO (existing code)

        // Emit the goto instruction
        ctx.mv.visitJumpInsn(Opcodes.GOTO, targetLabel.gotoLabel);
    }
}
