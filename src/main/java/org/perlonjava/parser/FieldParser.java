package org.perlonjava.parser;

import org.perlonjava.astnode.*;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

/**
 * FieldParser handles parsing of field declarations in Perl classes.
 * 
 * Syntax:
 *   field $name;
 *   field $name = default_value;
 *   field $name :param;
 *   field $name :param :reader;
 *   field $name :param = default_value;
 *   field @array_field;
 *   field %hash_field;
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
        // Consume 'field' keyword
        TokenUtils.consume(parser, LexerTokenType.IDENTIFIER, "field");
        
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
        
        // Parse attributes (optional)
        while (TokenUtils.peek(parser).text.equals(":")) {
            parseFieldAttribute(parser, fieldPlaceholder);
        }
        
        // Parse default value (optional)
        token = TokenUtils.peek(parser);
        if (token.type == LexerTokenType.OPERATOR && token.text.equals("=")) {
            TokenUtils.consume(parser); // consume '='
            
            // Parse the default value expression
            Node defaultValue = parser.parseExpression(parser.getPrecedence(","));
            fieldPlaceholder.operand = defaultValue;
            fieldPlaceholder.setAnnotation("hasDefault", true);
        }
        
        // Consume statement terminator
        StatementResolver.parseStatementTerminator(parser);
        
        return fieldPlaceholder;
    }
    
    /**
     * Parses a field attribute like :param or :reader.
     * 
     * @param parser The parser instance
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
