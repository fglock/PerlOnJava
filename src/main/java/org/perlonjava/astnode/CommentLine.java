package org.perlonjava.astnode;

/**
 * Represents a comment line in a format template.
 * Comment lines start with '#' in the first column and are ignored during format execution.
 * Example: "# This is a comment in the format"
 */
public class CommentLine extends FormatLine {
    /**
     * The comment text (without the leading #)
     */
    public final String comment;

    /**
     * Constructor for CommentLine.
     *
     * @param content The raw text content of the line
     * @param comment The comment text without the leading #
     * @param tokenIndex The token index in the source code
     */
    public CommentLine(String content, String comment, int tokenIndex) {
        super(content, tokenIndex);
        this.comment = comment;
    }

    @Override
    public String toString() {
        return "CommentLine{" +
                "comment='" + comment + '\'' +
                '}';
    }
}
