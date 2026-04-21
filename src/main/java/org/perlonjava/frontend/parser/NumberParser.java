package org.perlonjava.frontend.parser;

import org.perlonjava.frontend.astnode.BinaryOperatorNode;
import org.perlonjava.frontend.astnode.IdentifierNode;
import org.perlonjava.frontend.astnode.ListNode;
import org.perlonjava.frontend.astnode.Node;
import org.perlonjava.frontend.astnode.NumberNode;
import org.perlonjava.frontend.astnode.OperatorNode;
import org.perlonjava.frontend.astnode.StringNode;
import org.perlonjava.frontend.lexer.LexerToken;
import org.perlonjava.frontend.lexer.LexerTokenType;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarCache;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarInt;

/**
 * Refactored NumberParser with unified parsing logic for different number formats.
 */
public class NumberParser {

    public static final int MAX_NUMIFICATION_CACHE_SIZE = 1000;

    /**
     * Counter used to mint unique global-variable names for
     * {@code overload::constant} handlers that we capture at parse time.
     */
    private static final AtomicInteger CONSTANT_HANDLER_COUNTER = new AtomicInteger();

    /**
     * Wraps a just-parsed numeric literal in a call to the
     * {@code overload::constant} handler for {@code category}, if one has
     * been registered for the enclosing lexical scope via {@code %^H}.
     * <p>
     * This is Perl's compile-time numeric-literal rewrite: with
     * <pre>{@code
     *   BEGIN { $^H{integer} = sub { Math::BigInt->new(shift) }; }
     *   my $x = 5;
     * }</pre>
     * the literal {@code 5} is rewritten as a call to the handler.
     * <p>
     * Because {@code %^H} is cleared at runtime, we <em>capture</em> the
     * handler into a synthetic global ({@code $overload::__poj_const_handler_N})
     * at parse time and emit an AST that dereferences it at runtime.
     *
     * @param literal      the {@link NumberNode} produced by the parser
     * @param originalText the original source text of the literal
     *                     (e.g. {@code "5"}, {@code "0xff"}, {@code "3.14"}) —
     *                     passed as {@code $_[0]} to the handler
     * @param category     one of {@code "integer"}, {@code "float"},
     *                     {@code "binary"}
     * @param tokenIndex   source position for the synthetic nodes
     * @return {@code literal} unchanged when no handler is active, or a
     *         {@code $handler->(originalText, literal, category)} call AST
     */
    private static Node wrapWithConstantHandler(Node literal, String originalText,
                                                String category, int tokenIndex) {
        RuntimeHash hh = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        if (hh == null || hh.elements.isEmpty()) {
            return literal;
        }
        RuntimeScalar handler = hh.elements.get(category);
        if (handler == null) {
            return literal;
        }
        // Accept both a CODE scalar (rare) and a CODE reference (normal).
        boolean isCode = handler.type == RuntimeScalarType.CODE
                || (handler.type == RuntimeScalarType.REFERENCE
                    && handler.value instanceof RuntimeScalar ref
                    && ref.type == RuntimeScalarType.CODE);
        if (!isCode) {
            return literal;
        }

        // Stash the handler into a uniquely-named package global so it
        // remains reachable at runtime (unlike %^H, which is cleared).
        int id = CONSTANT_HANDLER_COUNTER.incrementAndGet();
        String varName = "overload::__poj_const_handler_" + id;
        GlobalVariable.getGlobalVariable(varName).set(handler);

        // Emit  overload::__poj_const_call($handler, $text, $literal, $category)
        // rather than a direct $handler->($text, $literal, $category) call.
        // The helper temporarily removes %^H{$category} for the duration of
        // the handler's execution so that patterns like
        //     sub { return eval $_[0] }
        // in `overload::constant float => ...` don't infinite-recurse when
        // the handler's body reparses the original source text.
        OperatorNode handlerVar = new OperatorNode("$",
                new IdentifierNode(varName, tokenIndex), tokenIndex);
        ListNode args = new ListNode(tokenIndex);
        args.elements.add(handlerVar);
        args.elements.add(new StringNode(originalText, tokenIndex));
        args.elements.add(literal);
        args.elements.add(new StringNode(category, tokenIndex));
        return new BinaryOperatorNode("(",
                new OperatorNode("&",
                        new IdentifierNode("overload::__poj_const_call", tokenIndex),
                        tokenIndex),
                args, tokenIndex);
    }

