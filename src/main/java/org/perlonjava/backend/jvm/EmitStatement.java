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
     * Emits bytecode to clean up and null out JVM local variable slots for
     * {@code my} variables going out of scope.
     * <p>
     * Must be called BEFORE {@code exitScope()} so the symbol table still has
     * the variable entries.
     * <p>
     * <b>Two-phase cleanup for anonymous filehandle globs:</b>
     * <ol>
     *   <li><b>Eager fd-number recycling (this method):</b> Before nulling each
     *       scalar slot, calls {@link RuntimeScalar#scopeExitCleanup} which
     *       unregisters the fileno from the fd table, returning it to the
     *       recycle pool for immediate reuse. This does NOT close the IO stream —
     *       only the fd number is freed. This is safe for shared handles because
     *       the IO stream stays open and functional.</li>
     *   <li><b>Deferred IO close via GC:</b> After nulling the slot, the
     *       RuntimeGlob becomes unreachable from this JVM frame. When all
     *       references are gone, the JVM GC enqueues the
     *       {@link java.lang.ref.PhantomReference} registered by
     *       {@link RuntimeIO#registerGlobForFdRecycling}, and
     *       {@link RuntimeIO#processAbandonedGlobs()} closes the actual IO.</li>
     * </ol>
     * <p>
     * <b>Design history — why we unregister the fd but do NOT close IO:</b>
     * <p>
     * An earlier version eagerly closed IO on anonymous globs at scope exit.
     * This was broken because PerlOnJava has no reference counting: there is
     * no way to know at scope exit whether other variables (array elements,
     * hash values, object fields, closures) still reference the same
     * RuntimeGlob. The eager close destroyed shared handles still in use:
     * <ul>
     *   <li>Test2::Formatter::TAP: {@code my $io = $handles->[$hid]} in a
     *       for loop — when $io went out of scope, the shared handle was
     *       closed, breaking all subsequent test output.</li>
     *   <li>Capture::Tiny: dup'd STDOUT/STDERR handles stored in a hash
     *       were closed when the gensym'd lexical was reassigned in a loop.</li>
     * </ul>
     * The current approach (unregister fd only) avoids these bugs: shared
     * handles keep their IO stream open. If the other reference later calls
     * {@code fileno()}, {@link RuntimeIO#assignFileno()} assigns a fresh fd.
     *
     * @param ctx        The emitter context with the MethodVisitor and symbol table
     * @param scopeIndex The scope boundary being exited
     */
    static void emitScopeExitNullStores(EmitterContext ctx, int scopeIndex) {
        emitScopeExitNullStores(ctx, scopeIndex, false);
    }

    /**
     * Same as {@link #emitScopeExitNullStores(EmitterContext, int)} but with
     * an option to flush the MortalList after cleanup.
     * <p>
     * When {@code flush} is true, emits a scoped flush using
     * {@code MortalList.pushMark()} before cleanup and
     * {@code MortalList.popAndFlush()} after. This only processes entries
     * added by the scope-exit cleanup itself (not entries from outer scopes
     * or prior operations), matching Perl 5's SAVETMPS/FREETMPS scoping.
     * <p>
     * {@code flush=true} is safe for bare blocks, loops, and control structures.
     * It must be {@code false} for subroutine body blocks where the implicit
     * return value may still be on the JVM operand stack — flushing would
     * destroy the return value before the caller captures it.
     *
     * @param ctx        The emitter context with the MethodVisitor and symbol table
     * @param scopeIndex The scope boundary being exited
     * @param flush      If true, emit scoped MortalList flush around null stores
     */
    static void emitScopeExitNullStores(EmitterContext ctx, int scopeIndex, boolean flush) {
        // Gather variable indices for this scope first, to determine if cleanup is needed.
        java.util.List<Integer> scalarIndices = ctx.symbolTable.getMyScalarIndicesInScope(scopeIndex);
        java.util.List<Integer> hashIndices = ctx.symbolTable.getMyHashIndicesInScope(scopeIndex);
        java.util.List<Integer> arrayIndices = ctx.symbolTable.getMyArrayIndicesInScope(scopeIndex);

        // Record my-variable indices for eval exception cleanup.
        // When evalCleanupLocals is non-null (set by EmitterMethodCreator for eval blocks),
        // we record all my-variable local indices so the catch handler can emit cleanup
        // for variables whose normal SCOPE_EXIT_CLEANUP was skipped by die.
        if (ctx.javaClassInfo.evalCleanupLocals != null) {
            ctx.javaClassInfo.evalCleanupLocals.addAll(scalarIndices);
            ctx.javaClassInfo.evalCleanupLocals.addAll(hashIndices);
            ctx.javaClassInfo.evalCleanupLocals.addAll(arrayIndices);
        }

        // Fast path: when CleanupNeededVisitor proved the sub has no
        // bless / weaken / local / nested-sub / defer / user-sub-call
        // activity, the MyVarCleanupStack.unregister emission (Phase E)
        // is dead code — MyVarCleanupStack is only populated when
        // WeakRefRegistry.weakRefsExist is true, which only ever
        // becomes true after a weaken() is called somewhere. If this
        // sub couldn't have weakened anything (the visitor proved it),
        // skip the per-variable unregister loop.
        //
        // We deliberately DO NOT skip Phase 1 (scopeExitCleanup on
        // scalars) or Phase 1b (scopeExitCleanupHash/Array): those fire
        // DESTROY for blessed refs that entered this sub via @_ params
        // or via return values. Skipping them breaks DBIC txn_scope_guard,
        // tie_scalar DESTROY-on-untie, and other legitimate patterns
        // where the sub receives a blessed ref it doesn't know about
        // statically.
        //
        // JPERL_FORCE_CLEANUP=1 forces cleanupNeeded=true at the
        // EmitterMethodCreator level for correctness debugging.
        //
        // Phase R (classic_experiment_finding.md): we EXTEND the existing
        // skipMyVarCleanup gate to also suppress MyVarCleanupStack.register
        // emission on `my` declarations in EmitVariable. We deliberately
        // leave Phase 1/1b (scopeExitCleanup, cleanupHash/Array) and Phase 3
        // (MortalList.flush) emitting unconditionally, per the safety note
        // above — those fire DESTROY for refs that entered via @_ even if
        // the sub's AST has no bless/weaken/user-sub-call and was marked
        // cleanupNeeded=false.
        boolean skipMyVarCleanup = !ctx.javaClassInfo.cleanupNeeded;

        // Only emit flush when there are variables that need cleanup.
        // Scopes with no my-variables (e.g., while/for loop bodies with no declarations)
        // skip the Phase 1/1b cleanup but still flush: pending entries from inner sub
        // scope exits (e.g., Foo->new()->method() chain temporaries) may need processing.
        boolean needsCleanup = !scalarIndices.isEmpty() || !hashIndices.isEmpty() || !arrayIndices.isEmpty();

        // Phase 1: Run scopeExitCleanup for scalar variables.
        // This defers refCount decrements for blessed references with DESTROY,
        // and handles IO fd recycling for anonymous filehandle globs.
        for (int idx : scalarIndices) {
            ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "scopeExitCleanup",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V",
                    false);
        }
        // Phase 1b: Walk hash/array variables for nested blessed references.
        // When a hash/array goes out of scope, any blessed refs stored inside
        // (or nested inside sub-containers) need their refCounts decremented.
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
        // Phase 2: Null all my variable slots to help GC collect associated objects.
        // For anonymous filehandle globs, this makes them unreachable so the
        // PhantomReference-based fd recycling in RuntimeIO can close the IO stream.
        java.util.List<Integer> allIndices = ctx.symbolTable.getMyVariableIndicesInScope(scopeIndex);
        // Phase E (refcount_alignment_52leaks_plan.md): deregister each
        // my-variable from MyVarCleanupStack before nulling the local slot.
        // Without this, the static stack holds strong references to
        // block-scoped scalars until the enclosing subroutine returns,
        // preventing JVM GC and keeping their RuntimeBase targets alive
        // past their Perl-level scope. The reachability walker would then
        // treat the scalar as a live lexical and mark its referent as
        // reachable, causing false-positive leaks (basic rerefrozen in
        // DBIC's t/52leaks.t).
        //
        // When skipMyVarCleanup is true (CleanupNeededVisitor proved this
        // sub never uses bless/weaken/user-sub-calls/etc.), the stack is
        // guaranteed empty for this sub's lexicals, so the unregister
        // loop is dead code. Skipping it is the win this fast path buys.
        if (!skipMyVarCleanup) {
            for (int idx : allIndices) {
                ctx.mv.visitVarInsn(Opcodes.ALOAD, idx);
                ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/MyVarCleanupStack",
                        "unregister",
                        "(Ljava/lang/Object;)V",
                        false);
            }
        }
        for (int idx : allIndices) {
            ctx.mv.visitInsn(Opcodes.ACONST_NULL);
            ctx.mv.visitVarInsn(Opcodes.ASTORE, idx);
        }
        // Phase 3: Full flush of ALL pending mortal decrements.
        // Unlike the previous pushMark/popAndFlush approach, this processes ALL
        // pending entries — including deferred decrements from subroutine scope
        // exits that occurred within this block. Those entries were previously
        // "orphaned" below the mark and never processed, causing:
        //   - Memory leaks (DESTROY never fires)
        //   - Premature DESTROY (deferred entries flushed at wrong time by
        //     setLargeRefCounted, which processes ALL pending entries)
        //
        // Full flush is safe here because by the time a scope exits:
        //   1. All return values from inner method calls have been captured
        //      (via setLargeRefCounted, which already flushes) or discarded.
        //   2. The pending entries are only deferred decrements that should
        //      have been processed earlier (Perl 5 FREETMPS at statement
        //      boundaries), not entries that need to be preserved.
        // Flush when requested (non-sub, non-do blocks) even without my-variables,
        // because pending entries may exist from inner sub scope exits.
        if (flush) {
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/MortalList",
                    "flush",
                    "()V",
                    false);
        }
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
                emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, true);
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
                    emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, true);
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
        emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, true);
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

            // Set up local variable cleanup for the loop/block scope.
            // This ensures that `local` variables are restored when exiting via `last`,
            // which jumps to endLabel and bypasses the body block's own localTeardown.
            // This mirrors EmitForeach.emitFor1() which has an outer localSetup/localTeardown
            // wrapping the loop body with teardown AFTER the loopEnd label.
            Local.localRecord for3LocalRecord = Local.localSetup(emitterVisitor.ctx, node, mv, true);

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

            // Restore local variables that were saved before the loop/block.
            // This catches `last` exits which bypass the body block's own localTeardown.
            Local.localTeardown(for3LocalRecord, mv);

            // Exit the scope in the symbol table
            if (node.useNewScope) {
                emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, true);
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
