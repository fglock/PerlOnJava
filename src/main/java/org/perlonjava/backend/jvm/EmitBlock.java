package org.perlonjava.backend.jvm;

import org.perlonjava.app.cli.CompilerOptions;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.perlonjava.backend.jvm.astrefactor.LargeBlockRefactorer;
import org.perlonjava.frontend.analysis.EmitterVisitor;
import org.perlonjava.frontend.analysis.RegexUsageDetector;
import org.perlonjava.frontend.analysis.DoBlockResultAnalysis;
import org.perlonjava.frontend.astnode.*;
import org.perlonjava.runtime.perlmodule.Warnings;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EmitBlock {

    private static void collectStateDeclSigilNodes(Node node, Set<OperatorNode> out) {
        if (node == null) {
            return;
        }
        if (node instanceof OperatorNode op) {
            if ("state".equals(op.operator) && op.operand instanceof OperatorNode sigilNode) {
                if (sigilNode.operand instanceof IdentifierNode && "$@%".contains(sigilNode.operator)) {
                    out.add(sigilNode);
                }
            }
            if ("state".equals(op.operator) && op.operand instanceof ListNode listNode) {
                for (Node element : listNode.elements) {
                    if (element instanceof OperatorNode sigilNode
                            && sigilNode.operand instanceof IdentifierNode
                            && "$@%".contains(sigilNode.operator)) {
                        out.add(sigilNode);
                    }
                }
            }
            collectStateDeclSigilNodes(op.operand, out);
            return;
        }
        if (node instanceof BinaryOperatorNode bin) {
            collectStateDeclSigilNodes(bin.left, out);
            collectStateDeclSigilNodes(bin.right, out);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) {
                collectStateDeclSigilNodes(child, out);
            }
            return;
        }
        if (node instanceof BlockNode block) {
            for (Node child : block.elements) {
                collectStateDeclSigilNodes(child, out);
            }
        }
        if (node instanceof For1Node for1) {
            collectStateDeclSigilNodes(for1.variable, out);
            collectStateDeclSigilNodes(for1.list, out);
            collectStateDeclSigilNodes(for1.body, out);
            collectStateDeclSigilNodes(for1.continueBlock, out);
            return;
        }
        if (node instanceof For3Node for3) {
            collectStateDeclSigilNodes(for3.initialization, out);
            collectStateDeclSigilNodes(for3.condition, out);
            collectStateDeclSigilNodes(for3.increment, out);
            collectStateDeclSigilNodes(for3.body, out);
            collectStateDeclSigilNodes(for3.continueBlock, out);
            return;
        }
        if (node instanceof IfNode ifNode) {
            collectStateDeclSigilNodes(ifNode.condition, out);
            collectStateDeclSigilNodes(ifNode.thenBranch, out);
            collectStateDeclSigilNodes(ifNode.elseBranch, out);
            return;
        }
        if (node instanceof TryNode tryNode) {
            collectStateDeclSigilNodes(tryNode.tryBlock, out);
            collectStateDeclSigilNodes(tryNode.catchBlock, out);
            collectStateDeclSigilNodes(tryNode.finallyBlock, out);
        }
    }

    private static void collectStatementLabelNames(List<Node> elements, List<String> out) {
        for (Node element : elements) {
            collectStatementLabelNamesRecursive(element, out);
        }
    }

    private static void collectStatementLabelNamesRecursive(Node node, List<String> out) {
        if (node == null) return;
        if (node instanceof LabelNode labelNode) {
            out.add(labelNode.label);
        } else if (node instanceof BlockNode block) {
            for (Node child : block.elements) {
                collectStatementLabelNamesRecursive(child, out);
            }
        }
    }

    /** Collect labels that a goto may target without leaving a finally body. */
    static Set<String> collectLabels(Node node) {
        Set<String> labels = new LinkedHashSet<>();
        collectLabels(node, labels);
        return labels;
    }

    private static void collectLabels(Node node, Set<String> labels) {
        if (node == null || node instanceof SubroutineNode) return;
        if (node instanceof LabelNode label) {
            labels.add(label.label);
        } else if (node instanceof BlockNode block) {
            labels.addAll(block.labels);
            for (Node child : block.elements) collectLabels(child, labels);
        } else if (node instanceof IfNode conditional) {
            collectLabels(conditional.thenBranch, labels);
            collectLabels(conditional.elseBranch, labels);
        } else if (node instanceof For1Node loop) {
            collectLabels(loop.body, labels);
            collectLabels(loop.continueBlock, labels);
        } else if (node instanceof For3Node loop) {
            collectLabels(loop.body, labels);
            collectLabels(loop.continueBlock, labels);
        } else if (node instanceof OperatorNode operator) {
            collectLabels(operator.operand, labels);
        } else if (node instanceof ListNode list) {
            for (Node child : list.elements) collectLabels(child, labels);
        } else if (node instanceof BinaryOperatorNode binary) {
            collectLabels(binary.left, labels);
            collectLabels(binary.right, labels);
        } else if (node instanceof TernaryOperatorNode ternary) {
            collectLabels(ternary.trueExpr, labels);
            collectLabels(ternary.falseExpr, labels);
        } else if (node instanceof TryNode attempt) {
            collectLabels(attempt.tryBlock, labels);
            collectLabels(attempt.catchBlock, labels);
            collectLabels(attempt.finallyBlock, labels);
        }
    }

    /**
     * Record labels that are only reachable after a loop has initialized its
     * iterator and control state.  An eval may not jump into such a body.
     */
    private static void collectLoopBodyLabels(Node node, Set<String> out,
            Map<String, Integer> tokenIndices, boolean insideLoop) {
        if (node == null) return;
        if (node instanceof LabelNode labelNode) {
            if (insideLoop) {
                out.add(labelNode.label);
                tokenIndices.putIfAbsent(labelNode.label, labelNode.getIndex());
            }
            return;
        }
        if (node instanceof For1Node for1) {
            collectLoopBodyLabels(for1.body, out, tokenIndices, true);
            collectLoopBodyLabels(for1.continueBlock, out, tokenIndices, true);
            return;
        }
        if (node instanceof For3Node for3) {
            collectLoopBodyLabels(for3.body, out, tokenIndices, true);
            collectLoopBodyLabels(for3.continueBlock, out, tokenIndices, true);
            return;
        }
        if (node instanceof BlockNode block) {
            if (insideLoop) {
                for (String label : block.labels) {
                    out.add(label);
                    tokenIndices.putIfAbsent(label, block.getIndex());
                }
            }
            for (Node child : block.elements) collectLoopBodyLabels(child, out, tokenIndices, insideLoop);
            return;
        }
        if (node instanceof SubroutineNode subroutine) {
            // An eval BLOCK is emitted as a separate JVM method but remains
            // within the enclosing Perl lexical control-flow scope.  Carry
            // protected foreach destinations into that method so a goto is
            // rejected there instead of propagating by name to a later outer
            // label. Ordinary subroutines remain control-flow boundaries.
            if (subroutine.useTryCatch) {
                collectLoopBodyLabels(subroutine.block, out, tokenIndices, insideLoop);
            }
            return;
        }
        if (node instanceof IfNode ifNode) {
            collectLoopBodyLabels(ifNode.thenBranch, out, tokenIndices, insideLoop);
            collectLoopBodyLabels(ifNode.elseBranch, out, tokenIndices, insideLoop);
            return;
        }
        // Parser-generated wrappers such as `local $_` around foreach must
        // not hide a label that is structurally in the loop body.  Do not
        // descend into SubroutineNode: ordinary subroutines are lexical
        // control-flow boundaries and compile independently.
        if (node instanceof OperatorNode operator) {
            collectLoopBodyLabels(operator.operand, out, tokenIndices, insideLoop);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) collectLoopBodyLabels(child, out, tokenIndices, insideLoop);
            return;
        }
        if (node instanceof BinaryOperatorNode binary) {
            collectLoopBodyLabels(binary.left, out, tokenIndices, insideLoop);
            collectLoopBodyLabels(binary.right, out, tokenIndices, insideLoop);
            return;
        }
        if (node instanceof TernaryOperatorNode ternary) {
            collectLoopBodyLabels(ternary.condition, out, tokenIndices, insideLoop);
            collectLoopBodyLabels(ternary.trueExpr, out, tokenIndices, insideLoop);
            collectLoopBodyLabels(ternary.falseExpr, out, tokenIndices, insideLoop);
        }
    }

    /** Collect protected foreach destinations for a separately compiled eval block. */
    static void collectEvalLoopBodyLabels(Node block, JavaClassInfo javaClassInfo) {
        collectLoopBodyLabels(block, javaClassInfo.gotoLabelsInsideLoop,
                javaClassInfo.gotoLoopLabelTokenIndices, false);
    }

    /**
     * Record labels in expression-level do blocks before emitting their
     * containing block. A goto to one would skip the enclosing expression's
     * setup, which Perl rejects.
     */
    /** Collect labels inside defer blocks so goto validation crosses their boundary. */
    static void collectDeferLabels(Node node, Set<String> out) {
        collectDeferLabels(node, out, false);
    }

    private static void collectDeferLabels(Node node, Set<String> out, boolean insideDefer) {
        if (node == null) return;
        if (node instanceof BinaryOperatorNode binary && isDeferBlockCall(binary)) {
            collectDeferLabels(binary.left, out, true);
            return;
        }
        if (node instanceof DeferNode defer) {
            collectDeferLabels(defer.block, out, true);
            return;
        }
        if (node instanceof SubroutineNode) return;
        if (node instanceof LabelNode label) {
            if (insideDefer) out.add(label.label);
            return;
        }
        if (node instanceof BlockNode block) {
            if (insideDefer) out.addAll(block.labels);
            for (Node child : block.elements) collectDeferLabels(child, out, insideDefer);
        } else if (node instanceof IfNode conditional) {
            collectDeferLabels(conditional.condition, out, insideDefer);
            collectDeferLabels(conditional.thenBranch, out, insideDefer);
            collectDeferLabels(conditional.elseBranch, out, insideDefer);
        } else if (node instanceof For3Node loop) {
            collectDeferLabels(loop.initialization, out, insideDefer);
            collectDeferLabels(loop.condition, out, insideDefer);
            collectDeferLabels(loop.increment, out, insideDefer);
            collectDeferLabels(loop.body, out, insideDefer);
            collectDeferLabels(loop.continueBlock, out, insideDefer);
        } else if (node instanceof For1Node loop) {
            collectDeferLabels(loop.variable, out, insideDefer);
            collectDeferLabels(loop.list, out, insideDefer);
            collectDeferLabels(loop.body, out, insideDefer);
            collectDeferLabels(loop.continueBlock, out, insideDefer);
        } else if (node instanceof OperatorNode operator) {
            collectDeferLabels(operator.operand, out, insideDefer);
        } else if (node instanceof ListNode list) {
            for (Node child : list.elements) collectDeferLabels(child, out, insideDefer);
        } else if (node instanceof BinaryOperatorNode binary) {
            collectDeferLabels(binary.left, out, insideDefer);
            collectDeferLabels(binary.right, out, insideDefer);
        } else if (node instanceof TernaryOperatorNode ternary) {
            collectDeferLabels(ternary.condition, out, insideDefer);
            collectDeferLabels(ternary.trueExpr, out, insideDefer);
            collectDeferLabels(ternary.falseExpr, out, insideDefer);
        }
    }

    private static boolean isDeferBlockCall(BinaryOperatorNode binary) {
        if (!"->".equals(binary.operator)
                || !(binary.left instanceof BlockNode)
                || !(binary.right instanceof BinaryOperatorNode invocation)
                || !"(".equals(invocation.operator)
                || !(invocation.left instanceof OperatorNode ampersand)
                || !"&".equals(ampersand.operator)
                || !(ampersand.operand instanceof IdentifierNode identifier)) return false;
        return "defer".equals(identifier.name);
    }

    private static void collectGivenLabels(Node node, Set<String> out,
            Map<String, Integer> tokenIndices, boolean insideGiven) {
        if (node == null) return;
        if (node instanceof LabelNode labelNode) {
            if (insideGiven) {
                out.add(labelNode.label);
                tokenIndices.putIfAbsent(labelNode.label, labelNode.getIndex());
            }
            return;
        }
        if (node instanceof BlockNode block) {
            boolean nestedGiven = insideGiven || block.getBooleanAnnotation("givenBlock");
            if (nestedGiven) {
                out.addAll(block.labels);
                for (String label : block.labels) tokenIndices.putIfAbsent(label, block.getIndex());
            }
            for (Node child : block.elements) collectGivenLabels(child, out, tokenIndices, nestedGiven);
            return;
        }
        if (node instanceof IfNode conditional) {
            collectGivenLabels(conditional.condition, out, tokenIndices, insideGiven);
            collectGivenLabels(conditional.thenBranch, out, tokenIndices, insideGiven);
            collectGivenLabels(conditional.elseBranch, out, tokenIndices, insideGiven);
        }
    }

    private static void collectConstructEntryLabels(
            Node node, Set<String> out, boolean expressionContext, boolean fieldInitializer,
            boolean insideEvalBlock, String currentPackage) {
        if (node == null) return;
        if (node instanceof AbstractNode abstractNode) {
            fieldInitializer |= abstractNode.getBooleanAnnotation("fieldInitializer");
        }
        if (node instanceof LabelNode labelNode) {
            if (expressionContext && !fieldInitializer) {
                out.add(labelNode.label);
            } else {
                // A later unconditional definition of the same Perl label is
                // the reachable destination. Do not let an earlier
                // expression-only occurrence poison it by name.
                out.remove(labelNode.label);
            }
            return;
        }
        if (node instanceof BlockNode block) {
            if (expressionContext && !fieldInitializer) {
                out.addAll(block.labels);
            } else {
                out.removeAll(block.labels);
            }
            for (Node child : block.elements) collectConstructEntryLabels(child, out,
                    expressionContext, fieldInitializer, insideEvalBlock, currentPackage);
            return;
        }
        if (node instanceof SubroutineNode subroutine) {
            collectConstructEntryLabels(subroutine.block, out, true, fieldInitializer,
                    insideEvalBlock || subroutine.useTryCatch, currentPackage);
            return;
        }
        if (node instanceof OperatorNode op) {
            collectConstructEntryLabels(op.operand, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) collectConstructEntryLabels(child, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            return;
        }
        if (node instanceof BinaryOperatorNode binary) {
            collectConstructEntryLabels(binary.left, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            collectConstructEntryLabels(binary.right, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            return;
        }
        if (node instanceof TernaryOperatorNode ternary) {
            collectConstructEntryLabels(ternary.condition, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            collectConstructEntryLabels(ternary.trueExpr, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            collectConstructEntryLabels(ternary.falseExpr, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            return;
        }
        if (node instanceof IfNode ifNode) {
            collectConstructEntryLabels(ifNode.condition, out, true, fieldInitializer, insideEvalBlock, currentPackage);
            Boolean constantValue = EmitStatement.constantIfConditionValue(ifNode, currentPackage);
            if (constantValue == null) {
                collectConstructEntryLabels(ifNode.thenBranch, out, true, fieldInitializer,
                        insideEvalBlock, currentPackage);
                collectConstructEntryLabels(ifNode.elseBranch, out, true, fieldInitializer,
                        insideEvalBlock, currentPackage);
            } else {
                Node liveBranch = constantValue ? ifNode.thenBranch : ifNode.elseBranch;
                collectConstructEntryLabels(liveBranch, out, true, fieldInitializer,
                        insideEvalBlock, currentPackage);
            }
        }
    }

    private static void collectConditionalGotoContexts(Node node, Map<String, Set<Integer>> labelContexts,
            Map<Integer, Set<Integer>> sourceContexts, Set<String> simpleConditionalBranchLabels,
            Set<Integer> conditionalContexts, boolean simpleConditionalBranch) {
        if (node == null) return;
        if (node instanceof LabelNode label) {
            if (!conditionalContexts.isEmpty()) {
                labelContexts.put(label.label, Set.copyOf(conditionalContexts));
                if (simpleConditionalBranch) simpleConditionalBranchLabels.add(label.label);
            } else {
                labelContexts.remove(label.label);
                simpleConditionalBranchLabels.remove(label.label);
            }
            return;
        }
        if (node instanceof BlockNode block) {
            for (String label : block.labels) {
                if (!conditionalContexts.isEmpty()) {
                    labelContexts.put(label, Set.copyOf(conditionalContexts));
                    if (simpleConditionalBranch) simpleConditionalBranchLabels.add(label);
                } else {
                    labelContexts.remove(label);
                    simpleConditionalBranchLabels.remove(label);
                }
            }
            for (Node child : block.elements) {
                collectConditionalGotoContexts(child, labelContexts, sourceContexts,
                        simpleConditionalBranchLabels, conditionalContexts, false);
            }
            return;
        }
        if (node instanceof IfNode conditional) {
            collectConditionalGotoContexts(conditional.condition, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            Set<Integer> branchContexts = new LinkedHashSet<>(conditionalContexts);
            branchContexts.add(conditional.getIndex());
            collectConditionalGotoContexts(conditional.thenBranch, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, branchContexts, true);
            collectConditionalGotoContexts(conditional.elseBranch, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, branchContexts, true);
            return;
        }
        if (node instanceof OperatorNode operator) {
            if ("goto".equals(operator.operator)) {
                sourceContexts.putIfAbsent(operator.getIndex(), Set.copyOf(conditionalContexts));
            }
            collectConditionalGotoContexts(operator.operand, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            return;
        }
        if (node instanceof SubroutineNode subroutine) {
            collectConditionalGotoContexts(subroutine.block, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            return;
        }
        if (node instanceof For1Node loop) {
            collectConditionalGotoContexts(loop.list, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.body, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.continueBlock, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            return;
        }
        if (node instanceof For3Node loop) {
            collectConditionalGotoContexts(loop.initialization, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.condition, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.increment, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.body, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(loop.continueBlock, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) {
                collectConditionalGotoContexts(child, labelContexts, sourceContexts,
                        simpleConditionalBranchLabels, conditionalContexts, false);
            }
            return;
        }
        if (node instanceof BinaryOperatorNode binary) {
            collectConditionalGotoContexts(binary.left, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(binary.right, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            return;
        }
        if (node instanceof TernaryOperatorNode ternary) {
            collectConditionalGotoContexts(ternary.condition, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(ternary.trueExpr, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
            collectConditionalGotoContexts(ternary.falseExpr, labelContexts, sourceContexts,
                    simpleConditionalBranchLabels, conditionalContexts, false);
        }
    }

    /** Labels in binary/list operands use Perl's more specific diagnostic. */
    private static void collectBinaryOrListExpressionLabels(Node node, Set<String> out) {
        collectBinaryOrListExpressionLabels(node, out, false, false);
    }

    private static void collectBinaryOrListExpressionLabels(Node node, Set<String> out,
            boolean expressionOperand, boolean fieldInitializer) {
        if (node == null) return;
        if (node instanceof AbstractNode abstractNode) {
            fieldInitializer |= abstractNode.getBooleanAnnotation("fieldInitializer");
        }
        if (node instanceof BlockNode block) {
            if (expressionOperand && !fieldInitializer) out.addAll(block.labels);
            for (Node child : block.elements) {
                collectBinaryOrListExpressionLabels(child, out, false, fieldInitializer);
            }
            return;
        }
        if (node instanceof SubroutineNode subroutine) {
            collectBinaryOrListExpressionLabels(subroutine.block, out, expressionOperand, fieldInitializer);
            return;
        }
        if (node instanceof BinaryOperatorNode binary) {
            collectBinaryOrListExpressionLabels(binary.left, out, true, fieldInitializer);
            collectBinaryOrListExpressionLabels(binary.right, out, true, fieldInitializer);
            return;
        }
        if (node instanceof ListNode list) {
            for (Node child : list.elements) {
                collectBinaryOrListExpressionLabels(child, out, true, fieldInitializer);
            }
            return;
        }
        if (node instanceof OperatorNode operator
                && (operator.operator.equals("map") || operator.operator.equals("grep"))) {
            collectBinaryOrListExpressionLabels(operator.operand, out, true, fieldInitializer);
        }
    }

    static void collectIfChainLabels(IfNode ifNode, List<String> out) {
        collectStatementLabelNamesRecursive(ifNode.thenBranch, out);
        if (ifNode.elseBranch instanceof IfNode elseIf) {
            collectIfChainLabels(elseIf, out);
        } else {
            collectStatementLabelNamesRecursive(ifNode.elseBranch, out);
        }
    }

    static int pushNewGotoLabels(JavaClassInfo javaClassInfo, List<String> labelNames) {
        int pushed = 0;
        for (String labelName : labelNames) {
            if (javaClassInfo.findGotoLabelsByName(labelName) == null) {
                javaClassInfo.pushGotoLabels(labelName, new Label());
                pushed++;
            }
        }
        return pushed;
    }

    /**
     * Emits bytecode for a block of statements.
     *
     * @param emitterVisitor The visitor used for code emission.
     * @param node           The block node representing the block of statements.
     */
    public static void emitBlock(EmitterVisitor emitterVisitor, BlockNode node) {
        MethodVisitor mv = emitterVisitor.ctx.mv;
        collectLoopBodyLabels(node, emitterVisitor.ctx.javaClassInfo.gotoLabelsInsideLoop,
                emitterVisitor.ctx.javaClassInfo.gotoLoopLabelTokenIndices, false);
        collectConditionalGotoContexts(node,
                emitterVisitor.ctx.javaClassInfo.gotoConditionalLabelContexts,
                emitterVisitor.ctx.javaClassInfo.gotoConditionalSourceContexts,
                emitterVisitor.ctx.javaClassInfo.gotoSimpleConditionalBranchLabels, Set.of(), false);
        collectConstructEntryLabels(node, emitterVisitor.ctx.javaClassInfo.gotoLabelsInsideConstruct,
                false, false, false, emitterVisitor.ctx.symbolTable.getCurrentPackage());
        collectBinaryOrListExpressionLabels(node,
                emitterVisitor.ctx.javaClassInfo.gotoLabelsInsideBinaryOrListExpression);
        collectGivenLabels(node, emitterVisitor.ctx.javaClassInfo.gotoLabelsInsideGiven,
                emitterVisitor.ctx.javaClassInfo.gotoGivenLabelTokenIndices, false);
        collectDeferLabels(node, emitterVisitor.ctx.javaClassInfo.gotoLabelsInsideDefer);

        // Try to refactor large blocks using the helper class
        if (LargeBlockRefactorer.processBlock(emitterVisitor, node)) {
            // Block was refactored and emitted by the helper
            return;
        }

        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("generateCodeBlock start context:" + emitterVisitor.ctx.contextType);
        int scopeIndex = emitterVisitor.ctx.symbolTable.enterScope();
        EmitterVisitor voidVisitor =
                emitterVisitor.with(RuntimeContextType.VOID); // statements in the middle of the block have context VOID
        List<Node> list = node.elements;

        // Hoist `state` declarations to the beginning of the block scope so that JVM local slots
        // are initialized even if a `goto` skips the original declaration statement.
        // This prevents NPEs when later code evaluates e.g. `defined $state_var`.
        Set<OperatorNode> stateDeclSigilNodes = new LinkedHashSet<>();
        for (Node element : list) {
            collectStateDeclSigilNodes(element, stateDeclSigilNodes);
        }
        if (!stateDeclSigilNodes.isEmpty()) {
            // Suppress "masks earlier declaration" warning during hoisting AND mark
            // the original nodes so they won't re-warn when processed later.
            boolean isWarningEnabled = Warnings.warningManager.isWarningEnabled("redefine");
            if (isWarningEnabled) {
                Warnings.warningManager.setWarningState("redefine", false);
            }
            for (OperatorNode sigilNode : stateDeclSigilNodes) {
                new OperatorNode("state", sigilNode, sigilNode.tokenIndex)
                        .accept(voidVisitor);
                // Mark the original sigil node so the real declaration suppresses its warning
                sigilNode.setAnnotation("hoistedState", true);
            }
            if (isWarningEnabled) {
                Warnings.warningManager.setWarningState("redefine", true);
            }
        }

        int lastNonNullIndex = -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            Node elem = list.get(i);
            if (elem != null
                    && !(elem instanceof CompilerFlagNode)
                    && !(elem instanceof AbstractNode ab && (ab.getBooleanAnnotation("compileTimeOnly") || ab.getBooleanAnnotation("noReturnValue")))) {
                lastNonNullIndex = i;
                break;
            }
        }
        if (lastNonNullIndex == -1) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) != null) {
                    lastNonNullIndex = i;
                    break;
                }
            }
        }

        // Create labels for the block as a loop, like `L1: {...}`
        Label redoLabel = new Label();
        Label nextLabel = new Label();

        // Pre-register statement labels (e.g. `NEXT:`) in this block so `goto NEXT` can resolve
        // even when the goto appears before the label (forward goto).
        //
        // We intentionally only register labels at this block's top level. Nested blocks get
        // their own EmitBlock invocation and maintain proper scoping/shadowing via the stack.
        List<String> statementLabelNames = new ArrayList<>();
        collectStatementLabelNames(list, statementLabelNames);
        Set<String> labelsAlreadyVisible = new LinkedHashSet<>();
        for (String labelName : statementLabelNames) {
            if (emitterVisitor.ctx.javaClassInfo.findGotoLabelsByName(labelName) != null) {
                labelsAlreadyVisible.add(labelName);
            }
        }
        int statementLabelsPushed = pushNewGotoLabels(emitterVisitor.ctx.javaClassInfo, statementLabelNames);

        // Create labels used inside the block, like `{ L1: ... }`
        Set<String> directStatementLabelNames = new LinkedHashSet<>();
        for (Node element : list) {
            if (element instanceof LabelNode labelNode) {
                directStatementLabelNames.add(labelNode.label);
            }
        }
        for (int i = 0; i < node.labels.size(); i++) {
            String labelName = node.labels.get(i);
            GotoLabels statementLabel = !labelsAlreadyVisible.contains(labelName)
                    && directStatementLabelNames.contains(labelName)
                    ? emitterVisitor.ctx.javaClassInfo.findGotoLabelsByName(labelName)
                    : null;
            Label target = statementLabel == null ? new Label() : statementLabel.gotoLabel;
            emitterVisitor.ctx.javaClassInfo.pushGotoLabels(labelName, target);
        }

        // Setup 'local' environment if needed
        Local.localRecord localRecord = node.getBooleanAnnotation("regexCallbackBody")
                ? new Local.localRecord(false, -1)
                : Local.localSetup(emitterVisitor.ctx, node, mv, true);

        int regexStateLocal = -1;
        if (!node.getBooleanAnnotation("blockIsSubroutine")
                && !node.getBooleanAnnotation("skipRegexSaveRestore")
                && RegexUsageDetector.containsRegexOperation(node)) {
            regexStateLocal = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            mv.visitTypeInsn(Opcodes.NEW, "org/perlonjava/runtime/runtimetypes/RegexState");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, regexStateLocal);
        }

        // Add redo label
        mv.visitLabel(redoLabel);

        // Restore 'local' environment if 'redo' was called
        Local.localTeardown(localRecord, mv);

        if (node.isLoop) {
            // A labeled/bare block used as a loop target (e.g. SKIP: { ... }) is a
            // pseudo-loop: it supports labeled next/last/redo (e.g. next SKIP), but
            // an unlabeled next/last/redo must target the nearest enclosing true loop.
            //
            // However, a *bare* block with loop control (e.g. `{ ...; redo }` or
            // `{ ... } continue { ... }`) is itself a valid target for *unlabeled*
            // last/next/redo, matching Perl semantics.
            boolean topicalizerLoopBody = node.getBooleanAnnotation("topicalizerLoopBody");
            boolean isBareBlock = node.labelName == null
                    && !node.getBooleanAnnotation("givenBlock");
            emitterVisitor.ctx.javaClassInfo.pushLoopLabels(
                    node.labelName,
                    nextLabel,
                    redoLabel,
                    nextLabel,
                    emitterVisitor.ctx.contextType,
                    topicalizerLoopBody ? false : isBareBlock,
                    topicalizerLoopBody ? false : isBareBlock);
            LoopLabels loopLabels = emitterVisitor.ctx.javaClassInfo.getInnermostLoopLabels();
            Object resultRegister = node.getAnnotation("resultRegister");
            if (resultRegister instanceof Integer resultSlot) {
                loopLabels.resultRegisterSlot = resultSlot;
                Object resultContext = node.getAnnotation("resultRegisterContext");
                if (resultContext instanceof Integer context) {
                    loopLabels.resultRegisterContext = context;
                }
            }
            loopLabels.implicitWhenTarget = node.getBooleanAnnotation("givenBlock") || topicalizerLoopBody;
            // An implicit `last` from a when, or an explicit `break`, jumps
            // past this synthetic given block.  Record its lexical boundary
            // so loop control tears down variables declared by nested when
            // bodies before reaching that jump target.
            loopLabels.cleanupScopeIndex = scopeIndex;
            if (localRecord.needsCleanup()) {
                loopLabels.dynamicLocalLevelSlot = localRecord.dynamicIndex();
            }
        }

        // Special case: detect pattern of `local $_` followed by `For1Node` with needsArrayOfAlias
        // In this case, we need to evaluate the For1Node's list before emitting the local operator
        For1Node preEvalForNode = null;
        int savedPreEvaluatedArrayIndex = -1;

        if (list.size() >= 2 &&
                list.get(0) instanceof OperatorNode localOp && localOp.operator.equals("local") &&
                list.get(1) instanceof For1Node forNode && forNode.needsArrayOfAlias) {

            // Pre-evaluate the For1Node's list before localizing $_. Keep an
            // actual array live; foreachAliasIterator snapshots other list
            // expressions when the loop is emitted.
            int tempArrayIndex = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            EmitForeach.emitForeachSource(emitterVisitor, forNode.list);
            mv.visitVarInsn(Opcodes.ASTORE, tempArrayIndex);

            // Mark the For1Node to use the pre-evaluated array
            preEvalForNode = forNode;
            savedPreEvaluatedArrayIndex = forNode.preEvaluatedArrayIndex;
            forNode.preEvaluatedArrayIndex = tempArrayIndex;
        }

        int savedStatementTokenIndex = emitterVisitor.ctx.javaClassInfo.statementTokenIndex;
        try {
            for (int i = 0; i < list.size(); i++) {
                Node element = list.get(i);

                // Skip null elements - these occur when parseStatement returns null to signal
                // "not a statement, continue parsing" (e.g., AUTOLOAD without {}, try without feature enabled)
                // ParseBlock.parseBlock() adds these null results to the statements list
                if (element == null) {
                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("Skipping null element in block at index " + i);
                    continue;
                }

                // Skip source location mapping for infrastructure nodes (e.g., BEGIN wrapper's
                // package declarations). These nodes are marked with skipDebug by SpecialBlockParser
                // to prevent them from storing incorrect package info in ByteCodeSourceMapper
                // before the package change takes effect (fixes caller() inside BEGIN blocks).
                if (!(element instanceof AbstractNode an && an.getBooleanAnnotation("skipDebug"))) {
                    ByteCodeSourceMapper.setDebugInfoLineNumber(emitterVisitor.ctx, element.getIndex());
                }

                // Perl attaches one COP per statement, so calls anywhere inside a
                // multi-line statement report that statement's line. Publish it for
                // the call emitters (see EmitSubroutine / Dereference / EmitOperator).
                // A do-block's lone statement inherits the enclosing statement's
                // line (op_scope; see StatementCopline).
                if (!(element instanceof AbstractNode scoped
                        && scoped.getBooleanAnnotation("inheritEnclosingCopline"))) {
                    emitterVisitor.ctx.javaClassInfo.statementTokenIndex =
                            element instanceof AbstractNode stmtNode
                                    && stmtNode.getAnnotation("statementStartIndex") instanceof Integer start
                                    && start > 0
                                    ? start
                                    : -1;
                }

                // Check if this block should store its result in a register (for bare block expressions)
                Object resultRegObj = node.getAnnotation("resultRegister");
                int resultReg = (resultRegObj instanceof Integer) ? (Integer) resultRegObj : -1;

                // Format declarations have no runtime value, but their registration
                // is a required compile-time side effect.  Visit them in VOID
                // context so write FILEHANDLE can find the declared format.
                // EmitFormat itself discards the resulting RuntimeFormat value.
                // Emit the statement with current context
                if (i == lastNonNullIndex) {
                    // Special case for the last element
                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("Last element: " + element);
                    if (resultReg >= 0) {
                        // Preserve the enclosing bare block's context. In particular, an
                        // lvalue sub may end in `{ @array }`, whose list container must
                        // survive the block boundary as an assignment target.
                        Object resultContext = node.getAnnotation("resultRegisterContext");
                        int context = resultContext instanceof Integer
                                ? (Integer) resultContext : RuntimeContextType.SCALAR;
                        element.accept(emitterVisitor.with(context));
                        mv.visitVarInsn(Opcodes.ASTORE, resultReg);
                    } else if (emitterVisitor.ctx.contextType == RuntimeContextType.RUNTIME
                            && (node.getBooleanAnnotation("isFileLevelBlock") || node.getBooleanAnnotation("blockIsSubroutine"))
                            && element instanceof For3Node for3
                            && for3.isSimpleBlock
                            && for3.labelName == null) {
                        // Bare block (no label) as last statement in file-level RUNTIME context
                        // or inside a subroutine. This handles do "file", require, and sub { { 99 } }.
                        // An lvalue sub must preserve its assignment-target context through
                        // the nested bare block instead of reducing an array result to scalar.
                        int blockContext = emitterVisitor.ctx.javaClassInfo.isLvalueSubroutine
                                ? RuntimeContextType.LVALUE : RuntimeContextType.SCALAR;
                        element.accept(emitterVisitor.with(blockContext));
                    } else {
                        element.accept(emitterVisitor);
                    }
                } else {
                    // General case for all other elements
                    if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("Element: " + element);
                    element.accept(voidVisitor);
                }

                // NOTE: Registry checks are DISABLED in EmitBlock because:
                // 1. They cause ASM frame computation errors in nested/refactored code
                // 2. Bare labeled blocks (like TODO:) don't need non-local control flow
                // 3. Real loops (for/while/foreach) have their own registry checks in
                //    EmitForeach.java and EmitStatement.java that work correctly
                //
                // This means non-local control flow (next LABEL from closures) works for
                // actual loop constructs but NOT for bare labeled blocks, which is correct
                // Perl behavior anyway.

                // After a block/control flow node exits, restore the parent's hint hash
                // so subsequent statements have the correct compile-time %^H for caller()[10].
                if (element instanceof AbstractNode an) {
                    Object hintIdObj = an.getAnnotation("postBlockHintHashId");
                    if (hintIdObj instanceof Integer hintId) {
                        mv.visitLdcInsn(hintId);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "org/perlonjava/runtime/HintHashRegistry",
                                "setCallSiteHintHashId",
                                "(I)V", false);
                    }
                    emitPostBlockStrictOptions(mv, an);
                }

                if (i < lastNonNullIndex) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/perlonjava/runtime/runtimetypes/MortalList",
                            "flushAboveMark",
                            "()V",
                            false);
                }

            }
        } finally {
            emitterVisitor.ctx.javaClassInfo.statementTokenIndex = savedStatementTokenIndex;
            if (preEvalForNode != null) {
                preEvalForNode.preEvaluatedArrayIndex = savedPreEvaluatedArrayIndex;
            }
        }

        if (node.isLoop) {
            emitterVisitor.ctx.javaClassInfo.popLoopLabels();
        }

        // Pop labels used inside the block
        for (int i = 0; i < node.labels.size(); i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Pop statement labels registered for this block
        for (int i = 0; i < statementLabelsPushed; i++) {
            emitterVisitor.ctx.javaClassInfo.popGotoLabels();
        }

        // Add 'next', 'last' label
        mv.visitLabel(nextLabel);

        // Materialize any special variable proxies (e.g., $1, $&) in the block result
        // BEFORE restoring regex state, so the values reflect the block's regex matches
        // rather than the restored caller state.
        // Only in SCALAR context where we know the stack has a RuntimeScalar.
        if (regexStateLocal >= 0 && emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "materializeBlockResult",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                    false);
        }

        if (node.getBooleanAnnotation("blockIsDoBlock")) {
            if (emitterVisitor.ctx.contextType == RuntimeContextType.SCALAR) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                        "copyDoBlockResult",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;)Lorg/perlonjava/runtime/runtimetypes/RuntimeScalar;",
                        false);
            } else if (emitterVisitor.ctx.contextType == RuntimeContextType.LIST) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                        "copyDoBlockListResult",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;",
                        false);
            }
        }

        Local.localTeardown(localRecord, mv);

        if (regexStateLocal >= 0) {
            mv.visitVarInsn(Opcodes.ALOAD, regexStateLocal);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "org/perlonjava/runtime/runtimetypes/RegexState", "restore", "()V", false);
        }

        // Flush mortal list for void non-subroutine blocks. Value-producing blocks
        // must NOT flush here because the implicit return value may still be on the
        // JVM stack or in the parent simple-block result register; flushing could
        // destroy it before the caller captures it. Example:
        //   $self->{cursor} ||= do { my $x = ...; create_obj() }
        // The do-block's scope exit would flush pending decrements from create_obj's
        // scope exit, destroying the return value before ||= can store it.
        //
        // EXCEPTION: do-blocks whose last expression is a "fresh-result"
        // operator (`!`, `not`, comparison ops, `defined`, etc.) produce
        // a value that is guaranteed independent of any inner my-var or
        // container. For those we DO flush, fixing op/do.t RT 124248
        // (DESTROY firing on do-block exit for transient my-vars) without
        // risking DBIC's `do { my $x = ...; $x }` patterns.
        boolean isSubBody = node.getBooleanAnnotation("blockIsSubroutine");
        boolean isDoBlock = node.getBooleanAnnotation("blockIsDoBlock");
        boolean doBlockFreshResult = isDoBlock && DoBlockResultAnalysis.isAlwaysFresh(node);
        Object blockResultRegObj = node.getAnnotation("resultRegister");
        boolean hasBlockResultRegister = blockResultRegObj instanceof Integer && (Integer) blockResultRegObj >= 0;
        boolean blockMayNeedResultAfterScopeExit = emitterVisitor.ctx.contextType != RuntimeContextType.VOID
                || hasBlockResultRegister;
        boolean flushAtScopeExit = !isSubBody
                && (isDoBlock ? doBlockFreshResult : !blockMayNeedResultAfterScopeExit);
        int returnedLvalueSlot = -1;
        if (isSubBody && (emitterVisitor.ctx.contextType != RuntimeContextType.VOID
                || emitterVisitor.ctx.javaClassInfo.isLvalueSubroutine)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/RuntimeCode",
                    "materializeReturnedIoAliases",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;",
                    false);
            returnedLvalueSlot = emitterVisitor.ctx.symbolTable.allocateLocalVariable();
            mv.visitVarInsn(Opcodes.ASTORE, returnedLvalueSlot);
        }
        boolean protectReturnedWeakOwner = returnedLvalueSlot >= 0
                && emitterVisitor.ctx.javaClassInfo.cleanupNeeded;
        if (protectReturnedWeakOwner) {
            // A weakly observed local may also be the implicit sub return.
            // Keep that return value as an explicit root while lexical cleanup
            // runs so noteVarLeftScope can avoid a global reachability sweep
            // during the caller's callback dispatch.
            mv.visitVarInsn(Opcodes.ALOAD, returnedLvalueSlot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/runtimetypes/MortalList",
                    "pushTemporaryRoot",
                    "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)V",
                    false);
        }
        EmitStatement.emitScopeExitNullStores(emitterVisitor.ctx, scopeIndex, flushAtScopeExit, returnedLvalueSlot);
        if (returnedLvalueSlot >= 0) {
            if (protectReturnedWeakOwner) {
                mv.visitVarInsn(Opcodes.ALOAD, returnedLvalueSlot);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/runtimetypes/MortalList",
                        "popTemporaryRoot",
                        "(Lorg/perlonjava/runtime/runtimetypes/RuntimeBase;)V",
                        false);
            }
            mv.visitVarInsn(Opcodes.ALOAD, returnedLvalueSlot);
        }
        emitterVisitor.ctx.symbolTable.exitScope(scopeIndex);
        emitPostBlockStrictOptions(mv, node);
        if (CompilerOptions.DEBUG_ENABLED) emitterVisitor.ctx.logDebug("generateCodeBlock end");
    }

    private static void emitPostBlockStrictOptions(MethodVisitor mv, AbstractNode node) {
        Object strictOptionsObj = node.getAnnotation("postBlockStrictOptions");
        if (strictOptionsObj instanceof Integer strictOptions) {
            if (!node.getBooleanAnnotation("blockIsSubroutine")) {
                Object warningBitsObj = node.getAnnotation("postBlockWarningBits");
                String warningBits = warningBitsObj instanceof String bits ? bits : "";
                mv.visitLdcInsn(warningBits);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/WarningBitsRegistry",
                        "setCallSiteBits",
                        "(Ljava/lang/String;)V", false);
                mv.visitLdcInsn(warningBits);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/perlonjava/runtime/WarningBitsRegistry",
                        "setRuntimeWarningBits",
                        "(Ljava/lang/String;)V", false);
            }
            mv.visitLdcInsn(strictOptions);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "org/perlonjava/runtime/WarningBitsRegistry",
                    "setCallSiteHints",
                    "(I)V", false);
        }
    }

}
