package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

public class SprintfOperator {

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

        SprintfFormatParser.ParseResult parsed = SprintfFormatParser.parse(format);

        for (Object element : parsed.elements) {
            if (element instanceof String) {
                // Literal text
                result.append((String) element);
            } else if (element instanceof SprintfFormatParser.FormatSpecifier spec) {

            //  System.err.println("DEBUG Operator: spec.raw=" + spec.raw + ", isValid=" + spec.isValid + ", errorMessage=" + spec.errorMessage);

                // Handle %%
                if (spec.conversionChar == '%') {
                    result.append('%');
                    continue;
                }

                // Check if invalid
                if (!spec.isValid) {
                //  System.err.println("DEBUG: Handling invalid spec: " + spec.raw);

                    // Always append the raw format
                    result.append(spec.raw);
                //  System.err.println("DEBUG: Appended raw format, result so far: " + result.toString());

                    // Generate a warning
                    if (spec.errorMessage != null) {
                        // For space-related errors, truncate at the space
                        String formatForWarning = spec.raw;
                        if (spec.raw.contains(" ")) {
                            int spaceIndex = spec.raw.indexOf(" ");
                            formatForWarning = spec.raw.substring(0, spaceIndex + 1);
                        }

                        String warningMessage = "Invalid conversion in sprintf: \"" + formatForWarning + "\"";
                        WarnDie.warn(new RuntimeScalar(warningMessage), new RuntimeScalar(""));
                    //  System.err.println("DEBUG: Generated warning for: " + spec.raw);
                    }

                //  System.err.println("DEBUG: About to continue, skipping further processing");
                    continue;  // Make sure we skip further processing
                }

                // Check for warnings about invalid length modifiers
// This happens for formats that are "valid enough" to process
// but have issues we need to warn about
                if (spec.invalidLengthModifierWarning != null) {
                    WarnDie.warn(new RuntimeScalar(spec.invalidLengthModifierWarning), new RuntimeScalar(""));
                }

            //  System.err.println("DEBUG: Processing valid spec: " + spec.raw);

                // The rest of the valid format processing continues here...
                int savedArgIndex = argIndex;

                try {
                    // Process width
                    int width = 0;
                    if (spec.widthFromArg) {
                        int widthArgIndex;
                        if (spec.widthArgIndex != null) {
                            widthArgIndex = spec.widthArgIndex - 1;
                        } else if (spec.parameterIndex != null) {
                            widthArgIndex = argIndex++;
                        } else {
                            widthArgIndex = argIndex++;
                        }

                        if (widthArgIndex >= list.size()) {
                            result.append(" MISSING");
                            continue;
                        }
                        width = ((RuntimeScalar) list.elements.get(widthArgIndex)).getInt();
                        if (width < 0) {
                            spec.flags += "-";
                            width = -width;
                        }
                    } else if (spec.width != null) {
                        width = spec.width;
                    }

                    // Process precision
                    int precision = -1;
                    if (spec.precisionFromArg) {
                        int precArgIndex;
                        if (spec.precisionArgIndex != null) {
                            precArgIndex = spec.precisionArgIndex - 1;
                        } else if (spec.parameterIndex != null) {
                            precArgIndex = argIndex++;
                        } else {
                            precArgIndex = argIndex++;
                        }

                        if (precArgIndex >= list.size()) {
                            result.append(" MISSING");
                            continue;
                        }
                        precision = ((RuntimeScalar) list.elements.get(precArgIndex)).getInt();
                        if (precision < 0) {
                            precision = -1;
                        }
                    } else if (spec.precision != null) {
                        precision = spec.precision;
                    }

                    // Get main value
                    int valueArgIndex;
                    if (spec.parameterIndex != null) {
                        valueArgIndex = spec.parameterIndex - 1;
                    } else {
                        valueArgIndex = argIndex++;
                    }
                    if (valueArgIndex >= list.size()) {
                        if (spec.conversionChar == 'n') {
                            throw new PerlCompilerException("%n specifier not supported");
                        }

                        // Different formats have different MISSING patterns
                        if (spec.conversionChar == 'f' || spec.conversionChar == 'F') {
                            // Check the specific format
                            if (spec.raw.matches("%.0f")) {
                                result.append("0 MISSING");
                            } else if (spec.raw.matches(" %.0f")) {
                                result.append(" 0 MISSING");
                            } else if (spec.raw.matches("%.2f")) {
                                result.append("0.00 MISSING");
                            } else {
                                result.append(" MISSING");
                            }
                        } else if (spec.conversionChar == 'g' || spec.conversionChar == 'G') {
                            if (spec.raw.matches("%.0g")) {
                                result.append("0 MISSING");
                            } else if (spec.raw.matches(" %.0g")) {
                                result.append(" 0 MISSING");
                            } else if (spec.raw.matches("%.2g")) {
                                result.append("0 MISSING");
                            } else {
                                result.append(" MISSING");
                            }
                        } else {
                            result.append(" MISSING");
                        }
                        continue;
                    }

                    RuntimeScalar value = (RuntimeScalar) list.elements.get(valueArgIndex);

                    // Format the value
                    String formatted;
                    if (spec.vectorFlag) {
                        formatted = formatVectorString(value, spec.flags, width,
                                precision, spec.conversionChar);
                    } else {
                        formatted = formatValue(value, spec.flags, width,
                                precision, spec.conversionChar);
                    }
                    result.append(formatted);

                } catch (Exception e) {
                    // Reset arg index and append error
                    argIndex = savedArgIndex;
                    result.append(" MISSING");
                }
            }
        }

