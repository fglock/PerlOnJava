package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.AbstractNode;
import org.perlonjava.frontend.astnode.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the shared AST transformer passes.
 * Runs passes in sequence to compute semantic annotations that both
 * the JVM backend and bytecode interpreter can use.
 *
 * <p>The transformer is idempotent: if the AST has already been transformed,
 * subsequent calls to {@link #transform(Node)} will skip all passes.
 * This is important because the same AST may be processed multiple times
 * when the JVM backend falls back to the interpreter.</p>
 *
 * <p>Pass execution order (as defined in the design document):
 * <ol>
 *   <li>PragmaResolver - Track strict, warnings, features across scopes</li>
 *   <li>VariableResolver - Link variable uses to declarations, detect closures</li>
 *   <li>LabelCollector - Collect labels and link control flow</li>
 *   <li>BlockAnalyzer - Detect local declarations and regex usage</li>
 *   <li>ContextResolver - Propagate scalar/list/void context</li>
 *   <li>LvalueResolver - Mark nodes that must return lvalues</li>
 *   <li>ConstantFolder - Fold compile-time constants</li>
 *   <li>WarningEmitter - Emit compile-time warnings</li>
 * </ol>
 * </p>
 *
 * <p>Example usage:
 * <pre>
 * // Create transformer with desired passes
 * ASTTransformer transformer = new ASTTransformer();
 * transformer.addPass(new ContextResolver());
 * transformer.addPass(new LvalueResolver());
 *
 * // Transform AST (idempotent - safe to call multiple times)
 * transformer.transform(ast);
 *
 * // Later, if JVM backend falls back to interpreter:
 * transformer.transform(ast);  // Skips - already transformed
 * </pre>
 * </p>
 */
public class ASTTransformer {

    private final List<ASTTransformPass> passes = new ArrayList<>();

    private static final boolean DEBUG = System.getenv("JPERL_TRANSFORMER_DEBUG") != null;

    /**
     * Adds a transformation pass to the pipeline.
     * Passes are executed in the order they are added.
     *
     * @param pass The pass to add
     * @return this transformer for method chaining
     */
    public ASTTransformer addPass(ASTTransformPass pass) {
        passes.add(pass);
        return this;
    }

    /**
     * Transforms the AST by running all registered passes.
     *
     * <p>This method is idempotent: if the AST has already been transformed
     * (as indicated by the FLAG_AST_TRANSFORMED flag on the root node),
     * all passes are skipped.</p>
     *
     * @param root The root node of the AST to transform
     * @return true if transformation was performed, false if skipped (already transformed)
     */
    public boolean transform(Node root) {
        if (root == null) {
            return false;
        }

        AbstractNode abstractRoot = root instanceof AbstractNode ? (AbstractNode) root : null;

        // Check idempotency: skip if already transformed
        if (abstractRoot != null && abstractRoot.isAstTransformed()) {
            if (DEBUG) {
                System.err.println("[ASTTransformer] Skipping - AST already transformed");
            }
            return false;
        }

        if (DEBUG) {
            System.err.println("[ASTTransformer] Running " + passes.size() + " passes");
        }

        // Run all passes in order
        for (ASTTransformPass pass : passes) {
            if (DEBUG) {
                System.err.println("[ASTTransformer] Running pass: " + pass.getClass().getSimpleName());
            }
            pass.transform(root);
        }

        // Mark AST as transformed
        if (abstractRoot != null) {
            abstractRoot.setAstTransformed();
        }

        if (DEBUG) {
            System.err.println("[ASTTransformer] Transformation complete");
        }

        return true;
    }

    /**
     * Returns the number of registered passes.
     */
    public int getPassCount() {
        return passes.size();
    }

    /**
     * Clears all registered passes.
     */
    public void clearPasses() {
        passes.clear();
    }

    /**
     * Creates a default transformer with the standard pass pipeline.
     * Currently returns an empty transformer since passes are not yet implemented.
     *
     * <p>Once passes are implemented, this will return a transformer with:
     * <ul>
     *   <li>PragmaResolver</li>
     *   <li>VariableResolver</li>
     *   <li>LabelCollector</li>
     *   <li>BlockAnalyzer</li>
     *   <li>ContextResolver</li>
     *   <li>LvalueResolver</li>
     *   <li>ConstantFolder</li>
     *   <li>WarningEmitter</li>
     * </ul>
     * </p>
     *
     * @return A new transformer configured with the default pass pipeline
     */
    public static ASTTransformer createDefault() {
        ASTTransformer transformer = new ASTTransformer();
        // TODO: Add passes as they are implemented
        // transformer.addPass(new PragmaResolver());
        // transformer.addPass(new VariableResolver());
        // transformer.addPass(new LabelCollector());
        // transformer.addPass(new BlockAnalyzer());
        // transformer.addPass(new ContextResolver());
        // transformer.addPass(new LvalueResolver());
        // transformer.addPass(new ConstantFolderPass());
        // transformer.addPass(new WarningEmitter());
        return transformer;
    }
}
