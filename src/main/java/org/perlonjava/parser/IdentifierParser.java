package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

/**
 * The IdentifierParser class is responsible for parsing complex Perl identifiers
 * from a list of tokens, excluding the sigil (e.g., $, @, %).
 */
public class IdentifierParser {

    /**
     * Parses a complex Perl identifier from the list of tokens, excluding the sigil.
     * This method handles identifiers that may be enclosed in braces.
     *
     * @param parser The parser object containing the tokens and current parsing state.
     * @return The parsed identifier as a String, or null if there is no valid identifier.
     */
    public static String parseComplexIdentifier(Parser parser) {
        // Save the current token index to allow backtracking if needed
        int saveIndex = parser.tokenIndex;

        // Skip any leading whitespace to find the start of the identifier
        parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);

        // Check if the identifier is enclosed in braces
        boolean insideBraces = false;
        if (parser.tokens.get(parser.tokenIndex).text.equals("{")) {
            insideBraces = true;
            parser.tokenIndex++; // Consume the opening brace
        }

        // Parse the identifier using the inner method
        String identifier = parseComplexIdentifierInner(parser, insideBraces);

        // If an identifier was found, and it was inside braces, ensure the braces are properly closed
        if (identifier != null && insideBraces) {
            // Skip any whitespace after the identifier
            parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);

            // Check for the closing brace
            if (parser.tokens.get(parser.tokenIndex).text.equals("}")) {
                parser.tokenIndex++; // Consume the closing brace
                return identifier;
            } else {
                // If the closing brace is not found, backtrack to the saved index
                // This indicates that we found `${expression}` instead of `${identifier}`
                parser.tokenIndex = saveIndex;
                return null;
            }
        }

        // Return the parsed identifier, or null if no valid identifier was found
        if (identifier == null) {
            parser.tokenIndex = saveIndex;
        }
        return identifier;
    }

    /**
     * Parses the inner part of a complex identifier, handling cases where the identifier
     * may be enclosed in braces.
     *
     * @param parser       The parser object containing the tokens and current parsing state.
     * @param insideBraces A boolean indicating if the identifier is enclosed in braces.
     * @return The parsed identifier as a String, or null if there is no valid identifier.
     */
    public static String parseComplexIdentifierInner(Parser parser, boolean insideBraces) {
        // Skip any leading whitespace to find the start of the identifier
        parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);

        boolean isFirstToken = true;
        StringBuilder variableName = new StringBuilder();

        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
        while (true) {
            // Check for various token types that can form part of an identifier
            if (token.type == LexerTokenType.OPERATOR || token.type == LexerTokenType.NUMBER || token.type == LexerTokenType.STRING) {
                if (token.text.equals("{")) {
                    // Finish because `${` is not a valid name (but `$[` is)
                    return variableName.toString();
                }
                if (token.text.equals("$") && (nextToken.text.equals("$")
                        || nextToken.type == LexerTokenType.IDENTIFIER
                        || nextToken.type == LexerTokenType.NUMBER)) {
                    // `@$` `$$` can't be followed by `$` or name or number
                    return null;
                }
                if (token.text.equals("^") && nextToken.type == LexerTokenType.IDENTIFIER && Character.isUpperCase(nextToken.text.charAt(0))) {
                    // `$^` can be followed by an optional uppercase identifier: `$^A`
                    //  ^A is control-A char(1)
                    TokenUtils.consume(parser); // consume the ^
                    parser.ctx.logDebug("parse $^ at token " + TokenUtils.peek(parser).text);
                    //  `$^LAST_FH` is parsed as `$^L` + `AST_FH`
                    //  `${^LAST_FH}` is parsed as `${^LAST_FH}`
                    String str = insideBraces
                            ? TokenUtils.consume(parser).text
                            : TokenUtils.consumeChar(parser);
                    variableName.append(Character.toString(str.charAt(0) - 'A' + 1)).append(str.substring(1));

                    return variableName.toString();
                }
                if (isFirstToken && token.type == LexerTokenType.NUMBER) {
                    // Finish because $1 can't be followed by `::`
                    variableName.append(token.text);
                    parser.tokenIndex++;
                    return variableName.toString();
                }
                if (!token.text.equals("::") && !(token.type == LexerTokenType.NUMBER)) {
                    // `::` or number can continue the loop
                    // XXX STRING token type needs more work (Unicode, control characters)
                    variableName.append(token.text);
                    parser.tokenIndex++;
                    return variableName.toString();
                }
            } else if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF || token.type == LexerTokenType.NEWLINE) {
                return variableName.toString();
            }
            isFirstToken = false;
            variableName.append(token.text);

            if ((token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER)
                    && (!nextToken.text.equals("::"))
            ) {
                parser.tokenIndex++;
                return variableName.toString();
            }

            parser.tokenIndex++;
            token = parser.tokens.get(parser.tokenIndex);
            nextToken = parser.tokens.get(parser.tokenIndex + 1);
        }
    }

    /**
     * Parses a subroutine identifier from the list of tokens.
     *
     * @param parser The parser object containing the tokens and current parsing state.
     * @return The parsed subroutine identifier as a String, or null if there is no valid identifier.
     */
    public static String parseSubroutineIdentifier(Parser parser) {
        // Skip any leading whitespace to find the start of the identifier
        parser.tokenIndex = Whitespace.skipWhitespace(parser.tokenIndex, parser.tokens);
        StringBuilder variableName = new StringBuilder();
        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
        if (token.type == LexerTokenType.NUMBER) {
            return null;
        }
        while (true) {
            // Check for various token types that can form part of a subroutine identifier
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF || token.type == LexerTokenType.NEWLINE || (token.type == LexerTokenType.OPERATOR && !token.text.equals("::"))) {
                return variableName.toString();
            }
            variableName.append(token.text);
            if ((token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER)
                    && (!nextToken.text.equals("::"))
            ) {
                parser.tokenIndex++;
                return variableName.toString();
            }
            parser.tokenIndex++;
            token = parser.tokens.get(parser.tokenIndex);
            nextToken = parser.tokens.get(parser.tokenIndex + 1);
        }
    }
}
