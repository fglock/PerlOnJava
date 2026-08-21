package org.perlonjava.frontend.analysis;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.BlockNode;
import org.perlonjava.frontend.astnode.CompilerFlagNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;

import java.util.Set;

/** Backend-neutral AST analysis for safe do-block scope cleanup. */
public final class DoBlockResultAnalysis {
    private static final Set<String> ALWAYS_FRESH_UNARY = Set.of(
            "!", "not", "defined", "exists", "ref", "length", "scalar", "wantarray");
    private static final Set<String> ALWAYS_FRESH_BINARY = Set.of(
            "==", "!=", "<", ">", "<=", ">=", "<=>",
            "eq", "ne", "lt", "gt", "le", "ge", "cmp", "isa");

    private DoBlockResultAnalysis() {
    }

    /**
     * Returns whether the final expression always produces a fresh scalar that
     * is independent of inner lexical/container identity.
     */
    public static boolean isAlwaysFresh(BlockNode block) {
        if (block == null || block.elements == null || block.elements.isEmpty()) {
            return false;
        }
        Node last = null;
        for (int i = block.elements.size() - 1; i >= 0; i--) {
            Node element = block.elements.get(i);
            if (element != null && !(element instanceof CompilerFlagNode)) {
                last = element;
                break;
            }
        }
        if (last instanceof NumberNode || last instanceof StringNode) {
            return true;
        }
        if (last instanceof OperatorNode operator) {
            return ALWAYS_FRESH_UNARY.contains(operator.operator);
        }
        if (last instanceof BinaryOperatorNode operator) {
            return ALWAYS_FRESH_BINARY.contains(operator.operator);
        }
        return false;
    }
}
