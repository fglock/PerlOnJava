package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.runtime.operators.OperatorHandler;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.ScalarUtils;

import static org.perlonjava.backend.jvm.EmitOperator.emitOperator;

public class EmitBinaryOperator {
    static final boolean ENABLE_SPILL_BINARY_LHS = true;

    private static boolean isIntegerEnabled(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        Object useInteger = node.getAnnotation("useInteger");
        if (useInteger instanceof Boolean value) {
            return value;
        }
        return emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER);
    }

    static void handleBinaryOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        // bless mutates the scalar slot as well as its referent.  In particular,
        // threads::shared publishes a class change only when the operand is the
        // actual shared scalar, not a scalar-context copy of its reference.
        EmitterVisitor leftVisitor = node.operator.equals("bless")
                && isDirectScalarLvalue(node.left)
                ? emitterVisitor.with(RuntimeContextType.LVALUE)
                : scalarVisitor;
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleBinaryOperator: " + node.toString());

        if (isIntegerEnabled(emitterVisitor, node)
                && switch (node.operator) {
                    case "+", "-", "*", "&", "|", "^" -> true;
                    default -> false;
                }) {
            if (isStagedIntegerBitwiseTree(node)
                    && emitStagedIntegerBitwiseTree(emitterVisitor, node)) {
                return;
            }
            emitIntegerBinaryOperator(emitterVisitor, scalarVisitor, node, operatorHandler);
            return;
        }

        if (isIntegerEnabled(emitterVisitor, node)
                && (node.operator.equals("<<") || node.operator.equals(">>"))
                && isStagedIntegerBitwiseTree(node)
                && emitStagedIntegerBitwiseTree(emitterVisitor, node)) {
            return;
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
                emitterVisitor.ctx.mv.visitLdcInsn(intValue);
                ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, node.left.getIndex());
                emitterVisitor.ctx.mv.visitMethodInsn(
                        operatorHandler.methodType(),
                        operatorHandler.className(),
                        operatorHandler.methodName(),
                        operatorHandler.getDescriptorWithIntParameter(),
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        var right = node.right;

        // Special case for `isa` - left side can be bareword
        if (node.operator.equals("isa") && right instanceof IdentifierNode identifierNode) {
            right = new StringNode(identifierNode.name, node.tokenIndex);
        }

        // Special case for modulus, division, and shift operators under "use integer"
        if (isIntegerEnabled(emitterVisitor, node)) {
            if (node.operator.equals("%")) {
                // Use integer modulus when "use integer" is in effect
                MethodVisitor mv = emitterVisitor.ctx.mv;
                if (ENABLE_SPILL_BINARY_LHS) {
                    node.left.accept(scalarVisitor);
                    int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooled = leftSlot >= 0;
                    if (!pooled) {
                        leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                    right.accept(scalarVisitor);

                    mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    mv.visitInsn(Opcodes.SWAP);
                    if (pooled) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                } else {
                    node.left.accept(scalarVisitor); // left parameter
                    right.accept(scalarVisitor); // right parameter
                }
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/MathOperators",
                        "integerModulus",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("/")) {
                // Use integer division when "use integer" is in effect
                MethodVisitor mv = emitterVisitor.ctx.mv;
                if (ENABLE_SPILL_BINARY_LHS) {
                    node.left.accept(scalarVisitor);
                    int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooled = leftSlot >= 0;
                    if (!pooled) {
                        leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                    right.accept(scalarVisitor);

                    mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    mv.visitInsn(Opcodes.SWAP);
                    if (pooled) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                } else {
                    node.left.accept(scalarVisitor); // left parameter
                    right.accept(scalarVisitor); // right parameter
                }
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/MathOperators",
                        "integerDivide",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals("<<")) {
                // Use integer left shift when "use integer" is in effect
                MethodVisitor mv = emitterVisitor.ctx.mv;
                if (ENABLE_SPILL_BINARY_LHS) {
                    node.left.accept(scalarVisitor);
                    int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooled = leftSlot >= 0;
                    if (!pooled) {
                        leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                    right.accept(scalarVisitor);

                    mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    mv.visitInsn(Opcodes.SWAP);
                    if (pooled) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                } else {
                    node.left.accept(scalarVisitor); // left parameter
                    right.accept(scalarVisitor); // right parameter
                }
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/BitwiseOperators",
                        "integerShiftLeft",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            } else if (node.operator.equals(">>")) {
                // Use integer right shift when "use integer" is in effect
                MethodVisitor mv = emitterVisitor.ctx.mv;
                if (ENABLE_SPILL_BINARY_LHS) {
                    node.left.accept(scalarVisitor);
                    int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
                    boolean pooled = leftSlot >= 0;
                    if (!pooled) {
                        leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
                    }
                    mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

                    right.accept(scalarVisitor);

                    mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
                    mv.visitInsn(Opcodes.SWAP);
                    if (pooled) {
                        emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
                    }
                } else {
                    node.left.accept(scalarVisitor); // left parameter
                    right.accept(scalarVisitor); // right parameter
                }
                emitterVisitor.ctx.mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/operators/BitwiseOperators",
                        "integerShiftRight",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
                EmitOperator.handleVoidContext(emitterVisitor);
                return;
            }
        }

        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.left.accept(leftVisitor); // left parameter
        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooled = leftSlot >= 0;
        if (!pooled) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

        right.accept(scalarVisitor); // right parameter

        mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        mv.visitInsn(Opcodes.SWAP);
        if (pooled) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }
        // stack: [left, right]
        ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, node.left.getIndex());
        emitOperator(node, emitterVisitor);
    }

    private static boolean isDirectScalarLvalue(Node node) {
        if (node instanceof OperatorNode operator) {
            return operator.operator.equals("$");
        }
        if (node instanceof BinaryOperatorNode binary) {
            return switch (binary.operator) {
                case "[", "{" -> true;
                default -> false;
            };
        }
        return false;
    }

    /**
     * A staged tree carries an unboxed word only across integer bitwise
     * operations.  Every leaf remains an ordinary scalar expression, and every
     * non-native leaf takes the existing operator path before its sibling is
     * evaluated.  That preserves Perl's left-to-right tie, overload, warning,
     * and taint behavior while avoiding intermediate result cells for an
     * entirely ordinary tree.
     */
    private static boolean isStagedIntegerBitwiseTree(Node node) {
        if (!(node instanceof BinaryOperatorNode binary)) return true;
        if (!(binary.operator.equals("&") || binary.operator.equals("|")
                || binary.operator.equals("^") || binary.operator.equals("<<")
                || binary.operator.equals(">>"))) {
            return false;
        }
        Object useInteger = binary.getAnnotation("useInteger");
        return !(useInteger instanceof Boolean enabled) || enabled
                ? isStagedIntegerBitwiseTree(binary.left) && isStagedIntegerBitwiseTree(binary.right)
                : false;
    }

    private record StagedBitwiseValue(int scalarSlot, int nativeSlot, int nativeFlagSlot) { }

    private static boolean emitStagedIntegerBitwiseTree(EmitterVisitor emitterVisitor,
                                                         BinaryOperatorNode root) {
        StagedBitwiseValue result = emitStagedIntegerBitwiseValue(emitterVisitor, root);
        emitStagedScalar(emitterVisitor.ctx.mv, result);
        EmitOperator.handleVoidContext(emitterVisitor);
        return true;
    }

    private static StagedBitwiseValue emitStagedIntegerBitwiseValue(
            EmitterVisitor emitterVisitor, Node node) {
        if (!(node instanceof BinaryOperatorNode binary) || !isStagedIntegerBitwiseTree(node)) {
            return emitStagedIntegerBitwiseLeaf(emitterVisitor, node);
        }

        StagedBitwiseValue left = emitStagedIntegerBitwiseValue(emitterVisitor, binary.left);
        StagedBitwiseValue right = emitStagedIntegerBitwiseValue(emitterVisitor, binary.right);
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int scalarSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        int nativeSlot = allocateLongLocal(emitterVisitor);
        int nativeFlagSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        Label generic = new Label();
        Label done = new Label();

        mv.visitVarInsn(Opcodes.ILOAD, left.nativeFlagSlot());
        mv.visitJumpInsn(Opcodes.IFEQ, generic);
        mv.visitVarInsn(Opcodes.ILOAD, right.nativeFlagSlot());
        mv.visitJumpInsn(Opcodes.IFEQ, generic);
        mv.visitVarInsn(Opcodes.LLOAD, left.nativeSlot());
        mv.visitVarInsn(Opcodes.LLOAD, right.nativeSlot());
        switch (binary.operator) {
            case "&" -> mv.visitInsn(Opcodes.LAND);
            case "|" -> mv.visitInsn(Opcodes.LOR);
            case "^" -> mv.visitInsn(Opcodes.LXOR);
            case "<<" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/operators/NumericFlowOperators",
                    "integerShiftLeftNative", "(JJ)J", false);
            case ">>" -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/operators/NumericFlowOperators",
                    "integerShiftRightNative", "(JJ)J", false);
            default -> throw new IllegalStateException("unexpected staged operator " + binary.operator);
        }
        mv.visitVarInsn(Opcodes.LSTORE, nativeSlot);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, nativeFlagSlot);
        mv.visitJumpInsn(Opcodes.GOTO, done);

        mv.visitLabel(generic);
        emitStagedScalar(mv, left);
        emitStagedScalar(mv, right);
        emitIntegerBitwiseMethod(mv, binary.operator);
        mv.visitVarInsn(Opcodes.ASTORE, scalarSlot);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, nativeFlagSlot);
        mv.visitLabel(done);
        return new StagedBitwiseValue(scalarSlot, nativeSlot, nativeFlagSlot);
    }

    private static StagedBitwiseValue emitStagedIntegerBitwiseLeaf(
            EmitterVisitor emitterVisitor, Node node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        int scalarSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        int nativeSlot = allocateLongLocal(emitterVisitor);
        int nativeFlagSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        Label scalar = new Label();
        Label done = new Label();
        node.accept(emitterVisitor.with(RuntimeContextType.SCALAR));
        mv.visitVarInsn(Opcodes.ASTORE, scalarSlot);
        mv.visitVarInsn(Opcodes.ALOAD, scalarSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/operators/NumericFlowOperators",
                "canUseNativeBitwiseValue",
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, scalar);
        mv.visitVarInsn(Opcodes.ALOAD, scalarSlot);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "getLong", "()J", false);
        mv.visitVarInsn(Opcodes.LSTORE, nativeSlot);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ISTORE, nativeFlagSlot);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(scalar);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, nativeFlagSlot);
        mv.visitLabel(done);
        return new StagedBitwiseValue(scalarSlot, nativeSlot, nativeFlagSlot);
    }

    private static void emitStagedScalar(MethodVisitor mv, StagedBitwiseValue value) {
        Label scalar = new Label();
        Label done = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, value.nativeFlagSlot());
        mv.visitJumpInsn(Opcodes.IFEQ, scalar);
        mv.visitVarInsn(Opcodes.LLOAD, value.nativeSlot());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/runtimetypes/RuntimeScalarCache", "getScalarInt",
                "(J)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(scalar);
        mv.visitVarInsn(Opcodes.ALOAD, value.scalarSlot());
        mv.visitLabel(done);
    }

    private static int allocateLongLocal(EmitterVisitor emitterVisitor) {
        int slot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        return slot;
    }

    private static void emitIntegerBitwiseMethod(MethodVisitor mv, String operator) {
        String method = switch (operator) {
            case "&" -> "integerBitwiseAnd";
            case "|" -> "integerBitwiseOr";
            case "^" -> "integerBitwiseXor";
            case "<<" -> "integerShiftLeft";
            case ">>" -> "integerShiftRight";
            default -> throw new IllegalStateException("unexpected staged operator " + operator);
        };
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "org/perlonjava/runtime/operators/BitwiseOperators", method,
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
    }

    private static void emitIntegerBinaryOperator(EmitterVisitor emitterVisitor,
                                                  EmitterVisitor scalarVisitor,
                                                  BinaryOperatorNode node,
                                                  OperatorHandler normalHandler) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        node.left.accept(scalarVisitor);
        int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
        boolean pooled = leftSlot >= 0;
        if (!pooled) {
            leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
        }
        mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
        node.right.accept(scalarVisitor);
        mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
        mv.visitInsn(Opcodes.SWAP);
        if (pooled) {
            emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
        }

        String className;
        String methodName;
        switch (node.operator) {
            case "+" -> {
                className = "org/perlonjava/runtime/operators/MathOperators";
                methodName = switch (normalHandler.methodName()) {
                    case "addWarn" -> "integerAddWarn";
                    case "addNoOverload" -> "integerAddNoOverload";
                    default -> "integerAdd";
                };
            }
            case "-" -> {
                className = "org/perlonjava/runtime/operators/MathOperators";
                methodName = switch (normalHandler.methodName()) {
                    case "subtractWarn" -> "integerSubtractWarn";
                    case "subtractNoOverload" -> "integerSubtractNoOverload";
                    default -> "integerSubtract";
                };
            }
            case "*" -> {
                className = "org/perlonjava/runtime/operators/MathOperators";
                methodName = switch (normalHandler.methodName()) {
                    case "multiplyWarn" -> "integerMultiplyWarn";
                    case "multiplyNoOverload" -> "integerMultiplyNoOverload";
                    default -> "integerMultiply";
                };
            }
            case "&" -> {
                className = "org/perlonjava/runtime/operators/BitwiseOperators";
                methodName = "integerBitwiseAnd";
            }
            case "|" -> {
                className = "org/perlonjava/runtime/operators/BitwiseOperators";
                methodName = "integerBitwiseOr";
            }
            case "^" -> {
                className = "org/perlonjava/runtime/operators/BitwiseOperators";
                methodName = "integerBitwiseXor";
            }
            default -> throw new IllegalArgumentException("not an integer binary operator: " + node.operator);
        }
        ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, node.left.getIndex());
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, className, methodName,
                "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                false);
        EmitOperator.handleVoidContext(emitterVisitor);
    }

    static void handleCompoundAssignment(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        // Compound assignment operators like `+=`, `-=`, etc.
        // These now have proper overload support via MathOperators.*Assign() methods
        
        // Operators that SHOULD warn for uninitialized: * / ** << >> x &
        // Operators that should NOT warn: + - . | ^ && ||
        boolean shouldUseWarnVariant = switch (node.operator) {
            case "*=", "/=", "%=", "**=", "<<=", ">>=", "x=", "&=" -> true;
            default -> false;
        };

        // Check if we have an operator handler for this compound operator
        // Under "use integer", use the integer warn variant for /=
        boolean isInteger = isIntegerEnabled(emitterVisitor, node);
        OperatorHandler operatorHandler;
        if (isInteger && switch (node.operator) {
            case "+=", "-=", "*=" -> true;
            default -> false;
        }) {
            operatorHandler = OperatorHandler.get(node.operator + "_int"
                    + (shouldUseWarnVariant ? "_warn" : ""));
        } else if (shouldUseWarnVariant && isInteger && node.operator.equals("/=")) {
            operatorHandler = OperatorHandler.get("/=_int_warn");
        } else {
            operatorHandler = shouldUseWarnVariant 
                    ? OperatorHandler.getWarn(node.operator)
                    : OperatorHandler.get(node.operator);
        }

        if (operatorHandler != null) {
            // Use the new *Assign methods which check for compound overloads first
            EmitterVisitor scalarVisitor =
                    emitterVisitor.with(RuntimeContextType.SCALAR);
            EmitterVisitor lvalueVisitor =
                    emitterVisitor.with(RuntimeContextType.LVALUE);
            MethodVisitor mv = emitterVisitor.ctx.mv;

            // We need to properly handle the lvalue by using spill slots
            // This ensures the same object is both read and written
            node.left.accept(lvalueVisitor); // target - left parameter
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "vivifyLvalue", "()V", false);

            node.right.accept(scalarVisitor); // right parameter

            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.SWAP); // swap so args are in right order (left, right)

            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            // Call the *Assign method (e.g., MathOperators.addAssign)
            // This modifies arg1 in place and returns it
            mv.visitMethodInsn(
                    operatorHandler.methodType(),
                    operatorHandler.className(),
                    operatorHandler.methodName(),
                    operatorHandler.descriptor(),
                    false);

            EmitOperator.handleVoidContext(emitterVisitor);
        } else {
            // Fallback for operators that don't have handlers yet (e.g., **=, <<=, etc.)
            // Use the old approach: strip = and call base operator, then assign
            EmitterVisitor scalarVisitor =
                    emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
            EmitterVisitor lvalueVisitor =
                    emitterVisitor.with(RuntimeContextType.LVALUE);
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(lvalueVisitor); // target - left parameter
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);
            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RuntimeScalar",
                    "vivifyLvalue", "()V", false);

            node.right.accept(scalarVisitor); // right parameter
            int rightSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledRight = rightSlot >= 0;
            if (!pooledRight) {
                rightSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, rightSlot);

            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ALOAD, rightSlot);

            if (pooledRight) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }
            // Note: leftSlot is released AFTER the assignment so we can reload it below
            // perform the operation
            // Note: operands are already on the stack (left DUPped, then right)
            String baseOperator = node.operator.substring(0, node.operator.length() - 1);
            // Get the operator handler for the base operator, use warn variant only for certain ops
            OperatorHandler baseOpHandler;
            if (isInteger && (baseOperator.equals("<<") || baseOperator.equals(">>"))) {
                baseOpHandler = OperatorHandler.get(baseOperator + "_int");
            } else {
                baseOpHandler = shouldUseWarnVariant
                        ? OperatorHandler.getWarn(baseOperator)
                        : OperatorHandler.get(baseOperator);
            }
            if (baseOpHandler == null) {
                baseOpHandler = OperatorHandler.get(baseOperator);
            }
            if (baseOpHandler != null) {
                if (node.operator.equals(".=")) {
                    mv.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/operators/StringOperators",
                            "stringConcatAssign",
                            "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                            false);
                } else {
                    mv.visitMethodInsn(
                            baseOpHandler.methodType(),
                            baseOpHandler.className(),
                            baseOpHandler.methodName(),
                            baseOpHandler.descriptor(),
                            false);
                }
            } else {
                throw new RuntimeException("No operator handler found for base operator: " + baseOperator);
            }
            // Assign to the Lvalue. Preserve byte-string semantics for .=.
            if (node.operator.equals(".=")) {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "setPreservingByteString", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/runtimetypes/RuntimeScalar", "set", "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;", false);
            }
            // Discard set()/setPreservingByteString() return value and reload leftObj.
            // This matches how *Assign methods (addAssign, etc.) return arg1 directly —
            // for TIED_SCALAR lvalues the caller will trigger a 2nd FETCH when it reads
            // the result, giving the correct Perl semantics (fetch=2 for compound assigns).
            mv.visitInsn(Opcodes.POP);
            mv.visitVarInsn(Opcodes.ALOAD, leftSlot);
            if (pooledLeft) {
                emitterVisitor.ctx.javaClassInfo.releaseSpillSlot();
            }

            // For string concat assign (.=), invalidate pos() since string was modified
            if (node.operator.equals(".=")) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, 
                        "org/perlonjava/runtime/runtimetypes/RuntimePosLvalue", 
                        "invalidatePos", 
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)V", 
                        false);
            }
            
            EmitOperator.handleVoidContext(emitterVisitor);
        }
    }

    static void handleRangeOrFlipFlop(EmitterVisitor emitterVisitor, BinaryOperatorNode node) {
        if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            EmitLogicalOperator.emitFlipFlopOperator(emitterVisitor, node);
        } else {
            EmitOperator.handleRangeOperator(emitterVisitor, node);
        }
    }
}
