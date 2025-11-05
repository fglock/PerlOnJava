package org.perlonjava.astnode;

import org.perlonjava.astvisitor.LValueVisitor;
import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.runtime.RuntimeContextType;

/**
 * The For3Node class represents a node in the abstract syntax tree (AST) that holds a "for" loop statement.
 * The parts of the statement are: "initialization", "condition", "increment", and "body".
 * This class implements the Node interface, allowing it to be visited by a Visitor.
 */
public class For3Node extends AbstractNode {

    /**
     * isDoWhile indicates if this is a do-while loop, which means that the condition is checked after the body.
     */
    public final boolean isDoWhile;

    /**
     * isSimpleBlock indicates if this is a simple block, which means that the conditions are not checked.
     */
    public final boolean isSimpleBlock;

    /**
     * This loop creates a new variable scope
     */
    public final boolean useNewScope;
    /**
     * The initialization part of the for loop.
     */
    public final Node initialization;
    /**
     * The condition part of the for loop.
     */
    public final Node condition;
    /**
     * The increment part of the for loop.
     */
    public final Node increment;
    /**
     * The body of the for loop.
     */
    public final Node body;
    public final Node continueBlock;
    /**
     * the label name for this loop
     */
    public String labelName;

    /**
     * Constructs a new For3Node with the specified parts of the for loop.
     *
     * @param initialization the initialization part of the for loop
     * @param condition      the condition part of the for loop
     * @param increment      the increment part of the for loop
     * @param body           the body of the for loop
     * @param tokenIndex     the index of the token in the source code
     */
    public For3Node(String labelName, boolean useNewScope, Node initialization, Node condition, Node increment, Node body, Node continueBlock, boolean isDoWhile, boolean isSimpleBlock, int tokenIndex) {
        condition = whileConditionMagic(condition, tokenIndex);

        this.isDoWhile = isDoWhile;
        this.isSimpleBlock = isSimpleBlock;
        this.labelName = labelName;
        this.useNewScope = useNewScope;
        this.initialization = initialization;
        this.condition = condition;
        this.increment = increment;
        this.body = body;
        this.continueBlock = continueBlock;
        this.tokenIndex = tokenIndex;
    }

    private static boolean isMagicWhile(Node node) {
        String operator = "";
        if (node instanceof OperatorNode) {
            // "<>", "each", "glob"
            operator = ((OperatorNode) node).operator;
        } else if (node instanceof BinaryOperatorNode) {
            // "readline"
            operator = ((BinaryOperatorNode) node).operator;
        }
        return operator.equals("<>") ||
                operator.equals("each") ||
                operator.equals("glob") ||
                operator.equals("readline");
    }

    private Node whileConditionMagic(Node condition, int tokenIndex) {
        // "magic" `while ( <> )`
        if (condition != null) {
            String operator = "";
            if (condition instanceof BinaryOperatorNode operatorNode) {
                // test for `my $line = <STDIN>`
                operator = operatorNode.operator;
                if (operator.equals("=")) {
                    // is SCALAR context?
                    int lvalueContext = LValueVisitor.getContext(operatorNode.left);
                    if (lvalueContext == RuntimeContextType.SCALAR && isMagicWhile(operatorNode.right)) {
                        // need  `defined( ... )`
                        condition = new OperatorNode(
                                "defined",
                                operatorNode,
                                operatorNode.tokenIndex
                        );
                    }
                }
            }
            if (isMagicWhile(condition)) {
                // need  `defined( $_ = ...)`
                condition = new OperatorNode(
                        "defined",
                        new BinaryOperatorNode(
                                "=",
                                new OperatorNode(
                                        "$",
                                        new IdentifierNode("_", tokenIndex),
                                        tokenIndex
                                ),
                                condition,
                                tokenIndex
                        ),
                        tokenIndex
                );
            }
        }
        return condition;
    }

    /**
     * Accepts a visitor that performs some operation on this node.
     * This method is part of the Visitor design pattern, which allows
     * for defining new operations on the AST nodes without changing
     * the node classes.
     *
     * @param visitor the visitor that will perform the operation on this node
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }
}

