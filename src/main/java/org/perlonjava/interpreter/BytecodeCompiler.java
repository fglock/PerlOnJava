package org.perlonjava.interpreter;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.codegen.EmitterContext;
import org.perlonjava.runtime.*;

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
    private final Map<String, Integer> registerMap = new HashMap<>();

    // Token index tracking for error reporting
    private final Map<Integer, Integer> pcToTokenIndex = new HashMap<>();

    // Register allocation
    private int nextRegister = 3;  // 0=this, 1=@_, 2=wantarray

    // Track last result register for expression chaining
    private int lastResultReg = -1;

    // Closure support
    private RuntimeBase[] capturedVars;           // Captured variable values
    private String[] capturedVarNames;            // Parallel array of names
    private Map<String, Integer> capturedVarIndices;  // Name → register index

    // Source information
    private final String sourceName;
    private final int sourceLine;

    public BytecodeCompiler(String sourceName, int sourceLine) {
        this.sourceName = sourceName;
        this.sourceLine = sourceLine;
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

        // Build InterpretedCode
        return new InterpretedCode(
            bytecode.toByteArray(),
            constants.toArray(),
            stringPool.toArray(new String[0]),
            nextRegister,  // maxRegisters
            capturedVars,  // NOW POPULATED!
            sourceName,
            sourceLine,
            pcToTokenIndex  // Pass token index map for error reporting
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
        // This is a simplified version - we collect variables from registerMap
        // which contains all lexically declared variables in the current compilation unit
        locals.addAll(registerMap.keySet());
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
            stmt.accept(this);
        }
    }

    @Override
    public void visit(NumberNode node) {
        // Emit LOAD_INT: rd = RuntimeScalarCache.getScalarInt(value)
        int rd = allocateRegister();

        try {
            if (node.value.contains(".")) {
                // TODO: Handle double values properly
                int intValue = (int) Double.parseDouble(node.value);
                emit(Opcodes.LOAD_INT);
                emit(rd);
                emitInt(intValue);
            } else {
                int intValue = Integer.parseInt(node.value);
                emit(Opcodes.LOAD_INT);
                emit(rd);
                emitInt(intValue);
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
        if (registerMap.containsKey(varName)) {
            // Lexical variable - already has a register
            lastResultReg = registerMap.get(varName);
        } else {
            // Try with sigils
            boolean found = false;
            for (String sigil : sigils) {
                String varNameWithSigil = sigil + varName;
                if (registerMap.containsKey(varNameWithSigil)) {
                    lastResultReg = registerMap.get(varNameWithSigil);
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

                            // Allocate register for new lexical variable
                            int reg = allocateRegister();
                            registerMap.put(varName, reg);

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

                    // Handle my x (direct identifier without sigil)
                    if (myOperand instanceof IdentifierNode) {
                        String varName = ((IdentifierNode) myOperand).name;

                        // Allocate register for new lexical variable
                        int reg = allocateRegister();
                        registerMap.put(varName, reg);

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
                        if (leftVarName.equals(rightLeftVarName) && registerMap.containsKey(leftVarName)) {
                            int targetReg = registerMap.get(leftVarName);

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

                    if (registerMap.containsKey(varName)) {
                        // Lexical variable - copy to its register
                        int targetReg = registerMap.get(varName);
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
                } else {
                    throw new RuntimeException("Assignment to non-scalar not yet supported");
                }
            } else if (node.left instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.left).name;

                if (registerMap.containsKey(varName)) {
                    // Lexical variable - copy to its register
                    int targetReg = registerMap.get(varName);
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
            } else {
                throw new RuntimeException("Assignment to non-identifier not yet supported: " + node.left.getClass().getSimpleName());
            }
            return;
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
            case "." -> {
                emit(Opcodes.CONCAT);
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
            case "()", "->" -> {
                // Apply operator: $coderef->(args) or &subname(args)
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
                emit(RuntimeContextType.SCALAR); // Context (TODO: detect from usage)

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
                        int start = Integer.parseInt(((NumberNode) node.left).value);
                        int end = Integer.parseInt(((NumberNode) node.right).value);

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
                    // Runtime range creation - not yet implemented
                    throw new RuntimeException("Range operator with non-constant values not yet implemented");
                }
            }
            default -> throw new RuntimeException("Unsupported operator: " + node.operator);
        }

        lastResultReg = rd;
    }

    @Override
    public void visit(OperatorNode node) {
        String op = node.operator;

        // Handle specific operators
        if (op.equals("my")) {
            // my $x - variable declaration
            // The operand will be OperatorNode("$", IdentifierNode("x"))
            if (node.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) node.operand;
                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;
                    int reg = allocateRegister();
                    registerMap.put(varName, reg);

                    // Load undef initially
                    emit(Opcodes.LOAD_UNDEF);
                    emit(reg);

                    lastResultReg = reg;
                    return;
                }
            }
            throw new RuntimeException("Unsupported my operand: " + node.operand.getClass().getSimpleName());
        } else if (op.equals("$")) {
            // Scalar variable dereference: $x
            if (node.operand instanceof IdentifierNode) {
                String varName = "$" + ((IdentifierNode) node.operand).name;

                if (registerMap.containsKey(varName)) {
                    // Lexical variable - use existing register
                    lastResultReg = registerMap.get(varName);
                } else {
                    // Global variable - load it
                    int rd = allocateRegister();
                    int nameIdx = addToStringPool(varName);

                    emit(Opcodes.LOAD_GLOBAL_SCALAR);
                    emit(rd);
                    emit(nameIdx);

                    lastResultReg = rd;
                }
            } else {
                throw new RuntimeException("Unsupported $ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("@")) {
            // Array variable dereference: @x or @_
            if (node.operand instanceof IdentifierNode) {
                String varName = "@" + ((IdentifierNode) node.operand).name;

                // Special case: @_ is register 1
                if (varName.equals("@_")) {
                    lastResultReg = 1; // @_ is always in register 1
                    return;
                }

                // For now, only support @_ - other arrays require global variable support
                throw new RuntimeException("Array variables other than @_ not yet supported: " + varName);
            } else {
                throw new RuntimeException("Unsupported @ operand: " + node.operand.getClass().getSimpleName());
            }
        } else if (op.equals("%")) {
            // Hash variable dereference: %x
            throw new RuntimeException("Hash variables not yet supported");
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
        } else if (op.equals("\\")) {
            // Reference operator: \$x, \@x, \%x, \*x, etc.
            if (node.operand != null) {
                // Evaluate the operand
                node.operand.accept(this);
                int valueReg = lastResultReg;

                // Allocate register for reference
                int rd = allocateRegister();

                // Emit CREATE_REF
                emit(Opcodes.CREATE_REF);
                emit(rd);
                emit(valueReg);

                lastResultReg = rd;
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
        } else if (op.equals("++") || op.equals("--") || op.equals("++postfix") || op.equals("--postfix")) {
            // Pre/post increment/decrement
            boolean isPostfix = op.endsWith("postfix");
            boolean isIncrement = op.startsWith("++");

            if (node.operand instanceof IdentifierNode) {
                String varName = ((IdentifierNode) node.operand).name;

                if (registerMap.containsKey(varName)) {
                    int varReg = registerMap.get(varName);

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

                    if (registerMap.containsKey(varName)) {
                        int varReg = registerMap.get(varName);

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
                        throw new RuntimeException("Increment/decrement of non-lexical variable not yet supported");
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
        } else {
            throw new UnsupportedOperationException("Unsupported operator: " + op);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private int allocateRegister() {
        return nextRegister++;
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

    // =========================================================================
    // UNIMPLEMENTED VISITOR METHODS (TODO)
    // =========================================================================

    @Override
    public void visit(ArrayLiteralNode node) {
        throw new UnsupportedOperationException("Arrays not yet implemented");
    }

    @Override
    public void visit(HashLiteralNode node) {
        throw new UnsupportedOperationException("Hashes not yet implemented");
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
            // Named subroutine - not yet supported
            throw new UnsupportedOperationException("Named subroutines not yet implemented in interpreter: " + node.name);
        }
    }

    /**
     * Visit an anonymous subroutine: sub { ... }
     *
     * Compiles the subroutine body to bytecode and creates an InterpretedCode instance.
     * Handles closure variable capture if needed.
     *
     * The result is an InterpretedCode wrapped in RuntimeScalar, stored in lastResultReg.
     */
    private void visitAnonymousSubroutine(SubroutineNode node) {
        // Create a new BytecodeCompiler for the subroutine body
        BytecodeCompiler subCompiler = new BytecodeCompiler(this.sourceName, node.getIndex());

        // Compile the subroutine body to InterpretedCode
        InterpretedCode subCode = subCompiler.compile(node.block);

        // Wrap InterpretedCode in RuntimeScalar
        // Explicitly cast to RuntimeCode to ensure RuntimeScalar(RuntimeCode) constructor is called
        RuntimeScalar codeScalar = new RuntimeScalar((RuntimeCode) subCode);

        // Store the wrapped code in constants pool and load it into a register
        int constIdx = addToConstantPool(codeScalar);
        int rd = allocateRegister();

        emit(Opcodes.LOAD_CONST);
        emit(rd);
        emit(constIdx);

        lastResultReg = rd;
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
        // TODO: Handle list-to-array conversion
        int arrayReg = allocateRegister();
        emit(Opcodes.CREATE_ARRAY);  // Placeholder - need to convert list to array
        emit(arrayReg);
        emit(listReg);

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

        // Step 5: Allocate loop variable register
        int varReg = allocateRegister();
        if (node.variable != null && node.variable instanceof OperatorNode) {
            OperatorNode varOp = (OperatorNode) node.variable;
            if (varOp.operator.equals("my") && varOp.operand instanceof OperatorNode) {
                OperatorNode sigilOp = (OperatorNode) varOp.operand;
                if (sigilOp.operator.equals("$") && sigilOp.operand instanceof IdentifierNode) {
                    String varName = "$" + ((IdentifierNode) sigilOp.operand).name;
                    registerMap.put(varName, varReg);
                }
            }
        }

        // Step 6: Loop start - check if index < size
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

        // Step 7: Get array element and assign to loop variable
        emit(Opcodes.ARRAY_GET);
        emit(varReg);
        emit(arrayReg);
        emit(indexReg);

        // Step 8: Execute body
        if (node.body != null) {
            node.body.accept(this);
        }

        // Step 9: Increment index
        emit(Opcodes.ADD_SCALAR_INT);
        emit(indexReg);
        emit(indexReg);
        emitInt(1);

        // Step 10: Jump back to loop start
        emit(Opcodes.GOTO);
        emitInt(loopStartPc);

        // Step 11: Loop end - patch the forward jump
        int loopEndPc = bytecode.size();
        patchJump(loopEndJumpPc, loopEndPc);

        lastResultReg = -1;  // For loop returns empty
    }

    @Override
    public void visit(For3Node node) {
        // For3Node: C-style for loop
        // for (init; condition; increment) { body }

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
        throw new UnsupportedOperationException("If statements not yet implemented");
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        throw new UnsupportedOperationException("Ternary operator not yet implemented");
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
        // In scalar context, returns the element itself (but we don't track context yet)
        if (node.elements.size() == 1) {
            // For now, always create a RuntimeList (LIST context behavior)
            node.elements.get(0).accept(this);
            int elemReg = lastResultReg;

            int listReg = allocateRegister();
            emit(Opcodes.CREATE_LIST);
            emit(listReg);
            emit(1); // count = 1
            emit(elemReg);
            lastResultReg = listReg;
            return;
        }

        // General case: multiple elements
        // Evaluate each element into a register
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
