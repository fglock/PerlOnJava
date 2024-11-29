package org.perlonjava.parser;

import org.perlonjava.astnode.ListNode;
import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.OperatorNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

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
                        LexerToken token = TokenUtils.peek(parser);
                        if (!token.text.equals(",") && !token.text.equals("=>")) {
                            if (isOptional) {
                                break;
                            }
                            throw new PerlCompilerException("syntax error, expected comma");
                        }
                        TokenUtils.consume(parser);
                    }
                    ListNode argList = ListParser.parseZeroOrOneList(parser, 0);
                    if (argList.elements.size() == 0) {
                        if (isOptional) {
                            break;
                        }
                        throw new PerlCompilerException("syntax error, expected scalar");
                    }
                    Node element = argList.elements.getFirst();
                    Node scalarElement = new OperatorNode("scalar", element, element.getIndex());
                    args.elements.add(scalarElement);
                    needComma = true;
                    prototype = prototype.substring(1);
                } else if (prototype.startsWith("@")) {
                    // System.out.println("prototype consume @");
                    if (needComma) {
                        LexerToken token = TokenUtils.peek(parser);
                        if (!token.text.equals(",") && !token.text.equals("=>")) {
                            break;
                        }
                        TokenUtils.consume(parser);
                    }
                    ListNode argList = ListParser.parseZeroOrMoreList(parser, 0, false, true, false, false);
                    for (Node element : argList.elements) {
                        args.elements.add(element);
                    }
                    needComma = true;
                    prototype = prototype.substring(1);
                } else {
                    throw new PerlCompilerException("syntax error, unexpected prototype " + prototype);
                }
            }
        }

        if (hasParentheses) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }
}
