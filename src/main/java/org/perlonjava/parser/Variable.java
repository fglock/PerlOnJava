package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.GlobalVariable;
import org.perlonjava.runtime.NameNormalizer;
import org.perlonjava.runtime.PerlCompilerException;

import static org.perlonjava.parser.ParserNodeUtils.atUnderscore;
import static org.perlonjava.parser.TokenUtils.peek;

public class Variable {
    /**
     * Parses a variable from the given lexer token.
     *
     * @param parser
     * @param sigil  The sigil that starts the variable.
     * @return The parsed variable node.
     * @throws PerlCompilerException If there is a syntax error.
     */
    public static Node parseVariable(Parser parser, String sigil) {
        Node operand;

        // Special handling for $$ followed by {
        if (peek(parser).text.equals("$")) {
            // Check if we have $${...} pattern
            if (parser.tokens.get(parser.tokenIndex + 1).text.equals("{")) {
                // This is $${...}, parse as dereference of ${...}
                // Don't consume the $ token, let it be parsed as part of the variable
                operand = parser.parseExpression(parser.getPrecedence("$") + 1);
                return new OperatorNode(sigil, operand, parser.tokenIndex);
            }
        }

        String varName = IdentifierParser.parseComplexIdentifier(parser);
        parser.ctx.logDebug("Parsing variable: " + varName);

        if (varName != null) {
            // Variable name is valid.
            // Check for illegal characters after a variable
            if (peek(parser).text.equals("(") && !sigil.equals("&") && !parser.parsingForLoopVariable) {
                // Parentheses are only allowed after a variable in specific cases:
                // - `for my $v (...`
                // - `&name(...`
                // - `obj->$name(...`
                parser.throwError("syntax error");
            }

            if (sigil.equals("*")) {
                // Vivify the GLOB if it doesn't exist yet
                // This helps distinguish between file handles and other barewords
                String fullName = NameNormalizer.normalizeVariableName(varName, parser.ctx.symbolTable.getCurrentPackage());
                GlobalVariable.getGlobalIO(fullName);
            }

            // Create a Variable node
            return new OperatorNode(sigil, new IdentifierNode(varName, parser.tokenIndex), parser.tokenIndex);
        } else if (peek(parser).text.equals("{")) {
            // Handle curly brackets to parse a nested expression `${v}`
            TokenUtils.consume(parser); // Consume the '{'
            Node block = ParseBlock.parseBlock(parser); // Parse the block inside the curly brackets
            TokenUtils.consume(parser, LexerTokenType.OPERATOR, "}"); // Consume the '}'
            return new OperatorNode(sigil, ParserNodeUtils.toScalarContext(block), parser.tokenIndex);
        }

        // Not a variable name, not a block. This could be a dereference like @$a
        // Parse the expression with the appropriate precedence
        operand = parser.parseExpression(parser.getPrecedence("$") + 1);
        return new OperatorNode(sigil, operand, parser.tokenIndex);
    }

    /**
     * Parses a code reference variable, handling Perl's `&` code reference parsing rules.
     * This method is responsible for parsing expressions that start with `&`, which in Perl
     * can be used to refer to subroutines or to call them.
     *
     * @param parser
     * @param token  The lexer token representing the `&` operator.
     * @return A Node representing the parsed code reference or subroutine call.
     */
    static Node parseCoderefVariable(Parser parser, LexerToken token) {
        // Set a flag to allow parentheses after a variable, as in &$sub(...)
        parser.parsingForLoopVariable = true;
        // Parse the variable following the `&` sigil
        Node node = parseVariable(parser, token.text);
        // Reset the flag after parsing
        parser.parsingForLoopVariable = false;

        // If we are parsing a reference (e.g., \&sub), return the node without adding parameters
        if (parser.parsingTakeReference) {
            return node;
        }

        parser.ctx.logDebug("parse & node: " + node);

        // Check if the node is an OperatorNode with a BinaryOperatorNode operand
        if (node instanceof OperatorNode operatorNode) {
            if (operatorNode.operand instanceof BinaryOperatorNode binaryOperatorNode) {
                // If the operator is `(`, return the BinaryOperatorNode directly
                if (binaryOperatorNode.operator.equals("(")) {
                    return binaryOperatorNode;
                }
            }
        }

        Node list;
        // If the next token is not `(`, handle auto-call by transforming `&subr` to `&subr(@_)`
        if (!peek(parser).text.equals("(")) {
            list = atUnderscore(parser);
        } else {
            // Otherwise, parse the list of arguments
            list = ListParser.parseZeroOrMoreList(parser,
                    0,
                    false,
                    true,
                    false,
                    false);
        }

        // Handle cases where the node is an OperatorNode
        if (node instanceof OperatorNode operatorNode) {
            // If the operand is another OperatorNode, transform &$sub to $sub(@_)
            if (operatorNode.operand instanceof OperatorNode) {
                node = operatorNode.operand;
            } else if (operatorNode.operand instanceof BlockNode blockNode) {
                // If the operand is a BlockNode, transform &{$sub} to $sub(@_)
                node = blockNode;
            }
        }

        // Return a new BinaryOperatorNode representing the function call with arguments
        return new BinaryOperatorNode("(", node, list, parser.tokenIndex);
    }
}
