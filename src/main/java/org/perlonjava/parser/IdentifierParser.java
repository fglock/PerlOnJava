package org.perlonjava.parser;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.perlmodule.Strict;
import org.perlonjava.runtime.PerlCompilerException;

import java.nio.charset.StandardCharsets;

/**
 * The IdentifierParser class is responsible for parsing complex Perl identifiers
 * from a list of tokens, excluding the sigil (e.g., $, @, %).
 */
public class IdentifierParser {

    private static boolean isIdentifierTooLong(StringBuilder variableName, boolean isTypeglob) {
        // perl5_t/t/comp/parser.t builds boundary cases using UTF-8 byte length.
        // With 4-byte UTF-8 identifier characters, the boundary is 255 * 4 = 1020 bytes.
        // Perl has a slightly different boundary for typeglob identifiers:
        //   - $ / @ / % / & / $# contexts: 1020 bytes is already too long
        //   - * (typeglob) context: 1020 bytes is allowed; only > 1020 is too long
        int byteLen = variableName.toString().getBytes(StandardCharsets.UTF_8).length;
        return isTypeglob ? byteLen > 1020 : byteLen >= 1020;
    }

    /**
     * Parses a complex Perl identifier from the list of tokens, excluding the sigil.
     * This method handles identifiers that may be enclosed in braces.
     *
     * @param parser The parser object containing the tokens and current parsing state.
     * @return The parsed identifier as a String, or null if there is no valid identifier.
     */
    public static String parseComplexIdentifier(Parser parser) {
        return parseComplexIdentifier(parser, false);
    }

    public static String parseComplexIdentifier(Parser parser, boolean isTypeglob) {
        // Save the current token index to allow backtracking if needed
        int saveIndex = parser.tokenIndex;

        // Skip horizontal whitespace to find the start of the identifier
        // (do not skip NEWLINE; "$\n" must be a syntax error)
        int afterWs = parser.tokenIndex;
        while (afterWs < parser.tokens.size() && parser.tokens.get(afterWs).type == LexerTokenType.WHITESPACE) {
            afterWs++;
        }
        boolean skippedWhitespace = afterWs != parser.tokenIndex;
        parser.tokenIndex = afterWs;

        // Whitespace between sigil and an identifier is allowed in Perl (e.g. "$ var"),
        // but whitespace characters themselves are not valid length-1 variable names.
        // If we consumed whitespace and the following token does not look like an identifier,
        // treat it as a syntax error (e.g. "$\t", "$ ", "$\n").
        if (skippedWhitespace) {
            LexerToken tokenAfter = parser.tokens.get(parser.tokenIndex);
            if (tokenAfter.type == LexerTokenType.EOF || tokenAfter.type == LexerTokenType.NEWLINE) {
                parser.throwError("syntax error");
            }

            // Perl does not allow whitespace to turn into a punctuation special variable.
            // For example "$\t = 4" must be a syntax error, not "$= 4".
            if (tokenAfter.type == LexerTokenType.OPERATOR
                    && tokenAfter.text.length() == 1
                    && "!|/*+-<>&~.=%'".indexOf(tokenAfter.text.charAt(0)) >= 0) {
                parser.throwError("syntax error");
            }
        }

        // Check if the identifier is enclosed in braces
        boolean insideBraces = false;
        if (parser.tokens.get(parser.tokenIndex).text.equals("{")) {
            insideBraces = true;
            parser.tokenIndex++; // Consume the opening brace
        }

        // Parse the identifier using the inner method
        String identifier = parseComplexIdentifierInner(parser, insideBraces, isTypeglob);

        // If an identifier was found, and it was inside braces, ensure the braces are properly closed
        if (identifier != null && insideBraces) {
            // Skip any whitespace after the identifier
            parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);

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
     * Helper method to check if a single quote can be treated as a package separator.
     * It should only be a separator when preceded by an identifier/number and followed by an identifier.
     *
     * @param parser       The parser object
     * @param variableName The identifier built so far
     * @return true if the single quote should be treated as a package separator
     */
    private static boolean isSingleQuotePackageSeparator(Parser parser, StringBuilder variableName) {
        // Single quote is only a package separator if:
        // 1. We have something before it (not at the start)
        // 2. The next token is an identifier or number that can continue the name
        if (variableName.length() == 0) {
            return false;
        }

        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);

        // Check if next token can be part of an identifier
        return nextToken.type == LexerTokenType.IDENTIFIER || nextToken.type == LexerTokenType.NUMBER;
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
        return parseComplexIdentifierInner(parser, insideBraces, false);
    }

