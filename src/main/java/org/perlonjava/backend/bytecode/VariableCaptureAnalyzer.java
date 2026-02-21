package org.perlonjava.backend.bytecode;

import org.perlonjava.astnode.*;

import java.util.*;

/**
 * Analyzes which lexical variables in the main script are captured by named subroutines.
 *
 * <p>In interpreter mode, when named subroutines are compiled, they need access to
 * lexical variables from the outer scope. This analyzer identifies which variables
 * need to be stored in persistent global storage (using the BEGIN mechanism) so
 * both the interpreter and compiled code can access them.</p>
 *
 * <h2>Example</h2>
 * <pre>
 * my $width = 20;
 * sub neighbors {
 *     # Uses $width - needs persistent storage
 *     return $width * 2;
 * }
 * </pre>
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Scan main script AST for named subroutine definitions</li>
 *   <li>For each named sub, collect all variable references</li>
 *   <li>Filter to only include lexical variables from outer scope</li>
 *   <li>Return set of captured variable names</li>
 * </ol>
 *
 * @see BytecodeCompiler
 * @see PersistentVariable
 */
public class VariableCaptureAnalyzer {

    /**
     * Analyzes which variables in the main script are captured by named subroutines.
     *
     * @param mainScript The AST of the main script (typically a BlockNode)
     * @param outerScopeVars Set of variable names declared in the outer (main) scope
     * @return Set of variable names that need persistent storage
     */
    public static Set<String> analyze(Node mainScript, Set<String> outerScopeVars) {
        Set<String> capturedVars = new HashSet<>();

        // Find all named subroutine definitions
        List<SubroutineNode> namedSubs = findNamedSubroutines(mainScript);

        // For each named sub, find which outer variables it references
        for (SubroutineNode sub : namedSubs) {
            Set<String> referencedVars = findVariableReferences(sub.block);

            // Only include variables that are declared in outer scope
            for (String var : referencedVars) {
                if (outerScopeVars.contains(var)) {
                    capturedVars.add(var);
                }
            }
        }

        return capturedVars;
    }

    /**
     * Recursively finds all named subroutine definitions in the AST.
     */
    private static List<SubroutineNode> findNamedSubroutines(Node node) {
        List<SubroutineNode> subs = new ArrayList<>();

        if (node instanceof SubroutineNode) {
            SubroutineNode sub = (SubroutineNode) node;
            // Only include named subroutines (not anonymous closures)
            if (sub.name != null && !sub.name.isEmpty()) {
                subs.add(sub);
            }
        }

        // Recursively search children
        if (node instanceof BlockNode) {
            for (Node child : ((BlockNode) node).elements) {
                subs.addAll(findNamedSubroutines(child));
            }
        } else if (node instanceof OperatorNode) {
            OperatorNode op = (OperatorNode) node;
            if (op.operand != null) {
                subs.addAll(findNamedSubroutines(op.operand));
            }
        } else if (node instanceof For1Node) {
            For1Node forNode = (For1Node) node;
            if (forNode.body != null) {
                subs.addAll(findNamedSubroutines(forNode.body));
            }
        } else if (node instanceof For3Node) {
            For3Node forNode = (For3Node) node;
            if (forNode.body != null) {
                subs.addAll(findNamedSubroutines(forNode.body));
            }
        } else if (node instanceof BinaryOperatorNode) {
            BinaryOperatorNode bin = (BinaryOperatorNode) node;
            if (bin.left != null) subs.addAll(findNamedSubroutines(bin.left));
            if (bin.right != null) subs.addAll(findNamedSubroutines(bin.right));
        } else if (node instanceof TernaryOperatorNode) {
            TernaryOperatorNode tern = (TernaryOperatorNode) node;
            if (tern.condition != null) subs.addAll(findNamedSubroutines(tern.condition));
            if (tern.trueExpr != null) subs.addAll(findNamedSubroutines(tern.trueExpr));
            if (tern.falseExpr != null) subs.addAll(findNamedSubroutines(tern.falseExpr));
        }

        return subs;
    }

    /**
     * Recursively finds all variable references in a node and its children.
     * Returns variable names with their sigil (e.g., "$width", "@array", "%hash").
     */
    private static Set<String> findVariableReferences(Node node) {
        Set<String> vars = new HashSet<>();

        if (node == null) {
            return vars;
        }

        // Check if this node is a variable reference
        if (node instanceof IdentifierNode) {
            IdentifierNode id = (IdentifierNode) node;
            String name = id.name;
            // Only include lexical variables (not package variables with ::)
            if (!name.contains("::")) {
                vars.add(name);
            }
        }

        // Recursively search children
        if (node instanceof BlockNode) {
            for (Node child : ((BlockNode) node).elements) {
                vars.addAll(findVariableReferences(child));
            }
        } else if (node instanceof OperatorNode) {
            OperatorNode op = (OperatorNode) node;
            if (op.operand != null) {
                vars.addAll(findVariableReferences(op.operand));
            }
        } else if (node instanceof SubroutineNode) {
            // Don't recurse into nested subroutines - they have their own scope
            // We only care about variables in the immediate subroutine
            SubroutineNode sub = (SubroutineNode) node;
            if (sub.block != null) {
                vars.addAll(findVariableReferences(sub.block));
            }
        } else if (node instanceof For1Node) {
            For1Node forNode = (For1Node) node;
            if (forNode.variable != null) vars.addAll(findVariableReferences(forNode.variable));
            if (forNode.list != null) vars.addAll(findVariableReferences(forNode.list));
            if (forNode.body != null) vars.addAll(findVariableReferences(forNode.body));
        } else if (node instanceof For3Node) {
            For3Node forNode = (For3Node) node;
            if (forNode.initialization != null) vars.addAll(findVariableReferences(forNode.initialization));
            if (forNode.condition != null) vars.addAll(findVariableReferences(forNode.condition));
            if (forNode.increment != null) vars.addAll(findVariableReferences(forNode.increment));
            if (forNode.body != null) vars.addAll(findVariableReferences(forNode.body));
        } else if (node instanceof BinaryOperatorNode) {
            BinaryOperatorNode bin = (BinaryOperatorNode) node;
            if (bin.left != null) vars.addAll(findVariableReferences(bin.left));
            if (bin.right != null) vars.addAll(findVariableReferences(bin.right));
        } else if (node instanceof TernaryOperatorNode) {
            TernaryOperatorNode tern = (TernaryOperatorNode) node;
            if (tern.condition != null) vars.addAll(findVariableReferences(tern.condition));
            if (tern.trueExpr != null) vars.addAll(findVariableReferences(tern.trueExpr));
            if (tern.falseExpr != null) vars.addAll(findVariableReferences(tern.falseExpr));
        } else if (node instanceof ListNode) {
            ListNode list = (ListNode) node;
            for (Node element : list.elements) {
                if (element != null) {
                    vars.addAll(findVariableReferences(element));
                }
            }
        }

        return vars;
    }
}
