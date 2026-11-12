package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.PrintVisitor;

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
    private static final int FLAG_BLOCK_ALREADY_REFACTORED = 1;
    // Round 24: useInteger encoded as 2 bits instead of a HashMap entry.
    // Avoids a HashMap allocation + HashMap.put in setAnnotation and a
    // HashMap.get in every getAnnotation("useInteger") call — which fires
    // for every arithmetic/bitwise operator node during eval compilation.
    private static final int FLAG_USE_INTEGER_CHECKED = 1 << 1;
    private static final int FLAG_USE_INTEGER_VALUE   = 1 << 2;
    public int tokenIndex;
    // Lazy initialization - only created when first annotation is set
    public Map<String, Object> annotations;
    private int internalAnnotationFlags;
    private int cachedBytecodeSize = Integer.MIN_VALUE;

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
        if (value instanceof Boolean boolVal) {
            if (key == "blockAlreadyRefactored") {
                if (boolVal) internalAnnotationFlags |= FLAG_BLOCK_ALREADY_REFACTORED;
                return;
            }
            if (key == "useInteger") {
                internalAnnotationFlags |= FLAG_USE_INTEGER_CHECKED;
                if (boolVal) internalAnnotationFlags |= FLAG_USE_INTEGER_VALUE;
                return;
            }
        }
        if (annotations == null) {
            annotations = new HashMap<>();
        }
        annotations.put(key, value);
    }

    public Integer getCachedBytecodeSize() {
        return cachedBytecodeSize == Integer.MIN_VALUE ? null : cachedBytecodeSize;
    }

    public void setCachedBytecodeSize(int size) {
        this.cachedBytecodeSize = size;
    }

    public Object getAnnotation(String key) {
        if (key == "blockAlreadyRefactored") {
            return (internalAnnotationFlags & FLAG_BLOCK_ALREADY_REFACTORED) != 0;
        }
        if (key == "useInteger") {
            if ((internalAnnotationFlags & FLAG_USE_INTEGER_CHECKED) == 0) return null;
            return (internalAnnotationFlags & FLAG_USE_INTEGER_VALUE) != 0;
        }
        return annotations == null ? null : annotations.get(key);
    }

    public boolean getBooleanAnnotation(String key) {
        Object value = getAnnotation(key);
        return value instanceof Boolean && (Boolean) value;
    }
}