    /**
     * Returns {@code true} if an {@code overload::constant} handler is
     * currently registered in {@code %^H} for {@code category}. Used to
     * decide whether a number-literal parse that would otherwise fail
     * (e.g. an over-long hex literal) can be salvaged by handing the
     * original source text to the handler.
     */
    private static boolean hasConstantHandler(String category) {
        RuntimeHash hh = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
        if (hh == null || hh.elements.isEmpty()) return false;
        RuntimeScalar handler = hh.elements.get(category);
        if (handler == null) return false;
        return handler.type == RuntimeScalarType.CODE
                || (handler.type == RuntimeScalarType.REFERENCE
                    && handler.value instanceof RuntimeScalar ref
                    && ref.type == RuntimeScalarType.CODE);
    }

    public static final Map<String, RuntimeScalar> numificationCache = new LinkedHashMap<String, RuntimeScalar>(MAX_NUMIFICATION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuntimeScalar> eldest) {
            return size() > MAX_NUMIFICATION_CACHE_SIZE;
        }
    };

    private static final Pattern WINDOWS_INF_PATTERN = Pattern.compile("1\\.?#INF.*");
    private static final Pattern WINDOWS_NAN_PATTERN = Pattern.compile("\\+?1\\.?#(QNAN|NANQ|NAN|IND|SNAN).*"
    );
    private static final NumberFormat BINARY_FORMAT = new NumberFormat(
            2,
            str -> str.matches("[01_]*"),
            str -> Long.parseLong(str.replaceAll("_", ""), 2),
            NumberParser::parseBinaryToDouble,
            true,
            "binary"
    );
    private static final NumberFormat OCTAL_FORMAT = new NumberFormat(
            8,
            str -> str.matches("[0-7_]*"),
            str -> Long.parseLong(str.replaceAll("_", ""), 8),
            NumberParser::parseOctalToDouble,
            true, // octal floats use binary exponent
            "octal"
    );
    private static final NumberFormat HEX_FORMAT = new NumberFormat(
            16,
            str -> str.matches("[0-9a-fA-F_]*"),
            str -> Long.parseUnsignedLong(str.replaceAll("_", ""), 16),
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
        boolean hasFractional = false;
        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            number.append(TokenUtils.consume(parser).text);
            if (parser.tokens.get(parser.tokenIndex).type == LexerTokenType.NUMBER) {
                number.append(TokenUtils.consume(parser).text);
            }
            hasFractional = true;
        }

        if (parser.tokens.get(parser.tokenIndex).text.equals(".")) {
            LexerToken nextToken = parser.tokens.get(parser.tokenIndex + 1);
            if (nextToken.type == LexerTokenType.NUMBER) {
                parser.tokenIndex = currentIndex;
                return StringParser.parseVstring(parser, firstPart, parser.tokenIndex);
            }
        }

        int beforeExponent = number.length();
        checkNumberExponent(parser, number);
        boolean hasExponent = number.length() > beforeExponent;
        String originalText = number.toString();
        NumberNode numberNode = new NumberNode(originalText, parser.tokenIndex);
        String category = (hasFractional || hasExponent) ? "float" : "integer";
        return wrapWithConstantHandler(numberNode, originalText, category, parser.tokenIndex);
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
                // Save token index for backtracking
                int savedTokenIndex = parser.tokenIndex;
                exponentStr = parseHexExponentTokens(parser);
                if (exponentStr.isEmpty()) {
                    // No exponent found, backtrack
                    parser.tokenIndex = savedTokenIndex;
                }
            }
        }

        try {
            // Reconstruct the original source text for the overload::constant
            // handler's $_[0] argument. numberStr already has "intPart.fracPart"
            // for hex-float input; we just add the prefix and (if present) the
            // binary exponent.
            String prefix;
            if (format == HEX_FORMAT) {
                prefix = "0x";
            } else if (format == BINARY_FORMAT) {
                prefix = "0b";
            } else { // OCTAL_FORMAT
                // Distinguish `0oNNN` (explicit 0o prefix, numberStr has no
                // leading 0) from traditional `0NNN` (numberStr is the full
                // "0NNN" text).
                prefix = numberStr.length() > 0 && numberStr.charAt(0) == '0' ? "" : "0";
            }
            StringBuilder originalBuilder = new StringBuilder(prefix).append(numberStr);
            if (!exponentStr.isEmpty()) {
                originalBuilder.append('p').append(exponentStr);
            }
            String originalText = originalBuilder.toString();

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

                NumberNode numberNode = new NumberNode(Double.toString(value), parser.tokenIndex);
                return wrapWithConstantHandler(numberNode, originalText, "float", parser.tokenIndex);
            } else {
                // Integer number
                long value;
                try {
                    value = format.integerParser.apply(numberStr.toString());
                } catch (NumberFormatException overflow) {
                    // Value doesn't fit in a Perl IV/NV. If a `binary`
                    // overload::constant handler is active (e.g. `use bigint`),
                    // it will consume the original source text and produce a
                    // bignum. Fall through with a 0 placeholder — the handler
                    // ignores the numeric-form argument in that case.
                    if (hasConstantHandler("binary")) {
                        NumberNode numberNode = new NumberNode("0", parser.tokenIndex);
                        return wrapWithConstantHandler(numberNode, originalText, "binary", parser.tokenIndex);
                    }
                    throw overflow;
                }
                NumberNode numberNode = new NumberNode(Long.toString(value), parser.tokenIndex);
                return wrapWithConstantHandler(numberNode, originalText, "binary", parser.tokenIndex);
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
        String originalText = number.toString();
        NumberNode numberNode = new NumberNode(originalText, parser.tokenIndex);
        return wrapWithConstantHandler(numberNode, originalText, "float", parser.tokenIndex);
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
        } else {
            // Not a valid exponent - return empty string
            return "";
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

    // parseNumber(RuntimeScalar) method - no operation context
    public static RuntimeScalar parseNumber(RuntimeScalar runtimeScalar) {
        return parseNumber(runtimeScalar, null);
    }

    // parseNumber(RuntimeScalar, String) method - with optional operation context for warnings
    public static RuntimeScalar parseNumber(RuntimeScalar runtimeScalar, String operation) {
        String str = (String) runtimeScalar.value;

        RuntimeScalar result = numificationCache.get(str);
        if (result != null) {
            if (result.type == RuntimeScalarType.STRING
                    || result.type == RuntimeScalarType.BYTE_STRING) {
                numificationCache.remove(str);
            } else {
                return result;
            }
        }

        int length = str.length();
        int start = 0, end = length;

        while (start < length && Character.isWhitespace(str.charAt(start))) start++;
        while (end > start && Character.isWhitespace(str.charAt(end - 1))) end--;

        // Track whether to emit a "isn't numeric" warning
        boolean shouldWarn = false;

        if (start == end) {
            // Empty or whitespace-only string: value is 0, but warn if string is non-null
            shouldWarn = true;
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
                        // Valid NaN extensions, no warning
                        shouldWarn = !remaining.matches("^[qQsS]?$") &&
                                !remaining.matches("^[qQsS]?\\(\\d+\\)$") &&
                                !remaining.matches("^[qQsS]?\\(0x[0-9a-fA-F]+\\)$") &&
                                !remaining.matches("^\\(\\d+\\)$") &&
                                !remaining.matches("^\\(0x[0-9a-fA-F]+\\)$");
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
                if (WINDOWS_INF_PATTERN.matcher(remaining).matches()) {
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
                else if (WINDOWS_NAN_PATTERN.matcher(remaining).matches()) {
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

                if (numberEnd == start) {
                    // No numeric characters found - value is 0, warn about non-numeric string
                    shouldWarn = true;
                    result = getScalarInt(0);
                } else {
                    // Trailing non-numeric characters after the number - warn
                    if (numberEnd < end) {
                        shouldWarn = true;
                    }

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
            }
        }

        // Generate warning for non-numeric strings (all cases)
        if (shouldWarn) {
            String msg = "Argument \"" + str + "\" isn't numeric";
            if (operation != null) {
                msg += " in " + operation;
            }
            WarnDie.warnWithCategory(new RuntimeScalar(msg),
                    RuntimeScalarCache.scalarEmptyString, "numeric");
        }

        if (!shouldWarn && result.type != RuntimeScalarType.STRING
                && result.type != RuntimeScalarType.BYTE_STRING) {
            numificationCache.put(str, result);
        }
        return result;
    }

    /**
     * Represents a number format with its specific parsing rules
     *
     * @param usesBinaryExponent true for binary/octal, false for hex
     */
    private record NumberFormat(int radix, Predicate<String> digitValidator, Function<String, Long> integerParser,
                                Function<String, Double> fractionalParser, boolean usesBinaryExponent, String name) {
    }
}