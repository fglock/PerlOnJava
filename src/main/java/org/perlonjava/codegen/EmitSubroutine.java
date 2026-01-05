package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeCode;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.symbols.ScopedSymbolTable;
import org.perlonjava.symbols.SymbolTable;

import java.util.Arrays;
import java.util.Map;

import static org.perlonjava.perlmodule.Strict.HINT_STRICT_REFS;

/**
 * The EmitSubroutine class is responsible for handling subroutine-related operations
 * and generating the corresponding bytecode using ASM.
 */
public class EmitSubroutine {
    // Feature flags for control flow implementation
    // 
    // WHAT THIS WOULD DO IF ENABLED:
    // After every subroutine call, check if the returned RuntimeList is a RuntimeControlFlowList
    // (marked with last/next/redo/goto), and if so, immediately propagate it to returnLabel
    // instead of continuing execution. This would enable loop handlers to catch control flow
    // at the loop level instead of propagating all the way up the call stack.
    //
    // WHY IT'S DISABLED:
    // The inline check pattern causes ArrayIndexOutOfBoundsException in ASM's frame computation:
    //   DUP                                    // Duplicate result
    //   INSTANCEOF RuntimeControlFlowList      // Check if marked
    //   IFEQ notMarked                        // Branch
    //   ASTORE tempSlot                       // Store (slot allocated dynamically)
    //   emitPopInstructions(0)                // Clean stack
    //   ALOAD tempSlot                        // Restore
    //   GOTO returnLabel                      // Propagate
    //   notMarked: POP                        // Discard duplicate
    //
    // The complex branching with dynamic slot allocation breaks ASM's ability to merge frames
    // at the branch target, especially when the tempSlot is allocated after the branch instruction.
    //
    // INVESTIGATION NEEDED:
    // 1. Try allocating tempSlot statically at method entry (not dynamically per call)
    // 2. Try simpler pattern without DUP (accept performance hit of extra ALOAD/ASTORE)
    // 3. Try manual frame hints with visitFrame() at merge points
    // 4. Consider moving check to VOID context only (after POP) - but this loses marked returns
    // 5. Profile real-world code to see if this optimization actually matters
    //
    // CURRENT WORKAROUND:
    // Without call-site checks, marked returns propagate through normal return paths.
    // This works correctly but is less efficient for deeply nested loops crossing subroutines.
    // Performance impact is minimal since most control flow is local (uses plain JVM GOTO).
    private static final boolean ENABLE_CONTROL_FLOW_CHECKS = true;
    
    // Set to true to enable debug output for control flow checks
    private static final boolean DEBUG_CONTROL_FLOW = false;

