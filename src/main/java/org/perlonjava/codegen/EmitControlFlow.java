package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.BinaryOperatorNode;
import org.perlonjava.astnode.IdentifierNode;
import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.ControlFlowType;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.HashSet;

import java.util.Map;
import java.util.Set;

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
        ctx.logDebug("visit(next)");

        // Initialize label string for labeled loops
        String labelStr = null;
        ListNode labelNode = (ListNode) node.operand;
        if (!labelNode.elements.isEmpty()) {
            // Handle 'next' with a label.
            Node arg = labelNode.elements.getFirst();
            if (arg instanceof IdentifierNode) {
                // Extract the label name.
                labelStr = ((IdentifierNode) arg).name;
            } else {
                throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + node, ctx.errorUtil);
            }
        }

        String operator = node.operator;
        // Find loop labels by name.
        // Note: Bare blocks are modeled as pseudo-loops so `last` can exit them.
        // However, `next`/`redo` must bind to the nearest *true* loop, skipping pseudo-loops.
        LoopLabels loopLabels;
        if (labelStr == null) {
            if (operator.equals("next") || operator.equals("redo")) {
                loopLabels = null;
                for (LoopLabels candidate : ctx.javaClassInfo.loopLabelStack) {
                    if (candidate.isTrueLoop) {
                        loopLabels = candidate;
                        break;
                    }
                }
            } else {
                // last: may exit a bare block, so bind to innermost boundary
                loopLabels = ctx.javaClassInfo.loopLabelStack.peek();
            }
        } else {
            if (operator.equals("next") || operator.equals("redo")) {
                loopLabels = null;
                for (LoopLabels candidate : ctx.javaClassInfo.loopLabelStack) {
                    if (candidate.isTrueLoop
                            && candidate.labelName != null
                            && candidate.labelName.equals(labelStr)) {
                        loopLabels = candidate;
                        break;
                    }
                }
            } else {
                // last LABEL: Perl allows labels on bare blocks too
                loopLabels = ctx.javaClassInfo.findLoopLabelsByName(labelStr);
            }
        }
        ctx.logDebug("visit(next) operator: " + operator + " label: " + labelStr + " labels: " + loopLabels);
        
        // Check if we're trying to use next/last/redo in a pseudo-loop (do-while/bare block)
        if (loopLabels != null && !loopLabels.isTrueLoop) {
            // Perl allows `last` to exit a bare block `{ ... }`, but `next` and `redo`
            // are only valid for true loops.
            if (operator.equals("next") || operator.equals("redo")) {
                throw new PerlCompilerException(node.tokenIndex,
                        "Can't \"" + operator + "\" outside a loop block",
                        ctx.errorUtil);
            }
        }
        
        if (loopLabels == null) {
            // Non-local control flow: return tagged RuntimeControlFlowList
            ctx.logDebug("visit(next): Non-local control flow for " + operator + " " + labelStr);
            
            // Determine control flow type
            ControlFlowType type = operator.equals("next") ? ControlFlowType.NEXT
                    : operator.equals("last") ? ControlFlowType.LAST
                    : ControlFlowType.REDO;
            
            // Create RuntimeControlFlowList: new RuntimeControlFlowList(type, label, fileName, lineNumber)
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeControlFlowList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitFieldInsn(Opcodes.GETSTATIC, 
                    "org/perlonjava/runtime/ControlFlowType", 
                    type.name(), 
                    "Lorg/perlonjava/runtime/ControlFlowType;");
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
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "<init>",
                    "(Lorg/perlonjava/runtime/ControlFlowType;Ljava/lang/String;Ljava/lang/String;I)V",
                    false);
            
            // Return the tagged list (will be detected at subroutine return boundary)
            ctx.mv.visitInsn(Opcodes.ARETURN);
            return;
        }

        // Local control flow: use fast GOTO (existing code)
        ctx.logDebug("visit(next): asmStackLevel: " + ctx.javaClassInfo.stackLevelManager.getStackLevel());

        // Clean up the stack before jumping by popping values up to the loop's stack level
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, loopLabels.asmStackLevel);

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

        // Ensure local variable consistency at merge point
        if (ctx.javaClassInfo.localVariableTracker != null) {
            ctx.javaClassInfo.ensureLocalVariableConsistencyBeforeJump(ctx.mv);
        }

        ctx.javaClassInfo.emitClearSpillSlots(ctx.mv);

        ctx.mv.visitJumpInsn(Opcodes.GOTO, label);
    }

    static void emitTaggedControlFlowHandling(EmitterVisitor emitterVisitor) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        Label notControlFlow = ctx.javaClassInfo.newLabel("notControlFlow");
        Label propagateToCaller = ctx.javaClassInfo.newLabel("propagateToCaller");
        Label checkGotoLabels = ctx.javaClassInfo.newLabel("checkGotoLabels");
        Label checkLoopLabels = ctx.javaClassInfo.newLabel("checkLoopLabels");

        int entryStackLevel = ctx.javaClassInfo.stackLevelManager.getStackLevel();
        int spillCount = Math.max(0, entryStackLevel - 1);
        JavaClassInfo.SpillRef[] spillRefs = null;
        if (spillCount > 0) {
            spillRefs = new JavaClassInfo.SpillRef[spillCount];
        }

        // Store the result into a temp slot so we can branch without keeping values on the operand stack.
        mv.visitVarInsn(Opcodes.ASTORE, ctx.javaClassInfo.controlFlowTempSlot);
        // Spill any extra stack intermediates that might be below the RuntimeList result.
        // This keeps branch targets consistent for ASM frame computation.
        for (int i = 0; i < spillCount; i++) {
            JavaClassInfo.SpillRef ref = ctx.javaClassInfo.acquireSpillRefOrAllocate(ctx.symbolTable);
            spillRefs[i] = ref;
            ctx.javaClassInfo.storeSpillRef(mv, ref);
        }
        ctx.javaClassInfo.resetStackLevel();

        // Load and check if it's a control flow marker
        mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeList",
                "isNonLocalGoto",
                "()Z",
                false);
        mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);

        // Marked: load control flow type ordinal into controlFlowActionSlot
        mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeControlFlowList",
                "getControlFlowType",
                "()Lorg/perlonjava/runtime/ControlFlowType;",
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/ControlFlowType",
                "ordinal",
                "()I",
                false);
        mv.visitVarInsn(Opcodes.ISTORE, ctx.javaClassInfo.controlFlowActionSlot);

        // Try to handle locally.
        // - Ordinal 3 (GOTO): match against local goto labels
        // - Ordinals 0/1/2 (LAST/NEXT/REDO): match against loop labels
        // - Anything else (e.g. TAILCALL): propagate
        mv.visitVarInsn(Opcodes.ILOAD, ctx.javaClassInfo.controlFlowActionSlot);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, checkGotoLabels);

        mv.visitVarInsn(Opcodes.ILOAD, ctx.javaClassInfo.controlFlowActionSlot);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, propagateToCaller);

        mv.visitJumpInsn(Opcodes.GOTO, checkLoopLabels);

        // Check local goto labels
        mv.visitLabel(checkGotoLabels);
        for (GotoLabels gotoLabels : ctx.javaClassInfo.gotoLabelStack) {
            Label nextGotoCheck = ctx.javaClassInfo.newLabel("nextGotoCheck", gotoLabels.labelName);
            Label nullLabel = ctx.javaClassInfo.newLabel("nullGotoLabel", gotoLabels.labelName);

            // String label = marked.getControlFlowLabel();
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "getControlFlowLabel",
                    "()Ljava/lang/String;",
                    false);

            // if (label == null) continue;
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, nullLabel);

            // if (!label.equals(gotoLabels.labelName)) continue;
            mv.visitLdcInsn(gotoLabels.labelName);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, nextGotoCheck);

            // Match found: jump
            ctx.javaClassInfo.emitClearSpillSlots(mv);
            ctx.javaClassInfo.stackLevelManager.emitPopInstructions(mv, gotoLabels.asmStackLevel);
            mv.visitJumpInsn(Opcodes.GOTO, gotoLabels.gotoLabel);

            mv.visitLabel(nullLabel);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(nextGotoCheck);
        }

        // No goto match; propagate
        ctx.javaClassInfo.emitClearSpillSlots(mv);
        mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);

        mv.visitLabel(checkLoopLabels);

        for (LoopLabels loopLabels : ctx.javaClassInfo.loopLabelStack) {
            Label nextLoopCheck = ctx.javaClassInfo.newLabel("nextLoopCheck", loopLabels.labelName);

            // if (!marked.matchesLabel(loopLabels.labelName)) continue;
            mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/RuntimeControlFlowList");
            if (loopLabels.labelName != null) {
                mv.visitLdcInsn(loopLabels.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "matchesLabel",
                    "(Ljava/lang/String;)Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, nextLoopCheck);

            // Match found: jump based on type
            Label checkNext = ctx.javaClassInfo.newLabel("checkNext", loopLabels.labelName);
            Label checkRedo = ctx.javaClassInfo.newLabel("checkRedo", loopLabels.labelName);

            // if (type == LAST (0)) goto lastLabel
            mv.visitVarInsn(Opcodes.ILOAD, ctx.javaClassInfo.controlFlowActionSlot);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkNext);
            if (loopLabels.lastLabel == ctx.javaClassInfo.returnLabel) {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                ctx.javaClassInfo.stackLevelManager.emitPopInstructions(mv, loopLabels.asmStackLevel);
                if (loopLabels.context != RuntimeContextType.VOID) {
                    EmitOperator.emitUndef(mv);
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.lastLabel);
            }

            // if (type == NEXT (1)) goto nextLabel
            mv.visitLabel(checkNext);
            mv.visitVarInsn(Opcodes.ILOAD, ctx.javaClassInfo.controlFlowActionSlot);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkRedo);
            if (loopLabels.nextLabel == ctx.javaClassInfo.returnLabel) {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                ctx.javaClassInfo.stackLevelManager.emitPopInstructions(mv, loopLabels.asmStackLevel);
                if (loopLabels.context != RuntimeContextType.VOID) {
                    EmitOperator.emitUndef(mv);
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.nextLabel);
            }

            // if (type == REDO (2)) goto redoLabel
            mv.visitLabel(checkRedo);
            if (loopLabels.redoLabel == ctx.javaClassInfo.returnLabel) {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                ctx.javaClassInfo.emitClearSpillSlots(mv);
                ctx.javaClassInfo.stackLevelManager.emitPopInstructions(mv, loopLabels.asmStackLevel);
                
                // Ensure local variable consistency before jump
                if (ctx.javaClassInfo.localVariableTracker != null) {
                    ctx.javaClassInfo.ensureLocalVariableConsistencyBeforeJump(mv);
                }
                
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.redoLabel);
            }

            mv.visitLabel(nextLoopCheck);
        }

        // No loop match; propagate
        ctx.javaClassInfo.emitClearSpillSlots(mv);
        mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);

        // Propagate: jump to returnLabel with the marked list
        mv.visitLabel(propagateToCaller);
        mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);
        mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);

        // Not a control flow marker - load it back and continue
        mv.visitLabel(notControlFlow);
        // Restore spilled intermediates (original stack order) and then the result.
        for (int i = spillCount - 1; i >= 0; i--) {
            ctx.javaClassInfo.loadSpillRef(mv, spillRefs[i]);
        }
        mv.visitVarInsn(Opcodes.ALOAD, ctx.javaClassInfo.controlFlowTempSlot);

        // Restore tracked stack level.
        ctx.javaClassInfo.incrementStackLevel(spillCount + 1);

        // Release spill refs after the helper completes.
        // This is a codegen-time resource release (not emitted bytecode) and must happen
        // regardless of which runtime branch was taken.
        if (spillRefs != null) {
            for (JavaClassInfo.SpillRef ref : spillRefs) {
                if (ref != null) {
                    ctx.javaClassInfo.releaseSpillRef(ref);
                }
            }
        }
    }

    /**
     * Handles the 'return' operator for subroutine exits.
     * Processes both single and multiple return values, ensuring proper stack management.
     * Also detects and handles 'goto &NAME' tail calls.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param node           The operator node representing the return statement
     */
    static void handleReturnOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        ctx.logDebug("visit(return) in context " + emitterVisitor.ctx.contextType);
        ctx.logDebug("visit(return) will visit " + node.operand + " in context " + emitterVisitor.ctx.with(RuntimeContextType.RUNTIME).contextType);

        // Check if this is a 'goto &NAME' or 'goto EXPR' tail call (parsed as 'return (coderef(@_))')
        // This handles: goto &foo, goto __SUB__, goto $coderef, etc.
        if (node.operand instanceof ListNode list && list.elements.size() == 1) {
            Node firstElement = list.elements.getFirst();
            if (firstElement instanceof BinaryOperatorNode callNode && callNode.operator.equals("(")) {
                // This is a function call - check if it's a coderef form
                Node callTarget = callNode.left;
                
                // Handle &sub syntax
                if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("&")) {
                    ctx.logDebug("visit(return): Detected goto &NAME tail call");
                    handleGotoSubroutine(emitterVisitor, opNode, callNode.right);
                    return;
                }
                
                // Handle __SUB__ and other code reference expressions
                // In Perl, goto EXPR where EXPR evaluates to a coderef is a tail call
                if (callTarget instanceof OperatorNode opNode && opNode.operator.equals("__SUB__")) {
                    ctx.logDebug("visit(return): Detected goto __SUB__ tail call");
                    handleGotoSubroutine(emitterVisitor, opNode, callNode.right);
                    return;
                }
            }
        }

        // Clean up tracked stack before return
        ctx.javaClassInfo.resetStackLevel();

        boolean hasOperand = !(node.operand == null || (node.operand instanceof ListNode list && list.elements.isEmpty()));

        if (!hasOperand) {
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "<init>",
                    "()V",
                    false);
        } else if (node.operand instanceof ListNode list && list.elements.size() == 1) {
            list.elements.getFirst().accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        } else {
            node.operand.accept(emitterVisitor.with(RuntimeContextType.RUNTIME));
        }

        ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
    }
    
    /**
     * Handles 'goto &NAME' tail call optimization.
     * Creates a TAILCALL marker with the coderef and arguments.
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param subNode        The operator node for the subroutine reference (&NAME)
     * @param argsNode       The node representing the arguments
     */
    static void handleGotoSubroutine(EmitterVisitor emitterVisitor, OperatorNode subNode, Node argsNode) {
        EmitterContext ctx = emitterVisitor.ctx;
        
        ctx.logDebug("visit(goto &sub): Emitting TAILCALL marker");
        
        // Clean up tracked stack before creating the marker
        ctx.javaClassInfo.resetStackLevel();
        
        subNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        int codeRefSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledCodeRef = codeRefSlot >= 0;
        if (!pooledCodeRef) {
            codeRefSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, codeRefSlot);

        argsNode.accept(emitterVisitor.with(RuntimeContextType.LIST));
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeBase",
                "getList",
                "()Lorg/perlonjava/runtime/RuntimeList;",
                false);
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/RuntimeList",
                "getArrayOfAlias",
                "()Lorg/perlonjava/runtime/RuntimeArray;",
                false);
        int argsSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledArgs = argsSlot >= 0;
        if (!pooledArgs) {
            argsSlot = ctx.symbolTable.allocateLocalVariable();
        }
        ctx.mv.visitVarInsn(Opcodes.ASTORE, argsSlot);

        ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeControlFlowList");
        ctx.mv.visitInsn(Opcodes.DUP);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
        ctx.mv.visitVarInsn(Opcodes.ALOAD, argsSlot);
        ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
        int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(subNode.tokenIndex) : 0;
        ctx.mv.visitLdcInsn(lineNumber);
        ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/RuntimeControlFlowList",
                "<init>",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeArray;Ljava/lang/String;I)V",
                false);

        if (pooledArgs) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledCodeRef) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
        
        // Jump to returnLabel (trampoline will handle it)
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

        // Parse the goto argument
        String labelName = null;
        boolean isDynamic = false;
        
        if (node.operand instanceof ListNode labelNode && !labelNode.elements.isEmpty()) {
            Node arg = labelNode.elements.getFirst();
            
            // Check if it's a static label (IdentifierNode)
            if (arg instanceof IdentifierNode) {
                labelName = ((IdentifierNode) arg).name;
            } else {
                // Check if this is a tail call (goto EXPR where EXPR is a coderef)
                // This handles: goto __SUB__, goto $coderef, etc.
                if (arg instanceof OperatorNode opNode && opNode.operator.equals("__SUB__")) {
                    ctx.logDebug("visit(goto): Detected goto __SUB__ tail call");
                    // Create a ListNode with @_ as the argument
                    ListNode argsNode = new ListNode(opNode.tokenIndex);
                    OperatorNode atUnderscore = new OperatorNode("@", 
                            new IdentifierNode("_", opNode.tokenIndex), opNode.tokenIndex);
                    argsNode.elements.add(atUnderscore);
                    handleGotoSubroutine(emitterVisitor, opNode, argsNode);
                    return;
                }
                
                // Dynamic label (goto EXPR) - expression evaluated at runtime
                isDynamic = true;
                ctx.logDebug("visit(goto): Dynamic goto with expression");
                
                // Evaluate the expression to get the label name at runtime
                arg.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                
                // Convert to string (label name)
                ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/RuntimeScalar",
                        "toString",
                        "()Ljava/lang/String;",
                        false);
                
                // For dynamic goto, we always create a RuntimeControlFlowList
                // because we can't know at compile-time if the label is local or not
                ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeControlFlowList");
                ctx.mv.visitInsn(Opcodes.DUP_X1); // Stack: label, RuntimeControlFlowList, RuntimeControlFlowList
                ctx.mv.visitInsn(Opcodes.SWAP);    // Stack: label, RuntimeControlFlowList, label, RuntimeControlFlowList
                
                ctx.mv.visitFieldInsn(Opcodes.GETSTATIC,
                        "org/perlonjava/runtime/ControlFlowType",
                        "GOTO",
                        "Lorg/perlonjava/runtime/ControlFlowType;");
                ctx.mv.visitInsn(Opcodes.SWAP);    // Stack: ..., ControlFlowType, label
                
                // Push fileName
                ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
                // Push lineNumber
                int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.tokenIndex) : 0;
                ctx.mv.visitLdcInsn(lineNumber);
                
                ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "org/perlonjava/runtime/RuntimeControlFlowList",
                        "<init>",
                        "(Lorg/perlonjava/runtime/ControlFlowType;Ljava/lang/String;Ljava/lang/String;I)V",
                        false);

                int markerSlot = ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledMarker = markerSlot >= 0;
                if (!pooledMarker) {
                    markerSlot = ctx.symbolTable.allocateLocalVariable();
                }
                ctx.mv.visitVarInsn(Opcodes.ASTORE, markerSlot);

                // Clean stack and jump to returnLabel with the marker on stack.
                ctx.javaClassInfo.resetStackLevel();
                ctx.mv.visitVarInsn(Opcodes.ALOAD, markerSlot);
                if (pooledMarker) {
                    ctx.javaClassInfo.releaseSpillSlot();
                }
                ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
                return;
            }
        }

        // Ensure label is provided for static goto
        if (labelName == null && !isDynamic) {
            throw new PerlCompilerException(node.tokenIndex, "goto must be given label", ctx.errorUtil);
        }

        // For static label, check if it's local
        GotoLabels targetLabel = ctx.javaClassInfo.findGotoLabelsByName(labelName);
        if (targetLabel == null) {
            // Non-local goto: create RuntimeControlFlowList and return
            ctx.logDebug("visit(goto): Non-local goto to " + labelName);
            
            // Create new RuntimeControlFlowList with GOTO type, label, fileName, lineNumber
            ctx.mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/RuntimeControlFlowList");
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitFieldInsn(Opcodes.GETSTATIC, 
                    "org/perlonjava/runtime/ControlFlowType", 
                    "GOTO", 
                    "Lorg/perlonjava/runtime/ControlFlowType;");
            ctx.mv.visitLdcInsn(labelName);
            // Push fileName
            ctx.mv.visitLdcInsn(ctx.compilerOptions.fileName != null ? ctx.compilerOptions.fileName : "(eval)");
            // Push lineNumber
            int lineNumber = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.tokenIndex) : 0;
            ctx.mv.visitLdcInsn(lineNumber);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/RuntimeControlFlowList",
                    "<init>",
                    "(Lorg/perlonjava/runtime/ControlFlowType;Ljava/lang/String;Ljava/lang/String;I)V",
                    false);

            int markerSlot = ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledMarker = markerSlot >= 0;
            if (!pooledMarker) {
                markerSlot = ctx.symbolTable.allocateLocalVariable();
            }
            ctx.mv.visitVarInsn(Opcodes.ASTORE, markerSlot);

            // Clean stack and jump to returnLabel with the marker on stack.
            ctx.javaClassInfo.resetStackLevel();
            ctx.mv.visitVarInsn(Opcodes.ALOAD, markerSlot);
            if (pooledMarker) {
                ctx.javaClassInfo.releaseSpillSlot();
            }
            ctx.mv.visitJumpInsn(Opcodes.GOTO, ctx.javaClassInfo.returnLabel);
            return;
        }

        // Local goto: use fast GOTO (existing code)
        // Clean up stack before jumping to maintain stack consistency
        ctx.javaClassInfo.emitClearSpillSlots(ctx.mv);
        ctx.javaClassInfo.stackLevelManager.emitPopInstructions(ctx.mv, targetLabel.asmStackLevel);

        // Emit the goto instruction
        ctx.mv.visitJumpInsn(Opcodes.GOTO, targetLabel.gotoLabel);
    }
}
