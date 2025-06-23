package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.ListParser.consumeCommas;
import static org.perlonjava.parser.ListParser.isComma;
import static org.perlonjava.parser.OperatorParser.scalarUnderscore;

/**
 * The PrototypeArgs class is responsible for parsing arguments based on a given prototype.
 * It handles different types of arguments such as scalars, arrays, hashes, and code references,
 * and manages optional arguments and comma-separated lists.
 * <p>
 * Perl prototype characters:
 * $ - scalar argument
 *
 * @ - array argument (consumes remaining args)
 * % - hash argument (consumes remaining args)
 * & - code reference or block
 * * - typeglob/filehandle
 * _ - scalar argument defaulting to $_
 * + - array/hash reference or scalar
 * \X - reference to type X (where X is $, @, %, &, *)
 * \[XYZ] - reference to any of types X, Y, Z
 * ; - separates required from optional arguments
 */
public class PrototypeArgs {

    /**
     * Consumes arguments from the parser according to a specified prototype.
     * If the prototype is null, parses a list of zero or more elements.
     * Handles optional parentheses around arguments.
     *
     * @param parser    The parser instance used for parsing
     * @param prototype The prototype string defining the expected argument types and structure
     * @return A ListNode containing the parsed arguments
     * @throws PerlCompilerException if the arguments don't match the prototype
     */
    static ListNode consumeArgsWithPrototype(Parser parser, String prototype) {
        ListNode args = new ListNode(parser.tokenIndex);
        boolean hasParentheses = handleOpeningParenthesis(parser);

        if (prototype == null) {
            args = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        } else {
            parsePrototypeArguments(parser, args, prototype);
        }

        if (hasParentheses) {
            // Consume any trailing commas before checking for closing parenthesis
            while (isComma(TokenUtils.peek(parser))) {
                consumeCommas(parser);
            }

            if (!TokenUtils.peek(parser).text.equals(")")) {
                parser.throwError("Too many arguments");
            }
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }

    /**
     * Checks for and consumes opening parenthesis if present.
     *
     * @param parser The parser instance
     * @return true if parentheses were found and consumed, false otherwise
     */
    private static boolean handleOpeningParenthesis(Parser parser) {
        if (TokenUtils.peek(parser).text.equals("(")) {
            TokenUtils.consume(parser);
            return true;
        }
        return false;
    }

    /**
     * Parse arguments according to the given prototype string.
     * Handles all prototype characters and their corresponding argument types.
     *
     * @param parser    The parser instance
     * @param args      The argument list to populate
     * @param prototype The prototype string to parse
     */
    private static void parsePrototypeArguments(Parser parser, ListNode args, String prototype) {
        boolean isOptional = false;
        boolean needComma = false;

        for (int i = 0; i < prototype.length(); i++) {
            char prototypeChar = prototype.charAt(i);

            switch (prototypeChar) {
                case ' ', '\t', '\n' -> {
                } // Ignore whitespace
                case ';' -> isOptional = true;
                case '_' -> {
                    handleUnderscoreArgument(parser, args, isOptional, needComma);
                    needComma = true;
                }
                case '*' -> {
                    handleTypeGlobArgument(parser, args, isOptional, needComma);
                    needComma = true;
                }
                case '$' -> {
                    handleScalarArgument(parser, args, isOptional, needComma);
                    needComma = true;
                }
                case '@', '%' -> {
                    handleListOrHashArgument(parser, args, needComma);
                    needComma = true;
                }
                case '&' -> needComma = handleCodeReferenceArgument(parser, args, isOptional, needComma);
                case '+' -> {
                    handlePlusArgument(parser, args, isOptional, needComma);
                    needComma = true;
                }
                case '\\' -> {
                    i = handleBackslashArgument(parser, args, prototype, i + 1, isOptional, needComma);
                    needComma = true;
                }
                default -> parser.throwError("syntax error, unexpected prototype character '" + prototypeChar + "'");
            }
        }
    }

    /**
     * Parses an argument with optional comma handling.
     *
     * @param parser       The parser instance
     * @param isOptional   Whether the argument is optional
     * @param needComma    Whether a comma is required before the argument
     * @param expectedType Description of the expected argument type for error messages
     * @return The parsed argument node, or null if parsing failed and the argument was optional
     */
    private static Node parseArgumentWithComma(Parser parser, boolean isOptional, boolean needComma, String expectedType) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return null;
        }
        return parseRequiredArgument(parser, isOptional, expectedType);
    }

    private static void handleScalarArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        Node arg = parseArgumentWithComma(parser, isOptional, needComma, "scalar argument");
        if (arg != null) {
            args.elements.add(new OperatorNode("scalar", arg, arg.getIndex()));
        }
    }

    private static void handleUnderscoreArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        Node arg = parseArgumentWithComma(parser, true, needComma, "scalar argument");
        if (arg == null) {
            args.elements.add(scalarUnderscore(parser));
            return;
        }
        args.elements.add(new OperatorNode("scalar", arg, arg.getIndex()));
    }

    private static void handleTypeGlobArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        // Parse the expression
        Node expr = parser.parseExpression(parser.getPrecedence(","));
        if (expr == null) {
            if (!isOptional) {
                parser.throwError("syntax error, expected argument");
            }
            return;
        }

        // Handle different types
        if (expr instanceof OperatorNode opNode && opNode.operator.equals("*")) {
            // Typeglob - create a typeglob reference
            args.elements.add(new OperatorNode("\\", expr, expr.getIndex()));
        } else {
            // Bare scalars
            args.elements.add(new OperatorNode("scalar", expr, expr.getIndex()));
        }
    }

    private static void handleListOrHashArgument(Parser parser, ListNode args, boolean needComma) {
        if (needComma && !isComma(TokenUtils.peek(parser))) {
            return;
        }
        if (needComma) {
            consumeCommas(parser);
        }
        ListNode argList = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        args.elements.addAll(argList.elements);
    }

    private static boolean handleCodeReferenceArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return true;
        }

        if (TokenUtils.peek(parser).text.equals("{")) {
            TokenUtils.consume(parser);
            Node block = new SubroutineNode(null, null, null, ParseBlock.parseBlock(parser), false, parser.tokenIndex);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            args.elements.add(block);
            return false;
        }

        Node codeRef = parseRequiredArgument(parser, isOptional, "code reference or block");
        if (codeRef != null) {
            args.elements.add(codeRef);
            return true;
        }
        return true;
    }

    private static void handlePlusArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        Node arg = parseArgumentWithComma(parser, isOptional, needComma, "array or hash reference");
        if (arg == null) {
            return;
        }

        if (arg instanceof OperatorNode opNode && (opNode.operator.equals("@") || opNode.operator.equals("%"))) {
            args.elements.add(new OperatorNode("\\", arg, arg.getIndex()));
        } else {
            args.elements.add(new OperatorNode("scalar", arg, arg.getIndex()));
        }
    }

    private static int handleBackslashArgument(Parser parser, ListNode args, String prototype, int prototypeIndex,
                                               boolean isOptional, boolean needComma) {
        if (prototypeIndex >= prototype.length()) {
            parser.throwError("syntax error, incomplete backslash reference in prototype");
        }

        boolean isGroup = prototype.charAt(prototypeIndex) == '[';
        String expectedType = isGroup ? "reference" : "reference to " + prototype.charAt(prototypeIndex);

        Node referenceArg = parseArgumentWithComma(parser, isOptional, needComma, expectedType);
        if (referenceArg != null) {
            args.elements.add(new OperatorNode("\\", referenceArg, referenceArg.getIndex()));
        }

        if (!isGroup) {
            return prototypeIndex + 1;
        }

        while (prototypeIndex < prototype.length() && prototype.charAt(prototypeIndex) != ']') {
            prototypeIndex++;
        }
        return prototypeIndex + 1;
    }

    private static boolean consumeCommaIfPresent(Parser parser, boolean isOptional) {
        if (!isComma(TokenUtils.peek(parser))) {
            if (isOptional) {
                return false;
            }
            parser.throwError("syntax error, expected comma");
        }
        consumeCommas(parser);
        return true;
    }

    private static Node parseRequiredArgument(Parser parser, boolean isOptional, String expectedType) {
        if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
            if (isOptional) {
                return null;
            }
            parser.throwError("syntax error, expected " + expectedType);
        }
        return parser.parseExpression(parser.getPrecedence(","));
    }
}
