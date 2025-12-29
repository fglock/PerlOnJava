package org.perlonjava.astnode;

import org.perlonjava.astvisitor.Visitor;
import org.perlonjava.astrefactor.LargeNodeRefactorer;
import org.perlonjava.parser.Parser;

import java.util.List;

/**
 * Represents an array literal in the AST, corresponding to Perl's {@code [...]} syntax.
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code [1, 2, 3]} - simple array literal</li>
 *   <li>{@code [$a, @b, %c]} - array with mixed elements</li>
 *   <li>{@code [[1,2], [3,4]]} - nested array literals</li>
 * </ul>
 * <p>
 * <b>Large Literal Handling:</b> The constructor automatically invokes
 * {@link LargeNodeRefactorer#maybeRefactorElements} to split very large arrays
 * into chunks when {@code JPERL_LARGECODE=refactor} is set. This prevents
 * JVM "method too large" errors for arrays with thousands of elements.
 *
 * @see LargeNodeRefactorer
 * @see HashLiteralNode
 * @see ListNode
 */
public class ArrayLiteralNode extends AbstractNode {
    /**
     * The list of element nodes contained in this array literal.
     * <p>
     * Each element is evaluated in LIST context when the array is constructed.
     * Elements may be scalars, arrays (which flatten), hashes (which flatten to key-value pairs),
     * or any expression.
     * <p>
     * Note: This field is non-final because {@link LargeNodeRefactorer} may replace
     * the original list with a refactored version containing chunk wrappers.
     */
    public List<Node> elements;

    /**
     * Constructs a new ArrayLiteralNode with the specified list of child nodes.
     * <p>
     * <b>Large Literal Refactoring:</b> When {@code JPERL_LARGECODE=refactor} environment
     * variable is set and the elements list is large enough to potentially exceed JVM's
     * 64KB method size limit, the constructor automatically splits the elements into
     * chunks wrapped in anonymous subroutines.
     *
     * @param elements   the list of child nodes to be stored in this ArrayLiteralNode
     * @param tokenIndex the token index in the source for error reporting
     * @see LargeNodeRefactorer#maybeRefactorElements
     */
    public ArrayLiteralNode(List<Node> elements, int tokenIndex) {
        this(elements, tokenIndex, null);
    }

    /**
     * Constructs a new ArrayLiteralNode with the specified list of child nodes and parser context.
     * <p>
     * This constructor provides better error messages with source code context when refactoring fails.
     *
     * @param elements   the list of child nodes to be stored in this ArrayLiteralNode
     * @param tokenIndex the token index in the source for error reporting
     * @param parser     the parser instance for access to error utilities
     * @see LargeNodeRefactorer#maybeRefactorElements
     */
    public ArrayLiteralNode(List<Node> elements, int tokenIndex, Parser parser) {
        this.tokenIndex = tokenIndex;
        this.elements = LargeNodeRefactorer.maybeRefactorElements(elements, tokenIndex, LargeNodeRefactorer.NodeType.ARRAY, parser);
    }

    /**
     * Converts this array literal to a ListNode containing the same elements.
     * <p>
     * This is useful when the array elements need to be processed as a flat list
     * rather than as an array reference.
     *
     * @return a new ListNode containing this array's elements
     */
    public ListNode asListNode() {
        return new ListNode(elements, tokenIndex);
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

