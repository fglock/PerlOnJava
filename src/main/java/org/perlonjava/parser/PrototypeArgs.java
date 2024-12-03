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
        // System.out.println("prototype start " + prototype);

        boolean hasParentheses = false;
        if (TokenUtils.peek(parser).text.equals("(")) {
            hasParentheses = true;
            TokenUtils.consume(parser);
        }

        if (prototype == null) {
            // Null prototype: parse a list of zero or more elements
            args = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        } else {
            // Parse arguments according to the prototype
            while (!prototype.isEmpty()) {
                String prototypeStart = prototype.substring(0, 1);
                prototype = prototype.substring(1);
                // System.out.println("prototype " + prototype + " needComma:" + needComma + " optional:" + isOptional);
                switch (prototypeStart) {
                    case " ", "\t", "\n":
                        // Ignore whitespace characters in the prototype
                        break;
                    case ";":
                        // Semicolon indicates the start of optional arguments
                        isOptional = true;
                        break;
                    case "_":
                        // Underscore indicates a scalar argument
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
                        // Asterisk indicates a file handle argument
                        if (needComma) {
                            if (!isComma(TokenUtils.peek(parser))) {
                                if (isOptional) {
                                    break;
                                }
                                throw new PerlCompilerException("syntax error, expected comma");
                            }
                            consumeCommas(parser);
                        }
                        // TODO: Handle different file handle formats:  STDERR  /  *STDERR  /  \*STDERR  /  $fh  / my $fh
                        Node argList5 = FileHandle.parseFileHandle(parser);
                        if (argList5 == null) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected file handle");
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
                        ListNode argList0 = ListParser.parseZeroOrOneList(parser, 0);
                        if (argList0.elements.isEmpty()) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected scalar");
                        }
                        args.elements.add(new OperatorNode("scalar", argList0.elements.getFirst(), argList0.elements.getFirst().getIndex()));
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
                            Node block = new SubroutineNode(null, null, null, parser.parseBlock(), false, parser.tokenIndex);
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
                    default:
                        throw new PerlCompilerException("syntax error, unexpected prototype " + prototype);
                }
            }
        }

        // TODO: Check for "Too many arguments" error

        if (hasParentheses) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }
}
