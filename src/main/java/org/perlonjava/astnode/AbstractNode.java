package org.perlonjava.astnode;

import org.perlonjava.astvisitor.PrintVisitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for AST nodes that includes an tokenIndex pointing
 * back to the token list. This tokenIndex is used for providing better
 * error messages by pointing to the exact location in the source code.
 * <p>
 * It also provides deep toString() formatting using PrintVisitor
 */
public abstract class AbstractNode implements Node {
    public int tokenIndex;

    // Lazy initialization - only created when first annotation is set
    public Map<String, Object> annotations;

    @Override
    public int getIndex() {
        return tokenIndex;
    }

    @Override
    public void setIndex(int tokenIndex) {
        this.tokenIndex = tokenIndex;
    }

    /**
     * Returns a string representation of the syntax tree.
     * The string representation includes the type and text of the syntax tree.
     *
     * @return a string representation of the syntax tree
     */
    @Override
    public String toString() {
        try {
            PrintVisitor printVisitor = new PrintVisitor();
            this.accept(printVisitor);
            return printVisitor.getResult();
        } catch (Exception e) {
            e.printStackTrace(); // Print any exceptions that occur during the process
            return e.toString();
        }
    }

    public void setAnnotation(String key, Object value) {
        if (annotations == null) {
            annotations = new HashMap<>();
        }
        annotations.put(key, value);
    }

    public Object getAnnotation(String key) {
        return annotations == null ? null : annotations.get(key);
    }

    public boolean getBooleanAnnotation(String key) {
        Object value = getAnnotation(key);
        return value instanceof Boolean && (Boolean) value;
    }
}
