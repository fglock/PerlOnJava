package org.perlonjava.frontend.astnode;

import org.perlonjava.frontend.analysis.ASTAnnotation;
import org.perlonjava.frontend.analysis.PrintVisitor;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;

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
    private static final int FLAG_QUEUED_FOR_REFACTOR = 2;
    private static final int FLAG_CHUNK_ALREADY_REFACTORED = 4;
    private static final int FLAG_AST_TRANSFORMED = 8;  // Shared transformer has run on this AST
    public int tokenIndex;
    // Lazy initialization - only created when first annotation is set
    public Map<String, Object> annotations;
    private int internalAnnotationFlags;
    private int cachedBytecodeSize = Integer.MIN_VALUE;
    private byte cachedHasAnyControlFlow = -1;

    // Shared AST transformer cached fields (frequently accessed by both backends)
    // Using bytes for memory efficiency: -1 = unset, then specific values
    private byte cachedContext = -1;      // RuntimeContextType (VOID=0, SCALAR=1, LIST=2, RUNTIME=3)
    private byte cachedIsLvalue = -1;     // -1=unset, 0=false, 1=true

    // Full annotation object (lazy initialized for nodes that need detailed annotations)
    private ASTAnnotation astAnnotation;

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

    // ========== Shared AST Transformer Support ==========

    /**
     * Checks if the shared AST transformer has already processed this node's AST.
     * Used to prevent re-running transformer passes when JVM backend falls back to interpreter.
     */
    public boolean isAstTransformed() {
        return (internalAnnotationFlags & FLAG_AST_TRANSFORMED) != 0;
    }

    /**
     * Marks this node (and implicitly its AST) as having been processed by the transformer.
     */
    public void setAstTransformed() {
        internalAnnotationFlags |= FLAG_AST_TRANSFORMED;
    }

    /**
     * Gets the cached context type for this node.
     * @return RuntimeContextType value, or -1 if not yet computed
     */
    public int getCachedContext() {
        return cachedContext;
    }

    /**
     * Sets the cached context type for this node.
     * @param context RuntimeContextType value (VOID, SCALAR, LIST, or RUNTIME)
     */
    public void setCachedContext(int context) {
        this.cachedContext = (byte) context;
    }

    /**
     * Checks if cached context has been set.
     */
    public boolean hasCachedContext() {
        return cachedContext >= 0;
    }

    /**
     * Gets the cached lvalue status for this node.
     * @return true if lvalue, false if not, null if not yet computed
     */
    public Boolean getCachedIsLvalue() {
        return cachedIsLvalue < 0 ? null : cachedIsLvalue != 0;
    }

    /**
     * Sets the cached lvalue status for this node.
     */
    public void setCachedIsLvalue(boolean isLvalue) {
        this.cachedIsLvalue = (byte) (isLvalue ? 1 : 0);
    }

    /**
     * Gets the full ASTAnnotation object for this node.
     * Creates one if it doesn't exist (lazy initialization).
     */
    public ASTAnnotation getAstAnnotation() {
        if (astAnnotation == null) {
            astAnnotation = new ASTAnnotation();
        }
        return astAnnotation;
    }

    /**
     * Gets the ASTAnnotation without creating one if it doesn't exist.
     * @return the annotation or null if not set
     */
    public ASTAnnotation getAstAnnotationOrNull() {
        return astAnnotation;
    }

    /**
     * Sets the ASTAnnotation object for this node.
     */
    public void setAstAnnotation(ASTAnnotation annotation) {
        this.astAnnotation = annotation;
    }

    /**
     * Checks if this node has detailed annotations.
     */
    public boolean hasAstAnnotation() {
        return astAnnotation != null;
    }
}
