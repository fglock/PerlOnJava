package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.astrefactor.LargeBlockRefactorer;
import org.perlonjava.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * The BlockNode class represents a node in the abstract syntax tree (AST) that holds
 * a list of other nodes. This class implements the Node interface, allowing it to be
 * visited by a Visitor.
 */
public class BlockNode extends AbstractNode {
    /**
     * The list of child nodes contained in this BlockNode.
     * Note: This field is non-final because {@link LargeBlockRefactorer} may modify
     * the list during parse-time refactoring.
     */
    public List<Node> elements;

    /**
     * This flag indicates if this BlockNode represents a loop.
     */
    public boolean isLoop;
    /**
     * the label name for this loop block, as in `L1: {...}`
     */
    public String labelName;

    /**
     * The list of labels inside this block, as in `{ L1: ..., L2:... }`
     */
    public List<String> labels;

    /**
     * Constructs a new BlockNode with the specified list of child nodes.
     * <p>
     * <b>Large Block Refactoring:</b> Currently disabled by default.
     * Large code is handled automatically via on-demand refactoring when compilation errors occur.
     *
     * @param elements   the list of child nodes to be stored in this BlockNode
     * @param tokenIndex the index of the token in the source code
     */
    public BlockNode(List<Node> elements, int tokenIndex) {
        this(elements, tokenIndex, null);
    }

    /**
     * Constructs a new BlockNode with the specified list of child nodes and parser context.
     * <p>
     * This constructor provides better error messages with source code context when refactoring fails.
     *
     * @param elements   the list of child nodes to be stored in this BlockNode
     * @param tokenIndex the index of the token in the source code
     * @param parser     the parser instance for access to error utilities
     */
    public BlockNode(List<Node> elements, int tokenIndex, Parser parser) {
        this.elements = elements;
        this.tokenIndex = tokenIndex;
        this.labels = new ArrayList<>();
        this.labelName = null;
        this.isLoop = false;
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

