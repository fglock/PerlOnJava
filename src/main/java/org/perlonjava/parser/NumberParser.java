package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.NumberNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * This class provides methods for parsing numbers in various formats.
 * It handles integer, fractional, and exponential notations, as well as
 * binary, octal, and hexadecimal representations.
 */
public class NumberParser {

    public static final int MAX_NUMIFICATION_CACHE_SIZE = 1000;

    /**
     * A cache for storing parsed numbers to improve performance by avoiding
     * repeated parsing of the same number strings.
     */
    public static final Map<String, RuntimeScalar> numificationCache = new LinkedHashMap<String, RuntimeScalar>(MAX_NUMIFICATION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeScalar> eldest) {
            return size() > MAX_NUMIFICATION_CACHE_SIZE;
        }
    };

    /**
     * Parses a number token and returns a NumberNode.
     *
     * @param parser The Parser instance.
     * @param token  The LexerToken representing the number.
     * @return A NumberNode representing the parsed number.
     */
    public static Node parseNumber(Parser parser, LexerToken token) {
        String firstPart = token.text;
        int currentIndex = parser.tokenIndex;
        StringBuilder number = new StringBuilder(firstPart);

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

        // Check for v-string (extra dot followed by a number)
        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
            if (nextToken.type == LexerTokenType.NUMBER) {
                // It's a v-string, use StringParser to parse it
                parser.tokenIndex = currentIndex; // backtrack to the start of the number
                return StringParser.parseVstring(parser, firstPart, parser.tokenIndex);
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

    /**
     * Parses a number from a RuntimeScalar and returns a RuntimeScalar.
     * This method also utilizes a cache to store and retrieve previously parsed numbers.
     *
     * @param runtimeScalar The RuntimeScalar containing the number as a string.
     * @return A RuntimeScalar representing the parsed number.
     */
    public static RuntimeScalar parseNumber(RuntimeScalar runtimeScalar) {
        String str = (String) runtimeScalar.value;

        // Check cache first
        RuntimeScalar result = numificationCache.get(str);
        if (result != null) {
            return result;
        }

        int length = str.length();
        int start = 0, end = length;

        // Trim leading and trailing whitespace
        while (start < length && Character.isWhitespace(str.charAt(start))) start++;
        while (end > start && Character.isWhitespace(str.charAt(end - 1))) end--;

        if (start == end) {
            result = getScalarInt(0);
        }

        if (result == null) {
            // Check for Infinity, -Infinity, NaN
            int trimmedLength = end - start;

            // Optimization: Check length and first character before expensive string comparison
            if (trimmedLength == 3 || trimmedLength == 4) {
                String trimmedStr = str.substring(start, end);
                // Direct character comparison for "NaN", "Inf", "-Inf"
                char firstChar = trimmedStr.charAt(0);
                if (firstChar == 'N' || firstChar == 'n') {
                    if (trimmedStr.equalsIgnoreCase("NaN")) {
                        result = new RuntimeScalar(Double.NaN);
                    }
                } else if (firstChar == 'I' || firstChar == 'i') {
                    if (trimmedStr.equalsIgnoreCase("Inf")) {
                        result = new RuntimeScalar(Double.POSITIVE_INFINITY);
                    }
                } else if (firstChar == '-') {
                    if (trimmedStr.equalsIgnoreCase("-Inf")) {
                        result = new RuntimeScalar(Double.NEGATIVE_INFINITY);
                    }
                }
            } else if (trimmedLength == 8 || trimmedLength == 9) {
                String trimmedStr = str.substring(start, end);
                // Check for "Infinity" and "-Infinity"
                char firstChar = trimmedStr.charAt(0);
                if (firstChar == 'I' || firstChar == 'i') {
                    if (trimmedStr.equalsIgnoreCase("Infinity")) {
                        result = new RuntimeScalar(Double.POSITIVE_INFINITY);
                    }
                } else if (firstChar == '-') {
                    if (trimmedStr.equalsIgnoreCase("-Infinity")) {
                        result = new RuntimeScalar(Double.NEGATIVE_INFINITY);
                    }
                }
            }
        }

        if (result == null) {
            boolean hasDecimal = false;
            boolean hasExponent = false;
            boolean isNegative = false;
            int exponentPos = -1;
            int numberEnd = start;

            char firstChar = str.charAt(start);
            if (firstChar == '-' || firstChar == '+') {
                isNegative = (firstChar == '-');
                start++;
            }

            for (int i = start; i < end; i++) {
                char c = str.charAt(i);
                if (Character.isDigit(c)) {
                    numberEnd = i + 1;
                } else if (c == '.' && !hasDecimal && !hasExponent) {
                    hasDecimal = true;
                    numberEnd = i + 1;
                } else if ((c == 'e' || c == 'E') && !hasExponent) {
                    hasExponent = true;
                    exponentPos = i;
                    if (i + 1 < end && (str.charAt(i + 1) == '-' || str.charAt(i + 1) == '+')) {
                        i++;
                    }
                } else {
                    break;
                }
            }

            if (hasExponent && exponentPos == numberEnd - 1) {
                // Invalid exponent, remove it
                hasExponent = false;
                numberEnd = exponentPos;
            }

            if (numberEnd == start) return getScalarInt(0);

            try {
                String numberStr = str.substring(start, numberEnd);
                if (hasDecimal || hasExponent) {
                    double value = Double.parseDouble(numberStr);
                    result = new RuntimeScalar(isNegative ? -value : value);
                } else {
                    int value = Integer.parseInt(numberStr);
                    result = getScalarInt(isNegative ? -value : value);
                }
            } catch (NumberFormatException e) {
                // If integer parsing fails, try parsing as double
                try {
                    double value = Double.parseDouble(str.substring(start, numberEnd));
                    result = new RuntimeScalar(isNegative ? -value : value);
                } catch (NumberFormatException e2) {
                    result = getScalarInt(0);
                }
            }
        }

        // Cache the result if the cache is not full
        if (numificationCache.size() < MAX_NUMIFICATION_CACHE_SIZE) {
            numificationCache.put(str, result);
        }

        return result;
    }
}
