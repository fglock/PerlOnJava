package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;

import java.util.List;

public class TestMoreHelper {

    // Use a macro to emulate Test::More SKIP blocks
    static void handleSkipTest(Parser parser, BlockNode block) {
        // Locate and rewrite skip() calls inside SKIP: { ... } blocks.
        // This must be robust because in perl5 tests skip() is often nested under
        // boolean operators/modifiers (e.g. `eval {...} or skip "...", 2;`).
        for (Node node : block.elements) {
            handleSkipTestNode(parser, node);
        }
    }

    private static void handleSkipTestNode(Parser parser, Node node) {
        if (node == null) {
            return;
        }

        if (node instanceof BinaryOperatorNode binop) {
            // Recurse first so we don't miss nested skip calls.
            handleSkipTestNode(parser, binop.left);
            handleSkipTestNode(parser, binop.right);

            // Also try to rewrite this node itself if it's a call.
            handleSkipTestInner(parser, binop);
            return;
        }

        if (node instanceof OperatorNode op) {
            handleSkipTestNode(parser, op.operand);
            return;
        }

        if (node instanceof ListNode list) {
            for (Node elem : list.elements) {
                handleSkipTestNode(parser, elem);
            }
            return;
        }

        if (node instanceof BlockNode block) {
            for (Node elem : block.elements) {
                handleSkipTestNode(parser, elem);
            }
            return;
        }

        if (node instanceof For3Node for3) {
            handleSkipTestNode(parser, for3.initialization);
            handleSkipTestNode(parser, for3.condition);
            handleSkipTestNode(parser, for3.increment);
            handleSkipTestNode(parser, for3.body);
            handleSkipTestNode(parser, for3.continueBlock);
            return;
        }

        if (node instanceof For1Node for1) {
            handleSkipTestNode(parser, for1.variable);
            handleSkipTestNode(parser, for1.list);
            handleSkipTestNode(parser, for1.body);
            return;
        }

        if (node instanceof IfNode ifNode) {
            handleSkipTestNode(parser, ifNode.condition);
            handleSkipTestNode(parser, ifNode.thenBranch);
            handleSkipTestNode(parser, ifNode.elseBranch);
            return;
        }

        if (node instanceof TryNode tryNode) {
            handleSkipTestNode(parser, tryNode.tryBlock);
            handleSkipTestNode(parser, tryNode.catchBlock);
            handleSkipTestNode(parser, tryNode.finallyBlock);
        }
    }

    private static void handleSkipTestInner(Parser parser, BinaryOperatorNode op) {
        if (op.operator.equals("(")) {
            int index = op.tokenIndex;
            IdentifierNode subName = null;
            if (op.left instanceof OperatorNode sub
                    && sub.operator.equals("&")
                    && sub.operand instanceof IdentifierNode subId
                    && subId.name.equals("skip")) {
                subName = subId;
            } else if (op.left instanceof IdentifierNode subId && subId.name.equals("skip")) {
                subName = subId;
            }

            if (subName != null) {
                // skip() call
                // op.right contains the arguments

                // Becomes:  `skip_internal(...) && last SKIP` if available, otherwise `skip(...) && last SKIP`.
                // This is critical for perl5 tests that rely on Test::More-style SKIP blocks.
                // We cannot rely on non-local `last SKIP` propagation through subroutine returns,
                // so we force the `last SKIP` to execute in the caller's scope.
                String fullName = NameNormalizer.normalizeVariableName(subName.name + "_internal", parser.ctx.symbolTable.getCurrentPackage());
                if (GlobalVariable.existsGlobalCodeRef(fullName)) {
                    subName.name = fullName;
                }

                op.operator = "&&";
                op.left = new BinaryOperatorNode("(", op.left, op.right, index);
                op.right = new OperatorNode("last",
                        new ListNode(List.of(new IdentifierNode("SKIP", index)), index), index);
            }
        }
    }
}
