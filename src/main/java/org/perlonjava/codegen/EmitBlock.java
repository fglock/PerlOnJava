package org.perlonjava.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.astnode.*;
import org.perlonjava.astvisitor.EmitterVisitor;
import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeContextType;

import java.util.List;

public class EmitBlock {

    private record MyVarInit(int varIndex, String varName, int beginId) {
    }

    private static void initMyVariablesAtScopeEntry(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        MyVarDeclarationCollector collector = new MyVarDeclarationCollector(emitterVisitor);
        for (int i = 0; i < node.elements.size(); i++) {
            Node element = node.elements.get(i);
            if (element != null) {
                element.accept(collector);
            }
        }

        for (MyVarInit init : collector.inits) {
            if (init.beginId == 0) {
                String initClassName = EmitterMethodCreator.getVariableClassName(init.varName);
                mv.visitTypeInsn(Opcodes.NEW, initClassName);
                mv.visitInsn(Opcodes.DUP);
                mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        initClassName,
                        "<init>",
                        "()V",
                        false);
            } else {
                String initMethodName;
                String initMethodDescriptor;
                switch (init.varName.charAt(0)) {
                    case '$' -> {
                        initMethodName = "retrieveBeginScalar";
                        initMethodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeScalar;";
                    }
                    case '@' -> {
                        initMethodName = "retrieveBeginArray";
                        initMethodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeArray;";
                    }
                    case '%' -> {
                        initMethodName = "retrieveBeginHash";
                        initMethodDescriptor = "(Ljava/lang/String;I)Lorg/perlonjava/runtime/RuntimeHash;";
                    }
                    default -> throw new PerlCompilerException(node.tokenIndex, "Unsupported variable type: " + init.varName.charAt(0), emitterVisitor.ctx.errorUtil);
                }

                mv.visitLdcInsn(init.varName);
                mv.visitLdcInsn(init.beginId);
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/PersistentVariable",
                        initMethodName,
                        initMethodDescriptor,
                        false);
            }
            mv.visitVarInsn(Opcodes.ASTORE, init.varIndex);
        }
    }

    private static final class MyVarDeclarationCollector implements Visitor {
        private final EmitterVisitor emitterVisitor;
        private final java.util.ArrayList<MyVarInit> inits = new java.util.ArrayList<>();
        private final java.util.HashSet<String> seen = new java.util.HashSet<>();

        private MyVarDeclarationCollector(EmitterVisitor emitterVisitor) {
            this.emitterVisitor = emitterVisitor;
        }

        @Override
        public void visit(OperatorNode node) {
            if ("my".equals(node.operator)) {
                collectFromMyOperand(node.operand);
                return;
            }
            if (node.operand != null) {
                node.operand.accept(this);
            }
        }

        private void collectFromMyOperand(Node operand) {
            if (operand instanceof BinaryOperatorNode binNode) {
                collectFromMyOperand(binNode.left);
                return;
            }
            if (operand instanceof ListNode listNode) {
                for (Node el : listNode.elements) {
                    collectFromMyOperand(el);
                }
                return;
            }
            if (operand instanceof OperatorNode sigilNode) {
                String sigil = sigilNode.operator;
                if ("$@%".contains(sigil) && sigilNode.operand instanceof IdentifierNode idNode) {
                    String varName = sigil + idNode.name;
                    addMyVar(varName, sigilNode.id, sigilNode);
                }
                return;
            }
            if (operand != null) {
                operand.accept(this);
            }
        }

        private void addMyVar(String varName, int beginId, OperatorNode astNode) {
            if (!seen.add(varName)) {
                return;
            }
            int existingIndex = emitterVisitor.ctx.symbolTable.getVariableIndexInCurrentScope(varName);
            int varIndex = existingIndex;
            if (varIndex == -1) {
                varIndex = emitterVisitor.ctx.symbolTable.addVariable(varName, "my", astNode);
            }
            inits.add(new MyVarInit(varIndex, varName, beginId));
        }

        @Override
        public void visit(BlockNode node) {
        }

        @Override
        public void visit(SubroutineNode node) {
        }

        @Override
        public void visit(BinaryOperatorNode node) {
            node.left.accept(this);
            node.right.accept(this);
        }

        @Override
        public void visit(IfNode node) {
            node.condition.accept(this);
        }

        @Override
        public void visit(For1Node node) {
            node.variable.accept(this);
            node.list.accept(this);
        }

        @Override
        public void visit(For3Node node) {
            if (node.initialization != null) {
                node.initialization.accept(this);
            }
            if (node.condition != null) {
                node.condition.accept(this);
            }
            if (node.increment != null) {
                node.increment.accept(this);
            }
        }

        @Override
        public void visit(TernaryOperatorNode node) {
            node.condition.accept(this);
            node.trueExpr.accept(this);
            node.falseExpr.accept(this);
        }

        @Override
        public void visit(ListNode node) {
            for (Node element : node.elements) {
                element.accept(this);
            }
        }

        @Override
        public void visit(HashLiteralNode node) {
            for (Node element : node.elements) {
                element.accept(this);
            }
        }

        @Override
        public void visit(ArrayLiteralNode node) {
            for (Node element : node.elements) {
                element.accept(this);
            }
        }

        @Override
        public void visit(TryNode node) {
        }

        @Override
        public void visit(NumberNode node) {
        }

        @Override
        public void visit(IdentifierNode node) {
        }

        @Override
        public void visit(StringNode node) {
        }

        @Override
        public void visit(LabelNode node) {
        }

        @Override
        public void visit(CompilerFlagNode node) {
        }

        @Override
        public void visit(FormatNode node) {
        }

        @Override
        public void visit(FormatLine node) {
        }
    }

    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The block node representing the block of statements.
     */
    public static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;

        emitterVisitor.ctx.logDebug("generateCodeBlock start context:" + emitterVisitor.ctx.contextType);
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();

        initMyVariablesAtScopeEntry(emitterVisitor, node);
        EmitterVisitor voidVisitor =
                emitterVisitor.with(RuntimeContextType.VOID); // statements in the middle of the block have context VOID
        List<Node> list = node.elements;

        // Create labels for the block as a loop, like `L1: {...}`
        Label redoLabel = new Label();
        Label nextLabel = new Label();

        // Create labels used inside the block, like `{ L1: ... }`
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.pushGotoLabels(node.labels.get(i), new Label());
        }

        // Setup 'local' environment if needed
        Local.localRecord localRecord = Local.localSetup(emitterVisitor.ctx, node, mv);

        // Add redo label
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                    node.labelName,
                    nextLabel,
                    redoLabel,
                    nextLabel,
                    emitterVisitor.ctx.contextType);
        }

        // Special case: detect pattern of `local $_` followed by `For1Node` with needsArrayOfAlias
        // In this case, we need to evaluate the For1Node's list before emitting the local operator
        if (list.size() >= 2 && 
            list.get(0) instanceof OperatorNode localOp && localOp.operator.equals("local") &&
            list.get(1) instanceof For1Node forNode && forNode.needsArrayOfAlias) {
            
            // Pre-evaluate the For1Node's list to array of aliases before localizing $_
            int tempArrayIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            forNode.list.accept(emitterVisitor.with(RuntimeContextType.LIST));
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/perlonjava/runtime/RuntimeBase", "getArrayOfAlias", "()Lorg/perlonjava/runtime/RuntimeArray;", false);
            mv.visitVarInsn(Opcodes.ASTORE, tempArrayIndex);
            
            // Mark the For1Node to use the pre-evaluated array
            forNode.preEvaluatedArrayIndex = tempArrayIndex;
        }

        for (int i = 0; i < list.size(); i++) {
            Node element = list.get(i);
            
            // Skip null elements - these occur when parseStatement returns null to signal
            // "not a statement, continue parsing" (e.g., AUTOLOAD without {}, try without feature enabled)
            // ParseBlock.parseBlock() adds these null results to the statements list
            if (element == null) {
                emitterVisitor.ctx.logDebug("Skipping null element in block at index " + i);
                continue;
            }

            ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, element.getIndex());

            // Emit the statement with current context
            if (i == list.size() - 1) {
                // Special case for the last element
                emitterVisitor.ctx.logDebug("Last element: " + element);
                element.accept(emitterVisitor);
            } else {
                // General case for all other elements
                emitterVisitor.ctx.logDebug("Element: " + element);
                element.accept(voidVisitor);
            }
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
        }

        // Pop labels used inside the block
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Add 'next', 'last' label
        mv.visitLabel(nextLabel);

        Local.localTeardown(localRecord, mv);

        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
        emitterVisitor.ctx.logDebug("generateCodeBlock end");
    }

}
