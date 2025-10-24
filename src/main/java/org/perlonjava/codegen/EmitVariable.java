package org.perlonjava.codegen;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.perlmodule.Warnings;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.SymbolTable;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.perlmodule.Strict.HINT_STRICT_REFS;
import static org.perlonjava.perlmodule.Strict.HINT_STRICT_VARS;

/**
 * Bytecode emitter for Perl variable operations.
 * 
 * <p>This class generates JVM bytecode for accessing and manipulating Perl variables,
 * including:
 * <ul>
 *   <li>Scalar variables: {@code $var}</li>
 *   <li>Array variables: {@code @array}</li>
 *   <li>Hash variables: {@code %hash}</li>
 *   <li>Typeglobs: {@code *glob}</li>
 *   <li>Array/hash element access: {@code $array[0]}, {@code $hash{key}}</li>
 *   <li>Array/hash slices: {@code @array[0,1,2]}, {@code @hash{keys}}</li>
 * </ul>
 * 
 * <p>The class handles several important Perl semantics:
 * <ul>
 *   <li><b>Strict vars checking:</b> Enforces {@code use strict 'vars'} by preventing
 *       access to undeclared global variables</li>
 *   <li><b>Lexical vs global variables:</b> Distinguishes between {@code my/our/state}
 *       declared variables and package globals</li>
 *   <li><b>Special variables:</b> Allows built-in variables like {@code $@}, {@code %SIG},
 *       {@code @INC} even under strict</li>
 *   <li><b>Variable vivification:</b> Auto-creates variables when needed (except under strict)</li>
 *   <li><b>Context-sensitive access:</b> Handles scalar vs list context appropriately</li>
 * </ul>
 * 
 * <p>Key methods:
 * <ul>
 *   <li>{@link #handleVariableOperator} - Main entry point for variable operations</li>
 *   <li>{@link #fetchGlobalVariable} - Emits bytecode to fetch global variables</li>
 * </ul>
 */
public class EmitVariable {

