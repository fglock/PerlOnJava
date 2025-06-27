package org.perlonjava.parser;

import org.perlonjava.astnode.*;

public class ScalarContextHelper {
    /**
     * Transforms a node to scalar context only if necessary.
     * Avoids wrapping nodes that are already scalar or don't need scalar conversion.
     *
     * @param node The node to potentially transform
     * @return The node in scalar context (wrapped if needed, or original if already scalar)
     */
    static Node toScalarContext(Node node) {
        boolean isAlreadyScalar = switch (node) {
            case null -> true;  // return as-is
            case OperatorNode opNode -> switch (opNode.operator) {
                // Already in scalar context or scalar variables/references
                case "scalar", "$", "\\" -> true;

                // These operators ALWAYS return a single scalar value
                case "int", "abs", "length", "defined", "undef",
                     "ref", "fileno", "eof", "tell" -> true;

                // File test operators always return a single scalar
                case "-r", "-w", "-x", "-o", "-R", "-W", "-X", "-O",
                     "-e", "-z", "-s", "-f", "-d", "-l", "-p", "-S",
                     "-b", "-c", "-t", "-u", "-g", "-k", "-T", "-B",
                     "-M", "-A", "-C" -> true;

                // Note: We do NOT include operators that can return lists like:
                // - keys, values, each (can return list)
                // - split, grep, map, sort (return lists)
                // - readdir, glob (return lists)
                // - caller, times, stat, lstat (return lists in list context)
                // - localtime, gmtime (return lists in list context)

                default -> false;
            };
            case BinaryOperatorNode binaryOpNode -> switch (binaryOpNode.operator) {
                // Binary operators always produce a single scalar result
                case "**", "*", "/", "%", "+", "-", ".",
                     "==", "!=", "<", ">", "<=", ">=", "<=>",
                     "eq", "ne", "lt", "gt", "le", "ge", "cmp",
                     "&&", "||", "//", "and", "or", "xor",
                     "&", "|", "^", "<<", ">>" -> true;

                // Note: The range operator ".." can return a list in list context
                case ".." -> false;

                default -> false;
            };
            case NumberNode numberNode -> true;
            case StringNode stringNode -> true;
            default -> false;
        };

        return isAlreadyScalar ? node : new OperatorNode("scalar", node, node.getIndex());
    }

    static OperatorNode scalarUnderscore(Parser parser) {
        return new OperatorNode(
                "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
    }
}
