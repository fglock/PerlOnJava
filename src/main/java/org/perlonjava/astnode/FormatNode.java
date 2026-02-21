package org.perlonjava.astnode;

import org.perlonjava.frontend.analysis.Visitor;

import java.util.List;

/**
 * The FormatNode class represents a node in the abstract syntax tree (AST) that holds
 * a Perl format declaration. This includes the format name and the template lines
 * that define the output format structure.
 * <p>
 * Perl format syntax:
 * format NAME =
 * template lines...
 * .
 */
public class FormatNode extends AbstractNode {
    /**
     * The name of the format. If omitted in the source, defaults to "STDOUT".
     */
    public final String formatName;

    /**
     * The list of template lines that make up the format definition.
     * This includes picture lines, argument lines, and comment lines.
     */
    public final List<FormatLine> templateLines;

    /**
     * Constructor for FormatNode.
     *
     * @param formatName    The name of the format
     * @param templateLines The list of template lines defining the format
     * @param tokenIndex    The token index in the source code
     */
    public FormatNode(String formatName, List<FormatLine> templateLines, int tokenIndex) {
        this.formatName = formatName;
        this.templateLines = templateLines;
        this.tokenIndex = tokenIndex;
    }

    /**
     * Accept method for the visitor pattern.
     *
     * @param visitor The visitor to accept
     */
    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    /**
     * Returns a string representation of the FormatNode.
     *
     * @return A string representation including the format name and number of template lines
     */
    @Override
    public String toString() {
        return "FormatNode{" +
                "formatName='" + formatName + '\'' +
                ", templateLines=" + templateLines.size() + " lines" +
                '}';
    }
}
