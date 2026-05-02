package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.runtime.operators.OperatorHandler;
import org.perlonjava.runtime.perlmodule.Strict;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.ScalarUtils;

import static org.perlonjava.backend.jvm.EmitOperator.emitOperator;

public class EmitBinaryOperator {
    static final boolean ENABLE_SPILL_BINARY_LHS = true;

    static void handleBinaryOperator(EmitterVisitor emitterVisitor, BinaryOperatorNode node, OperatorHandler operatorHandler) {
        EmitterVisitor scalarVisitor =
                emitterVisitor.with(RuntimeContextType.SCALAR); // execute operands in scalar context
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("handleBinaryOperator: " + node.toString());

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
        if (emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER)) {
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
        node.left.accept(scalarVisitor); // left parameter
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
        emitOperator(node, emitterVisitor);
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
        boolean isInteger = emitterVisitor.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_INTEGER);
        OperatorHandler operatorHandler;
        if (shouldUseWarnVariant && isInteger && node.operator.equals("/=")) {
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
            MethodVisitor mv = emitterVisitor.ctx.mv;

            // We need to properly handle the lvalue by using spill slots
            // This ensures the same object is both read and written
            node.left.accept(scalarVisitor); // target - left parameter
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

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
            MethodVisitor mv = emitterVisitor.ctx.mv;
            node.left.accept(scalarVisitor); // target - left parameter
            int leftSlot = emitterVisitor.ctx.javaClassInfo.acquireSpillSlot();
            boolean pooledLeft = leftSlot >= 0;
            if (!pooledLeft) {
                leftSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            }
            mv.visitVarInsn(Opcodes.ASTORE, leftSlot);

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
            OperatorHandler baseOpHandler = shouldUseWarnVariant
                    ? OperatorHandler.getWarn(baseOperator)
                    : OperatorHandler.get(baseOperator);
            if (baseOpHandler == null) {
                baseOpHandler = OperatorHandler.get(baseOperator);
            }
            if (baseOpHandler != null) {
                mv.visitMethodInsn(
                        baseOpHandler.methodType(),
                        baseOpHandler.className(),
                        baseOpHandler.methodName(),
                        baseOpHandler.descriptor(),
                        false);
            } else {
                throw new RuntimeException("No operator handler found for base operator: " + baseOperator);
            }
            // assign to the Lvalue
            // For .= use setPreservingByteString to prevent UTF-8 flag contamination of binary buffers
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
