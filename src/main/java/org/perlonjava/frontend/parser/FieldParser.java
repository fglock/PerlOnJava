package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * FieldParser handles parsing of field declarations in Perl classes.
 * <p>
 * Syntax:
 * field $name;
 * field $name = default_value;
 * field $name :param;
 * field $name :param :reader;
 * field $name :param = default_value;
 * field @array_field;
 * field %hash_field;
 */
public class FieldParser {

    /**
     * Parses a field declaration and stores it as metadata.
     * Returns a comment node that will be replaced during class transformation.
     *
     * @param parser The parser instance
     * @return A comment node placeholder for the field
     */
    public static Node parseFieldDeclaration(Parser parser) {
        int index = parser.tokenIndex;
        // Consume 'field' keyword
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER, "field");

        // Emit experimental warning for 'field' if warnings are enabled
        if (parser.ctx.symbolTable.isWarningCategoryEnabled("experimental::class")) {
            try {
                WarnDie.warn(
                        new RuntimeScalar("field is experimental"),
                        new RuntimeScalar(parser.ctx.errorUtil.warningLocation(index))
                );
            } catch (Exception e) {
                // If warning system isn't initialized yet, fall back to System.err
                System.err.println("field is experimental" + parser.ctx.errorUtil.warningLocation(index) + ".");
            }
        }

        // Parse the field variable (sigil + name)
        LexerToken token = TokenUtils.peek(parser);

        // Check for sigil
        String sigil = null;
        if (token.type == LexerTokenType.OPERATOR) {
            if (token.text.equals("$") || token.text.equals("@") || token.text.equals("%")) {
                sigil = token.text;
                TokenUtils.consume(parser);
            } else {
                throw new PerlCompilerException(parser.tokenIndex,
                        "Expected field variable after 'field' keyword", parser.ctx.errorUtil);
            }
        } else {
            throw new PerlCompilerException(parser.tokenIndex,
                    "Expected field variable after 'field' keyword", parser.ctx.errorUtil);
        }

        // Parse field name
        token = TokenUtils.peek(parser);
        if (token.type != LexerTokenType.IDENTIFIER) {
            throw new PerlCompilerException(parser.tokenIndex,
                    "Expected field name after sigil", parser.ctx.errorUtil);
        }
        String fieldName = token.text;
        TokenUtils.consume(parser);

        // Create a placeholder node with field information as annotations
        // This will be transformed when the class block is complete
        OperatorNode fieldPlaceholder = new OperatorNode("field",
                new IdentifierNode(fieldName, parser.tokenIndex), parser.tokenIndex);

        // Store field metadata as annotations
        fieldPlaceholder.setAnnotation("sigil", sigil);
        fieldPlaceholder.setAnnotation("name", fieldName);

        // Register field in symbol table with special "field:" prefix
        // This allows us to track fields separately from regular variables
        // and avoid shadowing issues
        String fieldSymbol = "field:" + fieldName;
        parser.ctx.symbolTable.addVariable(fieldSymbol, "field", fieldPlaceholder);

        // Also register with the sigil so the parse-time strict vars check
        // can find the field variable in later field default expressions
        // (e.g., field $two = $one + 1; needs to see $one)
        parser.ctx.symbolTable.addVariable(sigil + fieldName, "field", fieldPlaceholder);

        // Also register in global FieldRegistry for inheritance tracking
        String currentClass = parser.ctx.symbolTable.getCurrentPackage();
        FieldRegistry.registerField(currentClass, fieldName);

        // Parse attributes (optional)
        while (TokenUtils.peek(parser).text.equals(":")) {
            parseFieldAttribute(parser, fieldPlaceholder);
        }

        // Parse default value (optional)
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR) {
            String operator = token.text;

            // Support different assignment operators:
            // = for standard assignment
            // //= for defined-or assignment (assign if undefined)
            // ||= for logical-or assignment (assign if false/empty)
            if (operator.equals("=") || operator.equals("//=") || operator.equals("||=")) {
                TokenUtils.consume(parser); // consume the operator

                // Parse the default value expression
                Node defaultValue = parser.parseExpression(parser.getPrecedence(","));
                fieldPlaceholder.operand = defaultValue;
                fieldPlaceholder.setAnnotation("hasDefault", true);
                fieldPlaceholder.setAnnotation("defaultOperator", operator);
            }
        }

        // Consume statement terminator
        StatementResolver.parseStatementTerminator(parser);

        return fieldPlaceholder;
    }

    /**
     * Parses a field attribute like :param or :reader.
     *
     * @param parser    The parser instance
     * @param fieldNode The operator node with field annotations
     */
    private static void parseFieldAttribute(Parser parser, OperatorNode fieldNode) {
        // Consume ':'
        TokenUtils.consume(parser, LexerTokenType.OPERATOR, ":");

        // Get attribute name
        LexerToken token = TokenUtils.peek(parser);
        if (token.type != LexerTokenType.IDENTIFIER) {
            throw new PerlCompilerException(parser.tokenIndex,
                    "Expected attribute name after ':'", parser.ctx.errorUtil);
        }

        String attrName = token.text;
        TokenUtils.consume(parser);

        // Check for attribute value in parentheses
        String attrValue = "";
        if (TokenUtils.peek(parser).text.equals("(")) {
            TokenUtils.consume(parser); // consume '('

            // Check for empty parens
            if (!TokenUtils.peek(parser).text.equals(")")) {
                // Parse attribute value (usually an identifier)
                token = TokenUtils.peek(parser);
                if (token.type == LexerTokenType.IDENTIFIER) {
                    attrValue = token.text;
                    TokenUtils.consume(parser);
                } else {
                    throw new PerlCompilerException(parser.tokenIndex,
                            "Expected identifier in attribute value", parser.ctx.errorUtil);
                }
            }

            TokenUtils.consume(parser, LexerTokenType.OPERATOR, ")");
        }

        // Add attribute to field node as annotation
        fieldNode.setAnnotation("attr:" + attrName, attrValue);
    }
}
