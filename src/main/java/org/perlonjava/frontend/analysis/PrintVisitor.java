package org.perlonjava.frontend.analysis;

import org.perlonjava.astnode.*;
import org.perlonjava.perlmodule.Strict;

import static org.perlonjava.runtime.runtimetypes.ScalarUtils.printable;
import static org.perlonjava.symbols.ScopedSymbolTable.stringifyFeatureFlags;
import static org.perlonjava.symbols.ScopedSymbolTable.stringifyWarningFlags;

/*
 *
 * Usage:
 *
 *   PrintVisitor printVisitor = new PrintVisitor();
 *   node.accept(printVisitor);
 *   return printVisitor.getResult();
 */
public class PrintVisitor implements Visitor {

    private final StringBuilder sb = new StringBuilder();
    private int indentLevel = 0;

    @Override
    public void visit(FormatLine node) {
        // Default implementation - no action needed for format lines
    }

    @Override
    public void visit(FormatNode node) {
        // Default implementation - no action needed for format nodes
    }

    private void appendIndent() {
        sb.append("  ".repeat(Math.max(0, indentLevel)));
    }

    public String getResult() {
        return sb.toString();
    }

    @Override
    public void visit(NumberNode node) {
        appendIndent();
        sb.append("NumberNode: ").append(node.value).append("\n");
        printAnnotations(node);
    }

    @Override
    public void visit(IdentifierNode node) {
        appendIndent();
        sb.append("IdentifierNode: ").append(printable(node.name)).append("\n");
        printAnnotations(node);
    }

    @Override
    public void visit(BinaryOperatorNode node) {
        appendIndent();
        sb.append("BinaryOperatorNode: ").append(node.operator).append("  pos:").append(node.tokenIndex).append("\n");
        printAnnotations(node);
        indentLevel++;
        if (node.left == null) {
            appendIndent();
            sb.append("null\n");
        } else {
            node.left.accept(this);
        }
        if (node.right == null) {
            appendIndent();
            sb.append("null\n");
        } else {
            node.right.accept(this);
        }
        indentLevel--;
    }

    @Override
    public void visit(OperatorNode node) {
        appendIndent();
        sb.append("OperatorNode: ").append(node.operator)
                .append("  pos:").append(node.tokenIndex).append("\n");
        printAnnotations(node);
        indentLevel++;

        if (node.id != 0) {
            appendIndent();
            sb.append("id: ").append(node.id).append("\n");
        }

        if (node.operand != null) {
            node.operand.accept(this);
        }
        indentLevel--;
    }

    @Override
    public void visit(For1Node node) {
        appendIndent();
        sb.append("For1Node:\n");
        printAnnotations(node);
        indentLevel++;

        if (node.labelName != null) {
            appendIndent();
            sb.append("label: ").append(node.labelName).append("\n");
        }

        appendIndent();
        sb.append(node.useNewScope ? "useNewScope\n" : "no useNewScope\n");

        // Visit the variable part
        if (node.variable != null) {
            appendIndent();
            sb.append("Variable:\n");
            indentLevel++;
            node.variable.accept(this);
            indentLevel--;
        }

        // Visit the list part
        if (node.list != null) {
            appendIndent();
            sb.append("List:\n");
            indentLevel++;
            node.list.accept(this);
            indentLevel--;
        }

        // Visit the body of the loop
        if (node.body != null) {
            appendIndent();
            sb.append("Body:\n");
            indentLevel++;
            node.body.accept(this);
            indentLevel--;
        }

        // Visit the continueBlock
        if (node.continueBlock != null) {
            appendIndent();
            sb.append("Continue:\n");
            indentLevel++;
            node.continueBlock.accept(this);
            indentLevel--;
        }

        indentLevel--;
    }

    @Override
    public void visit(For3Node node) {
        appendIndent();
        sb.append("For3Node:\n");
        printAnnotations(node);
        indentLevel++;

        if (node.labelName != null) {
            appendIndent();
            sb.append("label: ").append(node.labelName).append("\n");
        }

        appendIndent();
        sb.append(node.useNewScope ? "useNewScope\n" : "no useNewScope\n");

        // Visit the initialization part
        if (node.initialization != null) {
            appendIndent();
            sb.append("Initialization:\n");
            indentLevel++;
            node.initialization.accept(this);
            indentLevel--;
        }

        // Visit the condition part
        if (node.condition != null) {
            appendIndent();
            sb.append("Condition:\n");
            indentLevel++;
            node.condition.accept(this);
            indentLevel--;
        }

        // Visit the increment part
        if (node.increment != null) {
            appendIndent();
            sb.append("Increment:\n");
            indentLevel++;
            node.increment.accept(this);
            indentLevel--;
        }

        // Visit the body of the loop
        if (node.body != null) {
            appendIndent();
            sb.append("Body:\n");
            indentLevel++;
            node.body.accept(this);
            indentLevel--;
        }

        // Visit the continueBlock
        if (node.continueBlock != null) {
            appendIndent();
            sb.append("Continue:\n");
            indentLevel++;
            node.continueBlock.accept(this);
            indentLevel--;
        }

        indentLevel--;
    }

    @Override
    public void visit(IfNode node) {
        appendIndent();
        sb.append("IfNode: ").append(node.operator).append("\n");
        printAnnotations(node);
        indentLevel++;
        node.condition.accept(this);
        node.thenBranch.accept(this);
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
        indentLevel--;
    }