    /**
     * Emits bytecode for a subroutine, including handling of closure variables.
     *
     * @param ctx  The context used for code emission.
     * @param node The subroutine node representing the subroutine.
     */
    public static void emitSubroutine(EmitterContext ctx, SubroutineNode node) {
        ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // Mark the block as subroutine block,
        // this prevents the "code too large" transform in emitBlock()
        node.block.setAnnotation("blockIsSubroutine", true);

        // Retrieve closure variable list
        // Alternately, scan the AST for variables and capture only the ones that are used
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = ctx.symbolTable.getAllVisibleVariables();
        
        // IMPORTANT: Package-level subs (named subs) should NOT capture closure variables from their 
        // definition context. Only anonymous subs (my sub, state sub, or true anonymous subs) should
        // capture variables. This prevents issues like defining 'sub bar::foo' inside a block with
        // 'our sub foo' from incorrectly capturing the 'our sub' as a closure variable.
        boolean isPackageSub = node.name != null && !node.name.equals("<anon>");
        if (isPackageSub) {
            // Package subs should not capture any closure variables
            // They can only access global variables and their parameters
            visibleVariables.clear();
        } else {
            // For anonymous/lexical subs, filter out 'our sub' declarations only
            visibleVariables.entrySet().removeIf(entry -> {
                SymbolTable.SymbolEntry symbolEntry = entry.getValue();
                if (symbolEntry.name().startsWith("&") && symbolEntry.ast() instanceof OperatorNode operatorNode) {
                    Boolean isOurSub = (Boolean) operatorNode.getAnnotation("isOurSub");
                    return isOurSub != null && isOurSub;
                }
                return false;
            });
        }
        
        ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        // Create a new symbol table for the subroutine, but manually add only the filtered variables
        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();
        
        // Add only the filtered visible variables (excluding 'our sub' entries)
        for (SymbolTable.SymbolEntry entry : visibleVariables.values()) {
            newSymbolTable.addVariable(entry.name(), entry.decl(), entry.ast());
        }
        
        // Copy package, subroutine, and flags from the current context
        newSymbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage(), ctx.symbolTable.currentPackageIsClass());
        newSymbolTable.setCurrentSubroutine(ctx.symbolTable.getCurrentSubroutine());
        newSymbolTable.warningFlagsStack.pop();
        newSymbolTable.warningFlagsStack.push(ctx.symbolTable.warningFlagsStack.peek());
        newSymbolTable.featureFlagsStack.pop();
        newSymbolTable.featureFlagsStack.push(ctx.symbolTable.featureFlagsStack.peek());
        newSymbolTable.strictOptionsStack.pop();
        newSymbolTable.strictOptionsStack.push(ctx.symbolTable.strictOptionsStack.peek());

        String[] newEnv = newSymbolTable.getVariableNames();
        ctx.logDebug("AnonSub " + newSymbolTable);

        // Reset the index counter to start after the closure variables
        // This prevents allocateLocalVariable() from creating slots that overlap with uninitialized slots
        // We need to use the MAXIMUM of newEnv.length and the current index to avoid conflicts
        int currentVarIndex = newSymbolTable.getCurrentLocalVariableIndex();
        int resetTo = Math.max(newEnv.length, currentVarIndex);
        newSymbolTable.resetLocalVariableIndex(resetTo);

        // Create the new method context
        EmitterContext subCtx =
                new EmitterContext(
                        new JavaClassInfo(), // Internal Java class name
                        newSymbolTable, // Closure symbolTable
                        null, // Method visitor
                        null, // Class writer
                        RuntimeContextType.RUNTIME, // Call context
                        true, // Is boxed
                        ctx.errorUtil, // Error message utility
                        ctx.compilerOptions,
                        null);
        Class<?> generatedClass =
                EmitterMethodCreator.createClassWithMethod(
                        subCtx, node.block, node.useTryCatch
                );
        String newClassNameDot = subCtx.javaClassInfo.javaClassName.replace('/', '.');
        ctx.logDebug("Generated class name: " + newClassNameDot + " internal " + subCtx.javaClassInfo.javaClassName);
        ctx.logDebug("Generated class env:  " + Arrays.toString(newEnv));
        RuntimeCode.anonSubs.put(subCtx.javaClassInfo.javaClassName, generatedClass); // Cache the class

        int skipVariables = EmitterMethodCreator.skipVariables; // Skip (this, @_, wantarray)

        // Direct instantiation approach - no reflection needed!

        // 1. NEW - Create new instance
        mv.visitTypeInsn(Opcodes.NEW, subCtx.javaClassInfo.javaClassName);
        mv.visitInsn(Opcodes.DUP);

        // 2. Load all captured variables for the constructor
        int newIndex = 0;
        for (Integer currentIndex : visibleVariables.keySet()) {
            if (newIndex >= skipVariables) {
                mv.visitVarInsn(Opcodes.ALOAD, currentIndex); // Load the captured variable
            }
            newIndex++;
        }

        // 3. Build the constructor descriptor
        StringBuilder constructorDescriptor = new StringBuilder("(");
        for (int i = skipVariables; i < newEnv.length; i++) {
            String descriptor = EmitterMethodCreator.getVariableDescriptor(newEnv[i]);
            constructorDescriptor.append(descriptor);
        }
        constructorDescriptor.append(")V");

