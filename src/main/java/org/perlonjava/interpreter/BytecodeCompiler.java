package org.perlonjava.interpreter;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.runtime.*;
import org.perlonjava.symbols.ScopedSymbolTable;
import org.perlonjava.symbols.SymbolTable;

import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * BytecodeCompiler traverses the AST and generates interpreter bytecode.
 *
 * This is analogous to EmitterVisitor but generates custom bytecode
 * for the interpreter instead of JVM bytecode via ASM.
 *
 * Key responsibilities:
 * - Visit AST nodes and emit interpreter opcodes
 * - Allocate registers for variables and temporaries
 * - Build constant pool and string pool
 * - Generate 3-address code (rd = rs1 op rs2)
 */
public class BytecodeCompiler implements Visitor {
    // Pre-allocate with reasonable initial capacity to reduce resizing
    // Typical small eval/subroutine needs 20-50 bytecodes, 5-10 constants, 3-8 strings
    private final List<Short> bytecode = new ArrayList<>(64);
    private final List<Object> constants = new ArrayList<>(16);
    private final List<String> stringPool = new ArrayList<>(16);
    private final Map<String, Integer> stringPoolIndex = new HashMap<>(16);  // O(1) lookup
    private final Map<Object, Integer> constantPoolIndex = new HashMap<>(16);  // O(1) lookup

    // Simple variable-to-register mapping for the interpreter
    // Each scope is a Map<String, Integer> mapping variable names to register indices
    private final Stack<Map<String, Integer>> variableScopes = new Stack<>();

    // Symbol table for package/class tracking
    // Tracks current package, class flag, and package versions like the compiler does
    private final ScopedSymbolTable symbolTable = new ScopedSymbolTable();

    // Stack to save/restore register state when entering/exiting scopes
    private final Stack<Integer> savedNextRegister = new Stack<>();
    private final Stack<Integer> savedBaseRegister = new Stack<>();

    // Token index tracking for error reporting
    private final TreeMap<Integer, Integer> pcToTokenIndex = new TreeMap<>();
    private int currentTokenIndex = -1;  // Track current token for error reporting

    // Error reporting
    private final ErrorMessageUtil errorUtil;

    // EmitterContext for strict checks and other compile-time options
    private EmitterContext emitterContext;

    // Register allocation
    private int nextRegister = 3;  // 0=this, 1=@_, 2=wantarray
    private int baseRegisterForStatement = 3;  // Reset point after each statement
    private int maxRegisterEverUsed = 2;  // Track highest register ever allocated

    // Track last result register for expression chaining
    private int lastResultReg = -1;

    // Track current calling context for subroutine calls
    private int currentCallContext = RuntimeContextType.LIST; // Default to LIST

    // Closure support
    private RuntimeBase[] capturedVars;           // Captured variable values
    private String[] capturedVarNames;            // Parallel array of names
    private Map<String, Integer> capturedVarIndices;  // Name → register index

    // Track ALL variables ever declared (for variableRegistry)
    // This is needed because inner scopes get popped before variableRegistry is built
    private final Map<String, Integer> allDeclaredVariables = new HashMap<>();

    // BEGIN support for named subroutine closures
    private int currentSubroutineBeginId = 0;     // BEGIN ID for current named subroutine (0 = not in named sub)
    private Set<String> currentSubroutineClosureVars = new HashSet<>();  // Variables captured from outer scope

    // Source information
    private final String sourceName;
    private final int sourceLine;

    public BytecodeCompiler(String sourceName, int sourceLine, ErrorMessageUtil errorUtil) {
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.errorUtil = errorUtil;

        // Initialize with global scope containing the 3 reserved registers
        Map<String, Integer> globalScope = new HashMap<>();
        globalScope.put("this", 0);
        globalScope.put("@_", 1);
        globalScope.put("wantarray", 2);
        variableScopes.push(globalScope);
    }

    // Legacy constructor for backward compatibility
    public BytecodeCompiler(String sourceName, int sourceLine) {
        this(sourceName, sourceLine, null);
    }

    /**
     * Constructor for eval STRING with parent scope variable registry.
     * Initializes variableScopes with variables from parent scope.
     *
     * @param sourceName Source name for error messages
     * @param sourceLine Source line for error messages
     * @param errorUtil Error message utility
     * @param parentRegistry Variable registry from parent scope (for eval STRING)
     */
    public BytecodeCompiler(String sourceName, int sourceLine, ErrorMessageUtil errorUtil,
                           Map<String, Integer> parentRegistry) {
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
        this.errorUtil = errorUtil;

        // Initialize with global scope containing the 3 reserved registers
        // plus any variables from parent scope (for eval STRING)
        Map<String, Integer> globalScope = new HashMap<>();
        globalScope.put("this", 0);
        globalScope.put("@_", 1);
        globalScope.put("wantarray", 2);

        if (parentRegistry != null) {
            // Add parent scope variables (for eval STRING variable capture)
            globalScope.putAll(parentRegistry);

            // Mark parent scope variables as captured so assignments use SET_SCALAR
            capturedVarIndices = new HashMap<>();
            for (Map.Entry<String, Integer> entry : parentRegistry.entrySet()) {
                String varName = entry.getKey();
                int regIndex = entry.getValue();
                // Skip reserved registers
                if (regIndex >= 3) {
                    capturedVarIndices.put(varName, regIndex);
                }
            }

            // Adjust nextRegister to account for captured variables
            // Find the maximum register index used by parent scope
            int maxRegister = 2;  // Start with reserved registers (0-2)
            for (Integer regIndex : parentRegistry.values()) {
                if (regIndex > maxRegister) {
                    maxRegister = regIndex;
                }
            }
            // Next available register is one past the maximum used
            this.nextRegister = maxRegister + 1;
            this.maxRegisterEverUsed = maxRegister;
            this.baseRegisterForStatement = this.nextRegister;
        }

        variableScopes.push(globalScope);
    }