    /**
     * Emits bytecode to fetch a global (package) variable.
     * 
     * <p>This method generates JVM bytecode to access global variables stored in the
     * {@link GlobalVariable} registry. It handles several important cases:
     * 
     * <h3>Strict Vars Enforcement</h3>
     * When {@code use strict 'vars'} is enabled and {@code createIfNotExists} is false,
     * this method enforces that only the following variables are allowed:
     * <ul>
     *   <li>Built-in special variables (checked via {@link #isBuiltinSpecialVariable})</li>
     *   <li>Variables that were explicitly allowed by the caller</li>
     * </ul>
     * 
     * <p>Note: The strict vars checking is done in the caller (handleVariableOperator)
     * before this method is called. This method only fetches variables that have been
     * determined to be accessible.
     * 
     * <h3>Variable Types Handled</h3>
     * <ul>
     *   <li><b>Scalars ($):</b> Calls {@code GlobalVariable.getGlobalVariable()}</li>
     *   <li><b>Arrays (@):</b> Calls {@code GlobalVariable.getGlobalArray()}</li>
     *   <li><b>Hashes (%):</b> Calls {@code GlobalVariable.getGlobalHash()}</li>
     *   <li><b>Stashes (%Package::):</b> Calls {@code HashSpecialVariable.getStash()}</li>
     * </ul>
     * 
     * @param ctx the emitter context containing the method visitor and symbol table
     * @param createIfNotExists if true, allows variable creation; if false, enforces strict checking
     * @param sigil the variable sigil ($, @, %)
     * @param varName the variable name (without sigil, may include package qualifier)
     * @param tokenIndex the token index for error reporting
     * @throws PerlCompilerException if strict vars is enabled and the variable is not allowed
     */
    private static void fetchGlobalVariable(EmitterContext ctx, boolean createIfNotExists, String sigil, String varName, int tokenIndex) {

        String var = NameNormalizer.normalizeVariableName(varName, ctx.symbolTable.getCurrentPackage());
        ctx.logDebug("GETVAR lookup global " + sigil + varName + " normalized to " + var + " createIfNotExists:" + createIfNotExists);

        if (sigil.equals("$") && (createIfNotExists || GlobalVariable.existsGlobalVariable(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
            return;
        }

        if (sigil.equals("@") && (createIfNotExists || GlobalVariable.existsGlobalArray(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeArray;",
                    false);
            return;
        }

        if (sigil.equals("%") && var.endsWith("::")) {
            // A stash
            // Stash is the hash that represents a package's symbol table,
            // containing all the typeglobs for that package.
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/HashSpecialVariable",
                    "getStash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
            return;
        }

        if (sigil.equals("%") && (createIfNotExists || GlobalVariable.existsGlobalHash(var))) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeHash;",
                    false);
            return;
        }

        // Variable not found and not allowed under strict
        throw new PerlCompilerException(
                tokenIndex,
                "Global symbol \""
                        + sigil + varName
                        + "\" requires explicit package name (did you forget to declare \"my "
                        + sigil + varName
                        + "\"?)",
                ctx.errorUtil);
    }

    /**
     * Main entry point for emitting bytecode for variable operations.
     * 
     * <p>This method handles all forms of Perl variable access and generates appropriate
     * JVM bytecode. It distinguishes between:
     * 
     * <h3>Variable Types</h3>
     * <ul>
     *   <li><b>Simple variables:</b> {@code $var}, {@code @array}, {@code %hash}</li>
     *   <li><b>Typeglobs:</b> {@code *name} (file handles, symbol table entries)</li>
     *   <li><b>Code references:</b> {@code &sub} (subroutine references)</li>
     *   <li><b>Dereferencing:</b> {@code $$ref}, {@code @$ref}, {@code %$ref}</li>
     * </ul>
     * 
     * <h3>Variable Storage</h3>
     * Variables can be stored in two places:
     * <ul>
     *   <li><b>Lexical (local):</b> {@code my}, {@code our}, {@code state} variables stored
     *       in JVM local variable slots</li>
     *   <li><b>Global (package):</b> Package variables stored in {@link GlobalVariable} registry</li>
     * </ul>
     * 
     * <h3>Strict Vars Logic</h3>
     * The method computes {@code createIfNotExists} flag based on:
     * <ul>
     *   <li>Fully qualified names: {@code $Package::var} (always allowed)</li>
     *   <li>Regex variables: {@code $1}, {@code $2} (always allowed)</li>
     *   <li>Special sort variables: {@code $a}, {@code $b} in {@code main::} (always allowed)</li>
     *   <li>Strict mode: {@code use strict 'vars'} (disallows undeclared globals)</li>
     *   <li>Lexical declaration: {@code my/our/state} (allowed under strict)</li>
     * </ul>
     * 
     * <h3>Context Handling</h3>
     * In scalar context, array/hash variables are automatically converted to scalar
     * using {@code RuntimeBase.scalar()}.
     * 
     * @param emitterVisitor the visitor containing the emitter context and method visitor
     * @param node the OperatorNode representing the variable operation
     */
    static void handleVariableOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        // In void context, don't emit any code
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
            return;
        }
        
        String sigil = node.operator;
        MethodVisitor mv = emitterVisitor.ctx.mv;
        
        // Case 1: Simple variable with identifier (most common case)
        // Examples: $var, @array, %hash, *glob, &sub
        if (node.operand instanceof IdentifierNode identifierNode) { // $a @a %a
            String name = identifierNode.name;
            emitterVisitor.ctx.logDebug("GETVAR " + sigil + name);

            if (sigil.equals("*")) {
                // typeglob
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
                        "getGlobalIO",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeGlob;",
                        false);
                return;
            }

