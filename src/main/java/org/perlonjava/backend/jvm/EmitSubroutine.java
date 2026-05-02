package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.backend.bytecode.VariableCollectorVisitor;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.semantic.ScopedSymbolTable;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.runtimetypes.NameNormalizer;
import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_STRICT_REFS;

/**
 * The EmitSubroutine class is responsible for handling subroutine-related operations
 * and generating the corresponding bytecode using ASM.
 */
public class EmitSubroutine {
    // Feature flags for control flow implementation
    // 
    // IMPORTANT:
    // These flags are intentionally conservative and are part of perl5 test-suite stability.
    // In particular, many core tests rely on SKIP/TODO blocks implemented via test.pl:
    //   sub skip { ...; last SKIP; }
    // which requires non-local control flow (LAST/NEXT/REDO/GOTO) to propagate across
    // subroutine boundaries correctly.
    //
    // Historically, toggling these flags has caused large test regressions (e.g. op/pack.t collapsing)
    // and JVM verifier/ASM frame computation failures due to stack-map frame merge issues.
    // Do not change these settings unless you also re-run the perl5 test suite and verify
    // both semantics and bytecode verification.
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
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("SUB start");
        if (ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        MethodVisitor mv = ctx.mv;

        // Retrieve closure variable list (copy to avoid corrupting the cache)
        Map<Integer, SymbolTable.SymbolEntry> visibleVariables = new TreeMap<>(ctx.symbolTable.getAllVisibleVariables());

        // IMPORTANT: Package-level subs (named subs) should NOT capture closure variables from their 
        // definition context. Only anonymous subs (my sub, state sub, or true anonymous subs) should
        // capture variables. This prevents issues like defining 'sub bar::foo' inside a block with
        // 'our sub foo' from incorrectly capturing the 'our sub' as a closure variable.
        // Note: "(eval)" is a special name for eval blocks which should capture variables like anonymous subs
        boolean isPackageSub = node.name != null && !node.name.equals("<anon>") && !node.name.equals("(eval)");
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

        // Optimization: Only capture variables actually used in the subroutine body.
        // This prevents hitting JVM's 255 constructor argument limit for closures
        // in modules like Perl::Tidy that have 200+ lexicals in scope.
        if (!isPackageSub && node.block != null && !visibleVariables.isEmpty()) {
            Set<String> usedVars = new HashSet<>();
            VariableCollectorVisitor collector = new VariableCollectorVisitor(usedVars);
            node.block.accept(collector);
            if (!collector.hasEvalString()) {
                int skip = EmitterMethodCreator.skipVariables;
                int pos = 0;
                Iterator<Map.Entry<Integer, SymbolTable.SymbolEntry>> it =
                        visibleVariables.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, SymbolTable.SymbolEntry> entry = it.next();
                    if (pos >= skip && !usedVars.contains(entry.getValue().name())) {
                        it.remove();
                    }
                    pos++;
                }
            }
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("AnonSub ctx.symbolTable.getAllVisibleVariables");

        // Create a new symbol table for the subroutine, but manually add only the filtered variables
        ScopedSymbolTable newSymbolTable = new ScopedSymbolTable();
        newSymbolTable.enterScope();

        // Add only the filtered visible variables (excluding 'our sub' entries)
        // IMPORTANT: Use the 4-argument version to preserve the original perlPackage
        // This is critical for 'our' variables declared in BEGIN captures (PerlOnJava::_BEGIN_*)
        // which must retain their original package to work correctly with the 'local' fix
        for (SymbolTable.SymbolEntry entry : visibleVariables.values()) {
            newSymbolTable.addVariable(entry.name(), entry.decl(), entry.perlPackage(), entry.ast());
        }

        // Copy package, subroutine, and flags from the current context
        newSymbolTable.setCurrentPackage(ctx.symbolTable.getCurrentPackage(), ctx.symbolTable.currentPackageIsClass());
        // For eval blocks "(eval)", set the subroutine name so caller() reports it correctly
        if ("(eval)".equals(node.name)) {
            newSymbolTable.setCurrentSubroutine("(eval)");
        } else if (node.name == null || node.name.equals("<anon>")) {
            // True anonymous sub: caller() should report it as "Package::__ANON__",
            // NOT as the enclosing named sub. Matches Perl 5 behavior.
            newSymbolTable.setCurrentSubroutine(ctx.symbolTable.getCurrentPackage() + "::__ANON__");
        } else {
            newSymbolTable.setCurrentSubroutine(ctx.symbolTable.getCurrentSubroutine());
        }
        newSymbolTable.warningFlagsStack.pop();
        newSymbolTable.warningFlagsStack.push((java.util.BitSet) ctx.symbolTable.warningFlagsStack.peek().clone());
        newSymbolTable.warningFatalStack.pop();
        newSymbolTable.warningFatalStack.push((java.util.BitSet) ctx.symbolTable.warningFatalStack.peek().clone());
        newSymbolTable.warningDisabledStack.pop();
        newSymbolTable.warningDisabledStack.push((java.util.BitSet) ctx.symbolTable.warningDisabledStack.peek().clone());
        newSymbolTable.featureFlagsStack.pop();
        newSymbolTable.featureFlagsStack.push(ctx.symbolTable.featureFlagsStack.peek());
        newSymbolTable.strictOptionsStack.pop();
        newSymbolTable.strictOptionsStack.push(ctx.symbolTable.strictOptionsStack.peek());

        String[] newEnv = newSymbolTable.getVariableNames();
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("AnonSub " + newSymbolTable);

        // Reset the index counter to start after the closure variables
        // This prevents allocateLocalVariable() from creating slots that overlap with uninitialized slots
        // We need to use the MAXIMUM of newEnv.length and the current index to avoid conflicts
        int currentVarIndex = newSymbolTable.getCurrentLocalVariableIndex();
        int resetTo = Math.max(newEnv.length, currentVarIndex);
        newSymbolTable.resetLocalVariableIndex(resetTo);

        // Create the new method context
        JavaClassInfo newJavaClassInfo = new JavaClassInfo();
        
        // Check if this subroutine is a defer block - control flow restrictions apply
        Boolean isDeferBlock = (Boolean) node.getAnnotation("isDeferBlock");
        if (isDeferBlock != null && isDeferBlock) {
            newJavaClassInfo.isInDeferBlock = true;
        }

        // Check if this is an eval block - goto &sub is prohibited
        if (node.useTryCatch) {
            newJavaClassInfo.isInEvalBlock = true;
        }

        // Check if this subroutine is a map/grep block - return should propagate non-locally
        Boolean isMapGrepBlock = (Boolean) node.getAnnotation("isMapGrepBlock");
        if (isMapGrepBlock != null && isMapGrepBlock) {
            newJavaClassInfo.isMapGrepBlock = true;
        }
        
        EmitterContext subCtx =
                new EmitterContext(
                        newJavaClassInfo, // Internal Java class name
                        newSymbolTable, // Closure symbolTable
                        null, // Method visitor
                        null, // Class writer
                        RuntimeContextType.RUNTIME, // Call context
                        true, // Is boxed
                        ctx.errorUtil, // Error message utility
                        ctx.compilerOptions,
                        null);
        
        int skipVariables = EmitterMethodCreator.skipVariables; // Skip (this, @_, wantarray)
        
        try {
            Class<?> generatedClass =
                    EmitterMethodCreator.createClassWithMethod(
                            subCtx, node.block, node.useTryCatch
                    );
            String newClassNameDot = subCtx.javaClassInfo.javaClassName.replace('/', '.');
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Generated class name: " + newClassNameDot + " internal " + subCtx.javaClassInfo.javaClassName);
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Generated class env:  " + Arrays.toString(newEnv));
            RuntimeCode.anonSubs.put(subCtx.javaClassInfo.javaClassName, generatedClass); // Cache the class

            // Transfer pad constants (cached string literals referenced via \) from compile time
            // to a registry so makeCodeObject() can attach them to the RuntimeCode at runtime.
            if (subCtx.javaClassInfo.padConstants != null && !subCtx.javaClassInfo.padConstants.isEmpty()) {
                RuntimeCode.padConstantsByClassName.put(subCtx.javaClassInfo.javaClassName,
                        subCtx.javaClassInfo.padConstants.toArray(new RuntimeBase[0]));
            }

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
            // Always pass the current package name (CvSTASH) so anonymous subs
            // know which package they were compiled in. This is critical for
            // $AUTOLOAD being set in the correct package and for B::svref_2object->STASH->NAME.
            if (node.prototype != null) {
                mv.visitLdcInsn(node.prototype);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "makeCodeObject",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        } catch (InterpreterFallbackException fallback) {
            // JVM compilation failed (e.g., ASM frame crash) - use InterpretedCode instead
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("Using interpreter fallback for subroutine");
            
            // Set CvSTASH on the InterpretedCode if not already set
            if (fallback.interpretedCode.packageName == null) {
                fallback.interpretedCode.packageName = ctx.symbolTable.getCurrentPackage();
            }
            
            // Store the InterpretedCode in the interpretedSubs map with a unique key
            String fallbackKey = "interpreted_" + System.identityHashCode(fallback.interpretedCode);
            RuntimeCode.interpretedSubs.put(fallbackKey, fallback.interpretedCode);
            
            // Generate bytecode to retrieve and configure the InterpretedCode
            // 1. Load the InterpretedCode from the map
            mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "interpretedSubs",
                    "Ljava/util/HashMap;");
            mv.visitLdcInsn(fallbackKey);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/util/HashMap",
                    "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/backend/bytecode/InterpretedCode");
            
            // 2. Build RuntimeBase[] array of captured variables
            int numCaptured = newEnv.length - skipVariables;
            if (numCaptured > 0) {
                // Store the InterpretedCode temporarily
                int codeSlot = ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ASTORE, codeSlot);
                
                // Create array for captured vars
                mv.visitLdcInsn(numCaptured);
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "org/perlonjava/runtime/runtimetypes/RuntimeBase");
                