    /**
     * Helper: Check if a variable exists in any scope.
     */
    private boolean hasVariable(String name) {
        for (int i = variableScopes.size() - 1; i >= 0; i--) {
            if (variableScopes.get(i).containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper: Get the register index for a variable.
     * Returns -1 if not found.
     */
    private int getVariableRegister(String name) {
        for (int i = variableScopes.size() - 1; i >= 0; i--) {
            Integer reg = variableScopes.get(i).get(name);
            if (reg != null) {
                return reg;
            }
        }
        return -1;
    }

    /**
     * Helper: Add a variable to the current scope and return its register index.
     * Allocates a new register.
     */
    private int addVariable(String name, String declType) {
        int reg = allocateRegister();
        variableScopes.peek().put(name, reg);
        allDeclaredVariables.put(name, reg);  // Track for variableRegistry
        return reg;
    }

    /**
     * Helper: Enter a new lexical scope.
     * Saves current register allocation state so inner scopes don't
     * interfere with outer scope register usage.
     */
    private void enterScope() {
        variableScopes.push(new HashMap<>());
        // Save current register state
        savedNextRegister.push(nextRegister);
        savedBaseRegister.push(baseRegisterForStatement);
        // Update base to protect all registers allocated before this scope
        baseRegisterForStatement = nextRegister;
    }

    /**
     * Helper: Exit the current lexical scope.
     * Restores register allocation state to what it was before entering the scope.
     */
    private void exitScope() {
        if (variableScopes.size() > 1) {
            variableScopes.pop();
            // Restore register state
            if (!savedNextRegister.isEmpty()) {
                nextRegister = savedNextRegister.pop();
            }
            if (!savedBaseRegister.isEmpty()) {
                baseRegisterForStatement = savedBaseRegister.pop();
            }
        }
    }

    /**
     * Helper: Get current package name for global variable resolution.
     * Uses symbolTable for proper package/class tracking.
     */
    private String getCurrentPackage() {
        return symbolTable.getCurrentPackage();
    }

    /**
     * Helper: Get all variable names in all scopes (for closure detection).
     */
    private String[] getVariableNames() {
        Set<String> allVars = new HashSet<>();
        for (Map<String, Integer> scope : variableScopes) {
            allVars.addAll(scope.keySet());
        }
        return allVars.toArray(new String[0]);
    }

    /**
     * Throw a compiler exception with proper error formatting.
     * Uses PerlCompilerException which formats with line numbers and code context.
     *
     * @param message The error message
     * @param tokenIndex The token index where the error occurred
     */
    private void throwCompilerException(String message, int tokenIndex) {
        if (errorUtil != null && tokenIndex >= 0) {
            throw new PerlCompilerException(tokenIndex, message, errorUtil);
        } else {
            // Fallback to simple error (no context available)
            throw new RuntimeException(message);
        }
    }

    /**
     * Throw a compiler exception using the current token index.
     *
     * @param message The error message
     */
    private void throwCompilerException(String message) {
        throwCompilerException(message, currentTokenIndex);
    }

    /**
     * Compile an AST node to InterpretedCode.
     *
     * @param node The AST node to compile
     * @return InterpretedCode ready for execution
     */
    public InterpretedCode compile(Node node) {
        return compile(node, null);
    }

    /**
     * Compile an AST node to InterpretedCode with optional closure support.
     *
     * @param node The AST node to compile
     * @param ctx  EmitterContext for closure detection (may be null)
     * @return InterpretedCode ready for execution
     */
    public InterpretedCode compile(Node node, EmitterContext ctx) {
        // Store context for strict checks and other compile-time options
        this.emitterContext = ctx;

        // Detect closure variables if context is provided
        if (ctx != null) {
            detectClosureVariables(node, ctx);
            // Use the calling context from EmitterContext for top-level expressions
            // This is crucial for eval STRING to propagate context correctly
            currentCallContext = ctx.contextType;
        }

        // If we have captured variables, allocate registers for them
        // BUT: If we were constructed with a parentRegistry (for eval STRING),
        // nextRegister is already correctly set by the constructor.
        // Only reset it if we have runtime capturedVars but no parentRegistry.
        if (capturedVars != null && capturedVars.length > 0 && capturedVarIndices == null) {
            // Registers 0-2 are reserved (this, @_, wantarray)
            // Registers 3+ are captured variables
            nextRegister = 3 + capturedVars.length;
        }

        // Visit the node to generate bytecode
        node.accept(this);

        // Emit RETURN with last result register
        // If no result was produced, return undef instead of register 0 ("this")
        int returnReg;
        if (lastResultReg >= 0) {
            returnReg = lastResultReg;
        } else {
            // No result - allocate register for undef
            returnReg = allocateRegister();
            emit(Opcodes.LOAD_UNDEF);
            emitReg(returnReg);
        }

        emit(Opcodes.RETURN);
        emitReg(returnReg);

        // Build variable registry for eval STRING support
        // Use allDeclaredVariables which tracks ALL variables ever declared,
        // not variableScopes which loses variables when scopes are popped
        Map<String, Integer> variableRegistry = new HashMap<>();
        variableRegistry.putAll(allDeclaredVariables);

        // Also include variables from current scopes (in case of nested contexts)
        for (Map<String, Integer> scope : variableScopes) {
            variableRegistry.putAll(scope);
        }

        // Build InterpretedCode
        return new InterpretedCode(
            toShortArray(),
            constants.toArray(),
            stringPool.toArray(new String[0]),
            maxRegisterEverUsed + 1,  // maxRegisters = highest register used + 1
            capturedVars,  // NOW POPULATED!
            sourceName,
            sourceLine,
            pcToTokenIndex,  // Pass token index map for error reporting
            variableRegistry,  // Variable registry for eval STRING
            errorUtil  // Pass error util for line number lookup
        );
    }

    // =========================================================================
    // CLOSURE DETECTION
    // =========================================================================

    /**
     * Detect closure variables: variables referenced but not declared locally.
     * Populates capturedVars, capturedVarNames, and capturedVarIndices.
     *
     * @param ast AST to scan for variable references
     * @param ctx EmitterContext containing symbol table and eval context
     */
    private void detectClosureVariables(Node ast, EmitterContext ctx) {
        // If capturedVarIndices was already set by the constructor (for eval STRING
        // with parentRegistry), don't overwrite it. The constructor has already set up
        // the correct captured variable mappings from the parent scope.
        if (capturedVarIndices != null) {
            return;  // Already set up by constructor with parentRegistry
        }

        // Step 1: Collect all variable references in AST
        Set<String> referencedVars = collectReferencedVariables(ast);

        // Step 2: Get local variable declarations from symbol table
        Set<String> localVars = getLocalVariableNames(ctx);

        // Step 3: Closure vars = referenced - local
        Set<String> closureVarNames = new HashSet<>(referencedVars);
        closureVarNames.removeAll(localVars);

        // Remove special variables that don't need capture (they're globals)
        closureVarNames.removeIf(name ->
            name.equals("$_") || name.equals("$@") || name.equals("$!")
        );

        if (closureVarNames.isEmpty()) {
            return;  // No closure vars
        }

        // Step 4: Build arrays
        capturedVarNames = closureVarNames.toArray(new String[0]);
        capturedVarIndices = new HashMap<>();
        List<RuntimeBase> values = new ArrayList<>();

        for (int i = 0; i < capturedVarNames.length; i++) {
            String varName = capturedVarNames[i];
            capturedVarIndices.put(varName, 3 + i);  // Registers 3+

            // Get variable value from eval runtime context
            RuntimeBase value = getVariableValueFromContext(varName, ctx);
            values.add(value);
        }

        capturedVars = values.toArray(new RuntimeBase[0]);
    }

    /**
     * Collect all variable references in AST.
     *
     * @param ast AST node to scan
     * @return Set of variable names (with sigils)
     */
    private Set<String> collectReferencedVariables(Node ast) {
        Set<String> refs = new HashSet<>();
        ast.accept(new VariableCollectorVisitor(refs));
        return refs;
    }

    /**
     * Get local variable names from current scope (not parent scopes).
     *
     * @param ctx EmitterContext containing symbol table
     * @return Set of local variable names
     */
    private Set<String> getLocalVariableNames(EmitterContext ctx) {
        Set<String> locals = new HashSet<>();
        // Collect variables from all scopes
        String[] varNames = getVariableNames();
        for (String name : varNames) {
            // Skip the 3 reserved registers (this, @_, wantarray)
            if (!name.equals("this") && !name.equals("@_") && !name.equals("wantarray")) {
                locals.add(name);
            }
        }
        return locals;
    }

    /**
     * Get variable value from eval runtime context for closure capture.
     *
     * @param varName Variable name (with sigil)
     * @param ctx     EmitterContext containing eval tag
     * @return RuntimeBase value to capture
     */
    private RuntimeBase getVariableValueFromContext(String varName, EmitterContext ctx) {
        // For eval STRING, runtime values are available via evalRuntimeContext ThreadLocal
        RuntimeCode.EvalRuntimeContext evalCtx = RuntimeCode.getEvalRuntimeContext();
        if (evalCtx != null && evalCtx.runtimeValues != null) {
            // Find variable in captured environment
            String[] capturedEnv = evalCtx.capturedEnv;
            Object[] runtimeValues = evalCtx.runtimeValues;

            for (int i = 0; i < capturedEnv.length; i++) {
                if (capturedEnv[i].equals(varName)) {
                    Object value = runtimeValues[i];
                    if (value instanceof RuntimeBase) {
                        return (RuntimeBase) value;
                    }
                }
            }
        }

        // If we can't find a runtime value, return a placeholder
        // This is OK - closures are typically created at runtime via eval
        return new RuntimeScalar();
    }

    // =========================================================================
    // VISITOR METHODS
    // =========================================================================

    @Override
    public void visit(BlockNode node) {
        // Blocks create a new lexical scope
        enterScope();

        // Visit each statement in the block
        int numStatements = node.elements.size();
        for (int i = 0; i < numStatements; i++) {
            Node stmt = node.elements.get(i);

            // Track line number for this statement (like codegen's setDebugInfoLineNumber)
            if (stmt != null) {
                int tokenIndex = stmt.getIndex();
                int pc = bytecode.size();
                pcToTokenIndex.put(pc, tokenIndex);
            }

            // Standalone statements (not assignments) use VOID context
            int savedContext = currentCallContext;

            // If this is not an assignment or other value-using construct, use VOID context
            // EXCEPT for the last statement in a block, which should use the block's context
            boolean isLastStatement = (i == numStatements - 1);
            if (!isLastStatement && !(stmt instanceof BinaryOperatorNode && ((BinaryOperatorNode) stmt).operator.equals("="))) {
                currentCallContext = RuntimeContextType.VOID;
            }

            stmt.accept(this);

            currentCallContext = savedContext;

            // Recycle temporary registers after each statement
            // enterScope() protects registers allocated before entering a scope
            recycleTemporaryRegisters();
        }

        // Exit scope restores register state
        exitScope();
    }

    @Override
    public void visit(NumberNode node) {
        // Handle number literals with proper Perl semantics
        int rd = allocateRegister();

        // Remove underscores which Perl allows as digit separators (e.g., 10_000_000)
        String value = node.value.replace("_", "");

        try {
            // Use ScalarUtils.isInteger() for consistent number parsing with compiler
            boolean isInteger = org.perlonjava.runtime.ScalarUtils.isInteger(value);

            // For 32-bit Perl emulation, check if this is a large integer
            // that needs to be stored as a string to preserve precision
            boolean isLargeInteger = !isInteger && value.matches("^-?\\d+$");

            if (isInteger) {
                // Regular integer - use LOAD_INT to create mutable scalar
                // Note: We don't use RuntimeScalarCache here because MOVE just copies references,
                // and we need mutable scalars for variables (++, --, etc.)
                int intValue = Integer.parseInt(value);
                emit(Opcodes.LOAD_INT);
                emitReg(rd);
                emitInt(intValue);
            } else if (isLargeInteger) {
                // Large integer - store as string to preserve precision (32-bit Perl emulation)
                int strIdx = addToStringPool(value);
                emit(Opcodes.LOAD_STRING);
                emitReg(rd);
                emit(strIdx);
            } else {
                // Floating-point number - create RuntimeScalar with double value
                RuntimeScalar doubleScalar = new RuntimeScalar(Double.parseDouble(value));
                int constIdx = addToConstantPool(doubleScalar);
                emit(Opcodes.LOAD_CONST);
                emitReg(rd);
                emit(constIdx);
            }

        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number: " + node.value, e);
        }

        lastResultReg = rd;
    }

    @Override
    public void visit(StringNode node) {
        // Emit LOAD_STRING: rd = new RuntimeScalar(stringPool[index])
        int rd = allocateRegister();
        int strIndex = addToStringPool(node.value);

        emit(Opcodes.LOAD_STRING);
        emitReg(rd);
        emit(strIndex);

        lastResultReg = rd;
    }

    @Override
    public void visit(IdentifierNode node) {
        // Variable reference
        String varName = node.name;

        // Check if this is a captured variable (with sigil)
        // Try common sigils: $, @, %
        String[] sigils = {"$", "@", "%"};
        for (String sigil : sigils) {
            String varNameWithSigil = sigil + varName;
            if (capturedVarIndices != null && capturedVarIndices.containsKey(varNameWithSigil)) {
                // Captured variable - use its pre-allocated register
                lastResultReg = capturedVarIndices.get(varNameWithSigil);
                return;
            }
        }

        // Check if it's a lexical variable (may have sigil or not)
        if (hasVariable(varName)) {
            // Lexical variable - already has a register
            lastResultReg = getVariableRegister(varName);
        } else {
            // Try with sigils
            boolean found = false;
            for (String sigil : sigils) {
                String varNameWithSigil = sigil + varName;
                if (hasVariable(varNameWithSigil)) {
                    lastResultReg = getVariableRegister(varNameWithSigil);
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Not a lexical variable - could be a global or a bareword
                // Check for strict subs violation (bareword without sigil)
                if (!varName.startsWith("$") && !varName.startsWith("@") && !varName.startsWith("%")) {
                    // This is a bareword (no sigil)
                    if (emitterContext != null && emitterContext.symbolTable != null &&
                        emitterContext.symbolTable.isStrictOptionEnabled(org.perlonjava.perlmodule.Strict.HINT_STRICT_SUBS)) {
                        throwCompilerException("Bareword \"" + varName + "\" not allowed while \"strict subs\" in use");
                    }
                    // Not strict - treat bareword as string literal
                    int rd = allocateRegister();
                    emit(Opcodes.LOAD_STRING);
                    emitReg(rd);
                    int strIdx = addToStringPool(varName);
                    emit(strIdx);
                    lastResultReg = rd;
                    return;
                }

                // Global variable
                // Strip sigil and normalize name (e.g., "$x" → "main::x")
                String bareVarName = varName.substring(1);  // Remove sigil
                String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
                int rd = allocateRegister();
                int nameIdx = addToStringPool(normalizedName);

                emit(Opcodes.LOAD_GLOBAL_SCALAR);
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            }
        }
    }

    /**
     * Handle hash slice operations: @hash{keys} or @$hashref{keys}
     * Must be called before automatic operand compilation to avoid compiling @ operator
     */
    /**
     * Handle array element access: $array[index]
     * Example: $ARGV[0] or $array[$i]
     */
    private void handleArrayElementAccess(BinaryOperatorNode node, OperatorNode leftOp) {
        // Get the array variable name
        if (!(leftOp.operand instanceof IdentifierNode)) {
            throwCompilerException("Array element access requires identifier");
            return;
        }
        String varName = ((IdentifierNode) leftOp.operand).name;
        String arrayVarName = "@" + varName;

        // Get the array register - check lexical first, then global
        int arrayReg;
        if (hasVariable(arrayVarName)) {
            // Lexical array
            arrayReg = getVariableRegister(arrayVarName);
        } else {
            // Global array - load it
            arrayReg = allocateRegister();
            String globalArrayName = NameNormalizer.normalizeVariableName(
                varName,
                getCurrentPackage()
            );
            int nameIdx = addToStringPool(globalArrayName);
            emit(Opcodes.LOAD_GLOBAL_ARRAY);
            emitReg(arrayReg);
            emit(nameIdx);
        }

        // Compile the index expression (right side)
        if (!(node.right instanceof ArrayLiteralNode)) {
            throwCompilerException("Array subscript requires ArrayLiteralNode");
            return;
        }
        ArrayLiteralNode indexNode = (ArrayLiteralNode) node.right;
        if (indexNode.elements.isEmpty()) {
            throwCompilerException("Array subscript requires at least one index");
            return;
        }

        // Handle single element access: $array[0]
        if (indexNode.elements.size() == 1) {
            Node indexExpr = indexNode.elements.get(0);
            indexExpr.accept(this);
            int indexReg = lastResultReg;

            // Emit ARRAY_GET opcode
            int rd = allocateRegister();
            emit(Opcodes.ARRAY_GET);
            emitReg(rd);
            emitReg(arrayReg);
            emitReg(indexReg);

            lastResultReg = rd;
        } else {
            throwCompilerException("Multi-element array access not yet implemented");
        }
    }

    /**
     * Handle array slice: @array[indices]
     * Example: @array[0,2,4] or @array[1..5]
     */
    private void handleArraySlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int arrayReg;

        // Get the array - either direct @array or dereferenced @$arrayref
        if (leftOp.operand instanceof IdentifierNode) {
            // Direct array slice: @array[indices]
            String varName = ((IdentifierNode) leftOp.operand).name;
            String arrayVarName = "@" + varName;

            // Get the array register - check lexical first, then global
            if (hasVariable(arrayVarName)) {
                // Lexical array
                arrayReg = getVariableRegister(arrayVarName);
            } else {
                // Global array - load it
                arrayReg = allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(
                    varName,
                    getCurrentPackage()
                );
                int nameIdx = addToStringPool(globalArrayName);
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emitReg(arrayReg);
                emit(nameIdx);
            }
        } else if (leftOp.operand instanceof OperatorNode) {
            // Array dereference slice: @$arrayref[indices]
            OperatorNode derefOp = (OperatorNode) leftOp.operand;
            if (derefOp.operator.equals("$")) {
                // Compile the scalar reference
                derefOp.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
            } else {
                throwCompilerException("Array slice requires array or array reference");
                return;
            }
        } else {
            throwCompilerException("Array slice requires identifier or reference");
            return;
        }

        // Compile the indices (right side) - this is an ArrayLiteralNode
        if (!(node.right instanceof ArrayLiteralNode)) {
            throwCompilerException("Array slice requires ArrayLiteralNode");
            return;
        }
        ArrayLiteralNode indicesNode = (ArrayLiteralNode) node.right;

        // Compile the indices into a list
        // The ArrayLiteralNode might contain a range operator (..) or multiple elements
        List<Integer> indexRegs = new ArrayList<>();
        for (Node indexExpr : indicesNode.elements) {
            // Each element could be a simple value or a range expression
            indexExpr.accept(this);
            indexRegs.add(lastResultReg);
        }

        // Create a RuntimeList from index registers
        int indicesListReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(indicesListReg);
        emit(indexRegs.size());
        for (int indexReg : indexRegs) {
            emitReg(indexReg);
        }

        // Emit ARRAY_SLICE opcode
        int rd = allocateRegister();
        emit(Opcodes.ARRAY_SLICE);
        emitReg(rd);
        emitReg(arrayReg);
        emitReg(indicesListReg);

        lastResultReg = rd;
    }

    /**
     * Handle hash element access: $hash{key}
     * Example: $hash{foo} or $hash{$key}
     */
    private void handleHashElementAccess(BinaryOperatorNode node, OperatorNode leftOp) {
        // Get the hash variable name
        if (!(leftOp.operand instanceof IdentifierNode)) {
            throwCompilerException("Hash element access requires identifier");
            return;
        }
        String varName = ((IdentifierNode) leftOp.operand).name;
        String hashVarName = "%" + varName;

        // Get the hash register - check lexical first, then global
        int hashReg;
        if (hasVariable(hashVarName)) {
            // Lexical hash
            hashReg = getVariableRegister(hashVarName);
        } else {
            // Global hash - load it
            hashReg = allocateRegister();
            String globalHashName = NameNormalizer.normalizeVariableName(
                varName,
                getCurrentPackage()
            );
            int nameIdx = addToStringPool(globalHashName);
            emit(Opcodes.LOAD_GLOBAL_HASH);
            emitReg(hashReg);
            emit(nameIdx);
        }

        // Compile the key expression (right side)
        if (!(node.right instanceof HashLiteralNode)) {
            throwCompilerException("Hash subscript requires HashLiteralNode");
            return;
        }
        HashLiteralNode keyNode = (HashLiteralNode) node.right;
        if (keyNode.elements.isEmpty()) {
            throwCompilerException("Hash subscript requires at least one key");
            return;
        }

        // Handle single element access: $hash{key}
        if (keyNode.elements.size() == 1) {
            Node keyExpr = keyNode.elements.get(0);

            // Check if it's a bareword (IdentifierNode) - autoquote it
            if (keyExpr instanceof IdentifierNode) {
                String keyString = ((IdentifierNode) keyExpr).name;
                int keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);

                // Emit HASH_GET opcode
                int rd = allocateRegister();
                emit(Opcodes.HASH_GET);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            } else {
                // Expression key - compile it
                keyExpr.accept(this);
                int keyReg = lastResultReg;

                // Emit HASH_GET opcode
                int rd = allocateRegister();
                emit(Opcodes.HASH_GET);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            }
        } else {
            throwCompilerException("Multi-element hash access not yet implemented");
        }
    }

    private void handleHashSlice(BinaryOperatorNode node, OperatorNode leftOp) {
        int hashReg;

        // Hash slice: @hash{'key1', 'key2'} returns array of values
        // Hashref slice: @$hashref{'key1', 'key2'} dereferences then slices

        if (leftOp.operand instanceof IdentifierNode) {
            // Direct hash slice: @hash{keys}
            String varName = ((IdentifierNode) leftOp.operand).name;
            String hashVarName = "%" + varName;

            // Get the hash - check lexical first, then global
            if (hasVariable(hashVarName)) {
                // Lexical hash
                hashReg = getVariableRegister(hashVarName);
            } else {
                // Global hash - load it
                hashReg = allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(
                    varName,
                    getCurrentPackage()
                );
                int nameIdx = addToStringPool(globalHashName);
                emit(Opcodes.LOAD_GLOBAL_HASH);
                emitReg(hashReg);
                emit(nameIdx);
            }
        } else if (leftOp.operand instanceof OperatorNode) {
            // Hashref slice: @$hashref{keys}
            // Compile the reference and dereference it
            leftOp.operand.accept(this);
            int scalarRefReg = lastResultReg;

            // Dereference to get the hash
            hashReg = allocateRegister();
            emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
            emitReg(hashReg);
            emitReg(scalarRefReg);
        } else {
            throwCompilerException("Hash slice requires hash variable or reference");
            return;
        }

        // Get the keys from HashLiteralNode
        if (!(node.right instanceof HashLiteralNode)) {
            throwCompilerException("Hash slice requires HashLiteralNode");
        }
        HashLiteralNode keysNode = (HashLiteralNode) node.right;
        if (keysNode.elements.isEmpty()) {
            throwCompilerException("Hash slice requires at least one key");
        }

        // Compile all keys into a list
        List<Integer> keyRegs = new ArrayList<>();
        for (Node keyElement : keysNode.elements) {
            if (keyElement instanceof IdentifierNode) {
                // Bareword key - autoquote
                String keyString = ((IdentifierNode) keyElement).name;
                int keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);
                keyRegs.add(keyReg);
            } else {
                // Expression key
                keyElement.accept(this);
                keyRegs.add(lastResultReg);
            }
        }

        // Create a RuntimeList from key registers
        int keysListReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(keysListReg);
        emit(keyRegs.size());
        for (int keyReg : keyRegs) {
            emitReg(keyReg);
        }

        // Emit direct opcode HASH_SLICE
        int rdSlice = allocateRegister();
        emit(Opcodes.HASH_SLICE);
        emitReg(rdSlice);
        emitReg(hashReg);
        emitReg(keysListReg);

        lastResultReg = rdSlice;
    }

    /**
     * Handle compound assignment operators: +=, -=, *=, /=, %=
     * Example: $sum += $elem means $sum = $sum + $elem
     */
    private void handleCompoundAssignment(BinaryOperatorNode node) {
        String op = node.operator;

        // Get the left operand register (the variable being assigned to)
        // The left side must be a variable reference
        if (!(node.left instanceof OperatorNode)) {
            throwCompilerException("Compound assignment requires variable on left side");
            return;
        }

        OperatorNode leftOp = (OperatorNode) node.left;
        if (!leftOp.operator.equals("$")) {
            throwCompilerException("Compound assignment currently only supports scalar variables");
            return;
        }

        if (!(leftOp.operand instanceof IdentifierNode)) {
            throwCompilerException("Compound assignment requires simple variable");
            return;
        }

        String varName = "$" + ((IdentifierNode) leftOp.operand).name;

        // Get the variable's register
        if (!hasVariable(varName)) {
            throwCompilerException("Undefined variable: " + varName);
            return;
        }
        int targetReg = getVariableRegister(varName);

        // Compile the right operand (the value to add/subtract/etc.)
        node.right.accept(this);
        int valueReg = lastResultReg;

        // Emit the appropriate compound assignment opcode
        switch (op) {
            case "+=" -> emit(Opcodes.ADD_ASSIGN);
            case "-=" -> emit(Opcodes.SUBTRACT_ASSIGN);
            case "*=" -> emit(Opcodes.MULTIPLY_ASSIGN);
            case "/=" -> emit(Opcodes.DIVIDE_ASSIGN);
            case "%=" -> emit(Opcodes.MODULUS_ASSIGN);
            case ".=" -> emit(Opcodes.STRING_CONCAT_ASSIGN);
            default -> {
                throwCompilerException("Unknown compound assignment operator: " + op);
                return;
            }
        }

        emitReg(targetReg);
        emitReg(valueReg);

        // The result is stored in targetReg
        lastResultReg = targetReg;
    }

    /**
     * Handle general array access: expr[index]
     * Example: $matrix[1][0] where $matrix[1] returns an arrayref
     */
    private void handleGeneralArrayAccess(BinaryOperatorNode node) {
        // Compile the left side (the expression that should yield an array or arrayref)
        node.left.accept(this);
        int baseReg = lastResultReg;

        // Compile the index expression (right side)
        if (!(node.right instanceof ArrayLiteralNode)) {
            throwCompilerException("Array subscript requires ArrayLiteralNode");
            return;
        }
        ArrayLiteralNode indexNode = (ArrayLiteralNode) node.right;
        if (indexNode.elements.isEmpty()) {
            throwCompilerException("Array subscript requires at least one index");
            return;
        }

        // Handle single element access
        if (indexNode.elements.size() == 1) {
            Node indexExpr = indexNode.elements.get(0);
            indexExpr.accept(this);
            int indexReg = lastResultReg;

            // The base might be either:
            // 1. A RuntimeArray (from $array which was an array variable)
            // 2. A RuntimeScalar containing an arrayref (from $matrix[1])
            // We need to handle both cases. The ARRAY_GET opcode should handle
            // dereferencing if needed, or we can use a deref+get sequence.

            // For now, let's assume it's a scalar with arrayref and dereference it first
            int arrayReg = allocateRegister();
            emit(Opcodes.DEREF_ARRAY);
            emitReg(arrayReg);
            emitReg(baseReg);

            // Now get the element
            int rd = allocateRegister();
            emit(Opcodes.ARRAY_GET);
            emitReg(rd);
            emitReg(arrayReg);
            emitReg(indexReg);

            lastResultReg = rd;
        } else {
            throwCompilerException("Multi-element array access not yet implemented");
        }
    }

    /**
     * Handle general hash access: expr{key}
     * Example: $hash{outer}{inner} where $hash{outer} returns a hashref
     */
    private void handleGeneralHashAccess(BinaryOperatorNode node) {
        // Compile the left side (the expression that should yield a hash or hashref)
        node.left.accept(this);
        int baseReg = lastResultReg;

        // Compile the key expression (right side)
        if (!(node.right instanceof HashLiteralNode)) {
            throwCompilerException("Hash subscript requires HashLiteralNode");
            return;
        }
        HashLiteralNode keyNode = (HashLiteralNode) node.right;
        if (keyNode.elements.isEmpty()) {
            throwCompilerException("Hash subscript requires at least one key");
            return;
        }

        // Handle single element access
        if (keyNode.elements.size() == 1) {
            Node keyExpr = keyNode.elements.get(0);

            // Compile the key
            int keyReg;
            if (keyExpr instanceof IdentifierNode) {
                // Bareword key - autoquote it
                String keyString = ((IdentifierNode) keyExpr).name;
                keyReg = allocateRegister();
                int keyIdx = addToStringPool(keyString);
                emit(Opcodes.LOAD_STRING);
                emitReg(keyReg);
                emit(keyIdx);
            } else {
                // Expression key - compile it
                keyExpr.accept(this);
                keyReg = lastResultReg;
            }

            // The base might be either:
            // 1. A RuntimeHash (from %hash which was a hash variable)
            // 2. A RuntimeScalar containing a hashref (from $hash{outer})
            // We need to handle both cases. Dereference if needed.

            // For now, let's assume it's a scalar with hashref and dereference it first
            int hashReg = allocateRegister();
            emit(Opcodes.DEREF_HASH);
            emitReg(hashReg);
            emitReg(baseReg);

            // Now get the element
            int rd = allocateRegister();
            emit(Opcodes.HASH_GET);
            emitReg(rd);
            emitReg(hashReg);
            emitReg(keyReg);

            lastResultReg = rd;
        } else {
            throwCompilerException("Multi-element hash access not yet implemented");
        }
    }

    /**
     * Handle push/unshift operators: push @array, values or unshift @array, values
     * Example: push @arr, 1, 2, 3
     */
    private void handlePushUnshift(BinaryOperatorNode node) {
        boolean isPush = node.operator.equals("push");

        // Left operand is the array (@array or @$arrayref)
        // Right operand is the list of values to push/unshift

        // Get the array
        int arrayReg;
        if (node.left instanceof OperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;

            if (leftOp.operator.equals("@") && leftOp.operand instanceof IdentifierNode) {
                // Direct array: @array
                String varName = ((IdentifierNode) leftOp.operand).name;
                String arrayVarName = "@" + varName;

                // Get array register - check lexical first, then global
                if (hasVariable(arrayVarName)) {
                    arrayReg = getVariableRegister(arrayVarName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                        varName,
                        getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else if (leftOp.operator.equals("@") && leftOp.operand instanceof OperatorNode) {
                // Array dereference: @$arrayref
                OperatorNode derefOp = (OperatorNode) leftOp.operand;
                if (derefOp.operator.equals("$")) {
                    // Compile the scalar reference
                    derefOp.accept(this);
                    int refReg = lastResultReg;

                    // Dereference to get the array
                    arrayReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                } else {
                    throwCompilerException("push/unshift requires array or array reference");
                    return;
                }
            } else {
                throwCompilerException("push/unshift requires array or array reference");
                return;
            }
        } else {
            throwCompilerException("push/unshift requires array operand");
            return;
        }

        // Compile the values to push/unshift (right operand) in list context
        int savedContext = currentCallContext;
        currentCallContext = RuntimeContextType.LIST;
        node.right.accept(this);
        int valuesReg = lastResultReg;
        currentCallContext = savedContext;

        // Emit ARRAY_PUSH or ARRAY_UNSHIFT opcode
        if (isPush) {
            emit(Opcodes.ARRAY_PUSH);
        } else {
            emit(Opcodes.ARRAY_UNSHIFT);
        }
        emitReg(arrayReg);
        emitReg(valuesReg);

        // push/unshift return the new array size
        lastResultReg = -1;  // No result register needed
    }

    /**
     * Helper method to compile assignment operators (=).
     * Extracted from visit(BinaryOperatorNode) to reduce method size.
     * Handles all forms of assignment including my/our/local, scalars, arrays, hashes, and slices.
     */
    private void compileAssignmentOperator(BinaryOperatorNode node) {
        // Determine the calling context for the RHS based on LHS type
        int rhsContext = RuntimeContextType.LIST; // Default

        // Check if LHS is a scalar assignment (my $x = ... or our $x = ...)
        if (node.left instanceof OperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;
            if ((leftOp.operator.equals("my") || leftOp.operator.equals("our")) && leftOp.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) leftOp.operand;
                if (sigilOp.operator.equals("$")) {
                    // Scalar assignment: use SCALAR context for RHS
                    rhsContext = RuntimeContextType.SCALAR;
                }
            } else if (leftOp.operator.equals("$")) {
                // Regular scalar assignment: $x = ...
                rhsContext = RuntimeContextType.SCALAR;
            }
        }

        // Set the context for subroutine calls in RHS
        int savedContext = currentCallContext;
        try {
            currentCallContext = rhsContext;

        // Special case: my $x = value
        if (node.left instanceof OperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;
            if (leftOp.operator.equals("my")) {
                // Extract variable name from "my" operand
                Node myOperand = leftOp.operand;

                // Handle my $x (where $x is OperatorNode("$", IdentifierNode("x")))
                if (myOperand instanceof OperatorNode) {
                    OperatorNode sigilOp = (OperatorNode) myOperand;
                    if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                        String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if this variable is captured by named subs (Parser marks with id)
                        if (sigilOp.id != 0) {
                            // RETRIEVE the persistent variable (creates if doesn't exist)
                            int beginId = sigilOp.id;
                            int nameIdx = addToStringPool(varName);
                            int reg = allocateRegister();

                            emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                            emitReg(reg);
                            emit(nameIdx);
                            emit(beginId);

                            // Now register contains a reference to the persistent RuntimeScalar
                            // Store the initializer value INTO that RuntimeScalar
                            node.right.accept(this);
                            int valueReg = lastResultReg;

                            // Set the value in the persistent scalar using SET_SCALAR
                            // This calls .set() on the RuntimeScalar without overwriting the reference
                            emit(Opcodes.SET_SCALAR);
                            emitReg(reg);
                            emitReg(valueReg);

                            // Track this variable - map the name to the register we already allocated
                            variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                            lastResultReg = reg;
                            return;
                        }

                        // Regular lexical variable (not captured)
                        // Allocate register for new lexical variable and add to symbol table
                        int reg = addVariable(varName, "my");

                        // Compile RHS in the appropriate context
                        // @ operator will check currentCallContext and emit ARRAY_SIZE if needed
                        node.right.accept(this);
                        int valueReg = lastResultReg;

                        // Move to variable register
                        emit(Opcodes.MOVE);
                        emitReg(reg);
                        emitReg(valueReg);

                        lastResultReg = reg;
                        return;
                    } else if (sigilOp.operator.equals("@") && sigilOp.operand instanceof IdentifierNode) {
                        // Handle my @array = ...
                        String varName = "@" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if this variable is captured by named subs
                        if (sigilOp.id != 0) {
                            // RETRIEVE the persistent array
                            int beginId = sigilOp.id;
                            int nameIdx = addToStringPool(varName);
                            int arrayReg = allocateRegister();

                            emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                            emitReg(arrayReg);
                            emit(nameIdx);
                            emit(beginId);

                            // Compile RHS (should evaluate to a list)
                            node.right.accept(this);
                            int listReg = lastResultReg;

                            // Populate array from list
                            emit(Opcodes.ARRAY_SET_FROM_LIST);
                            emitReg(arrayReg);
                            emitReg(listReg);

                            // Track this variable - map the name to the register we already allocated
                            variableScopes.peek().put(varName, arrayReg);
                            allDeclaredVariables.put(varName, arrayReg);  // Track for variableRegistry
                            lastResultReg = arrayReg;
                            return;
                        }

                        // Regular lexical array (not captured)
                        // Allocate register for new lexical array and add to symbol table
                        int arrayReg = addVariable(varName, "my");

                        // Create empty array
                        emit(Opcodes.NEW_ARRAY);
                        emitReg(arrayReg);

                        // Compile RHS (should evaluate to a list)
                        node.right.accept(this);
                        int listReg = lastResultReg;

                        // Populate array from list using setFromList
                        emit(Opcodes.ARRAY_SET_FROM_LIST);
                        emitReg(arrayReg);
                        emitReg(listReg);

                        lastResultReg = arrayReg;
                        return;
                    } else if (sigilOp.operator.equals("%") && sigilOp.operand instanceof IdentifierNode) {
                        // Handle my %hash = ...
                        String varName = "%" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if this variable is captured by named subs
                        if (sigilOp.id != 0) {
                            // RETRIEVE the persistent hash
                            int beginId = sigilOp.id;
                            int nameIdx = addToStringPool(varName);
                            int hashReg = allocateRegister();

                            emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                            emitReg(hashReg);
                            emit(nameIdx);
                            emit(beginId);

                            // Compile RHS (should evaluate to a list)
                            node.right.accept(this);
                            int listReg = lastResultReg;

                            // Populate hash from list
                            emit(Opcodes.HASH_SET_FROM_LIST);
                            emitReg(hashReg);
                            emitReg(listReg);

                            // Track this variable - map the name to the register we already allocated
                            variableScopes.peek().put(varName, hashReg);
                            allDeclaredVariables.put(varName, hashReg);  // Track for variableRegistry
                            lastResultReg = hashReg;
                            return;
                        }

                        // Regular lexical hash (not captured)
                        // Allocate register for new lexical hash and add to symbol table
                        int hashReg = addVariable(varName, "my");

                        // Create empty hash
                        emit(Opcodes.NEW_HASH);
                        emitReg(hashReg);

                        // Compile RHS (should evaluate to a list)
                        node.right.accept(this);
                        int listReg = lastResultReg;

                        // Populate hash from list
                        emit(Opcodes.HASH_SET_FROM_LIST);
                        emitReg(hashReg);
                        emitReg(listReg);

                        lastResultReg = hashReg;
                        return;
                    }
                }

                // Handle my x (direct identifier without sigil)
                if (myOperand instanceof IdentifierNode) {
                    String varName = ((IdentifierNode) myOperand).name;

                    // Allocate register for new lexical variable and add to symbol table
                    int reg = addVariable(varName, "my");

                    // Compile RHS
                    node.right.accept(this);
                    int valueReg = lastResultReg;

                    // Move to variable register
                    emit(Opcodes.MOVE);
                    emitReg(reg);
                    emitReg(valueReg);

                    lastResultReg = reg;
                    return;
                }

                // Handle my ($x, $y, @rest) = ... - list declaration with assignment
                if (myOperand instanceof ListNode) {
                    ListNode listNode = (ListNode) myOperand;

                    // Compile RHS first
                    node.right.accept(this);
                    int listReg = lastResultReg;

                    // Convert to list if needed
                    int rhsListReg = allocateRegister();
                    emit(Opcodes.SCALAR_TO_LIST);
                    emitReg(rhsListReg);
                    emitReg(listReg);

                    // Declare and assign each variable
                    for (int i = 0; i < listNode.elements.size(); i++) {
                        Node element = listNode.elements.get(i);
                        if (element instanceof OperatorNode) {
                            OperatorNode sigilOp = (OperatorNode) element;
                            String sigil = sigilOp.operator;

                            if (sigilOp.operand instanceof IdentifierNode) {
                                String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                                int varReg;

                                // Check if this variable is captured by named subs (Parser marks with id)
                                if (sigilOp.id != 0) {
                                    // This variable is captured - use RETRIEVE_BEGIN to get persistent storage
                                    int beginId = sigilOp.id;
                                    int nameIdx = addToStringPool(varName);
                                    varReg = allocateRegister();

                                    switch (sigil) {
                                        case "$" -> {
                                            emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                            emitReg(varReg);
                                            emit(nameIdx);
                                            emit(beginId);
                                        }
                                        case "@" -> {
                                            emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                            emitReg(varReg);
                                            emit(nameIdx);
                                            emit(beginId);
                                        }
                                        case "%" -> {
                                            emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                            emitReg(varReg);
                                            emit(nameIdx);
                                            emit(beginId);
                                        }
                                    }

                                    // Track this variable
                                    variableScopes.peek().put(varName, varReg);
                            allDeclaredVariables.put(varName, varReg);  // Track for variableRegistry
                                } else {
                                    // Regular lexical variable (not captured)
                                    // Declare the variable
                                    varReg = addVariable(varName, "my");

                                    // Initialize based on sigil
                                    switch (sigil) {
                                        case "$" -> {
                                            emit(Opcodes.LOAD_UNDEF);
                                            emitReg(varReg);
                                        }
                                        case "@" -> {
                                            emit(Opcodes.NEW_ARRAY);
                                            emitReg(varReg);
                                        }
                                        case "%" -> {
                                            emit(Opcodes.NEW_HASH);
                                            emitReg(varReg);
                                        }
                                    }
                                }

                                // Get i-th element from RHS
                                int indexReg = allocateRegister();
                                emit(Opcodes.LOAD_INT);
                                emitReg(indexReg);
                                emitInt(i);

                                int elemReg = allocateRegister();
                                emit(Opcodes.ARRAY_GET);
                                emitReg(elemReg);
                                emitReg(rhsListReg);
                                emitReg(indexReg);

                                // Assign to variable
                                if (sigil.equals("$")) {
                                    if (sigilOp.id != 0) {
                                        // Captured variable - use SET_SCALAR to preserve aliasing
                                        emit(Opcodes.SET_SCALAR);
                                        emitReg(varReg);
                                        emitReg(elemReg);
                                    } else {
                                        // Regular variable - use MOVE
                                        emit(Opcodes.MOVE);
                                        emitReg(varReg);
                                        emitReg(elemReg);
                                    }
                                } else if (sigil.equals("@")) {
                                    emit(Opcodes.ARRAY_SET_FROM_LIST);
                                    emitReg(varReg);
                                    emitReg(elemReg);
                                } else if (sigil.equals("%")) {
                                    emit(Opcodes.HASH_SET_FROM_LIST);
                                    emitReg(varReg);
                                    emitReg(elemReg);
                                }
                            }
                        }
                    }

                    lastResultReg = rhsListReg;
                    return;
                }
            }

            // Special case: local $x = value
            if (leftOp.operator.equals("local")) {
                // Extract variable from "local" operand
                Node localOperand = leftOp.operand;

                // Handle local $hash{key} = value (localizing hash element)
                if (localOperand instanceof BinaryOperatorNode) {
                    BinaryOperatorNode hashAccess = (BinaryOperatorNode) localOperand;
                    if (hashAccess.operator.equals("{")) {
                        // Compile the hash access to get the hash element reference
                        // This returns a RuntimeScalar that is aliased to the hash slot
                        hashAccess.accept(this);
                        int elemReg = lastResultReg;

                        // Push this hash element to the local variable stack
                        emit(Opcodes.PUSH_LOCAL_VARIABLE);
                        emitReg(elemReg);

                        // Compile RHS
                        node.right.accept(this);
                        int valueReg = lastResultReg;

                        // Assign value to the hash element (which is already localized)
                        emit(Opcodes.SET_SCALAR);
                        emitReg(elemReg);
                        emitReg(valueReg);

                        lastResultReg = elemReg;
                        return;
                    }
                }

                // Handle local $x (where $x is OperatorNode("$", IdentifierNode("x")))
                if (localOperand instanceof OperatorNode) {
                    OperatorNode sigilOp = (OperatorNode) localOperand;
                    if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                        String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if it's a lexical variable (should not be localized)
                        if (hasVariable(varName)) {
                            throwCompilerException("Can't localize lexical variable " + varName);
                            return;
                        }

                        // Compile RHS first
                        node.right.accept(this);
                        int valueReg = lastResultReg;

                        // It's a global variable - call makeLocal which returns the localized scalar
                        String packageName = getCurrentPackage();
                        String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                        int nameIdx = addToStringPool(globalVarName);

                        int localReg = allocateRegister();
                        emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                        emitReg(localReg);
                        emit(nameIdx);

                        // Assign value to the localized scalar (not to the global!)
                        emit(Opcodes.SET_SCALAR);
                        emitReg(localReg);
                        emitReg(valueReg);

                        lastResultReg = localReg;
                        return;
                    } else if (sigilOp.operator.equals("@") && sigilOp.operand instanceof IdentifierNode) {
                        // Handle local @array = value
                        String varName = "@" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if it's a lexical variable (should not be localized)
                        if (hasVariable(varName)) {
                            throwCompilerException("Can't localize lexical variable " + varName);
                            return;
                        }

                        // Compile RHS first
                        node.right.accept(this);
                        int valueReg = lastResultReg;

                        // It's a global array - get it and push to local stack
                        String packageName = getCurrentPackage();
                        String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                        int nameIdx = addToStringPool(globalVarName);

                        int arrayReg = allocateRegister();
                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        emitReg(arrayReg);
                        emit(nameIdx);

                        // Push to local variable stack
                        emit(Opcodes.PUSH_LOCAL_VARIABLE);
                        emitReg(arrayReg);

                        // Populate array from list
                        emit(Opcodes.ARRAY_SET_FROM_LIST);
                        emitReg(arrayReg);
                        emitReg(valueReg);

                        lastResultReg = arrayReg;
                        return;
                    } else if (sigilOp.operator.equals("%") && sigilOp.operand instanceof IdentifierNode) {
                        // Handle local %hash = value
                        String varName = "%" + ((IdentifierNode) sigilOp.operand).name;

                        // Check if it's a lexical variable (should not be localized)
                        if (hasVariable(varName)) {
                            throwCompilerException("Can't localize lexical variable " + varName);
                            return;
                        }

                        // Compile RHS first
                        node.right.accept(this);
                        int valueReg = lastResultReg;

                        // It's a global hash - get it and push to local stack
                        String packageName = getCurrentPackage();
                        String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                        int nameIdx = addToStringPool(globalVarName);

                        int hashReg = allocateRegister();
                        emit(Opcodes.LOAD_GLOBAL_HASH);
                        emitReg(hashReg);
                        emit(nameIdx);

                        // Push to local variable stack
                        emit(Opcodes.PUSH_LOCAL_VARIABLE);
                        emitReg(hashReg);

                        // Populate hash from list
                        emit(Opcodes.HASH_SET_FROM_LIST);
                        emitReg(hashReg);
                        emitReg(valueReg);

                        lastResultReg = hashReg;
                        return;
                    }
                } else if (localOperand instanceof ListNode) {
                    // Handle local($x) = value or local($x, $y) = (v1, v2)
                    ListNode listNode = (ListNode) localOperand;

                    // Special case: single element list local($x) = value
                    if (listNode.elements.size() == 1) {
                        Node element = listNode.elements.get(0);
                        if (element instanceof OperatorNode) {
                            OperatorNode sigilOp = (OperatorNode) element;
                            if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                                String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                                // Check if it's a lexical variable (should not be localized)
                                if (hasVariable(varName)) {
                                    throwCompilerException("Can't localize lexical variable " + varName);
                                    return;
                                }

                                // Compile RHS first
                                node.right.accept(this);
                                int valueReg = lastResultReg;

                                // Get the global variable and localize it
                                String packageName = getCurrentPackage();
                                String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                                int nameIdx = addToStringPool(globalVarName);

                                int localReg = allocateRegister();
                                emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                emitReg(localReg);
                                emit(nameIdx);

                                // Assign value to the localized variable
                                emit(Opcodes.SET_SCALAR);
                                emitReg(localReg);
                                emitReg(valueReg);

                                lastResultReg = localReg;
                                return;
                            }
                        }
                    }

                    // Multi-element case: local($x, $y) = (v1, v2)
                    // Compile RHS first
                    node.right.accept(this);
                    int valueReg = lastResultReg;

                    // For each element in the list, localize and assign
                    for (int i = 0; i < listNode.elements.size(); i++) {
                        Node element = listNode.elements.get(i);

                        if (element instanceof OperatorNode) {
                            OperatorNode sigilOp = (OperatorNode) element;
                            if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                                String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                                // Check if it's a lexical variable (should not be localized)
                                if (hasVariable(varName)) {
                                    throwCompilerException("Can't localize lexical variable " + varName);
                                    return;
                                }

                                // Get the global variable
                                String packageName = getCurrentPackage();
                                String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                                int nameIdx = addToStringPool(globalVarName);

                                int localReg = allocateRegister();
                                emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                                emitReg(localReg);
                                emit(nameIdx);

                                // Extract element from RHS list
                                int elemReg = allocateRegister();
                                emit(Opcodes.ARRAY_GET);
                                emitReg(elemReg);
                                emitReg(valueReg);
                                emitInt(i);

                                // Assign to the localized variable
                                emit(Opcodes.SET_SCALAR);
                                emitReg(localReg);
                                emitReg(elemReg);

                                if (i == 0) {
                                    // Return the first localized variable
                                    lastResultReg = localReg;
                                }
                            }
                        }
                    }
                    return;
                }
            }
        }

        // Regular assignment: $x = value
        // OPTIMIZATION: Detect $x = $x + $y and emit ADD_ASSIGN instead of ADD_SCALAR + MOVE
        if (node.left instanceof OperatorNode && node.right instanceof BinaryOperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;
            BinaryOperatorNode rightBin = (BinaryOperatorNode) node.right;

            if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode &&
                rightBin.operator.equals("+") &&
                rightBin.left instanceof OperatorNode) {

                String leftVarName = "$" + ((IdentifierNode) leftOp.operand).name;
                OperatorNode rightLeftOp = (OperatorNode) rightBin.left;

                if (rightLeftOp.operator.equals("$") && rightLeftOp.operand instanceof IdentifierNode) {
                    String rightLeftVarName = "$" + ((IdentifierNode) rightLeftOp.operand).name;

                    // Pattern match: $x = $x + $y (emit ADD_ASSIGN)
                    // Skip optimization for captured variables (need SET_SCALAR)
                    boolean isCaptured = capturedVarIndices != null &&
                                        capturedVarIndices.containsKey(leftVarName);

                    if (leftVarName.equals(rightLeftVarName) && hasVariable(leftVarName) && !isCaptured) {
                        int targetReg = getVariableRegister(leftVarName);

                        // Compile RHS operand ($y)
                        rightBin.right.accept(this);
                        int rhsReg = lastResultReg;

                        // Emit ADD_ASSIGN instead of ADD_SCALAR + MOVE
                        emit(Opcodes.ADD_ASSIGN);
                        emitReg(targetReg);
                        emitReg(rhsReg);

                        lastResultReg = targetReg;
                        return;
                    }
                }
            }
        }