    @Override
    public void visit(SubroutineNode node) {
        appendIndent();
        sb.append("SubroutineNode:  pos:").append(node.tokenIndex).append("\n");
        printAnnotations(node);
        indentLevel++;

        appendIndent();
        sb.append("name: ").append(node.name == null ? "<anon>\n" : node.name + "\n");

        if (node.prototype != null) {
            appendIndent();
            sb.append("prototype: ").append(node.prototype).append("\n");
        }

        if (node.attributes != null) {
            // List<String> attributes
            appendIndent();
            sb.append("attributes:\n");
            indentLevel++;
            for (String element : node.attributes) {
                appendIndent();
                sb.append(element).append("\n");
            }
            indentLevel--;
        }

        appendIndent();
        sb.append(node.useTryCatch ? "useTryCatch\n" : "no useTryCatch\n");
        node.block.accept(this);
        indentLevel--;
    }

    @Override
    public void visit(TernaryOperatorNode node) {
        appendIndent();
        sb.append("TernaryOperatorNode: ").append(node.operator).append("\n");
        printAnnotations(node);
        indentLevel++;
        node.condition.accept(this);
        node.trueExpr.accept(this);
        node.falseExpr.accept(this);
        indentLevel--;
    }

    @Override
    public void visit(StringNode node) {
        appendIndent();
        sb.append("StringNode: ").append(printable(node.value)).append("\n");
        printAnnotations(node);
        indentLevel++;
        if (node.isVString) {
            appendIndent();
            sb.append("isVString=true\n");
        }
        indentLevel--;
    }

    @Override
    public void visit(BlockNode node) {
        appendIndent();
        sb.append("BlockNode:\n");
        printAnnotations(node);
        indentLevel++;
        if (node.isLoop) {
            appendIndent();
            sb.append("isLoop").append("\n");

            if (node.labelName != null) {
                appendIndent();
                sb.append("label: ").append(node.labelName).append("\n");
            }
        }

        // Add labels printing
        if (!node.labels.isEmpty()) {
            appendIndent();
            sb.append("labels: ").append(String.join(", ", node.labels)).append("\n");
        }

        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
        indentLevel--;
    }

    @Override
    public void visit(ListNode node) {
        appendIndent();
        sb.append("ListNode:\n");
        printAnnotations(node);
        indentLevel++;
        if (node.handle != null) {
            appendIndent();
            sb.append("Handle:\n");
            indentLevel++;
            node.handle.accept(this);
            indentLevel--;
        }
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
        indentLevel--;
    }

    @Override
    public void visit(ArrayLiteralNode node) {
        appendIndent();
        sb.append("ArrayLiteralNode:\n");
        printAnnotations(node);
        indentLevel++;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
        indentLevel--;
    }

    @Override
    public void visit(HashLiteralNode node) {
        appendIndent();
        sb.append("HashLiteralNode:\n");
        printAnnotations(node);
        indentLevel++;
        for (Node element : node.elements) {
            if (element != null) {
                element.accept(this);
            }
        }
        indentLevel--;
    }

    @Override
    public void visit(TryNode node) {
        appendIndent();
        sb.append("TryNode:\n");
        printAnnotations(node);
        indentLevel++;

        // Visit the try block
        appendIndent();
        sb.append("TryBlock:\n");
        indentLevel++;
        if (node.tryBlock != null) {
            node.tryBlock.accept(this);
        }
        indentLevel--;

        // Visit the catch parameter
        if (node.catchParameter != null) {
            appendIndent();
            sb.append("CatchParameter:\n");
            indentLevel++;
            node.catchParameter.accept(this);
            indentLevel--;
        }

        // Visit the catch block
        appendIndent();
        sb.append("CatchBlock:\n");
        indentLevel++;
        if (node.catchBlock != null) {
            node.catchBlock.accept(this);
        }
        indentLevel--;

        // Visit the finally block, if it exists
        if (node.finallyBlock != null) {
            appendIndent();
            sb.append("FinallyBlock:\n");
            indentLevel++;
            node.finallyBlock.accept(this);
            indentLevel--;
        }

        indentLevel--;
    }

    @Override
    public void visit(LabelNode node) {
        appendIndent();
        sb.append("LabelNode: ").append(node.label).append(":\n");
        printAnnotations(node);
    }

    @Override
    public void visit(CompilerFlagNode node) {
        appendIndent();
        sb.append("CompilerFlagNode:\n");
        printAnnotations(node);
        indentLevel++;

        appendIndent();
        sb.append("Warning Flags: ").append(stringifyWarningFlags(node.getWarningFlags())).append("\n");

        appendIndent();
        sb.append("Feature Flags: ").append(stringifyFeatureFlags(node.getFeatureFlags())).append("\n");

        appendIndent();
        sb.append("Strict Options: ").append(Strict.stringifyStrictOptions(node.getStrictOptions())).append("\n");

        indentLevel--;
    }

    private void printAnnotations(AbstractNode node) {
        // Print annotations if present
        if (node.annotations != null && !node.annotations.isEmpty()) {
            indentLevel++;
            appendIndent();
            sb.append("annotations:\n");
            indentLevel++;
            node.annotations.forEach((key, value) -> {
                appendIndent();
                String v = printable(value.toString());
                sb.append(key).append(": ").append(v).append("\n");
            });
            indentLevel--;
            indentLevel--;
        }
    }
}