    public static String parseComplexIdentifierInner(Parser parser, boolean insideBraces, boolean isTypeglob) {
        // Perl allows whitespace between the sigil and the variable name (e.g. "$ a" parses as "$a").
        // But if whitespace is skipped and the next token is not a valid identifier start (e.g. "$\t = 4"),
        // the variable name is missing and we should trigger a plain "syntax error".
        int wsStart = parser.tokenIndex;
        // Skip horizontal whitespace to find the start of the identifier.
        // Do not skip NEWLINE here: "$\n" is not a valid variable name.
        while (parser.tokenIndex < parser.tokens.size()
                && parser.tokens.get(parser.tokenIndex).type == LexerTokenType.WHITESPACE) {
            parser.tokenIndex++;
        }
        boolean skippedWhitespace = parser.tokenIndex != wsStart;

        boolean isFirstToken = true;
        StringBuilder variableName = new StringBuilder();

        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);

        if (skippedWhitespace) {
            // Perl allows "$ a" (whitespace before an identifier). But if whitespace is followed by
            // something that cannot start an identifier (e.g. "$\t = 4"), Perl reports a syntax error.
            // Signal "missing variable name" to the caller by returning the empty string.
            if (token.type != LexerTokenType.IDENTIFIER
                    && token.type != LexerTokenType.NUMBER
                    && token.type != LexerTokenType.STRING) {
                return "";
            }
        }

        // Special case: Handle ellipsis inside braces - ${...} should be parsed as a block, not as ${.}
        if (insideBraces && token.type == LexerTokenType.OPERATOR && token.text.equals("...")) {
            // Return null to force fallback to block parsing for ellipsis inside braces
            return null;
        }

        // Special case for special variables like `$|`, `$'`, etc.
        char firstChar = token.text.charAt(0);
        if (token.type == LexerTokenType.OPERATOR && "!|/*+-<>&~.=%'".indexOf(firstChar) >= 0) {
            // Check if this is a leading single quote followed by an identifier ($'foo means $main::foo)
            if (firstChar == '\'' && (nextToken.type == LexerTokenType.IDENTIFIER || nextToken.type == LexerTokenType.NUMBER)) {
                // This is $'foo which means $main::foo
                // We convert it to ::foo internally (leading :: means main::)
                variableName.append("::");
                parser.tokenIndex++;
                token = parser.tokens.get(parser.tokenIndex);
                nextToken = parser.tokens.get(parser.tokenIndex + 1);
                // Continue to parse the rest of the identifier - fall through to main loop
            } else {
                // Either it's a special variable like $' (postmatch), $| (autoflush), etc.
                // Consume the character from the token (which might be "|=" or just "|")
                variableName.append(TokenUtils.consumeChar(parser));
                return variableName.toString(); // Returns "'" for $', "|" for $|, etc.
            }
        }

        // FIXED: Explicitly reject WHITESPACE tokens as invalid identifier starts
        if (token.type == LexerTokenType.WHITESPACE) {
            int cp = token.text.codePointAt(0);
            String hex = cp > 255
                    ? "\\x{" + Integer.toHexString(cp) + "}"
                    : String.format("\\x%02x", cp);
            throw new PerlCompilerException("Unrecognized character " + hex + ";");
        }

        if (token.type == LexerTokenType.STRING) {
            // Assert valid Unicode start of identifier - \p{XID_Start}
            String id = token.text;
            int cp = id.codePointAt(0);
            boolean valid = cp == '_' || UCharacter.hasBinaryProperty(cp, UProperty.XID_START);

            // Under 'no utf8', Perl allows many non-ASCII bytes as length-1 variables.
            // Only enforce XID_START there for multi-character identifiers.
            boolean utf8Enabled = parser.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_UTF8)
                    && !parser.ctx.compilerOptions.isEvalbytes;
            boolean hasMoreIdentifierContent = insideBraces
                    && (nextToken.type == LexerTokenType.IDENTIFIER || nextToken.type == LexerTokenType.NUMBER);
            boolean mustValidateStart = utf8Enabled || id.length() > 1 || hasMoreIdentifierContent;