        // Regular assignment: $x = value (no optimization)
        // Compile RHS first
        node.right.accept(this);
        int valueReg = lastResultReg;

        // Assign to LHS
        if (node.left instanceof OperatorNode) {
            OperatorNode leftOp = (OperatorNode) node.left;
            if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                String varName = "$" + ((IdentifierNode) leftOp.operand).name;

                if (hasVariable(varName)) {
                    // Lexical variable - check if it's captured
                    int targetReg = getVariableRegister(varName);

                    if (capturedVarIndices != null && capturedVarIndices.containsKey(varName)) {
                        // Captured variable - use SET_SCALAR to preserve aliasing
                        emit(Opcodes.SET_SCALAR);
                        emitReg(targetReg);
                        emitReg(valueReg);
                    } else {
                        // Regular lexical - use MOVE
                        emit(Opcodes.MOVE);
                        emitReg(targetReg);
                        emitReg(valueReg);
                    }

                    lastResultReg = targetReg;
                } else {
                    // Global variable
                    // Strip sigil and normalize name (e.g., "$x" → "main::x")
                    String bareVarName = varName.substring(1);  // Remove sigil
                    String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
                    int nameIdx = addToStringPool(normalizedName);
                    emit(Opcodes.STORE_GLOBAL_SCALAR);
                    emit(nameIdx);
                    emitReg(valueReg);
                    lastResultReg = valueReg;
                }
            } else if (leftOp.operator.equals("@") && leftOp.operand instanceof IdentifierNode) {
                // Array assignment: @array = ...
                String varName = "@" + ((IdentifierNode) leftOp.operand).name;

                int arrayReg;
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }

                // Populate array from list using setFromList
                emit(Opcodes.ARRAY_SET_FROM_LIST);
                emitReg(arrayReg);
                emitReg(valueReg);

