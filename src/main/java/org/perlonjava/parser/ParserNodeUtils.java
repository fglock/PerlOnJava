package org.perlonjava.parser;

import org.perlonjava.astnode.*;

/**
 * Utility class for common node transformations and operations used during parsing.
 * <p>
 * This class provides helper methods for working with AST nodes, particularly for
 * context conversions and creating commonly used node patterns.
 */
public class ParserNodeUtils {
    /**
     * Transforms a node to scalar context only if necessary.
     * <p>
     * This method implements an optimization by avoiding unnecessary wrapping of nodes
     * that are already in scalar context or inherently produce scalar values.
     * <p>
     * The following nodes are considered already scalar and returned as-is:
     * <ul>
     *   <li>null values</li>
     *   <li>Nodes already wrapped with scalar context operator</li>
     *   <li>Scalar variables ($var) and references (\$var)</li>
     *   <li>Operators that always return scalar values (int, abs, length, etc.)</li>
     *   <li>File test operators (-e, -f, -d, etc.)</li>
     *   <li>Binary operators that produce scalar results (+, -, ==, etc.)</li>
     *   <li>Number and string literal nodes</li>
     * </ul>
     * <p>
     * Nodes that can return lists in list context (like keys, values, split, etc.)
     * are wrapped with a scalar context operator.
     *
     * @param node The node to potentially transform to scalar context
     * @return The node in scalar context - either the original node if already scalar,
     * or wrapped in a scalar context operator if transformation is needed
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

    /**
     * Creates an AST node representing the scalar variable $_.
     * <p>
     * The $_ variable is Perl's default scalar variable, used implicitly by many
     * operations when no explicit variable is provided.
     *
     * @param parser The parser instance, used to get the current token index
     * @return An OperatorNode representing the scalar variable $_
     */
    static OperatorNode scalarUnderscore(Parser parser) {
        return new OperatorNode(
                "$", new IdentifierNode("main::_", parser.tokenIndex), parser.tokenIndex);
    }

    /**
     * Creates an AST node representing the array variable @_.
     * <p>
     * The @_ array is Perl's default array for subroutine arguments. When a
     * subroutine is called, its arguments are passed via this array.
     *
     * @param parser The parser instance, used to get the current token index
     * @return An OperatorNode representing the array variable @_
     */
    static OperatorNode atUnderscore(Parser parser) {
        return new OperatorNode("@",
                new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
    }

    static OperatorNode atArgv(Parser parser) {
        return new OperatorNode("@",
                new IdentifierNode("main::ARGV", parser.tokenIndex), parser.tokenIndex);
    }

    /**
     * Creates a ListNode containing @_ for passing as arguments to subroutine calls.
     * <p>
     * This is used when transforming blocks into anonymous subroutines that need to
     * receive the outer subroutine's @_ parameter. For example:
     * <ul>
     *   <li>eval { } blocks are transformed to sub { }->(@_)</li>
     *   <li>try { } blocks are transformed to sub { }->(@_)</li>
     *   <li>Large code blocks are refactored to sub { }->(@_)</li>
     * </ul>
     *
     * @param parser The parser instance, used to get the current token index
     * @return A ListNode containing a single element: the @_ operator
     */
    static ListNode atUnderscoreArgs(Parser parser) {
        ListNode args = new ListNode(parser.tokenIndex);
        args.elements.add(atUnderscore(parser));
        return args;
    }
}
