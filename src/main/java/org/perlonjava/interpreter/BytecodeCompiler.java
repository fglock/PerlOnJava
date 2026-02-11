package org.perlonjava.interpreter;

import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.runtime.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Register allocation
    private int nextRegister = 3;  // 0=this, 1=@_, 2=wantarray

    // Track last result register for expression chaining
    private int lastResultReg = -1;

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
            null,  // capturedVars (TODO: closure support)
            sourceName,
            sourceLine
        );
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

        // Check if it's a lexical variable
        if (registerMap.containsKey(varName)) {
            // Lexical variable - already has a register
            lastResultReg = registerMap.get(varName);
        } else {
            // Global variable
            int rd = allocateRegister();
            int nameIdx = addToStringPool(varName);

            emit(Opcodes.LOAD_GLOBAL_SCALAR);
            emit(rd);
            emit(nameIdx);

            lastResultReg = rd;
        }
    }

    @Override
    public void visit(BinaryOperatorNode node) {
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
                    int rd = allocateRegister();

                    if (isIncrement) {
                        emit(Opcodes.ADD_SCALAR_INT);
                        emit(rd);
                        emit(varReg);
                        emitInt(1);
                    } else {
                        emit(Opcodes.SUB_SCALAR_INT);
                        emit(rd);
                        emit(varReg);
                        emitInt(1);
                    }

                    // Store back to variable
                    emit(Opcodes.MOVE);
                    emit(varReg);
                    emit(rd);

                    lastResultReg = rd;
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
                        int rd = allocateRegister();

                        if (isIncrement) {
                            emit(Opcodes.ADD_SCALAR_INT);
                            emit(rd);
                            emit(varReg);
                            emitInt(1);
                        } else {
                            emit(Opcodes.SUB_SCALAR_INT);
                            emit(rd);
                            emit(varReg);
                            emitInt(1);
                        }

                        // Store back to variable
                        emit(Opcodes.MOVE);
                        emit(varReg);
                        emit(rd);

                        lastResultReg = rd;
                    } else {
                        throw new RuntimeException("Increment/decrement of non-lexical variable not yet supported");
                    }
                }
            }
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
        throw new UnsupportedOperationException("Subroutines not yet implemented");
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
        throw new UnsupportedOperationException("Lists not yet implemented");
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
