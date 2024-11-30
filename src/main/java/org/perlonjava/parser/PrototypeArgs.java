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
                String prototypeStart = prototype.substring(0, 1);
                prototype = prototype.substring(1);
                // System.out.println("prototype " + prototype + " needComma:" + needComma + " optional:" + isOptional);
                switch (prototypeStart) {
                    case " ", "\t", "\n":
                        break;
                    case ";":
                        // System.out.println("prototype consume ;");
                        isOptional = true;
                        break;
                    case "_":
                        // System.out.println("prototype consume _");
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
                    case "$":
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
                        // System.out.println("prototype consume @ or %");
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

        // TODO check for "Too many arguments" error

        if (hasParentheses) {
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        return args;
    }
}
