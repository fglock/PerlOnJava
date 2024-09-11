package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.ModuleLoader;
import org.perlonjava.runtime.PerlCompilerException;

import java.util.List;

public class OperatorParser {

    static BinaryOperatorNode parseMapGrepSort(Parser parser, LexerToken token) {
        ListNode operand;
        // Handle 'sort' keyword as a Binary operator with a Code and List operands
        operand = ListParser.parseZeroOrMoreList(parser, 1, true, false, false, false);
        // transform:   { 123 }
        // into:        sub { 123 }
        Node block = operand.handle;
        operand.handle = null;
        if (block == null && token.text.equals("sort")) {
            // create default block for `sort`: { $a cmp $b }
            block = new BlockNode(List.of(new BinaryOperatorNode("cmp", new OperatorNode("$", new IdentifierNode("main::a", parser.tokenIndex), parser.tokenIndex), new OperatorNode("$", new IdentifierNode("main::b", parser.tokenIndex), parser.tokenIndex), parser.tokenIndex)), parser.tokenIndex);
        }
        if (block instanceof BlockNode) {
            block = new AnonSubNode(null, null, null, block, false, parser.tokenIndex);
        }
        return new BinaryOperatorNode(token.text, block, operand, parser.tokenIndex);
    }

    static Node parseRequire(Parser parser) {
        LexerToken token;
        // Handle 'require' keyword which can be followed by a version, bareword or filename
        token = parser.peek();
        Node operand;
        if (token.type == LexerTokenType.IDENTIFIER) {
            // TODO `require` version

            // `require` module
            String moduleName = IdentifierParser.parseSubroutineIdentifier(parser);
            parser.ctx.logDebug("name `" + moduleName + "`");
            if (moduleName == null) {
                throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
            }
            String fileName = ModuleLoader.moduleToFilename(moduleName);
            operand = ListNode.makeList(new StringNode(fileName, parser.tokenIndex));
        } else {
            // `require` file
            operand = ListParser.parseZeroOrOneList(parser, 1);
        }
        return new OperatorNode("require", operand, parser.tokenIndex);
    }

    static Node parseDoOperator(Parser parser) {
        LexerToken token;
        Node block;
        // Handle 'do' keyword which can be followed by a block or filename
        token = parser.peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            parser.consume(LexerTokenType.OPERATOR, "{");
            block = parser.parseBlock();
            parser.consume(LexerTokenType.OPERATOR, "}");
            return block;
        }
        // `do` file
        Node operand = ListParser.parseZeroOrOneList(parser, 1);
        return new OperatorNode("doFile", operand, parser.tokenIndex);
    }

    static AbstractNode parseEval(Parser parser) {
        Node block;
        Node operand;
        LexerToken token;
        // Handle 'eval' keyword which can be followed by a block or an expression
        token = parser.peek();
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("{")) {
            // If the next token is '{', parse a block
            parser.consume(LexerTokenType.OPERATOR, "{");
            block = parser.parseBlock();
            parser.consume(LexerTokenType.OPERATOR, "}");
            // transform:  eval { 123 }
            // into:  sub { 123 }->()  with useTryCatch flag
            return new BinaryOperatorNode("->",
                    new AnonSubNode(null, null, null, block, true, parser.tokenIndex), new ListNode(parser.tokenIndex), parser.tokenIndex);
        } else {
            // Otherwise, parse a primary expression
            operand = parser.parsePrimary();
        }
        return new OperatorNode("eval", operand, parser.tokenIndex);
    }
}
