package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.NameNormalizer;
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
            block = new SubroutineNode(null, null, null, block, false, parser.tokenIndex);
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
            String fileName = NameNormalizer.moduleToFilename(moduleName);
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
                    new SubroutineNode(null, null, null, block, true, parser.tokenIndex), new ListNode(parser.tokenIndex), parser.tokenIndex);
        } else {
            // Otherwise, parse an expression, and default to $_
            operand = ListParser.parseZeroOrOneList(parser, 0);
            if (((ListNode) operand).elements.isEmpty()) {
                // create `$_` variable
                operand = new OperatorNode(
                        "$", new IdentifierNode("_", parser.tokenIndex), parser.tokenIndex);
            }
        }
        return new OperatorNode("eval", operand, parser.tokenIndex);
    }

    static Node parseDiamondOperator(Parser parser, LexerToken token) {
        // Save the current token index to restore later if needed
        int currentTokenIndex = parser.tokenIndex;
        String tokenText = parser.tokens.get(parser.tokenIndex).text;

        // Check if the token is a dollar sign, indicating a variable
        if (tokenText.equals("$")) {
            // Handle the case for <$fh>
            parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
            parser.tokenIndex++;
            Node var = parser.parseVariable("$"); // Parse the variable following the dollar sign
            parser.ctx.logDebug("diamond operator var " + var);

            // Check if the next token is a closing angle bracket
            if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                parser.consume(); // Consume the '>' token
                // Return a BinaryOperatorNode representing a readline operation
                return new BinaryOperatorNode("readline",
                        var,
                        new ListNode(parser.tokenIndex), parser.tokenIndex);
            }
        }

        // Restore the token index
        parser.tokenIndex = currentTokenIndex;

        // Check if the token is one of the standard input sources
        if (tokenText.equals("STDIN") || tokenText.equals("DATA") || tokenText.equals("ARGV")) {
            // Handle the case for <STDIN>, <DATA>, or <ARGV>
            parser.ctx.logDebug("diamond operator " + token.text + parser.tokens.get(parser.tokenIndex));
            parser.tokenIndex++;

            // Check if the next token is a closing angle bracket
            if (parser.tokens.get(parser.tokenIndex).text.equals(">")) {
                parser.consume(); // Consume the '>' token
                // Return a BinaryOperatorNode representing a readline operation
                return new BinaryOperatorNode("readline",
                        new IdentifierNode("main::" + tokenText, currentTokenIndex),
                        new ListNode(parser.tokenIndex), parser.tokenIndex);
            }
        }

        // Restore the token index
        parser.tokenIndex = currentTokenIndex;

        // Handle other cases like <>, <<>>, or <*.*> by parsing as a raw string
        return StringParser.parseRawString(parser, token.text);
    }
}
