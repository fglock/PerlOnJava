package org.perlonjava.parser;

import org.perlonjava.astnode.Node;
import org.perlonjava.astnode.NumberNode;
import org.perlonjava.lexer.LexerToken;
import org.perlonjava.lexer.LexerTokenType;
import org.perlonjava.operators.WarnDie;
import org.perlonjava.runtime.RuntimeScalar;
import org.perlonjava.runtime.RuntimeScalarCache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * Refactored NumberParser with unified parsing logic for different number formats.
 */
public class NumberParser {

    public static final int MAX_NUMIFICATION_CACHE_SIZE = 1000;

    public static final Map<String, RuntimeScalar> numificationCache = new LinkedHashMap<String, RuntimeScalar>(MAX_NUMIFICATION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeScalar> eldest) {
            return size() > MAX_NUMIFICATION_CACHE_SIZE;
        }
    };
    private static final NumberFormat BINARY_FORMAT = new NumberFormat(
            2,
            str -> str.matches("[01_]*"),
            str -> Long.parseLong(str, 2),
            NumberParser::parseBinaryToDouble,
            true,
            "binary"
    );
    private static final NumberFormat OCTAL_FORMAT = new NumberFormat(
            8,
            str -> str.matches("[0-7_]*"),
            str -> Long.parseLong(str, 8),
            NumberParser::parseOctalToDouble,
            true, // octal floats use binary exponent
            "octal"
    );
    private static final NumberFormat HEX_FORMAT = new NumberFormat(
            16,
            str -> str.matches("[0-9a-fA-F_]*"),
            str -> Long.parseLong(str, 16),
            NumberParser::parseHexToDouble,
            false, // hex floats use their own exponent handling
            "hexadecimal"
    );

    /**
     * Parses a number token and returns a NumberNode.
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
                    TokenUtils.consume(parser);
                    return parseSpecialNumber(parser, letter.substring(1), BINARY_FORMAT);
                } else if (secondChar == 'x' || secondChar == 'X') {
                    TokenUtils.consume(parser);
                    return parseSpecialNumber(parser, letter.substring(1), HEX_FORMAT);
                } else if (secondChar == 'o' || secondChar == 'O') {
                    TokenUtils.consume(parser);
                    return parseSpecialNumber(parser, letter.substring(1), OCTAL_FORMAT);
                }
            } else {
                // Traditional octal number: 0...
                return parseSpecialNumber(parser, token.text, OCTAL_FORMAT);
            }
        }

        // Regular decimal number parsing
        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            number.append(TokenUtils.consume(parser).text);
            if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(TokenUtils.consume(parser).text);
            }
        }

        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
            if (nextToken.type == LexerTokenType.NUMBER) {
                parser.tokenIndex = currentIndex;
                return StringParser.parseVstring(parser, firstPart, parser.tokenIndex);
            }
        }

        checkNumberExponent(parser, number);
        return new NumberNode(number.toString(), parser.tokenIndex);
    }

    /**
     * Unified parsing method for special number formats (binary, octal, hex)
     */
    private static Node parseSpecialNumber(Parser parser, String initialPart, NumberFormat format) {
        StringBuilder numberStr = new StringBuilder();
        boolean hasFractionalPart = false;
        String exponentStr = "";

        // Check if the initialPart already contains 'p' (exponent)
        int pIndex = initialPart.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            String actualNumberPart = initialPart.substring(0, pIndex);
            exponentStr = initialPart.substring(pIndex + 1);
            numberStr.append(cleanUnderscores(actualNumberPart));
        } else {
            numberStr.append(cleanUnderscores(initialPart));
        }

        // Parse fractional part if no exponent found yet
        if (exponentStr.isEmpty() && parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).text.equals(".")) {

            hasFractionalPart = true;
            TokenUtils.consume(parser); // consume '.'

            StringBuilder fractionalPart = new StringBuilder();

            while (parser.tokenIndex < parser.tokens.size()) {
                String currentToken = parser.tokens.get(parser.tokenIndex).text;

                if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                    if (!format.digitValidator.test(digitStr)) {
                        parser.throwError("Invalid " + format.name + " digit in fractional part");
                    }
                    fractionalPart.append(digitStr);
                } else if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.IDENTIFIER) {
                    int tokenPIndex = currentToken.toLowerCase().indexOf('p');
                    if (tokenPIndex >= 0) {
                        String digits = currentToken.substring(0, tokenPIndex);
                        if (!digits.isEmpty()) {
                            digits = cleanUnderscores(digits);
                            if (!format.digitValidator.test(digits)) {
                                parser.throwError("Invalid " + format.name + " digit in fractional part");
                            }
                            fractionalPart.append(digits);
                        }
                        exponentStr = currentToken.substring(tokenPIndex + 1);
                        TokenUtils.consume(parser);
                        break;
                    } else if (format.digitValidator.test(currentToken.replaceAll("_", ""))) {
                        String digitStr = cleanUnderscores(TokenUtils.consume(parser).text);
                        fractionalPart.append(digitStr);
                    } else {
                        break;
                    }
                } else if (currentToken.equalsIgnoreCase("p")) {
                    TokenUtils.consume(parser);
                    break;
                } else {
                    break;
                }
            }
            numberStr.append(".").append(fractionalPart);
        }

        // Parse exponent if not found yet
        if (exponentStr.isEmpty()) {
            if (format.usesBinaryExponent) {
                int exponent = checkBinaryExponent(parser);
                if (exponent != 0) {
                    exponentStr = String.valueOf(exponent);
                }
            } else {
                exponentStr = parseHexExponentTokens(parser);
            }
        }

        try {
            if (hasFractionalPart || !exponentStr.isEmpty()) {
                // Floating point number
                int exponent = exponentStr.isEmpty() ? 0 : Integer.parseInt(exponentStr);
                double value;

                if (format == HEX_FORMAT) {
                    // Use Java's native hex float parsing for hex
                    String hexFloat = "0x" + numberStr;
                    if (!exponentStr.isEmpty()) {
                        hexFloat += "p" + exponentStr;
                    }
                    value = Double.parseDouble(hexFloat);
                } else {
                    // Use custom parsing for binary/octal
                    value = format.fractionalParser.apply(numberStr + "," + exponent);
                }

                return new NumberNode(Double.toString(value), parser.tokenIndex);
            } else {
                // Integer number
                long value = format.integerParser.apply(numberStr.toString());
                return new NumberNode(Long.toString(value), parser.tokenIndex);
            }
        } catch (NumberFormatException e) {
            parser.throwError("Invalid " + format.name + " number");
        }
        return null;
    }

    // Helper methods
    public static Node parseFractionalNumber(Parser parser) {
        StringBuilder number = new StringBuilder("0.");
        LexerToken token = parser.tokens.get(parser.tokenIndex++);
        if (token.type != LexerTokenType.NUMBER) {
            parser.throwError("syntax error");
        }
        number.append(token.text);
        checkNumberExponent(parser, number);
        return new NumberNode(number.toString(), parser.tokenIndex);
    }

    public static void checkNumberExponent(Parser parser, StringBuilder number) {
        String exponentPart = parser.tokens.get(parser.tokenIndex).text;
        if (exponentPart.startsWith("e") || exponentPart.startsWith("E")) {
            TokenUtils.consume(parser);
            int index = 1;
            for (; index < exponentPart.length(); index++) {
                if (!Character.isDigit(exponentPart.charAt(index)) && exponentPart.charAt(index) != '_') {
                    parser.throwError("Malformed number");
                }
            }
            number.append(cleanUnderscores(exponentPart));

            if (index == 1) {
                if (parser.tokens.get(parser.tokenIndex).text.equals("-") || parser.tokens.get(parser.tokenIndex).text.equals("+")) {
                    number.append(TokenUtils.consume(parser).text);
                }
                number.append(cleanUnderscores(TokenUtils.consume(parser, LexerTokenType.NUMBER).text));
            }
        }
    }

    private static String parseHexExponentTokens(Parser parser) {
        if (parser.tokenIndex >= parser.tokens.size()) {
            return "";
        }

        StringBuilder exponentStr = new StringBuilder();
        String currentToken = parser.tokens.get(parser.tokenIndex).text;
        if (currentToken.equals("-") || currentToken.equals("+")) {
            exponentStr.append(TokenUtils.consume(parser).text);
        }

        if (parser.tokenIndex < parser.tokens.size() &&
                parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
            exponentStr.append(cleanUnderscores(TokenUtils.consume(parser).text));
        } else if (exponentStr.length() > 0) {
            parser.throwError("Malformed hexadecimal floating-point exponent");
        }

        return exponentStr.toString();
    }

    private static String checkHexExponent(Parser parser) {
        if (parser.tokenIndex >= parser.tokens.size()) {
            return "";
        }

        String currentTokenText = parser.tokens.get(parser.tokenIndex).text;
        int pIndex = currentTokenText.toLowerCase().indexOf('p');
        if (pIndex >= 0) {
            String exponentStr = currentTokenText.substring(pIndex + 1);
            TokenUtils.consume(parser);

            if (exponentStr.isEmpty()) {
                if (parser.tokenIndex < parser.tokens.size() &&
                        (parser.tokens.get(parser.tokenIndex).text.equals("-") ||
                                parser.tokens.get(parser.tokenIndex).text.equals("+"))) {
                    exponentStr += TokenUtils.consume(parser).text;
                }

                if (parser.tokenIndex < parser.tokens.size() &&
                        parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    exponentStr += TokenUtils.consume(parser).text;
                } else {
                    parser.throwError("Malformed hexadecimal floating-point exponent");
                }
            }
            return cleanUnderscores(exponentStr);
        } else if (currentTokenText.toLowerCase().startsWith("p")) {
            String exponentStr = currentTokenText.substring(1);
            TokenUtils.consume(parser);

            if (exponentStr.isEmpty()) {
                if (parser.tokenIndex < parser.tokens.size() &&
                        (parser.tokens.get(parser.tokenIndex).text.equals("-") ||
                                parser.tokens.get(parser.tokenIndex).text.equals("+"))) {
                    exponentStr += TokenUtils.consume(parser).text;
                }

                if (parser.tokenIndex < parser.tokens.size() &&
                        parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                    exponentStr += TokenUtils.consume(parser).text;
                } else {
                    parser.throwError("Malformed floating-point exponent");
                }
            }
            return cleanUnderscores(exponentStr);
        }

        return "";
    }

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

    // Helper methods
    private static String cleanUnderscores(String str) {
        return str.replaceAll("_", "");
    }

    // Fractional parsers
    private static double parseBinaryToDouble(String input) {
        String[] parts = input.split(",");
        String binary = parts[0];
        int exponent = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

        String[] binaryParts = binary.split("\\.");
        long integerPart = binaryParts[0].isEmpty() ? 0 : Long.parseLong(binaryParts[0], 2);
        double fractionalPart = 0.0;

        if (binaryParts.length > 1) {
            String fraction = binaryParts[1];
            for (int i = 0; i < fraction.length(); i++) {
                if (fraction.charAt(i) == '1') {
                    fractionalPart += Math.pow(2, -(i + 1));
                }
            }
        }

        double value = integerPart + fractionalPart;
        return value * Math.pow(2, exponent);
    }

    private static double parseOctalToDouble(String input) {
        String[] parts = input.split(",");
        String octal = parts[0];
        int exponent = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

        String[] octalParts = octal.split("\\.");
        long integerPart = octalParts[0].isEmpty() ? 0 : Long.parseLong(octalParts[0], 8);
        double fractionalPart = 0.0;

        if (octalParts.length > 1) {
            String fraction = octalParts[1];
            for (int i = 0; i < fraction.length(); i++) {
                int digit = Character.digit(fraction.charAt(i), 8);
                fractionalPart += digit * Math.pow(8, -(i + 1));
            }
        }

        double value = integerPart + fractionalPart;
        return value * Math.pow(2, exponent);
    }

    private static double parseHexToDouble(String input) {
        // This won't actually be called due to special handling in parseSpecialNumber
        // but included for completeness
        return 0.0;
    }

    // parseNumber(RuntimeScalar) method
    public static RuntimeScalar parseNumber(RuntimeScalar runtimeScalar) {
        String str = (String) runtimeScalar.value;

        RuntimeScalar result = numificationCache.get(str);
        if (result != null) {
            return result;
        }

        int length = str.length();
        int start = 0, end = length;

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

            if (start < end) {
                char firstChar = str.charAt(start);
                if (firstChar == '-' || firstChar == '+') {
                    isNegative = (firstChar == '-');
                    start++;
                }
            }

            int numberEnd = start;

            // Check for special values with trailing characters
            boolean shouldWarn = false;
            String originalStr = str.substring(start, end);
            int specialEnd = start;

            if (end - start >= 3) {
                // Check for NaN variants
                if (str.regionMatches(true, start, "NaN", 0, 3)) {
                    result = new RuntimeScalar(Double.NaN);
                    specialEnd = start + 3;
                    if (specialEnd < end) {
                        // Check for valid NaN extensions
                        String remaining = str.substring(specialEnd, end);
                        if (remaining.matches("^[qQsS]?$") ||
                            remaining.matches("^[qQsS]?\\(\\d+\\)$") ||
                            remaining.matches("^[qQsS]?\\(0x[0-9a-fA-F]+\\)$") ||
                            remaining.matches("^\\(\\d+\\)$") ||
                            remaining.matches("^\\(0x[0-9a-fA-F]+\\)$")) {
                            // Valid NaN extensions, no warning
                            shouldWarn = false;
                        } else {
                            shouldWarn = true;
                        }
                    }
                }
                // Check for Inf
                else if (str.regionMatches(true, start, "Inf", 0, 3)) {
                    result = new RuntimeScalar(isNegative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
                    specialEnd = start + 3;
                    if (specialEnd < end) {
                        // Check if it's "Infinity"
                        if (end - specialEnd >= 5 && str.regionMatches(true, specialEnd, "inity", 0, 5)) {
                            specialEnd += 5;
                        }
                        if (specialEnd < end) {
                            shouldWarn = true;
                        }
                    }
                }
            }

            // Check for special NaN variants at the beginning
            if (result == null && end - start >= 4) {
                String upperStr = str.substring(start, Math.min(end, start + 5)).toUpperCase();
                if (upperStr.startsWith("QNAN") || upperStr.startsWith("SNAN") ||
                    upperStr.startsWith("NANQ") || upperStr.startsWith("NANS")) {
                    result = new RuntimeScalar(Double.NaN);
                    specialEnd = start + 4;
                    if (specialEnd < end) {
                        shouldWarn = true;
                    }
                }
            }

            // Check for Windows-style formats
            if (result == null && start < end) {
                String remaining = str.substring(start, end);

                // Check for Windows-style Inf: 1.#INF, 1#INF, 1.#INF00, etc.
                if (remaining.matches("1\\.?#INF.*")) {
                    result = new RuntimeScalar(isNegative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
                    // Check if there are non-digit characters after INF
                    int infPos = remaining.indexOf("INF") + 3;
                    if (infPos < remaining.length()) {
                        String afterInf = remaining.substring(infPos);
                        if (!afterInf.matches("\\d*")) {
                            shouldWarn = true;
                        }
                    }
                }
                // Check for Windows-style NaN: 1.#QNAN, 1.#NAN, 1.#IND, 1.#IND00, etc.
                else if (remaining.matches("\\+?1\\.?#(QNAN|NANQ|NAN|IND|SNAN).*")) {
                    result = new RuntimeScalar(Double.NaN);
                    // Check if there are non-digit characters after the NaN variant
                    int nanPos = remaining.indexOf('#') + 1;
                    String afterHash = remaining.substring(nanPos);

                    // Determine where the NaN identifier ends
                    if (afterHash.startsWith("QNAN")) {
                        nanPos += 4;
                    } else if (afterHash.startsWith("NANQ")) {
                        nanPos += 4;
                    } else if (afterHash.startsWith("SNAN")) {
                        nanPos += 4;
                    } else if (afterHash.startsWith("NAN")) {
                        nanPos += 3;
                    } else if (afterHash.startsWith("IND")) {
                        nanPos += 3;
                    }

                    if (nanPos < remaining.length()) {
                        String afterNan = remaining.substring(nanPos);
                        // Only digits (including empty string) are valid after NaN identifier
                        if (!afterNan.matches("\\d*")) {
                            shouldWarn = true;
                        }
                    }
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
                    try {
                        double value = Double.parseDouble(str.substring(start, numberEnd));
                        result = new RuntimeScalar(isNegative ? -value : value);
                    } catch (NumberFormatException e2) {
                        result = getScalarInt(0);
                    }
                }
            }

            // Generate warning if needed
            if (shouldWarn) {
                String warnStr = str.trim();
                if (warnStr.startsWith("-") || warnStr.startsWith("+")) {
                    warnStr = warnStr.substring(1);
                }
                WarnDie.warn(new RuntimeScalar("Argument \"" + warnStr + "\" isn't numeric"),
                           RuntimeScalarCache.scalarEmptyString);
            }
        }

        numificationCache.put(str, result);
        return result;
    }

    /**
     * Represents a number format with its specific parsing rules
     */
    private static class NumberFormat {
        final int radix;
        final Predicate<String> digitValidator;
        final Function<String, Long> integerParser;
        final Function<String, Double> fractionalParser;
        final boolean usesBinaryExponent; // true for binary/octal, false for hex
        final String name;

        NumberFormat(int radix, Predicate<String> digitValidator,
                     Function<String, Long> integerParser,
                     Function<String, Double> fractionalParser,
                     boolean usesBinaryExponent, String name) {
            this.radix = radix;
            this.digitValidator = digitValidator;
            this.integerParser = integerParser;
            this.fractionalParser = fractionalParser;
            this.usesBinaryExponent = usesBinaryExponent;
            this.name = name;
        }
    }
}