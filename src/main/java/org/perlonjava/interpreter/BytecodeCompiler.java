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
    private final ByteArrayOutputStream bytecode = new ByteArrayOutputStream();
    private final List<Object> constants = new ArrayList<>();
    private final List<String> stringPool = new ArrayList<>();

    // Simple variable-to-register mapping for the interpreter
    // Each scope is a Map<String, Integer> mapping variable names to register indices
    private final Stack<Map<String, Integer>> variableScopes = new Stack<>();

    // Track current package name (for global variables)
    private String currentPackage = "main";

    // Token index tracking for error reporting
    private final Map<Integer, Integer> pcToTokenIndex = new HashMap<>();
    private int currentTokenIndex = -1;  // Track current token for error reporting

    // Error reporting
    private final ErrorMessageUtil errorUtil;

    // Register allocation
    private int nextRegister = 3;  // 0=this, 1=@_, 2=wantarray

    // Track last result register for expression chaining
    private int lastResultReg = -1;

    // Track current calling context for subroutine calls
    private int currentCallContext = RuntimeContextType.LIST; // Default to LIST

    // Closure support
    private RuntimeBase[] capturedVars;           // Captured variable values
    private String[] capturedVarNames;            // Parallel array of names
    private Map<String, Integer> capturedVarIndices;  // Name → register index

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
        return reg;
    }

    /**
     * Helper: Enter a new lexical scope.
     */
    private void enterScope() {
        variableScopes.push(new HashMap<>());
    }

    /**
     * Helper: Exit the current lexical scope.
     */
    private void exitScope() {
        if (variableScopes.size() > 1) {
            variableScopes.pop();
        }
    }

    /**
     * Helper: Get current package name for global variable resolution.
     */
    private String getCurrentPackage() {
        return currentPackage;
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
        // Detect closure variables if context is provided
        if (ctx != null) {
            detectClosureVariables(node, ctx);
        }

        // If we have captured variables, allocate registers for them
        if (capturedVars != null && capturedVars.length > 0) {
            // Registers 0-2 are reserved (this, @_, wantarray)
            // Registers 3+ are captured variables
            nextRegister = 3 + capturedVars.length;
        }

        // Visit the node to generate bytecode
        node.accept(this);

        // Emit RETURN with last result register (or register 0 for empty)
        emit(Opcodes.RETURN);
        emit(lastResultReg >= 0 ? lastResultReg : 0);

        // Build variable registry for eval STRING support
        // This maps variable names to their register indices for variable capture
        Map<String, Integer> variableRegistry = new HashMap<>();
        for (Map<String, Integer> scope : variableScopes) {
            variableRegistry.putAll(scope);
        }

        // Build InterpretedCode
        return new InterpretedCode(
            bytecode.toByteArray(),
            constants.toArray(),
            stringPool.toArray(new String[0]),
            nextRegister,  // maxRegisters
            capturedVars,  // NOW POPULATED!
            sourceName,
            sourceLine,
            pcToTokenIndex,  // Pass token index map for error reporting
            variableRegistry  // Variable registry for eval STRING
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
        // Visit each statement in the block
        for (Node stmt : node.elements) {
            // Standalone statements (not assignments) use VOID context
            int savedContext = currentCallContext;

            // If this is not an assignment or other value-using construct, use VOID context
            if (!(stmt instanceof BinaryOperatorNode && ((BinaryOperatorNode) stmt).operator.equals("="))) {
                currentCallContext = RuntimeContextType.VOID;
            }

            stmt.accept(this);

            currentCallContext = savedContext;
        }
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
                emit(rd);
                emitInt(intValue);
            } else if (isLargeInteger) {
                // Large integer - store as string to preserve precision (32-bit Perl emulation)
                int strIdx = addToStringPool(value);
                emit(Opcodes.LOAD_STRING);
                emit(rd);
                emit(strIdx);
            } else {
                // Floating-point number - create RuntimeScalar with double value
                RuntimeScalar doubleScalar = new RuntimeScalar(Double.parseDouble(value));
                int constIdx = addToConstantPool(doubleScalar);
                emit(Opcodes.LOAD_CONST);
                emit(rd);
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
        emit(rd);
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
                // Global variable
                int rd = allocateRegister();
                int nameIdx = addToStringPool(varName);

                emit(Opcodes.LOAD_GLOBAL_SCALAR);
                emit(rd);
                emit(nameIdx);

                lastResultReg = rd;
            }
        }
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
            emit(contentReg);
            emit(filehandleReg);

            // print/say return 1 on success
            int rd = allocateRegister();
            emit(Opcodes.LOAD_INT);
            emit(rd);
            emitInt(1);

            lastResultReg = rd;
            return;
        }

        // Handle assignment separately (doesn't follow standard left-right-op pattern)
        if (node.operator.equals("=")) {
            // Determine the calling context for the RHS based on LHS type
            int rhsContext = RuntimeContextType.LIST; // Default

            // Check if LHS is a scalar assignment (my $x = ...)
            if (node.left instanceof OperatorNode) {
                OperatorNode leftOp = (OperatorNode) node.left;
                if (leftOp.operator.equals("my") && leftOp.operand instanceof OperatorNode) {
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

                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_SCALAR);
                                emit(reg);
                                emit(nameIdx);
                                emit(beginId);

                                // Now register contains a reference to the persistent RuntimeScalar
                                // Store the initializer value INTO that RuntimeScalar
                                node.right.accept(this);
                                int valueReg = lastResultReg;

                                // Set the value in the persistent scalar using SET_SCALAR
                                // This calls .set() on the RuntimeScalar without overwriting the reference
                                emit(Opcodes.SET_SCALAR);
                                emit(reg);
                                emit(valueReg);

                                // Track this variable - map the name to the register we already allocated
                                variableScopes.peek().put(varName, reg);
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
                            emit(reg);
                            emit(valueReg);

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

                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_ARRAY);
                                emit(arrayReg);
                                emit(nameIdx);
                                emit(beginId);

                                // Compile RHS (should evaluate to a list)
                                node.right.accept(this);
                                int listReg = lastResultReg;

                                // Populate array from list
                                emit(Opcodes.ARRAY_SET_FROM_LIST);
                                emit(arrayReg);
                                emit(listReg);

                                // Track this variable - map the name to the register we already allocated
                                variableScopes.peek().put(varName, arrayReg);
                                lastResultReg = arrayReg;
                                return;
                            }

                            // Regular lexical array (not captured)
                            // Allocate register for new lexical array and add to symbol table
                            int arrayReg = addVariable(varName, "my");

                            // Create empty array
                            emit(Opcodes.NEW_ARRAY);
                            emit(arrayReg);

                            // Compile RHS (should evaluate to a list)
                            node.right.accept(this);
                            int listReg = lastResultReg;

                            // Populate array from list using setFromList
                            emit(Opcodes.ARRAY_SET_FROM_LIST);
                            emit(arrayReg);
                            emit(listReg);

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

                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_HASH);
                                emit(hashReg);
                                emit(nameIdx);
                                emit(beginId);

                                // Compile RHS (should evaluate to a list)
                                node.right.accept(this);
                                int listReg = lastResultReg;

                                // Populate hash from list
                                emit(Opcodes.HASH_SET_FROM_LIST);
                                emit(hashReg);
                                emit(listReg);

                                // Track this variable - map the name to the register we already allocated
                                variableScopes.peek().put(varName, hashReg);
                                lastResultReg = hashReg;
                                return;
                            }

                            // Regular lexical hash (not captured)
                            // Allocate register for new lexical hash and add to symbol table
                            int hashReg = addVariable(varName, "my");

                            // Create empty hash
                            emit(Opcodes.NEW_HASH);
                            emit(hashReg);

                            // Compile RHS (should evaluate to a list)
                            node.right.accept(this);
                            int listReg = lastResultReg;

                            // Populate hash from list
                            emit(Opcodes.HASH_SET_FROM_LIST);
                            emit(hashReg);
                            emit(listReg);

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
                        emit(reg);
                        emit(valueReg);

                        lastResultReg = reg;
                        return;
                    }
                }

                // Special case: local $x = value
                if (leftOp.operator.equals("local")) {
                    // Extract variable from "local" operand
                    Node localOperand = leftOp.operand;

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

                            // It's a global variable - emit SLOW_OP to call GlobalRuntimeScalar.makeLocal()
                            String packageName = getCurrentPackage();
                            String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                            int nameIdx = addToStringPool(globalVarName);

                            int localReg = allocateRegister();
                            emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                            emit(Opcodes.SLOWOP_LOCAL_SCALAR);
                            emit(localReg);
                            emit(nameIdx);

                            // Compile RHS
                            node.right.accept(this);
                            int valueReg = lastResultReg;

                            // Assign value to the localized variable
                            // The localized variable is a RuntimeScalar, so we use set() on it
                            emit(Opcodes.STORE_GLOBAL_SCALAR);
                            emit(nameIdx);
                            emit(valueReg);

                            lastResultReg = localReg;
                            return;
                        }
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
                            emit(targetReg);
                            emit(rhsReg);

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
                            emit(targetReg);
                            emit(valueReg);
                        } else {
                            // Regular lexical - use MOVE
                            emit(Opcodes.MOVE);
                            emit(targetReg);
                            emit(valueReg);
                        }

                        lastResultReg = targetReg;
                    } else {
                        // Global variable
                        int nameIdx = addToStringPool(varName);
                        emit(Opcodes.STORE_GLOBAL_SCALAR);
                        emit(nameIdx);
                        emit(valueReg);
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
                        emit(arrayReg);
                        emit(nameIdx);
                    }

                    // Populate array from list using setFromList
                    emit(Opcodes.ARRAY_SET_FROM_LIST);
                    emit(arrayReg);
                    emit(valueReg);

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
                        emit(hashReg);
                        emit(nameIdx);
                    }

                    // Populate hash from list using setFromList
                    emit(Opcodes.HASH_SET_FROM_LIST);
                    emit(hashReg);
                    emit(valueReg);

                    lastResultReg = hashReg;
                } else {
                    throw new RuntimeException("Assignment to unsupported operator: " + leftOp.operator);
                }
            } else if (node.left instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.left).name;

                if (hasVariable(varName)) {
                    // Lexical variable - copy to its register
                    int targetReg = getVariableRegister(varName);
                    emit(Opcodes.MOVE);
                    emit(targetReg);
                    emit(valueReg);
                    lastResultReg = targetReg;
                } else {
                    // Global variable
                    int nameIdx = addToStringPool(varName);
                    emit(Opcodes.STORE_GLOBAL_SCALAR);
                    emit(nameIdx);
                    emit(valueReg);
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
                            emit(arrayReg);
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
                        emit(indicesReg);
                        emit(indexRegs.size());
                        for (int indexReg : indexRegs) {
                            emit(indexReg);
                        }

                        // Compile values (RHS of assignment)
                        node.right.accept(this);
                        int valuesReg = lastResultReg;

                        // Emit SLOW_OP with SLOWOP_ARRAY_SLICE_SET
                        emit(Opcodes.SLOW_OP);
                        emit(Opcodes.SLOWOP_ARRAY_SLICE_SET);
                        emit(arrayReg);
                        emit(indicesReg);
                        emit(valuesReg);

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
                                emit(arrayReg);
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
                        emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                        emit(Opcodes.SLOWOP_DEREF_ARRAY);
                        emit(arrayReg);
                        emit(scalarReg);
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
                    emit(arrayReg);
                    emit(indexReg);
                    emit(assignValueReg);

                    lastResultReg = assignValueReg;
                    currentCallContext = savedContext;
                    return;
                }

                throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            } else {
                throwCompilerException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            }

            return;
            } finally {
                // Always restore the calling context
                currentCallContext = savedContext;
            }
        }

        // Compile left and right operands
        node.left.accept(this);
        int rs1 = lastResultReg;

        node.right.accept(this);
        int rs2 = lastResultReg;

        // Allocate result register
        int rd = allocateRegister();

        // Emit opcode based on operator
        switch (node.operator) {
            case "+" -> {
                emit(Opcodes.ADD_SCALAR);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "-" -> {
                emit(Opcodes.SUB_SCALAR);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "*" -> {
                emit(Opcodes.MUL_SCALAR);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "%" -> {
                emit(Opcodes.MOD_SCALAR);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "." -> {
                emit(Opcodes.CONCAT);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "x" -> {
                emit(Opcodes.REPEAT);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "<=>" -> {
                emit(Opcodes.COMPARE_NUM);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "==" -> {
                emit(Opcodes.EQ_NUM);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "<" -> {
                emit(Opcodes.LT_NUM);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case ">" -> {
                emit(Opcodes.GT_NUM);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "!=" -> {
                emit(Opcodes.NE_NUM);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case "(", "()", "->" -> {
                // Apply operator: $coderef->(args) or &subname(args) or foo(args)
                // left (rs1) = code reference (RuntimeScalar containing RuntimeCode or SubroutineNode)
                // right (rs2) = arguments (should be RuntimeList from ListNode)

                // Note: rs2 should contain a RuntimeList (from visiting the ListNode)
                // We need to convert it to RuntimeArray for the CALL_SUB opcode

                // For now, rs2 is a RuntimeList - we'll pass it directly and let
                // BytecodeInterpreter convert it to RuntimeArray

                // Emit CALL_SUB: rd = coderef.apply(args, context)
                emit(Opcodes.CALL_SUB);
                emit(rd);  // Result register
                emit(rs1); // Code reference register
                emit(rs2); // Arguments register (RuntimeList to be converted to RuntimeArray)
                emit(currentCallContext); // Use current calling context

                // Note: CALL_SUB may return RuntimeControlFlowList
                // The interpreter will handle control flow propagation
            }
            case "join" -> {
                // String join: rd = join(separator, list)
                // left (rs1) = separator (empty string for interpolation)
                // right (rs2) = list of elements

                emit(Opcodes.JOIN);
                emit(rd);
                emit(rs1);
                emit(rs2);
            }
            case ".." -> {
                // Range operator: start..end
                // Create a PerlRange object which can be iterated or converted to a list

                // Optimization: if both operands are constant numbers, create range at compile time
                if (node.left instanceof NumberNode && node.right instanceof NumberNode) {
                    try {
                        // Remove underscores for parsing (Perl allows them as digit separators)
                        String startStr = ((NumberNode) node.left).value.replace("_", "");
                        String endStr = ((NumberNode) node.right).value.replace("_", "");

                        int start = Integer.parseInt(startStr);
                        int end = Integer.parseInt(endStr);

                        // Create PerlRange with RuntimeScalarCache integers
                        RuntimeScalar startScalar = RuntimeScalarCache.getScalarInt(start);
                        RuntimeScalar endScalar = RuntimeScalarCache.getScalarInt(end);
                        PerlRange range = PerlRange.createRange(startScalar, endScalar);

                        // Store in constant pool and load
                        int constIdx = addToConstantPool(range);
                        emit(Opcodes.LOAD_CONST);
                        emit(rd);
                        emit(constIdx);
                    } catch (NumberFormatException e) {
                        throw new RuntimeException("Range operator requires integer values: " + e.getMessage());
                    }
                } else {
                    // Runtime range creation using RANGE opcode
                    // rs1 and rs2 already contain the start and end values
                    emit(Opcodes.RANGE);
                    emit(rd);
                    emit(rs1);
                    emit(rs2);
                }
            }
            case "&&", "and" -> {
                // Logical AND with short-circuit evaluation
                // Semantics: if left is false, return left; else evaluate and return right
                // Implementation:
                //   rd = left
                //   if (!rd) goto skip_right
                //   rd = right
                //   skip_right:

                // Left operand is already in rs1
                // Move to result register
                emit(Opcodes.MOVE);
                emit(rd);
                emit(rs1);

                // Mark position for forward jump
                int skipRightPos = bytecode.size();

                // Emit conditional jump: if (!rd) skip right evaluation
                emit(Opcodes.GOTO_IF_FALSE);
                emit(rd);
                emitInt(0); // Placeholder for offset (will be patched)

                // Right operand is already in rs2
                // Move to result register (overwriting left value)
                emit(Opcodes.MOVE);
                emit(rd);
                emit(rs2);

                // Patch the forward jump offset
                int skipRightTarget = bytecode.size();
                patchIntOffset(skipRightPos + 2, skipRightTarget);
            }
            case "||", "or" -> {
                // Logical OR with short-circuit evaluation
                // Semantics: if left is true, return left; else evaluate and return right
                // Implementation:
                //   rd = left
                //   if (rd) goto skip_right
                //   rd = right
                //   skip_right:

                // Left operand is already in rs1
                // Move to result register
                emit(Opcodes.MOVE);
                emit(rd);
                emit(rs1);

                // Mark position for forward jump
                int skipRightPos = bytecode.size();

                // Emit conditional jump: if (rd) skip right evaluation
                emit(Opcodes.GOTO_IF_TRUE);
                emit(rd);
                emitInt(0); // Placeholder for offset (will be patched)

                // Right operand is already in rs2
                // Move to result register (overwriting left value)
                emit(Opcodes.MOVE);
                emit(rd);
                emit(rs2);

                // Patch the forward jump offset
                int skipRightTarget = bytecode.size();
                patchIntOffset(skipRightPos + 2, skipRightTarget);
            }
            case "map" -> {
                // Map operator: map { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit MAP opcode
                emit(Opcodes.MAP);
                emit(rd);
                emit(rs2);       // List register
                emit(rs1);       // Closure register
                emit(RuntimeContextType.LIST);  // Map always uses list context
            }
            case "grep" -> {
                // Grep operator: grep { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit GREP opcode
                emit(Opcodes.GREP);
                emit(rd);
                emit(rs2);       // List register
                emit(rs1);       // Closure register
                emit(RuntimeContextType.LIST);  // Grep uses list context
            }
            case "sort" -> {
                // Sort operator: sort { block } list
                // rs1 = closure (SubroutineNode compiled to code reference)
                // rs2 = list expression

                // Emit SORT opcode
                emit(Opcodes.SORT);
                emit(rd);
                emit(rs2);       // List register
                emit(rs1);       // Closure register
                emitInt(addToStringPool(currentPackage));  // Package name for sort
            }
            case "split" -> {
                // Split operator: split pattern, string
                // rs1 = pattern (string or regex)
                // rs2 = list containing string to split (and optional limit)

                // Emit SLOW_OP with SLOWOP_SPLIT
                emit(Opcodes.SLOW_OP);
                emit(Opcodes.SLOWOP_SPLIT);
                emit(rd);
                emit(rs1);  // Pattern register
                emit(rs2);  // Args register
                emit(RuntimeContextType.LIST);  // Split uses list context
            }
            case "[" -> {
                // Array element access: $a[10] means get element 10 from array @a
                // Array slice: @a[1,2,3] or @a[1..3] means get multiple elements
                // Also handles multidimensional: $a[0][1] means $a[0]->[1]
                // left: OperatorNode("$", IdentifierNode("a")) for element access
                //       OperatorNode("@", IdentifierNode("a")) for array slice
                // right: ArrayLiteralNode(index_expression or indices)

                int arrayReg = -1; // Will be initialized in if/else branches

                if (node.left instanceof OperatorNode) {
                    OperatorNode leftOp = (OperatorNode) node.left;

                    // Check if this is an array slice (@array[...]) or element access ($array[...])
                    if (leftOp.operator.equals("@")) {
                        // Array slice: @array[1,2,3] or @array[1..3] or @$arrayref[1..3]

                        if (leftOp.operand instanceof IdentifierNode) {
                            // Simple case: @array[indices]
                            String varName = "@" + ((IdentifierNode) leftOp.operand).name;

                            // Get the array - check lexical first, then global
                            if (hasVariable(varName)) {
                                // Lexical array
                                arrayReg = getVariableRegister(varName);
                            } else {
                                // Global array - load it
                                arrayReg = allocateRegister();
                                String globalArrayName = NameNormalizer.normalizeVariableName(
                                    ((IdentifierNode) leftOp.operand).name,
                                    getCurrentPackage()
                                );
                                int nameIdx = addToStringPool(globalArrayName);
                                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                emit(arrayReg);
                                emit(nameIdx);
                            }
                        } else {
                            // Complex case: @$arrayref[indices] or @{expr}[indices]
                            // Compile the operand to get the array (which might involve dereferencing)
                            leftOp.accept(this);
                            arrayReg = lastResultReg;
                        }

                        // Evaluate the indices
                        if (!(node.right instanceof ArrayLiteralNode)) {
                            throwCompilerException("Array slice requires ArrayLiteralNode on right side");
                        }
                        ArrayLiteralNode indicesNode = (ArrayLiteralNode) node.right;
                        if (indicesNode.elements.isEmpty()) {
                            throwCompilerException("Array slice requires index expressions");
                        }

                        // Compile all indices into a list
                        List<Integer> indexRegs = new ArrayList<>();
                        for (Node indexExpr : indicesNode.elements) {
                            indexExpr.accept(this);
                            indexRegs.add(lastResultReg);
                        }

                        // Create a RuntimeList from these index registers
                        int indicesListReg = allocateRegister();
                        emit(Opcodes.CREATE_LIST);
                        emit(indicesListReg);
                        emit(indexRegs.size());
                        for (int indexReg : indexRegs) {
                            emit(indexReg);
                        }

                        // Emit SLOW_OP with SLOWOP_ARRAY_SLICE
                        emit(Opcodes.SLOW_OP);
                        emit(Opcodes.SLOWOP_ARRAY_SLICE);
                        emit(rd);
                        emit(arrayReg);
                        emit(indicesListReg);

                        // Array slice returns a list
                        lastResultReg = rd;
                        return;
                    } else if (leftOp.operator.equals("$")) {
                        // Single element access: $var[index]
                        if (!(leftOp.operand instanceof IdentifierNode)) {
                            throwCompilerException("Array access requires scalar dereference: $var[index]");
                        }

                        String varName = ((IdentifierNode) leftOp.operand).name;
                        String arrayVarName = "@" + varName;

                        // Get the array - check lexical first, then global
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
                            emit(arrayReg);
                            emit(nameIdx);
                        }
                    } else {
                        throwCompilerException("Array access requires scalar ($) or array (@) dereference");
                    }
                } else if (node.left instanceof BinaryOperatorNode) {
                    // Multidimensional case: $a[0][1] is really $a[0]->[1]
                    // Compile left side (which returns a scalar containing an array reference)
                    node.left.accept(this);
                    int scalarReg = lastResultReg;

                    // Dereference the array reference to get the actual array
                    // Use SLOW_OP with SLOWOP_DEREF_ARRAY
                    arrayReg = allocateRegister();
                    emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                    emit(Opcodes.SLOWOP_DEREF_ARRAY);
                    emit(arrayReg);
                    emit(scalarReg);
                } else {
                    throwCompilerException("Array access requires variable or expression on left side");
                }

                // Evaluate index expression
                // For ArrayLiteralNode, get the first element
                if (!(node.right instanceof ArrayLiteralNode)) {
                    throwCompilerException("Array access requires ArrayLiteralNode on right side");
                }
                ArrayLiteralNode indexNode = (ArrayLiteralNode) node.right;
                if (indexNode.elements.isEmpty()) {
                    throwCompilerException("Array access requires index expression");
                }

                // Compile the index expression
                indexNode.elements.get(0).accept(this);
                int indexReg = lastResultReg;

                // Emit ARRAY_GET
                emit(Opcodes.ARRAY_GET);
                emit(rd);
                emit(arrayReg);
                emit(indexReg);
            }
            case "{" -> {
                // Hash element access: $h{key} means get element 'key' from hash %h
                // left: OperatorNode("$", IdentifierNode("h"))
                // right: HashLiteralNode(key_expression)

                if (!(node.left instanceof OperatorNode)) {
                    throw new RuntimeException("Hash access requires variable on left side");
                }
                OperatorNode leftOp = (OperatorNode) node.left;
                if (!leftOp.operator.equals("$") || !(leftOp.operand instanceof IdentifierNode)) {
                    throw new RuntimeException("Hash access requires scalar dereference: $var{key}");
                }

                String varName = ((IdentifierNode) leftOp.operand).name;
                String hashVarName = "%" + varName;

                // Get the hash - check lexical first, then global
                int hashReg;
                if (hasVariable(hashVarName)) {
                    // Lexical hash
                    hashReg = getVariableRegister(hashVarName);
                } else {
                    // Global hash - load it
                    hashReg = allocateRegister();
                    String globalHashName = "main::" + varName;
                    int nameIdx = addToStringPool(globalHashName);
                    emit(Opcodes.LOAD_GLOBAL_HASH);
                    emit(hashReg);
                    emit(nameIdx);
                }

                // Evaluate key expression
                // For HashLiteralNode, get the first element (should be the key)
                if (!(node.right instanceof HashLiteralNode)) {
                    throw new RuntimeException("Hash access requires HashLiteralNode on right side");
                }
                HashLiteralNode keyNode = (HashLiteralNode) node.right;
                if (keyNode.elements.isEmpty()) {
                    throw new RuntimeException("Hash access requires key expression");
                }

                // Compile the key expression
                // Special case: bareword identifiers should be treated as string literals
                int keyReg;
                Node keyElement = keyNode.elements.get(0);
                if (keyElement instanceof IdentifierNode) {
                    // Bareword key - treat as string literal
                    String keyStr = ((IdentifierNode) keyElement).name;
                    keyReg = allocateRegister();
                    int strIdx = addToStringPool(keyStr);
                    emit(Opcodes.LOAD_STRING);
                    emit(keyReg);
                    emit(strIdx);
                } else {
                    // Expression key - evaluate normally
                    keyElement.accept(this);
                    keyReg = lastResultReg;
                }

                // Emit HASH_GET
                emit(Opcodes.HASH_GET);
                emit(rd);
                emit(hashReg);
                emit(keyReg);
            }
            case "push" -> {
                // Array push: push(@array, values...)
                // left: OperatorNode("@", IdentifierNode("array"))
                // right: ListNode with values to push

                if (!(node.left instanceof OperatorNode)) {
                    throwCompilerException("push requires array variable");
                }
                OperatorNode leftOp = (OperatorNode) node.left;
                if (!leftOp.operator.equals("@") || !(leftOp.operand instanceof IdentifierNode)) {
                    throwCompilerException("push requires array variable: push @array, values");
                }

                String varName = "@" + ((IdentifierNode) leftOp.operand).name;

                // Get the array - check lexical first, then global
                int arrayReg;
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = getCurrentPackage() + "::" + ((IdentifierNode) leftOp.operand).name;
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emit(arrayReg);
                    emit(nameIdx);
                }

                // Evaluate the values to push (right operand)
                node.right.accept(this);
                int valuesReg = lastResultReg;

                // Emit ARRAY_PUSH
                emit(Opcodes.ARRAY_PUSH);
                emit(arrayReg);
                emit(valuesReg);

                // push returns the new size of the array
                // For now, just return the array itself
                lastResultReg = arrayReg;
            }
            case "unshift" -> {
                // Array unshift: unshift(@array, values...)
                // left: OperatorNode("@", IdentifierNode("array"))
                // right: ListNode with values to unshift

                if (!(node.left instanceof OperatorNode)) {
                    throwCompilerException("unshift requires array variable");
                }
                OperatorNode leftOp = (OperatorNode) node.left;
                if (!leftOp.operator.equals("@") || !(leftOp.operand instanceof IdentifierNode)) {
                    throwCompilerException("unshift requires array variable: unshift @array, values");
                }

                String varName = "@" + ((IdentifierNode) leftOp.operand).name;

                // Get the array - check lexical first, then global
                int arrayReg;
                if (hasVariable(varName)) {
                    // Lexical array
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = getCurrentPackage() + "::" + ((IdentifierNode) leftOp.operand).name;
                    int nameIdx = addToStringPool(globalArrayName);
                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emit(arrayReg);
                    emit(nameIdx);
                }

                // Evaluate the values to unshift (right operand)
                node.right.accept(this);
                int valuesReg = lastResultReg;

                // Emit ARRAY_UNSHIFT
                emit(Opcodes.ARRAY_UNSHIFT);
                emit(arrayReg);
                emit(valuesReg);

                // unshift returns the new size of the array
                // For now, just return the array itself
                lastResultReg = arrayReg;
            }
            case "+=" -> {
                // Compound assignment: $var += $value
                // left: variable (OperatorNode)
                // right: value expression

                if (!(node.left instanceof OperatorNode)) {
                    throwCompilerException("+= requires variable on left side");
                }
                OperatorNode leftOp = (OperatorNode) node.left;
                if (!leftOp.operator.equals("$") || !(leftOp.operand instanceof IdentifierNode)) {
                    throwCompilerException("+= requires scalar variable: $var += value");
                }

                String varName = "$" + ((IdentifierNode) leftOp.operand).name;

                // Get the variable register
                if (!hasVariable(varName)) {
                    throwCompilerException("+= requires existing variable: " + varName);
                }
                int varReg = getVariableRegister(varName);

                // Compile the right side
                node.right.accept(this);
                int valueReg = lastResultReg;

                // Emit ADD_ASSIGN
                emit(Opcodes.ADD_ASSIGN);
                emit(varReg);
                emit(valueReg);

                lastResultReg = varReg;
            }
            default -> throwCompilerException("Unsupported operator: " + node.operator);
        }

        lastResultReg = rd;
    }

    @Override
    public void visit(OperatorNode node) {
        // Track token index for error reporting
        currentTokenIndex = node.getIndex();

        String op = node.operator;

        // Handle specific operators
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
                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_SCALAR);
                                emit(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                // Track this as a captured variable - map to the register we allocated
                                variableScopes.peek().put(varName, reg);
                            }
                            case "@" -> {
                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_ARRAY);
                                emit(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                variableScopes.peek().put(varName, reg);
                            }
                            case "%" -> {
                                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                                emit(Opcodes.SLOWOP_RETRIEVE_BEGIN_HASH);
                                emit(reg);
                                emit(nameIdx);
                                emit(sigilOp.id);
                                variableScopes.peek().put(varName, reg);
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
                            emit(reg);
                        }
                        case "@" -> {
                            emit(Opcodes.NEW_ARRAY);
                            emit(reg);
                        }
                        case "%" -> {
                            emit(Opcodes.NEW_HASH);
                            emit(reg);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    lastResultReg = reg;
                    return;
                }
            }
            throw new RuntimeException("Unsupported my operand: " + node.operand.getClass().getSimpleName());
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

                    // Load from global variable
                    // Get current package from symbol table
                    String packageName = getCurrentPackage();
                    String globalVarName = packageName + "::" + ((IdentifierNode) sigilOp.operand).name;
                    int nameIdx = addToStringPool(globalVarName);

                    switch (sigil) {
                        case "$" -> {
                            emit(Opcodes.LOAD_GLOBAL_SCALAR);
                            emit(reg);
                            emit(nameIdx);
                        }
                        case "@" -> {
                            emit(Opcodes.LOAD_GLOBAL_ARRAY);
                            emit(reg);
                            emit(nameIdx);
                        }
                        case "%" -> {
                            emit(Opcodes.LOAD_GLOBAL_HASH);
                            emit(reg);
                            emit(nameIdx);
                        }
                        default -> throwCompilerException("Unsupported variable type: " + sigil);
                    }

                    lastResultReg = reg;
                    return;
                }
            }
            throw new RuntimeException("Unsupported our operand: " + node.operand.getClass().getSimpleName());
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
                    emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                    emit(Opcodes.SLOWOP_LOCAL_SCALAR);
                    emit(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                    return;
                }
            }
            throw new RuntimeException("Unsupported local operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("$")) {
            // Scalar variable dereference: $x
            if (node.operand instanceof IdentifierNode) {
                String varName = "$" + ((IdentifierNode) node.operand).name;

                if (hasVariable(varName)) {
                    // Lexical variable - use existing register
                    lastResultReg = getVariableRegister(varName);
                } else {
                    // Global variable - load it
                    // Add package prefix if not present (match compiler behavior)
                    String globalVarName = varName;
                    if (!globalVarName.contains("::")) {
                        // Remove $ sigil, add package, restore sigil
                        globalVarName = "main::" + varName.substring(1);
                    }

                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(globalVarName);

                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emit(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                }
            } else {
                throw new RuntimeException("Unsupported $ operand: " + node.operand.getClass().getSimpleName());
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
                        emit(rd);
                        emit(arrayReg);
                        lastResultReg = rd;
                    } else {
                        lastResultReg = arrayReg;
                    }
                    return;
                }

                // Check if it's a lexical array
                int arrayReg;
                if (hasVariable(varName)) {
                    // Lexical array - use existing register
                    arrayReg = getVariableRegister(varName);
                } else {
                    // Global array - load it
                    arrayReg = allocateRegister();
                    String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) node.operand).name, getCurrentPackage());
                    int nameIdx = addToStringPool(globalArrayName);

                    emit(Opcodes.LOAD_GLOBAL_ARRAY);
                    emit(arrayReg);
                    emit(nameIdx);
                }

                // Check if we're in scalar context - if so, return array size
                if (currentCallContext == RuntimeContextType.SCALAR) {
                    int rd = allocateRegister();
                    emit(Opcodes.ARRAY_SIZE);
                    emit(rd);
                    emit(arrayReg);
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
                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                emit(Opcodes.SLOWOP_DEREF_ARRAY);
                emit(rd);
                emit(refReg);

                lastResultReg = rd;
                // Note: We don't check scalar context here because dereferencing
                // should return the array itself. The slice or other operation
                // will handle scalar context conversion if needed.
            } else {
                throwCompilerException("Unsupported @ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("scalar")) {
            // Scalar context: scalar(@array) or scalar(%hash) or scalar(expr)
            // Forces scalar context on the operand
            if (node.operand != null) {
                // Special case: if operand is a ListNode with single array/hash variable,
                // compile it directly in scalar context instead of list context
                if (node.operand instanceof ListNode) {
                    ListNode listNode = (ListNode) node.operand;
                    if (listNode.elements.size() == 1) {
                        Node elem = listNode.elements.get(0);
                        if (elem instanceof OperatorNode) {
                            OperatorNode opNode = (OperatorNode) elem;
                            if (opNode.operator.equals("@")) {
                                // scalar(@array) - get array size
                                if (opNode.operand instanceof IdentifierNode) {
                                    String varName = "@" + ((IdentifierNode) opNode.operand).name;

                                    int arrayReg;
                                    if (varName.equals("@_")) {
                                        arrayReg = 1;
                                    } else if (hasVariable(varName)) {
                                        arrayReg = getVariableRegister(varName);
                                    } else {
                                        // Global array
                                        arrayReg = allocateRegister();
                                        String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) opNode.operand).name, getCurrentPackage());
                                        int nameIdx = addToStringPool(globalArrayName);
                                        emit(Opcodes.LOAD_GLOBAL_ARRAY);
                                        emit(arrayReg);
                                        emit(nameIdx);
                                    }

                                    // Emit ARRAY_SIZE
                                    int rd = allocateRegister();
                                    emit(Opcodes.ARRAY_SIZE);
                                    emit(rd);
                                    emit(arrayReg);

                                    lastResultReg = rd;
                                    return;
                                }
                            } else if (opNode.operator.equals("%")) {
                                // scalar(%hash) - get hash size (not implemented yet)
                                throwCompilerException("scalar(%hash) not yet implemented");
                            }
                        }
                    }
                }

                // General case: compile operand and apply scalar context
                // ARRAY_SIZE will:
                // - Convert arrays/hashes to their size
                // - Pass through scalars unchanged
                node.operand.accept(this);
                int operandReg = lastResultReg;

                int rd = allocateRegister();
                emit(Opcodes.ARRAY_SIZE);
                emit(rd);
                emit(operandReg);

                lastResultReg = rd;
            } else {
                throwCompilerException("scalar operator requires an operand");
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
                emit(rd);
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

                // Emit SLOW_OP with SLOWOP_LOAD_GLOB
                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                emit(Opcodes.SLOWOP_LOAD_GLOB);
                emit(rd);
                emit(nameIdx);

                lastResultReg = rd;
            } else {
                throw new RuntimeException("Unsupported * operand: " + node.operand.getClass().getSimpleName());
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
                emit(rd);
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
                    emit(rd);
                    emit(valueReg);

                    lastResultReg = rd;
                } finally {
                    currentCallContext = savedContext;
                }
            } else {
                throw new RuntimeException("Reference operator requires operand");
            }
        } else if (op.equals("package")) {
            // Package declaration: package Foo;
            // This is a compile-time directive that sets the namespace context.
            // It doesn't generate any runtime bytecode.
            // The operand is an IdentifierNode with the package name.

            // Don't emit any bytecode - just leave lastResultReg unchanged
            // (or set to -1 to indicate no result)
            lastResultReg = -1;
        } else if (op.equals("say") || op.equals("print")) {
            // say/print $x
            if (node.operand != null) {
                node.operand.accept(this);
                int rs = lastResultReg;

                emit(op.equals("say") ? Opcodes.SAY : Opcodes.PRINT);
                emit(rs);
            }
        } else if (op.equals("not")) {
            // Logical NOT operator: not $x
            // Evaluate operand and emit NOT opcode
            if (node.operand != null) {
                node.operand.accept(this);
                int rs = lastResultReg;

                // Allocate result register
                int rd = allocateRegister();

                // Emit NOT opcode
                emit(Opcodes.NOT);
                emit(rd);
                emit(rs);

                lastResultReg = rd;
            } else {
                throw new RuntimeException("NOT operator requires operand");
            }
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
                        if (isIncrement) {
                            emit(Opcodes.POST_AUTOINCREMENT);
                        } else {
                            emit(Opcodes.POST_AUTODECREMENT);
                        }
                    } else {
                        // Prefix: returns new value after modifying
                        if (isIncrement) {
                            emit(Opcodes.PRE_AUTOINCREMENT);
                        } else {
                            emit(Opcodes.PRE_AUTODECREMENT);
                        }
                    }
                    emit(varReg);

                    lastResultReg = varReg;
                } else {
                    throw new RuntimeException("Increment/decrement of non-lexical variable not yet supported");
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
                            if (isIncrement) {
                                emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.POST_AUTODECREMENT);
                            }
                        } else {
                            if (isIncrement) {
                                emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.PRE_AUTODECREMENT);
                            }
                        }
                        emit(varReg);

                        lastResultReg = varReg;
                    } else {
                        // Global variable increment/decrement
                        // Add package prefix if not present
                        String globalVarName = varName;
                        if (!globalVarName.contains("::")) {
                            globalVarName = "main::" + varName.substring(1);
                        }
                        int nameIdx = addToStringPool(globalVarName);

                        // Load global variable
                        int globalReg = allocateRegister();
                        emit(Opcodes.LOAD_GLOBAL_SCALAR);
                        emit(globalReg);
                        emit(nameIdx);

                        // Apply increment/decrement
                        if (isPostfix) {
                            if (isIncrement) {
                                emit(Opcodes.POST_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.POST_AUTODECREMENT);
                            }
                        } else {
                            if (isIncrement) {
                                emit(Opcodes.PRE_AUTOINCREMENT);
                            } else {
                                emit(Opcodes.PRE_AUTODECREMENT);
                            }
                        }
                        emit(globalReg);

                        // Store back to global variable
                        emit(Opcodes.STORE_GLOBAL_SCALAR);
                        emit(nameIdx);
                        emit(globalReg);

                        lastResultReg = globalReg;
                    }
                }
            }
        } else if (op.equals("return")) {
            // return $expr;
            if (node.operand != null) {
                // Evaluate return expression
                node.operand.accept(this);
                int exprReg = lastResultReg;

                // Emit RETURN with expression register
                emitWithToken(Opcodes.RETURN, node.getIndex());
                emit(exprReg);
            } else {
                // return; (no value - return empty list/undef)
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emit(undefReg);

                emitWithToken(Opcodes.RETURN, node.getIndex());
                emit(undefReg);
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
                emit(rd);
                emit(maxReg);
            } else {
                // rand() with no argument - defaults to 1
                int oneReg = allocateRegister();
                emit(Opcodes.LOAD_INT);
                emit(oneReg);
                emitInt(1);

                // Emit RAND opcode
                emit(Opcodes.RAND);
                emit(rd);
                emit(oneReg);
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

                // Emit SLOW_OP with SLOWOP_SLEEP
                emit(Opcodes.SLOW_OP);
                emit(Opcodes.SLOWOP_SLEEP);
                emit(rd);
                emit(secondsReg);
            } else {
                // sleep with no argument - defaults to infinity (but we'll use a large number)
                int maxReg = allocateRegister();
                emit(Opcodes.LOAD_INT);
                emit(maxReg);
                emitInt(Integer.MAX_VALUE);

                emit(Opcodes.SLOW_OP);
                emit(Opcodes.SLOWOP_SLEEP);
                emit(rd);
                emit(maxReg);
            }

            lastResultReg = rd;
        } else if (op.equals("die")) {
            // die $message;
            if (node.operand != null) {
                // Evaluate die message
                node.operand.accept(this);
                int msgReg = lastResultReg;

                // Emit DIE with message register
                emitWithToken(Opcodes.DIE, node.getIndex());
                emit(msgReg);
            } else {
                // die; (no message - use $@)
                // For now, emit with undef register
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emit(undefReg);

                emitWithToken(Opcodes.DIE, node.getIndex());
                emit(undefReg);
            }
            lastResultReg = -1; // No result after die
        } else if (op.equals("eval")) {
            // eval $string;
            if (node.operand != null) {
                // Evaluate eval operand (the code string)
                node.operand.accept(this);
                int stringReg = lastResultReg;

                // Allocate register for result
                int rd = allocateRegister();

                // Emit SLOW_OP with SLOWOP_EVAL_STRING
                emitWithToken(Opcodes.SLOW_OP, node.getIndex());
                emit(Opcodes.SLOWOP_EVAL_STRING);
                emit(rd);
                emit(stringReg);

                lastResultReg = rd;
            } else {
                // eval; (no operand - return undef)
                int undefReg = allocateRegister();
                emit(Opcodes.LOAD_UNDEF);
                emit(undefReg);
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
                emit(rd);
                emit(listReg);
            } else {
                // select() with no arguments - returns current filehandle
                // Create empty list
                emit(Opcodes.CREATE_LIST);
                int listReg = allocateRegister();
                emit(listReg);
                emit(0); // count = 0

                // Emit SELECT opcode
                emitWithToken(Opcodes.SELECT, node.getIndex());
                emit(rd);
                emit(listReg);
            }

            lastResultReg = rd;
        } else if (op.equals("undef")) {
            // undef operator - returns undefined value
            // Can be used standalone: undef
            // Or with an operand to undef a variable: undef $x (not implemented yet)
            int undefReg = allocateRegister();
            emit(Opcodes.LOAD_UNDEF);
            emit(undefReg);
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
            emit(rd);
            emit(operandReg);

            lastResultReg = rd;
        } else if (op.equals("pop")) {
            // Array pop: $x = pop @array
            // operand: ListNode containing OperatorNode("@", IdentifierNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("pop requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                throwCompilerException("pop requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@") || !(arrayOp.operand instanceof IdentifierNode)) {
                throwCompilerException("pop requires array variable: pop @array");
            }

            String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

            // Get the array - check lexical first, then global
            int arrayReg;
            if (hasVariable(varName)) {
                // Lexical array
                arrayReg = getVariableRegister(varName);
            } else {
                // Global array - load it
                arrayReg = allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, getCurrentPackage());
                int nameIdx = addToStringPool(globalArrayName);
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emit(arrayReg);
                emit(nameIdx);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit ARRAY_POP
            emit(Opcodes.ARRAY_POP);
            emit(rd);
            emit(arrayReg);

            lastResultReg = rd;
        } else if (op.equals("shift")) {
            // Array shift: $x = shift @array
            // operand: ListNode containing OperatorNode("@", IdentifierNode)
            if (node.operand == null || !(node.operand instanceof ListNode)) {
                throwCompilerException("shift requires array argument");
            }

            ListNode list = (ListNode) node.operand;
            if (list.elements.isEmpty() || !(list.elements.get(0) instanceof OperatorNode)) {
                throwCompilerException("shift requires array variable");
            }

            OperatorNode arrayOp = (OperatorNode) list.elements.get(0);
            if (!arrayOp.operator.equals("@") || !(arrayOp.operand instanceof IdentifierNode)) {
                throwCompilerException("shift requires array variable: shift @array");
            }

            String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

            // Get the array - check lexical first, then global
            int arrayReg;
            if (hasVariable(varName)) {
                // Lexical array
                arrayReg = getVariableRegister(varName);
            } else {
                // Global array - load it
                arrayReg = allocateRegister();
                String globalArrayName = NameNormalizer.normalizeVariableName(((IdentifierNode) arrayOp.operand).name, getCurrentPackage());
                int nameIdx = addToStringPool(globalArrayName);
                emit(Opcodes.LOAD_GLOBAL_ARRAY);
                emit(arrayReg);
                emit(nameIdx);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit ARRAY_SHIFT
            emit(Opcodes.ARRAY_SHIFT);
            emit(rd);
            emit(arrayReg);

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
            if (!arrayOp.operator.equals("@") || !(arrayOp.operand instanceof IdentifierNode)) {
                throwCompilerException("splice requires array variable: splice @array, ...");
            }

            String varName = "@" + ((IdentifierNode) arrayOp.operand).name;

            // Get the array - check lexical first, then global
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
                emit(arrayReg);
                emit(nameIdx);
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
            emit(argsListReg);
            emit(argRegs.size());
            for (int argReg : argRegs) {
                emit(argReg);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit SLOW_OP with SLOWOP_SPLICE
            emit(Opcodes.SLOW_OP);
            emit(Opcodes.SLOWOP_SPLICE);
            emit(rd);
            emit(arrayReg);
            emit(argsListReg);

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
            emit(argsListReg);
            emit(argRegs.size());
            for (int argReg : argRegs) {
                emit(argReg);
            }

            // Allocate result register
            int rd = allocateRegister();

            // Emit SLOW_OP with SLOWOP_REVERSE
            emit(Opcodes.SLOW_OP);
            emit(Opcodes.SLOWOP_REVERSE);
            emit(rd);
            emit(argsListReg);
            emit(RuntimeContextType.LIST);  // Context

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
        if (reg > 255) {
            throwCompilerException("Too many registers: exceeded 255 register limit. " +
                    "Consider breaking this code into smaller subroutines.");
        }
        return reg;
    }

    private int addToStringPool(String str) {
        int index = stringPool.indexOf(str);
        if (index >= 0) {
            return index;
        }
        stringPool.add(str);
        return stringPool.size() - 1;
    }

    private int addToConstantPool(Object obj) {
        int index = constants.indexOf(obj);
        if (index >= 0) {
            return index;
        }
        constants.add(obj);
        return constants.size() - 1;
    }

    private void emit(byte opcode) {
        bytecode.write(opcode);
    }

    /**
     * Emit opcode and track tokenIndex for error reporting.
     * Use this for opcodes that may throw exceptions (DIE, method calls, etc.)
     */
    private void emitWithToken(byte opcode, int tokenIndex) {
        int pc = bytecode.size();
        pcToTokenIndex.put(pc, tokenIndex);
        bytecode.write(opcode);
    }

    private void emit(int value) {
        bytecode.write(value & 0xFF);
    }

    private void emitInt(int value) {
        bytecode.write((value >> 24) & 0xFF);
        bytecode.write((value >> 16) & 0xFF);
        bytecode.write((value >> 8) & 0xFF);
        bytecode.write(value & 0xFF);
    }

    /**
     * Emit a 2-byte short value (big-endian for jump offsets).
     */
    private void emitShort(int value) {
        bytecode.write((value >> 8) & 0xFF);
        bytecode.write(value & 0xFF);
    }

    /**
     * Patch a 4-byte int offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    private void patchIntOffset(int position, int target) {
        byte[] bytes = bytecode.toByteArray();
        // Store absolute target address (not relative offset)
        bytes[position] = (byte)((target >> 24) & 0xFF);
        bytes[position + 1] = (byte)((target >> 16) & 0xFF);
        bytes[position + 2] = (byte)((target >> 8) & 0xFF);
        bytes[position + 3] = (byte)(target & 0xFF);
        // Rebuild the stream with patched bytes
        bytecode.reset();
        bytecode.write(bytes, 0, bytes.length);
    }

    /**
     * Patch a 2-byte short offset at the specified position.
     * Used for forward jumps where the target is unknown at emit time.
     */
    private void patchShortOffset(int position, int target) {
        byte[] bytes = bytecode.toByteArray();
        // Store absolute target address (not relative offset)
        bytes[position] = (byte)((target >> 8) & 0xFF);
        bytes[position + 1] = (byte)(target & 0xFF);
        // Rebuild the stream with patched bytes
        bytecode.reset();
        bytecode.write(bytes, 0, bytes.length);
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
            emit(listReg);
            emit(0); // count = 0

            // Convert to RuntimeArray reference (CREATE_ARRAY now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_ARRAY);
            emit(refReg);
            emit(listReg);

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
        emit(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emit(elemReg);
        }

        // Convert list to array reference (CREATE_ARRAY now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_ARRAY);
        emit(refReg);
        emit(listReg);

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
            emit(listReg);
            emit(0); // count = 0

            // Create hash reference (CREATE_HASH now returns reference!)
            int refReg = allocateRegister();
            emit(Opcodes.CREATE_HASH);
            emit(refReg);
            emit(listReg);

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
        emit(listReg);
        emit(node.elements.size()); // count

        // Emit register numbers for each element
        for (int elemReg : elementRegs) {
            emit(elemReg);
        }

        // Create hash reference (CREATE_HASH now returns reference!)
        int refReg = allocateRegister();
        emit(Opcodes.CREATE_HASH);
        emit(refReg);
        emit(listReg);

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

        // Step 3: Create a new BytecodeCompiler for the subroutine body
        BytecodeCompiler subCompiler = new BytecodeCompiler(this.sourceName, node.getIndex(), this.errorUtil);

        // Step 4: Pre-populate sub-compiler's variable scope with captured variables
        for (String varName : closureVarNames) {
            subCompiler.addVariable(varName, "my");
        }

        // Step 5: Compile the subroutine body
        InterpretedCode subCode = subCompiler.compile(node.block);

        // Step 6: Emit bytecode to create closure with captured variables at RUNTIME
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emit(codeReg);
            emit(constIdx);
        } else {
            int templateIdx = addToConstantPool(subCode);
            emit(Opcodes.CREATE_CLOSURE);
            emit(codeReg);
            emit(templateIdx);
            emit(closureVarIndices.size());
            for (int regIdx : closureVarIndices) {
                emit(regIdx);
            }
        }

        // Step 7: Store in global namespace
        String fullName = node.name;
        if (!fullName.contains("::")) {
            fullName = "main::" + fullName;
        }

        int nameIdx = addToStringPool(fullName);
        emit(Opcodes.STORE_GLOBAL_CODE);
        emit(nameIdx);
        emit(codeReg);

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
        BytecodeCompiler subCompiler = new BytecodeCompiler(this.sourceName, node.getIndex(), this.errorUtil);

        // Step 4: Pre-populate sub-compiler's variable scope with captured variables
        for (String varName : closureVarNames) {
            subCompiler.addVariable(varName, "my");
        }

        // Step 5: Compile the subroutine body
        InterpretedCode subCode = subCompiler.compile(node.block);

        // Step 6: Create closure or simple code ref
        int codeReg = allocateRegister();

        if (closureVarIndices.isEmpty()) {
            // No closures - just wrap the InterpretedCode
            RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);
            int constIdx = addToConstantPool(codeScalar);
            emit(Opcodes.LOAD_CONST);
            emit(codeReg);
            emit(constIdx);
        } else {
            // Has closures - emit CREATE_CLOSURE
            int templateIdx = addToConstantPool(subCode);
            emit(Opcodes.CREATE_CLOSURE);
            emit(codeReg);
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

        // Emit EVAL_TRY with placeholder for catch offset
        int tryPc = bytecode.size();
        emitWithToken(Opcodes.EVAL_TRY, node.getIndex());
        int catchOffsetPos = bytecode.size();
        emit(0); // High byte placeholder
        emit(0); // Low byte placeholder

        // Compile the eval block body
        node.block.accept(this);

        // Store result from block
        if (lastResultReg >= 0) {
            emit(Opcodes.MOVE);
            emit(resultReg);
            emit(lastResultReg);
        }

        // Emit EVAL_END (clears $@)
        emit(Opcodes.EVAL_END);

        // Jump over catch block
        int gotoEndPos = bytecode.size();
        emit(Opcodes.GOTO);
        int gotoEndOffsetPos = bytecode.size();
        emit(0); // High byte placeholder
        emit(0); // Low byte placeholder

        // CATCH block starts here
        int catchPc = bytecode.size();

        // Patch EVAL_TRY with catch offset
        int catchOffset = catchPc - tryPc;
        byte[] bc = bytecode.toByteArray();
        bc[catchOffsetPos] = (byte) ((catchOffset >> 8) & 0xFF);
        bc[catchOffsetPos + 1] = (byte) (catchOffset & 0xFF);
        bytecode.reset();
        bytecode.write(bc, 0, bc.length);

        // Emit EVAL_CATCH (sets $@, stores undef)
        emit(Opcodes.EVAL_CATCH);
        emit(resultReg);

        // END label (after catch)
        int endPc = bytecode.size();

        // Patch GOTO to end
        int gotoEndOffset = endPc - gotoEndPos;
        bc = bytecode.toByteArray();
        bc[gotoEndOffsetPos] = (byte) ((gotoEndOffset >> 8) & 0xFF);
        bc[gotoEndOffsetPos + 1] = (byte) (gotoEndOffset & 0xFF);
        bytecode.reset();
        bytecode.write(bc, 0, bc.length);

        lastResultReg = resultReg;
    }

    @Override
    public void visit(For1Node node) {
        // For1Node: foreach-style loop
        // for my $var (@list) { body }

        // Step 1: Evaluate list in list context
        node.list.accept(this);
        int listReg = lastResultReg;

        // Step 2: Convert to RuntimeArray if needed
        // Check if listReg contains an array or needs conversion
        int arrayReg;

        // If the list is an array variable (like @x), the register already contains the array
        // Otherwise, we need to create a temporary array from the list
        if (node.list instanceof OperatorNode && ((OperatorNode) node.list).operator.equals("@")) {
            // Direct array variable - register contains RuntimeArray
            arrayReg = listReg;
        } else {
            // Need to convert list to array
            arrayReg = allocateRegister();
            emit(Opcodes.NEW_ARRAY);
            emit(arrayReg);
            emit(Opcodes.ARRAY_SET_FROM_LIST);
            emit(arrayReg);
            emit(listReg);
        }

        // Step 3: Allocate iterator index register
        int indexReg = allocateRegister();
        emit(Opcodes.LOAD_INT);
        emit(indexReg);
        emitInt(0);

        // Step 4: Allocate array size register
        int sizeReg = allocateRegister();
        emit(Opcodes.ARRAY_SIZE);
        emit(sizeReg);
        emit(arrayReg);

        // Step 5: Enter new scope for loop variable
        enterScope();

        // Step 6: Declare loop variable in the new scope
        // CRITICAL: We must let addVariable allocate the register so it's synchronized
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

        // Step 7: Loop start - check if index < size
        int loopStartPc = bytecode.size();

        // Compare index with size
        int cmpReg = allocateRegister();
        emit(Opcodes.LT_NUM);
        emit(cmpReg);
        emit(indexReg);
        emit(sizeReg);

        // If false, jump to end (we'll patch this later)
        emit(Opcodes.GOTO_IF_FALSE);
        emit(cmpReg);
        int loopEndJumpPc = bytecode.size();
        emitInt(0);  // Placeholder for jump target

        // Step 8: Get array element and assign to loop variable
        emit(Opcodes.ARRAY_GET);
        emit(varReg);
        emit(arrayReg);
        emit(indexReg);

        // Step 9: Execute body
        if (node.body != null) {
            node.body.accept(this);
        }

        // Step 10: Increment index
        emit(Opcodes.ADD_SCALAR_INT);
        emit(indexReg);
        emit(indexReg);
        emitInt(1);

        // Step 11: Jump back to loop start
        emit(Opcodes.GOTO);
        emitInt(loopStartPc);

        // Step 12: Loop end - patch the forward jump
        int loopEndPc = bytecode.size();
        patchJump(loopEndJumpPc, loopEndPc);

        // Step 13: Exit scope
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
            emit(condReg);
            emitInt(1);
        }

        // Step 4: If condition is false, jump to end
        emit(Opcodes.GOTO_IF_FALSE);
        emit(condReg);
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
        emit(condReg);
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
                emit(thenResultReg);
                emit(elseResultReg);
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
        emit(condReg);
        emitInt(0); // Placeholder for false_label

        // Compile true expression
        node.trueExpr.accept(this);
        int trueReg = lastResultReg;

        // Move true result to rd
        emit(Opcodes.MOVE);
        emit(rd);
        emit(trueReg);

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
        emit(rd);
        emit(falseReg);

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
            // Return empty RuntimeList
            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emit(listReg);
            emit(0); // count = 0
            lastResultReg = listReg;
            return;
        }

        // Fast path: single element
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
                emit(listReg);
                emit(1); // count = 1
                emit(elemReg);
                lastResultReg = listReg;
            } finally {
                currentCallContext = savedContext;
            }
            return;
        }

        // General case: multiple elements
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
            emit(listReg);
            emit(node.elements.size()); // count

            // Emit register numbers for each element
            for (int elemReg : elementRegs) {
                emit(elemReg);
            }

            lastResultReg = listReg;
        } finally {
            currentCallContext = savedContext;
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Patch a forward jump instruction with the actual target offset.
     *
     * @param jumpPc The PC where the 4-byte jump target was emitted
     * @param targetPc The actual target PC to jump to
     */
    private void patchJump(int jumpPc, int targetPc) {
        byte[] bc = bytecode.toByteArray();
        bc[jumpPc] = (byte) ((targetPc >> 24) & 0xFF);
        bc[jumpPc + 1] = (byte) ((targetPc >> 16) & 0xFF);
        bc[jumpPc + 2] = (byte) ((targetPc >> 8) & 0xFF);
        bc[jumpPc + 3] = (byte) (targetPc & 0xFF);
        // Reset bytecode stream with patched data
        bytecode.reset();
        bytecode.write(bc, 0, bc.length);
    }
}