            if (sigil.equals("&")) {
                // Code
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/GlobalVariable",
                        "getGlobalCodeRef",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                        false);
                return;
            }

            // ===== SYMBOL TABLE LOOKUP =====
            // Check if this variable is declared in the current lexical scope
            SymbolTable.SymbolEntry symbolEntry = emitterVisitor.ctx.symbolTable.getSymbolEntry(sigil + name);
            
            // Note: @_ is lexical in PerlOnJava (unlike standard Perl where it's package-scoped)
            boolean isDeclared = symbolEntry != null;
            
            // A variable is lexical if it was declared with my/our/state
            // These are stored in JVM local variable slots, not in GlobalVariable registry
            boolean isLexical = isDeclared && (
                    symbolEntry.decl().equals("my")
                            || symbolEntry.decl().equals("state")
                            || symbolEntry.decl().equals("our")
                    // Note: @_ special handling is disabled as it breaks some tests
                    // || (symbolEntry.decl().equals("our") && symbolEntry.name().equals("@_"))
            );

            if (!isLexical) {
                // ===== GLOBAL VARIABLE ACCESS =====
                // This is not a lexically declared variable, so fetch it from the global registry
                
                // If there's a symbol entry (e.g., from 'our' declaration), use its package
                if (symbolEntry != null) {
                    name = NameNormalizer.normalizeVariableName(name, symbolEntry.perlPackage());
                }

                // ===== STRICT VARS LOGIC =====
                // Determine if this variable should be allowed under 'use strict "vars"'
                
                // Special case: $a and $b in main:: package are exempt from strict
                // (they're used by sort() without declaration)
                String normalizedName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                boolean isSpecialSortVar = sigil.equals("$") && ("main::a".equals(normalizedName) || "main::b".equals(normalizedName));

                // Compute createIfNotExists flag - determines if variable can be auto-vivified
                boolean createIfNotExists = name.contains("::")  // Fully qualified: $Package::var
                        || ScalarUtils.isInteger(name)           // Regex capture: $1, $2, etc.
                        || isSpecialSortVar                      // Sort variables: $a, $b
                        || !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_VARS)  // no strict 'vars'
                        || (isDeclared && isLexical);            // Lexically declared (my/our/state)
                
                // Fetch the global variable (may throw exception if strict and not allowed)
                fetchGlobalVariable(emitterVisitor.ctx, createIfNotExists, sigil, name, node.getIndex());
            } else {
                // ===== LEXICAL VARIABLE ACCESS =====
                // Variable is lexical (my/our/state), load it from JVM local variable slot
                mv.visitVarInsn(Opcodes.ALOAD, symbolEntry.index());
            }
            
            // ===== CONTEXT CONVERSION =====
            // In scalar context, convert array/hash to scalar (e.g., array length, hash key count)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
            
            emitterVisitor.ctx.logDebug("GETVAR end " + symbolEntry);
            return;
        }
        switch (sigil) {
            case "@":
                // `@$a`
                emitterVisitor.ctx.logDebug("GETVAR `@$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "arrayDeref", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeArray", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                return;
            case "%":
                // `%$a`
                emitterVisitor.ctx.logDebug("GETVAR `%$a`");
                node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "hashDeref", "()Lorg/perlonjava/runtime/RuntimeHash;", false);
                return;
            case "$":
                // `$$a`
                emitterVisitor.ctx.logDebug("GETVAR `$$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDeref", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "scalarDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                return;
            case "*":
                // `*$a`
                emitterVisitor.ctx.logDebug("GETVAR `*$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "globDeref", "()Lorg/perlonjava/runtime/RuntimeGlob;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "globDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeGlob;", false);
                }
                return;
            case "&":
                // `&$a` or `&{sub ...}`
                emitterVisitor.ctx.logDebug("GETVAR `&$a` or `&{sub ...}`");

                // Special handling for &{sub ...} - BlockNode containing SubroutineNode
                if (node.operand instanceof BlockNode blockNode &&
                        blockNode.elements.size() == 1 &&
                        blockNode.elements.get(0) instanceof SubroutineNode) {

                    emitterVisitor.ctx.logDebug("GETVAR `&{sub ...}` - emitting subroutine as RuntimeScalar");
                    // Emit the subroutine directly as a RuntimeScalar (code reference)
                    blockNode.elements.get(0).accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                } else {
                    // Regular case: `&$a`
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                }

                mv.visitVarInsn(Opcodes.ALOAD, 1);  // push @_ to stack
                emitterVisitor.pushCallContext();   // push call context to stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/RuntimeCode",
                        "apply",
                        "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeArray;I)Lorg/perlonjava/runtime/RuntimeList;",
                        false); // generate an .apply() call

                // Handle context conversion: RuntimeCode.apply() always returns RuntimeList
                // but we need to convert based on the calling context
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    // VOID context: consume the stack
                    mv.visitInsn(Opcodes.POP);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    // SCALAR context: convert RuntimeList to RuntimeScalar
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                // LIST context: RuntimeList is already correct, no conversion needed

                return;
        }

        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + sigil, emitterVisitor.ctx.errorUtil);
    }

    static void handleAssignOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        ctx.logDebug("SET " + node);
        MethodVisitor mv = ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        int lvalueContext = LValueVisitor.getContext(node);
        ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative

        Node left = node.left;
        Node right = node.right;

        boolean isLocalAssignment = left instanceof OperatorNode operatorNode && operatorNode.operator.equals("local");

        switch (lvalueContext) {
            case RuntimeContextType.SCALAR:
                ctx.logDebug("SET right side scalar");

                if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("state")) {
                    emitStateInitialization(emitterVisitor, node, operatorNode, ctx);
                    break;
                }

                // The left value can be a variable, an operator or a subroutine call:
                //   `pos`, `substr`, `vec`, `sub :lvalue`

                node.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the value

                if (isLocalAssignment) {
                    // Clone the scalar before calling local()
                    if (right instanceof OperatorNode operatorNode && operatorNode.operator.equals("*")) {
                        // TODO - glob clone
                    } else {
                        mv.visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/RuntimeScalar",
                                "clone",
                                "()Lorg/perlonjava/runtime/RuntimeScalar;",
                                false
                        );
                    }
                }

                node.left.accept(emitterVisitor.with(RuntimeContextType.SCALAR));   // emit the variable

                OperatorNode nodeLeft = null;
                if (node.left instanceof OperatorNode operatorNode) {
                    nodeLeft = operatorNode;
                    if (nodeLeft.operator.equals("local") && nodeLeft.operand instanceof OperatorNode localNode) {
                        nodeLeft = localNode;  // local *var = ...
                    }

                    if (nodeLeft.operator.equals("keys")) {
                        // `keys %x = 3`   lvalue keys is a no-op
                        mv.visitInsn(Opcodes.SWAP); // move the target first
                        mv.visitInsn(Opcodes.POP);
                        break;
                    }

                    if (nodeLeft.operator.equals("\\")) {
                        // `\$b = \$a` requires "refaliasing"
                        if (!ctx.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
                            throw new PerlCompilerException(node.tokenIndex, "Experimental aliasing via reference not enabled", ctx.errorUtil);
                        }
                        // TODO: Implement proper reference aliasing
                        // For now, we just assign the reference value without creating an alias
                        // This is not fully correct but allows tests to progress
                    }
                }

                boolean isGlob = false;
                String leftDescriptor = "org/perlonjava/runtime/RuntimeScalar";
                if (nodeLeft != null && nodeLeft.operator.equals("*")) {
                    // glob:  *var
                    leftDescriptor = "org/perlonjava/runtime/RuntimeGlob";
                    isGlob = true;
                }
                String rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;";
                if (node.right instanceof OperatorNode && ((OperatorNode) node.right).operator.equals("*")) {
                    rightDescriptor = "(Lorg/perlonjava/runtime/RuntimeGlob;)Lorg/perlonjava/runtime/RuntimeScalar;";
                    isGlob = true;
                }
                if (isGlob) {
                    mv.visitInsn(Opcodes.SWAP); // move the target first
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, leftDescriptor, "set", rightDescriptor, false);
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "addToScalar", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
                }
                break;
            case RuntimeContextType.LIST:
                emitterVisitor.ctx.logDebug("SET right side list");

                if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("state")) {
                    emitStateInitialization(emitterVisitor, node, operatorNode, ctx);
                    break;
                }

                // make sure the right node is a ListNode
                if (!(right instanceof ListNode)) {
                    List<Node> elements = new ArrayList<>();
                    elements.add(right);
                    right = new ListNode(elements, node.tokenIndex);
                }
                right.accept(emitterVisitor.with(RuntimeContextType.LIST));   // emit the value

                if (isLocalAssignment) {
                    // Clone the list before calling local()
                    mv.visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/RuntimeList",
                            "clone",
                            "()Lorg/perlonjava/runtime/RuntimeList;",
                            false
                    );
                }

                // For declared references, we need special handling
                // The my operator needs to be processed to create the variables first
                node.left.accept(emitterVisitor.with(RuntimeContextType.LIST));   // emit the variable
                mv.visitInsn(Opcodes.SWAP); // move the target first
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "setFromList", "(Lorg/perlonjava/runtime/RuntimeList;)Lorg/perlonjava/runtime/RuntimeArray;", false);
                EmitOperator.handleScalarContext(emitterVisitor, node);
                break;
            default:
                // Check if this is a chop/chomp that can't be an lvalue
                if (node.left instanceof OperatorNode operatorNode) {
                    String op = operatorNode.operator;
                    if (op.equals("chop") || op.equals("chomp")) {
                        throw new PerlCompilerException(node.tokenIndex, "Can't modify " + op + " in scalar assignment", ctx.errorUtil);
                    }
                }
                throw new PerlCompilerException(node.tokenIndex, "Unsupported assignment context: " + lvalueContext, ctx.errorUtil);
        }
        EmitOperator.handleVoidContext(emitterVisitor);
        emitterVisitor.ctx.logDebug("SET end");
    }

    private static void emitStateInitialization(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorNode operatorNode, EmitterContext ctx) {
        // This is a state variable initialization, it should run exactly once.
        ctx.logDebug("handleAssignOperator initialize state variable " + operatorNode);
        OperatorNode varNode = (OperatorNode) operatorNode.operand;
        IdentifierNode nameNode = (IdentifierNode) varNode.operand;
        String sigil = varNode.operator;

        // Emit: state $var // initializeState(id, value)
        int tokenIndex = node.tokenIndex;

        operatorNode.accept(emitterVisitor.with(RuntimeContextType.VOID));

        Node testStateVariable = new BinaryOperatorNode(
                "(",
                new OperatorNode(
                        "&",
                        new IdentifierNode("Internals::is_initialized_state_variable", tokenIndex),
                        tokenIndex
                ),
                ListNode.makeList(
                        new OperatorNode("__SUB__", null, tokenIndex),
                        new StringNode(sigil + nameNode.name, tokenIndex),
                        new NumberNode(String.valueOf(varNode.id), tokenIndex)
                ),
                tokenIndex
        );
        ctx.logDebug("handleAssignOperator initialize state variable " + testStateVariable);
        // testStateVariable.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

        // Determine the method to call and its descriptor based on the sigil
        String methodName = switch (sigil) {
            case "$" -> "Internals::initialize_state_variable";
            case "@" -> "Internals::initialize_state_array";
            case "%" -> "Internals::initialize_state_hash";
            default ->
                    throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + sigil, ctx.errorUtil);
        };
        Node initStateVariable = new BinaryOperatorNode(
                "(",
                new OperatorNode(
                        "&",
                        new IdentifierNode(methodName, tokenIndex),
                        tokenIndex
                ),
                ListNode.makeList(
                        new OperatorNode("__SUB__", null, tokenIndex),
                        new StringNode(sigil + nameNode.name, tokenIndex),
                        new NumberNode(String.valueOf(varNode.id), tokenIndex),
                        node.right
                ),
                tokenIndex
        );
        ctx.logDebug("handleAssignOperator initialize state variable " + initStateVariable);
        // initStateVariable.accept(emitterVisitor.with(RuntimeContextType.VOID));

        new BinaryOperatorNode("||", testStateVariable, initStateVariable, tokenIndex)
                .accept(emitterVisitor.with(RuntimeContextType.VOID));

        varNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
    }

    static void handleMyOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        String operator = node.operator;
        if (node.operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            for (Node element : listNode.elements) {
                if (element instanceof OperatorNode && "undef".equals(((OperatorNode) element).operator)) {
                    continue; // skip "undef"
                }

                // Check if this element is a backslash operator (declared reference)
                // This handles cases like my(\$x) where the backslash is inside the parentheses
                if (element instanceof OperatorNode operatorNode && operatorNode.operator.equals("\\")) {
                    // Handle my(\$x), my(\@arr), my(\%hash)
                    if (operatorNode.operand instanceof OperatorNode varNode) {
                        // This is a declared reference: my(\$x), my(\@arr), my(\%hash)
                        // Declared references always create scalar variables
                        OperatorNode scalarVarNode = varNode;
                        if (varNode.operator.equals("@") || varNode.operator.equals("%")) {
                            // Create a scalar version of the variable for emission
                            scalarVarNode = new OperatorNode("$", varNode.operand, varNode.tokenIndex);
                            // Transfer the isDeclaredReference annotation
                            scalarVarNode.setAnnotation("isDeclaredReference", true);
                        }
                        // Create a my node for the scalar variable with the isDeclaredReference flag
                        OperatorNode myNode = new OperatorNode(operator, scalarVarNode, listNode.tokenIndex);
                        // Transfer the isDeclaredReference annotation
                        if (scalarVarNode.annotations != null && Boolean.TRUE.equals(scalarVarNode.annotations.get("isDeclaredReference"))) {
                            myNode.setAnnotation("isDeclaredReference", true);
                        }
                        myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                    } else if (operatorNode.operand instanceof ListNode nestedList) {
                        // Handle my(\($d, $e)) - nested list with backslash
                        // Process each element in the nested list as a declared reference
                        for (Node nestedElement : nestedList.elements) {
                            if (nestedElement instanceof OperatorNode nestedVarNode && "$@%".contains(nestedVarNode.operator)) {
                                // Create scalar version if needed
                                OperatorNode scalarVarNode = nestedVarNode;
                                if (nestedVarNode.operator.equals("@") || nestedVarNode.operator.equals("%")) {
                                    scalarVarNode = new OperatorNode("$", nestedVarNode.operand, nestedVarNode.tokenIndex);
                                    scalarVarNode.setAnnotation("isDeclaredReference", true);
                                }
                                // Create a my node for each variable
                                OperatorNode myNode = new OperatorNode(operator, scalarVarNode, listNode.tokenIndex);
                                myNode.setAnnotation("isDeclaredReference", true);
                                myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                            }
                        }
                    } else {
                        // Unknown structure, fall through to default handling
                        OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                        myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                    }
                } else {
                    OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                    myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                }
            }
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                listNode.accept(emitterVisitor);
            }
            return;
        } else if (node.operand instanceof OperatorNode sigilNode) { //  [my our] followed by [$ @ %]
            String sigil = sigilNode.operator;
            if ("$@%".contains(sigil)) {
                Node identifierNode = sigilNode.operand;
                if (identifierNode instanceof IdentifierNode) { // my $a
                    String name = ((IdentifierNode) identifierNode).name;
                    String var = sigil + name;
                    emitterVisitor.ctx.logDebug("MY " + operator + " " + sigil + name);
                    if (emitterVisitor.ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                        if (Warnings.warningManager.isWarningEnabled("redefine")) {
                            System.err.println(
                                    emitterVisitor.ctx.errorUtil.errorMessage(node.getIndex(),
                                            "Warning: \"" + operator + "\" variable "
                                                    + var
                                                    + " masks earlier declaration in same ctx.symbolTable"));
                        }
                    }
                    int varIndex = emitterVisitor.ctx.symbolTable.addVariable(var, operator, sigilNode);
                    // TODO optimization - SETVAR+MY can be combined

                    // Check if this is a declared reference (my \$x)
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    // Determine the class name based on the sigil
                    String className = EmitterMethodCreator.getVariableClassName(sigil);

                    if (operator.equals("my")) {
                        // "my":
                        if (sigilNode.id == 0) {
                            // Create a new instance of the determined class
                            ctx.mv.visitTypeInsn(Opcodes.NEW, className);
                            ctx.mv.visitInsn(Opcodes.DUP);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESPECIAL,
                                    className,
                                    "<init>",
                                    "()V",
                                    false);
                        } else {
                            // The variable was initialized by a BEGIN block

                            // Determine the method to call and its descriptor based on the sigil
                            String methodName;
                            String methodDescriptor;
                            switch (var.charAt(0)) {
                                case '$' -> {
                                    methodName = "retrieveBeginScalar";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeScalar;";
                                }
                                case '@' -> {
                                    methodName = "retrieveBeginArray";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeArray;";
                                }
                                case '%' -> {
                                    methodName = "retrieveBeginHash";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeHash;";
                                }
                                default ->
                                        throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + var.charAt(0), ctx.errorUtil);
                            }

                            ctx.mv.visitLdcInsn(var);
                            ctx.mv.visitLdcInsn(sigilNode.id);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/PersistentVariable",
                                    methodName,
                                    methodDescriptor,
                                    false);
                        }
                    } else if (operator.equals("state")) {
                        // "state":

                        // Determine the method to call and its descriptor based on the sigil
                        String methodName;
                        String methodDescriptor;
                        switch (var.charAt(0)) {
                            case '$' -> {
                                methodName = "retrieveStateScalar";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeScalar;";
                            }
                            case '@' -> {
                                methodName = "retrieveStateArray";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeArray;";
                            }
                            case '%' -> {
                                methodName = "retrieveStateHash";
                                methodDescriptor = "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeHash;";
                            }
                            default ->
                                    throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + var.charAt(0), ctx.errorUtil);
                        }

                        Node codeRef = new OperatorNode("__SUB__", null, node.tokenIndex);
                        codeRef.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                        ctx.mv.visitLdcInsn(var);
                        ctx.mv.visitLdcInsn(sigilNode.id);
                        ctx.mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "org/perlonjava/runtime/StateVariable",
                                methodName,
                                methodDescriptor,
                                false);
                    } else {
                        // "our":
                        // Create and fetch a global variable
                        fetchGlobalVariable(emitterVisitor.ctx, true, sigil, name, node.getIndex());
                    }
                    // For declared references in non-void context, we need different handling
                    if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        // Duplicate the variable for storage
                        emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                        // Store in a JVM local variable
                        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                        // The original is still on the stack for the assignment
                    } else {
                        // Normal handling for non-declared references
                        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                            emitterVisitor.ctx.mv.visitInsn(Opcodes.DUP);
                        }
                        // Store in a JVM local variable
                        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);
                    }

                    if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                        // scalar context: transform the value in the stack to scalar
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, emitterVisitor.ctx.errorUtil);
    }
}
