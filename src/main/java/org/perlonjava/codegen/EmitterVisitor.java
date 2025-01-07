package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.operators.OperatorHandler;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.RuntimeContextType;
import org.perlonjava.runtime.ScalarUtils;

import java.util.*;

import static org.perlonjava.codegen.EmitOperator.emitOperator;

public class EmitterVisitor implements Visitor {

    public final EmitterContext ctx;
    /**
     * Cache for EmitterVisitor instances with different ContextTypes
     */
    private final Map<Integer, EmitterVisitor> visitorCache = new HashMap<>();

    public EmitterVisitor(EmitterContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns an EmitterVisitor with the specified context type. Uses a cache to avoid creating new
     * instances unnecessarily.
     *
     * <p>Example usage:
     *
     * <pre>
     *   // emits the condition code in scalar context
     *   node.condition.accept(this.with(RuntimeContextType.SCALAR));
     * </pre>
     *
     * @param contextType The context type for the new EmitterVisitor.
     * @return An EmitterVisitor with the specified context type.
     */
    public EmitterVisitor with(int contextType) {
        // Check if the visitor is already cached
        if (visitorCache.containsKey(contextType)) {
            return visitorCache.get(contextType);
        }
        // Create a new visitor and cache it
        EmitterVisitor newVisitor = new EmitterVisitor(ctx.with(contextType));
        visitorCache.put(contextType, newVisitor);
        return newVisitor;
    }

    public void pushCallContext() {
        // push call context to stack
        if (ctx.contextType == RuntimeContextType.RUNTIME) {
            // Retrieve wantarray value from JVM local vars
            ctx.mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
        } else {
            ctx.mv.visitLdcInsn(ctx.contextType);
        }
    }

    @Override
    public void visit(NumberNode node) {
        EmitLiteral.emitNumber(ctx, node);
    }

    @Override
    public void visit(IdentifierNode node) {
        EmitLiteral.emitIdentifier(ctx, node);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        EmitBinaryOperatorNode.emitBinaryOperatorNode(this, node);
    }

    void handleBinaryOperator(BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        ctx.logDebug("handleBinaryOperator: " + node.toString());

        // Check for chained comparison operators like `a < b < c`
        if (node.left instanceof BinaryOperatorNode) {
            String[] comparisonOps = {"<", ">", "<=", ">=", "lt", "gt", "le", "ge"};
            String[] equalityOps = {"==", "!=", "eq", "ne"};

            // Check if operators are of same type
            boolean isComparisonChain = Arrays.asList(comparisonOps).contains(node.operator);
            boolean isEqualityChain = Arrays.asList(equalityOps).contains(node.operator);

            if ((isComparisonChain && isOperatorOfType(node.left, comparisonOps)) ||
                    (isEqualityChain && isOperatorOfType(node.left, equalityOps))) {

                emitChainedComparison(node, scalarVisitor);
                return;
            }
        }

        // Optimization
        if ((node.operator.equals("+")
                || node.operator.equals("-")
                || node.operator.equals("=="))
                && node.right instanceof NumberNode right) {
            String value = right.value;
            boolean isInteger = ScalarUtils.isInteger(value);
            if (isInteger) {
                node.left.accept(scalarVisitor); // target - left parameter
                int intValue = Integer.parseInt(value);
                ctx.mv.visitLdcInsn(intValue);
                ctx.mv.visitMethodInsn(
                        operatorHandler.getMethodType(),
                        operatorHandler.getClassName(),
                        operatorHandler.getMethodName(),
                        operatorHandler.getDescriptorWithIntParameter(),
                        false);
                if (ctx.contextType == RuntimeContextType.VOID) {
                    ctx.mv.visitInsn(Opcodes.POP);
                }
                return;
            }
        }

        node.left.accept(scalarVisitor); // left parameter
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, right]
        emitOperator(node.operator, this);
    }

    private boolean isOperatorOfType(Node node, String[] operatorTypes) {
        if (node instanceof BinaryOperatorNode binOp) {
            return Arrays.asList(operatorTypes).contains(binOp.operator);
        }
        return false;
    }

