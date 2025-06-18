package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

import static org.perlonjava.parser.ListParser.consumeCommas;
import static org.perlonjava.parser.ListParser.isComma;
import static org.perlonjava.parser.OperatorParser.scalarUnderscore;

/**
 * The PrototypeArgs class is responsible for parsing arguments based on a given prototype.
 * It handles different types of arguments such as scalars, arrays, hashes, and code references,
 * and manages optional arguments and comma-separated lists.
 */
public class PrototypeArgs {

    /**
     * Consumes arguments from the parser according to a specified prototype.
     *
     * @param parser    The parser instance used for parsing.
     * @param prototype The prototype string defining the expected argument types and structure.
     * @return A ListNode containing the parsed arguments.
     */
    static ListNode consumeArgsWithPrototype(Parser parser, String prototype) {
        ListNode args = new ListNode(parser.tokenIndex);
        boolean isOptional = false;
        boolean needComma = false;
        boolean inBackslashGroup = false;
        StringBuilder backslashGroup = new StringBuilder();

        boolean hasParentheses = false;
        if (TokenUtils.peek(parser).text.equals("(")) {
            hasParentheses = true;
            TokenUtils.consume(parser);
        }

        if (prototype == null) {
            // Null prototype: parse a list of zero or more elements
            args = ListParser.parseZeroOrMoreList(parser, 0, false, false, false, false);
        } else {
            // Parse arguments according to the prototype
            while (!prototype.isEmpty()) {
                String prototypeStart = prototype.substring(0, 1);
                prototype = prototype.substring(1);

                if (inBackslashGroup) {
                    if (prototypeStart.equals("]")) {
                        inBackslashGroup = false;
                        // Handle backslash group
                        handleBackslashGroup(parser, args, backslashGroup.toString(), isOptional, needComma);
                        backslashGroup.setLength(0); // Reset the group
                        needComma = true;
                    } else {
                        backslashGroup.append(prototypeStart);
                    }
                    continue;
                }

                switch (prototypeStart) {
                    case " ", "\t", "\n":
                        // Ignore whitespace characters in the prototype
                        break;
                    case ";":
                        // Semicolon indicates the start of optional arguments
                        isOptional = true;
                        break;
                    case "_":
                        // Underscore indicates a scalar argument (defaults to $_ if not provided)
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                args.elements.add(scalarUnderscore(parser));
                                break;
                            }
                            consumeCommas(parser);
                        }
                        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
                        if (argList.elements.isEmpty()) {
                            args.elements.add(scalarUnderscore(parser));
                            break;
                        }
                        args.elements.add(new OperatorNode("scalar", argList.elements.getFirst(), argList.elements.getFirst().getIndex()));
                        needComma = true;
                        break;
                    case "*":
                        // Asterisk indicates a file handle or typeglob argument
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected comma");
                            }
                            consumeCommas(parser);
                        }
                        Node argList5 = FileHandle.parseFileHandle(parser);
                        if (argList5 == null) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected file handle or typeglob");
                        }
                        args.elements.add(argList5);
                        needComma = true;
                        break;
                    case "$":
                        // Dollar sign indicates a scalar argument
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected comma");
                            }
                            consumeCommas(parser);
                        }

                        // Check if we have reached the end of the input (EOF) or a terminator (like `;`).
                        if (Parser.isExpressionTerminator(TokenUtils.peek(parser))) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected scalar");
                        }

                        Node arg = parser.parseExpression(parser.getPrecedence(","));
                        args.elements.add(new OperatorNode("scalar", arg, arg.getIndex()));
                        needComma = true;
                        break;
                    case "@", "%":
                        // At sign or percent sign indicates a list or hash argument
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                break;
                            }
                            consumeCommas(parser);
                        }
                        ListNode argList1 = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                        args.elements.addAll(argList1.elements);
                        needComma = true;
                        break;
                    case "&":
                        // Ampersand indicates a code reference argument
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected comma");
                            }
                            consumeCommas(parser);
                        }
                        if (TokenUtils.peek(parser).text.equals("{")) {
                            // Parse a block
                            TokenUtils.consume(parser);
                            Node block = new SubroutineNode(null, null, null, ParseBlock.parseBlock(parser), false, parser.tokenIndex);
                            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                            args.elements.add(block);
                            needComma = false;
                        } else if (TokenUtils.peek(parser).text.equals("\\")) {
                            // Parse a code reference
                            ListNode argList2 = ListParser.parseZeroOrOneList(parser, 0);
                            if (argList2.elements.isEmpty()) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected coderef");
                            }
                            args.elements.add(argList2.elements.getFirst());
                            needComma = true;
                        } else {
                            throw new PerlCompilerException("syntax error, expected block");
                        }
                        break;
                    case "+":
                        // Plus sign indicates a hash or array reference argument
                        // The + prototype is a special alternative to $ that will act like \[@%] when given a literal array or hash variable, but will otherwise force scalar context on the argument. This is useful for functions which should accept either a literal array or an array reference as the argument

                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected comma");
                            }
                            consumeCommas(parser);
                        }
                        ListNode argList3 = ListParser.parseZeroOrOneList(parser, 0);
                        if (argList3.elements.isEmpty()) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected hash or array reference");
                        }

                        // TODO WIP
                        // args.elements.add(new OperatorNode("ref", argList3.elements.getFirst(), argList3.elements.getFirst().getIndex(), "+"));
                        args.elements.addAll(argList3.elements);

                        needComma = true;
                        break;
                    case "\\":
                        // Backslash indicates the start of a backslash group
                        if (prototype.startsWith("[")) {
                            inBackslashGroup = true;
                            prototype = prototype.substring(1); // Skip the '['
                        } else {
                            // Single backslashed character
                            String refType = prototype.substring(0, 1);
                            prototype = prototype.substring(1);
                            if (needComma) {
                                if (!isComma(TokenUtils.peek(parser))) {
                                    if (isOptional) {
                                        break;
                                    }
                                    throw new PerlCompilerException("syntax error, expected comma");
                                }
                                consumeCommas(parser);
                            }
                            ListNode argList4 = ListParser.parseZeroOrOneList(parser, 0);
                            if (argList4.elements.isEmpty()) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected reference");
                            }

                            // TODO WIP
                            // args.elements.add(new OperatorNode("ref", argList4.elements.getFirst(), argList4.elements.getFirst().getIndex(), refType));
                            args.elements.addAll(argList4.elements);

                            needComma = true;
                        }
                        break;
                    default:
                        throw new PerlCompilerException("syntax error, unexpected prototype " + prototypeStart);
                }
            }
        }

        if (hasParentheses) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }

    /**
     * Handles a backslash group in the prototype.
     *
     * @param parser     The parser instance used for parsing.
     * @param args       The list of arguments to add to.
     * @param group      The backslash group string (e.g., "$@%&*").
     * @param isOptional Whether the argument is optional.
     * @param needComma  Whether a comma is needed before parsing the argument.
     */
    private static void handleBackslashGroup(Parser parser, ListNode args, String group, boolean isOptional, boolean needComma) {
        if (needComma) {
            if (!isComma(TokenUtils.peek(parser))) {
                if (isOptional) {
                    return;
                }
                throw new PerlCompilerException("syntax error, expected comma");
            }
            consumeCommas(parser);
        }
        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
        if (argList.elements.isEmpty()) {
            if (isOptional) {
                return;
            }
            throw new PerlCompilerException("syntax error, expected reference");
        }

        // TODO WIP
        // args.elements.add(new OperatorNode("ref", argList.elements.getFirst(), argList.elements.getFirst().getIndex(), group));
        args.elements.addAll(argList.elements);

    }
}