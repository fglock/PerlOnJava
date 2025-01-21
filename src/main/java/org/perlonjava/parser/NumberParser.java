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
                    return new NumberNode(Long.toString(Long.parseLong(letter.substring(1), 2)), parser.tokenIndex);
                } else if (secondChar == 'x' || secondChar == 'X') {
                    // Hexadecimal number: 0x...
                    TokenUtils.consume(parser);
                    return parseHexadecimalNumber(parser, letter.substring(1));
                }
            } else {
                // Octal number: 0...
                return new NumberNode(Long.toString(Long.parseLong(token.text, 8)), parser.tokenIndex);
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
     * Parses a hexadecimal number, including support for hexadecimal floating-point.
     *
     * @param parser  The Parser instance.
     * @param hexPart The initial part of the hexadecimal number.
     * @return A NumberNode representing the parsed number.
     */
    private static Node parseHexadecimalNumber(Parser parser, String hexPart) {
        StringBuilder number = new StringBuilder(hexPart);

        // Check for fractional part
        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            number.append(TokenUtils.consume(parser).text); // consume '.'
            while (isHexDigit(parser.tokens.get(parser.tokenIndex).text) || parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(TokenUtils.consume(parser).text); // consume hex digits after '.'
            }
        }

        // Check for 'p' exponent part, including cases like "ap"
        String currentTokenText = parser.tokens.get(parser.tokenIndex).text;
        if (currentTokenText.toLowerCase().endsWith("p")) {
            int pIndex = currentTokenText.toLowerCase().indexOf('p');
            number.append(currentTokenText, 0, pIndex); // append any preceding hex digits
            TokenUtils.consume(parser); // consume the token containing 'p'
            number.append("p"); // append 'p' to the number

            // Check for optional sign
            if (parser.tokens.get(parser.tokenIndex).text.equals("-") || parser.tokens.get(parser.tokenIndex).text.equals("+")) {
                number.append(TokenUtils.consume(parser).text); // consume '-' or '+'
            }

            // Consume exponent digits
            if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(TokenUtils.consume(parser).text);
            } else {
                throw new PerlCompilerException(parser.tokenIndex, "Malformed hexadecimal floating-point number", parser.ctx.errorUtil);
            }
        }

        try {
            // Convert the hexadecimal number to a double
            double value = parseHexToDouble(number.toString());
            return new NumberNode(Double.toString(value), parser.tokenIndex);
        } catch (NumberFormatException e) {
            System.err.println("NumberFormatException: " + e.getMessage());
            throw new PerlCompilerException(parser.tokenIndex, "Invalid hexadecimal number", parser.ctx.errorUtil);
        }
    }

    private static boolean isHexDigit(String text) {
        return text.matches("[0-9a-fA-F]");
    }

    private static double parseHexToDouble(String hex) {
        String[] parts = hex.split("\\.");
        long integerPart = Long.parseLong(parts[0], 16);
        double fractionalPart = 0.0;

        if (parts.length > 1) {
            String fraction = parts[1];
            for (int i = 0; i < fraction.length(); i++) {
                fractionalPart += Character.digit(fraction.charAt(i), 16) / Math.pow(16, i + 1);
            }
        }

        // Extract exponent part if present
        int exponentIndex = hex.toLowerCase().indexOf('p');
        int exponent = 0;
        if (exponentIndex != -1) {
            String exponentPart = hex.substring(exponentIndex + 1);
            exponent = Integer.parseInt(exponentPart);
        }

        double value = integerPart + fractionalPart;
        return value * Math.pow(2, exponent);
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
        String exponentPart = parser.tokens.get(parser.tokenIndex).text;
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

            if (end - start >= 3) {
                if (str.regionMatches(true, start, "NaN", 0, 3)) {
                    result = new RuntimeScalar(Double.NaN);
                }
                if (str.regionMatches(true, start, "Inf", 0, 3)) {
                    result = new RuntimeScalar(isNegative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
                }
            }

            if (result == null) {
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
                        long value = Long.parseLong(numberStr);
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
        }

        // Cache the result if the cache is not full
        numificationCache.put(str, result);

        return result;
    }
}

