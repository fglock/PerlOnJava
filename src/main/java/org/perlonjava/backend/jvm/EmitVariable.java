package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.LValueVisitor;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.frontend.semantic.SymbolTable;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.perlmodule.Strict.HINT_STRICT_REFS;
import static org.perlonjava.runtime.perlmodule.Strict.HINT_STRICT_VARS;

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

    private static boolean isBuiltinSpecialLengthOneVar(String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.length() != 1) {
            return false;
        }
        char c = name.charAt(0);
        // In Perl, many single-character non-identifier variables (punctuation/digits)
        // are built-in special vars and are exempt from strict 'vars'.
        return !Character.isLetter(c);
    }

    private static boolean isBuiltinSpecialScalarVar(String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.isEmpty()) {
            return false;
        }
        // ${^FOO} variables are encoded as a leading ASCII control character.
        // (e.g. ${^GLOBAL_PHASE} -> "\aLOBAL_PHASE"). These are built-in and strict-safe.
        if (name.charAt(0) < 32) {
            return true;
        }
        return name.equals("ARGV")
                || name.equals("ARGVOUT")
                || name.equals("ENV")
                || name.equals("INC")
                || name.equals("SIG")
                || name.equals("STDIN")
                || name.equals("STDOUT")
                || name.equals("STDERR");
    }

    private static boolean isNonAsciiLengthOneScalarAllowedUnderNoUtf8(EmitterContext ctx, String sigil, String name) {
        if (!"$".equals(sigil) || name == null || name.length() != 1) {
            return false;
        }
        char c = name.charAt(0);
        // Allow if character is in Latin-1 extended range (128-255) and 'use utf8' is NOT enabled
        // Unicode characters above 255 (like Greek α = 945) should NOT be exempt
        return c > 127 && c <= 255 && !ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_UTF8);
    }

    private static boolean isBuiltinSpecialContainerVar(String sigil, String name) {
        if (name == null) {
            return false;
        }
        if ("%".equals(sigil)) {
            return name.equals("SIG")
                    || name.equals("ENV")
                    || name.equals("INC")
                    || name.equals("+")
                    || name.equals("-")
                    || name.equals("_");
        }
        if ("@".equals(sigil)) {
            return name.equals("ARGV")
                    || name.equals("INC")
                    || name.equals("+")
                    || name.equals("-");
        }
        return false;
    }

    /**
     * Emits bytecode to handle RUNTIME context conversion for arrays and hashes.
     *
     * <p>When an array or hash is used in RUNTIME context (e.g., {@code return @a}),
     * we need to check wantarray at runtime:
     * <ul>
     *   <li>If scalar context (wantarray == 1), return the count (array.scalar())</li>
     *   <li>Otherwise, return the array/hash as-is for list context</li>
     * </ul>
     *
     * <p>This is needed because in Perl:
     * <ul>
     *   <li>{@code return @a} in scalar context returns the count of elements</li>
     *   <li>{@code return @a} in list context returns the elements</li>
     *   <li>{@code return (1,2,3)} in scalar context returns the last element (3)</li>
     * </ul>
     *
     * @param emitterVisitor The visitor handling the bytecode emission
     * @param sigil          The variable sigil (@ or %)
     */
    private static void emitRuntimeContextConversion(EmitterVisitor emitterVisitor, String sigil) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Stack has the array/hash. We need to check wantarray (slot 2) and decide:
        // - If wantarray == SCALAR (1), call .scalar() and return the count
        // - Otherwise, leave the array/hash as-is
        //
        // IMPORTANT: We must store the result to a register and reload it so that
        // both branches converge with the same type on the stack (RuntimeBase).
        // Direct stack manipulation causes VerifyError because the branches have
        // different types (RuntimeScalar vs RuntimeArray/Hash).

        Label notScalarContext = new Label();
        Label done = new Label();

        // Store the array/hash temporarily
        int inputSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, inputSlot);

        // Allocate result slot - will hold RuntimeBase (common supertype)
        int resultSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();

        // Load wantarray (slot 2 = callContext parameter)
        mv.visitVarInsn(Opcodes.ILOAD, 2);

        // Check if wantarray == RuntimeContextType.SCALAR (1)
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.IF_ICMPNE, notScalarContext);

        // Scalar context: load array/hash and call .scalar(), store to result
        mv.visitVarInsn(Opcodes.ALOAD, inputSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "scalar",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);
        mv.visitJumpInsn(Opcodes.GOTO, done);

        // List/void context: store input as-is to result
        mv.visitLabel(notScalarContext);
        mv.visitVarInsn(Opcodes.ALOAD, inputSlot);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);

        // Both branches converge here - load result from register
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, resultSlot);
    }

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
     * @param ctx               the emitter context containing the method visitor and symbol table
     * @param createIfNotExists if true, allows variable creation; if false, enforces strict checking
     * @param sigil             the variable sigil ($, @, %)
     * @param varName           the variable name (without sigil, may include package qualifier)
     * @param tokenIndex        the token index for error reporting
     * @throws PerlCompilerException if strict vars is enabled and the variable is not allowed
     */
    private static void fetchGlobalVariable(EmitterContext ctx, boolean createIfNotExists, String sigil, String varName, int tokenIndex) {

        String var = NameNormalizer.normalizeVariableName(varName, ctx.symbolTable.getCurrentPackage());
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("GETVAR lookup global " + sigil + varName + " normalized to " + var + " createIfNotExists:" + createIfNotExists);

        // Perl creates package symbols at compile time when they are referenced.
        // Our emitter runs before the program executes, so we pre-vivify globals here
        // when creation is allowed. This makes stash enumeration (keys %pkg::) match Perl.
        if (createIfNotExists) {
            if (sigil.equals("$")) {
                GlobalVariable.getGlobalVariable(var);
            } else if (sigil.equals("@")) {
                GlobalVariable.getGlobalArray(var);
            } else if (sigil.equals("%") && !var.endsWith("::")) {
                GlobalVariable.getGlobalHash(var);
            }
        }

        if (sigil.equals("$") && createIfNotExists) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                    "getGlobalVariable",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
            return;
        }

        if (sigil.equals("@") && createIfNotExists) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                    "getGlobalArray",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
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
                    "org/perlonjava/runtime/runtimetypes/HashSpecialVariable",
                    "getStash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;",
                    false);
            return;
        }

        if (sigil.equals("%") && createIfNotExists) {
            // fetch a global variable
            ctx.mv.visitLdcInsn(var);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                    "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;",
                    false);
            return;
        }

        // Variable not found and not allowed under strict
        throw PerlCompilerException.withSourceLocation(
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
     * @param node           the OperatorNode representing the variable operation
     */
    static void handleVariableOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        String sigil = node.operator;

        // In void context, don't emit any code — EXCEPT for glob (*) access,
        // which has vivification side effects. In Perl, `*{"PKG::name"}` in void
        // context still vivifies the glob entry in the stash. Package::Stash::PP
        // relies on this for its `local *__ANON__:: = $namespace; *{"__ANON__::$name"};` pattern.
        if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID
                && !sigil.equals("*")
                && !(sigil.equals("%") && operandIsErrnoHash(node))
                && node.operand instanceof IdentifierNode) {
            return;
        }
        MethodVisitor mv = emitterVisitor.ctx.mv;

        // Case 1: Simple variable with identifier (most common case)
        // Examples: $var, @array, %hash, *glob, &sub
        if (node.operand instanceof IdentifierNode identifierNode) { // $a @a %a
            String name = identifierNode.name;
            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR " + sigil + name);

            if (sigil.equals("*")) {
                // typeglob - return a detached copy to preserve IO during local scope
                // This is crucial for the `do { local *FH; *FH }` pattern
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                        "getGlobalIO",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;",
                        false);
                // Create detached copy NOW (before local restores) to capture current IO
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeGlob",
                        "createDetachedCopy",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;",
                        false);
                // In void context, pop the result — the access was needed for vivification side effects
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            }

            if (sigil.equals("&")) {
                // Code
                String fullName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(fullName); // emit string
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                        "getGlobalCodeRef",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                return;
            }

            // ===== SYMBOL TABLE LOOKUP =====
            // Check if this variable is declared in the current lexical scope
            SymbolTable.SymbolEntry symbolEntry = emitterVisitor.ctx.symbolTable.getSymbolEntry(sigil + name);

            // Note: @_ is lexical in PerlOnJava (unlike standard Perl where it's package-scoped)
            boolean isDeclared = symbolEntry != null;

            // A variable is lexical if it was declared with my/state.
            // 'our' variables have special handling:
            // - For BEGIN block captures (package starts with "PerlOnJava::_BEGIN_"): treat as lexical
            //   This is needed because BEGIN blocks re-declare outer 'my' variables as 'our' for persistence
            // - For regular 'our' variables: NOT lexical - must look up from GlobalVariable
            //   This ensures 'local $Pkg::Var' changes are visible inside subroutines
            boolean isOurInBeginCapture = isDeclared 
                    && symbolEntry.decl().equals("our")
                    && symbolEntry.perlPackage().startsWith("PerlOnJava::_BEGIN_");
            boolean isLexical = isDeclared && (
                    symbolEntry.decl().equals("my")
                            || symbolEntry.decl().equals("state")
                            || isOurInBeginCapture  // 'our' in BEGIN captures are lexical
                            || symbolEntry.name().equals("@_")  // @_ is always lexical
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

                // Special case: $a and $b are exempt from strict
                // (they're used by sort() without declaration)
                String normalizedName = NameNormalizer.normalizeVariableName(name, emitterVisitor.ctx.symbolTable.getCurrentPackage());
                boolean isSpecialSortVar = sigil.equals("$")
                        && !name.contains("::")
                        && (name.equals("a") || name.equals("b"));

                boolean allowIfAlreadyExists = false;
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_VARS)) {
                    if (sigil.equals("$")) {
                        allowIfAlreadyExists = GlobalVariable.existsGlobalVariable(normalizedName);
                    } else if (sigil.equals("@")) {
                        allowIfAlreadyExists = GlobalVariable.existsGlobalArray(normalizedName)
                                || GlobalVariable.isDeclaredGlobalArray(normalizedName);
                    } else if (sigil.equals("%") && !normalizedName.endsWith("::")) {
                        allowIfAlreadyExists = GlobalVariable.existsGlobalHash(normalizedName)
                                || GlobalVariable.isDeclaredGlobalHash(normalizedName);
                    }

                    // Single-letter scalars ($A-$Z) bypass strict only if explicitly declared
                    // (via use vars or Exporter import), not if merely auto-vivified under 'no strict'.
                    if (sigil.equals("$")
                            && name != null
                            && name.length() == 1
                            && Character.isLetter(name.charAt(0))
                            && !name.contains("::")
                            && !isSpecialSortVar) {
                        if (!GlobalVariable.isDeclaredGlobalVariable(normalizedName)) {
                            allowIfAlreadyExists = false;
                        }
                    }
                }

                // Check if this is an 'our' declaration (not in BEGIN capture) - these create global vars
                boolean isOurDeclaration = isDeclared && symbolEntry.decl().equals("our") && !isOurInBeginCapture;

                // Compute createIfNotExists flag - determines if variable can be auto-vivified
                boolean createIfNotExists = name.contains("::")  // Fully qualified: $Package::var
                        || (ScalarUtils.isInteger(name) && !name.startsWith("0"))  // Regex capture: $1, $2, etc.
                        || isSpecialSortVar                      // Sort variables: $a, $b
                        || isBuiltinSpecialLengthOneVar(sigil, name) // $%, $-, $[, $}, etc.
                        || isBuiltinSpecialScalarVar(sigil, name) // ${^GLOBAL_PHASE}, $ARGV, $ENV, etc.
                        || isBuiltinSpecialContainerVar(sigil, name) // %SIG, %ENV, @ARGV, etc.
                        || isNonAsciiLengthOneScalarAllowedUnderNoUtf8(emitterVisitor.ctx, sigil, name)
                        || allowIfAlreadyExists
                        || !emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_VARS)  // no strict 'vars'
                        || isOurDeclaration                      // 'our' declarations (global variable aliases)
                        || (isDeclared && isLexical);            // Lexically declared (my/state)

                // Fetch the global variable (may throw exception if strict and not allowed)
                fetchGlobalVariable(emitterVisitor.ctx, createIfNotExists, sigil, name, node.getIndex());
            } else {
                // ===== LEXICAL VARIABLE ACCESS =====
                // Variable is lexical (my/state/@_/BEGIN-captured-our), load it from JVM local variable slot
                mv.visitVarInsn(Opcodes.ALOAD, symbolEntry.index());
            }

            // ===== CONTEXT CONVERSION =====
            // In scalar context, convert array/hash to scalar (e.g., array length, hash key count)
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME && !sigil.equals("$")) {
                // In RUNTIME context (e.g., return @a), check wantarray at runtime
                // If scalar context, return the count; otherwise return the array/hash as-is
                emitRuntimeContextConversion(emitterVisitor, sigil);
            }

            if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR end " + symbolEntry);
            return;
        }
        switch (sigil) {
            case "@":
                // `@$a`
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `@$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "arrayDeref", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                } else {
                    // no strict refs - allow symbolic references
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "arrayDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeArray", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                    emitRuntimeContextConversion(emitterVisitor, sigil);
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            case "%":
                // `%$a`
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `%$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDeref", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                } else {
                    // no strict refs - allow symbolic references
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeHash", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                    emitRuntimeContextConversion(emitterVisitor, sigil);
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            case "$":
                // `$$a`
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `$$a`");
                if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "scalarDeref", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "scalarDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            case "*":
                // `*$a`
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `*$a`");
                boolean isPostfixDeref = Boolean.TRUE.equals(node.getAnnotation("postfixDeref"));
                boolean postfixLiteralSymbol = isPostfixDeref
                        && (node.operand instanceof StringNode || node.operand instanceof IdentifierNode);

                if (postfixLiteralSymbol) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                            "globDerefPostfix",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;",
                            false);
                } else if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "globDeref", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;", false);
                } else {
                    // no strict refs
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                    emitterVisitor.pushCurrentPackage();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "globDerefNonStrict", "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;", false);
                }
                // In void context, pop the result off the JVM stack since no one consumes it.
                // The glob access was still needed for its vivification side effect.
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    mv.visitInsn(Opcodes.POP);
                }
                return;
            case "&":
                // `&$a` or `&{sub ...}`
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `&$a` or `&{sub ...}`");

                // Special handling for &{sub ...} - BlockNode containing SubroutineNode
                if (node.operand instanceof BlockNode blockNode &&
                        blockNode.elements.size() == 1 &&
                        blockNode.elements.get(0) instanceof SubroutineNode) {

                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("GETVAR `&{sub ...}` - emitting subroutine as RuntimeScalar");
                    // Emit the subroutine directly as a RuntimeScalar (code reference)
                    blockNode.elements.get(0).accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                } else {
                    // Regular case: `&$a`
                    node.operand.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                    // Check if the variable is a lexical subroutine (already a CODE reference)
                    // Lexical subs have a "hiddenVarName" annotation and should not be dereferenced
                    boolean isLexicalSub = false;
                    if (node.operand instanceof OperatorNode opNode && opNode.operator.equals("$")) {
                        String hiddenVarName = (String) opNode.getAnnotation("hiddenVarName");
                        isLexicalSub = (hiddenVarName != null);
                    }

                    // Dereference the scalar to get the CODE reference
                    if (!isLexicalSub) {
                        if (emitterVisitor.ctx.isLvalueSubroutine
                                && emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(HINT_STRICT_REFS)) {
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                    "codeDerefStrict",
                                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                    false);
                        } else {
                            emitterVisitor.pushCurrentPackage();
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                    "codeDerefNonStrict",
                                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                    false);
                        }
                    }
                }

                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("EmitVariable: about to call RuntimeCode.apply for &$var");

                mv.visitVarInsn(Opcodes.ALOAD, 1);  // push @_ to stack
                emitterVisitor.pushCallContext();   // push call context to stack
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                        "apply",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                        false); // generate an .apply() call

                // RuntimeCode.apply() can return a tagged RuntimeControlFlowList (last/next/redo).
                // Handle it before context conversion (especially before POP in VOID context).
                Label applyNoControlFlow = new Label();
                Label applyNotNextLastRedo = new Label();
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeList",
                        "isNonLocalGoto",
                        "()Z",
                        false);
                mv.visitJumpInsn(Opcodes.IFEQ, applyNoControlFlow);

                int cfSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                mv.visitTypeInsn(Opcodes.CHECKCAST, "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList");
                mv.visitVarInsn(Opcodes.ASTORE, cfSlot);

                int labelSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeControlFlowList",
                        "getControlFlowLabel",
                        "()Ljava/lang/String;",
                        false);
                mv.visitVarInsn(Opcodes.ASTORE, labelSlot);

                int typeSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
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
                mv.visitVarInsn(Opcodes.ISTORE, typeSlot);

                // Only handle NEXT/LAST/REDO here (ordinals 0..2). Others propagate as values.
                mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitJumpInsn(Opcodes.IF_ICMPGT, applyNotNextLastRedo);

                Label checkUnlabeled = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, labelSlot);
                mv.visitJumpInsn(Opcodes.IFNULL, checkUnlabeled);

                for (LoopLabels loopLabels : emitterVisitor.ctx.javaClassInfo.loopLabelStack) {
                    if (loopLabels != null && loopLabels.labelName != null) {
                        Label nextLabel = new Label();
                        mv.visitVarInsn(Opcodes.ALOAD, labelSlot);
                        mv.visitLdcInsn(loopLabels.labelName);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "java/lang/String",
                                "equals",
                                "(Ljava/lang/Object;)Z",
                                false);
                        mv.visitJumpInsn(Opcodes.IFEQ, nextLabel);

                        Label isLast = new Label();
                        Label isNext = new Label();
                        Label isRedo = new Label();
                        mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isLast);
                        mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                        mv.visitInsn(Opcodes.ICONST_1);
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isNext);
                        mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                        mv.visitInsn(Opcodes.ICONST_2);
                        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isRedo);
                        mv.visitJumpInsn(Opcodes.GOTO, nextLabel);

                        mv.visitLabel(isLast);
                        mv.visitJumpInsn(Opcodes.GOTO, loopLabels.lastLabel);

                        mv.visitLabel(isNext);
                        mv.visitJumpInsn(Opcodes.GOTO, loopLabels.nextLabel);

                        mv.visitLabel(isRedo);
                        mv.visitJumpInsn(Opcodes.GOTO, loopLabels.redoLabel);

                        mv.visitLabel(nextLabel);
                    }
                }

                // No labeled target matched: propagate via returnLabel if available.
                if (emitterVisitor.ctx.javaClassInfo.returnLabel != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
                    mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.returnValueSlot);
                    mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
                }

                mv.visitLabel(checkUnlabeled);
                LoopLabels unlabeledTarget = emitterVisitor.ctx.javaClassInfo.findInnermostTrueLoopLabels();
                if (unlabeledTarget != null) {
                    Label isLast = new Label();
                    Label isNext = new Label();
                    Label isRedo = new Label();
                    mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isLast);
                    mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isNext);
                    mv.visitVarInsn(Opcodes.ILOAD, typeSlot);
                    mv.visitInsn(Opcodes.ICONST_2);
                    mv.visitJumpInsn(Opcodes.IF_ICMPEQ, isRedo);
                    mv.visitJumpInsn(Opcodes.GOTO, applyNotNextLastRedo);

                    // Push a value before GOTO to match expected stack state at merge point.
                    // The applyNoControlFlow merge point expects a value on the stack.
                    mv.visitLabel(isLast);
                    mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
                    mv.visitJumpInsn(Opcodes.GOTO, applyNoControlFlow);

                    mv.visitLabel(isNext);
                    mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
                    mv.visitJumpInsn(Opcodes.GOTO, applyNoControlFlow);

                    mv.visitLabel(isRedo);
                    mv.visitJumpInsn(Opcodes.GOTO, unlabeledTarget.redoLabel);
                } else if (emitterVisitor.ctx.javaClassInfo.returnLabel != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, cfSlot);
                    mv.visitVarInsn(Opcodes.ASTORE, emitterVisitor.ctx.javaClassInfo.returnValueSlot);
                    mv.visitJumpInsn(Opcodes.GOTO, emitterVisitor.ctx.javaClassInfo.returnLabel);
                }

                mv.visitLabel(applyNotNextLastRedo);
                mv.visitVarInsn(Opcodes.ALOAD, cfSlot);

                mv.visitLabel(applyNoControlFlow);

                // Handle context conversion: RuntimeCode.apply() always returns RuntimeList
                // but we need to convert based on the calling context
                if (emitterVisitor.ctx.contextType == RuntimeContextType.VOID) {
                    // VOID context: consume the stack
                    mv.visitInsn(Opcodes.POP);
                } else if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                    // A call result consumed as a scalar can return its private
                    // one-scalar wrapper to the runtime-local pool. Ordinary
                    // lists and markers retain scalar() behavior.
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalarAndRecycle",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }
                // LIST context: RuntimeList is already correct, no conversion needed

                return;
        }

        // TODO ${a} ${[ 123 ]}
        throw new PerlCompilerException(node.tokenIndex, "Not implemented: " + sigil, emitterVisitor.ctx.errorUtil);
    }

    private static boolean operandIsErrnoHash(OperatorNode node) {
        return node.operand instanceof IdentifierNode identifier
                && identifier.name.equals("!");
    }

    static void handleAssignOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        if (node.left instanceof OperatorNode leftOperator
                && leftOperator.operator.equals("substr")
                && leftOperator.operand instanceof ListNode arguments
                && arguments.elements.size() > 3) {
            throw new PerlCompilerException(node.tokenIndex,
                    "Can't modify substr in scalar assignment", ctx.errorUtil);
        }

        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("SET " + node);
        MethodVisitor mv = ctx.mv;
        // Determine the assign type based on the left side.
        // Inspect the AST and get the L-value context: SCALAR or LIST
        int lvalueContext = LValueVisitor.getContext(node);
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("SET Lvalue context: " + lvalueContext);
        // Execute the right side first: assignment is right-associative

        Node left = node.left;
        Node right = node.right;

        boolean isLocalAssignment = left instanceof OperatorNode operatorNode && operatorNode.operator.equals("local");

        boolean localCaptureAssignment = isLocalAssignment && left instanceof OperatorNode local
                && local.operand instanceof OperatorNode sigil
                && sigil.operator.equals("$")
                && sigil.operand instanceof IdentifierNode id
                && id.name.matches("[1-9]\\d*");
        if (localCaptureAssignment) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalRuntimeScalar",
                    "rejectReadonlyCaptureAssignment", "()V", false);
        }

        // `\\local @array[indices] = REFERENCES` is reference aliasing, not
        // an ordinary slice assignment.  Evaluate the RHS in list context,
        // localize every slot as one dynamic operation, then replace each
        // localized slot through its lvalue proxy.  The scalar assignment path
        // below would otherwise collapse `($ref) x N` to its repeat count.
        if (isArraySliceReferenceAlias(left)) {
            emitArraySliceReferenceAlias(emitterVisitor, node);
            EmitOperator.handleVoidContext(emitterVisitor);
            return;
        }

        // A parenthesized reference-alias target is structurally a list
        // assignment, but it must replace its target cells with the RHS
        // referents rather than use the ordinary setFromList copy path.
        // Handle it before lvalue-context dispatch so both the single
        // reference form and a multi-member parenthesized form reach the
        // same aliasing emitter.
        if (isReferenceAliasListAssignment(left)) {
            emitReferenceAliasListAssignment(emitterVisitor, node);
            EmitOperator.handleVoidContext(emitterVisitor);
            return;
        }

        switch (lvalueContext) {
            case RuntimeContextType.SCALAR:
                if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("SET right side scalar");

                if (node.left instanceof OperatorNode operatorNode && operatorNode.operator.equals("state")) {
                    emitStateInitialization(emitterVisitor, node, operatorNode, ctx);
                    break;
                }

                // Special case: ternary LHS with LIST assignment branches.
                // Perl 5 parses e.g. `($x ? @$a = () : $b) = []` where the true branch
                // is itself an assignment. For LIST assignments (like @$a = ()), the result
                // in scalar context is a cached read-only count (RuntimeScalarReadOnly).
                // The outer assignment targets this read-only temp (effectively a no-op).
                // Scalar assignments (like $c = 100) return the variable itself (writable),
                // so they work fine with the normal code path.
                if (node.left instanceof TernaryOperatorNode ternary) {
                    boolean trueIsListAssign = isListAssignBranch(ternary.trueExpr);
                    boolean falseIsListAssign = isListAssignBranch(ternary.falseExpr);
                    if (trueIsListAssign || falseIsListAssign) {
                        emitTernaryWithAssignBranches(emitterVisitor, node, ternary, trueIsListAssign, falseIsListAssign);
                        break;
                    }
                }

                // The left value can be a variable, an operator or a subroutine call:
                //   `pos`, `substr`, `vec`, `sub :lvalue`

                boolean directArrayToGlob = node.left instanceof OperatorNode globTarget
                        && (globTarget.operator.equals("*")
                        || globTarget.operator.equals("local")
                        && globTarget.operand instanceof OperatorNode localizedGlob
                        && localizedGlob.operator.equals("*"))
                        && node.right instanceof OperatorNode arrayRhs
                        && arrayRhs.operator.equals("@");
                node.right.accept(emitterVisitor.with(directArrayToGlob
                        ? RuntimeContextType.LIST : RuntimeContextType.SCALAR));
                if (directArrayToGlob) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/RuntimeGlob",
                            "assignmentScalar",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                            false);
                }

                boolean spillRhs = true;
                int rhsSlot = -1;
                boolean pooledRhs = false;

                if (isLocalAssignment) {
                    // Clone the scalar before calling local()
                    if (right instanceof OperatorNode operatorNode && operatorNode.operator.equals("*")) {
                        // TODO - glob clone
                    } else {
                        mv.visitMethodInsn(
                                Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "clone",
                                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                false
                        );
                    }
                }

                if (spillRhs) {
                    rhsSlot = ctx.javaClassInfo.acquireSpillSlot();
                    pooledRhs = rhsSlot >= 0;
                    if (!pooledRhs) {
                        rhsSlot = ctx.symbolTable.allocateLocalVariable();
                    }
                    mv.visitVarInsn(Opcodes.ASTORE, rhsSlot);
                }

                OperatorNode nodeLeft = null;
                if (node.left instanceof OperatorNode operatorNode) {
                    nodeLeft = operatorNode;
                    if (nodeLeft.operator.equals("local") && nodeLeft.operand instanceof OperatorNode localNode) {
                        nodeLeft = localNode;  // local *var = ...
                    }

                    if (nodeLeft.operator.equals("keys")) {
                        // `keys %x = $number`  - preallocate hash capacity
                        // Emit the hash operand directly instead of calling keys.
                        if (nodeLeft.operand != null) {
                            nodeLeft.operand.accept(emitterVisitor.with(RuntimeContextType.LIST));
                        }
                        // Stack: [hash]
                        mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                        // Stack: [hash, value]
                        mv.visitInsn(Opcodes.DUP2);  // Stack: [hash, value, hash, value]
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "getInt", "()I", false); // Stack: [hash, value, hash, int]
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeHash",
                                "preallocateCapacity", "(I)V", false); // Stack: [hash, value]
                        mv.visitInsn(Opcodes.SWAP); // Stack: [value, hash]
                        mv.visitInsn(Opcodes.POP);  // Stack: [value]

                        if (ctx.contextType == RuntimeContextType.VOID) {
                            mv.visitInsn(Opcodes.POP);
                        }

                        if (pooledRhs) {
                            ctx.javaClassInfo.releaseSpillSlot();
                        }
                        return;  // Skip normal assignment processing
                    }
                }

                // A conditional can itself be the ref-alias target:
                //   $condition ? \$left : $right = \$source
                // The parser attaches the assignment outside the ternary, so
                // this cannot be handled by the ordinary `\\target` branch
                // below.  Select and lower the branch as a reference-alias
                // target, after the RHS reference has been evaluated once.
                if (node.left instanceof TernaryOperatorNode ternaryTarget
                        && node.right instanceof OperatorNode reference
                        && reference.operator.equals("\\")) {
                    if (!ctx.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
                        throw new PerlCompilerException(node.tokenIndex, "Experimental aliasing via reference not enabled", ctx.errorUtil);
                    }
                    if (ctx.symbolTable.isWarningCategoryEnabled("experimental::refaliasing")) {
                        WarnDie.warn(
                                new RuntimeScalar("Aliasing via reference is experimental"),
                                new RuntimeScalar(ctx.errorUtil.warningLocation(node.tokenIndex)));
                    }
                    emitReferenceAliasTarget(emitterVisitor, ternaryTarget, rhsSlot, false);
                    if (ctx.contextType == RuntimeContextType.VOID) mv.visitInsn(Opcodes.POP);
                    if (pooledRhs) ctx.javaClassInfo.releaseSpillSlot();
                    return;
                }

                // Check for ref aliasing (\$y = $ref) BEFORE emitting LHS
                if (nodeLeft != null && nodeLeft.operator.equals("\\")) {
                    // `\$b = \$a` requires "refaliasing"
                    if (!ctx.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
                        throw new PerlCompilerException(node.tokenIndex, "Experimental aliasing via reference not enabled", ctx.errorUtil);
                    }
                    // Emit experimental warning if warnings are enabled
                    if (ctx.symbolTable.isWarningCategoryEnabled("experimental::refaliasing")) {
                        try {
                            WarnDie.warn(
                                    new RuntimeScalar("Aliasing via reference is experimental"),
                                    new RuntimeScalar(ctx.errorUtil.warningLocation(node.tokenIndex))
                            );
                        } catch (Exception e) {
                            // If warning system isn't initialized yet, fall back to System.err
                            System.err.println("Aliasing via reference is experimental" + ctx.errorUtil.warningLocation(node.tokenIndex) + ".");
                        }
                    }

                    // Hash and array elements are loose scalar lvalues: replace their
                    // slot with the RHS referent rather than copying the SV.
                    Node refAliasTarget = nodeLeft.operand;
                    while (refAliasTarget instanceof ListNode listNode
                            && listNode.elements.size() == 1) {
                        refAliasTarget = listNode.elements.get(0);
                    }
                    if (refAliasTarget instanceof TernaryOperatorNode ternaryTarget) {
                        emitReferenceAliasTarget(emitterVisitor, ternaryTarget, rhsSlot);
                        if (ctx.contextType == RuntimeContextType.VOID) mv.visitInsn(Opcodes.POP);
                        if (pooledRhs) ctx.javaClassInfo.releaseSpillSlot();
                        return;
                    }
                    BinaryOperatorNode element = refAliasTarget instanceof BinaryOperatorNode binaryElement
                            ? binaryElement : null;
                    if (element != null && (element.operator.equals("{") || element.operator.equals("["))) {
                        if (element.operator.equals("[")) {
                            Dereference.handleArrayElementOperator(
                                    emitterVisitor.with(RuntimeContextType.LVALUE), element, "getLvalue");
                        } else {
                            element.accept(emitterVisitor.with(RuntimeContextType.LVALUE));
                        }
                        mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "aliasLvalueReference",
                                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                false);
                        if (ctx.contextType == RuntimeContextType.VOID) mv.visitInsn(Opcodes.POP);
                        if (pooledRhs) ctx.javaClassInfo.releaseSpillSlot();
                        return;
                    }

                    // Handle ref aliasing: \$y = $ref, \@y = $ref, \%y = $ref.
                    // The target can also be a declaration nested in a
                    // parenthesized reference: \(my $y) = $ref.
                    OperatorNode varNode = refAliasTarget instanceof OperatorNode directVarNode
                            && (directVarNode.operator.equals("$")
                            || directVarNode.operator.equals("@")
                            || directVarNode.operator.equals("%"))
                            ? directVarNode : null;
                    if (varNode == null
                            && refAliasTarget instanceof OperatorNode declaration
                            && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                            && declaration.operand instanceof OperatorNode declaredVarNode
                            && (declaredVarNode.operator.equals("$")
                            || declaredVarNode.operator.equals("@")
                            || declaredVarNode.operator.equals("%"))) {
                        // Ref aliasing bypasses the normal LHS visit, which is
                        // normally responsible for allocating a lexical cell.
                        // Materialize it before looking up its JVM local slot.
                        declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                        varNode = declaredVarNode;
                    }
                    if (varNode != null) {
                        String varName;
                        if (varNode.operand instanceof IdentifierNode idNode) {
                            varName = varNode.operator + idNode.name;
                        } else {
                            throw new PerlCompilerException(node.tokenIndex, "Assignment to unsupported ref aliasing target", ctx.errorUtil);
                        }
                        SymbolTable.SymbolEntry symEntry = ctx.symbolTable.getSymbolEntry(varName);

                        if (symEntry != null && (symEntry.decl().equals("my") || symEntry.decl().equals("state"))) {
                            // Load RHS (the reference) onto stack
                            if (spillRhs) {
                                mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                            }
                            // else RHS is already on top of stack

                            switch (varNode.operator) {
                                case "$" -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                        "refAliasScalarReference",
                                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                        false);
                                case "@" -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                        "foreachArrayReference",
                                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;",
                                        false);
                                case "%" -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                        "foreachHashReference",
                                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;",
                                        false);
                            }

                            if (symEntry.decl().equals("state")) {
                                // The local is a view of persistent state.
                                // Replace the cell's value, not that view.
                                mv.visitVarInsn(Opcodes.ALOAD, symEntry.index());
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set",
                                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                            } else {
                                mv.visitInsn(Opcodes.DUP);
                                mv.visitVarInsn(Opcodes.ASTORE, symEntry.index());
                            }

                            if (pooledRhs) {
                                ctx.javaClassInfo.releaseSpillSlot();
                            }
                            break;
                        } else if ((symEntry == null || symEntry.decl().equals("our"))
                                && (varNode.operator.equals("$")
                                || varNode.operator.equals("@")
                                || varNode.operator.equals("%"))) {
                            String globalName = NameNormalizer.normalizeVariableName(
                                    varName.substring(1), ctx.symbolTable.getCurrentPackage());
                            mv.visitLdcInsn(globalName);
                            mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                            String dereferenceMethod;
                            String dereferenceDescriptor;
                            String aliasMethod;
                            String aliasDescriptor;
                            String loadMethod;
                            String loadDescriptor;
                            switch (varNode.operator) {
                                case "$" -> {
                                    dereferenceMethod = "refAliasScalarReference";
                                    dereferenceDescriptor = "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                                    aliasMethod = "aliasGlobalVariable";
                                    aliasDescriptor = "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V";
                                    loadMethod = "getGlobalVariable";
                                    loadDescriptor = "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                                }
                                case "@" -> {
                                    dereferenceMethod = "foreachArrayReference";
                                    dereferenceDescriptor = "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;";
                                    aliasMethod = "aliasGlobalArray";
                                    aliasDescriptor = "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)V";
                                    loadMethod = "getGlobalArray";
                                    loadDescriptor = "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;";
                                }
                                case "%" -> {
                                    dereferenceMethod = "foreachHashReference";
                                    dereferenceDescriptor = "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;";
                                    aliasMethod = "aliasGlobalHash";
                                    aliasDescriptor = "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;)V";
                                    loadMethod = "getGlobalHash";
                                    loadDescriptor = "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;";
                                }
                                default -> throw new IllegalStateException(
                                        "Unexpected ref aliasing target: " + varNode.operator);
                            }
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar", dereferenceMethod,
                                    dereferenceDescriptor, false);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/runtimetypes/GlobalVariable", aliasMethod,
                                    aliasDescriptor, false);
                            mv.visitLdcInsn(globalName);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/runtimetypes/GlobalVariable", loadMethod,
                                    loadDescriptor, false);
                            if (pooledRhs) ctx.javaClassInfo.releaseSpillSlot();
                            break;
                        }
                    }
                    // Fall through for unsupported ref aliasing targets (global vars, etc.)
                }

                int lhsContext = isScalarLvalueTarget(node.left)
                        ? RuntimeContextType.LVALUE
                        : RuntimeContextType.SCALAR;
                node.left.accept(emitterVisitor.with(lhsContext));   // emit the variable

                if (spillRhs) {
                    mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                    mv.visitInsn(Opcodes.SWAP);
                }

                boolean isGlob = false;
                String leftDescriptor = "org/perlonjava/runtime/runtimetypes/RuntimeScalar";
                if (nodeLeft != null && nodeLeft.operator.equals("*")) {
                    // glob:  *var
                    leftDescriptor = "org/perlonjava/runtime/runtimetypes/RuntimeGlob";
                    isGlob = true;
                }
                String rightDescriptor = "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                if (node.right instanceof OperatorNode && ((OperatorNode) node.right).operator.equals("*")) {
                    rightDescriptor = "(Lorg/perlonjava/runtime/runtimetypes/RuntimeGlob;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                    isGlob = true;
                }
                if (isGlob) {
                    mv.visitInsn(Opcodes.SWAP); // move the target first
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, leftDescriptor, "set", rightDescriptor, false);
                } else {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "assignTo",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }

                if (pooledRhs) {
                    ctx.javaClassInfo.releaseSpillSlot();
                }
                break;
            case RuntimeContextType.LIST:
                if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("SET right side list");

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
                            "org/perlonjava/runtime/runtimetypes/RuntimeList",
                            "clone",
                            "()Lorg/perlonjava/runtime/runtimetypes/RuntimeList;",
                            false
                    );
                }

                // Spill RHS list before evaluating the LHS so LHS evaluation can safely propagate
                // non-local control flow without leaving RHS values on the operand stack.
                int rhsListSlot = ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledRhsList = rhsListSlot >= 0;
                if (!pooledRhsList) {
                    rhsListSlot = ctx.symbolTable.allocateLocalVariable();
                }
                mv.visitVarInsn(Opcodes.ASTORE, rhsListSlot);

                // For declared references, we need special handling.
                // The my operator needs to be processed to create the variables first.
                node.left.accept(emitterVisitor.with(RuntimeContextType.LVALUE_LIST));   // emit the variable (target)
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);                      // reload RHS list
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "setFromList", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);

                if (pooledRhsList) {
                    ctx.javaClassInfo.releaseSpillSlot();
                }
                if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME) {
                    // A final list assignment in a subroutine inherits the
                    // caller's context. RuntimeArray.scalar() uses the RHS
                    // element count recorded by setFromList().
                    emitRuntimeContextConversion(emitterVisitor, "@");
                } else {
                    EmitOperator.handleScalarContext(emitterVisitor, node);
                }
                break;
            default:
                // Check if this is a chop/chomp that can't be an lvalue
                if (node.left instanceof OperatorNode operatorNode) {
                    String op = operatorNode.operator;
                    if (op.equals("chop") || op.equals("chomp") || op.equals("substr")) {
                        throw new PerlCompilerException(node.tokenIndex, "Can't modify " + op + " in scalar assignment", ctx.errorUtil);
                    }
                }
                throw new PerlCompilerException(node.tokenIndex, "Unsupported assignment context: " + lvalueContext, ctx.errorUtil);
        }
        EmitOperator.handleVoidContext(emitterVisitor);
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("SET end");
    }

    /**
     * Checks whether a ternary branch is a LIST assignment expression (e.g. {@code @arr = expr}).
     * LIST assignments in scalar context return a cached read-only element count, which cannot
     * be used as an lvalue target. Scalar assignments return the variable itself (writable).
     */
    private static boolean isListAssignBranch(Node expr) {
        if (expr instanceof BinaryOperatorNode binop && binop.operator.equals("=")) {
            int innerContext = LValueVisitor.getContext(binop);
            return innerContext == RuntimeContextType.LIST;
        }
        return false;
    }

    private static boolean isScalarLvalueTarget(Node node) {
        if (node instanceof OperatorNode operator) {
            // Unary plus is a parse disambiguator around an lvalue, except
            // for the separately handled +() empty-list assignment form.
            if (operator.operator.equals("+")) {
                return isScalarLvalueTarget(operator.operand);
            }
            return operator.operator.equals("substr")
                    || operator.operator.equals("pos")
                    || operator.operator.equals("vec");
        }
        if (!(node instanceof BinaryOperatorNode binop)) {
            return false;
        }
        if (binop.operator.equals("(")) {
            return true;
        }
        if (!binop.operator.equals("->")) {
            return false;
        }
        return binop.right instanceof ListNode
                || (binop.right instanceof BinaryOperatorNode call && call.operator.equals("("));
    }

    private static boolean isReferenceAliasListAssignment(Node left) {
        if (left instanceof ListNode targets) {
            // Parenthesized reference targets retain their individual `\\`
            // wrappers in a ListNode even when there is only one member.
            // That one-member form still consumes the RHS as a list.
            return targets.elements.stream().allMatch(element -> element instanceof OperatorNode operator
                    && operator.operator.equals("\\"));
        }
        if (!(left instanceof OperatorNode referenceOp) || !referenceOp.operator.equals("\\")) {
            return false;
        }
        if (referenceOp.operand instanceof ListNode targets) {
            return !targets.elements.isEmpty();
        }
        return referenceOp.operand instanceof OperatorNode declaration
                && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                && declaration.operand instanceof ListNode targets
                && !targets.elements.isEmpty();
    }

    private static boolean isArraySliceReferenceAlias(Node left) {
        if (!(left instanceof OperatorNode reference) || !reference.operator.equals("\\")) {
            return false;
        }
        Node target = reference.operand;
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.get(0);
        }
        if (target instanceof OperatorNode local && local.operator.equals("local")) target = local.operand;
        if (!(target instanceof BinaryOperatorNode slice) || !slice.operator.equals("[")) return false;
        return slice.left instanceof OperatorNode aggregate && aggregate.operator.equals("@")
                && slice.right instanceof ArrayLiteralNode;
    }

    /** Emit reference aliasing for {@code \\@array[indices]} and its {@code local} form. */
    private static void emitArraySliceReferenceAlias(
            EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;
        if (!ctx.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            throw new PerlCompilerException(node.tokenIndex,
                    "Experimental aliasing via reference not enabled", ctx.errorUtil);
        }

        OperatorNode reference = (OperatorNode) node.left;
        Node target = reference.operand;
        while (target instanceof ListNode list && list.elements.size() == 1) {
            target = list.elements.get(0);
        }
        OperatorNode local = target instanceof OperatorNode operator
                && operator.operator.equals("local") ? operator : null;
        BinaryOperatorNode slice = (BinaryOperatorNode) (local == null ? target : local.operand);
        ArrayLiteralNode indices = (ArrayLiteralNode) slice.right;

        Node right = node.right instanceof ListNode ? node.right
                : new ListNode(List.of(node.right), node.tokenIndex);
        right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        int rhsSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRhs = rhsSlot >= 0;
        if (!pooledRhs) rhsSlot = ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, rhsSlot);

        // Flatten the RHS once, matching list assignment and the ordinary
        // parenthesized reference-alias path.
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeArray", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeArray", "setFromList",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
        mv.visitVarInsn(Opcodes.ASTORE, rhsSlot);

        // Save all slice cells before obtaining the proxies used for aliasing.
        if (local != null) local.accept(emitterVisitor.with(RuntimeContextType.VOID));

        for (int i = 0; i < indices.elements.size(); i++) {
            ArrayLiteralNode oneIndex = new ArrayLiteralNode(List.of(indices.elements.get(i)), slice.right.getIndex());
            BinaryOperatorNode element = new BinaryOperatorNode("[", slice.left, oneIndex, slice.tokenIndex);
            Dereference.handleArrayElementOperator(
                    emitterVisitor.with(RuntimeContextType.LVALUE), element, "getLvalue");
            // `@array[index]` remains a slice even with one index, so the
            // lvalue helper returns a one-element RuntimeList.  Extract its
            // proxy before dispatching RuntimeScalar.aliasLvalueReference.
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeList", "scalar",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
            mv.visitLdcInsn(i);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                    "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "aliasLvalueReference",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            if (i < indices.elements.size() - 1) mv.visitInsn(Opcodes.POP);
        }
        if (pooledRhs) ctx.javaClassInfo.releaseSpillSlot();
    }

    /** Emits \(TARGETS) = \(REFERENTS) without collapsing the RHS list to scalar context. */
    private static void emitReferenceAliasListAssignment(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;
        ListNode targets;
        OperatorNode listDeclaration = null;
        boolean outerReferenceToList = false;
        if (node.left instanceof ListNode list) {
            targets = list;
        } else {
            OperatorNode reference = (OperatorNode) node.left;
            if (reference.operand instanceof ListNode list) {
                targets = list;
                // `\\(@array)` is a reference to a parenthesized target
                // list.  It differs from `(\\@array)`, whose member
                // reference aliases the aggregate itself.
                outerReferenceToList = list.elements.size() == 1;
            } else {
                listDeclaration = (OperatorNode) reference.operand;
                targets = (ListNode) listDeclaration.operand;
            }
        }
        if (!ctx.symbolTable.isFeatureCategoryEnabled("refaliasing")) {
            throw new PerlCompilerException(node.tokenIndex, "Experimental aliasing via reference not enabled", ctx.errorUtil);
        }

        // Keep the RHS's list contract even when the parser represents it as
        // a single aggregate expression such as @refs.  This mirrors the
        // normal LIST-assignment path below.
        Node right = node.right;
        if (!(right instanceof ListNode)) {
            right = new ListNode(List.of(right), node.tokenIndex);
        }
        right.accept(emitterVisitor.with(RuntimeContextType.LIST));
        int rhsListSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRhsList = rhsListSlot >= 0;
        if (!pooledRhsList) rhsListSlot = ctx.symbolTable.allocateLocalVariable();
        mv.visitVarInsn(Opcodes.ASTORE, rhsListSlot);

        // A ListNode can retain an aggregate as one of its members (for
        // example @{[\$b, \$c]}).  Ordinary list assignment flattens that
        // aggregate through RuntimeArray.setFromList before indexing it; do
        // the same here so aliases pair with the individual references.
        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeArray");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeArray", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeArray", "setFromList",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
        mv.visitVarInsn(Opcodes.ASTORE, rhsListSlot);

        for (int i = 0; i < targets.elements.size(); i++) {
            Node target = targets.elements.get(i);
            // \my(@array) and \state(@array) declare a parenthesized
            // aggregate slot list; retain that information after the
            // declaration is lifted around each member below.
            boolean aggregateReferenceListTarget = listDeclaration != null || outerReferenceToList;
            if (listDeclaration != null && target instanceof OperatorNode) {
                target = new OperatorNode(listDeclaration.operator, target, listDeclaration.tokenIndex);
            }
            if (target instanceof OperatorNode memberReference
                    && memberReference.operator.equals("\\")) {
                target = memberReference.operand;
                aggregateReferenceListTarget = target instanceof ListNode;
                while (target instanceof ListNode memberList && memberList.elements.size() == 1) {
                    target = memberList.elements.getFirst();
                }
            }
            if (target instanceof ListNode groupedTarget
                    && groupedTarget.parenthesized
                    && groupedTarget.elements.size() == 1) {
                target = groupedTarget.elements.getFirst();
                aggregateReferenceListTarget = true;
            }
            if (Boolean.TRUE.equals(target.getAnnotation("parenthesizedList"))) {
                aggregateReferenceListTarget = true;
            }
            // \my(@array) and \state(@array) are aggregate slot-list
            // targets.  Preserve that meaning when their one-member ListNode
            // is nested inside the declaration wrapper.
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof ListNode declarationList
                    && declarationList.elements.size() == 1
                    && declarationList.elements.getFirst() instanceof OperatorNode declaredAggregate
                    && declaredAggregate.operator.equals("@")) {
                OperatorNode normalizedDeclaration = new OperatorNode(
                        declaration.operator, declaredAggregate, declaration.tokenIndex);
                normalizedDeclaration.annotations = declaration.annotations;
                target = normalizedDeclaration;
                aggregateReferenceListTarget = true;
            }
            if (target instanceof TernaryOperatorNode ternary
                    && emitConditionalReferenceAliasTarget(emitterVisitor, ternary, rhsListSlot, i)) {
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof TernaryOperatorNode ternary) {
                int referenceSlot = ctx.javaClassInfo.acquireSpillSlot();
                boolean pooledReference = referenceSlot >= 0;
                if (!pooledReference) referenceSlot = ctx.symbolTable.allocateLocalVariable();
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitVarInsn(Opcodes.ASTORE, referenceSlot);
                emitReferenceAliasTarget(emitterVisitor, ternary, referenceSlot);
                if (pooledReference) ctx.javaClassInfo.releaseSpillSlot();
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode aggregate
                    && aggregate.operator.equals("@")
                    && aggregate.operand instanceof IdentifierNode aggregateId) {
                if (!aggregateReferenceListTarget) {
                    emitDirectReferenceAliasTarget(emitterVisitor, aggregate, rhsListSlot, i);
                    if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                    continue;
                }
                String arrayName = "@" + aggregateId.name;
                SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(arrayName);
                if (entry != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, entry.index());
                } else {
                    // Package arrays do not have lexical slots.  Refaliasing
                    // their parenthesized form (for example \(@pkg)) still
                    // replaces the array's element cells, so load its global
                    // array container directly.
                    String globalName = NameNormalizer.normalizeVariableName(
                            aggregateId.name, ctx.symbolTable.getCurrentPackage());
                    mv.visitLdcInsn(globalName);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalArray",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                }
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "referenceListFrom",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "setFromReferenceList",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode aggregate
                    && aggregate.operator.equals("@")
                    && aggregate.operand instanceof IdentifierNode aggregateId) {
                // The declaration establishes the lexical/persistent array.
                // Refaliasing then replaces its slots with the RHS referents;
                // do not send the aggregate through the scalar lvalue path.
                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                String arrayName = "@" + aggregateId.name;
                SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(arrayName);
                if (entry == null) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Array " + arrayName + " not found for ref aliasing", ctx.errorUtil);
                }
                mv.visitVarInsn(Opcodes.ALOAD, entry.index());
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "referenceListFrom",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeList;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "setFromReferenceList",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeList;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode local
                    && local.operator.equals("local")
                    && local.operand instanceof OperatorNode localArray
                    && localArray.operator.equals("@")
                    && localArray.operand instanceof IdentifierNode localId) {
                // Keep `local`'s dynamic scope while aliasing its fresh array
                // container to the RHS array reference.  Do this directly:
                // the generic local emitter sees the surrounding reference
                // annotation and would return `\\@array` instead of the
                // localized RuntimeArray needed below.
                String globalName = NameNormalizer.normalizeVariableName(
                        localId.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalRuntimeArray", "makeLocal",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "arrayDeref",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                mv.visitLdcInsn(globalName);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalArray",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalArray",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode local
                    && local.operator.equals("local")
                    && local.operand instanceof OperatorNode localHash
                    && localHash.operator.equals("%")
                    && localHash.operand instanceof IdentifierNode localId) {
                String globalName = NameNormalizer.normalizeVariableName(
                        localId.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalRuntimeHash", "makeLocal",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDeref",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                mv.visitLdcInsn(globalName);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalHash",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalHash",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode hashTarget
                    && hashTarget.operator.equals("%")
                    && hashTarget.operand instanceof IdentifierNode hashId) {
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDeref",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                String hashName = "%" + hashId.name;
                SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(hashName);
                if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, entry.index());
                } else {
                    String globalName = NameNormalizer.normalizeVariableName(
                            hashId.name, ctx.symbolTable.getCurrentPackage());
                    mv.visitLdcInsn(globalName);
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalHash",
                            "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;)V", false);
                    mv.visitLdcInsn(globalName);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalHash",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                }
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            OperatorNode scalarTarget = target instanceof OperatorNode direct
                    && direct.operator.equals("$") ? direct : null;
            if (scalarTarget == null
                    && target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declared
                    && declared.operator.equals("$")) {
                // Materialize the declaration before replacing its pad cell.
                target.accept(emitterVisitor.with(RuntimeContextType.VOID));
                scalarTarget = declared;
            }
            if (scalarTarget != null && scalarTarget.operand instanceof IdentifierNode scalarId) {
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "refAliasScalarReference",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);

                String varName = "$" + scalarId.name;
                SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(varName);
                if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, entry.index());
                } else {
                    String globalName = NameNormalizer.normalizeVariableName(
                            scalarId.name, ctx.symbolTable.getCurrentPackage());
                    mv.visitLdcInsn(globalName);
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalVariable",
                            "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                    mv.visitLdcInsn(globalName);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalVariable",
                            "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                }
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode declaration
                    && (declaration.operator.equals("my") || declaration.operator.equals("state"))
                    && declaration.operand instanceof OperatorNode declaredHash
                    && declaredHash.operator.equals("%")
                    && declaredHash.operand instanceof IdentifierNode declaredId) {
                // `(\my %hash)` is one aggregate reference-alias target,
                // not a scalar lvalue.  Allocate the declaration, then
                // replace its lexical/persistent hash container from the
                // matching RHS reference.
                declaration.accept(emitterVisitor.with(RuntimeContextType.VOID));
                String hashName = "%" + declaredId.name;
                SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(hashName);
                if (entry == null) {
                    throw new PerlCompilerException(node.tokenIndex,
                            "Hash " + hashName + " not found for ref aliasing", ctx.errorUtil);
                }
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDeref",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
                if (entry.decl().equals("state")) {
                    mv.visitVarInsn(Opcodes.ALOAD, entry.index());
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, entry.index());
                }
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof OperatorNode codeTarget
                    && codeTarget.operator.equals("&")
                    && codeTarget.operand instanceof IdentifierNode codeId) {
                // A CODE reference is itself the installable value.  A
                // parenthesized alias must therefore replace the package CV,
                // not route through RuntimeScalar.aliasLvalueReference.
                mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
                mv.visitLdcInsn(i);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                        "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                String globalName = NameNormalizer.normalizeVariableName(
                        codeId.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalCodeRef",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalCodeRef",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
                continue;
            }
            if (target instanceof BinaryOperatorNode targetElement
                    && (targetElement.operator.equals("[") || targetElement.operator.equals("{"))) {
                if (targetElement.operator.equals("[")) {
                    Dereference.handleArrayElementOperator(
                            emitterVisitor.with(RuntimeContextType.LVALUE), targetElement, "getLvalue");
                } else {
                    targetElement.accept(emitterVisitor.with(RuntimeContextType.LVALUE));
                }
            } else {
                // Scalar/package targets and declarations are also valid
                // members of a parenthesized ref-alias assignment, e.g.
                // \($_a, my $a) = (\$b, \$c).
                target.accept(emitterVisitor.with(RuntimeContextType.LVALUE));
            }
            mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
            mv.visitLdcInsn(i);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                    "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "aliasLvalueReference",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            if (i < targets.elements.size() - 1) mv.visitInsn(Opcodes.POP);
        }

        // Perl list assignment evaluates to the assigned RHS list, not to
        // its final target.  The target operations above leave their final
        // value on the operand stack; replace it with the flattened RHS so
        // scalar context gets its element count and list context retains the
        // individual references.
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
        if (ctx.contextType == RuntimeContextType.RUNTIME) {
            emitRuntimeContextConversion(emitterVisitor, "@");
        } else if (ctx.contextType != RuntimeContextType.LIST
                && ctx.contextType != RuntimeContextType.LVALUE_LIST) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeArray", "scalar",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        }
        if (pooledRhsList) ctx.javaClassInfo.releaseSpillSlot();
    }

    /**
     * Bind an already-evaluated reference to a scalar ref-alias target.
     * Nested conditionals and explicit reference wrappers are target syntax,
     * not value expressions: both branches therefore receive the same RHS
     * reference and are evaluated in LVALUE context only when selected.
     */
    private static void emitReferenceAliasTarget(
            EmitterVisitor emitterVisitor, Node target, int rhsSlot) {
        emitReferenceAliasTarget(emitterVisitor, target, rhsSlot, true);
    }

    private static void emitReferenceAliasTarget(
            EmitterVisitor emitterVisitor, Node target, int rhsSlot, boolean aliasBareScalar) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;
        while (target instanceof ListNode listNode && listNode.elements.size() == 1) {
            target = listNode.elements.getFirst();
        }
        if (target instanceof OperatorNode reference && reference.operator.equals("\\")) {
            emitReferenceAliasTarget(emitterVisitor, reference.operand, rhsSlot, true);
            return;
        }
        if (target instanceof TernaryOperatorNode ternary) {
            Label falseLabel = new Label();
            Label endLabel = new Label();
            ternary.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                    EmitOperator.booleanConversionMethod(emitterVisitor, "getBoolean"), "()Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
            emitReferenceAliasTarget(emitterVisitor, ternary.trueExpr, rhsSlot, aliasBareScalar);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);
            mv.visitLabel(falseLabel);
            emitReferenceAliasTarget(emitterVisitor, ternary.falseExpr, rhsSlot, aliasBareScalar);
            mv.visitLabel(endLabel);
            return;
        }
        if (target instanceof OperatorNode scalar
                && scalar.operator.equals("$")
                && scalar.operand instanceof IdentifierNode id) {
            if (!aliasBareScalar) {
                target.accept(emitterVisitor.with(RuntimeContextType.LVALUE));
                mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                return;
            }
            String variableName = "$" + id.name;
            SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(variableName);
            if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
                mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "refAliasScalarReference",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                if (entry.decl().equals("state")) {
                    mv.visitVarInsn(Opcodes.ALOAD, entry.index());
                    mv.visitInsn(Opcodes.SWAP);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                } else {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitVarInsn(Opcodes.ASTORE, entry.index());
                }
            } else {
                String globalName = NameNormalizer.normalizeVariableName(id.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "scalarDeref",
                        "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalVariable",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalVariable",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            }
            return;
        }
        target.accept(emitterVisitor.with(RuntimeContextType.LVALUE));
        mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                "aliasLvalueReference",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
    }

    /**
     * Preserve the selected branch's aggregate type for a conditional member
     * of a ref-alias list.  Emitting the ternary in ordinary LVALUE context
     * turns an @/% branch into a scalar temporary and makes aliasLvalueReference
     * reject the selected aggregate at runtime.
     */
    private static boolean emitConditionalReferenceAliasTarget(
            EmitterVisitor emitterVisitor, TernaryOperatorNode ternary, int rhsListSlot, int index) {
        if (!isDirectReferenceAliasTarget(ternary.trueExpr)
                || !isDirectReferenceAliasTarget(ternary.falseExpr)) {
            return false;
        }
        MethodVisitor mv = emitterVisitor.ctx.mv;
        Label falseLabel = new Label();
        Label endLabel = new Label();
        ternary.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                EmitOperator.booleanConversionMethod(emitterVisitor, "getBoolean"), "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);
        emitDirectReferenceAliasTarget(emitterVisitor, ternary.trueExpr, rhsListSlot, index);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        mv.visitLabel(falseLabel);
        emitDirectReferenceAliasTarget(emitterVisitor, ternary.falseExpr, rhsListSlot, index);
        mv.visitLabel(endLabel);
        return true;
    }

    private static boolean isDirectReferenceAliasTarget(Node target) {
        return target instanceof OperatorNode sigil
                && (sigil.operator.equals("$") || sigil.operator.equals("@") || sigil.operator.equals("%"))
                && sigil.operand instanceof IdentifierNode;
    }

    /**
     * Emits one conditional scalar/array/hash member, leaving its assigned
     * value on the stack.  A selected aggregate is one target, rather than a
     * bare aggregate list member that consumes all remaining references.
     */
    private static void emitDirectReferenceAliasTarget(
            EmitterVisitor emitterVisitor, Node target, int rhsListSlot, int index) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;
        OperatorNode sigil = (OperatorNode) target;
        IdentifierNode id = (IdentifierNode) sigil.operand;
        String variableName = sigil.operator + id.name;
        mv.visitVarInsn(Opcodes.ALOAD, rhsListSlot);
        mv.visitLdcInsn(index);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeArray", "get",
                "(I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        if (sigil.operator.equals("@")) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "arrayDeref",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
            SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(variableName);
            if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ASTORE, entry.index());
            } else {
                String globalName = NameNormalizer.normalizeVariableName(id.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalArray",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalArray",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;", false);
            }
            return;
        }
        if (sigil.operator.equals("$")) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "refAliasScalarReference",
                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(variableName);
            if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.ASTORE, entry.index());
            } else {
                String globalName = NameNormalizer.normalizeVariableName(id.name, ctx.symbolTable.getCurrentPackage());
                mv.visitLdcInsn(globalName);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalVariable",
                        "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", false);
                mv.visitLdcInsn(globalName);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalVariable",
                        "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            }
            return;
        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "hashDeref",
                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
        SymbolTable.SymbolEntry entry = ctx.symbolTable.getSymbolEntry(variableName);
        if (entry != null && (entry.decl().equals("my") || entry.decl().equals("state"))) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ASTORE, entry.index());
        } else {
            String globalName = NameNormalizer.normalizeVariableName(id.name, ctx.symbolTable.getCurrentPackage());
            mv.visitLdcInsn(globalName);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable", "aliasGlobalHash",
                    "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;)V", false);
            mv.visitLdcInsn(globalName);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable", "getGlobalHash",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;", false);
        }
    }

    /**
     * Emits a scalar assignment where the LHS is a ternary operator with at least one
     * branch that is a LIST assignment expression.
     * <p>
     * In Perl 5, patterns like {@code ($cond ? @arr = expr : $var) = rhs} work because
     * LIST assignment branches execute their inner assignment and return a writable temporary.
     * Non-assignment branches are used as lvalue targets for the outer assignment.
     * <p>
     * In PerlOnJava, list assignments in scalar context return cached read-only values
     * (RuntimeScalarReadOnly), so we compile this as an if/else: LIST assignment branches
     * execute in void context (for side effects), while non-assignment branches get the
     * outer assignment applied normally. The result is always the outer RHS value,
     * matching Perl 5 behavior.
     */
    private static void emitTernaryWithAssignBranches(
            EmitterVisitor emitterVisitor,
            BinaryOperatorNode outerAssign,
            TernaryOperatorNode ternary,
            boolean trueIsAssign,
            boolean falseIsAssign) {

        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        // Emit and spill the outer RHS
        outerAssign.right.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        int rhsSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledRhs = rhsSlot >= 0;
        if (!pooledRhs) {
            rhsSlot = ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitVarInsn(Opcodes.ASTORE, rhsSlot);

        // Use a result spill slot so both branches produce consistent stack state
        int resultSlot = ctx.javaClassInfo.acquireSpillSlot();
        boolean pooledResult = resultSlot >= 0;
        if (!pooledResult) {
            resultSlot = ctx.symbolTable.allocateLocalVariable();
        }

        Label elseLabel = new Label();
        Label endLabel = new Label();

        // Emit condition
        ternary.condition.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                EmitOperator.booleanConversionMethod(emitterVisitor, "getBoolean"), "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, elseLabel);

        // True branch
        emitTernaryAssignBranch(emitterVisitor, ternary.trueExpr, trueIsAssign, rhsSlot);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        // False branch
        mv.visitLabel(elseLabel);
        emitTernaryAssignBranch(emitterVisitor, ternary.falseExpr, falseIsAssign, rhsSlot);
        mv.visitVarInsn(Opcodes.ASTORE, resultSlot);

        mv.visitLabel(endLabel);
        mv.visitVarInsn(Opcodes.ALOAD, resultSlot);

        // Release spill slots in LIFO order
        if (pooledResult) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
        if (pooledRhs) {
            ctx.javaClassInfo.releaseSpillSlot();
        }
    }

    /**
     * Emits one branch of a ternary-as-lvalue with LIST assignment branches.
     * <p>
     * If the branch is a LIST assignment, executes it in void context and pushes the outer RHS
     * as the result. If the branch is a plain lvalue (or scalar assignment), performs the outer
     * assignment normally.
     */
    private static void emitTernaryAssignBranch(
            EmitterVisitor emitterVisitor,
            Node branchExpr,
            boolean isAssign,
            int rhsSlot) {

        MethodVisitor mv = emitterVisitor.ctx.mv;

        if (isAssign) {
            // Branch is an assignment expression — execute it for side effects only
            branchExpr.accept(emitterVisitor.with(RuntimeContextType.VOID));
            // Push the outer RHS as the result (matching Perl 5 behavior where
            // assigning to the temp result of an inner assignment is effectively a no-op)
            mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
        } else {
            // Branch is a plain lvalue — do the outer assignment
            branchExpr.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
            mv.visitVarInsn(Opcodes.ALOAD, rhsSlot);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "assignTo",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }
    }

    private static void emitStateInitialization(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorNode operatorNode, EmitterContext ctx) {
        // This is a state variable initialization, it should run exactly once.
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleAssignOperator initialize state variable " + operatorNode);
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
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleAssignOperator initialize state variable " + testStateVariable);
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
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleAssignOperator initialize state variable " + initStateVariable);
        // initStateVariable.accept(emitterVisitor.with(RuntimeContextType.VOID));

        new BinaryOperatorNode("||", testStateVariable, initStateVariable, tokenIndex)
                .accept(emitterVisitor.with(RuntimeContextType.VOID));

        int resultContext = switch (ctx.contextType) {
            case RuntimeContextType.RUNTIME -> RuntimeContextType.RUNTIME;
            case RuntimeContextType.LIST, RuntimeContextType.LVALUE_LIST -> RuntimeContextType.LIST;
            default -> RuntimeContextType.SCALAR;
        };
        varNode.accept(emitterVisitor.with(resultContext));
    }

    static void handleMyOperator(EmitterVisitor emitterVisitor, OperatorNode node) {
        EmitterContext ctx = emitterVisitor.ctx;

        // Emit runtime "No such class" check for typed declarations (my TYPE $var)
        String varType = node.getAnnotation("varType") != null ? (String) node.getAnnotation("varType") : null;
        if (varType != null) {
            ctx.mv.visitLdcInsn(varType);
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/GlobalVariable",
                    "checkClassExists",
                    "(Ljava/lang/String;)V", false);
        }

        String operator = node.operator;
        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleMyOperator: operator=" + operator + ", operand type=" + (node.operand != null ? node.operand.getClass().getSimpleName() : "null") + ", contextType=" + ctx.contextType);
        if (node.operand instanceof ListNode listNode) { // my ($a, $b)  our ($a, $b)
            // process each item of the list; then returns the list
            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleMyOperator: ListNode operand, contextType=" + ctx.contextType + ", annotations=" + node.annotations);

            // Propagate attribute annotations from the parent declaration to each child.
            // When my ($x,$y) : attr is parsed, the "attributes" annotation is on the
            // parent OperatorNode but must be carried to each individual variable node
            // so that runtime attribute dispatch fires for each variable.
            boolean hasParentAttrs = node.annotations != null && node.annotations.containsKey("attributes");

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
                        if (hasParentAttrs) {
                            myNode.annotations = myNode.annotations != null ? new java.util.HashMap<>(myNode.annotations) : new java.util.HashMap<>();
                            myNode.annotations.put("attributes", node.annotations.get("attributes"));
                            myNode.annotations.put("attributePackage", node.annotations.get("attributePackage"));
                        }
                        myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                        Object lexicalSlot = myNode.getAnnotation("jvmLexicalSlot");
                        if (lexicalSlot != null) {
                            varNode.setAnnotation("jvmLexicalSlot", lexicalSlot);
                        }
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
                                if (hasParentAttrs) {
                                    myNode.annotations.put("attributes", node.annotations.get("attributes"));
                                    myNode.annotations.put("attributePackage", node.annotations.get("attributePackage"));
                                }
                                myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                            }
                        }
                    } else {
                        // Unknown structure, fall through to default handling
                        OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                        if (hasParentAttrs) {
                            myNode.annotations = new java.util.HashMap<>();
                            myNode.annotations.put("attributes", node.annotations.get("attributes"));
                            myNode.annotations.put("attributePackage", node.annotations.get("attributePackage"));
                        }
                        myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                    }
                } else {
                    OperatorNode myNode = new OperatorNode(operator, element, listNode.tokenIndex);
                    if (hasParentAttrs) {
                        myNode.annotations = new java.util.HashMap<>();
                        myNode.annotations.put("attributes", node.annotations.get("attributes"));
                        myNode.annotations.put("attributePackage", node.annotations.get("attributePackage"));
                    }
                    myNode.accept(emitterVisitor.with(RuntimeContextType.VOID));
                }
            }
            if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                // Check if this is a declared reference (my \($b, $c))
                boolean isDeclaredReference = node.annotations != null &&
                        Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                if (isDeclaredReference) {
                    // For declared references, return a list of references to the variables
                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleMyOperator: isDeclaredReference=true, emitting references for list elements");
                    MethodVisitor mv = emitterVisitor.ctx.mv;

                    // Create a new RuntimeList
                    mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);

                    // For each element in the list, emit the variable and create a reference
                    for (Node element : listNode.elements) {
                        if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleMyOperator: processing element: " + element + ", class=" + element.getClass().getSimpleName());
                        if (element instanceof OperatorNode elemOpNode && "$@%".contains(elemOpNode.operator)) {
                            if (CompilerOptions.DEBUG_ENABLED) ctx.logDebug("handleMyOperator: emitting createReference for " + elemOpNode.operator);
                            mv.visitInsn(Opcodes.DUP);  // Dup the RuntimeList

                            // Emit the variable in SCALAR context
                            element.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                            // Create a reference to the variable
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                                    "createReference",
                                    "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                    false);

                            // Add to the list
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "org/perlonjava/runtime/runtimetypes/RuntimeList",
                                    "add",
                                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V",
                                    false);
                        }
                    }
                } else {
                    // Check if any element has isDeclaredReference annotation
                    boolean hasAnyDeclaredRef = false;
                    for (Node element : listNode.elements) {
                        if (element instanceof OperatorNode elemOpNode &&
                                elemOpNode.annotations != null &&
                                Boolean.TRUE.equals(elemOpNode.annotations.get("isDeclaredReference"))) {
                            hasAnyDeclaredRef = true;
                            break;
                        }
                    }

                    if (hasAnyDeclaredRef) {
                        // Mixed case: some elements are declared refs, some are not
                        // Build the list manually, emitting references for declared refs
                        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleMyOperator: hasAnyDeclaredRef=true, building mixed list");
                        MethodVisitor mv = emitterVisitor.ctx.mv;

                        mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RuntimeList");
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/perlonjava/runtime/runtimetypes/RuntimeList", "<init>", "()V", false);

                        for (Node element : listNode.elements) {
                            if (element instanceof OperatorNode elemOpNode && "$@%".contains(elemOpNode.operator)) {
                                mv.visitInsn(Opcodes.DUP);
                                element.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                                // If this element has isDeclaredReference, create a reference
                                if (elemOpNode.annotations != null &&
                                        Boolean.TRUE.equals(elemOpNode.annotations.get("isDeclaredReference"))) {
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                                            "createReference",
                                            "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                            false);
                                }

                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "org/perlonjava/runtime/runtimetypes/RuntimeList",
                                        "add",
                                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V",
                                        false);
                            }
                        }
                    } else {
                        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleMyOperator: isDeclaredReference=false, emitting listNode directly");
                        listNode.accept(emitterVisitor);
                    }
                }
            }
            return;
        } else if (node.operand instanceof OperatorNode sigilNode) { //  [my our] followed by [$ @ %]
            String sigil = sigilNode.operator;

            // Handle my \\$x - reference to a declared reference
            if (sigil.equals("\\") && node.annotations != null &&
                    Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"))) {
                // This is my \\$x which means: create a declared reference and then take a reference to it
                // The operand is \$x, so we need to emit the declared reference creation
                // and then take a reference to it

                // First, emit the declared reference variable (the inner part)
                sigilNode.accept(emitterVisitor.with(RuntimeContextType.SCALAR));

                // The variable is now on the stack, and we're in an assignment context
                // The assignment operator will handle storing the reference
                return;
            }

            if ("$@%".contains(sigil)) {
                Node identifierNode = sigilNode.operand;
                if (identifierNode instanceof IdentifierNode) { // my $a
                    String name = ((IdentifierNode) identifierNode).name;
                    String var = sigil + name;
                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("MY " + operator + " " + sigil + name);
                    
                    // Check for redeclaration warnings (Perl category "shadow", not "redefine")
                    if (WarningFlags.ckWarnForScope(emitterVisitor.ctx.symbolTable, "shadow")) {
                        // Skip warning for state variables that were already hoisted by EmitBlock.
                        // The hoisted declaration pre-allocates the JVM local slot, so the original
                        // declaration sees it as a duplicate. This is not a real user-level redeclaration.
                        boolean wasHoisted = sigilNode.getBooleanAnnotation("hoistedState");
                        if (operator.equals("our")) {
                            // For 'our', only warn if redeclared in the same package (matching Perl behavior)
                            if (emitterVisitor.ctx.symbolTable.isOurVariableRedeclaredInSamePackage(var)) {
                                System.err.println(
                                        emitterVisitor.ctx.errorUtil.errorMessage(node.getIndex(),
                                                "\"our\" variable " + var + " redeclared"));
                            }
                        } else {
                            // For 'my'/'local'/'state', warn if redeclared in the same scope
                            // (but not if the variable was hoisted by state declaration hoisting)
                            if (!wasHoisted && emitterVisitor.ctx.symbolTable.getVariableIndexInCurrentScope(var) != -1) {
                                System.err.println(
                                        emitterVisitor.ctx.errorUtil.errorMessage(node.getIndex(),
                                                "\"" + operator + "\" variable " + var + " masks earlier declaration in same scope"));
                            }
                        }
                    }
                    
                    int varIndex = emitterVisitor.ctx.symbolTable.addVariable(var, operator, sigilNode);
                    sigilNode.setAnnotation("jvmLexicalSlot", varIndex);
                    node.setAnnotation("jvmLexicalSlot", varIndex);
                    // TODO optimization - SETVAR+MY can be combined

                    // Check if this is a declared reference (my \$x)
                    boolean isDeclaredReference = node.annotations != null &&
                            Boolean.TRUE.equals(node.annotations.get("isDeclaredReference"));

                    // Determine the class name based on the sigil
                    String className = EmitterMethodCreator.getVariableClassName(sigil);

                    if (operator.equals("my")) {
                        Integer beginId = RuntimeCode.evalBeginIds().get(sigilNode);
                        if (beginId == null) {
                            ctx.mv.visitTypeInsn(Opcodes.NEW, className);
                            ctx.mv.visitInsn(Opcodes.DUP);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESPECIAL,
                                    className,
                                    "<init>",
                                    "()V",
                                    false);
                        } else {
                            String methodName;
                            String methodDescriptor;
                            switch (var.charAt(0)) {
                                case '$' -> {
                                    methodName = "retrieveBeginScalar";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                                }
                                case '@' -> {
                                    methodName = "retrieveBeginArray";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;";
                                }
                                case '%' -> {
                                    methodName = "retrieveBeginHash";
                                    methodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;";
                                }
                                default ->
                                        throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + var.charAt(0), ctx.errorUtil);
                            }

                            ctx.mv.visitLdcInsn(var);
                            ctx.mv.visitLdcInsn(beginId);
                            ctx.mv.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "org/perlonjava/runtime/runtimetypes/PersistentVariable",
                                    methodName,
                                    methodDescriptor,
                                    false);
                        }

                        // Devel::LexAlias can replace this CV's lexical cell
                        // before invocation. Bare `my` must retain the aliased
                        // value rather than resetting it to undef/empty.
                        Node codeRef = new OperatorNode("__SUB__", null, node.tokenIndex);
                        codeRef.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
                        ctx.mv.visitLdcInsn(var);
                        ctx.mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                                "resolveLexicalAlias",
                                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;",
                                false);
                        ctx.mv.visitTypeInsn(Opcodes.CHECKCAST, className);
                    } else if (operator.equals("state")) {
                        // "state":
                        // Determine the method to call and its descriptor based on the sigil
                        String methodName;
                        String methodDescriptor;
                        switch (var.charAt(0)) {
                            case '$' -> {
                                methodName = "retrieveStateScalar";
                                methodDescriptor = "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;";
                            }
                            case '@' -> {
                                methodName = "retrieveStateArray";
                                methodDescriptor = "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeArray;";
                            }
                            case '%' -> {
                                methodName = "retrieveStateHash";
                                methodDescriptor = "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Ljava/lang/String;I)Lorg/perlonjava/runtime/runtimetypes/RuntimeHash;";
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
                                "org/perlonjava/runtime/runtimetypes/StateVariable",
                                methodName,
                                methodDescriptor,
                                false);
                    } else {
                        // "our":
                        // Create and fetch a global variable
                        fetchGlobalVariable(emitterVisitor.ctx, true, sigil, name, node.getIndex());
                    }
                    if (sigil.equals("$") && !operator.equals("our")) {
                        ctx.mv.visitLdcInsn(var);
                        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                                "setLexicalDisplayName",
                                "(Ljava/lang/String;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                false);
                    }
                    // Store the variable in a JVM local variable
                    emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ASTORE, varIndex);

                    // Register my-variables on the cleanup stack so DESTROY fires
                    // if die propagates through this subroutine without eval.
                    // State/our variables are excluded: state persists across calls,
                    // our is global.  register() is a no-op until the first bless().
                    //
                    // Phase R (classic_experiment_finding.md): skip emission when
                    // CleanupNeededVisitor proved the enclosing sub has no
                    // bless/weaken/user-sub-calls — no tracked ref can ever land
                    // in this my-var, so register/unregister pair is dead code.
                    if (operator.equals("my")
                            && emitterVisitor.ctx.javaClassInfo.cleanupNeeded) {
                        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "org/perlonjava/runtime/runtimetypes/MyVarCleanupStack",
                                "register",
                                "(Ljava/lang/Object;)V",
                                false);
                    }

                    // Emit runtime attribute dispatch for my/state variables.
                    // For 'our', attributes were already dispatched at compile time.
                    if (!operator.equals("our") && node.annotations != null
                            && node.annotations.containsKey("attributes")) {
                        emitRuntimeAttributeDispatch(emitterVisitor, node, varIndex, sigil);
                    }

                    // For declared references in non-void context, return a reference to the variable
                    if (isDeclaredReference && emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                        // Load the variable back from the local variable slot
                        emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                        // Create a reference to it
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "org/perlonjava/runtime/runtimetypes/RuntimeBase",
                                "createReference",
                                "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                                false);
                    } else {
                        // Normal handling for non-declared references
                        if (emitterVisitor.ctx.contextType != RuntimeContextType.VOID) {
                            // Load the variable back for the return value
                            emitterVisitor.ctx.mv.visitVarInsn(Opcodes.ALOAD, varIndex);
                        }
                    }

                    if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR && !sigil.equals("$")) {
                        // scalar context: transform the value in the stack to scalar
                        emitterVisitor.ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeBase", "scalar", "()Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
                    }
                    return;
                }
            }
        }
        throw new PerlCompilerException(
                node.tokenIndex, "Not implemented: " + node.operator, emitterVisitor.ctx.errorUtil);
    }

    /**
     * Emit bytecode to call Attributes.runtimeDispatchModifyVariableAttributes()
     * for my/state variable declarations that have non-builtin attributes.
     *
     * <p>This is called after the variable is stored in its JVM local slot, so
     * the reference passed to MODIFY_*_ATTRIBUTES points to the actual lexical.
     */
    @SuppressWarnings("unchecked")
    private static void emitRuntimeAttributeDispatch(EmitterVisitor emitterVisitor,
                                                      OperatorNode node, int varIndex, String sigil) {
        EmitterContext ctx = emitterVisitor.ctx;
        MethodVisitor mv = ctx.mv;

        List<String> attributes = (List<String>) node.annotations.get("attributes");
        String packageName = (String) node.annotations.get("attributePackage");
        if (packageName == null) {
            packageName = ctx.symbolTable.getCurrentPackage();
        }
        String fileName = ctx.compilerOptions.fileName;
        int lineNum = ctx.errorUtil != null ? ctx.errorUtil.getLineNumber(node.getIndex()) : 0;

        // Push args: (String packageName, RuntimeBase variable, String sigil, String[] attrs, String fileName, int lineNum)
        mv.visitLdcInsn(packageName);
        mv.visitVarInsn(Opcodes.ALOAD, varIndex);
        mv.visitLdcInsn(sigil);

        // Create String[] for attributes
        mv.visitIntInsn(Opcodes.BIPUSH, attributes.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        for (int i = 0; i < attributes.size(); i++) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            mv.visitLdcInsn(attributes.get(i));
            mv.visitInsn(Opcodes.AASTORE);
        }

        mv.visitLdcInsn(fileName);
        mv.visitLdcInsn(lineNum);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/perlmodule/Attributes",
                "runtimeDispatchModifyVariableAttributes",
                "(Ljava/lang/String;Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;I)V",
                false);
    }
}