            // Always reject the Unicode replacement character: it usually indicates an invalid byte sequence.
            // Perl reports these as unrecognized bytes (e.g. \xB6 in comp/parser_run.t test 66).
            // Also reject control characters (0x00-0x1F, 0x7F) as identifier starts.
            // Reject control characters and other non-graphic bytes that Perl treats as invalid variable names.
            // In particular, C1 controls (0x80-0x9F) must always be rejected.
            if (cp == 0xFFFD
                    || cp < 32
                    || cp == 127
                    || (cp >= 0x80 && cp <= 0x9F)
                    || (mustValidateStart && !valid)) {
                String hex;
                // Special case: if we got the Unicode replacement character (0xFFFD),
                // it likely means the original was an invalid UTF-8 byte sequence.
                // For Perl compatibility, we should report a representative invalid byte.
                if (cp == 0xFFFD) {
                    hex = "\\xB6";
                } else {
                    if (cp <= 255) {
                        // Perl tends to report non-ASCII bytes as \x{..} in these contexts
                        hex = "\\x{" + Integer.toHexString(cp) + "}";
                    } else {
                        hex = "\\x{" + Integer.toHexString(cp) + "}";
                    }
                }
                // Use clean error message format to match Perl's exact format
                parser.throwCleanError("Unrecognized character " + hex + "; marked by <-- HERE after ${ <-- HERE near column 4");
            }
        }

        if (insideBraces && token.type == LexerTokenType.IDENTIFIER) {
            // Some invalid bytes can be tokenized as IDENTIFIER (e.g. U+FFFD replacement).
            // Validate start char in the same way as for STRING tokens so we can emit the
            // expected Perl diagnostic (comp/parser_run.t test 66).
            String id = token.text;
            if (!id.isEmpty()) {
                int cp = id.codePointAt(0);
                boolean valid = cp == '_' || UCharacter.hasBinaryProperty(cp, UProperty.XID_START);

                boolean utf8Enabled = parser.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_UTF8)
                        && !parser.ctx.compilerOptions.isEvalbytes;
                boolean mustValidateStart = utf8Enabled || id.length() > 1;

                if (mustValidateStart && !valid) {
                    String hex;
                    if (cp == 0xFFFD) {
                        hex = "\\xB6";
                    } else if (cp <= 255) {
                        hex = String.format("\\\\x%02X", cp);
                    } else {
                        hex = "\\x{" + Integer.toHexString(cp) + "}";
                    }
                    parser.throwCleanError("Unrecognized character " + hex + "; marked by <-- HERE after ${ <-- HERE near column 4");
                }
            }
        }

        while (true) {
            // Check for various token types that can form part of an identifier
            if (token.type == LexerTokenType.OPERATOR || token.type == LexerTokenType.NUMBER || token.type == LexerTokenType.STRING) {
                if (token.text.equals("{")) {
                    String prefix = variableName.toString();
                    if (prefix.isEmpty()) {
                        // `${` is not a valid name
                        return null;
                    }
                    return variableName.toString();
                }
                if (token.text.equals(";")) {
                    String prefix = variableName.toString();
                    if (prefix.equals("")) {
                        // `$;` is a valid name
                        variableName.append(token.text);
                        parser.tokenIndex++;
                        return variableName.toString();
                    }
                    return prefix;
                }
                if (token.text.equals("$") && (nextToken.text.equals("$")
                        || nextToken.type == LexerTokenType.IDENTIFIER
                        || nextToken.type == LexerTokenType.NUMBER)
                        || nextToken.text.equals("::")) {
                    // `@$` can't be followed by `$`, `::`, name or number
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

                // Handle single quote as package separator (legacy Perl syntax)
                if (token.text.equals("'") && isSingleQuotePackageSeparator(parser, variableName)) {
                    // Convert ' to :: for internal representation
                    variableName.append("::");
                    parser.tokenIndex++;

                    // Skip whitespace after '
                    parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);

                    // Update token references
                    token = parser.tokens.get(parser.tokenIndex);
                    nextToken = parser.tokens.get(parser.tokenIndex + 1);

                    // After ', only identifiers or another separator are allowed
                    if (token.type != LexerTokenType.IDENTIFIER && !token.text.equals("::") && !token.text.equals("'")) {
                        // Nothing valid follows ', so return what we have
                        return variableName.toString();
                    }
                    // Continue the loop to process the next token
                    continue;
                }

                if (token.text.equals("::")) {
                    // Handle :: specially
                    variableName.append(token.text);
                    parser.tokenIndex++;

                    // Skip whitespace after ::
                    parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);

                    // Check what follows ::
                    token = parser.tokens.get(parser.tokenIndex);
                    nextToken = parser.tokens.get(parser.tokenIndex + 1);

                    // After ::, only identifiers or another :: are allowed (or ' as package separator)
                    if (token.type != LexerTokenType.IDENTIFIER && !token.text.equals("::") && !token.text.equals("'")) {
                        // Nothing valid follows ::, so return what we have
                        return variableName.toString();
                    }
                    // Continue the loop to process the next token
                    continue;
                }
                if (!(token.type == LexerTokenType.NUMBER)) {
                    // Not ::, not ', and not a number, so this is the end
                    // Validate STRING tokens to reject control characters
                    if (token.type == LexerTokenType.STRING) {
                        String id = token.text;
                        if (!id.isEmpty()) {
                            int cp = id.codePointAt(0);
                            // Reject control characters (0x00-0x1F, 0x7F) and replacement char
                            if (cp < 32 || cp == 127 || cp == 0xFFFD) {
                                String hex = cp <= 255 ? String.format("\\x{%02X}", cp) : "\\x{" + Integer.toHexString(cp) + "}";
                                throw new PerlCompilerException("Unrecognized character " + hex + ";");
                            }
                        }
                    }
                    
                    variableName.append(token.text);

                    // Check identifier length limit (Perl's limit is around 251 characters)
                    if (isIdentifierTooLong(variableName, isTypeglob)) {
                        parser.throwCleanError("Identifier too long");
                    }

                    parser.tokenIndex++;
                    return variableName.toString();
                }
            } else if (token.type == LexerTokenType.IDENTIFIER) {
                // Handle identifiers
                variableName.append(token.text);

                // Check identifier length limit (Perl's limit is around 251 characters)
                if (isIdentifierTooLong(variableName, isTypeglob)) {
                    parser.throwCleanError("Identifier too long");
                }

                // Check if the next token is a valid separator
                boolean hasDoubleColon = nextToken.text.equals("::");
                boolean hasSingleQuote = false;

                if (nextToken.text.equals("'")) {
                    // Look ahead to see what follows the '
                    LexerToken afterQuote = parser.tokens.get(parser.tokenIndex + 2);
                    if (afterQuote.type == LexerTokenType.IDENTIFIER || afterQuote.type == LexerTokenType.NUMBER) {
                        hasSingleQuote = true;
                    }
                }

                if (!hasDoubleColon && !hasSingleQuote) {
                    parser.tokenIndex++;
                    return variableName.toString();
                }

                // :: or ' follows, so continue parsing
                parser.tokenIndex++;
                token = parser.tokens.get(parser.tokenIndex);
                nextToken = parser.tokens.get(parser.tokenIndex + 1);
                continue;
            } else if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF || token.type == LexerTokenType.NEWLINE) {
                return variableName.toString();
            } else {
                // Any other token type ends the identifier
                return variableName.toString();
            }

            isFirstToken = false;

            // For NUMBER tokens that aren't first token
            if (token.type == LexerTokenType.NUMBER) {
                variableName.append(token.text);

                // Check identifier length limit (Perl's limit is around 251 characters)
                if (isIdentifierTooLong(variableName, isTypeglob)) {
                    parser.throwCleanError("Identifier too long");
                }

                if (!nextToken.text.equals("::") && !nextToken.text.equals("'")) {
                    parser.tokenIndex++;
                    return variableName.toString();
                }
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
        parser.tokenIndex = Whitespace.skipWhitespace(parser, parser.tokenIndex, parser.tokens);
        StringBuilder variableName = new StringBuilder();
        LexerToken token = parser.tokens.get(parser.tokenIndex);
        LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);

        // Track if we're at the start of the identifier
        boolean isFirstToken = true;

        // Handle leading ' (old-style package separator meaning main::)
        if (isFirstToken && token.text.equals("'")) {
            // Leading ' means main:: (e.g., 'Hello'_he_said means main::Hello::_he_said)
            variableName.append("::");  // Leading :: means main::
            parser.tokenIndex++;
            token = parser.tokens.get(parser.tokenIndex);
            nextToken = parser.tokens.get(parser.tokenIndex + 1);
            isFirstToken = false;  // We've consumed the leading '
            // Continue to parse the rest
        }

        // Numbers are not allowed at the very beginning (unless after a leading ' or ::)
        if (isFirstToken && token.type == LexerTokenType.NUMBER) {
            return null;
        }

        while (true) {
            // Check for various token types that can form part of a subroutine identifier
            if (token.type == LexerTokenType.WHITESPACE || token.type == LexerTokenType.EOF ||
                    token.type == LexerTokenType.NEWLINE ||
                    (token.type == LexerTokenType.OPERATOR && !token.text.equals("::") && !token.text.equals("'"))) {
                return variableName.toString();
            }

            // Handle single quote as package separator in subroutine names
            if (token.text.equals("'") && variableName.length() > 0) {
                // Check if next token can continue the identifier
                if (nextToken.type == LexerTokenType.IDENTIFIER || nextToken.type == LexerTokenType.NUMBER) {
                    // Convert ' to :: for internal representation
                    variableName.append("::");
                    parser.tokenIndex++;
                    token = parser.tokens.get(parser.tokenIndex);
                    nextToken = parser.tokens.get(parser.tokenIndex + 1);
                    continue;
                } else {
                    // Single quote not followed by valid identifier part
                    return variableName.toString();
                }
            }

            // Append the current token
            variableName.append(token.text);

            // Mark that we're no longer at the first token
            if (isFirstToken) {
                isFirstToken = false;
            }

            // If this is a :: operator, continue to next token
            if (token.text.equals("::")) {
                parser.tokenIndex++;
                token = parser.tokens.get(parser.tokenIndex);
                nextToken = parser.tokens.get(parser.tokenIndex + 1);
                
                // Validate that what follows :: is a valid identifier start
                // Allow EOF or closing tokens for package names that end with ::
                if (token.type != LexerTokenType.IDENTIFIER && token.type != LexerTokenType.NUMBER && 
                    !token.text.equals("'") && !token.text.equals("::") && !token.text.equals("->") &&
                    token.type != LexerTokenType.EOF &&
                    !(token.type == LexerTokenType.OPERATOR && (token.text.equals("}") || token.text.equals(";") || token.text.equals("=") || token.text.equals(")")))) {
                    // Bad name after ::
                    parser.throwCleanError("Bad name after " + variableName.toString() + "::");
                }
                continue;
            }

            // If current token is IDENTIFIER or NUMBER
            if (token.type == LexerTokenType.IDENTIFIER || token.type == LexerTokenType.NUMBER) {
                // If next token is :: or ', continue parsing
                if (nextToken.text.equals("::")) {
                    parser.tokenIndex++;
                    token = parser.tokens.get(parser.tokenIndex);
                    nextToken = parser.tokens.get(parser.tokenIndex + 1);
                    continue;
                }

                if (nextToken.text.equals("'")) {
                    // Look ahead to see what follows the '
                    LexerToken afterQuote = parser.tokens.get(parser.tokenIndex + 2);
                    if (afterQuote.type == LexerTokenType.IDENTIFIER || afterQuote.type == LexerTokenType.NUMBER) {
                        // ' is a package separator, continue parsing
                        parser.tokenIndex++;
                        token = parser.tokens.get(parser.tokenIndex);
                        nextToken = parser.tokens.get(parser.tokenIndex + 1);
                        continue;
                    } else {
                        // Bad name after '
                        parser.throwCleanError("Bad name after " + variableName.toString() + "'");
                    }
                }

                // If current token is NUMBER and next token is IDENTIFIER (like "5" followed by "p_4p1s")
                // This handles cases where an identifier segment after :: starts with a number
                if (token.type == LexerTokenType.NUMBER && nextToken.type == LexerTokenType.IDENTIFIER) {
                    parser.tokenIndex++;
                    token = parser.tokens.get(parser.tokenIndex);
                    nextToken = parser.tokens.get(parser.tokenIndex + 1);
                    continue;  // Continue to append the IDENTIFIER part
                }

                // Otherwise, we've reached the end of the identifier
                parser.tokenIndex++;
                return variableName.toString();
            }

            parser.tokenIndex++;
            token = parser.tokens.get(parser.tokenIndex);
            nextToken = parser.tokens.get(parser.tokenIndex + 1);
        }
    }

    static void validateIdentifier(Parser parser, String varName, int startIndex) {
        if (varName.startsWith("0") && varName.length() > 1) {
            parser.throwCleanError("Numeric variables with more than one digit may not start with '0'");
        }

        // Check for non-ASCII characters in variable names under 'no utf8'
        if (!parser.ctx.symbolTable.isStrictOptionEnabled(Strict.HINT_UTF8)) {
            // Under 'no utf8', perl5 still accepts valid Unicode identifiers when the source is
            // already Unicode (e.g. eval() of a UTF-8 string). What must be rejected are invalid
            // sequences that decode to U+FFFD (replacement character).
            if (varName.length() > 1 && varName.indexOf('\uFFFD') >= 0) {
                parser.tokenIndex = startIndex;
                int lastCp = varName.codePointBefore(varName.length());
                parser.throwError("Unrecognized character \\x{" + Integer.toHexString(lastCp) + "}");
            }
        }
    }
}