package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.NumberNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;

/**
 * This class provides methods for parsing numbers in various formats.
 * It handles integer, fractional, and exponential notations, as well as
 * binary, octal, and hexadecimal representations.
 */
public class NumberParser {

    /**
     * Parses a number token and returns a NumberNode.
     *
     * @param parser The Parser instance.
     * @param token  The LexerToken representing the number.
     * @return A NumberNode representing the parsed number.
     */
    public static NumberNode parseNumber(Parser parser, LexerToken token) {
        StringBuilder number = new StringBuilder(token.text);

        // Check for binary, octal, or hexadecimal prefixes
        if (token.text.startsWith("0")) {
            if (token.text.length() == 1) {
                String letter = parser.tokens.get(parser.tokenIndex).text;
                char secondChar = letter.charAt(0);
                if (secondChar == 'b' || secondChar == 'B') {
                    // Binary number: 0b...
                    TokenUtils.consume(parser);
                    int num = Integer.parseInt(letter.substring(1), 2);
                    return new NumberNode(Integer.toString(num), parser.tokenIndex);
                } else if (secondChar == 'x' || secondChar == 'X') {
                    // Hexadecimal number: 0x...
                    TokenUtils.consume(parser);
                    int num = Integer.parseInt(letter.substring(1), 16);
                    return new NumberNode(Integer.toString(num), parser.tokenIndex);
                }
            } else {
                // Octal number: 0...
                int num = Integer.parseInt(token.text, 8);
                return new NumberNode(Integer.toString(num), parser.tokenIndex);
            }
        }

        // Check for fractional part
        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            number.append(TokenUtils.consume(parser).text); // consume '.'
            if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(TokenUtils.consume(parser).text); // consume digits after '.'
            }
        }
        // Check for exponent part
        checkNumberExponent(parser, number);

        return new NumberNode(number.toString(), parser.tokenIndex);
    }

    /**
     * Parses a fractional number starting with a decimal point.
     *
     * @param parser The Parser instance.
     * @return A Node representing the parsed fractional number.
     * @throws PerlCompilerException if the syntax is invalid.
     */
    public static Node parseFractionalNumber(Parser parser) {
        StringBuilder number = new StringBuilder("0.");

        LexerToken token = parser.tokens.get(parser.tokenIndex++);
        if (token.type != LexerTokenType.NUMBER) {
            throw new PerlCompilerException(parser.tokenIndex, "Syntax error", parser.ctx.errorUtil);
        }

        number.append(token.text); // consume digits after '.'
        // Check for exponent part
        checkNumberExponent(parser, number);
        return new NumberNode(number.toString(), parser.tokenIndex);
    }

    /**
     * Checks for and parses the exponent part of a number.
     *
     * @param parser The Parser instance.
     * @param number The StringBuilder containing the number being parsed.
     * @throws PerlCompilerException if the exponent is malformed.
     */
    public static void checkNumberExponent(Parser parser, StringBuilder number) {
        // Check for exponent part
        String exponentPart = TokenUtils.peek(parser).text;
        if (exponentPart.startsWith("e") || exponentPart.startsWith("E")) {
            TokenUtils.consume(parser); // consume 'e' or 'E' and possibly more 'E10'

            // Check if the rest of the token contains digits (e.g., "E10")
            int index = 1;
            for (; index < exponentPart.length(); index++) {
                if (!Character.isDigit(exponentPart.charAt(index)) && exponentPart.charAt(index) != '_') {
                    throw new PerlCompilerException(parser.tokenIndex, "Malformed number", parser.ctx.errorUtil);
                }
            }
            number.append(exponentPart);

            // If the exponent part was not fully consumed, check for separate tokens
            if (index == 1) {
                // Check for optional sign
                if (parser.tokens.get(parser.tokenIndex).text.equals("-") || parser.tokens.get(parser.tokenIndex).text.equals("+")) {
                    number.append(TokenUtils.consume(parser).text); // consume '-' or '+'
                }

                // Consume exponent digits
                number.append(TokenUtils.consume(parser, LexerTokenType.NUMBER).text);
            }
        }
    }
}