package org.perlonjava.frontend.astnode;

import java.util.List;

/**
 * Represents a picture line in a format template.
 * Picture lines contain field definitions (@ and ^ fields) mixed with literal text.
 * Example: "@<<<<<< @|||||| @>>>>>>"
 */
public class PictureLine extends FormatLine {
    /**
     * List of format fields found in this picture line
     */
    public final List<FormatField> fields;

    /**
     * The literal text portions between fields
     */
    public final String literalText;

    /**
     * Constructor for PictureLine.
     *
     * @param content     The raw text content of the line
     * @param fields      List of format fields in this line
     * @param literalText The literal text with field placeholders
     * @param tokenIndex  The token index in the source code
     */
    public PictureLine(String content, List<FormatField> fields, String literalText, int tokenIndex) {
        super(content, tokenIndex);
        this.fields = fields;
        this.literalText = literalText;
    }

    @Override
    public String toString() {
        return "PictureLine{" +
                "fields=" + fields.size() +
                ", content='" + content + '\'' +
                '}';
    }
}
