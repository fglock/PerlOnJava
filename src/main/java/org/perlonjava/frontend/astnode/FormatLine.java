package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.Visitor;

/**
 * Base class for different types of format template lines.
 * Format templates consist of three types of lines:
 * 1. Comment lines (starting with #)
 * 2. Picture lines (containing field definitions like @, ^, <, >, |, #)
 * 3. Argument lines (containing expressions that supply values for fields)
 */
public abstract class FormatLine extends AbstractNode {
    /**
     * The raw text content of this format line
     */
    public final String content;

    /** Original source provenance, retained for runtime format diagnostics. */
    public String sourceFileName;
    public int sourceLine = -1;

    /**
     * Constructor for FormatLine.
     *
     * @param content    The raw text content of the line
     * @param tokenIndex The token index in the source code
     */
    public FormatLine(String content, int tokenIndex) {
        this.content = content;
        this.tokenIndex = tokenIndex;
    }

    public FormatLine setSourceLocation(String fileName, int line) {
        this.sourceFileName = fileName;
        this.sourceLine = line;
        return this;
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
}
