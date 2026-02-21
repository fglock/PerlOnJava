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
    public int tokenIndex;

    // Lazy initialization - only created when first annotation is set
    public Map<String, Object> annotations;

    private int internalAnnotationFlags;
    private static final int FLAG_BLOCK_ALREADY_REFACTORED = 1;
    private static final int FLAG_QUEUED_FOR_REFACTOR = 2;
    private static final int FLAG_CHUNK_ALREADY_REFACTORED = 4;

    private int cachedBytecodeSize = Integer.MIN_VALUE;
    private byte cachedHasAnyControlFlow = -1;

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
        if (value instanceof Boolean boolVal && boolVal) {
            if ("blockAlreadyRefactored".equals(key)) {
                internalAnnotationFlags |= FLAG_BLOCK_ALREADY_REFACTORED;
                return;
            }
            if ("queuedForRefactor".equals(key)) {
                internalAnnotationFlags |= FLAG_QUEUED_FOR_REFACTOR;
                return;
            }
            if ("chunkAlreadyRefactored".equals(key)) {
                internalAnnotationFlags |= FLAG_CHUNK_ALREADY_REFACTORED;
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

    public Boolean getCachedHasAnyControlFlow() {
        return cachedHasAnyControlFlow < 0 ? null : cachedHasAnyControlFlow != 0;
    }

    public void setCachedHasAnyControlFlow(boolean hasAnyControlFlow) {
        this.cachedHasAnyControlFlow = (byte) (hasAnyControlFlow ? 1 : 0);
    }

    public Object getAnnotation(String key) {
        if ("blockAlreadyRefactored".equals(key)) {
            return (internalAnnotationFlags & FLAG_BLOCK_ALREADY_REFACTORED) != 0;
        }
        if ("queuedForRefactor".equals(key)) {
            return (internalAnnotationFlags & FLAG_QUEUED_FOR_REFACTOR) != 0;
        }
        if ("chunkAlreadyRefactored".equals(key)) {
            return (internalAnnotationFlags & FLAG_CHUNK_ALREADY_REFACTORED) != 0;
        }
        return annotations == null ? null : annotations.get(key);
    }

    public boolean getBooleanAnnotation(String key) {
        Object value = getAnnotation(key);
        return value instanceof Boolean && (Boolean) value;
    }
}