                lastResultReg = arrayReg;
            } else if (leftOp.operator.equals("%") && leftOp.operand instanceof IdentifierNode) {
                // Hash assignment: %hash = ...
                String varName = "%" + ((IdentifierNode) leftOp.operand).name;

                int hashReg;
                if (hasVariable(varName)) {
                    // Lexical hash
                    hashReg = getVariableRegister(varName);
                } else {
                    // Global hash - load it
                    hashReg = allocateRegister();
                    String globalHashName = NameNormalizer.normalizeVariableName(((IdentifierNode) leftOp.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalHashName);
                    emit(Opcodes.LOAD_GLOBAL_HASH);
                    emitReg(hashReg);
                    emit(nameIdx);
                }

                // Populate hash from list using setFromList
                emit(Opcodes.HASH_SET_FROM_LIST);
                emitReg(hashReg);
                emitReg(valueReg);

                lastResultReg = hashReg;
            } else if (leftOp.operator.equals("our")) {
                // Assignment to our variable: our $x = value or our @x = value or our %x = value
                // Compile the our declaration first (which loads the global into a register)
                leftOp.accept(this);
                int targetReg = lastResultReg;

                // Now assign the RHS value to the target register
                // The target register contains either a scalar, array, or hash
                // We need to determine which and use the appropriate assignment

                // Extract the sigil from our operand
                if (leftOp.operand instanceof OperatorNode) {
                    OperatorNode sigilOp = (OperatorNode) leftOp.operand;
                    String sigil = sigilOp.operator;

                    if (sigil.equals("$")) {
                        // Scalar: use SET_SCALAR to modify value without breaking alias
                        emit(Opcodes.SET_SCALAR);
                        emitReg(targetReg);
                        emitReg(valueReg);
                    } else if (sigil.equals("@")) {
                        // Array: use ARRAY_SET_FROM_LIST
                        emit(Opcodes.ARRAY_SET_FROM_LIST);
                        emitReg(targetReg);
                        emitReg(valueReg);
                    } else if (sigil.equals("%")) {
                        // Hash: use HASH_SET_FROM_LIST
                        emit(Opcodes.HASH_SET_FROM_LIST);
                        emitReg(targetReg);
                        emitReg(valueReg);
                    }
                } else if (leftOp.operand instanceof ListNode) {
                    // our ($a, $b) = ... - list declaration with assignment
                    // The our statement already declared the variables and returned a list
                    // We need to assign the RHS values to each variable
                    ListNode listNode = (ListNode) leftOp.operand;

                    // Convert RHS to list
                    int rhsListReg = allocateRegister();
                    emit(Opcodes.SCALAR_TO_LIST);
                    emitReg(rhsListReg);
                    emitReg(valueReg);

                    // Assign each element
                    for (int i = 0; i < listNode.elements.size(); i++) {
                        Node element = listNode.elements.get(i);
                        if (element instanceof OperatorNode) {
                            OperatorNode sigilOp = (OperatorNode) element;
                            String sigil = sigilOp.operator;

                            if (sigilOp.operand instanceof IdentifierNode) {
                                String varName = sigil + ((IdentifierNode) sigilOp.operand).name;
                                int varReg = getVariableRegister(varName);

                                // Get i-th element from RHS
                                int indexReg = allocateRegister();
                                emit(Opcodes.LOAD_INT);
                                emitReg(indexReg);
                                emitInt(i);

                                int elemReg = allocateRegister();
                                emit(Opcodes.ARRAY_GET);
                                emitReg(elemReg);
                                emitReg(rhsListReg);
                                emitReg(indexReg);

                                // Assign to variable
                                if (sigil.equals("$")) {
                                    emit(Opcodes.MOVE);
                                    emitReg(varReg);
                                    emitReg(elemReg);
                                } else if (sigil.equals("@")) {
                                    emit(Opcodes.ARRAY_SET_FROM_LIST);
                                    emitReg(varReg);
                                    emitReg(elemReg);
                                } else if (sigil.equals("%")) {
                                    emit(Opcodes.HASH_SET_FROM_LIST);
                                    emitReg(varReg);
                                    emitReg(elemReg);
                                }
                            }
                        }
                    }
                    lastResultReg = valueReg;
                    currentCallContext = savedContext;
                    return;
                }

                lastResultReg = targetReg;
            } else if (leftOp.operator.equals("*") && leftOp.operand instanceof IdentifierNode) {
                // Typeglob assignment: *foo = value
                String varName = ((IdentifierNode) leftOp.operand).name;
                String globalName = NameNormalizer.normalizeVariableName(varName, getCurrentPackage());
                int nameIdx = addToStringPool(globalName);

                // Load the glob
                int globReg = allocateRegister();
                emit(Opcodes.LOAD_GLOB);
                emitReg(globReg);
                emit(nameIdx);

                // Store value to glob
                emit(Opcodes.STORE_GLOB);
                emitReg(globReg);
                emitReg(valueReg);

                lastResultReg = globReg;
            } else {
                throwCompilerException("Assignment to unsupported operator: " + leftOp.operator);
            }
        } else if (node.left instanceof IdentifierNode) {
            String varName = ((IdentifierNode) node.left).name;

            if (hasVariable(varName)) {
                // Lexical variable - copy to its register
                int targetReg = getVariableRegister(varName);
                emit(Opcodes.MOVE);
                emitReg(targetReg);
                emitReg(valueReg);
                lastResultReg = targetReg;
            } else {
                // Global variable (varName has no sigil here)
                String normalizedName = NameNormalizer.normalizeVariableName(varName, getCurrentPackage());
                int nameIdx = addToStringPool(normalizedName);
                emit(Opcodes.STORE_GLOBAL_SCALAR);
                emit(nameIdx);
                emitReg(valueReg);
                lastResultReg = valueReg;
            }
        } else if (node.left instanceof BinaryOperatorNode) {
            BinaryOperatorNode leftBin = (BinaryOperatorNode) node.left;

            // Handle array slice assignment: @array[1, 3, 5] = (20, 30, 40)
            if (leftBin.operator.equals("[") && leftBin.left instanceof OperatorNode) {
                OperatorNode arrayOp = (OperatorNode) leftBin.left;

                // Must be @array (not $array)
                if (arrayOp.operator.equals("@") && arrayOp.operand instanceof IdentifierNode) {
                    String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                    // Get the array register
                    int arrayReg;
                    if (hasVariable(varName)) {
                        // Lexical array
                        arrayReg = getVariableRegister(varName);
                    } else {
                        // Global array - load it
                        arrayReg = allocateRegister();
                        String globalArrayName = NameNormalizer.normalizeVariableName(
                            ((IdentifierNode) arrayOp.operand).name,
                            getCurrentPackage()
                        );
                        int nameIdx = addToStringPool(globalArrayName);
                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        emitReg(arrayReg);
                        emit(nameIdx);
                    }

                    // Compile indices (right side of [])
                    // ArrayLiteralNode contains the indices
                    if (!(leftBin.right instanceof ArrayLiteralNode)) {
                        throwCompilerException("Array slice assignment requires index list");
                    }

                    ArrayLiteralNode indicesNode = (ArrayLiteralNode) leftBin.right;
                    List<Integer> indexRegs = new ArrayList<>();
                    for (Node indexNode : indicesNode.elements) {
                        indexNode.accept(this);
                        indexRegs.add(lastResultReg);
                    }

                    // Create indices list
                    int indicesReg = allocateRegister();
                    emit(Opcodes.CREATE_LIST);
                    emitReg(indicesReg);
                    emit(indexRegs.size());
                    for (int indexReg : indexRegs) {
                        emitReg(indexReg);
                    }

                    // Compile values (RHS of assignment)
                    node.right.accept(this);
                    int valuesReg = lastResultReg;

                    // Emit direct opcode ARRAY_SLICE_SET
                    emit(Opcodes.ARRAY_SLICE_SET);
                    emitReg(arrayReg);
                    emitReg(indicesReg);
                    emitReg(valuesReg);

                    lastResultReg = arrayReg;
                    currentCallContext = savedContext;
                    return;
                }
            }

            // Handle single element array assignment
            // For: $array[index] = value or $matrix[3][0] = value
            if (leftBin.operator.equals("[")) {
                int arrayReg;

                // Check if left side is a variable or multidimensional access
                if (leftBin.left instanceof OperatorNode) {
                    OperatorNode arrayOp = (OperatorNode) leftBin.left;

                    // Single element assignment: $array[index] = value
                    if (arrayOp.operator.equals("$") && arrayOp.operand instanceof IdentifierNode) {
                        String varName = ((IdentifierNode) arrayOp.operand).name;
                        String arrayVarName = "@" + varName;

                        // Get the array register
                        if (hasVariable(arrayVarName)) {
                            // Lexical array
                            arrayReg = getVariableRegister(arrayVarName);
                        } else {
                            // Global array - load it
                            arrayReg = allocateRegister();
                            String globalArrayName = NameNormalizer.normalizeVariableName(
                                varName,
                                getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalArrayName);
                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            emitReg(arrayReg);
                            emit(nameIdx);
                        }
                    } else {
                        throwCompilerException("Assignment requires scalar dereference: $var[index]");
                        return;
                    }
                } else if (leftBin.left instanceof BinaryOperatorNode) {
                    // Multidimensional case: $matrix[3][0] = value
                    // Compile left side (which returns a scalar containing an array reference)
                    leftBin.left.accept(this);
                    int scalarReg = lastResultReg;

                    // Dereference the array reference to get the actual array
                    arrayReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(scalarReg);
                } else {
                    throwCompilerException("Array assignment requires variable or expression on left side");
                    return;
                }

                // Compile index expression
                if (!(leftBin.right instanceof ArrayLiteralNode)) {
                    throwCompilerException("Array assignment requires ArrayLiteralNode on right side");
                }
                ArrayLiteralNode indexNode = (ArrayLiteralNode) leftBin.right;
                if (indexNode.elements.isEmpty()) {
                    throwCompilerException("Array assignment requires index expression");
                }

                indexNode.elements.get(0).accept(this);
                int indexReg = lastResultReg;

                // Compile RHS value
                node.right.accept(this);
                int assignValueReg = lastResultReg;

                // Emit ARRAY_SET
                emit(Opcodes.ARRAY_SET);
                emitReg(arrayReg);
                emitReg(indexReg);
                emitReg(assignValueReg);

                lastResultReg = assignValueReg;
                currentCallContext = savedContext;
                return;
            } else if (leftBin.operator.equals("{")) {
                // Hash element/slice assignment
                // $hash{key} = value (scalar element)
                // @hash{keys} = values (slice)

                // 1. Get hash variable (leftBin.left)
                int hashReg;
                if (leftBin.left instanceof OperatorNode) {
                    OperatorNode hashOp = (OperatorNode) leftBin.left;

                    // Check for hash slice assignment: @hash{keys} = values
                    if (hashOp.operator.equals("@")) {
                        // Hash slice assignment
                        if (!(hashOp.operand instanceof IdentifierNode)) {
                            throwCompilerException("Hash slice assignment requires identifier");
                            return;
                        }
                        String varName = ((IdentifierNode) hashOp.operand).name;
                        String hashVarName = "%" + varName;

                        // Get the hash - check lexical first, then global
                        if (hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                varName,
                                getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalHashName);
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(hashReg);
                            emit(nameIdx);
                        }

                        // Get the keys from HashLiteralNode
                        if (!(leftBin.right instanceof HashLiteralNode)) {
                            throwCompilerException("Hash slice assignment requires HashLiteralNode");
                            return;
                        }
                        HashLiteralNode keysNode = (HashLiteralNode) leftBin.right;
                        if (keysNode.elements.isEmpty()) {
                            throwCompilerException("Hash slice assignment requires at least one key");
                            return;
                        }

                        // Compile all keys into a list
                        List<Integer> keyRegs = new ArrayList<>();
                        for (Node keyElement : keysNode.elements) {
                            if (keyElement instanceof IdentifierNode) {
                                // Bareword key - autoquote
                                String keyString = ((IdentifierNode) keyElement).name;
                                int keyReg = allocateRegister();
                                int keyIdx = addToStringPool(keyString);
                                emit(Opcodes.LOAD_STRING);
                                emitReg(keyReg);
                                emit(keyIdx);
                                keyRegs.add(keyReg);
                            } else {
                                // Expression key
                                keyElement.accept(this);
                                keyRegs.add(lastResultReg);
                            }
                        }

                        // Create a RuntimeList from key registers
                        int keysListReg = allocateRegister();
                        emit(Opcodes.CREATE_LIST);
                        emitReg(keysListReg);
                        emit(keyRegs.size());
                        for (int keyReg : keyRegs) {
                            emitReg(keyReg);
                        }

                        // Compile RHS values
                        node.right.accept(this);
                        int valuesReg = lastResultReg;

                        // Emit direct opcode HASH_SLICE_SET
                        emit(Opcodes.HASH_SLICE_SET);
                        emitReg(hashReg);
                        emitReg(keysListReg);
                        emitReg(valuesReg);

                        lastResultReg = valuesReg;
                        currentCallContext = savedContext;
                        return;
                    } else if (hashOp.operator.equals("$")) {
                        // $hash{key} - dereference to get hash
                        if (!(hashOp.operand instanceof IdentifierNode)) {
                            throwCompilerException("Hash assignment requires identifier");
                            return;
                        }
                        String varName = ((IdentifierNode) hashOp.operand).name;
                        String hashVarName = "%" + varName;

                        if (hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                varName,
                                getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalHashName);
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(hashReg);
                            emit(nameIdx);
                        }
                    } else {
                        throwCompilerException("Hash assignment requires scalar dereference: $var{key}");
                        return;
                    }
                } else if (leftBin.left instanceof BinaryOperatorNode) {
                    // Nested: $hash{outer}{inner} = value
                    // Compile left side (returns scalar containing hash reference or autovivifies)
                    leftBin.left.accept(this);
                    int scalarReg = lastResultReg;

                    // Dereference to get the hash (with autovivification)
                    hashReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                } else {
                    throwCompilerException("Hash assignment requires variable or expression on left side");
                    return;
                }

                // 2. Compile key expression
                if (!(leftBin.right instanceof HashLiteralNode)) {
                    throwCompilerException("Hash assignment requires HashLiteralNode on right side");
                    return;
                }
                HashLiteralNode keyNode = (HashLiteralNode) leftBin.right;
                if (keyNode.elements.isEmpty()) {
                    throwCompilerException("Hash key required for assignment");
                    return;
                }

                // Compile the key
                // Special case: IdentifierNode in hash access is autoquoted (bareword key)
                int keyReg;
                Node keyElement = keyNode.elements.get(0);
                if (keyElement instanceof IdentifierNode) {
                    // Bareword key: $hash{key} -> key is autoquoted to "key"
                    String keyString = ((IdentifierNode) keyElement).name;
                    keyReg = allocateRegister();
                    int keyIdx = addToStringPool(keyString);
                    emit(Opcodes.LOAD_STRING);
                    emitReg(keyReg);
                    emit(keyIdx);
                } else {
                    // Expression key: $hash{$var} or $hash{func()}
                    keyElement.accept(this);
                    keyReg = lastResultReg;
                }

                // 3. Compile RHS value
                node.right.accept(this);
                int hashValueReg = lastResultReg;

                // 4. Emit HASH_SET
                emit(Opcodes.HASH_SET);
                emitReg(hashReg);
                emitReg(keyReg);
                emitReg(hashValueReg);

                lastResultReg = hashValueReg;
                currentCallContext = savedContext;
                return;
            }

            // Handle lvalue subroutine: f() = value
            // When a function is called in lvalue context, it returns a RuntimeBaseProxy
            // that wraps a mutable reference. We can assign to it using SET_SCALAR.
            if (leftBin.operator.equals("(")) {
                // Call the function (which returns a RuntimeBaseProxy in lvalue context)
                node.left.accept(this);
                int lvalueReg = lastResultReg;

                // Compile RHS
                node.right.accept(this);
                int rhsReg = lastResultReg;

                // Assign to the lvalue using SET_SCALAR
                emit(Opcodes.SET_SCALAR);
                emitReg(lvalueReg);
                emitReg(rhsReg);

                lastResultReg = rhsReg;
                currentCallContext = savedContext;
                return;
            }

            throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
        } else if (node.left instanceof ListNode) {
            // List assignment: ($a, $b) = ... or () = ...
            // In scalar context, returns the number of elements on RHS
            // In list context, returns the RHS list
            ListNode listNode = (ListNode) node.left;

            // Compile RHS in LIST context to get all elements
            int savedRhsContext = currentCallContext;
            currentCallContext = RuntimeContextType.LIST;
            node.right.accept(this);
            int rhsReg = lastResultReg;
            currentCallContext = savedRhsContext;

            // Convert RHS to RuntimeList if needed
            int rhsListReg = allocateRegister();
            emit(Opcodes.SCALAR_TO_LIST);
            emitReg(rhsListReg);
            emitReg(rhsReg);

            // If the list is not empty, perform the assignment
            if (!listNode.elements.isEmpty()) {
                // Assign each RHS element to corresponding LHS variable
                for (int i = 0; i < listNode.elements.size(); i++) {
                    Node lhsElement = listNode.elements.get(i);

                    // Get the i-th element from RHS list
                    int indexReg = allocateRegister();
                    emit(Opcodes.LOAD_INT);
                    emitReg(indexReg);
                    emitInt(i);

                    int elementReg = allocateRegister();
                    emit(Opcodes.ARRAY_GET);
                    emitReg(elementReg);
                    emitReg(rhsListReg);
                    emitReg(indexReg);

                    // Assign to LHS element
                    if (lhsElement instanceof OperatorNode) {
                        OperatorNode lhsOp = (OperatorNode) lhsElement;
                        if (lhsOp.operator.equals("$") && lhsOp.operand instanceof IdentifierNode) {
                            String varName = "$" + ((IdentifierNode) lhsOp.operand).name;

                            if (hasVariable(varName)) {
                                int targetReg = getVariableRegister(varName);
                                if (capturedVarIndices != null && capturedVarIndices.containsKey(varName)) {
                                    emit(Opcodes.SET_SCALAR);
                                    emitReg(targetReg);
                                    emitReg(elementReg);
                                } else {
                                    emit(Opcodes.MOVE);
                                    emitReg(targetReg);
                                    emitReg(elementReg);
                                }
                            } else {
                                // Normalize global variable name (remove sigil, add package)
                                String bareVarName = varName.substring(1);  // Remove "$"
                                String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
                                int nameIdx = addToStringPool(normalizedName);
                                emit(Opcodes.STORE_GLOBAL_SCALAR);
                                emit(nameIdx);
                                emitReg(elementReg);
                            }
                        } else if (lhsOp.operator.equals("@") && lhsOp.operand instanceof IdentifierNode) {
                            // Array slurp: ($a, @rest) = ...
                            // Collect remaining elements into a RuntimeList
                            String varName = "@" + ((IdentifierNode) lhsOp.operand).name;

                            int arrayReg;
                            if (hasVariable(varName)) {
                                arrayReg = getVariableRegister(varName);
                            } else {
                                arrayReg = allocateRegister();
                                String globalArrayName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) lhsOp.operand).name,
                                    getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalArrayName);
                                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                emitReg(arrayReg);
                                emit(nameIdx);
                            }

                            // Create a list of remaining indices
                            // Use SLOWOP_LIST_SLICE_FROM to get list[i..]
                            int remainingListReg = allocateRegister();
                            emit(Opcodes.LIST_SLICE_FROM);
                            emitReg(remainingListReg);
                            emitReg(rhsListReg);
                            emitInt(i);  // Start index

                            // Populate array from remaining elements
                            emit(Opcodes.ARRAY_SET_FROM_LIST);
                            emitReg(arrayReg);
                            emitReg(remainingListReg);

                            // Array slurp consumes all remaining elements
                            break;
                        } else if (lhsOp.operator.equals("%") && lhsOp.operand instanceof IdentifierNode) {
                            // Hash slurp: ($a, %rest) = ...
                            String varName = "%" + ((IdentifierNode) lhsOp.operand).name;

                            int hashReg;
                            if (hasVariable(varName)) {
                                hashReg = getVariableRegister(varName);
                            } else {
                                hashReg = allocateRegister();
                                String globalHashName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) lhsOp.operand).name,
                                    getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalHashName);
                                emit(Opcodes.LOAD_GLOBAL_HASH);
                                emitReg(hashReg);
                                emit(nameIdx);
                            }

                            // Get remaining elements from list
                            int remainingListReg = allocateRegister();
                            emit(Opcodes.LIST_SLICE_FROM);
                            emitReg(remainingListReg);
                            emitReg(rhsListReg);
                            emitInt(i);  // Start index

                            // Populate hash from remaining elements
                            emit(Opcodes.HASH_SET_FROM_LIST);
                            emitReg(hashReg);
                            emitReg(remainingListReg);

                            // Hash slurp consumes all remaining elements
                            break;
                        }
                    }
                }
            }

            // Return value depends on savedContext (the context this assignment was called in)
            if (savedContext == RuntimeContextType.SCALAR) {
                // In scalar context, list assignment returns the count of RHS elements
                int countReg = allocateRegister();
                emit(Opcodes.ARRAY_SIZE);
                emitReg(countReg);
                emitReg(rhsListReg);
                lastResultReg = countReg;
            } else {
                // In list context, return the RHS value
                lastResultReg = rhsListReg;
            }

            currentCallContext = savedContext;
            return;
        } else {
            throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
        }

        } finally {
            // Always restore the calling context
            currentCallContext = savedContext;
        }
    }

    /**
     * Helper method to compile binary operator switch statement.
     * Extracted from visit(BinaryOperatorNode) to reduce method size.
     * Handles the giant switch statement for all binary operators.
     *
     * @param operator The binary operator string
     * @param rs1 Left operand register
     * @param rs2 Right operand register
     * @param tokenIndex Token index for error reporting
     * @return Result register containing the operation result
     */
    private int compileBinaryOperatorSwitch(String operator, int rs1, int rs2, int tokenIndex) {
        // Allocate result register
        int rd = allocateRegister();

        // Emit opcode based on operator
        switch (operator) {
            case "+" -> {
                emit(Opcodes.ADD_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "-" -> {
                emit(Opcodes.SUB_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "*" -> {
                emit(Opcodes.MUL_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "%" -> {
                emit(Opcodes.MOD_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "/" -> {
                emit(Opcodes.DIV_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "**" -> {
                emit(Opcodes.POW_SCALAR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "." -> {
                emit(Opcodes.CONCAT);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "x" -> {
                emit(Opcodes.REPEAT);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "<=>" -> {
                emit(Opcodes.COMPARE_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "cmp" -> {
                emit(Opcodes.COMPARE_STR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "bless" -> {
                // bless $ref, "Package" or bless $ref (defaults to current package)
                // rs1 = reference to bless
                // rs2 = package name (or undef for current package)
                emit(Opcodes.BLESS);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "isa" -> {
                // $obj isa "Package" - check if object is instance of package
                // rs1 = object/reference
                // rs2 = package name
                emit(Opcodes.ISA);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "==" -> {
                emit(Opcodes.EQ_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "<" -> {
                emit(Opcodes.LT_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case ">" -> {
                emit(Opcodes.GT_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "<=" -> {
                emit(Opcodes.LE_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case ">=" -> {
                emit(Opcodes.GE_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "!=" -> {
                emit(Opcodes.NE_NUM);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "eq" -> {
                // String equality: $a eq $b
                emit(Opcodes.EQ_STR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "ne" -> {
                // String inequality: $a ne $b
                emit(Opcodes.NE_STR);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "lt", "gt", "le", "ge" -> {
                // String comparisons using COMPARE_STR (like cmp)
                // cmp returns: -1 if $a lt $b, 0 if equal, 1 if $a gt $b
                int cmpReg = allocateRegister();
                emit(Opcodes.COMPARE_STR);
                emitReg(cmpReg);
                emitReg(rs1);
                emitReg(rs2);

                // Compare result to 0
                int zeroReg = allocateRegister();
                emit(Opcodes.LOAD_INT);
                emitReg(zeroReg);
                emitInt(0);

                // Emit appropriate comparison
                switch (operator) {
                    case "lt" -> emit(Opcodes.LT_NUM);  // cmp < 0
                    case "gt" -> emit(Opcodes.GT_NUM);  // cmp > 0
                    case "le" -> {
                        // le: cmp <= 0, which is !(cmp > 0)
                        int gtReg = allocateRegister();
                        emit(Opcodes.GT_NUM);
                        emitReg(gtReg);
                        emitReg(cmpReg);
                        emitReg(zeroReg);
                        emit(Opcodes.NOT);
                        emitReg(rd);
                        emitReg(gtReg);
                        return rd;
                    }
                    case "ge" -> {
                        // ge: cmp >= 0, which is !(cmp < 0)
                        int ltReg = allocateRegister();
                        emit(Opcodes.LT_NUM);
                        emitReg(ltReg);
                        emitReg(cmpReg);
                        emitReg(zeroReg);
                        emit(Opcodes.NOT);
                        emitReg(rd);
                        emitReg(ltReg);
                        return rd;
                    }
                }
                emitReg(rd);
                emitReg(cmpReg);
                emitReg(zeroReg);
            }
            case "(", "()" -> {
                // Apply operator: $coderef->(args) or &subname(args) or foo(args)
                // left (rs1) = code reference (RuntimeScalar containing RuntimeCode or SubroutineNode)
                // right (rs2) = arguments (should be RuntimeList from ListNode)

                // Note: rs2 should contain a RuntimeList (from visiting the ListNode)
                // We need to convert it to RuntimeArray for the CALL_SUB opcode

                // For now, rs2 is a RuntimeList - we'll pass it directly and let
                // BytecodeInterpreter convert it to RuntimeArray

                // Emit CALL_SUB: rd = coderef.apply(args, context)
                emit(Opcodes.CALL_SUB);
                emitReg(rd);  // Result register
                emitReg(rs1); // Code reference register
                emitReg(rs2); // Arguments register (RuntimeList to be converted to RuntimeArray)
                emit(currentCallContext); // Use current calling context

                // Note: CALL_SUB may return RuntimeControlFlowList
                // The interpreter will handle control flow propagation
            }
            case ".." -> {
                // Range operator: start..end
                // Create a PerlRange object which can be iterated or converted to a list

                // Optimization: if both operands are constant numbers, create range at compile time
                // (This optimization would need access to the original nodes, which we don't have here)
                // So we always use runtime range creation

                // Runtime range creation using RANGE opcode
                // rs1 and rs2 already contain the start and end values
                emit(Opcodes.RANGE);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
            }
            case "map" -> {
                // Map operator: map { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit MAP opcode
                emit(Opcodes.MAP);
                emitReg(rd);
                emitReg(rs2);       // List register
                emitReg(rs1);       // Closure register
                emit(RuntimeContextType.LIST);  // Map always uses list context
            }
            case "grep" -> {
                // Grep operator: grep { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit GREP opcode
                emit(Opcodes.GREP);
                emitReg(rd);
                emitReg(rs2);       // List register
                emitReg(rs1);       // Closure register
                emit(RuntimeContextType.LIST);  // Grep uses list context
            }
            case "sort" -> {
                // Sort operator: sort { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit SORT opcode
                emit(Opcodes.SORT);
                emitReg(rd);
                emitReg(rs2);       // List register
                emitReg(rs1);       // Closure register
                emitInt(addToStringPool(getCurrentPackage()));  // Package name for sort
            }
            case "split" -> {
                // Split operator: split pattern, string
                // rs1 = pattern (string or regex)
                // rs2 = list containing string to split (and optional limit)

                // Emit direct opcode SPLIT
                emit(Opcodes.SPLIT);
                emitReg(rd);
                emitReg(rs1);  // Pattern register
                emitReg(rs2);  // Args register
                emit(RuntimeContextType.LIST);  // Split uses list context
            }
            case "[" -> {
                // Array element access: $a[10] means get element 10 from array @a
                // Array slice: @a[1,2,3] or @a[1..3] means get multiple elements
                // Also handles multidimensional: $a[0][1] means $a[0]->[1]
                // This case should NOT be reached because array access is handled specially before this switch
                throwCompilerException("Array access [ should be handled before switch", tokenIndex);
            }
            case "{" -> {
                // Hash element access: $h{key} means get element 'key' from hash %h
                // Hash slice access: @h{keys} returns multiple values as array
                // This case should NOT be reached because hash access is handled specially before this switch
                throwCompilerException("Hash access { should be handled before switch", tokenIndex);
            }
            case "push" -> {
                // This should NOT be reached because push is handled specially before this switch
                throwCompilerException("push should be handled before switch", tokenIndex);
            }
            case "unshift" -> {
                // This should NOT be reached because unshift is handled specially before this switch
                throwCompilerException("unshift should be handled before switch", tokenIndex);
            }
            case "+=" -> {
                // This should NOT be reached because += is handled specially before this switch
                throwCompilerException("+= should be handled before switch", tokenIndex);
            }
            case "-=", "*=", "/=", "%=", ".=" -> {
                // This should NOT be reached because compound assignments are handled specially before this switch
                throwCompilerException(operator + " should be handled before switch", tokenIndex);
            }
            case "readline" -> {
                // <$fh> - read line from filehandle
                // rs1 = filehandle (or undef for ARGV)
                // rs2 = unused (ListNode)
                emit(Opcodes.READLINE);
                emitReg(rd);
                emitReg(rs1);
                emit(currentCallContext);
            }
            case "=~" -> {
                // $string =~ /pattern/ - regex match
                // rs1 = string to match against
                // rs2 = compiled regex pattern
                emit(Opcodes.MATCH_REGEX);
                emitReg(rd);
                emitReg(rs1);
                emitReg(rs2);
                emit(currentCallContext);
            }
            default -> throwCompilerException("Unsupported operator: " + operator, tokenIndex);
        }

        return rd;
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        // Track token index for error reporting
        currentTokenIndex = node.getIndex();

        // Handle print/say early (special handling for filehandle)
        if (node.operator.equals("print") || node.operator.equals("say")) {
            // print/say FILEHANDLE LIST
            // left = filehandle reference (\*STDERR)
            // right = list to print

            // Compile the filehandle (left operand)
            node.left.accept(this);
            int filehandleReg = lastResultReg;

            // Compile the content (right operand)
            node.right.accept(this);
            int contentReg = lastResultReg;

            // Emit PRINT or SAY with both registers
            emit(node.operator.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
            emitReg(contentReg);
            emitReg(filehandleReg);

            // print/say return 1 on success
            int rd = allocateRegister();
            emit(Opcodes.LOAD_INT);
            emitReg(rd);
            emitInt(1);

            lastResultReg = rd;
            return;
        }

        // Handle compound assignment operators (+=, -=, *=, /=, %=, .=)
        if (node.operator.equals("+=") || node.operator.equals("-=") ||
            node.operator.equals("*=") || node.operator.equals("/=") ||
            node.operator.equals("%=") || node.operator.equals(".=")) {
            handleCompoundAssignment(node);
            return;
        }

        // Handle assignment separately (doesn't follow standard left-right-op pattern)
        if (node.operator.equals("=")) {
            compileAssignmentOperator(node);
            return;
        }


        // Handle -> operator specially for hashref/arrayref dereference
        if (node.operator.equals("->")) {
            currentTokenIndex = node.getIndex();  // Track token for error reporting

            if (node.right instanceof HashLiteralNode) {
                // Hashref dereference: $ref->{key}
                // left: scalar containing hash reference
                // right: HashLiteralNode containing key

                // Compile the reference (left side)
                node.left.accept(this);
                int scalarRefReg = lastResultReg;

                // Dereference the scalar to get the actual hash
                int hashReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                emitReg(hashReg);
                emitReg(scalarRefReg);

                // Get the key
                HashLiteralNode keyNode = (HashLiteralNode) node.right;
                if (keyNode.elements.isEmpty()) {
                    throwCompilerException("Hash dereference requires key");
                }

                // Compile the key - handle bareword autoquoting
                int keyReg;
                Node keyElement = keyNode.elements.get(0);
                if (keyElement instanceof IdentifierNode) {
                    // Bareword key: $ref->{key} -> key is autoquoted
                    String keyString = ((IdentifierNode) keyElement).name;
                    keyReg = allocateRegister();
                    int keyIdx = addToStringPool(keyString);
                    emit(Opcodes.LOAD_STRING);
                    emitReg(keyReg);
                    emit(keyIdx);
                } else {
                    // Expression key: $ref->{$var}
                    keyElement.accept(this);
                    keyReg = lastResultReg;
                }

                // Access hash element
                int rd = allocateRegister();
                emit(Opcodes.HASH_GET);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
                return;
            } else if (node.right instanceof ArrayLiteralNode) {
                // Arrayref dereference: $ref->[index]
                // left: scalar containing array reference
                // right: ArrayLiteralNode containing index

                // Compile the reference (left side)
                node.left.accept(this);
                int scalarRefReg = lastResultReg;

                // Dereference the scalar to get the actual array
                int arrayReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(scalarRefReg);

                // Get the index
                ArrayLiteralNode indexNode = (ArrayLiteralNode) node.right;
                if (indexNode.elements.isEmpty()) {
                    throwCompilerException("Array dereference requires index");
                }

                // Compile the index expression
                indexNode.elements.get(0).accept(this);
                int indexReg = lastResultReg;

                // Access array element
                int rd = allocateRegister();
                emit(Opcodes.ARRAY_GET);
                emitReg(rd);
                emitReg(arrayReg);
                emitReg(indexReg);

                lastResultReg = rd;
                return;
            }
            // Code reference call: $code->() or $code->(@args)
            // right is ListNode with arguments
            else if (node.right instanceof ListNode) {
                // This is a code reference call: $coderef->(args)
                // Compile the code reference in scalar context
                int savedContext = currentCallContext;
                currentCallContext = RuntimeContextType.SCALAR;
                node.left.accept(this);
                int coderefReg = lastResultReg;

                // Compile arguments in list context
                currentCallContext = RuntimeContextType.LIST;
                node.right.accept(this);
                int argsReg = lastResultReg;
                currentCallContext = savedContext;

                // Allocate result register
                int rd = allocateRegister();

                // Emit CALL_SUB opcode
                emit(Opcodes.CALL_SUB);
                emitReg(rd);
                emitReg(coderefReg);
                emitReg(argsReg);
                emit(currentCallContext);

                lastResultReg = rd;
                return;
            }
            // Method call: ->method() or ->$method()
            // right is BinaryOperatorNode with operator "("
            else if (node.right instanceof BinaryOperatorNode) {
                BinaryOperatorNode rightCall = (BinaryOperatorNode) node.right;
                if (rightCall.operator.equals("(")) {
                    // object.call(method, arguments, context)
                    Node invocantNode = node.left;
                    Node methodNode = rightCall.left;
                    Node argsNode = rightCall.right;

                    // Convert class name to string if needed: Class->method()
                    if (invocantNode instanceof IdentifierNode) {
                        String className = ((IdentifierNode) invocantNode).name;
                        invocantNode = new StringNode(className, ((IdentifierNode) invocantNode).getIndex());
                    }

                    // Convert method name to string if needed
                    if (methodNode instanceof OperatorNode) {
                        OperatorNode methodOp = (OperatorNode) methodNode;
                        // &method is introduced by parser if method is predeclared
                        if (methodOp.operator.equals("&")) {
                            methodNode = methodOp.operand;
                        }
                    }
                    if (methodNode instanceof IdentifierNode) {
                        String methodName = ((IdentifierNode) methodNode).name;
                        methodNode = new StringNode(methodName, ((IdentifierNode) methodNode).getIndex());
                    }

                    // Compile invocant in scalar context
                    int savedContext = currentCallContext;
                    currentCallContext = RuntimeContextType.SCALAR;
                    invocantNode.accept(this);
                    int invocantReg = lastResultReg;

                    // Compile method name in scalar context
                    methodNode.accept(this);
                    int methodReg = lastResultReg;

                    // Get currentSub (__SUB__ for SUPER:: resolution)
                    int currentSubReg = allocateRegister();
                    emit(Opcodes.LOAD_GLOBAL_CODE);
                    emitReg(currentSubReg);
                    int subIdx = addToStringPool("__SUB__");
                    emit(subIdx);

                    // Compile arguments in list context
                    currentCallContext = RuntimeContextType.LIST;
                    argsNode.accept(this);
                    int argsReg = lastResultReg;
                    currentCallContext = savedContext;

                    // Allocate result register
                    int rd = allocateRegister();

                    // Emit CALL_METHOD
                    emit(Opcodes.CALL_METHOD);
                    emitReg(rd);
                    emitReg(invocantReg);
                    emitReg(methodReg);
                    emitReg(currentSubReg);
                    emitReg(argsReg);
                    emit(currentCallContext);

                    lastResultReg = rd;
                    return;
                }
            }
            // Otherwise, fall through to normal -> handling (method call)
        }

        // Handle [] operator for array access
        // Must be before automatic operand compilation to handle array slices
        if (node.operator.equals("[")) {
            currentTokenIndex = node.getIndex();

            // Check if this is an array slice: @array[indices]
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("@")) {
                    // This is an array slice - handle it specially
                    handleArraySlice(node, leftOp);
                    return;
                }

                // Handle normal array element access: $array[index]
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    handleArrayElementAccess(node, leftOp);
                    return;
                }
            }

            // Handle general case: expr[index]
            // This covers cases like $matrix[1][0] where $matrix[1] is an expression
            handleGeneralArrayAccess(node);
            return;
        }

        // Handle {} operator specially for hash slice operations
        // Must be before automatic operand compilation to avoid compiling @ operator
        if (node.operator.equals("{")) {
            currentTokenIndex = node.getIndex();

            // Check if this is a hash slice: @hash{keys} or @$hashref{keys}
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("@")) {
                    // This is a hash slice - handle it specially
                    handleHashSlice(node, leftOp);
                    return;
                }

                // Handle normal hash element access: $hash{key}
                if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                    handleHashElementAccess(node, leftOp);
                    return;
                }
            }

            // Handle general case: expr{key}
            // This covers cases like $hash{outer}{inner} where $hash{outer} is an expression
            handleGeneralHashAccess(node);
            return;
        }

        // Handle push/unshift operators
        if (node.operator.equals("push") || node.operator.equals("unshift")) {
            handlePushUnshift(node);
            return;
        }

        // Handle "join" operator specially to ensure proper context
        // Left operand (separator) needs SCALAR context, right operand (list) needs LIST context
        if (node.operator.equals("join")) {
            // Save and set context for left operand (separator)
            int savedContext = currentCallContext;
            currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(this);
            int rs1 = lastResultReg;

            // Set context for right operand (array/list)
            currentCallContext = RuntimeContextType.LIST;
            node.right.accept(this);
            int rs2 = lastResultReg;
            currentCallContext = savedContext;

            // Emit JOIN opcode
            int rd = allocateRegister();
            emit(Opcodes.JOIN);
            emitReg(rd);
            emitReg(rs1);
            emitReg(rs2);

            lastResultReg = rd;
            return;
        }

        // Handle function call operators specially to ensure arguments are in LIST context
        if (node.operator.equals("(") || node.operator.equals("()")) {
            // Function call: subname(args) or $coderef->(args)
            // Save and set context for left operand (code reference)
            int savedContext = currentCallContext;
            currentCallContext = RuntimeContextType.SCALAR;
            node.left.accept(this);
            int rs1 = lastResultReg;

            // Arguments must ALWAYS be evaluated in LIST context
            // Even if the call itself is in SCALAR context (e.g., scalar(func()))
            currentCallContext = RuntimeContextType.LIST;
            node.right.accept(this);
            int rs2 = lastResultReg;
            currentCallContext = savedContext;

            // Emit CALL_SUB opcode
            int rd = compileBinaryOperatorSwitch(node.operator, rs1, rs2, node.getIndex());
            lastResultReg = rd;
            return;
        }

        // Handle short-circuit operators specially - don't compile right operand yet!
        if (node.operator.equals("&&") || node.operator.equals("and")) {
            // Logical AND with short-circuit evaluation
            // Only evaluate right side if left side is true

            // Compile left operand
            node.left.accept(this);
            int rs1 = lastResultReg;

            // Allocate result register and move left value to it
            int rd = allocateRegister();
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs1);

            // Mark position for forward jump
            int skipRightPos = bytecode.size();

            // Emit conditional jump: if (!rd) skip right evaluation
            emit(Opcodes.GOTO_IF_FALSE);
            emitReg(rd);
            emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was true)
            node.right.accept(this);
            int rs2 = lastResultReg;

            // Move right result to rd (overwriting left value)
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecode.size();
            patchIntOffset(skipRightPos + 2, skipRightTarget);

            lastResultReg = rd;
            return;
        }

        if (node.operator.equals("||") || node.operator.equals("or")) {
            // Logical OR with short-circuit evaluation
            // Only evaluate right side if left side is false

            // Compile left operand
            node.left.accept(this);
            int rs1 = lastResultReg;

            // Allocate result register and move left value to it
            int rd = allocateRegister();
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs1);

            // Mark position for forward jump
            int skipRightPos = bytecode.size();

            // Emit conditional jump: if (rd) skip right evaluation
            emit(Opcodes.GOTO_IF_TRUE);
            emitReg(rd);
            emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was false)
            node.right.accept(this);
            int rs2 = lastResultReg;

            // Move right result to rd (overwriting left value)
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecode.size();
            patchIntOffset(skipRightPos + 2, skipRightTarget);

            lastResultReg = rd;
            return;
        }

        if (node.operator.equals("//")) {
            // Defined-OR with short-circuit evaluation
            // Only evaluate right side if left side is undefined

            // Compile left operand
            node.left.accept(this);
            int rs1 = lastResultReg;

            // Allocate result register and move left value to it
            int rd = allocateRegister();
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs1);

            // Check if left is defined
            int definedReg = allocateRegister();
            emit(Opcodes.DEFINED);
            emitReg(definedReg);
            emitReg(rd);

            // Mark position for forward jump
            int skipRightPos = bytecode.size();

            // Emit conditional jump: if (defined) skip right evaluation
            emit(Opcodes.GOTO_IF_TRUE);
            emitReg(definedReg);
            emitInt(0); // Placeholder for offset (will be patched)

            // NOW compile right operand (only executed if left was undefined)
            node.right.accept(this);
            int rs2 = lastResultReg;

            // Move right result to rd (overwriting left value)
            emit(Opcodes.MOVE);
            emitReg(rd);
            emitReg(rs2);

            // Patch the forward jump offset
            int skipRightTarget = bytecode.size();
            patchIntOffset(skipRightPos + 2, skipRightTarget);

            lastResultReg = rd;
            return;
        }

        // Compile left and right operands (for non-short-circuit operators)
        node.left.accept(this);
        int rs1 = lastResultReg;

        node.right.accept(this);
        int rs2 = lastResultReg;

        // Emit opcode based on operator (delegated to helper method)
        int rd = compileBinaryOperatorSwitch(node.operator, rs1, rs2, node.getIndex());


        lastResultReg = rd;
    }

    /**
     * Compile variable declaration operators (my, our, local).
     * Extracted to reduce visit(OperatorNode) bytecode size.
     */
    private void compileVariableDeclaration(OperatorNode node, String op) {
        if (op.equals("my")) {
            // my $x / my @x / my %x - variable declaration
            // The operand will be OperatorNode("$"/"@"/"%", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) node.operand;
                String sigil = sigilOp.operator;

                if (sigilOp.operand instanceof IdentifierNode) {
                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                    // Check if this variable is captured by closures (sigilOp.id != 0)
                    if (sigilOp.id != 0) {
                        // Variable is captured by compiled named subs
                        // Store as persistent variable so both interpreted and compiled code can access it
                        // Don't use a local register; instead load/store through persistent globals

                        // For now, retrieve the persistent variable and store in register
                        // This handles BEGIN-initialized variables
                        int reg = allocateRegister();
                        int nameIdx = addToStringPool(varName);

                        switch (sigil) {
                            case "$" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                // Track this as a captured variable - map to the register we allocated
                                variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                            }
                            case "@" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                            }
                            case "%" -> {
                                emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                emitReg(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                            }
                            default -> throwCompilerException("Unsupported variable type: " + sigil);
                        }

                        lastResultReg = reg;
                        return;
                    }

                    // Regular lexical variable (not captured)
                    int reg = addVariable(varName, "my");

                    // Normal initialization: load undef/empty array/empty hash
                    switch (sigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_UNDEF);
                            emitReg(reg);
                        }
                        case "@" -> {
                            emit(Opcodes.NEW_ARRAY);
                            emitReg(reg);
                        }
                        case "%" -> {
                            emit(Opcodes.NEW_HASH);
                            emitReg(reg);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    lastResultReg = reg;
                    return;
                }
            } else if (node.operand instanceof ListNode) {
                // my ($x, $y, @rest) - list of variable declarations
                ListNode listNode = (ListNode) node.operand;
                List<Integer> varRegs = new ArrayList<>();

                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode) {
                        OperatorNode sigilOp = (OperatorNode) element;
                        String sigil = sigilOp.operator;

                        if (sigilOp.operand instanceof IdentifierNode) {
                            String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                            // Check if this variable is captured by closures
                            if (sigilOp.id != 0) {
                                // Variable is captured - use persistent storage
                                int reg = allocateRegister();
                                int nameIdx = addToStringPool(varName);

                                switch (sigil) {
                                    case "$" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(sigilOp.id);
                                        variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                                    }
                                    case "@" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(sigilOp.id);
                                        variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                                    }
                                    case "%" -> {
                                        emitWithToken(Opcodes.RETRIEVE_BEGIN_HASH, node.getIndex());
                                        emitReg(reg);
                                        emit(nameIdx);
                                        emit(sigilOp.id);
                                        variableScopes.peek().put(varName, reg);
                            allDeclaredVariables.put(varName, reg);  // Track for variableRegistry
                                    }
                                    default -> throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }

                                varRegs.add(reg);
                            } else {
                                // Regular lexical variable
                                int reg = addVariable(varName, "my");

                                // Initialize the variable
                                switch (sigil) {
                                    case "$" -> {
                                        emit(Opcodes.LOAD_UNDEF);
                                        emitReg(reg);
                                    }
                                    case "@" -> {
                                        emit(Opcodes.NEW_ARRAY);
                                        emitReg(reg);
                                    }
                                    case "%" -> {
                                        emit(Opcodes.NEW_HASH);
                                        emitReg(reg);
                                    }
                                    default -> throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }

                                varRegs.add(reg);
                            }
                        } else {
                            throwCompilerException("my list declaration requires identifier: " + sigilOp.operand.getClass().getSimpleName());
                        }
                    } else {
                        throwCompilerException("my list declaration requires scalar/array/hash: " + element.getClass().getSimpleName());
                    }
                }

                // Return a list of the declared variables
                int resultReg = allocateRegister();
                emit(Opcodes.CREATE_LIST);
                emitReg(resultReg);
                emit(varRegs.size());
                for (int varReg : varRegs) {
                    emitReg(varReg);
                }

                lastResultReg = resultReg;
                return;
            }
            throwCompilerException("Unsupported my operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("our")) {
            // our $x / our @x / our %x - package variable declaration
            // The operand will be OperatorNode("$"/"@"/"%", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) node.operand;
                String sigil = sigilOp.operator;

                if (sigilOp.operand instanceof IdentifierNode) {
                    String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                    // Check if already declared in current scope
                    if (hasVariable(varName)) {
                        // Already declared, just return the existing register
                        lastResultReg = getVariableRegister(varName);
                        return;
                    }

                    // Allocate register and add to symbol table
                    int reg = addVariable(varName, "our");

                    // Load from global variable using normalized name
                    String globalVarName = NameNormalizer.normalizeVariableName(
                        ((IdentifierNode) sigilOp.operand).name,
                        getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalVarName);

                    switch (sigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        case "@" -> {
                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        case "%" -> {
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(reg);
                            emit(nameIdx);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    lastResultReg = reg;
                    return;
                }
            } else if (node.operand instanceof ListNode) {
                // our ($x, $y) - list of package variable declarations
                ListNode listNode = (ListNode) node.operand;
                List<Integer> varRegs = new ArrayList<>();

                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode) {
                        OperatorNode sigilOp = (OperatorNode) element;
                        String sigil = sigilOp.operator;

                        if (sigilOp.operand instanceof IdentifierNode) {
                            String varName = sigil + ((IdentifierNode) sigilOp.operand).name;

                            int reg;
                            // Check if already declared in current scope
                            if (hasVariable(varName)) {
                                // Already declared, just use existing register
                                reg = getVariableRegister(varName);
                            } else {
                                // Allocate register and add to symbol table
                                reg = addVariable(varName, "our");

                                // Load from global variable using normalized name
                                String globalVarName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) sigilOp.operand).name,
                                    getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalVarName);

                                switch (sigil) {
                                    case "$" -> {
                                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "@" -> {
                                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    case "%" -> {
                                        emit(Opcodes.LOAD_GLOBAL_HASH);
                                        emitReg(reg);
                                        emit(nameIdx);
                                    }
                                    default -> throwCompilerException("Unsupported variable type in list declaration: " + sigil);
                                }
                            }

                            varRegs.add(reg);
                        } else {
                            throwCompilerException("our list declaration requires identifier: " + sigilOp.operand.getClass().getSimpleName());
                        }
                    } else {
                        throwCompilerException("our list declaration requires scalar/array/hash: " + element.getClass().getSimpleName());
                    }
                }

                // Return a list of the declared variables
                int resultReg = allocateRegister();
                emit(Opcodes.CREATE_LIST);
                emitReg(resultReg);
                emit(varRegs.size());
                for (int varReg : varRegs) {
                    emitReg(varReg);
                }

                lastResultReg = resultReg;
                return;
            }
            throwCompilerException("Unsupported our operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("local")) {
            // local $x - temporarily localize a global variable            // The operand will be OperatorNode("$", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) node.operand;

                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;

                    // Check if it's a lexical variable (should not be localized)
                    if (hasVariable(varName)) {
                        throwCompilerException("Can't localize lexical variable " + varName);
                        return;
                    }

                    // It's a global variable - emit SLOW_OP to call GlobalRuntimeScalar.makeLocal()
                    String packageName = getCurrentPackage();
                    String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                    int nameIdx = addToStringPool(globalVarName);

                    int rd = allocateRegister();
                    emitWithToken(Opcodes.LOCAL_SCALAR, node.getIndex());
                    emitReg(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                    return;
                }
            }
            throwCompilerException("Unsupported local operand: " + node.operand.getClass().getSimpleName());
        }
        throwCompilerException("Unsupported variable declaration operator: " + op);
    }

    private void compileVariableReference(OperatorNode node, String op) {
        if (op.equals("$")) {
            // Scalar variable dereference: $x
            if (node.operand instanceof IdentifierNode) {
                String varName = "$" + ((IdentifierNode) node.operand).name;

                // Check if this is a closure variable captured from outer scope via PersistentVariable
                if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars.contains(varName)) {
                    // This is a closure variable - use RETRIEVE_BEGIN_SCALAR
                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(varName);

                    emitWithToken(Opcodes.RETRIEVE_BEGIN_SCALAR, node.getIndex());
                    emitReg(rd);
                    emit(nameIdx);
                    emit(currentSubroutineBeginId);

                    lastResultReg = rd;
                } else if (hasVariable(varName)) {
                    // Lexical variable - use existing register
                    lastResultReg = getVariableRegister(varName);
                } else {
                    // Global variable - load it
                    // Add package prefix if not present (match compiler behavior)
                    String globalVarName = varName.substring(1); // Remove $ sigil first
                    if (!globalVarName.contains("::")) {
                        // Add package prefix
                        globalVarName = getCurrentPackage() + "::" + globalVarName;
                    }

                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(globalVarName);

                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emitReg(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                }
            } else if (node.operand instanceof BlockNode) {
                // Block dereference: ${\0} or ${expr}
                // Execute the block and dereference the result
                BlockNode block = (BlockNode) node.operand;

                // Compile the block
                block.accept(this);
                int blockResultReg = lastResultReg;

                // Dereference the result
                int rd = allocateRegister();
                emitWithToken(Opcodes.DEREF, node.getIndex());
                emitReg(rd);
                emitReg(blockResultReg);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported $ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("@")) {
            // Array variable dereference: @x or @_ or @$arrayref
            if (node.operand instanceof IdentifierNode) {
                String varName = "@" + ((IdentifierNode) node.operand).name;

                // Special case: @_ is register 1
                if (varName.equals("@_")) {
                    int arrayReg = 1; // @_ is always in register 1

                    // Check if we're in scalar context - if so, return array size
                    if (currentCallContext == RuntimeContextType.SCALAR) {
                        int rd = allocateRegister();
                        emit(Opcodes.ARRAY_SIZE);
                        emitReg(rd);
                        emitReg(arrayReg);
                        lastResultReg = rd;
                    } else {
                        lastResultReg = arrayReg;
                    }
                    return;
                }

                // Check if this is a closure variable captured from outer scope via PersistentVariable
                int arrayReg;
                if (currentSubroutineBeginId != 0 && currentSubroutineClosureVars.contains(varName)) {
                    // This is a closure variable - use RETRIEVE_BEGIN_ARRAY
                    arrayReg = allocateRegister();
                    int nameIdx = addToStringPool(varName);

                    emitWithToken(Opcodes.RETRIEVE_BEGIN_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emit(nameIdx);
                    emit(currentSubroutineBeginId);
                } else if (hasVariable(varName)) {
                    // Lexical array - use existing register
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);

                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }

                // Check if we're in scalar context - if so, return array size
                if (currentCallContext == RuntimeContextType.SCALAR) {
                    int rd = allocateRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(rd);
                    emitReg(arrayReg);
                    lastResultReg = rd;
                } else {
                    lastResultReg = arrayReg;
                }
            } else if (node.operand instanceof OperatorNode) {
                // Dereference: @$arrayref or @{$hashref}
                OperatorNode operandOp = (OperatorNode) node.operand;

                // Compile the reference
                operandOp.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                // The reference should contain a RuntimeArray
                // For @$scalar, we need to dereference it
                int rd = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(rd);
                emitReg(refReg);

                lastResultReg = rd;
                // Note: We don't check scalar context here because dereferencing
                // should return the array itself. The slice or other operation
                // will handle scalar context conversion if needed.
            } else if (node.operand instanceof BlockNode) {
                // @{ block } - evaluate block and dereference the result
                // The block should return an arrayref
                BlockNode blockNode = (BlockNode) node.operand;
                blockNode.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                int rd = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(rd);
                emitReg(refReg);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported @ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("%")) {
            // Hash variable dereference: %x
            if (node.operand instanceof IdentifierNode) {
                String varName = "%" + ((IdentifierNode) node.operand).name;

                // Check if it's a lexical hash
                if (hasVariable(varName)) {
                    // Lexical hash - use existing register
                    lastResultReg = getVariableRegister(varName);
                    return;
                }

                // Global hash - load it
                int rd = allocateRegister();
                String globalHashName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                int nameIdx = addToStringPool(globalHashName);

                emit(Opcodes.LOAD_GLOBAL_HASH);
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported % operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("*")) {
            // Glob variable dereference: *x
            if (node.operand instanceof IdentifierNode) {
                IdentifierNode idNode = (IdentifierNode) node.operand;
                String varName = idNode.name;

                // Add package prefix if not present
                if (!varName.contains("::")) {
                    varName = "main::" + varName;
                }

                // Allocate register for glob
                int rd = allocateRegister();
                int nameIdx = addToStringPool(varName);

                // Emit direct opcode LOAD_GLOB
                emitWithToken(Opcodes.LOAD_GLOB, node.getIndex());
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported * operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("&")) {
            // Code reference: &subname
            // Gets a reference to a named subroutine
            if (node.operand instanceof IdentifierNode) {
                IdentifierNode idNode = (IdentifierNode) node.operand;
                String subName = idNode.name;

                // Add package prefix if not present
                if (!subName.contains("::")) {
                    subName = "main::" + subName;
                }

                // Allocate register for code reference
                int rd = allocateRegister();
                int nameIdx = addToStringPool(subName);

                // Emit LOAD_GLOBAL_CODE
                emit(Opcodes.LOAD_GLOBAL_CODE);
                emitReg(rd);
                emit(nameIdx);

                lastResultReg = rd;
            } else {
                throwCompilerException("Unsupported & operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("\\")) {
            // Reference operator: \$x, \@x, \%x, \*x, etc.
            if (node.operand != null) {
                // Compile operand in LIST context to get the actual value
                // Example: \@array should get a reference to the array itself,
                // not its size (which would happen in SCALAR context)
                int savedContext = currentCallContext;
                currentCallContext = RuntimeContextType.LIST;
                try {
                    node.operand.accept(this);
                    int valueReg = lastResultReg;

                    // Allocate register for reference
                    int rd = allocateRegister();

                    // Emit CREATE_REF
                    emit(Opcodes.CREATE_REF);
                    emitReg(rd);
                    emitReg(valueReg);

                    lastResultReg = rd;
                } finally {
                    currentCallContext = savedContext;
                }
            } else {
                throwCompilerException("Reference operator requires operand");
            }
        }
    }

    @Override
    public void visit(OperatorNode node) {
        // Track token index for error reporting
        currentTokenIndex = node.getIndex();

        String op = node.operator;

        // Group 1: Variable declarations (my, our, local)
        if (op.equals("my") || op.equals("our") || op.equals("local")) {
            compileVariableDeclaration(node, op);
            return;
        }

        // Group 2: Variable reference operators ($, @, %, *, &, \)
        if (op.equals("$") || op.equals("@") || op.equals("%") || op.equals("*") || op.equals("&") || op.equals("\\")) {
            compileVariableReference(node, op);
            return;
        }

        // Handle remaining operators
        if (op.equals("scalar")) {
            // Force scalar context: scalar(expr)
            // Evaluates the operand and converts the result to scalar
            if (node.operand != null) {
                // Evaluate operand in scalar context
                int savedContext = currentCallContext;
                currentCallContext = RuntimeContextType.SCALAR;
                try {
                    node.operand.accept(this);
                    int operandReg = lastResultReg;

                    // Emit ARRAY_SIZE to convert to scalar
                    // This handles arrays/hashes (converts to size) and passes through scalars
                    int rd = allocateRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(rd);
                    emitReg(operandReg);

                    lastResultReg = rd;
                } finally {
                    currentCallContext = savedContext;
                }
            } else {
                throwCompilerException("scalar operator requires an operand");
            }
            return;
        } else if (op.equals("package") || op.equals("class")) {
            // Package/Class declaration: package Foo; or class Foo;
            // This updates the current package context for subsequent variable declarations
            if (node.operand instanceof IdentifierNode) {
                String packageName = ((IdentifierNode) node.operand).name;

                // Check if this is a class declaration (either "class" operator or isClass annotation)
                Boolean isClassAnnotation = (Boolean) node.getAnnotation("isClass");
                boolean isClass = op.equals("class") || (isClassAnnotation != null && isClassAnnotation);

                // Update the current package/class in symbol table
                // This tracks package name, isClass flag, and version
                symbolTable.setCurrentPackage(packageName, isClass);

                // Register as Perl 5.38+ class for proper stringification if needed
                if (isClass) {
                    org.perlonjava.runtime.ClassRegistry.registerClass(packageName);
                }

                lastResultReg = -1;  // No runtime value
            } else {
                throwCompilerException(op + " operator requires an identifier");
            }
        } else if (op.equals("say") || op.equals("print")) {
            // say/print $x
            if (node.operand != null) {
                node.operand.accept(this);
                int rs = lastResultReg;

                emit(op.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
                emitReg(rs);
            }
        } else if (op.equals("not") || op.equals("!")) {
            // Logical NOT operator: not $x or !$x
            // Evaluate operand and emit NOT opcode
            if (node.operand != null) {
                node.operand.accept(this);
                int rs = lastResultReg;

                // Allocate result register
                int rd = allocateRegister();

                // Emit NOT opcode
                emit(Opcodes.NOT);
                emitReg(rd);
                emitReg(rs);

                lastResultReg = rd;
            } else {
                throwCompilerException("NOT operator requires operand");
            }
        } else if (op.equals("defined")) {
            // Defined operator: defined($x)
            // Check if value is defined (not undef)
            if (node.operand != null) {
                node.operand.accept(this);
                int rs = lastResultReg;

                // Allocate result register
                int rd = allocateRegister();

                // Emit DEFINED opcode
                emit(Opcodes.DEFINED);
                emitReg(rd);
                emitReg(rs);

                lastResultReg = rd;
            } else {
                throwCompilerException("defined operator requires operand");
            }
        } else if (op.equals("ref")) {
            // Ref operator: ref($x)
            // Get reference type (blessed class name or base type)
            if (node.operand == null) {
                throwCompilerException("ref requires an argument");
            }

            // Compile the operand
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    throwCompilerException("ref requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(this);
            } else {
                node.operand.accept(this);
            }
            int argReg = lastResultReg;

            // Allocate result register
            int rd = allocateRegister();

            // Emit REF opcode
            emit(Opcodes.REF);
            emitReg(rd);
            emitReg(argReg);

            lastResultReg = rd;
        } else if (op.equals("prototype")) {
            // Prototype operator: prototype(\&func) or prototype("func_name")
            // Returns the prototype string for a subroutine
            if (node.operand == null) {
                throwCompilerException("prototype requires an argument");
            }

            // Compile the operand (code reference or function name)
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    throwCompilerException("prototype requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(this);
            } else {
                node.operand.accept(this);
            }
            int argReg = lastResultReg;

            // Allocate result register
            int rd = allocateRegister();

            // Add current package to string pool
            int packageIdx = addToStringPool(getCurrentPackage());

            // Emit PROTOTYPE opcode
            emit(Opcodes.PROTOTYPE);
            emitReg(rd);
            emitReg(argReg);
            emitInt(packageIdx);

            lastResultReg = rd;
        } else if (op.equals("quoteRegex")) {
            // Quote regex operator: qr{pattern}flags
            // operand is a ListNode with [pattern, flags]
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("quoteRegex requires pattern and flags");
            }

            ListNode operand = (ListNode) node.operand;
            if (operand.elements.size() < 2) {
                throwCompilerException("quoteRegex requires pattern and flags");
            }

            // Compile pattern and flags
            operand.elements.get(0).accept(this);  // Pattern
            int patternReg = lastResultReg;

            operand.elements.get(1).accept(this);  // Flags
            int flagsReg = lastResultReg;

            // Allocate result register
            int rd = allocateRegister();

            // Emit QUOTE_REGEX opcode
            emit(Opcodes.QUOTE_REGEX);
            emitReg(rd);
            emitReg(patternReg);
            emitReg(flagsReg);

            lastResultReg = rd;
        } else if (op.equals("++") || op.equals("--") || op.equals("++postfix") || op.equals("--postfix")) {
            // Pre/post increment/decrement
            boolean isPostfix = op.endsWith("postfix");
            boolean isIncrement = op.startsWith("++");

            if (node.operand instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.operand).name;

                if (hasVariable(varName)) {
                    int varReg = getVariableRegister(varName);

                    // Use optimized autoincrement/decrement opcodes
                    if (isPostfix) {
                        // Postfix: returns old value before modifying
                        // Need TWO registers: one for result (old value), one for variable
                        int resultReg = allocateRegister();
                        if (isIncrement) {
                            emit(Opcodes.POST_AUTOINCREMENT);
                        } else {
                            emit(Opcodes.POST_AUTODECREMENT);
                        }
                        emitReg(resultReg);  // Destination for old value
                        emitReg(varReg);     // Variable to modify in-place
                        lastResultReg = resultReg;
                    } else {
                        // Prefix: returns new value after modifying
                        if (isIncrement) {
                            emit(Opcodes.PRE_AUTOINCREMENT);
                        } else {
                            emit(Opcodes.PRE_AUTODECREMENT);
                        }
                        emitReg(varReg);
                        lastResultReg = varReg;
                    }
                } else {
                    throwCompilerException("Increment/decrement of non-lexical variable not yet supported");
                }
            } else if (node.operand instanceof OperatorNode) {
                // Handle $x++
                OperatorNode innerOp = (OperatorNode) node.operand;
                if (innerOp.operator.equals("$") && innerOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) innerOp.operand).name;

                    if (hasVariable(varName)) {
                        int varReg = getVariableRegister(varName);

                        // Use optimized autoincrement/decrement opcodes
                        if (isPostfix) {
                            // Postfix: returns old value before modifying
                            // Need TWO registers: one for result (old value), one for variable
                            int resultReg = allocateRegister();
                            if (isIncrement) {
                                emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.POST_AUTODECREMENT);
                            }
                            emitReg(resultReg);  // Destination for old value
                            emitReg(varReg);     // Variable to modify in-place
                            lastResultReg = resultReg;
                        } else {
                            if (isIncrement) {
                                emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.PRE_AUTODECREMENT);
                            }
                            emitReg(varReg);
                            lastResultReg = varReg;
                        }
                    } else {
                        // Global variable increment/decrement
                        // Normalize global variable name (remove sigil, add package)
                        String bareVarName = varName.substring(1);  // Remove "$"
                        String normalizedName = NameNormalizer.normalizeVariableName(bareVarName, getCurrentPackage());
                        int nameIdx = addToStringPool(normalizedName);

                        // Load global variable
                        int globalReg = allocateRegister();
                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        emitReg(globalReg);
                        emit(nameIdx);

                        // Apply increment/decrement
                        if (isPostfix) {
                            // Postfix: returns old value before modifying
                            // Need TWO registers: one for result (old value), one for variable
                            int resultReg = allocateRegister();
                            if (isIncrement) {
                                emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.POST_AUTODECREMENT);
                            }
                            emitReg(resultReg);   // Destination for old value
                            emitReg(globalReg);   // Variable to modify in-place
                            lastResultReg = resultReg;
                        } else {
                            if (isIncrement) {
                                emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.PRE_AUTODECREMENT);
                            }
                            emitReg(globalReg);
                            lastResultReg = globalReg;
                        }

                        // NOTE: Do NOT store back to global variable!
                        // The POST/PRE_AUTO* opcodes modify the global variable directly
                        // and return the appropriate value (old for postfix, new for prefix).
                        // Storing back would overwrite the modification with the return value.
                    }
                } else {
                    throwCompilerException("Invalid operand for increment/decrement operator");
                }
            } else {
                throwCompilerException("Increment/decrement operator requires operand");
            }
        } else if (op.equals("return")) {
            // return $expr;
            if (node.operand != null) {
                // Evaluate return expression
                node.operand.accept(this);
                int exprReg = lastResultReg;

                // Emit RETURN with expression register
                emitWithToken(Opcodes.RETURN, node.getIndex());
                emitReg(exprReg);
            } else {
                // return; (no value - return empty list/undef)
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(undefReg);

                emitWithToken(Opcodes.RETURN, node.getIndex());
                emitReg(undefReg);
            }
            lastResultReg = -1; // No result after return
        } else if (op.equals("rand")) {
            // rand() or rand($max)
            // Calls Random.rand(max) where max defaults to 1
            int rd = allocateRegister();

            if (node.operand != null) {
                // rand($max) - evaluate operand
                node.operand.accept(this);
                int maxReg = lastResultReg;

                // Emit RAND opcode
                emit(Opcodes.RAND);
                emitReg(rd);
                emitReg(maxReg);
            } else {
                // rand() with no argument - defaults to 1
                int oneReg = allocateRegister();
                emit(Opcodes.LOAD_INT);
                emitReg(oneReg);
                emitInt(1);

                // Emit RAND opcode
                emit(Opcodes.RAND);
                emitReg(rd);
                emitReg(oneReg);
            }

            lastResultReg = rd;
        } else if (op.equals("sleep")) {
            // sleep $seconds
            // Calls Time.sleep(seconds)
            int rd = allocateRegister();

            if (node.operand != null) {
                // sleep($seconds) - evaluate operand
                node.operand.accept(this);
                int secondsReg = lastResultReg;

                // Emit direct opcode SLEEP_OP
                emit(Opcodes.SLEEP_OP);
                emitReg(rd);
                emitReg(secondsReg);
            } else {
                // sleep with no argument - defaults to infinity (but we'll use a large number)
                int maxReg = allocateRegister();
                emit(Opcodes.LOAD_INT);
                emitReg(maxReg);
                emitInt(Integer.MAX_VALUE);

                emit(Opcodes.SLEEP_OP);
                emitReg(rd);
                emitReg(maxReg);
            }

            lastResultReg = rd;
        } else if (op.equals("die")) {
            // die $message;
            if (node.operand != null) {
                // Evaluate die message
                node.operand.accept(this);
                int msgReg = lastResultReg;

                // Precompute location message at compile time (zero overhead!)
                String locationMsg;
                // Use annotation from AST node which has the correct line number
                Object lineObj = node.getAnnotation("line");
                Object fileObj = node.getAnnotation("file");
                if (lineObj != null && fileObj != null) {
                    String fileName = fileObj.toString();
                    int lineNumber = Integer.parseInt(lineObj.toString());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else if (errorUtil != null) {
                    // Fallback to errorUtil if annotations not available
                    String fileName = errorUtil.getFileName();
                    int lineNumber = errorUtil.getLineNumberAccurate(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    // Final fallback if neither available
                    locationMsg = " at " + sourceName + " line " + sourceLine;
                }

                int locationReg = allocateRegister();
                emit(Opcodes.LOAD_STRING);
                emitReg(locationReg);
                emit(addToStringPool(locationMsg));

                // Emit DIE with both message and precomputed location
                emitWithToken(Opcodes.DIE, node.getIndex());
                emitReg(msgReg);
                emitReg(locationReg);
            } else {
                // die; (no message - use $@)
                // For now, emit with undef register
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(undefReg);

                // Precompute location message for bare die
                String locationMsg;
                if (errorUtil != null) {
                    String fileName = errorUtil.getFileName();
                    int lineNumber = errorUtil.getLineNumber(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    locationMsg = " at " + sourceName + " line " + sourceLine;
                }

                int locationReg = allocateRegister();
                emit(Opcodes.LOAD_STRING);
                emitReg(locationReg);
                emitInt(addToStringPool(locationMsg));

                emitWithToken(Opcodes.DIE, node.getIndex());
                emitReg(undefReg);
                emitReg(locationReg);
            }
            lastResultReg = -1; // No result after die
        } else if (op.equals("warn")) {
            // warn $message;
            if (node.operand != null) {
                // Evaluate warn message
                node.operand.accept(this);
                int msgReg = lastResultReg;

                // Precompute location message at compile time
                String locationMsg;
                // Use annotation from AST node which has the correct line number
                Object lineObj = node.getAnnotation("line");
                Object fileObj = node.getAnnotation("file");
                if (lineObj != null && fileObj != null) {
                    String fileName = fileObj.toString();
                    int lineNumber = Integer.parseInt(lineObj.toString());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else if (errorUtil != null) {
                    // Fallback to errorUtil if annotations not available
                    String fileName = errorUtil.getFileName();
                    int lineNumber = errorUtil.getLineNumberAccurate(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    // Final fallback if neither available
                    locationMsg = " at " + sourceName + " line " + sourceLine;
                }

                int locationReg = allocateRegister();
                emit(Opcodes.LOAD_STRING);
                emitReg(locationReg);
                emit(addToStringPool(locationMsg));

                // Emit WARN with both message and precomputed location
                emitWithToken(Opcodes.WARN, node.getIndex());
                emitReg(msgReg);
                emitReg(locationReg);
            } else {
                // warn; (no message - use $@)
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(undefReg);

                // Precompute location message for bare warn
                String locationMsg;
                if (errorUtil != null) {
                    String fileName = errorUtil.getFileName();
                    int lineNumber = errorUtil.getLineNumber(node.getIndex());
                    locationMsg = " at " + fileName + " line " + lineNumber;
                } else {
                    locationMsg = " at " + sourceName + " line " + sourceLine;
                }

                int locationReg = allocateRegister();
                emit(Opcodes.LOAD_STRING);
                emitReg(locationReg);
                emitInt(addToStringPool(locationMsg));

                emitWithToken(Opcodes.WARN, node.getIndex());
                emitReg(undefReg);
                emitReg(locationReg);
            }
            // warn returns 1 (true) in Perl
            int resultReg = allocateRegister();
            emit(Opcodes.LOAD_INT);
            emitReg(resultReg);
            emitInt(1);
            lastResultReg = resultReg;
        } else if (op.equals("eval")) {
            // eval $string;
            if (node.operand != null) {
                // Evaluate eval operand (the code string)
                node.operand.accept(this);
                int stringReg = lastResultReg;

                // Allocate register for result
                int rd = allocateRegister();

                // Emit direct opcode EVAL_STRING
                emitWithToken(Opcodes.EVAL_STRING, node.getIndex());
                emitReg(rd);
                emitReg(stringReg);

                lastResultReg = rd;
            } else {
                // eval; (no operand - return undef)
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(undefReg);
                lastResultReg = undefReg;
            }
        } else if (op.equals("select")) {
            // select FILEHANDLE or select()
            // SELECT is a fast opcode (used in every print statement)
            // Format: [SELECT] [rd] [rs_list]
            // Effect: rd = IOOperator.select(registers[rs_list], SCALAR)

            int rd = allocateRegister();

            if (node.operand != null && node.operand instanceof ListNode) {
                // select FILEHANDLE or select() with arguments
                // Compile the operand (ListNode containing filehandle ref)
                node.operand.accept(this);
                int listReg = lastResultReg;

                // Emit SELECT opcode
                emitWithToken(Opcodes.SELECT, node.getIndex());
                emitReg(rd);
                emitReg(listReg);
            } else {
                // select() with no arguments - returns current filehandle
                // Create empty list
                emit(Opcodes.CREATE_LIST);
                int listReg = allocateRegister();
                emitReg(listReg);
                emit(0); // count = 0

                // Emit SELECT opcode
                emitWithToken(Opcodes.SELECT, node.getIndex());
                emitReg(rd);
                emitReg(listReg);
            }

            lastResultReg = rd;
        } else if (op.equals("undef")) {
            // undef operator - returns undefined value
            // Can be used standalone: undef
            // Or with an operand to undef a variable: undef $x (not implemented yet)
            int undefReg = allocateRegister();
            emit(Opcodes.LOAD_UNDEF);
            emitReg(undefReg);
            lastResultReg = undefReg;
        } else if (op.equals("unaryMinus")) {
            // Unary minus: -$x
            // Compile operand
            node.operand.accept(this);
            int operandReg = lastResultReg;

            // Allocate result register
            int rd = allocateRegister();

            // Emit NEG_SCALAR
            emit(Opcodes.NEG_SCALAR);
            emitReg(rd);
            emitReg(operandReg);

            lastResultReg = rd;
        } else if (op.equals("pop")) {
            // Array pop: $x = pop @array or $x = pop @$ref
            // operand: ListNode containing OperatorNode("@", IdentifierNode or OperatorNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("pop requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                throwCompilerException("pop requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                throwCompilerException("pop requires array variable: pop @array");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // pop @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // pop @$ref - dereference first
                arrayOp.operand.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
            } else {
                throwCompilerException("pop requires array variable or dereferenced array: pop @array or pop @$ref");
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit ARRAY_POP
            emit(Opcodes.ARRAY_POP);
            emitReg(rd);
            emitReg(arrayReg);

            lastResultReg = rd;
        } else if (op.equals("shift")) {
            // Array shift: $x = shift @array or $x = shift @$ref
            // operand: ListNode containing OperatorNode("@", IdentifierNode or OperatorNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("shift requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                throwCompilerException("shift requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                throwCompilerException("shift requires array variable: shift @array");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // shift @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // shift @$ref - dereference first
                arrayOp.operand.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
            } else {
                throwCompilerException("shift requires array variable or dereferenced array: shift @array or shift @$ref");
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit ARRAY_SHIFT
            emit(Opcodes.ARRAY_SHIFT);
            emitReg(rd);
            emitReg(arrayReg);

            lastResultReg = rd;
        } else if (op.equals("splice")) {
            // Array splice: splice @array, offset, length, @list
            // operand: ListNode containing [@array, offset, length, replacement_list]
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("splice requires array and arguments");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                throwCompilerException("splice requires array variable");
            }

            // First element is the array
            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@")) {
                throwCompilerException("splice requires array variable: splice @array, ...");
            }

            int arrayReg = -1;  // Will be assigned in if/else blocks

            if (arrayOp.operand instanceof IdentifierNode) {
                // splice @array
                String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

                // Get the array - check lexical first, then global
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                        ((IdentifierNode) arrayOp.operand).name,
                        getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else if (arrayOp.operand instanceof OperatorNode) {
                // splice @$ref - dereference first
                arrayOp.operand.accept(this);
                int refReg = lastResultReg;

                // Dereference to get the array
                arrayReg = allocateRegister();
                emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                emitReg(arrayReg);
                emitReg(refReg);
            } else {
                throwCompilerException("splice requires array variable or dereferenced array: splice @array or splice @$ref");
            }

            // Create a list with the remaining arguments (offset, length, replacement values)
            // Compile each remaining argument and collect them into a RuntimeList
            List<Integer> argRegs = new ArrayList<>();
            for (int i = 1; i < list.elements.size(); i++) {
                list.elements.get(i).accept(this);
                argRegs.add(lastResultReg);
            }

            // Create a RuntimeList from these registers
            int argsListReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(argsListReg);
            emit(argRegs.size());
            for (int argReg : argRegs) {
                emitReg(argReg);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit direct opcode SPLICE
            emit(Opcodes.SPLICE);
            emitReg(rd);
            emitReg(arrayReg);
            emitReg(argsListReg);
            emit(currentCallContext);  // Pass context for scalar/list conversion

            lastResultReg = rd;
        } else if (op.equals("reverse")) {
            // Array/string reverse: reverse @array or reverse $string
            // operand: ListNode containing arguments
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("reverse requires arguments");
            }

            ListNode list = (ListNode) node.operand;

            // Compile all arguments into registers
            List<Integer> argRegs = new ArrayList<>();
            for (Node arg : list.elements) {
                arg.accept(this);
                argRegs.add(lastResultReg);
            }

            // Create a RuntimeList from these registers
            int argsListReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(argsListReg);
            emit(argRegs.size());
            for (int argReg : argRegs) {
                emitReg(argReg);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit direct opcode REVERSE
            emit(Opcodes.REVERSE);
            emitReg(rd);
            emitReg(argsListReg);
            emit(RuntimeContextType.LIST);  // Context

            lastResultReg = rd;
        } else if (op.equals("exists")) {
            // exists $hash{key} or exists $array[index]
            // operand: ListNode containing the hash/array access
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("exists requires an argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty()) {
                throwCompilerException("exists requires an argument");
            }

            Node arg = list.elements.get(0);

            // Handle hash access: $hash{key}
            if (arg instanceof BinaryOperatorNode && ((BinaryOperatorNode) arg).operator.equals("{")) {
                BinaryOperatorNode hashAccess = (BinaryOperatorNode) arg;

                // Get hash register (need to handle $hash{key} -> %hash)
                int hashReg;
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                        // Simple: exists $hash{key} -> get %hash
                        String varName = ((IdentifierNode) leftOp.operand).name;
                        String hashVarName = "%" + varName;

                        if (hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                varName,
                                getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalHashName);
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(hashReg);
                            emit(nameIdx);
                        }
                    } else {
                        // Complex: dereference needed
                        leftOp.operand.accept(this);
                        int scalarReg = lastResultReg;

                        hashReg = allocateRegister();
                        emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                        emitReg(hashReg);
                        emitReg(scalarReg);
                    }
                } else if (hashAccess.left instanceof BinaryOperatorNode) {
                    // Nested: exists $hash{outer}{inner}
                    hashAccess.left.accept(this);
                    int scalarReg = lastResultReg;

                    hashReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                } else {
                    throwCompilerException("Hash access requires variable or expression on left side");
                    return;
                }

                // Compile key (right side contains HashLiteralNode)
                int keyReg;
                if (hashAccess.right instanceof HashLiteralNode) {
                    HashLiteralNode keyNode = (HashLiteralNode) hashAccess.right;
                    if (!keyNode.elements.isEmpty()) {
                        Node keyElement = keyNode.elements.get(0);
                        if (keyElement instanceof IdentifierNode) {
                            // Bareword key - autoquote
                            String keyString = ((IdentifierNode) keyElement).name;
                            keyReg = allocateRegister();
                            int keyIdx = addToStringPool(keyString);
                            emit(Opcodes.LOAD_STRING);
                            emitReg(keyReg);
                            emit(keyIdx);
                        } else {
                            // Expression key
                            keyElement.accept(this);
                            keyReg = lastResultReg;
                        }
                    } else {
                        throwCompilerException("Hash key required for exists");
                        return;
                    }
                } else {
                    hashAccess.right.accept(this);
                    keyReg = lastResultReg;
                }

                // Emit HASH_EXISTS
                int rd = allocateRegister();
                emit(Opcodes.HASH_EXISTS);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            } else {
                // For now, use SLOW_OP for other cases (array exists, etc.)
                arg.accept(this);
                int argReg = lastResultReg;

                int rd = allocateRegister();
                emit(Opcodes.EXISTS);
                emitReg(rd);
                emitReg(argReg);

                lastResultReg = rd;
            }
        } else if (op.equals("delete")) {
            // delete $hash{key} or delete @hash{@keys}
            // operand: ListNode containing the hash/array access
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("delete requires an argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty()) {
                throwCompilerException("delete requires an argument");
            }

            Node arg = list.elements.get(0);

            // Handle hash access: $hash{key} or hash slice delete: delete @hash{keys}
            if (arg instanceof BinaryOperatorNode && ((BinaryOperatorNode) arg).operator.equals("{")) {
                BinaryOperatorNode hashAccess = (BinaryOperatorNode) arg;

                // Check if it's a hash slice delete: delete @hash{keys}
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("@")) {
                        // Hash slice delete: delete @hash{'key1', 'key2'}
                        // Use SLOW_OP for slice delete
                        int hashReg;

                        if (leftOp.operand instanceof IdentifierNode) {
                            String varName = ((IdentifierNode) leftOp.operand).name;
                            String hashVarName = "%" + varName;

                            if (hasVariable(hashVarName)) {
                                hashReg = getVariableRegister(hashVarName);
                            } else {
                                hashReg = allocateRegister();
                                String globalHashName = NameNormalizer.normalizeVariableName(
                                    varName,
                                    getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalHashName);
                                emit(Opcodes.LOAD_GLOBAL_HASH);
                                emitReg(hashReg);
                                emit(nameIdx);
                            }
                        } else {
                            throwCompilerException("Hash slice delete requires identifier");
                            return;
                        }

                        // Get keys from HashLiteralNode
                        if (!(hashAccess.right instanceof HashLiteralNode)) {
                            throwCompilerException("Hash slice delete requires HashLiteralNode");
                            return;
                        }
                        HashLiteralNode keysNode = (HashLiteralNode) hashAccess.right;

                        // Compile all keys
                        List<Integer> keyRegs = new ArrayList<>();
                        for (Node keyElement : keysNode.elements) {
                            if (keyElement instanceof IdentifierNode) {
                                String keyString = ((IdentifierNode) keyElement).name;
                                int keyReg = allocateRegister();
                                int keyIdx = addToStringPool(keyString);
                                emit(Opcodes.LOAD_STRING);
                                emitReg(keyReg);
                                emit(keyIdx);
                                keyRegs.add(keyReg);
                            } else {
                                keyElement.accept(this);
                                keyRegs.add(lastResultReg);
                            }
                        }

                        // Create RuntimeList from keys
                        int keysListReg = allocateRegister();
                        emit(Opcodes.CREATE_LIST);
                        emitReg(keysListReg);
                        emit(keyRegs.size());
                        for (int keyReg : keyRegs) {
                            emitReg(keyReg);
                        }

                        // Use SLOW_OP for hash slice delete
                        int rd = allocateRegister();
                        emit(Opcodes.HASH_SLICE_DELETE);
                        emitReg(rd);
                        emitReg(hashReg);
                        emitReg(keysListReg);

                        lastResultReg = rd;
                        return;
                    }
                }

                // Single key delete: delete $hash{key}
                // Get hash register (need to handle $hash{key} -> %hash)
                int hashReg;
                if (hashAccess.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) hashAccess.left;
                    if (leftOp.operator.equals("$") && leftOp.operand instanceof IdentifierNode) {
                        // Simple: delete $hash{key} -> get %hash
                        String varName = ((IdentifierNode) leftOp.operand).name;
                        String hashVarName = "%" + varName;

                        if (hasVariable(hashVarName)) {
                            // Lexical hash
                            hashReg = getVariableRegister(hashVarName);
                        } else {
                            // Global hash - load it
                            hashReg = allocateRegister();
                            String globalHashName = NameNormalizer.normalizeVariableName(
                                varName,
                                getCurrentPackage()
                            );
                            int nameIdx = addToStringPool(globalHashName);
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emitReg(hashReg);
                            emit(nameIdx);
                        }
                    } else {
                        // Complex: dereference needed
                        leftOp.operand.accept(this);
                        int scalarReg = lastResultReg;

                        hashReg = allocateRegister();
                        emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                        emitReg(hashReg);
                        emitReg(scalarReg);
                    }
                } else if (hashAccess.left instanceof BinaryOperatorNode) {
                    // Nested: delete $hash{outer}{inner}
                    hashAccess.left.accept(this);
                    int scalarReg = lastResultReg;

                    hashReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_HASH, node.getIndex());
                    emitReg(hashReg);
                    emitReg(scalarReg);
                } else {
                    throwCompilerException("Hash access requires variable or expression on left side");
                    return;
                }

                // Compile key (right side contains HashLiteralNode)
                int keyReg;
                if (hashAccess.right instanceof HashLiteralNode) {
                    HashLiteralNode keyNode = (HashLiteralNode) hashAccess.right;
                    if (!keyNode.elements.isEmpty()) {
                        Node keyElement = keyNode.elements.get(0);
                        if (keyElement instanceof IdentifierNode) {
                            // Bareword key - autoquote
                            String keyString = ((IdentifierNode) keyElement).name;
                            keyReg = allocateRegister();
                            int keyIdx = addToStringPool(keyString);
                            emit(Opcodes.LOAD_STRING);
                            emitReg(keyReg);
                            emit(keyIdx);
                        } else {
                            // Expression key
                            keyElement.accept(this);
                            keyReg = lastResultReg;
                        }
                    } else {
                        throwCompilerException("Hash key required for delete");
                        return;
                    }
                } else {
                    hashAccess.right.accept(this);
                    keyReg = lastResultReg;
                }

                // Emit HASH_DELETE
                int rd = allocateRegister();
                emit(Opcodes.HASH_DELETE);
                emitReg(rd);
                emitReg(hashReg);
                emitReg(keyReg);

                lastResultReg = rd;
            } else {
                // For now, use SLOW_OP for other cases (hash slice delete, array delete, etc.)
                arg.accept(this);
                int argReg = lastResultReg;

                int rd = allocateRegister();
                emit(Opcodes.DELETE);
                emitReg(rd);
                emitReg(argReg);

                lastResultReg = rd;
            }
        } else if (op.equals("keys")) {
            // keys %hash
            // operand: hash variable (OperatorNode("%" ...) or other expression)
            if (node.operand == null) {
                throwCompilerException("keys requires a hash argument");
            }

            // Compile the hash operand
            node.operand.accept(this);
            int hashReg = lastResultReg;

            // Emit HASH_KEYS
            int rd = allocateRegister();
            emit(Opcodes.HASH_KEYS);
            emitReg(rd);
            emitReg(hashReg);

            lastResultReg = rd;
        } else if (op.equals("values")) {
            // values %hash
            // operand: hash variable (OperatorNode("%" ...) or other expression)
            if (node.operand == null) {
                throwCompilerException("values requires a hash argument");
            }

            // Compile the hash operand
            node.operand.accept(this);
            int hashReg = lastResultReg;

            // Emit HASH_VALUES
            int rd = allocateRegister();
            emit(Opcodes.HASH_VALUES);
            emitReg(rd);
            emitReg(hashReg);

            lastResultReg = rd;
        } else if (op.equals("$#")) {
            // $#array - get last index of array (size - 1)
            // operand: array variable (OperatorNode("@" ...) or IdentifierNode)
            if (node.operand == null) {
                throwCompilerException("$# requires an array argument");
            }

            int arrayReg = -1;

            // Handle different operand types
            if (node.operand instanceof OperatorNode) {
                OperatorNode operandOp = (OperatorNode) node.operand;

                if (operandOp.operator.equals("@") && operandOp.operand instanceof IdentifierNode) {
                    // $#@array or $#array (both work)
                    String varName = "@" + ((IdentifierNode) operandOp.operand).name;

                    if (hasVariable(varName)) {
                        arrayReg = getVariableRegister(varName);
                    } else {
                        arrayReg = allocateRegister();
                        String globalArrayName = NameNormalizer.normalizeVariableName(
                            ((IdentifierNode) operandOp.operand).name,
                            getCurrentPackage()
                        );
                        int nameIdx = addToStringPool(globalArrayName);
                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                        emitReg(arrayReg);
                        emit(nameIdx);
                    }
                } else if (operandOp.operator.equals("$")) {
                    // $#$ref - dereference first
                    operandOp.accept(this);
                    int refReg = lastResultReg;

                    arrayReg = allocateRegister();
                    emitWithToken(Opcodes.DEREF_ARRAY, node.getIndex());
                    emitReg(arrayReg);
                    emitReg(refReg);
                } else {
                    throwCompilerException("$# requires array variable or dereferenced array");
                }
            } else if (node.operand instanceof IdentifierNode) {
                // $#array (without @)
                String varName = "@" + ((IdentifierNode) node.operand).name;

                if (hasVariable(varName)) {
                    arrayReg = getVariableRegister(varName);
                } else {
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(
                        ((IdentifierNode) node.operand).name,
                        getCurrentPackage()
                    );
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emitReg(arrayReg);
                    emit(nameIdx);
                }
            } else {
                throwCompilerException("$# requires array variable");
            }

            // Get array size
            int sizeReg = allocateRegister();
            emit(Opcodes.ARRAY_SIZE);
            emitReg(sizeReg);
            emitReg(arrayReg);

            // Subtract 1 to get last index
            int oneReg = allocateRegister();
            emit(Opcodes.LOAD_INT);
            emitReg(oneReg);
            emitInt(1);

            int rd = allocateRegister();
            emit(Opcodes.SUB_SCALAR);
            emitReg(rd);
            emitReg(sizeReg);
            emitReg(oneReg);

            lastResultReg = rd;
        } else if (op.equals("length")) {
            // length($string) - get string length
            // operand: ListNode containing the string argument
            if (node.operand == null) {
                throwCompilerException("length requires an argument");
            }

            // Compile the operand
            if (node.operand instanceof ListNode) {
                ListNode list = (ListNode) node.operand;
                if (list.elements.isEmpty()) {
                    throwCompilerException("length requires an argument");
                }
                // Get first element
                list.elements.get(0).accept(this);
            } else {
                node.operand.accept(this);
            }
            int stringReg = lastResultReg;

            // Call length builtin using SLOW_OP
            int rd = allocateRegister();
            emit(Opcodes.LENGTH_OP);
            emitReg(rd);
            emitReg(stringReg);

            lastResultReg = rd;
        } else if (op.equals("open")) {
            // open(filehandle, mode, filename) or open(filehandle, expr)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("open requires arguments");
            }

            ListNode argsList = (ListNode) node.operand;
            if (argsList.elements.isEmpty()) {
                throwCompilerException("open requires arguments");
            }

            // Compile all arguments into a list
            int argsReg = allocateRegister();
            emit(Opcodes.NEW_ARRAY);
            emitReg(argsReg);

            for (Node arg : argsList.elements) {
                arg.accept(this);
                int elemReg = lastResultReg;

                emit(Opcodes.ARRAY_PUSH);
                emitReg(argsReg);
                emitReg(elemReg);
            }

            // Call open with context and args
            int rd = allocateRegister();
            emit(Opcodes.OPEN);
            emitReg(rd);
            emit(currentCallContext);
            emitReg(argsReg);

            lastResultReg = rd;
        } else if (op.equals("matchRegex")) {
            // m/pattern/flags - create a regex and return it (for use with =~)
            // operand: ListNode containing pattern string and flags string
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("matchRegex requires pattern and flags");
            }

            ListNode args = (ListNode) node.operand;
            if (args.elements.size() < 2) {
                throwCompilerException("matchRegex requires pattern and flags");
            }

            // Compile pattern
            args.elements.get(0).accept(this);
            int patternReg = lastResultReg;

            // Compile flags
            args.elements.get(1).accept(this);
            int flagsReg = lastResultReg;

            // Create quoted regex using QUOTE_REGEX opcode
            int rd = allocateRegister();
            emit(Opcodes.QUOTE_REGEX);
            emitReg(rd);
            emitReg(patternReg);
            emitReg(flagsReg);

            lastResultReg = rd;
        } else if (op.equals("chomp")) {
            // chomp($x) or chomp - remove trailing newlines
            if (node.operand == null) {
                // chomp with no args - operates on $_
                String varName = "$_";
                int targetReg;
                if (hasVariable(varName)) {
                    targetReg = getVariableRegister(varName);
                } else {
                    targetReg = allocateRegister();
                    int nameIdx = addToStringPool("main::_");
                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emitReg(targetReg);
                    emit(nameIdx);
                }

                int rd = allocateRegister();
                emit(Opcodes.CHOMP);
                emitReg(rd);
                emitReg(targetReg);

                lastResultReg = rd;
            } else {
                // chomp with argument
                if (node.operand instanceof ListNode) {
                    ListNode list = (ListNode) node.operand;
                    if (!list.elements.isEmpty()) {
                        list.elements.get(0).accept(this);
                    } else {
                        throwCompilerException("chomp requires an argument");
                    }
                } else {
                    node.operand.accept(this);
                }
                int targetReg = lastResultReg;

                int rd = allocateRegister();
                emit(Opcodes.CHOMP);
                emitReg(rd);
                emitReg(targetReg);

                lastResultReg = rd;
            }
        } else if (op.equals("+")) {
            // Unary + operator: forces numeric context on its operand
            // For arrays/hashes in scalar context, this returns the size
            // For scalars, this ensures the value is numeric
            if (node.operand != null) {
                // Evaluate operand in scalar context
                int savedContext = currentCallContext;
                currentCallContext = RuntimeContextType.SCALAR;
                try {
                    node.operand.accept(this);
                    int operandReg = lastResultReg;

                    // Emit ARRAY_SIZE to convert to scalar
                    // This handles arrays/hashes (converts to size) and passes through scalars
                    int rd = allocateRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emitReg(rd);
                    emitReg(operandReg);

                    lastResultReg = rd;
                } finally {
                    currentCallContext = savedContext;
                }
            } else {
                throwCompilerException("unary + operator requires an operand");
            }
        } else if (op.equals("wantarray")) {
            // wantarray operator: returns undef in VOID, false in SCALAR, true in LIST
            // Read register 2 (wantarray context) and convert to Perl convention
            int rd = allocateRegister();
            emit(Opcodes.WANTARRAY);
            emitReg(rd);
            emitReg(2);  // Register 2 contains the calling context

            lastResultReg = rd;
        } else {
            throwCompilerException("Unsupported operator: " + op);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private int allocateRegister() {
        int reg = nextRegister++;
        if (reg > 65535) {
            throwCompilerException("Too many registers: exceeded 65535 register limit. " +
                    "Consider breaking this code into smaller subroutines.");
        }
        // Track the highest register ever used for array sizing
        if (reg > maxRegisterEverUsed) {
            maxRegisterEverUsed = reg;
        }
        return reg;
    }

    /**
     * Get the highest register index currently used by variables (not temporaries).
     * This is used to determine the reset point for register recycling.
     */
    private int getHighestVariableRegister() {
        int maxReg = 2;  // Start with reserved registers (0=this, 1=@_, 2=wantarray)

        // Check all variable scopes
        for (Map<String, Integer> scope : variableScopes) {
            for (Integer reg : scope.values()) {
                if (reg > maxReg) {
                    maxReg = reg;
                }
            }
        }

        // Also check captured variables
        if (capturedVarIndices != null) {
            for (Integer reg : capturedVarIndices.values()) {
                if (reg > maxReg) {
                    maxReg = reg;
                }
            }
        }

        return maxReg;
    }

    /**
     * Reset temporary registers after a statement completes.
     * This allows register reuse for the next statement, preventing register exhaustion
     * in large scripts.
     *
     * IMPORTANT: Only resets registers that are truly temporary. Preserves:
     * - Reserved registers (0-2)
     * - All variables in all scopes
     * - Captured variables (closures)
     *
     * NOTE: This assumes that by the end of a statement, all intermediate values
     * have either been stored in variables or used/discarded. Any value that needs
     * to persist must be in a variable, not just a temporary register.
     */
    private void recycleTemporaryRegisters() {
        // Find highest variable register and reset nextRegister to one past it
        baseRegisterForStatement = getHighestVariableRegister() + 1;
        nextRegister = baseRegisterForStatement;

        // DO NOT reset lastResultReg - BlockNode needs it to return the last statement's result
    }

    private int addToStringPool(String str) {
        // Use HashMap for O(1) lookup instead of O(n) ArrayList.indexOf()
        Integer cached = stringPoolIndex.get(str);
        if (cached != null) {
            return cached;
        }
        int index = stringPool.size();
        stringPool.add(str);
        stringPoolIndex.put(str, index);
        return index;
    }

    private int addToConstantPool(Object obj) {
        // Use HashMap for O(1) lookup instead of O(n) ArrayList.indexOf()
        Integer cached = constantPoolIndex.get(obj);
        if (cached != null) {
            return cached;
        }
        int index = constants.size();
        constants.add(obj);
        constantPoolIndex.put(obj, index);
        return index;
    }

    private void emit(short opcode) {
        bytecode.add(opcode);
    }

    /**
     * Emit opcode and track tokenIndex for error reporting.
     * Use this for opcodes that may throw exceptions (DIE, method calls, etc.)
     */
    private void emitWithToken(short opcode, int tokenIndex) {
        int pc = bytecode.size();
        pcToTokenIndex.put(pc, tokenIndex);
        bytecode.add(opcode);
    }

    private void emit(int value) {
        bytecode.add((short)value);
    }

    private void emitInt(int value) {
        bytecode.add((short)(value >> 16));  // High 16 bits
        bytecode.add((short)value);          // Low 16 bits
    }

    /**
     * Emit a short value (register index, small immediate, etc.).
     */
    private void emitShort(int value) {
        bytecode.add((short)value);
    }

    /**
     * Emit a register index as a short value.
     * Registers are now 16-bit (0-65535) instead of 8-bit (0-255).
     */
    private void emitReg(int register) {
        bytecode.add((short)register);
    }

    /**
     * Patch a 2-short (4-byte) int offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    private void patchIntOffset(int position, int target) {
        // Store absolute target address (not relative offset) as 2 shorts
        bytecode.set(position, (short)((target >> 16) & 0xFFFF));      // High 16 bits
        bytecode.set(position + 1, (short)(target & 0xFFFF));          // Low 16 bits
    }

    /**
     * Helper for forward jumps - emit placeholder and return position for later patching.
     */
    private void emitJumpPlaceholder() {
        emitInt(0);  // 2 shorts (4 bytes) placeholder
    }

    private void patchJump(int placeholderPos, int target) {
        patchIntOffset(placeholderPos, target);
    }

    /**
     * Patch a short offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    private void patchShortOffset(int position, int target) {
        bytecode.set(position, (short)target);
    }

    /**
     * Convert List<Short> bytecode to short[] array.
     */
    private short[] toShortArray() {
        short[] result = new short[bytecode.size()];
        for (int i = 0; i < bytecode.size(); i++) {
            result[i] = bytecode.get(i);
        }
        return result;
    }

    // =========================================================================
    // UNIMPLEMENTED VISITOR METHODS (TODO)
    // =========================================================================

    @Override
    public void visit(ArrayLiteralNode node) {
        // Array literal: [expr1, expr2, ...]
        // In Perl, [..] creates an ARRAY REFERENCE (RuntimeScalar containing RuntimeArray)
        // Implementation:
        //   1. Create a list with all elements
        //   2. Convert list to array reference using CREATE_ARRAY (which now returns reference)

        // Fast path: empty array
        if (node.elements.isEmpty()) {
            // Create empty RuntimeList
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(0); // count = 0

            // Convert to RuntimeArray reference (CREATE_ARRAY now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_ARRAY);
            emitReg(refReg);
            emitReg(listReg);

            lastResultReg = refReg;
            return;
        }

        // General case: evaluate all elements
        int[] elementRegs = new int[node.elements.size()];
        for (int i = 0; i < node.elements.size(); i++) {
            node.elements.get(i).accept(this);
            elementRegs[i] = lastResultReg;
        }

        // Create RuntimeList with all elements
        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emitReg(elemReg);
        }

        // Convert list to array reference (CREATE_ARRAY now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_ARRAY);
        emitReg(refReg);
        emitReg(listReg);

        lastResultReg = refReg;
    }

    @Override
    public void visit(HashLiteralNode node) {
        // Hash literal: {key1 => value1, key2 => value2, ...}
        // Can also contain array expansion: {a => 1, @x}
        // In Perl, {...} creates a HASH REFERENCE (RuntimeScalar containing RuntimeHash)
        //
        // Implementation:
        //   1. Create a RuntimeList with all elements (including arrays)
        //   2. Call CREATE_HASH which flattens arrays and creates hash reference

        // Fast path: empty hash
        if (node.elements.isEmpty()) {
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(0); // count = 0

            // Create hash reference (CREATE_HASH now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_HASH);
            emitReg(refReg);
            emitReg(listReg);

            lastResultReg = refReg;
            return;
        }

        // General case: evaluate all elements
        int[] elementRegs = new int[node.elements.size()];
        for (int i = 0; i < node.elements.size(); i++) {
            node.elements.get(i).accept(this);
            elementRegs[i] = lastResultReg;
        }

        // Create RuntimeList with all elements
        // Arrays will be included as-is; RuntimeHash.createHash() will flatten them
        int listReg = allocateRegister();
        emit(Opcodes.CREATE_LIST);
        emitReg(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emitReg(elemReg);
        }

        // Create hash reference (CREATE_HASH now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_HASH);
        emitReg(refReg);
        emitReg(listReg);

        lastResultReg = refReg;
    }

    @Override
    public void visit(SubroutineNode node) {
        if (node.useTryCatch) {
            // This is an eval block: eval { ... }
            visitEvalBlock(node);
        } else if (node.name == null || node.name.equals("<anon>")) {
            // Anonymous subroutine: sub { ... }
            visitAnonymousSubroutine(node);
        } else {
            // Named subroutine: sub foo { ... }
            // NOTE: Named subs are compiled by the parser, not here.
            // They use the JVM compiler which has full closure support.
            // This is fine because compiled and interpreted code share the same RuntimeCode API.
            visitNamedSubroutine(node);
        }
    }

    /**
     * Visit a named subroutine: sub foo { ... }
     *
     * NOTE: In practice, named subroutines are compiled by the parser using the JVM compiler,
     * not the interpreter. This method exists for completeness but may not be called for
     * typical named sub definitions. The parser creates compiled RuntimeCode objects that
     * interoperate seamlessly with interpreted code via the shared RuntimeCode.apply() API.
     */
    private void visitNamedSubroutine(SubroutineNode node) {
        // Step 1: Collect outer variables used by this subroutine
        Set<String> usedVars = new HashSet<>();
        VariableCollectorVisitor collector = new VariableCollectorVisitor(usedVars);
        node.block.accept(collector);

        // Step 2: Filter to only include lexical variables that exist in current scope
        List<String> closureVarNames = new ArrayList<>();
        List<Integer> closureVarIndices = new ArrayList<>();

        for (String varName : usedVars) {
            int varIndex = getVariableRegister(varName);
            if (varIndex != -1 && varIndex >= 3) {
                closureVarNames.add(varName);
                closureVarIndices.add(varIndex);
            }
        }

        // If there are closure variables, we need to store them in PersistentVariable globals
        // so the named sub can retrieve them using RETRIEVE_BEGIN opcodes
        int beginId = 0;
        if (!closureVarIndices.isEmpty()) {
            // Assign a unique BEGIN ID for this subroutine
            beginId = org.perlonjava.codegen.EmitterMethodCreator.classCounter++;

            // Store each closure variable in PersistentVariable globals
            for (int i = 0; i < closureVarNames.size(); i++) {
                String varName = closureVarNames.get(i);
                int varReg = closureVarIndices.get(i);

                // Get the variable type from the sigil
                String sigil = varName.substring(0, 1);
                String bareVarName = varName.substring(1);
                String beginVarName = org.perlonjava.runtime.PersistentVariable.beginPackage(beginId) + "::" + bareVarName;

                // Store the variable value in PersistentVariable global
                int nameIdx = addToStringPool(beginVarName);
                switch (sigil) {
                    case "$" -> {
                        emit(Opcodes.STORE_GLOBAL_SCALAR);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                    case "@" -> {
                        emit(Opcodes.STORE_GLOBAL_ARRAY);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                    case "%" -> {
                        emit(Opcodes.STORE_GLOBAL_HASH);
                        emit(nameIdx);
                        emitReg(varReg);
                    }
                }
            }
        }

        // Step 3: Create a new BytecodeCompiler for the subroutine body
        BytecodeCompiler subCompiler = new BytecodeCompiler(
            this.sourceName,
            node.getIndex(),
            this.errorUtil
        );

        // Set the BEGIN ID in the sub-compiler so it knows to use RETRIEVE_BEGIN opcodes
        subCompiler.currentSubroutineBeginId = beginId;
        subCompiler.currentSubroutineClosureVars = new HashSet<>(closureVarNames);

        // Step 4: Compile the subroutine body
        // Sub-compiler will use RETRIEVE_BEGIN opcodes for closure variables
        InterpretedCode subCode = subCompiler.compile(node.block);

        // Step 5: Emit bytecode to create closure or simple code ref
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emitReg(codeReg);
            emit(constIdx);
        } else {
            // Store the InterpretedCode directly (closures are handled via PersistentVariable)
            RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emitReg(codeReg);
            emit(constIdx);
        }

        // Step 6: Store in global namespace
        String fullName = node.name;
        if (!fullName.contains("::")) {
            fullName = getCurrentPackage() + "::" + fullName;  // Use getCurrentPackage() for proper package tracking
        }

        int nameIdx = addToStringPool(fullName);
        emit(Opcodes.STORE_GLOBAL_CODE);
        emit(nameIdx);
        emitReg(codeReg);

        lastResultReg = -1;
    }

    /**
     * Visit an anonymous subroutine: sub { ... }
     *
     * Compiles the subroutine body to bytecode and creates an InterpretedCode instance.
     * Handles closure variable capture if needed.
     *
     * The result is an InterpretedCode wrapped in RuntimeScalar, stored in lastResultReg.
     */
    /**
     * Visit an anonymous subroutine: sub { ... }
     *
     * Compiles the subroutine body to bytecode with closure support.
     * Anonymous subs capture lexical variables from the enclosing scope.
     */
    private void visitAnonymousSubroutine(SubroutineNode node) {
        // Step 1: Collect outer variables used by this subroutine
        Set<String> usedVars = new HashSet<>();
        VariableCollectorVisitor collector = new VariableCollectorVisitor(usedVars);
        node.block.accept(collector);

        // Step 2: Filter to only include lexical variables that exist in current scope
        List<String> closureVarNames = new ArrayList<>();
        List<Integer> closureVarIndices = new ArrayList<>();

        for (String varName : usedVars) {
            int varIndex = getVariableRegister(varName);
            if (varIndex != -1 && varIndex >= 3) {
                closureVarNames.add(varName);
                closureVarIndices.add(varIndex);
            }
        }

        // Step 3: Create a new BytecodeCompiler for the subroutine body
        // Build a variable registry from current scope to pass to sub-compiler
        // This allows nested closures to see grandparent scope variables
        Map<String, Integer> parentRegistry = new HashMap<>();
        parentRegistry.put("this", 0);
        parentRegistry.put("@_", 1);
        parentRegistry.put("wantarray", 2);

        // Add captured variables with adjusted indices (starting at 3)
        for (int i = 0; i < closureVarNames.size(); i++) {
            parentRegistry.put(closureVarNames.get(i), 3 + i);
        }

        BytecodeCompiler subCompiler = new BytecodeCompiler(
            this.sourceName,
            node.getIndex(),
            this.errorUtil,
            parentRegistry  // Pass parent variable registry for nested closure support
        );

        // Step 4: Compile the subroutine body
        // Sub-compiler will use parentRegistry to resolve captured variables
        InterpretedCode subCode = subCompiler.compile(node.block);

        // Step 5: Create closure or simple code ref
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            // No closures - just wrap the InterpretedCode
            RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emitReg(codeReg);
            emit(constIdx);
        } else {
            // Has closures - emit CREATE_CLOSURE
            int templateIdx = addToConstantPool(subCode);
            emit(Opcodes.CREATE_CLOSURE);
            emitReg(codeReg);
            emit(templateIdx);
            emit(closureVarIndices.size());
            for (int regIdx : closureVarIndices) {
                emit(regIdx);
            }
        }

        lastResultReg = codeReg;
    }

    /**
     * Visit an eval block: eval { ... }
     *
     * Generates:
     *   EVAL_TRY catch_offset    # Set up exception handler, clear $@
     *   ... block bytecode ...
     *   EVAL_END                 # Clear $@ on success
     *   GOTO end
     *   LABEL catch:
     *   EVAL_CATCH rd            # Set $@, store undef in rd
     *   LABEL end:
     *
     * The result is stored in lastResultReg.
     */
    private void visitEvalBlock(SubroutineNode node) {
        int resultReg = allocateRegister();

        // Emit EVAL_TRY with placeholder for catch target (absolute address)
        emitWithToken(Opcodes.EVAL_TRY, node.getIndex());
        int catchTargetPos = bytecode.size();
        emitInt(0); // Placeholder for absolute catch address (4 bytes)

        // Compile the eval block body
        node.block.accept(this);

        // Store result from block
        if (lastResultReg >= 0) {
            emit(Opcodes.MOVE);
            emitReg(resultReg);
            emitReg(lastResultReg);
        }

        // Emit EVAL_END (clears $@)
        emit(Opcodes.EVAL_END);

        // Jump over catch block to end
        emit(Opcodes.GOTO);
        int gotoEndPos = bytecode.size();
        emitInt(0); // Placeholder for absolute end address (4 bytes)

        // CATCH block starts here
        int catchPc = bytecode.size();

        // Patch EVAL_TRY with absolute catch target (4 bytes)
        patchIntOffset(catchTargetPos, catchPc);

        // Emit EVAL_CATCH (sets $@, stores undef)
        emit(Opcodes.EVAL_CATCH);
        emitReg(resultReg);

        // END label (after catch)
        int endPc = bytecode.size();

        // Patch GOTO with absolute end target (4 bytes)
        patchIntOffset(gotoEndPos, endPc);

        lastResultReg = resultReg;
    }

    @Override
    public void visit(For1Node node) {
        // For1Node: foreach-style loop
        // for my $var (@list) { body }

        // Step 1: Evaluate list in list context
        node.list.accept(this);
        int listReg = lastResultReg;

        // Step 2: Create iterator from the list
        // This works for RuntimeArray, RuntimeList, PerlRange, etc.
        int iterReg = allocateRegister();
        emit(Opcodes.ITERATOR_CREATE);
        emitReg(iterReg);
        emitReg(listReg);

        // Step 3: Enter new scope for loop variable
        enterScope();

        // Step 4: Declare loop variable in the new scope
        int varReg = -1;
        if (node.variable != null && node.variable instanceof OperatorNode) {
            OperatorNode varOp = (OperatorNode) node.variable;
            if (varOp.operator.equals("my") && varOp.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) varOp.operand;
                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;
                    varReg = addVariable(varName, "my");
                }
            }
        }

        // If no variable declared, allocate a temporary register
        if (varReg == -1) {
            varReg = allocateRegister();
        }

        // Step 5: Loop start - combined check/next/exit (superinstruction)
        int loopStartPc = bytecode.size();

        // Emit FOREACH_NEXT_OR_EXIT superinstruction
        // This combines: hasNext check, next() call, and conditional jump
        // Format: FOREACH_NEXT_OR_EXIT varReg, iterReg, exitTarget (absolute address)
        emit(Opcodes.FOREACH_NEXT_OR_EXIT);
        emitReg(varReg);        // destination register for element
        emitReg(iterReg);       // iterator register
        int loopEndJumpPc = bytecode.size();
        emitInt(0);             // placeholder for exit target (absolute, will be patched)

        // Step 6: Execute body
        if (node.body != null) {
            node.body.accept(this);
        }

        // Step 7: Jump back to loop start
        emit(Opcodes.GOTO);
        emitInt(loopStartPc);

        // Step 8: Loop end - patch the forward jump
        int loopEndPc = bytecode.size();
        patchJump(loopEndJumpPc, loopEndPc);

        // Step 9: Exit scope
        exitScope();

        lastResultReg = -1;  // For loop returns empty
    }

    @Override
    public void visit(For3Node node) {
        // For3Node: C-style for loop or bare block
        // for (init; condition; increment) { body }
        // { body }  (bare block - isSimpleBlock=true)

        // Handle bare blocks (simple blocks) differently - they execute once, not loop
        if (node.isSimpleBlock) {
            // Simple bare block: { statements; }
            // Create a new scope for the block
            enterScope();
            try {
                // Just execute the body once, no loop
                if (node.body != null) {
                    node.body.accept(this);
                }
                lastResultReg = -1;  // Block returns empty
            } finally {
                // Exit scope to clean up lexical variables
                exitScope();
            }
            return;
        }

        // Step 1: Execute initialization
        if (node.initialization != null) {
            node.initialization.accept(this);
        }

        // Step 2: Loop start
        int loopStartPc = bytecode.size();

        // Step 3: Check condition
        int condReg = allocateRegister();
        if (node.condition != null) {
            node.condition.accept(this);
            condReg = lastResultReg;
        } else {
            // No condition means infinite loop - load true
            emit(Opcodes.LOAD_INT);
            emitReg(condReg);
            emitInt(1);
        }

        // Step 4: If condition is false, jump to end
        emit(Opcodes.GOTO_IF_FALSE);
        emitReg(condReg);
        int loopEndJumpPc = bytecode.size();
        emitInt(0);  // Placeholder for jump target (will be patched)

        // Step 5: Execute body
        if (node.body != null) {
            node.body.accept(this);
        }

        // Step 6: Execute continue block if present
        if (node.continueBlock != null) {
            node.continueBlock.accept(this);
        }

        // Step 7: Execute increment
        if (node.increment != null) {
            node.increment.accept(this);
        }

        // Step 8: Jump back to loop start
        emit(Opcodes.GOTO);
        emitInt(loopStartPc);

        // Step 9: Loop end - patch the forward jump
        int loopEndPc = bytecode.size();
        patchJump(loopEndJumpPc, loopEndPc);

        lastResultReg = -1;  // For loop returns empty
    }

    @Override
    public void visit(IfNode node) {
        // Compile condition
        node.condition.accept(this);
        int condReg = lastResultReg;

        // Mark position for forward jump to else/end
        int ifFalsePos = bytecode.size();
        emit(Opcodes.GOTO_IF_FALSE);
        emitReg(condReg);
        emitInt(0); // Placeholder for else/end target

        // Compile then block
        if (node.thenBranch != null) {
            node.thenBranch.accept(this);
        }
        int thenResultReg = lastResultReg;

        if (node.elseBranch != null) {
            // Need to jump over else block after then block executes
            int gotoEndPos = bytecode.size();
            emit(Opcodes.GOTO);
            emitInt(0); // Placeholder for end target

            // Patch if-false jump to here (start of else block)
            int elseStart = bytecode.size();
            patchIntOffset(ifFalsePos + 2, elseStart);

            // Compile else block
            node.elseBranch.accept(this);
            int elseResultReg = lastResultReg;

            // Both branches should produce results in the same register
            // If they differ, move else result to then result register
            if (thenResultReg >= 0 && elseResultReg >= 0 && thenResultReg != elseResultReg) {
                emit(Opcodes.MOVE);
                emitReg(thenResultReg);
                emitReg(elseResultReg);
            }

            // Patch goto-end jump to here
            int endPos = bytecode.size();
            patchIntOffset(gotoEndPos + 1, endPos);

            lastResultReg = thenResultReg >= 0 ? thenResultReg : elseResultReg;
        } else {
            // No else block - patch if-false jump to here (after then block)
            int endPos = bytecode.size();
            patchIntOffset(ifFalsePos + 2, endPos);

            lastResultReg = thenResultReg;
        }
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        // condition ? true_expr : false_expr
        // Implementation:
        //   eval condition
        //   if (!condition) goto false_label
        //   rd = true_expr
        //   goto end_label
        //   false_label:
        //   rd = false_expr
        //   end_label:

        // Compile condition
        node.condition.accept(this);
        int condReg = lastResultReg;

        // Allocate result register
        int rd = allocateRegister();

        // Mark position for forward jump to false expression
        int ifFalsePos = bytecode.size();
        emit(Opcodes.GOTO_IF_FALSE);
        emitReg(condReg);
        emitInt(0); // Placeholder for false_label

        // Compile true expression
        node.trueExpr.accept(this);
        int trueReg = lastResultReg;

        // Move true result to rd
        emit(Opcodes.MOVE);
        emitReg(rd);
        emitReg(trueReg);

        // Jump over false expression
        int gotoEndPos = bytecode.size();
        emit(Opcodes.GOTO);
        emitInt(0); // Placeholder for end_label

        // Patch if-false jump to here (start of false expression)
        int falseStart = bytecode.size();
        patchIntOffset(ifFalsePos + 2, falseStart);

        // Compile false expression
        node.falseExpr.accept(this);
        int falseReg = lastResultReg;

        // Move false result to rd
        emit(Opcodes.MOVE);
        emitReg(rd);
        emitReg(falseReg);

        // Patch goto-end jump to here
        int endPos = bytecode.size();
        patchIntOffset(gotoEndPos + 1, endPos);

        lastResultReg = rd;
    }

    @Override
    public void visit(TryNode node) {
        throw new UnsupportedOperationException("Try/catch not yet implemented");
    }

    @Override
    public void visit(LabelNode node) {
        // Labels are tracked in loops, standalone labels are no-ops
        lastResultReg = -1;
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags affect parsing, not runtime - no-op
        lastResultReg = -1;
    }

    @Override
    public void visit(FormatNode node) {
        throw new UnsupportedOperationException("Formats not yet implemented");
    }

    @Override
    public void visit(ListNode node) {
        // Handle ListNode - represents (expr1, expr2, ...) in Perl

        // Fast path: empty list
        if (node.elements.isEmpty()) {
            // In SCALAR context, return undef; in LIST context, return empty list
            if (currentCallContext == RuntimeContextType.SCALAR) {
                int rd = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emitReg(rd);
                lastResultReg = rd;
            } else {
                int listReg = allocateRegister();
                emit(Opcodes.CREATE_LIST);
                emitReg(listReg);
                emit(0); // count = 0
                lastResultReg = listReg;
            }
            return;
        }

        // In SCALAR context, evaluate all elements except last for side effects
        // and return only the last element's value (like compiled backend does)
        if (currentCallContext == RuntimeContextType.SCALAR) {
            // Evaluate all elements except the last in SCALAR context for side effects
            for (int i = 0; i < node.elements.size() - 1; i++) {
                node.elements.get(i).accept(this);
                // Result is discarded (side effects only)
            }
            // Evaluate and keep the last element
            node.elements.get(node.elements.size() - 1).accept(this);
            // lastResultReg already contains the last element's value
            return;
        }

        // Fast path: single element in LIST context
        // In list context, returns a RuntimeList with one element
        // List elements should be evaluated in LIST context
        if (node.elements.size() == 1) {
            int savedContext = currentCallContext;
            currentCallContext = RuntimeContextType.LIST;
            try {
                node.elements.get(0).accept(this);
                int elemReg = lastResultReg;

                int listReg = allocateRegister();
                emit(Opcodes.CREATE_LIST);
                emitReg(listReg);
                emit(1); // count = 1
                emitReg(elemReg);
                lastResultReg = listReg;
            } finally {
                currentCallContext = savedContext;
            }
            return;
        }

        // General case: multiple elements in LIST context
        // Evaluate each element into a register
        // List elements should be evaluated in LIST context
        int savedContext = currentCallContext;
        currentCallContext = RuntimeContextType.LIST;
        try {
            int[] elementRegs = new int[node.elements.size()];
            for (int i = 0; i < node.elements.size(); i++) {
                node.elements.get(i).accept(this);
                elementRegs[i] = lastResultReg;
            }

            // Create RuntimeList with all elements
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emitReg(listReg);
            emit(node.elements.size()); // count

            // Emit register numbers for each element
            for (int elemReg : elementRegs) {
                emitReg(elemReg);
            }

            lastResultReg = listReg;
        } finally {
            currentCallContext = savedContext;
        }
    }
}
