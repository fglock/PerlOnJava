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
                    return parseBinaryNumber(parser, letter.substring(1));
                } else if (secondChar == 'x' || secondChar == 'X') {
                    // Hexadecimal number: 0x...
                    TokenUtils.consume(parser);
                    // Remove the 'x' or 'X' prefix from the token
                    String hexPart = letter.substring(1);
                    return parseHexadecimalNumber(parser, hexPart);
                } else if (secondChar == 'o' || secondChar == 'O') {
                    // Octal number: 0o...
                    TokenUtils.consume(parser);
                    return parseOctalNumber(parser, letter.substring(1));
                }
            } else {
                // Traditional octal number: 0...
                return parseOctalNumber(parser, token.text);
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
     * Parses a binary number, including support for binary floating-point.
     *
     * @param parser    The Parser instance.
     * @param binaryPart The initial part of the binary number.
     * @return A NumberNode representing the parsed number.
     */
    private static Node parseBinaryNumber(Parser parser, String binaryPart) {
        StringBuilder binaryStr = new StringBuilder();
        boolean hasFractionalPart = false;
        String exponentStr = "";

        // Check if the binaryPart already contains 'p' (exponent)
        int pIndex = binaryPart.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            // Split the binary part and exponent (e.g., "0p0" -> "0" + "0")
            String actualBinaryPart = binaryPart.substring(0, pIndex);
            exponentStr = binaryPart.substring(pIndex + 1);
            binaryStr.append(cleanUnderscores(actualBinaryPart));
        } else {
            binaryStr.append(cleanUnderscores(binaryPart));
        }

        // Check for fractional part only if we haven't found an exponent yet
        if (exponentStr.isEmpty() && parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            hasFractionalPart = true;
            TokenUtils.consume(parser); // consume '.'

            StringBuilder fractionalPart = new StringBuilder();
            while (parser.tokenIndex < parser.tokens.size()) {
                String currentToken = parser.tokens.get(parser.tokenIndex).text;

                // Check if this is a NUMBER token (pure digits)
                if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                    if (!isBinaryString(digitStr)) {
                        parser.throwError("Invalid binary digit in fractional part");
                    }
                    fractionalPart.append(digitStr);
                }
                // Check if this is an IDENTIFIER that might contain 'p'
                else if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.IDENTIFIER) {
                    int tokenPIndex = currentToken.toLowerCase().indexOf('p');
                    if (tokenPIndex >= 0) {
                        // This token has binary digits followed by 'p' and exponent
                        String binaryDigits = currentToken.substring(0, tokenPIndex);
                        if (!binaryDigits.isEmpty()) {
                            binaryDigits = cleanUnderscores(binaryDigits);
                            if (!isBinaryString(binaryDigits)) {
                                parser.throwError("Invalid binary digit in fractional part");
                            }
                            fractionalPart.append(binaryDigits);
                        }
                        // Extract the exponent value
                        exponentStr = currentToken.substring(tokenPIndex + 1);
                        TokenUtils.consume(parser); // consume this token
                        break;
                    } else if (isBinaryString(currentToken.replaceAll("_", ""))) {
                        // Pure binary digits
                        String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                        fractionalPart.append(digitStr);
                    } else {
                        break;
                    }
                }
                // Check if this is just 'p' (exponent marker)
                else if (currentToken.toLowerCase().equals("p")) {
                    TokenUtils.consume(parser); // consume 'p'
                    break;
                }
                else {
                    break;
                }
            }
            binaryStr.append(".").append(fractionalPart.toString());
        }

        // Parse exponent if we haven't found it yet
        if (exponentStr.isEmpty()) {
            int exponent = checkBinaryExponent(parser);
            if (exponent != 0) {
                exponentStr = String.valueOf(exponent);
            }
        }

        try {
            int exponent = exponentStr.isEmpty() ? 0 : Integer.parseInt(exponentStr);
            double value = parseBinaryToDouble(binaryStr.toString(), exponent);
            return new NumberNode(Double.toString(value), parser.tokenIndex);
        } catch (NumberFormatException e) {
            parser.throwError("Invalid binary number");
        }
        return null;
    }

    /**
     * Parses an octal number, including support for octal floating-point.
     *
     * @param parser   The Parser instance.
     * @param octalPart The initial part of the octal number.
     * @return A NumberNode representing the parsed number.
     */
    private static Node parseOctalNumber(Parser parser, String octalPart) {
        StringBuilder octalStr = new StringBuilder();
        boolean hasFractionalPart = false;
        String exponentStr = "";

        // Check if the octalPart already contains 'p' (exponent)
        int pIndex = octalPart.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            // Split the octal part and exponent
            String actualOctalPart = octalPart.substring(0, pIndex);
            exponentStr = octalPart.substring(pIndex + 1);
            octalStr.append(cleanUnderscores(actualOctalPart));
        } else {
            octalStr.append(cleanUnderscores(octalPart));
        }

        // Check for fractional part only if we haven't found an exponent yet
        if (exponentStr.isEmpty() && parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            hasFractionalPart = true;
            TokenUtils.consume(parser); // consume '.'

            StringBuilder fractionalPart = new StringBuilder();
            while (parser.tokenIndex < parser.tokens.size()) {
                String currentToken = parser.tokens.get(parser.tokenIndex).text;

                // Check if this is a NUMBER token (pure digits)
                if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                    if (!isOctalString(digitStr)) {
                        parser.throwError("Invalid octal digit in fractional part");
                    }
                    fractionalPart.append(digitStr);
                }
                // Check if this is an IDENTIFIER that might contain 'p'
                else if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.IDENTIFIER) {
                    int tokenPIndex = currentToken.toLowerCase().indexOf('p');
                    if (tokenPIndex >= 0) {
                        // This token has octal digits followed by 'p' and exponent
                        String octalDigits = currentToken.substring(0, tokenPIndex);
                        if (!octalDigits.isEmpty()) {
                            octalDigits = cleanUnderscores(octalDigits);
                            if (!isOctalString(octalDigits)) {
                                parser.throwError("Invalid octal digit in fractional part");
                            }
                            fractionalPart.append(octalDigits);
                        }
                        // Extract the exponent value
                        exponentStr = currentToken.substring(tokenPIndex + 1);
                        TokenUtils.consume(parser); // consume this token
                        break;
                    } else if (isOctalString(currentToken.replaceAll("_", ""))) {
                        // Pure octal digits
                        String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                        fractionalPart.append(digitStr);
                    } else {
                        break;
                    }
                }
                // Check if this is just 'p' (exponent marker)
                else if (currentToken.toLowerCase().equals("p")) {
                    TokenUtils.consume(parser); // consume 'p'
                    break;
                }
                else {
                    break;
                }
            }
            octalStr.append(".").append(fractionalPart.toString());
        }

        // Parse exponent if we haven't found it yet
        if (exponentStr.isEmpty()) {
            int exponent = checkBinaryExponent(parser); // Binary exponent (base 2) for octal floats too
            if (exponent != 0) {
                exponentStr = String.valueOf(exponent);
            }
        }

        try {
            int exponent = exponentStr.isEmpty() ? 0 : Integer.parseInt(exponentStr);
            if (hasFractionalPart || exponent != 0) {
                double value = parseOctalToDouble(octalStr.toString(), exponent);
                return new NumberNode(Double.toString(value), parser.tokenIndex);
            } else {
                // Integer octal
                long value = Long.parseLong(octalStr.toString(), 8);
                return new NumberNode(Long.toString(value), parser.tokenIndex);
            }
        } catch (NumberFormatException e) {
            parser.throwError("Invalid octal number");
        }
        return null;
    }

    /**
     * Parses a hexadecimal number, including support for hexadecimal floating-point.
     *
     * @param parser  The Parser instance.
     * @param hexPart The initial part of the hexadecimal number.
     * @return A NumberNode representing the parsed number.
     */
    private static Node parseHexadecimalNumber(Parser parser, String hexPart) {
        StringBuilder hexStr = new StringBuilder();
        boolean hasFractionalPart = false;
        String exponentStr = "";

        // Handle the initial hex part (might be just "x1" or "x1p0" etc.)
        String actualHexPart = hexPart;
        int pIndex = hexPart.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            // Split the hex part and exponent (e.g., "1p0" -> "1" + "0")
            actualHexPart = hexPart.substring(0, pIndex);
            exponentStr = hexPart.substring(pIndex + 1);
        }

        hexStr.append(cleanUnderscores(actualHexPart));

        // Check for fractional part (dot followed by more hex digits)
        if (exponentStr.isEmpty() && parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            hasFractionalPart = true;
            hexStr.append(".");
            TokenUtils.consume(parser); // consume '.'

            // Consume hex digits after decimal point
            while (parser.tokenIndex < parser.tokens.size()) {
                String currentToken = parser.tokens.get(parser.tokenIndex).text;

                // Check if this is a NUMBER token (pure digits)
                if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                    if (!isHexString(digitStr)) {
                        parser.throwError("Invalid hex digit in fractional part");
                    }
                    hexStr.append(digitStr);
                }
                // Check if this is an IDENTIFIER that contains hex digits possibly followed by 'p'
                else if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.IDENTIFIER) {
                    int tokenPIndex = currentToken.toLowerCase().indexOf('p');
                    if (tokenPIndex >= 0) {
                        // This token has hex digits followed by 'p' (e.g., "ap" or "p0")
                        String hexDigits = currentToken.substring(0, tokenPIndex);
                        if (!hexDigits.isEmpty()) {
                            hexDigits = cleanUnderscores(hexDigits);
                            if (!isHexString(hexDigits)) {
                                parser.throwError("Invalid hex digit in fractional part");
                            }
                            hexStr.append(hexDigits);
                        }
                        // Extract the exponent value that comes after 'p'
                        exponentStr = currentToken.substring(tokenPIndex + 1);
                        TokenUtils.consume(parser); // consume this token
                        break;
                    } else if (isHexString(currentToken.replaceAll("_", ""))) {
                        // Pure hex digits
                        String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                        hexStr.append(digitStr);
                    } else {
                        break;
                    }
                }
                // Check if this is just 'p' (exponent marker)
                else if (currentToken.toLowerCase().equals("p")) {
                    TokenUtils.consume(parser); // consume 'p'
                    break;
                }
                else {
                    break;
                }
            }
        }

        // Parse exponent if we haven't found it yet
        if (exponentStr.isEmpty()) {
            exponentStr = parseHexExponentTokens(parser);
        }

        try {
            if (hasFractionalPart || !exponentStr.isEmpty()) {
                // Use Java's native hex float parsing
                String hexFloat = "0x" + hexStr.toString();
                if (!exponentStr.isEmpty()) {
                    hexFloat += "p" + exponentStr;
                }
                double value = Double.parseDouble(hexFloat);
                return new NumberNode(Double.toString(value), parser.tokenIndex);
            } else {
                // Integer hex
                long value = Long.parseLong(hexStr.toString(), 16);
                return new NumberNode(Long.toString(value), parser.tokenIndex);
            }
        } catch (NumberFormatException e) {
            System.err.println("Failed to parse hex float: 0x" + hexStr.toString() + (exponentStr.isEmpty() ? "" : "p" + exponentStr));
            parser.throwError("Invalid hexadecimal number");
        }
        return null;
    }

    /**
     * Parses the exponent part of a hex float from multiple tokens.
     * Handles cases where the exponent is split across tokens like: '-', '4'
     *
     * @param parser The Parser instance.
     * @return The exponent string, or empty string if no exponent.
     */
    private static String parseHexExponentTokens(Parser parser) {
        if (parser.tokenIndex >= parser.tokens.size()) {
            return "";
        }

        StringBuilder exponentStr = new StringBuilder();

        // Check for optional sign
        String currentToken = parser.tokens.get(parser.tokenIndex).text;
        if (currentToken.equals("-") || currentToken.equals("+")) {
            exponentStr.append(TokenUtils.consume(parser).text);
        }

        // Consume exponent digits
        if (parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
            exponentStr.append(cleanUnderscores(TokenUtils.consume(parser).text));
        } else if (exponentStr.length() > 0) {
            // We found a sign but no number - this is an error
            parser.throwError("Malformed hexadecimal floating-point exponent");
        }

        return exponentStr.toString();
    }

    /**
     * Checks for and parses the hex exponent part (p/P followed by digits).
     * Handles cases where hex digits and 'p' appear in the same token.
     *
     * @param parser The Parser instance.
     * @return The exponent string, or empty string if no exponent is present.
     */
    private static String checkHexExponent(Parser parser) {
        if (parser.tokenIndex >= parser.tokens.size()) {
            return "";
        }

        String currentTokenText = parser.tokens.get(parser.tokenIndex).text;

        // Check if current token contains 'p' or 'P'
        int pIndex = currentTokenText.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            String exponentStr = currentTokenText.substring(pIndex + 1);
            TokenUtils.consume(parser); // consume the token containing 'p'

            // If there are no digits after 'p' in this token, check next tokens
            if (exponentStr.isEmpty()) {
                // Check for optional sign
                if (parser.tokenIndex < parser.tokens.size() &&
                        (parser.tokens.get(parser.tokenIndex).text.equals("-") ||
                                parser.tokens.get(parser.tokenIndex).text.equals("+"))) {
                    exponentStr += TokenUtils.consume(parser).text;
                }

                // Consume exponent digits
                if (parser.tokenIndex < parser.tokens.size() &&
                        parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    exponentStr += TokenUtils.consume(parser).text;
                } else {
                    parser.throwError("Malformed hexadecimal floating-point exponent");
                }
            }

            return cleanUnderscores(exponentStr);
        }
        // Also check if current token starts with 'p' or 'P' (from IDENTIFIER token)
        else if (currentTokenText.toLowerCase().startsWith("p")) {
            String exponentStr = currentTokenText.substring(1);
            TokenUtils.consume(parser); // consume the 'p...' token

            // If there are more digits after 'p', they should be in exponentStr
            if (exponentStr.isEmpty()) {
                // Check for optional sign
                if (parser.tokenIndex < parser.tokens.size() &&
                        (parser.tokens.get(parser.tokenIndex).text.equals("-") ||
                                parser.tokens.get(parser.tokenIndex).text.equals("+"))) {
                    exponentStr += TokenUtils.consume(parser).text;
                }

                // Consume exponent digits
                if (parser.tokenIndex < parser.tokens.size() &&
                        parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    exponentStr += TokenUtils.consume(parser).text;
                } else {
                    parser.throwError("Malformed floating-point exponent");
                }
            }

            return cleanUnderscores(exponentStr);
        }

        return ""; // No exponent
    }

    /**
     * Checks for and parses the binary exponent part (p/P followed by digits).
     *
     * @param parser The Parser instance.
     * @return The exponent value, or 0 if no exponent is present.
     */
    private static int checkBinaryExponent(Parser parser) {
        String exponentStr = checkHexExponent(parser);
        if (exponentStr.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(exponentStr);
        } catch (NumberFormatException e) {
            parser.throwError("Invalid exponent value");
            return 0;
        }
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
            parser.throwError("syntax error");
        }

        number.append(token.text); // consume digits after '.'
        // Check for exponent part
        checkNumberExponent(parser, number);
        return new NumberNode(number.toString(), parser.tokenIndex);
    }

    /**
     * Checks for and parses the exponent part of a decimal number.
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
                    parser.throwError("Malformed number");
                }
            }
            number.append(cleanUnderscores(exponentPart));

            // If the exponent part was not fully consumed, check for separate tokens
            if (index == 1) {
                // Check for optional sign
                if (parser.tokens.get(parser.tokenIndex).text.equals("-") || parser.tokens.get(parser.tokenIndex).text.equals("+")) {
                    number.append(TokenUtils.consume(parser).text); // consume '-' or '+'
                }

                // Consume exponent digits
                number.append(cleanUnderscores(TokenUtils.consume(parser, LexerTokenType.NUMBER).text));
            }
        }
    }

    // Helper methods
    private static String cleanUnderscores(String str) {
        return str.replaceAll("_", "");
    }

    private static boolean isBinaryDigit(String text) {
        return text.matches("[01]");
    }

    private static boolean isBinaryString(String text) {
        return text.matches("[01_]*");
    }

    private static boolean isOctalDigit(String text) {
        return text.matches("[0-7]");
    }

    private static boolean isOctalString(String text) {
        return text.matches("[0-7_]*");
    }

    private static boolean isHexDigit(String text) {
        return text.matches("[0-9a-fA-F]");
    }

    private static boolean isHexString(String text) {
        return text.matches("[0-9a-fA-F_]*");
    }

    private static double parseBinaryToDouble(String binary, int exponent) {
        String[] parts = binary.split("\\.");
        long integerPart = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0], 2);
        double fractionalPart = 0.0;

        if (parts.length > 1) {
            String fraction = parts[1];
            for (int i = 0; i < fraction.length(); i++) {
                if (fraction.charAt(i) == '1') {
                    fractionalPart += Math.pow(2, -(i + 1));
                }
            }
        }

        double value = integerPart + fractionalPart;
        return value * Math.pow(2, exponent);
    }

    private static double parseOctalToDouble(String octal, int exponent) {
        String[] parts = octal.split("\\.");
        long integerPart = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0], 8);
        double fractionalPart = 0.0;

        if (parts.length > 1) {
            String fraction = parts[1];
            for (int i = 0; i < fraction.length(); i++) {
                int digit = Character.digit(fraction.charAt(i), 8);
                fractionalPart += digit * Math.pow(8, -(i + 1));
            }
        }

        double value = integerPart + fractionalPart;
        return value * Math.pow(2, exponent); // Binary exponent even for octal
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

            // Check for sign BEFORE initializing numberEnd
            if (start < end) {
                char firstChar = str.charAt(start);
                if (firstChar == '-' || firstChar == '+') {
                    isNegative = (firstChar == '-');
                    start++;
                }
            }

            // Initialize numberEnd AFTER handling the sign
            int numberEnd = start;

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