                // Fill the array with captured variables
                int arrayIndex = 0;
                int varIndex = 0;
                for (Integer currentIndex : visibleVariables.keySet()) {
                    if (varIndex >= skipVariables) {
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitLdcInsn(arrayIndex);
                        mv.visitVarInsn(Opcodes.ALOAD, currentIndex);
                        mv.visitInsn(Opcodes.AASTORE);
                        arrayIndex++;
                    }
                    varIndex++;
                }
                
                // Call withCapturedVars to create a new InterpretedCode with captured vars
                int arraySlot = ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ASTORE, arraySlot);
                
                mv.visitVarInsn(Opcodes.ALOAD, codeSlot);
                mv.visitVarInsn(Opcodes.ALOAD, arraySlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/backend/bytecode/InterpretedCode",
                        "withCapturedVars",
                        "([Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/backend/bytecode/InterpretedCode;",
                        false);
            }
            
            // 3. Wrap in RuntimeScalar(RuntimeCode)
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeScalar");
            mv.visitInsn(Opcodes.DUP_X1);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "<init>",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeCode;)V",
                    false);
            
            // Set prototype if needed
            if (node.prototype != null) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitFieldInsn(Opcodes.GETFIELD,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        "value",
                        "Ljava/lang/Object;");
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeCode");
                mv.visitLdcInsn(node.prototype);
                mv.visitFieldInsn(Opcodes.PUTFIELD,
                        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                        "prototype",
                        "Ljava/lang/String;");
            }
        }

        // Set isMapGrepBlock on the RuntimeCode so RuntimeCode.apply() can propagate
        // non-local returns through map/grep blocks
        if (isMapGrepBlock != null && isMapGrepBlock) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "value",
                    "Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeCode");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitFieldInsn(Opcodes.PUTFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "isMapGrepBlock",
                    "Z");
        }

        // Set isEvalBlock on the RuntimeCode so RuntimeCode.apply() propagates
        // non-local returns through eval BLOCK boundaries
        if (node.useTryCatch) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "value",
                    "Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeCode");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitFieldInsn(Opcodes.PUTFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "isEvalBlock",
                    "Z");
        }

        // Set attributes if needed (after try-catch, both paths leave RuntimeScalar on stack)
        if (node.attributes != null && !node.attributes.isEmpty()) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "value",
                    "Ljava/lang/Object;");
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeCode");
            // Create a new ArrayList and populate it
            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/util/ArrayList",
                    "<init>",
                    "()V",
                    false);
            for (String attr : node.attributes) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(attr);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "add",
                        "(Ljava/lang/Object;)Z",
                        true);
                mv.visitInsn(Opcodes.POP); // pop boolean return of add()
            }
            mv.visitFieldInsn(Opcodes.PUTFIELD,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "attributes",
                    "Ljava/util/List;");
        }

        // Dispatch MODIFY_CODE_ATTRIBUTES for anonymous subs with non-builtin attributes.
        // Named subs have their dispatch in SubroutineParser.handleNamedSub at compile time.
        // Anonymous subs need runtime dispatch because the code ref only exists at runtime.
        if (node.name == null && node.attributes != null && !node.attributes.isEmpty()) {
            java.util.Set<String> builtinAttrs = java.util.Set.of("lvalue", "method", "const");
            boolean hasNonBuiltin = false;
            for (String attr : node.attributes) {
                String name = attr.startsWith("-") ? attr.substring(1) : attr;
                int parenIdx = name.indexOf('(');
                String baseName = parenIdx >= 0 ? name.substring(0, parenIdx) : name;
                if (!builtinAttrs.contains(baseName) && !baseName.equals("prototype")) {
                    hasNonBuiltin = true;
                    break;
                }
            }
            if (hasNonBuiltin) {
                // Determine if this sub is a closure (captures outer lexical variables).
                // Closures get closure prototype semantics: MODIFY_CODE_ATTRIBUTES receives
                // the prototype (non-callable), and the expression result is a callable clone.
                boolean isClosure = visibleVariables.size() > skipVariables;

                // Stack: [RuntimeScalar(codeRef)]
                mv.visitInsn(Opcodes.DUP);
                // Stack: [codeRef, codeRef]
                mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
                mv.visitInsn(Opcodes.SWAP);
                // Stack: [codeRef, pkg, codeRef]
                mv.visitInsn(isClosure ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                // Stack: [codeRef, pkg, codeRef, isClosure]
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/perlmodule/Attributes",
                        "runtimeDispatchModifyCodeAttributes",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Z)V",
                        false);
                // Stack: [codeRef] (codeRef.value now points to clone if isClosure)
            }
        }

        // 6. Clean up the stack if context is VOID
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP); // Remove the RuntimeScalar object from the stack
        }

        // If the context is not VOID, the stack should contain [RuntimeScalar] (the CODE variable)
        // If the context is VOID, the stack should be empty
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("SUB end");
    }

    /**
     * Handles the postfix `()` node, which runs a subroutine.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The binary operator node representing the apply operation.
     */
    static void handleApplyOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleApplyElementOperator " + node + " in context " + emitterVisitor.ctx.contextType);
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Note: The call context is NOT stored in a local variable slot.
        // pushCallContext() emits either a compile-time constant (LDC) or loads the
        // callContext method parameter (ILOAD 2).  Both are side-effect-free and can be
        // re-emitted at the exact moment the value is needed on the JVM operand stack,
        // so there is no need to stash the int in a slot.
        //
        // Storing it in a slot would be WRONG: the pre-initialisation loop in
        // EmitterMethodCreator initialises every temporary slot to null (ACONST_NULL /
        // ASTORE) so that reference slots are never in TOP state at merge points.
        // An int slot initialised that way acquires the reference type "null" from the
        // pre-init path.  At a merge point (e.g. blockDispatcher) that is reachable from
        // both a path that executed ISTORE-callContextSlot and a path through a
        // conditional branch that skipped it, the JVM verifier sees conflicting types
        // (int vs null-reference) and throws VerifyError: "Bad local variable type".
        // This VerifyError triggers the interpreter-fallback which re-runs the main
        // script body, calling plan() a second time and causing the "tried to plan
        // twice" error in DBIx::Class torture.t (perf/reduce-apply-bytecode Phase 2).

        String subroutineName = "";
        if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
            if (operatorNode.operand instanceof IdentifierNode identifierNode) {
                subroutineName = NameNormalizer.normalizeVariableName(identifierNode.name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleApplyElementOperator subroutine " + subroutineName);
            }
        }

        node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR)); // Target - left parameter: Code ref

        // Dereference the scalar to get the CODE reference if needed
        // When we have &$x() the left side is OperatorNode("$") (the & is consumed by the parser)
        // We need to look up the CODE slot from the glob if the scalar contains a string.
        // Check if the left side is a scalar variable or a block containing a scalar variable
        boolean isScalarVariable = false;
        boolean isLexicalSub = false;
        OperatorNode scalarOpNode = null;
        boolean isBlockDeref = false;  // &{expr} syntax - always need to deref for symbolic refs

        if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("$")) {
            // This is &$var() or $var->() syntax
            isScalarVariable = true;
            scalarOpNode = operatorNode;
        } else if (node.left instanceof BlockNode blockNode) {
            // This is &{expr} syntax where expr can be:
            // - &{$var} - simple variable
            // - &{$hash{key}} - hash element
            // - &{$array[idx]} - array element
            // - &{some_expression} - any expression
            // All of these may return a string that needs to be resolved as a symbolic reference
            isBlockDeref = true;
            if (blockNode.elements.size() == 1 &&
                    blockNode.elements.get(0) instanceof OperatorNode opNode &&
                    opNode.operator.equals("$")) {
                // Specific case: &{$var}
                isScalarVariable = true;
                scalarOpNode = opNode;
            }
        }

        if (isScalarVariable && scalarOpNode != null) {
            // Check if the variable is a lexical subroutine (already a CODE reference)
            // Lexical subs have a "hiddenVarName" annotation and should not be dereferenced
            String hiddenVarName = (String) scalarOpNode.getAnnotation("hiddenVarName");
            isLexicalSub = (hiddenVarName != null);

            // Only call codeDerefNonStrict when strict refs is disabled AND not a lexical sub
            // This allows symbolic references like: my $x = "main::test"; &$x()
            if (!isLexicalSub && !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                // Without strict refs and not a lexical sub: allow symbolic references
                // Call codeDerefNonStrict to look up CODE slot from glob if needed
                emitterVisitor.pushCurrentPackage();
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                        "codeDerefNonStrict",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            }
        } else if (isBlockDeref && !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
            // For &{expr} where expr is not a simple variable (e.g., &{$hash{key}})
            // We need to call codeDerefNonStrict to resolve symbolic references
            // using the current package
            emitterVisitor.pushCurrentPackage();
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "codeDerefNonStrict",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }

        int codeRefSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledCodeRef = codeRefSlot >= 0;
        if (!pooledCodeRef) {
            codeRefSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitVarInsn(Opcodes.ASTORE, codeRefSlot);

        // Special handling for eval blocks: share @_ with enclosing sub directly.
        // In Perl 5, eval { } shares @_ with its enclosing sub, so shift/pop inside
        // eval { } modifies the caller's @_. We achieve this by passing the caller's
        // RuntimeArray directly instead of expanding @_ into a new array.
        // Note: use apply() not applyEval() because the eval block's own generated
        // method already has try/catch handling (useTryCatch=true). Using applyEval
        // would add a second layer that clears $@ after the block returns.
        if (node.left instanceof SubroutineNode subNode && subNode.useTryCatch) {
            mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
            mv.visitVarInsn(Opcodes.ALOAD, 1);  // caller's @_ (slot 1) - shared, not copied
            emitterVisitor.pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "apply",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);

            if (pooledCodeRef) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalar",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
            return;
        }

        // Special handling for &func (no parens): share @_ with caller directly.
        // In Perl 5, &func without parens shares the caller's @_ by alias,
        // so shift/pop inside the callee modifies the caller's @_.
        // We achieve this by passing the caller's RuntimeArray (slot 1) directly
        // instead of creating a new array from @_ elements.
        if (node.getBooleanAnnotation("shareCallerArgs")) {
            mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
            mv.visitVarInsn(Opcodes.ALOAD, 1);  // caller's @_ (slot 1) - shared, not copied
            emitterVisitor.pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "apply",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);

            if (pooledCodeRef) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            // Registry-based non-local control flow check (for next/last/redo LABEL from closures)
            emitControlFlowCheck(emitterVisitor.ctx);

            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalar",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
            return;
        }

        int nameSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledName = nameSlot >= 0;
        if (!pooledName) {
            nameSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitLdcInsn(subroutineName);
        mv.visitVarInsn(Opcodes.ASTORE, nameSlot);

        // Generate native RuntimeBase[] array for parameters instead of RuntimeList
        ListNode paramList = ListNode.makeList(node.right);
        int argCount = paramList.elements.size();

        int argsArraySlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledArgsArray = argsArraySlot >= 0;
        if (!pooledArgsArray) {
            argsArraySlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }

        if (argCount <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + argCount);
        } else if (argCount <= 127) {
            mv.visitIntInsn(Opcodes.BIPUSH, argCount);
        } else {
            mv.visitIntInsn(Opcodes.SIPUSH, argCount);
        }
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "org/perlonjava/runtime/runtimetypes/RuntimeBase");
        mv.visitVarInsn(Opcodes.ASTORE, argsArraySlot);

        EmitterVisitor listVisitor = emitterVisitor.with(RuntimeContextType.LIST);
        for (int index = 0; index < argCount; index++) {
            int argSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledArg = argSlot >= 0;
            if (!pooledArg) {
                argSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }

            paramList.elements.get(index).accept(listVisitor);
            mv.visitVarInsn(Opcodes.ASTORE, argSlot);

            mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
            if (index <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + index);
            } else if (index <= 127) {
                mv.visitIntInsn(Opcodes.BIPUSH, index);
            } else {
                mv.visitIntInsn(Opcodes.SIPUSH, index);
            }
            mv.visitVarInsn(Opcodes.ALOAD, argSlot);
            mv.visitInsn(Opcodes.AASTORE);

            if (pooledArg) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
        }

        // Set debug line number to the call site (the function name/reference expression),
        // so that caller() inside the called subroutine reports the correct source line.
        // Without this, the JVM frame reports the line of the closing ')' instead.
        if (node.left != null && node.left.getIndex() > 0) {
            ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, node.left.getIndex());
        }

        mv.visitVarInsn(Opcodes.ALOAD, codeRefSlot);
        mv.visitVarInsn(Opcodes.ALOAD, nameSlot);
        mv.visitVarInsn(Opcodes.ALOAD, argsArraySlot);
        emitterVisitor.pushCallContext();   // Push call context to stack
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                "apply",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;[Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                false); // Generate an .apply() call

        if (pooledArgsArray) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledName) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledCodeRef) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        // Tagged returns control-flow handling:
        // If RuntimeCode.apply() returned a RuntimeControlFlowList marker, handle it here.
        if (ENABLE_CONTROL_FLOW_CHECKS
                && emitterVisitor.ctx.javaClassInfo.returnLabel != null
                && emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot >= 0) {

            // Get or create a block-level dispatcher for the current loop state
            String loopStateSignature = emitterVisitor.ctx.javaClassInfo.getLoopStateSignature();
            Label blockDispatcher = emitterVisitor.ctx.javaClassInfo.blockDispatcherLabels.get(loopStateSignature);
            boolean isFirstUse = (blockDispatcher == null);

            if (isFirstUse) {
                blockDispatcher = new Label();
                emitterVisitor.ctx.javaClassInfo.blockDispatcherLabels.put(loopStateSignature, blockDispatcher);
            }

            Label notControlFlow = new Label();

            int belowResultStackLevel = 0;
            JavaClassInfo.SpillRef[] baseSpills = new JavaClassInfo.SpillRef[0];

            // Store result in temp slot
            mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

            // If the caller kept values on the JVM operand stack below the call result (e.g. a left operand),
            // spill them now so control-flow propagation can jump to returnLabel with an empty stack.
            for (int i = belowResultStackLevel - 1; i >= 0; i--) {
                baseSpills[i] = emitterVisitor.ctx.javaClassInfo.acquireSpillRefOrAllocate(emitterVisitor.ctx.symbolTable);
                emitterVisitor.ctx.javaClassInfo.storeSpillRef(mv, baseSpills[i]);
            }

            // Fast path: check if the result is any kind of control flow marker.
            // This is the common case (no control flow) — branch skips everything below.
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "isNonLocalGoto",
                    "()Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);

            // A control-flow marker was returned.  It might be TAILCALL (goto &sub),
            // LAST/NEXT/REDO/GOTO, or RETURN.  Resolve any TAILCALL chain first, then
            // re-check whether the final result still carries a marker that the
            // block-level dispatcher needs to handle.
            // Keeping resolveTailCalls() inside this branch means the common case
            // (no control flow) incurs zero extra overhead.  (Phase 2 of
            // perf/reduce-apply-bytecode.)
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
            emitterVisitor.pushCallContext();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "resolveTailCalls",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

            // Re-check: a TAILCALL chain may have ended in a normal return.
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                    "isNonLocalGoto",
                    "()Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, notControlFlow);

            // Still a marker (LAST/NEXT/REDO/GOTO/RETURN) — dispatch it.
            mv.visitJumpInsn(Opcodes.GOTO, blockDispatcher);

            // Not a control flow marker - load it back and continue
            mv.visitLabel(notControlFlow);
            for (JavaClassInfo.SpillRef ref : baseSpills) {
                if (ref != null) {
                    emitterVisitor.ctx.javaClassInfo.loadSpillRef(mv, ref);
                    emitterVisitor.ctx.javaClassInfo.releaseSpillRef(ref);
                }
            }
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);

            // If this is the first use of this dispatcher, emit it now
            // We need to skip over it in the normal flow
            if (isFirstUse) {
                Label skipDispatcher = new Label();
                mv.visitJumpInsn(Opcodes.GOTO, skipDispatcher);
                emitBlockDispatcher(mv, emitterVisitor, blockDispatcher, baseSpills);
                mv.visitLabel(skipDispatcher);
            }
        }

        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            // Transform the value in the stack to RuntimeScalar
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        } else if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
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
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleSelfCallOperator " + node + " in context " + emitterVisitor.ctx.contextType);

        MethodVisitor mv = emitterVisitor.ctx.mv;

        String className = emitterVisitor.ctx.javaClassInfo.javaClassName;

        // Load 'this' (the current RuntimeCode instance)
        mv.visitVarInsn(Opcodes.ALOAD, 0); // Assuming 'this' is at index 0

        // Retrieve this.__SUB__
        mv.visitFieldInsn(Opcodes.GETFIELD,
                className,    // The class containing the field (e.g., "com/example/MyClass")
                "__SUB__",     // Field name
                "Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;");    // Field descriptor

        // Create a Perl undef if the value in the stack is null
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                "selfReferenceMaybeNull",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);

        // Now we have a RuntimeScalar representing the current subroutine (__SUB__)
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    /**
     * Emits bytecode to check if a RuntimeList returned from a subroutine call
     * is marked with control flow information (last/next/redo/goto/tail call).
     * If marked, cleans the stack and jumps to returnLabel.
     * <p>
     * Pattern:
     * DUP                          // Duplicate result for test
     * INVOKEVIRTUAL isNonLocalGoto // Check if marked
     * IFNE handleControlFlow       // Jump if marked
     * POP                          // Discard duplicate
     * // Continue with result on stack
     * <p>
     * handleControlFlow:
     * ASTORE temp                // Save marked result
     * emitPopInstructions(0)     // Clean stack
     * ALOAD temp                 // Load marked result
     * GOTO returnLabel           // Jump to return point
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
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowRegistry",
                    "checkAndWrapIfNeeded",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                    false);
            // Stack: [RuntimeList result_or_marked]

            // No branching needed! The helper method handles everything.
            // The result is either the original or a marked list.
            // The loop level will check if it's marked and handle it.
        }
        // If not inside a loop, don't check registry (result stays on stack)
    }

    /**
     * Emits the block-level dispatcher code that handles control flow for all call sites
     * with the same visible loop state.
     *
     * @param mv              MethodVisitor to emit bytecode
     * @param emitterVisitor  The emitter visitor context
     * @param blockDispatcher The label for this block dispatcher
     * @param baseSpills      Array of spill references that need to be cleaned up
     */
    static void emitBlockDispatcher(MethodVisitor mv, EmitterVisitor emitterVisitor,
                                            Label blockDispatcher, JavaClassInfo.SpillRef[] baseSpills) {
        Label propagateToCaller = new Label();
        Label checkLoopLabels = new Label();
        Label handleHigherOrdinals = new Label();

        // Entry point for block dispatcher
        mv.visitLabel(blockDispatcher);

        // Get control flow type ordinal into controlFlowActionSlot
        mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                "getControlFlowType",
                "()Lorg/perlonjava/runtime/runtimetypes/ControlFlowType;",
                false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/ControlFlowType",
                "ordinal",
                "()I",
                false);
        mv.visitVarInsn(Opcodes.ISTORE, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);

        // Only handle LAST/NEXT/REDO locally (ordinals 0/1/2). Others go to handleHigherOrdinals.
        mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitJumpInsn(Opcodes.IF_ICMPGT, handleHigherOrdinals);

        // Check each visible loop label
        mv.visitLabel(checkLoopLabels);
        for (LoopLabels loopLabels : emitterVisitor.ctx.javaClassInfo.loopLabelStack) {
            Label nextLoopCheck = new Label();

            // if (!marked.matchesLabel(loopLabels.labelName)) continue;
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            if (loopLabels.labelName != null) {
                mv.visitLdcInsn(loopLabels.labelName);
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "matchesLabel",
                    "(Ljava/lang/String;)Z",
                    false);
            mv.visitJumpInsn(Opcodes.IFEQ, nextLoopCheck);

            // Match found: dispatch based on type
            Label checkNext = new Label();
            Label checkRedo = new Label();

            // if (type == LAST (0)) goto lastLabel
            mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkNext);
            if (loopLabels.lastLabel == emitterVisitor.ctx.javaClassInfo.returnLabel) {
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                if (loopLabels.context != RuntimeContextType.VOID) {
                    EmitOperator.emitUndef(mv);
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.lastLabel);
            }

            // if (type == NEXT (1)) goto nextLabel
            mv.visitLabel(checkNext);
            mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, checkRedo);
            if (loopLabels.nextLabel == emitterVisitor.ctx.javaClassInfo.returnLabel) {
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                if (loopLabels.context != RuntimeContextType.VOID) {
                    EmitOperator.emitUndef(mv);
                }
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.nextLabel);
            }

            // if (type == REDO (2)) goto redoLabel
            mv.visitLabel(checkRedo);
            if (loopLabels.redoLabel == emitterVisitor.ctx.javaClassInfo.returnLabel) {
                mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);
            } else {
                mv.visitJumpInsn(Opcodes.GOTO, loopLabels.redoLabel);
            }

            mv.visitLabel(nextLoopCheck);
        }

        // No loop match; propagate to caller
        mv.visitJumpInsn(Opcodes.GOTO, propagateToCaller);

        // Handle higher ordinals (GOTO=3, TAILCALL=4, RETURN=5)
        mv.visitLabel(handleHigherOrdinals);
        if (!emitterVisitor.ctx.javaClassInfo.isMapGrepBlock) {
            // In a normal subroutine: consume RETURN markers by unwrapping the return value.
            // This is where a non-local return from a map/grep block gets consumed.
            Label notReturn = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, emitterVisitor.ctx.javaClassInfo.controlFlowActionSlot);
            mv.visitLdcInsn(5);  // RETURN.ordinal() = 5
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, notReturn);

            // It's RETURN: unwrap the return value and use it as the subroutine's return value
            for (JavaClassInfo.SpillRef ref : baseSpills) {
                if (ref != null) {
                    emitterVisitor.ctx.javaClassInfo.releaseSpillRef(ref);
                }
            }
            mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                    "getReturnValue",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;",
                    false);
            mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.returnValueSlot);
            mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);

            mv.visitLabel(notReturn);
        }
        // For map/grep blocks or non-RETURN markers: propagate to caller
        // (fall through to propagateToCaller)

        // Propagate: jump to returnLabel with the marked list
        mv.visitLabel(propagateToCaller);
        for (JavaClassInfo.SpillRef ref : baseSpills) {
            if (ref != null) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillRef(ref);
            }
        }
        mv.visitVarInsn(Opcodes.ALOAD, emitterVisitor.ctx.javaClassInfo.controlFlowTempSlot);
        mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.returnValueSlot);
        mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
    }
}
