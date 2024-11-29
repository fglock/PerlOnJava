package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.astnode.SubroutineNode;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.ListParser.consumeCommas;
import static org.perlonjava.parser.ListParser.isComma;

public class PrototypeArgs {

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
            // null prototype
            args = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
        } else {

            while (!prototype.isEmpty()) {
                // System.out.println("prototype " + prototype + " needComma:" + needComma + " optional:" + isOptional);
                if (prototype.startsWith(";")) {
                    // System.out.println("prototype consume ;");
                    isOptional = true;
                    prototype = prototype.substring(1);
                } else if (prototype.startsWith("$")) {
                    // System.out.println("prototype consume $");
                    if (needComma) {
                        if (!isComma(TokenUtils.peek(parser))) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected comma");
                        }
                        consumeCommas(parser);
                    }
                    ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
                    if (argList.elements.size() == 0) {
                        if (isOptional) {
                            break;
                        }
                        throw new PerlCompilerException("syntax error, expected scalar");
                    }
                    args.elements.add(new OperatorNode("scalar", argList.elements.getFirst(), argList.elements.getFirst().getIndex()));
                    needComma = true;
                    prototype = prototype.substring(1);
                } else if (prototype.startsWith("@")) {
                    // System.out.println("prototype consume @");
                    if (needComma) {
                        if (!isComma(TokenUtils.peek(parser))) {
                            break;
                        }
                        consumeCommas(parser);
                    }
                    ListNode argList = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                    for (Node element : argList.elements) {
                        args.elements.add(element);
                    }
                    needComma = true;
                    prototype = prototype.substring(1);
                } else if (prototype.startsWith("&")) {
                    // System.out.println("prototype consume &");
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
                        //  { ... }
                        TokenUtils.consume(parser);
                        Node block = new SubroutineNode(null, null, null, parser.parseBlock(), false, parser.tokenIndex);
                        TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}");
                        args.elements.add(block);
                        needComma = false;
                    } else if (TokenUtils.peek(parser).text.equals("\\")) {
                        //  \&sub
                        ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
                        if (argList.elements.size() == 0) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected coderef");
                        }
                        args.elements.add(argList.elements.getFirst());
                        needComma = true;
                    } else {
                        throw new PerlCompilerException("syntax error, expected block");
                    }
                    prototype = prototype.substring(1);
                } else {
                    throw new PerlCompilerException("syntax error, unexpected prototype " + prototype);
                }
            }
        }

        // TODO check for "Too many arguments" error

        if (hasParentheses) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }
}
