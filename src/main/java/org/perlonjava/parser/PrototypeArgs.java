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
     *
     * @param parser    The parser instance used for parsing.
     * @param prototype The prototype string defining the expected argument types and structure.
     * @return A ListNode containing the parsed arguments.
     * @throws PerlCompilerException if the arguments don't match the prototype
     */
    static ListNode consumeArgsWithPrototype(Parser parser, String prototype) {
        ListNode args = new ListNode(parser.tokenIndex);

        // Handle parentheses around arguments
        boolean hasParentheses = handleOpeningParenthesis(parser);

        if (prototype == null) {
            // Null prototype: parse a list of zero or more elements
            args = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        } else {
            parsePrototypeArguments(parser, args, prototype);
        }

        if (hasParentheses) {
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
     *
     * @param parser    The parser instance
     * @param args      The argument list to populate
     * @param prototype The prototype string to parse
     */
    private static void parsePrototypeArguments(Parser parser, ListNode args, String prototype) {
        boolean isOptional = false;
        boolean needComma = false;

        int prototypeIndex = 0;
        while (prototypeIndex < prototype.length()) {
            char prototypeChar = prototype.charAt(prototypeIndex);
            prototypeIndex++;

            switch (prototypeChar) {
                case ' ', '\t', '\n':
                    // Ignore whitespace characters in the prototype
                    break;
                case ';':
                    // Semicolon indicates the start of optional arguments
                    isOptional = true;
                    break;
                case '_':
                    // Underscore indicates a scalar argument (defaults to $_ if not provided)
                    handleUnderscoreArgument(parser, args, isOptional, needComma);
                    needComma = true;
                    break;
                case '*':
                    // Asterisk indicates a file handle or typeglob argument
                    handleTypeGlobArgument(parser, args, isOptional, needComma);
                    needComma = true;
                    break;
                case '$':
                    // Dollar sign indicates a scalar argument
                    handleScalarArgument(parser, args, isOptional, needComma);
                    needComma = true;
                    break;
                case '@', '%':
                    // At sign or percent sign indicates a list or hash argument
                    handleListOrHashArgument(parser, args, needComma);
                    needComma = true;
                    break;
                case '&':
                    // Ampersand indicates a code reference argument
                    boolean commaNeeded = handleCodeReferenceArgument(parser, args, isOptional, needComma);
                    needComma = commaNeeded;
                    break;
                case '+':
                    // Plus sign indicates a hash or array reference argument
                    handlePlusArgument(parser, args, isOptional, needComma);
                    needComma = true;
                    break;
                case '\\':
                    // Backslash indicates reference or start of backslash group
                    prototypeIndex = handleBackslashArgument(parser, args, prototype, prototypeIndex, isOptional, needComma);
                    needComma = true;
                    break;
                default:
                    throw new PerlCompilerException("syntax error, unexpected prototype character '" + prototypeChar + "'");
            }
        }
    }

    /**
     * Handle underscore argument parsing.
     */
    private static void handleUnderscoreArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            args.elements.add(scalarUnderscore(parser));
            return;
        }

        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
        if (argList.elements.isEmpty()) {
            args.elements.add(scalarUnderscore(parser));
        } else {
            args.elements.add(new OperatorNode("scalar", argList.elements.getFirst(), argList.elements.getFirst().getIndex()));
        }
    }

    /**
     * Handle typeglob/filehandle argument parsing.
     */
    private static void handleTypeGlobArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        Node fileHandle = FileHandle.parseFileHandle(parser);
        if (fileHandle == null) {
            if (isOptional) {
                return;
            }
            throw new PerlCompilerException("syntax error, expected file handle or typeglob");
        }
        args.elements.add(fileHandle);
    }

    /**
     * Handle scalar argument parsing.
     */
    private static void handleScalarArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        // Check if we have reached the end of the input (EOF) or a terminator (like `;`).
        if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
            if (isOptional) {
                return;
            }
            throw new PerlCompilerException("syntax error, expected scalar argument");
        }

        Node arg = parser.parseExpression(parser.getPrecedence(","));
        args.elements.add(new OperatorNode("scalar", arg, arg.getIndex()));
    }

    /**
     * Handle list or hash argument parsing.
     */
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

    /**
     * Handle code reference argument parsing.
     *
     * @return true if a comma is needed after this argument, false otherwise
     */
    private static boolean handleCodeReferenceArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return true;
        }

        if (TokenUtils.peek(parser).text.equals("{")) {
            // Parse a block
            TokenUtils.consume(parser);
            Node block = new SubroutineNode(null, null, null, ParseBlock.parseBlock(parser), false, parser.tokenIndex);
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
            args.elements.add(block);
            return false; // Blocks don't need commas after them
        } else if (TokenUtils.peek(parser).text.equals("\\")) {
            // Parse a code reference
            ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
            if (argList.elements.isEmpty()) {
                if (isOptional) {
                    return true;
                }
                throw new PerlCompilerException("syntax error, expected code reference");
            }
            args.elements.add(argList.elements.getFirst());
            return true;
        } else {
            if (isOptional) {
                return true;
            }
            throw new PerlCompilerException("syntax error, expected block or code reference");
        }
    }

    /**
     * Handle plus argument parsing (array/hash reference or scalar).
     */
    private static void handlePlusArgument(Parser parser, ListNode args, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
        if (argList.elements.isEmpty()) {
            if (isOptional) {
                return;
            }
            throw new PerlCompilerException("syntax error, expected hash or array reference");
        }

        // The + prototype is a special alternative to $ that will act like \[@%] when given a literal
        // array or hash variable, but will otherwise force scalar context on the argument.
        // This is useful for functions which should accept either a literal array or an array
        // reference as the argument

        // TODO: Implement proper + prototype handling
        // For now, just add the arguments as-is
        args.elements.addAll(argList.elements);
    }

    /**
     * Handle backslash argument parsing (references).
     *
     * @return the updated prototype index
     */
    private static int handleBackslashArgument(Parser parser, ListNode args, String prototype, int prototypeIndex,
                                               boolean isOptional, boolean needComma) {
        if (prototypeIndex < prototype.length() && prototype.charAt(prototypeIndex) == '[') {
            // Start of backslash group \[...]
            return prototypeIndex + 1; // Skip the '['
        } else if (prototypeIndex < prototype.length()) {
            // Single backslashed character
            char refType = prototype.charAt(prototypeIndex);
            if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
                return prototypeIndex + 1;
            }

            // Check if we have reached the end of the input (EOF) or a terminator (like `;`).
            if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
                if (isOptional) {
                    return prototypeIndex + 1;
                }
                throw new PerlCompilerException("syntax error, expected reference to " + refType);
            }

            Node arg = parser.parseExpression(parser.getPrecedence(","));

            // TODO: Add type checking - "Type of arg 1 to main::xxx must be array"
            args.elements.add(new OperatorNode("\\", arg, arg.getIndex()));
            return prototypeIndex + 1;
        } else {
            throw new PerlCompilerException("syntax error, incomplete backslash reference in prototype");
        }
    }

    /**
     * Consume comma if present and needed, handling optional arguments.
     *
     * @param parser     The parser instance
     * @param isOptional Whether the current argument is optional
     * @return true if comma was consumed or not needed, false if comma was required but missing
     */
    private static boolean consumeCommaIfPresent(Parser parser, boolean isOptional) {
        if (!isComma(TokenUtils.peek(parser))) {
            if (isOptional) {
                return false;
            }
            throw new PerlCompilerException("syntax error, expected comma");
        }
        consumeCommas(parser);
        return true;
    }

    /**
     * Handles a backslash group in the prototype (e.g., \[$@%&*]).
     * This allows any of the specified reference types.
     *
     * @param parser     The parser instance used for parsing.
     * @param args       The list of arguments to add to.
     * @param group      The backslash group string (e.g., "$@%&*").
     * @param isOptional Whether the argument is optional.
     * @param needComma  Whether a comma is needed before parsing the argument.
     */
    private static void handleBackslashGroup(Parser parser, ListNode args, String group, boolean isOptional, boolean needComma) {
        if (needComma && !consumeCommaIfPresent(parser, isOptional)) {
            return;
        }

        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
        if (argList.elements.isEmpty()) {
            if (isOptional) {
                return;
            }
            throw new PerlCompilerException("syntax error, expected reference matching [" + group + "]");
        }

        // TODO: Implement proper backslash group handling with type validation
        // The group string contains the allowed reference types (e.g., "$@%" means scalar, array, or hash ref)
        // For now, just add the arguments as-is
        args.elements.addAll(argList.elements);
    }
}