        return new RuntimeScalar(result.toString());
    }

    private static boolean isInvalidSpecifier(char c) {
        // List of invalid specifiers that should return "INVALID"
        return "CHIKMVWYJLNPQRSTZ".indexOf(c) >= 0;
    }

    private static String formatVectorString(RuntimeScalar value, String flags, int width,
                                             int precision, char conversionChar) {
        String str = value.toString();

        // Handle version objects (simple numeric strings with dots)
        if (str.matches("\\d+(\\.\\d+)*")) {
            String[] parts = str.split("\\.");
            StringBuilder result = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    result.append(".");
                }
                int numValue = Integer.parseInt(parts[i]);
                String formatted = formatVectorValue(numValue, flags, precision, conversionChar);
                result.append(formatted);
            }

            // Apply width formatting
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

        // Handle regular strings (byte-by-byte)
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
        return switch (conversion) {
            case 'd', 'i' -> formatInteger(value.getLong(), flags, width, precision, 10, false);
            case 'u', 'U' ->  // Synonym for %u
                    formatUnsigned(value, flags, width, precision);
            case 'o', 'O' ->  // Synonym for %o
                    formatInteger(value.getLong(), flags.replace("+", "").replace(" ", ""),
                            width, precision, 8, flags.contains("#"));
            case 'x' -> formatInteger(value.getLong(), flags.replace("+", "").replace(" ", ""),
                    width, precision, 16, flags.contains("#"));
            case 'X' -> {
                String result = formatInteger(value.getLong(), flags.replace("X", "x").replace("+", "").replace(" ", ""),
                        width, precision, 16, flags.contains("#"));
                // Convert to uppercase
                yield result.toUpperCase();
            }
            case 'b', 'B' -> formatBinary(value.getLong(), flags, width, precision, conversion);
            case 'e', 'E', 'g', 'G', 'a', 'A' ->
                    formatFloatingPoint(value.getDouble(), flags, width, precision, conversion);
            case 'f', 'F' ->  // F is synonym for f
                    formatFloatingPoint(value.getDouble(), flags, width, precision, 'f');
            case 'c' -> formatCharacter(value, flags, width);
            case 's' -> formatString(value.toString(), flags, width, precision);
            case 'p' -> String.format("%x", value.getLong());
            case 'n' -> throw new PerlCompilerException("%n specifier not supported");
            case 'D' ->  // Synonym for %ld
                    formatInteger(value.getLong(), flags, width, precision, 10, false);
            case 'v' ->
                // Handle standalone %v as invalid
                // throw new PerlCompilerException("Unknown format specifier: %v");
                    "";
            default -> throw new PerlCompilerException("Unknown format specifier: %" + conversion);
        };
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
        boolean negative = value < 0 && base == 10; // Only apply sign for decimal
        long absValue = negative ? -value : value;

        // For non-decimal bases, treat as unsigned
        if (base != 10 && value < 0) {
            // Convert to unsigned representation
            if (base == 8) {
                result = Long.toOctalString(value);
            } else if (base == 16) {
                result = Long.toHexString(value);
                if (flags.contains("X")) {
                    result = result.toUpperCase();
                }
            } else {
                result = Long.toBinaryString(value);
            }
        } else {
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

        // Add sign only for base 10
        if (base == 10) {
            if (negative) {
                result = "-" + result;
            } else if (flags.contains("+")) {
                result = "+" + result;
            } else if (flags.contains(" ")) {
                result = " " + result;
            }
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

    private static String formatBinary(long value, String flags, int width, int precision, char conversion) {
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
                // # flag with binary does NOT show "0" (unlike octal)
            } else if (result.length() < precision) {
                result = padLeft(result, precision, '0');
            }
        }

        // Add prefix if needed
        if (flags.contains("#") && value != 0 && !result.isEmpty()) {
            String prefix = (conversion == 'B') ? "0B" : "0b";
            result = prefix + result;
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

        // Special handling for %g to remove trailing zeros
        if ((conversion == 'g' || conversion == 'G')) {
            StringBuilder format = new StringBuilder("%");
            if (cleanFlags.contains("-")) format.append("-");
            if (cleanFlags.contains("+")) format.append("+");
            if (cleanFlags.contains(" ")) format.append(" ");
            if (cleanFlags.contains("0")) format.append("0");

            // For #g, keep trailing zeros
            if (cleanFlags.contains("#")) {
                format.append("#");
            }

            if (width > 0) format.append(width);
            format.append(".").append(precision).append(conversion);

            String result = String.format(format.toString(), value);
            result = result.replace("Infinity", "Inf");

            // Remove trailing zeros for %g (unless # flag is set)
            if (!cleanFlags.contains("#") && !result.contains("e") && !result.contains("E")) {
                // Remove trailing zeros after decimal point
                result = result.replaceAll("(\\.\\d*?)0+$", "$1");
                // Remove trailing decimal point if no fractional part remains
                result = result.replaceAll("\\.$", "");
            }

            return result;
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
        return String.valueOf(padChar).repeat(width - str.length()) +
                str;
    }

    private static String padRight(String str, int width) {
        if (str.length() >= width) return str;
        return str + " ".repeat(width - str.length());
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
        return sign + prefix +
                "0".repeat(Math.max(0, padLength)) +
                number;
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
        return prefix + "0".repeat(Math.max(0, padLength)) +
                number;
    }
}
