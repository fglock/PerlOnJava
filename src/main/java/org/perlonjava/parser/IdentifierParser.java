package org.perlonjava.parser;

import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;

public class IdentifierParser {

    /**
     * Parses a complex Perl identifier from the list of tokens, excluding the sigil.
     *
     * @param parser the parser object
     * @return The parsed identifier as a String, or null if there is no valid identifier.
     */
    public static String parseComplexIdentifier(Parser parser) {
        // Save the current token index to allow backtracking if needed
        int saveIndex = parser.tokenIndex;

        // Skip any leading whitespace
        parser.tokenIndex = Parser.skipWhitespace(parser.tokenIndex, parser.tokens);

        // Check if the identifier is enclosed in braces
        boolean insideBraces = false;
        if (parser.tokens.get(parser.tokenIndex).text.equals("{")) {
            insideBraces = true;
            parser.tokenIndex++; // Consume the opening brace
        }

        // Parse the identifier using the inner method
        String identifier = parseComplexIdentifierInner(parser);

        // If an identifier was found, and it was inside braces, ensure the braces are properly closed
        if (identifier != null && insideBraces) {
            // Skip any whitespace after the identifier
            parser.tokenIndex = Parser.skipWhitespace(parser.tokenIndex, parser.tokens);

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
        return identifier;
    }

    public static String parseComplexIdentifierInner(Parser parser) {
        parser.tokenIndex = Parser.skipWhitespace(parser.tokenIndex, parser.tokens);

        boolean isFirstToken = true;
        StringBuilder variableName = new StringBuilder();

        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
        while (true) {
            if (token.type == LexerTokenType.OPERATOR || token.type == LexerTokenType.NUMBER || token.type == LexerTokenType.STRING) {
                if (token.text.equals("$") && (nextToken.text.equals("$")
                        || nextToken.type == LexerTokenType.IDENTIFIER
                        || nextToken.type == LexerTokenType.NUMBER)) {
                    // `@$` `$$` can't be followed by `$` or name or number
                    return null;
                }
                if (token.text.equals("^") && nextToken.type == LexerTokenType.IDENTIFIER && Character.isUpperCase(nextToken.text.charAt(0))) {
                    // `$^` can be followed by an optional uppercase identifier: `$^A`
                    variableName.append(token.text);
                    variableName.append(nextToken.text);
                    parser.tokenIndex += 2;
                    return variableName.toString();
                }
                if (isFirstToken && token.type == LexerTokenType.NUMBER) {
                    // finish because $1 can't be followed by `::`
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

    public static String parseSubroutineIdentifier(Parser parser) {
        parser.tokenIndex = Parser.skipWhitespace(parser.tokenIndex, parser.tokens);
        StringBuilder variableName = new StringBuilder();
        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
        if (token.type == LexerTokenType.NUMBER) {
            return null;
        }
        while (true) {
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