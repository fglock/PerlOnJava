package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;

import java.util.List;

public class TestMoreHelper {

    // Use a macro to emulate Test::More SKIP blocks
    static void handleSkipTest(Parser parser, BlockNode block) {
        // Locate skip statements
        // TODO create skip visitor
        for (Node node : block.elements) {
            if (node instanceof BinaryOperatorNode op) {
                if (!op.operator.equals("(")) {
                    // Possible if-modifier
                    if (op.left instanceof BinaryOperatorNode left) {
                        handleSkipTestInner(parser, left);
                    }
                    if (op.right instanceof BinaryOperatorNode right) {
                        handleSkipTestInner(parser, right);
                    }
                } else {
                    handleSkipTestInner(parser, op);
                }
            }
        }
    }

    private static void handleSkipTestInner(Parser parser, BinaryOperatorNode op) {
        if (op.operator.equals("(")) {
            int index = op.tokenIndex;
            if (op.left instanceof OperatorNode sub && sub.operator.equals("&") && sub.operand instanceof IdentifierNode subName && subName.name.equals("skip")) {
                // skip() call
                // op.right contains the arguments

                // Becomes:  `skip_internal() && last SKIP`
                // But first, test if the subroutine exists
                String fullName = NameNormalizer.normalizeVariableName(subName.name + "_internal", parser.ctx.symbolTable.getCurrentPackage());
                if (GlobalVariable.existsGlobalCodeRef(fullName)) {
                    subName.name = fullName;
                    op.operator = "&&";
                    op.left = new BinaryOperatorNode("(", op.left, op.right, index);
                    op.right = new OperatorNode("last",
                            new ListNode(List.of(new IdentifierNode("SKIP", index)), index), index);
                }
            }
        }
    }
}
