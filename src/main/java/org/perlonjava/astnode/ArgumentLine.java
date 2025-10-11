package org.perlonjava.astnode;

import java.util.List;

/**
 * Represents an argument line in a format template.
 * Argument lines contain expressions that provide values for the fields
 * defined in the preceding picture line.
 * Example: "$left, $center, $right"
 */
public class ArgumentLine extends FormatLine {
    /**
     * List of expression nodes that provide values for format fields
     */
    public final List<Node> expressions;

    /**
     * Constructor for ArgumentLine.
     *
     * @param content     The raw text content of the line
     * @param expressions List of expression nodes for field values
     * @param tokenIndex  The token index in the source code
     */
    public ArgumentLine(String content, List<Node> expressions, int tokenIndex) {
        super(content, tokenIndex);
        this.expressions = expressions;
    }

    @Override
    public String toString() {
        return "ArgumentLine{" +
                "expressions=" + expressions.size() +
                ", content='" + content + '\'' +
                '}';
    }
}
