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
        // NumberNode.value is a String - need to parse it
        int rd = allocateRegister();

        try {
            if (node.value.contains(".")) {
                // TODO: Handle double values properly (for now, truncate to int)
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
        // StringNode.value is a String
        int rd = allocateRegister();
        int strIndex = addToStringPool(node.value);

        emit(Opcodes.LOAD_STRING);
        emit(rd);
        emit(strIndex);

        lastResultReg = rd;
    }

    @Override
    public void visit(IdentifierNode node) {
        // IdentifierNode uses .name (NOT .value)
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

            // Compile RHS first
            node.right.accept(this);
            int valueReg = lastResultReg;

            // Assign to LHS
            if (node.left instanceof IdentifierNode) {
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
            // my $x or my $x = value
            // OperatorNode has .operand which can be a single node or ListNode
            Node operand = node.operand;

            if (operand instanceof IdentifierNode) {
                IdentifierNode var = (IdentifierNode) operand;
                int reg = allocateRegister();
                registerMap.put(var.name, reg);

                // Load undef initially
                emit(Opcodes.LOAD_UNDEF);
                emit(reg);

                lastResultReg = reg;
            } else if (operand instanceof BinaryOperatorNode) {
                // my $x = value
                BinaryOperatorNode assignOp = (BinaryOperatorNode) operand;
                if (assignOp.operator.equals("=")) {
                    // Left is identifier, right is value
                    if (assignOp.left instanceof IdentifierNode) {
                        IdentifierNode var = (IdentifierNode) assignOp.left;
                        int reg = allocateRegister();
                        registerMap.put(var.name, reg);

                        // Compile RHS
                        assignOp.right.accept(this);
                        int valueReg = lastResultReg;

                        // Move to variable register
                        emit(Opcodes.MOVE);
                        emit(reg);
                        emit(valueReg);

                        lastResultReg = reg;
                    }
                }
            }
        } else if (op.equals("=")) {
            // Assignment: $x = value
            // For OperatorNode, operand might be a BinaryOperatorNode
            if (node.operand instanceof BinaryOperatorNode) {
                BinaryOperatorNode binOp = (BinaryOperatorNode) node.operand;
                if (binOp.operator.equals("=")) {
                    // Compile RHS
                    binOp.right.accept(this);
                    int valueReg = lastResultReg;

                    // Assign to LHS
                    if (binOp.left instanceof IdentifierNode) {
                        String varName = ((IdentifierNode) binOp.left).name;

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
                    }
                }
            }
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
        } else {
            throw new RuntimeException("Unsupported operator: " + op);
        }
    }

    @Override
    public void visit(ListNode node) {
        // Visit all elements in the list
        for (Node element : node.elements) {
            element.accept(this);
        }
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
        throw new UnsupportedOperationException("For loops not yet implemented");
    }

    @Override
    public void visit(For3Node node) {
        throw new UnsupportedOperationException("C-style for loops not yet implemented");
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
        throw new UnsupportedOperationException("Labels not yet implemented");
    }

    @Override
    public void visit(CompilerFlagNode node) {
        // Compiler flags are no-ops for the interpreter
        // They affect parsing/compilation but not runtime execution
    }

    @Override
    public void visit(FormatNode node) {
        throw new UnsupportedOperationException("Formats not yet implemented");
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
}
