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

        if (node.isInteger()) {
            emit(Opcodes.LOAD_INT);
            emit(rd);
            emitInt((int) node.value);
        } else {
            // TODO: Handle double values
            emit(Opcodes.LOAD_INT);
            emit(rd);
            emitInt((int) node.value);
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
        String varName = node.value;

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
            // my $x = value
            // Allocate register for the variable
            if (node.operands.size() == 1 && node.operands.get(0) instanceof IdentifierNode) {
                IdentifierNode var = (IdentifierNode) node.operands.get(0);
                int reg = allocateRegister();
                registerMap.put(var.value, reg);

                // Load undef initially
                emit(Opcodes.LOAD_UNDEF);
                emit(reg);

                lastResultReg = reg;
            }
        } else if (op.equals("=")) {
            // Assignment: $x = value
            if (node.operands.size() == 2) {
                Node lhs = node.operands.get(0);
                Node rhs = node.operands.get(1);

                // Compile RHS
                rhs.accept(this);
                int valueReg = lastResultReg;

                // Assign to LHS
                if (lhs instanceof IdentifierNode) {
                    String varName = ((IdentifierNode) lhs).value;

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
        } else if (op.equals("say")) {
            // say $x
            if (node.operands.size() > 0) {
                node.operands.get(0).accept(this);
                int rs = lastResultReg;

                emit(Opcodes.SAY);
                emit(rs);
            }
        } else if (op.equals("print")) {
            // print $x
            if (node.operands.size() > 0) {
                node.operands.get(0).accept(this);
                int rs = lastResultReg;

                emit(Opcodes.PRINT);
                emit(rs);
            }
        } else {
            throw new RuntimeException("Unsupported operator: " + op);
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
    public void visit(ForNode node) {
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
    public void visit(ListNode node) {
        throw new UnsupportedOperationException("Lists not yet implemented");
    }
}