        // 4. INVOKESPECIAL - Call the constructor
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                subCtx.javaClassInfo.javaClassName,
                "<init>",
                constructorDescriptor.toString(),
                false);

        // 5. Create a CODE variable using RuntimeCode.makeCodeObject
        if (node.prototype != null) {
            mv.visitLdcInsn(node.prototype);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "makeCodeObject",
                    "(Ljava/lang/Object;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else {
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "makeCodeObject",
                    "(Ljava/lang/Object;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        }

        // 6. Clean up the stack if context is VOID
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP); // Remove the RuntimeScalar object from the stack
        }

        // If the context is not VOID, the stack should contain [RuntimeScalar] (the CODE variable)
        // If the context is VOID, the stack should be empty
        ctx.logDebug("SUB end");
    }

    /**
     * Handles the postfix `()` node, which runs a subroutine.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the apply operation.
     */
    static void handleApplyOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        emitterVisitor.ctx.logDebug("handleApplyElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        String subroutineName = "";
        if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
            if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                subroutineName = NameNormalizer.normalizeVariableName(identifierNode.name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                emitterVisitor.ctx.logDebug("handleApplyElementOperator subroutine " + subroutineName);
            }
        }

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // Target - left parameter: Code ref
        
        // Dereference the scalar to get the CODE reference if needed
        // When we have &$x() the left side is OperatorNode("$") (the & is consumed by the parser)
        // We need to look up the CODE slot from the glob if the scalar contains a string.
        // Check if the left side is a scalar variable
        if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("$")) {
            // This is &$var() or $var->() syntax - check if we need to dereference
            
            // Check if the variable is a lexical subroutine (already a CODE reference)
            // Lexical subs have a "hiddenVarName" annotation and should not be dereferenced
            String hiddenVarName = (String) operatorNode.getAnnotation("hiddenVarName");
            boolean isLexicalSub = (hiddenVarName != null);
            
            // Only call codeDerefNonStrict when strict refs is disabled AND not a lexical sub
            // This allows symbolic references like: my $x = "main::test"; &$x()
            if (!isLexicalSub && !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                // Without strict refs and not a lexical sub: allow symbolic references
                // Call codeDerefNonStrict to look up CODE slot from glob if needed
                emitterVisitor.pushCurrentPackage();
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, 
                        "org/perlonjava/runtime/RuntimeScalar", 
                        "codeDerefNonStrict", 
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", 
                        false);
            }
        }
        
        mv.visitLdcInsn(subroutineName);

        // Generate native RuntimeBase[] array for parameters instead of RuntimeList
        ListNode paramList = ListNode.makeList(node.right);
        int argCount = paramList.elements.size();

        // Create array of RuntimeBase with size equal to number of arguments
        if (argCount <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + argCount);
        } else if (argCount <= 127) {
            mv.visitIntInsn(Opcodes.BIPUSH, argCount);
        } else {
            mv.visitIntInsn(Opcodes.SIPUSH, argCount);
        }
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "org/perlonjava/runtime/RuntimeBase");

        // Populate the array with arguments
        EmitterVisitor listVisitor = emitterVisitor.with(RuntimeContextType.LIST);
        for (int index = 0; index < argCount; index++) {
            mv.visitInsn(Opcodes.DUP); // Duplicate array reference
            if (index <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + index);
            } else if (index <= 127) {
                mv.visitIntInsn(Opcodes.BIPUSH, index);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, index);
            }

            // Generate code for argument in LIST context
            paramList.elements.get(index).accept(listVisitor);

            mv.visitInsn(Opcodes.AASTORE); // Store in array
        }

        emitterVisitor.pushCallContext();   // Push call context to stack
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "apply",
                "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;[Lorg/perlonjava/runtime/RuntimeBase;I)Lorg/perlonjava/runtime/RuntimeList;",
                false); // Generate an .apply() call
        
        // Check for control flow (last/next/redo/goto/tail calls)
        // NOTE: Call-site control flow is handled in VOID context below (after the call result is on stack).
        // Do not call emitControlFlowCheck here, as it can clear the registry and/or require returning.
        
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Check for control flow before converting to scalar
            // Only emit control flow check if we're actually inside a loop
            if (ENABLE_CONTROL_FLOW_CHECKS && !emitterVisitor.ctx.javaClassInfo.loopLabelStack.isEmpty()) {
                LoopLabels innermostLoop = null;
                for (LoopLabels loopLabels : emitterVisitor.ctx.javaClassInfo.loopLabelStack) {
                    // Check any true loop, not just SCALAR context loops
                    if (loopLabels.isTrueLoop) {
                        innermostLoop = loopLabels;
                        break;
                    }
                }
                if (innermostLoop != null) {
                    Label noAction = new Label();
                    Label noMarker = new Label();
                    Label checkNext = new Label();
                    Label checkRedo = new Label();

                    // action = checkLoopAndGetAction(loopLabel)
                    if (innermostLoop.labelName != null) {
                        mv.visitLdcInsn(innermostLoop.labelName);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "checkLoopAndGetAction",
                            "(Ljava/lang/String;)I",
                            false);
                    mv.visitVarInsn(Opcodes.ISTORE, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);

                    // if (action == 0) goto noAction
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitJumpInsn(Opcodes.IFEQ, noAction);

                    // action != 0: pop call result, clean stack, jump to next/last/redo
                    mv.visitInsn(Opcodes.POP);

                    // if (action == 1) last
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkNext);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    // if (action == 2) next
                    mv.visitLabel(checkNext);
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkRedo);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.nextLabel);

                    // if (action == 3) redo
                    mv.visitLabel(checkRedo);
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_3);
                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, innermostLoop.redoLabel);

                    // Unknown action: unwind this loop (do NOT fall through to noMarker)
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    // action == 0: if marker still present, unwind this loop (label targets outer)
                    mv.visitLabel(noAction);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "hasMarker",
                            "()Z",
                            false);
                    mv.visitJumpInsn(Opcodes.IFEQ, noMarker);

                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    mv.visitLabel(noMarker);
                }
            }
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            if (ENABLE_CONTROL_FLOW_CHECKS) {
                LoopLabels innermostLoop = null;
                for (LoopLabels loopLabels : emitterVisitor.ctx.javaClassInfo.loopLabelStack) {
                    if (loopLabels.isTrueLoop && loopLabels.context == RuntimeContextType.VOID) {
                        innermostLoop = loopLabels;
                        break;
                    }
                }
                if (innermostLoop != null) {
                    Label noAction = new Label();
                    Label noMarker = new Label();
                    Label checkNext = new Label();
                    Label checkRedo = new Label();

                    // action = checkLoopAndGetAction(loopLabel)
                    if (innermostLoop.labelName != null) {
                        mv.visitLdcInsn(innermostLoop.labelName);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "checkLoopAndGetAction",
                            "(Ljava/lang/String;)I",
                            false);
                    mv.visitVarInsn(Opcodes.ISTORE, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);

                    // if (action == 0) goto noAction
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitJumpInsn(Opcodes.IFEQ, noAction);

                    // action != 0: pop call result, clean stack, jump to next/last/redo
                    mv.visitInsn(Opcodes.POP);

                    // if (action == 1) last
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkNext);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    // if (action == 2) next
                    mv.visitLabel(checkNext);
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkRedo);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.nextLabel);

                    // if (action == 3) redo
                    mv.visitLabel(checkRedo);
                    mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
                    mv.visitInsn(Opcodes.ICONST_3);
                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, innermostLoop.redoLabel);

                    // Unknown action: unwind this loop (do NOT fall through to noMarker)
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    // action == 0: if marker still present, unwind this loop (label targets outer)
                    mv.visitLabel(noAction);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                            "hasMarker",
                            "()Z",
                            false);
                    mv.visitJumpInsn(Opcodes.IFEQ, noMarker);

                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, innermostLoop.lastLabel);

                    mv.visitLabel(noMarker);
                }
            }

            mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Handles the `__SUB__` operator, which refers to the current subroutine.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The operator node representing the `__SUB__` operation.
     */
    static void handleSelfCallOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        emitterVisitor.ctx.logDebug("handleSelfCallOperator " + node + " in context " + emitterVisitor.ctx.contextType);

        MethodVisitor mv = emitterVisitor.ctx.mv;

        String className = emitterVisitor.ctx.javaClassInfo.javaClassName;

        // Load 'this' (the current RuntimeCode instance)
        mv.visitVarInsn(Opcodes.ALOAD, 0); // Assuming 'this' is at index 0

        // Retrieve this.__SUB__
        mv.visitFieldInsn(Opcodes.GETFIELD,
                className,    // The class containing the field (e.g., "com/example/MyClass")
                "__SUB__",     // Field name
                "Lorg/perlonjava/runtime/RuntimeScalar;");    // Field descriptor

        // Create a Perl undef if the value in the stack is null
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/RuntimeCode",
                "selfReferenceMaybeNull",
                "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;",
                false);

        // Now we have a RuntimeScalar representing the current subroutine (__SUB__)
        EmitOperator.handleVoidContext(emitterVisitor);
    }
    
    /**
     * Emits bytecode to check if a RuntimeList returned from a subroutine call
     * is marked with control flow information (last/next/redo/goto/tail call).
     * If marked, cleans the stack and jumps to returnLabel.
     * 
     * Pattern:
     *   DUP                          // Duplicate result for test
     *   INVOKEVIRTUAL isNonLocalGoto // Check if marked
     *   IFNE handleControlFlow       // Jump if marked
     *   POP                          // Discard duplicate
     *   // Continue with result on stack
     *   
     *   handleControlFlow:
     *     ASTORE temp                // Save marked result
     *     emitPopInstructions(0)     // Clean stack
     *     ALOAD temp                 // Load marked result
     *     GOTO returnLabel           // Jump to return point
     * 
     * @param ctx The emitter context
     */
    private static void emitControlFlowCheck(EmitterContext ctx) {
        // After a subroutine call, check if non-local control flow was registered
        // This enables next/last/redo LABEL to work from inside closures
        //
        // APPROACH: Use a helper method to simplify the control flow
        // Instead of complex branching with TABLESWITCH or multiple IFs,
        // call a single helper method that does all the checking and returns
        // either the original result or a marked RuntimeControlFlowList
        
        // Stack: [RuntimeList result]
        
        // Check the registry for any pending control flow
        // Get the innermost loop labels (if we're inside a loop)
        LoopLabels innermostLoop = ctx.javaClassInfo.getInnermostLoopLabels();
        
        if (innermostLoop != null) {
            // We're inside a loop - check if non-local control flow was registered
            // Call helper: RuntimeControlFlowRegistry.checkAndWrapIfNeeded(result, labelName)
            // Returns: either the original result or a marked RuntimeControlFlowList
            
            // Stack: [RuntimeList result]
            
            // Push the label name (or null if no label)
            if (innermostLoop.labelName != null) {
                ctx.mv.visitLdcInsn(innermostLoop.labelName);
            } else {
                ctx.mv.visitInsn(Opcodes.ACONST_NULL);
            }
            
            // Call: RuntimeList result = RuntimeControlFlowRegistry.checkAndWrapIfNeeded(result, labelName)
            // This method checks the registry and returns either:
            // - The original result if no action (action == 0)
            // - A marked RuntimeControlFlowList if action detected
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeControlFlowRegistry",
                    "checkAndWrapIfNeeded",
                    "(Lorg/perlonjava/runtime/RuntimeList;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeList;",
                    false);
            // Stack: [RuntimeList result_or_marked]
            
            // No branching needed! The helper method handles everything.
            // The result is either the original or a marked list.
            // The loop level will check if it's marked and handle it.
        }
        // If not inside a loop, don't check registry (result stays on stack)
    }
}
