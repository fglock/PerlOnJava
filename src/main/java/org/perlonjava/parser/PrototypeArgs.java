package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.ListParser.consumeCommas;
import static org.perlonjava.parser.ListParser.isComma;
import static org.perlonjava.parser.ParserNodeUtils.scalarUnderscore;

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
     * Throws a "Not enough arguments" error with the subroutine name if available.
     *
     * @param parser The parser instance
     */
    private static void throwNotEnoughArgumentsError(Parser parser) {
        String subName = parser.ctx.symbolTable.getCurrentSubroutine();
        String errorMsg = (subName == null || subName.isEmpty())
                ? "Not enough arguments"
                : "Not enough arguments for " + subName;
        parser.throwError(errorMsg);
    }

    /**
     * Throws a "Too many arguments" error with the subroutine name if available.
     *
     * @param parser The parser instance
     */
    private static void throwTooManyArgumentsError(Parser parser) {
        String subName = parser.ctx.symbolTable.getCurrentSubroutine();
        String errorMsg = (subName == null || subName.isEmpty())
                ? "Too many arguments"
                : "Too many arguments for " + subName;
        parser.throwError(errorMsg);
    }

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

        // Comma is forbidden here
        if (prototype != null && !prototype.isEmpty() && isComma(TokenUtils.peek(parser))) {
            parser.throwError("syntax error");
        }

        if (prototype == null) {
            args = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
            // When no prototype is given, arguments are evaluated in LIST context
//            for (Node element : args.elements) {
//                element.setAnnotation("context", "LIST");
//            }
        } else {
            parsePrototypeArguments(parser, args, prototype);

            // Check for too many arguments without parentheses only if prototype expects 2+ args
            if (!hasParentheses && countPrototypeArgs(prototype) >= 2) {
                // If we see a comma after parsing all required args, there are too many
                if (isComma(TokenUtils.peek(parser))) {
                    throwTooManyArgumentsError(parser);
                }
            }
        }

        if (hasParentheses) {
            // Consume any trailing commas before checking for closing parenthesis
            while (isComma(TokenUtils.peek(parser))) {
                consumeCommas(parser);
            }

            if (!TokenUtils.peek(parser).text.equals(")")) {
                throwTooManyArgumentsError(parser);
            }
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        // Array or Hash dereference is forbidden here
        if (TokenUtils.peek(parser).text.equals("{") || TokenUtils.peek(parser).text.equals("[")) {
            parser.throwError("syntax error");
        }

        return args;
    }

    /**
     * Count the number of arguments expected by a prototype.
     * This counts all arguments before @ or % (which consume all remaining).
     *
     * @param prototype The prototype string
     * @return The number of discrete arguments expected
     */
    private static int countPrototypeArgs(String prototype) {
        int count = 0;
        boolean inBackslash = false;
        boolean inGroup = false;

        for (int i = 0; i < prototype.length(); i++) {
            char c = prototype.charAt(i);

            if (inBackslash) {
                if (c == '[') {
                    inGroup = true;
                } else if (!inGroup) {
                    count++;
                    inBackslash = false;
                }
            } else if (inGroup) {
                if (c == ']') {
                    count++;
                    inGroup = false;
                    inBackslash = false;
                }
            } else {
                switch (c) {
                    case ' ', '\t', '\n', ';' -> {
                    } // Ignore whitespace and optional separator
                    case '\\' -> inBackslash = true;
                    case '@', '%' -> {
                        return count; // These consume all remaining args
                    }
                    case '$', '_', '*', '&', '+' -> count++;
                }
            }
        }

        return count;
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
        // Check if we're at the end of arguments before checking for comma
        if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
            if (!isOptional) {
                throwNotEnoughArgumentsError(parser);
            }
            return null;
        }

        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return null;
        }
        return parseRequiredArgument(parser, isOptional, expectedType);
    }

    private static void handleScalarArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        Node arg = parseArgumentWithComma(parser, isOptional, needComma, "scalar argument");
        if (arg != null) {
            Node scalarArg = ParserNodeUtils.toScalarContext(arg);
            scalarArg.setAnnotation("context", "SCALAR");
            args.elements.add(scalarArg);
        }
    }

    private static void handleUnderscoreArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        Node arg = parseArgumentWithComma(parser, true, needComma, "scalar argument");
        if (arg == null) {
            Node underscoreArg = scalarUnderscore(parser);
            underscoreArg.setAnnotation("context", "SCALAR");
            args.elements.add(underscoreArg);
            return;
        }
        Node scalarArg = ParserNodeUtils.toScalarContext(arg);
        scalarArg.setAnnotation("context", "SCALAR");
        args.elements.add(scalarArg);
    }

    private static void handleTypeGlobArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        // Check if we're at the end of arguments
        if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
            if (!isOptional) {
                throwNotEnoughArgumentsError(parser);
            }
            return;
        }

        // Parse the expression
        Node expr = parser.parseExpression(parser.getPrecedence(","));
        if (expr == null) {
            if (!isOptional) {
                throwNotEnoughArgumentsError(parser);
            }
            return;
        }

        // Handle different types
        if (expr instanceof OperatorNode opNode && opNode.operator.equals("*")) {
            // Typeglob - create a typeglob reference
            Node typeglobRef = new OperatorNode("\\", expr, expr.getIndex());
            typeglobRef.setAnnotation("context", "SCALAR");
            args.elements.add(typeglobRef);
        } else if (expr instanceof IdentifierNode idNode) {
            // Bareword - create a typeglob reference
            Node typeglobRef = FileHandle.parseBarewordHandle(parser, idNode.name);
            args.elements.add(typeglobRef == null ? expr : typeglobRef);
        } else {
            // Bare scalars
            Node scalarArg = ParserNodeUtils.toScalarContext(expr);
            scalarArg.setAnnotation("context", "SCALAR");
            args.elements.add(scalarArg);
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
        // @ and % consume remaining arguments in LIST context
//        for (Node element : argList.elements) {
//            element.setAnnotation("context", "LIST");
//        }
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
            // Code references/blocks are evaluated in SCALAR context
            block.setAnnotation("context", "SCALAR");
            args.elements.add(block);
            return false;
        }

        Node codeRef = parseRequiredArgument(parser, isOptional, "code reference or block");
        if (codeRef != null) {
            // Code references are evaluated in SCALAR context
            codeRef.setAnnotation("context", "SCALAR");
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
            Node refArg = new OperatorNode("\\", arg, arg.getIndex());
            refArg.setAnnotation("context", "SCALAR");
            args.elements.add(refArg);
        } else {
            Node scalarArg = ParserNodeUtils.toScalarContext(arg);
            scalarArg.setAnnotation("context", "SCALAR");
            args.elements.add(scalarArg);
        }
    }

    private static int handleBackslashArgument(Parser parser, ListNode args, String prototype, int prototypeIndex,
                                               boolean isOptional, boolean needComma) {
        if (prototypeIndex >= prototype.length()) {
            parser.throwError("syntax error, incomplete backslash reference in prototype");
        }

        boolean isGroup = prototype.charAt(prototypeIndex) == '[';
        char refType = isGroup ? ' ' : prototype.charAt(prototypeIndex);
        String expectedType = isGroup ? "reference" : "reference to " + refType;

        Node referenceArg = parseArgumentWithComma(parser, isOptional, needComma, expectedType);
        if (referenceArg != null) {
            // Check if user passed an explicit reference when prototype expects auto-reference
            if (refType == '$' && referenceArg instanceof OperatorNode opNode &&
                    opNode.operator.equals("\\")) {
                // Get the subroutine name from the parser context
                String subName = parser.ctx.symbolTable.getCurrentSubroutine();
                String subNamePart = (subName == null || subName.isEmpty()) ? "" : " to " + subName;
                parser.throwError("Type of arg " + (args.elements.size() + 1) +
                        subNamePart +
                        " must be scalar (not single ref constructor)");
            }

            // For groups like \[$@%*], check if SubroutineNode is allowed
            if (isGroup && referenceArg instanceof SubroutineNode) {
                // Extract the allowed types from the group
                int groupStart = prototypeIndex + 1;
                int groupEnd = prototype.indexOf(']', groupStart);
                if (groupEnd != -1) {
                    String allowedTypes = prototype.substring(groupStart, groupEnd);
                    if (!allowedTypes.contains("&")) {
                        // Match Perl's error message format
                        String subName = parser.ctx.symbolTable.getCurrentSubroutine();
                        if (subName != null && !subName.isEmpty()) {
                            parser.throwError("Can't modify anonymous subroutine in " + subName);
                        } else {
                            parser.throwError("Can't modify anonymous subroutine");
                        }
                    }
                }
            }

            Node refNode = new OperatorNode("\\", referenceArg, referenceArg.getIndex());
            // References are evaluated in SCALAR context
            refNode.setAnnotation("context", "SCALAR");
            args.elements.add(refNode);
        }

        if (!isGroup) {
            return prototypeIndex;  // Return the index of the character after '\X', not one beyond it
        }

        // Skip to the end of the group [...]
        while (prototypeIndex < prototype.length() && prototype.charAt(prototypeIndex) != ']') {
            prototypeIndex++;
        }
        return prototypeIndex + 1;  // For groups, skip past the closing ']'
    }

    private static boolean consumeCommaIfPresent(Parser parser, boolean isOptional) {
        if (!isComma(TokenUtils.peek(parser))) {
            if (isOptional) {
                return false;
            }
            // Check if we're at the end of arguments before requiring a comma
            if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
                throwNotEnoughArgumentsError(parser);
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
            throwNotEnoughArgumentsError(parser);
        }
        Node expr = parser.parseExpression(parser.getPrecedence(","));
        if (expr == null) {
            if (!isOptional) {
                throwNotEnoughArgumentsError(parser);
            }
        }
        return expr;
    }
}