    private void emitChainedComparison(BinaryOperatorNode node, EmitterVisitor scalarVisitor) {
        // Collect all nodes in the chain from left to right
        List<Node> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();

        // Build the chain
        BinaryOperatorNode current = node;
        while (true) {
            operators.add(0, current.operator);
            operands.add(0, current.right);

            if (current.left instanceof BinaryOperatorNode leftNode) {
                current = leftNode;
            } else {
                operands.add(0, current.left);
                break;
            }
        }

        // Emit first comparison
        operands.get(0).accept(scalarVisitor);
        operands.get(1).accept(scalarVisitor);
        emitOperator(operators.get(0), scalarVisitor);

        // Set up labels for the chain
        Label endLabel = new Label();
        Label falseLabel = new Label();

        // Emit remaining comparisons
        for (int i = 1; i < operators.size(); i++) {
            // Check previous result
            ctx.mv.visitInsn(Opcodes.DUP);
            ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                    "org/perlonjava/runtime/RuntimeDataProvider",
                    "getBoolean",
                    "()Z",
                    true);
            ctx.mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);

            // Previous was true, do next comparison
            ctx.mv.visitInsn(Opcodes.POP);
            operands.get(i).accept(scalarVisitor);
            operands.get(i + 1).accept(scalarVisitor);
            emitOperator(operators.get(i), scalarVisitor);
        }

        ctx.mv.visitJumpInsn(Opcodes.GOTO, endLabel);
        ctx.mv.visitLabel(falseLabel);
        ctx.mv.visitLabel(endLabel);

        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    void handleCompoundAssignment(BinaryOperatorNode node, OperatorHandler operatorHandler) {
        // compound assignment operators like `+=`
        EmitterVisitor scalarVisitor =
                this.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        node.left.accept(scalarVisitor); // target - left parameter
        ctx.mv.visitInsn(Opcodes.DUP);
        node.right.accept(scalarVisitor); // right parameter
        // stack: [left, left, right]
        // perform the operation
        ctx.mv.visitMethodInsn(
                operatorHandler.getMethodType(),
                operatorHandler.getClassName(),
                operatorHandler.getMethodName(),
                operatorHandler.getDescriptor(),
                false);
        // assign to the Lvalue
        ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    /**
     * Emits a call to a unary built-in method on the RuntimeScalar class.
     *
     * @param operator The name of the built-in method to call.
     */
    void handleUnaryBuiltin(OperatorNode node, String operator) {
        MethodVisitor mv = ctx.mv;
        OperatorHandler operatorHandler = OperatorHandler.get(operator);
        if (node.operand == null) {
            // Unary operator with no arguments, or with optional arguments called without arguments
            // example: undef()  wantarray()  time()  times()
            if (operator.equals("wantarray")) {
                // Retrieve wantarray value from JVM local vars
                mv.visitVarInsn(Opcodes.ILOAD, ctx.symbolTable.getVariableIndex("wantarray"));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "(I)Lorg/perlonjava/runtime/RuntimeScalar;", false);
            } else if (operator.equals("times") || operator.equals("time")) {
                // Call static RuntimeScalar method with no arguments; returns RuntimeList
                emitOperator(operator, this);
                return;
            } else {
                // Call static RuntimeScalar method with no arguments
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        } else if (operator.equals("undef")) {
            operator = "undefine";
            node.operand.accept(this.with(RuntimeContextType.RUNTIME));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeList", operator, "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else if (operator.equals("gmtime") || operator.equals("localtime") || operator.equals("caller") || operator.equals("reset") || operator.equals("select")) {
            node.operand.accept(this.with(RuntimeContextType.LIST));
            pushCallContext();
            emitOperator(operator, this);
            return;
        } else if (operator.equals("prototype")) {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
            ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/RuntimeCode",
                    "prototype",
                    "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if ((operator.equals("stat") || operator.equals("lstat"))
                && (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_"))) {
            // `stat _`
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/Stat",
                    operator + "LastHandle",
                    "()Lorg/perlonjava/runtime/RuntimeList;", false);
        } else {
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            if (operatorHandler != null) {
                emitOperator(operator, this);
                return;
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeScalar", operator, "()Lorg/perlonjava/runtime/RuntimeScalar;", false);
            }
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    void handleArrayUnaryBuiltin(OperatorNode node, String operator) {
        // Handle:  $#array  $#$array_ref  shift @array  pop @array
        Node operand = node.operand;
        ctx.logDebug("handleArrayUnaryBuiltin " + operand);
        if (operand instanceof ListNode) {
            operand = ((ListNode) operand).elements.getFirst();
        }
        operand.accept(this.with(RuntimeContextType.LIST));
        emitOperator(operator, this);
    }

    void handleFileTestBuiltin(OperatorNode node) {
        // Handle:  -d FILE
        String operator = node.operator;
        ctx.logDebug("handleFileTestBuiltin " + node);

        // push the operator string to the JVM stack
        ctx.mv.visitLdcInsn(node.operator);
        if (node.operand instanceof IdentifierNode && ((IdentifierNode) node.operand).name.equals("_")) {
            // use the `_` file handle
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/FileTestOperator",
                    "fileTestLastHandle",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        } else {
            // push the file name to the JVM stack
            node.operand.accept(this.with(RuntimeContextType.SCALAR));
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/operators/FileTestOperator",
                    "fileTest",
                    "(Ljava/lang/String;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;", false);
        }
        if (ctx.contextType == RuntimeContextType.VOID) {
            ctx.mv.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visit(OperatorNode node) {
        EmitOperatorNode.emitOperatorNode(this, node);
    }

    void handleCreateReference(OperatorNode node) {
        MethodVisitor mv = ctx.mv;
        if (node.operand instanceof ListNode) {
            // operand is a list:  `\(1,2,3)`
            node.operand.accept(this.with(RuntimeContextType.LIST));
            ctx.mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/RuntimeList",
                    "createListReference",
                    "()Lorg/perlonjava/runtime/RuntimeList;", false);
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        } else {
            if (node.operand instanceof OperatorNode operatorNode && operatorNode.operator.equals("&")) {
                ctx.logDebug("Handle \\& " + operatorNode.operand);
                if (operatorNode.operand instanceof OperatorNode || operatorNode.operand instanceof BlockNode) {
                    // \&$a or \&{$a}
                    operatorNode.operand.accept(this.with(RuntimeContextType.SCALAR));
                    ctx.mv.visitLdcInsn(ctx.symbolTable.getCurrentPackage());
                    ctx.mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/RuntimeCode",
                            "createCodeReference",
                            "(Lorg/perlonjava/runtime/RuntimeScalar;Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                            false);
                } else {
                    // assume \&var, which is already a reference
                    node.operand.accept(this.with(RuntimeContextType.LIST));
                }
            } else {
                node.operand.accept(this.with(RuntimeContextType.LIST));
                ctx.mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/perlonjava/runtime/RuntimeDataProvider", "createReference", "()Lorg/perlonjava/runtime/RuntimeScalar;", true);
            }
            if (ctx.contextType == RuntimeContextType.VOID) {
                mv.visitInsn(Opcodes.POP);
            }
        }
    }

    public void emitFileHandle(Node node) {
        // Emit File Handle
        if (node instanceof IdentifierNode) {
            // `print FILE 123`
            // retrieve STDOUT, STDERR from GlobalIORef
            // fetch a global fileHandle by name

            // resolve the full name of the file handle
            String name = ((IdentifierNode) node).name;
            name = NameNormalizer.normalizeVariableName(name, ctx.symbolTable.getCurrentPackage());

            ctx.mv.visitLdcInsn(name);
            ctx.mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/GlobalVariable",
                    "getGlobalIO",
                    "(Ljava/lang/String;)Lorg/perlonjava/runtime/RuntimeScalar;",
                    false);
        } else if (node instanceof BlockNode) {
            // {STDERR}  or  {$fh}
            // TODO
        }
    }

    @Override
    public void visit(TryNode node) {
        EmitStatement.emitTryCatch(this, node);
    }

    @Override
    public void visit(SubroutineNode node) {
        EmitSubroutine.emitSubroutine(ctx, node);
    }

    @Override
    public void visit(For1Node node) {
        EmitForeach.emitFor1(this, node);
    }

    @Override
    public void visit(For3Node node) {
        EmitStatement.emitFor3(this, node);
    }

    @Override
    public void visit(IfNode node) {
        EmitStatement.emitIf(this, node);
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        EmitLogicalOperator.emitTernaryOperator(this, node);
    }

    @Override
    public void visit(BlockNode node) {
        EmitStatement.emitBlock(this, node);
    }

    @Override
    public void visit(ListNode node) {
        EmitLiteral.emitList(this, node);
    }

    @Override
    public void visit(StringNode node) {
        EmitLiteral.emitString(ctx, node);
    }

    @Override
    public void visit(HashLiteralNode node) {
        EmitLiteral.emitHashLiteral(this, node);
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        EmitLiteral.emitArrayLiteral(this, node);
    }

}
