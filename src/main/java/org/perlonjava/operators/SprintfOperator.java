package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class SprintfOperator {

    // Pattern to match a complete format specifier
    // Updated to handle parameter index, vector flags, and size modifiers
    private static final Pattern FORMAT_PATTERN = Pattern.compile(
            "%(\\d+\\$)?([-+ #0]*)(\\*(?:\\d+\\$)?)?([*]?)(\\d*)(?:\\.(\\*(?:\\d+\\$)?|\\d*))?([*]?)(?:(hh|h|ll|l|t|z|q|L|V)?)(v?)([diouxXeEfFgGaAcspnvDUOBb%A-Z])"
            // "%(\\d+\\$)?([-+ #0]*)(\\*(?:\\d+\\$)?)?(\\d*)(?:\\.(\\*(?:\\d+\\$)?|\\d*))?([hlLtqzV])?([v])?([a-zA-Z])"
    );

    /**
     * Formats the elements according to the specified format string.
     *
     * @param runtimeScalar The format string
     * @param list          The list of elements to be formatted.
     * @return A RuntimeScalar containing the formatted string.
     */
    public static RuntimeScalar sprintf(RuntimeScalar runtimeScalar, RuntimeList list) {
        // Expand the list
        list = new RuntimeList((RuntimeBase) list);
        String format = runtimeScalar.toString();

        StringBuilder result = new StringBuilder();
        int argIndex = 0;
        int pos = 0;

        Matcher matcher = FORMAT_PATTERN.matcher(format);

        while (matcher.find()) {
            // Append text before the format specifier
            result.append(format, pos, matcher.start());
            pos = matcher.end();

            // Parse the format specifier
            String paramIndex = matcher.group(1);
            String flags = matcher.group(2);
            String widthFromArgSpec = matcher.group(3);
            boolean widthFromArg = !matcher.group(4).isEmpty() && matcher.group(3).isEmpty();
            String widthStr = matcher.group(5);
            String precisionSpec = matcher.group(6);
            boolean precisionFromArg = !matcher.group(7).isEmpty() && matcher.group(6) == null;
            String lengthModifier = matcher.group(8);
            boolean vectorFlag = !matcher.group(9).isEmpty();
            char conversionChar = matcher.group(10).charAt(0);

            // Handle %% - literal percent
            if (conversionChar == '%') {
                result.append('%');
                continue;
            }

            // Handle invalid format specifiers - return the specifier unchanged with "INVALID"
            if (isInvalidSpecifier(conversionChar)) {
                String invalidSpec = matcher.group(0);
                result.append(invalidSpec);
                result.append(" INVALID");
                continue;
            }

            // Check for invalid format like space between % and specifier
            String originalSpec = matcher.group(0);
            if (originalSpec.contains(" ") && !originalSpec.matches("%[-+ #0]*\\d*\\.?\\d*[a-zA-Z%]")) {
                result.append(originalSpec);
                result.append(" INVALID");
                continue;
            }

            // Handle length modifiers that should be invalid for certain formats
            if (lengthModifier != null && ("hf".equals(lengthModifier + conversionChar) ||
                    "hg".equals(lengthModifier + conversionChar) ||
                    "he".equals(lengthModifier + conversionChar))) {
                result.append(originalSpec);
                result.append(" INVALID");
                continue;
            }

            // Handle invalid combinations for vector format
            if (vectorFlag && !"diouxXbB".contains(String.valueOf(conversionChar))) {
                String invalidSpec = matcher.group(0);
                result.append(invalidSpec);
                result.append(" INVALID");
                continue;
            }

            // Handle parameter index for width/precision
            int actualArgIndex = argIndex;
            if (paramIndex != null) {
                // Parameter indexing is 1-based
                int paramNum = Integer.parseInt(paramIndex.substring(0, paramIndex.length() - 1));
                actualArgIndex = paramNum - 1;
            }

            // Get width from argument if needed
            int width = 0;
            if (widthFromArg || widthFromArgSpec != null) {
                int widthArgIndex = argIndex;
                if (widthFromArgSpec != null && widthFromArgSpec.contains("$")) {
                    // Extract parameter index from width spec
                    String widthParamStr = widthFromArgSpec.replaceAll("[^0-9]", "");
                    if (!widthParamStr.isEmpty()) {
                        widthArgIndex = Integer.parseInt(widthParamStr) - 1;
                    }
                } else {
                    argIndex++; // Consume next argument for width
                }

                if (widthArgIndex >= list.size()) {
                    result.append(" MISSING");
                    continue;
                }
                width = ((RuntimeScalar) list.elements.get(widthArgIndex)).getInt();
                if (width < 0) {
                    flags += "-";
                    width = -width;
                }
            } else if (!widthStr.isEmpty()) {
                width = Integer.parseInt(widthStr);
            }

            // Get precision from argument if needed
            int precision = -1;
            if (precisionFromArg || (precisionSpec != null && precisionSpec.startsWith("*"))) {
                int precisionArgIndex = argIndex;
                if (precisionSpec != null && precisionSpec.contains("$")) {
                    // Extract parameter index from precision spec
                    String precParamStr = precisionSpec.replaceAll("[^0-9]", "");
                    if (!precParamStr.isEmpty()) {
                        precisionArgIndex = Integer.parseInt(precParamStr) - 1;
                    }
                } else {
                    argIndex++; // Consume next argument for precision
                }

                if (precisionArgIndex >= list.size()) {
                    result.append(" MISSING");
                    continue;
                }
                precision = ((RuntimeScalar) list.elements.get(precisionArgIndex)).getInt();
                if (precision < 0) {
                    precision = -1; // Negative precision is ignored
                }
            } else if (precisionSpec != null && !precisionSpec.startsWith("*")) {
                precision = precisionSpec.isEmpty() ? 0 : Integer.parseInt(precisionSpec);
            }

            // Get the value to format
            if (actualArgIndex >= list.size()) {
                if (conversionChar == 'n') {
                    throw new PerlCompilerException("%n specifier not supported");
                }
                result.append(" MISSING");
                continue;
            }
            RuntimeScalar value = actualArgIndex >= 0 && actualArgIndex < list.elements.size()
                    ? (RuntimeScalar) list.elements.get(actualArgIndex)
                    : scalarUndef;

            // Only increment argIndex if we're not using parameter indexing
            if (paramIndex == null) {
                argIndex++;
            }

            // Handle vector format specifier
            if (vectorFlag) {
                String formatted = formatVectorString(value, flags, width, precision, conversionChar);
                result.append(formatted);
                continue;
            }

            // Format the value
            String formatted = formatValue(value, flags, width, precision, conversionChar);
            result.append(formatted);
        }

        // Append any remaining text
        result.append(format.substring(pos));

        return new RuntimeScalar(result.toString());
    }

    private static boolean isInvalidSpecifier(char c) {
        // List of invalid specifiers that should return "INVALID"
        return "CHIKMVWYJLNPQRSTZ".indexOf(c) >= 0;
    }

    private static String formatVectorString(RuntimeScalar value, String flags, int width,
                                             int precision, char conversionChar) {
        String str = value.toString();

        // Handle version objects specifically
        if (isVersionObject(value)) {
            return formatVersionVector(value, flags, width, precision, conversionChar);
        }

        if (str.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        byte[] bytes = str.getBytes();

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                result.append(".");
            }

            int byteValue = bytes[i] & 0xFF;
            String formatted = formatVectorValue(byteValue, flags, precision, conversionChar);
            result.append(formatted);
        }

        // Apply width formatting to the entire vector string
        String formatted = result.toString();
        if (width > 0 && formatted.length() < width) {
            boolean leftAlign = flags.contains("-");
            if (leftAlign) {
                formatted = String.format("%-" + width + "s", formatted);
            } else {
                formatted = String.format("%" + width + "s", formatted);
            }
        }

        return formatted;
    }

    private static boolean isVersionObject(RuntimeScalar value) {
        // Check if this is a version object by looking at its string representation
        // This is a heuristic - in a real implementation, we'd check the actual object type
        String str = value.toString();
        return str.matches("\\d+(\\.\\d+)*") && str.contains(".");
    }

    private static String formatVersionVector(RuntimeScalar value, String flags, int width,
                                              int precision, char conversionChar) {
        String versionStr = value.toString();
        String[] parts = versionStr.split("\\.");

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                result.append(".");
            }

            try {
                int intValue = Integer.parseInt(parts[i]);
                String formatted = formatVectorValue(intValue, flags, precision, conversionChar);
                result.append(formatted);
            } catch (NumberFormatException e) {
                result.append(parts[i]);
            }
        }

        String formatted = result.toString();
        if (width > 0 && formatted.length() < width) {
            boolean leftAlign = flags.contains("-");
            if (leftAlign) {
                formatted = String.format("%-" + width + "s", formatted);
            } else {
                formatted = String.format("%" + width + "s", formatted);
            }
        }

        return formatted;
    }

    private static String formatVectorValue(int byteValue, String flags, int precision, char conversionChar) {
        String formatted;
        switch (conversionChar) {
            case 'd':
            case 'i':
                formatted = String.valueOf(byteValue);
                if (flags.contains("+") && byteValue >= 0) {
                    formatted = "+" + formatted;
                } else if (flags.contains(" ") && byteValue >= 0) {
                    formatted = " " + formatted;
                }
                break;
            case 'o':
                formatted = Integer.toOctalString(byteValue);
                if (flags.contains("#") && byteValue != 0) {
                    formatted = "0" + formatted;
                }
                break;
            case 'x':
                formatted = Integer.toHexString(byteValue);
                if (flags.contains("#") && byteValue != 0) {
                    formatted = "0x" + formatted;
                }
                break;
            case 'X':
                formatted = Integer.toHexString(byteValue).toUpperCase();
                if (flags.contains("#") && byteValue != 0) {
                    formatted = "0X" + formatted;
                }
                break;
            case 'b':
            case 'B':
                formatted = Integer.toBinaryString(byteValue);
                if (flags.contains("#") && byteValue != 0) {
                    formatted = (conversionChar == 'B' ? "0B" : "0b") + formatted;
                }
                break;
            default:
                formatted = String.valueOf(byteValue);
        }

        // Apply precision padding
        if (precision > 0 && formatted.length() < precision) {
            if (formatted.startsWith("+") || formatted.startsWith(" ") ||
                    formatted.startsWith("0x") || formatted.startsWith("0X") ||
                    formatted.startsWith("0b") || formatted.startsWith("0B")) {
                // Preserve prefixes
                String prefix = formatted.replaceFirst("^(.[xXbB]?)?.*", "$1");
                String number = formatted.substring(prefix.length());
                formatted = prefix + String.format("%0" + (precision - prefix.length()) + "d",
                        Integer.parseInt(number.isEmpty() ? "0" : number));
            } else {
                formatted = String.format("%0" + precision + "d", Integer.parseInt(formatted));
            }
        }

        return formatted;
    }

    private static String formatValue(RuntimeScalar value, String flags, int width,
                                      int precision, char conversion) {
        // Check for special values first
        double doubleValue = value.getDouble();
        boolean isInf = Double.isInfinite(doubleValue);
        boolean isNaN = Double.isNaN(doubleValue);

        if (isInf || isNaN) {
            return formatSpecialValue(doubleValue, flags, width, conversion);
        }

        // Handle normal values
        switch (conversion) {
            case 'd':
            case 'i':
                return formatInteger(value.getLong(), flags, width, precision, 10, false);

            case 'u':
            case 'U':  // Synonym for %u
                return formatUnsigned(value, flags, width, precision);

            case 'o':
            case 'O':  // Synonym for %o
                return formatInteger(value.getLong(), flags, width, precision, 8, flags.contains("#"));

            case 'x':
                return formatInteger(value.getLong(), flags, width, precision, 16, flags.contains("#"));
            case 'X':
                String result = formatInteger(value.getLong(), flags.replace("X", "x"), width, precision, 16, flags.contains("#"));
                // Convert to uppercase
                return result.toUpperCase();

            case 'b':
            case 'B':
                return formatBinary(value.getLong(), flags, width, precision);

            case 'e':
            case 'E':
            case 'g':
            case 'G':
            case 'a':
            case 'A':
                return formatFloatingPoint(value.getDouble(), flags, width, precision, conversion);

            case 'f':
            case 'F':  // F is synonym for f
                return formatFloatingPoint(value.getDouble(), flags, width, precision, 'f');

            case 'c':
                return formatCharacter(value, flags, width);

            case 's':
                return formatString(value.toString(), flags, width, precision);

            case 'p':
                return String.format("%x", value.getLong());

            case 'n':
                throw new PerlCompilerException("%n specifier not supported");

            case 'D':  // Synonym for %ld
                return formatInteger(value.getLong(), flags, width, precision, 10, false);

            case 'v':
                // Handle standalone %v as invalid
                // throw new PerlCompilerException("Unknown format specifier: %v");
                return "";

            default:
                throw new PerlCompilerException("Unknown format specifier: %" + conversion);
        }
    }

    private static String formatSpecialValue(double value, String flags, int width,
                                             char conversion) {
        String result;

        if (Double.isNaN(value)) {
            result = "NaN";
        } else if (value > 0) {
            result = "Inf";
        } else {
            result = "-Inf";
        }

        // For %c, special values should throw an error
        if (conversion == 'c') {
            throw new PerlCompilerException("Cannot printf " + result + " with 'c'");
        }

        // Apply + flag for positive infinity
        if (flags.contains("+") && result.equals("Inf")) {
            result = "+" + result;
        }

        // Apply width
        if (width > 0) {
            boolean leftAlign = flags.contains("-");
            if (result.length() < width) {
                if (leftAlign) {
                    result = padRight(result, width);
                } else {
                    result = padLeft(result, width);
                }
            }
        }

        return result;
    }

    private static String formatInteger(long value, String flags, int width, int precision,
                                        int base, boolean usePrefix) {
        String result;
        boolean negative = value < 0;
        long absValue = negative ? -value : value;

        // Convert to string in the specified base
        switch (base) {
            case 8:
                result = Long.toOctalString(absValue);
                break;
            case 16:
                result = Long.toHexString(absValue);
                if (flags.contains("X")) {
                    result = result.toUpperCase();
                }
                break;
            case 10:
            default:
                result = Long.toString(absValue);
                break;
        }

        // Apply precision (zero-padding)
        if (precision >= 0) {
            // Special case: precision 0 with value 0 produces empty string
            if (precision == 0 && value == 0) {
                result = "";
                // But # flag with octal still shows "0"
                if (usePrefix && base == 8) {
                    result = "0";
                }
            } else if (result.length() < precision) {
                result = padLeft(result, precision, '0');
            }
        }

        // Add prefix if needed
        if (usePrefix && value != 0 && !result.isEmpty()) {
            switch (base) {
                case 8:
                    if (!result.startsWith("0")) {
                        result = "0" + result;
                    }
                    break;
                case 16:
                    String prefix = flags.contains("X") ? "0X" : "0x";
                    result = prefix + result;
                    break;
            }
        }

        // Add sign
        if (negative) {
            result = "-" + result;
        } else if (flags.contains("+")) {
            result = "+" + result;
        } else if (flags.contains(" ")) {
            result = " " + result;
        }

        // Apply width
        if (width > 0 && result.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && precision < 0 && !leftAlign;

            if (leftAlign) {
                result = padRight(result, width);
            } else if (zeroPad) {
                result = applyZeroPadding(result, width);
            } else {
                result = padLeft(result, width);
            }
        }

        return result;
    }

    private static String formatUnsigned(RuntimeScalar value, String flags, int width,
                                         int precision) {
        long longValue = value.getLong();

        // Convert to unsigned representation
        String result = Long.toUnsignedString(longValue);

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && longValue == 0) {
                result = "";
            } else if (result.length() < precision) {
                result = padLeft(result, precision, '0');
            }
        }

        // Apply width
        if (width > 0 && result.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && precision < 0 && !leftAlign;

            if (leftAlign) {
                result = padRight(result, width);
            } else if (zeroPad) {
                result = padLeft(result, width, '0');
            } else {
                result = padLeft(result, width);
            }
        }

        return result;
    }

    private static String formatBinary(long value, String flags, int width, int precision) {
        String result;
        boolean negative = value < 0;

        // For binary format, treat as unsigned
        if (negative) {
            // Convert to unsigned representation
            result = Long.toBinaryString(value);
        } else {
            result = Long.toBinaryString(value);
        }

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && value == 0) {
                result = "";
                // But # flag still shows prefix
                if (flags.contains("#")) {
                    result = "0";
                }
            } else if (result.length() < precision) {
                result = padLeft(result, precision, '0');
            }
        }

        // Add prefix if needed
        if (flags.contains("#") && value != 0 && !result.isEmpty()) {
            String prefix = (flags.contains("B") || Character.toUpperCase(flags.charAt(flags.length()-1)) == 'B') ? "0B" : "0b";
            result = prefix + result;
        }

        // Binary format ignores +, -, and space flags for sign
        // (binary is always treated as unsigned)

        // Apply width
        if (width > 0 && result.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && precision < 0 && !leftAlign;

            if (leftAlign) {
                result = padRight(result, width);
            } else if (zeroPad) {
                result = applyZeroPaddingBinary(result, width);
            } else {
                result = padLeft(result, width);
            }
        }

        return result;
    }

    private static String formatFloatingPoint(double value, String flags, int width,
                                              int precision, char conversion) {
        if (precision < 0) {
            precision = 6;
        }

        // Handle special case of -0 flag combination which is invalid in Java
        String cleanFlags = flags.replace("-0", "-").replace("0-", "-");
        if (cleanFlags.contains("-") && cleanFlags.contains("0")) {
            cleanFlags = cleanFlags.replace("0", "");
        }

        // Handle # flag for g/G conversions
        if ((conversion == 'g' || conversion == 'G') && cleanFlags.contains("#")) {
            String result = formatFloatingPoint(value, cleanFlags.replace("#", ""),
                    width, precision, conversion);
            // Ensure trailing decimal point if no fractional part
            if (!result.contains(".") && !result.matches(".*[eE][-+]?\\d+")) {
                int eIndex = result.indexOf('e');
                if (eIndex == -1) eIndex = result.indexOf('E');
                if (eIndex != -1) {
                    result = result.substring(0, eIndex) + "." + result.substring(eIndex);
                } else {
                    result += ".";
                }
            }
            return result;
        }

        StringBuilder format = new StringBuilder("%");
        if (cleanFlags.contains("-")) format.append("-");
        if (cleanFlags.contains("+")) format.append("+");
        if (cleanFlags.contains(" ")) format.append(" ");
        if (cleanFlags.contains("0")) format.append("0");
        if (cleanFlags.contains("#")) format.append("#");

        if (width > 0) format.append(width);
        format.append(".").append(precision).append(conversion);

        String result = String.format(format.toString(), value);
        result = result.replace("Infinity", "Inf");
        return result;
    }

    private static String formatCharacter(RuntimeScalar value, String flags, int width) {
        long longValue = value.getLong();
        char c = (char) longValue;
        String result = String.valueOf(c);

        // Apply width - for %c, zero padding means padding with '0' characters, not numeric zero
        if (width > 0) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && !leftAlign;

            if (leftAlign) {
                result = String.format("%-" + width + "s", result);
            } else if (zeroPad) {
                // For %c with zero flag, pad with '0' characters
                result = padLeft(result, width, '0');
            } else {
                result = String.format("%" + width + "s", result);
            }
        }

        return result;
    }

    private static String formatString(String value, String flags, int width,
                                       int precision) {
        // Apply precision (truncate string)
        if (precision >= 0 && value.length() > precision) {
            value = value.substring(0, precision);
        }

        // Apply width
        if (width > 0 && value.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && !leftAlign;

            if (leftAlign) {
                value = padRight(value, width);
            } else if (zeroPad) {
                value = padLeft(value, width, '0');
            } else {
                value = padLeft(value, width);
            }
        }

        return value;
    }

    // Helper methods for padding to avoid String.format issues
    private static String padLeft(String str, int width) {
        return padLeft(str, width, ' ');
    }

    private static String padLeft(String str, int width, char padChar) {
        if (str.length() >= width) return str;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width - str.length(); i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }

    private static String padRight(String str, int width) {
        if (str.length() >= width) return str;
        StringBuilder sb = new StringBuilder(str);
        for (int i = str.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String applyZeroPadding(String str, int width) {
        if (str.length() >= width) return str;

        // Zero padding goes after sign/prefix but before number
        String sign = "";
        String prefix = "";
        String number = str;

        if (str.startsWith("-") || str.startsWith("+") || str.startsWith(" ")) {
            sign = str.substring(0, 1);
            number = str.substring(1);
        }
        if (number.startsWith("0x") || number.startsWith("0X")) {
            prefix = number.substring(0, 2);
            number = number.substring(2);
        } else if (number.startsWith("0") && number.length() > 1) {
            // For octal, don't treat leading 0 as prefix for zero-padding
        }

        int padLength = width - sign.length() - prefix.length() - number.length();
        StringBuilder result = new StringBuilder(sign).append(prefix);
        for (int i = 0; i < padLength; i++) {
            result.append('0');
        }
        result.append(number);
        return result.toString();
    }

    private static String applyZeroPaddingBinary(String str, int width) {
        if (str.length() >= width) return str;

        // Zero padding goes after prefix but before number
        String prefix = "";
        String number = str;

        if (str.startsWith("0b") || str.startsWith("0B")) {
            prefix = str.substring(0, 2);
            number = str.substring(2);
        }

        int padLength = width - prefix.length() - number.length();
        StringBuilder result = new StringBuilder(prefix);
        for (int i = 0; i < padLength; i++) {
            result.append('0');
        }
        result.append(number);
        return result.toString();
    }
}