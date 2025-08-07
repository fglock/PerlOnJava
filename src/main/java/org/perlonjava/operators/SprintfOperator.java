package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SprintfOperator {

    // Pattern to match a complete format specifier
    // Updated to handle size modifiers like hh, ll, t, z
    private static final Pattern FORMAT_PATTERN = Pattern.compile(
            "%([-+ #0]*)([*]?)(\\d*)(?:\\.(\\d*))?([*]?)(?:(hh|h|ll|l|t|z|q|L|V)?)([diouxXeEfFgGaAcspnvDUOBb%])"
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
            String flags = matcher.group(1);
            boolean widthFromArg = !matcher.group(2).isEmpty();
            String widthStr = matcher.group(3);
            String precisionStr = matcher.group(4);
            boolean precisionFromArg = !matcher.group(5).isEmpty();
            String lengthModifier = matcher.group(6);
            char conversionChar = matcher.group(7).charAt(0);

            // Handle %% - literal percent
            if (conversionChar == '%') {
                result.append('%');
                continue;
            }

            // Get width from argument if needed
            int width = 0;
            if (widthFromArg) {
                if (argIndex >= list.size()) {
                    throw new PerlCompilerException("Missing argument for sprintf");
                }
                width = ((RuntimeScalar) list.elements.get(argIndex++)).getInt();
            } else if (!widthStr.isEmpty()) {
                width = Integer.parseInt(widthStr);
            }

            // Get precision from argument if needed
            int precision = -1;
            if (precisionFromArg) {
                if (argIndex >= list.size()) {
                    throw new PerlCompilerException("Missing argument for sprintf");
                }
                precision = ((RuntimeScalar) list.elements.get(argIndex++)).getInt();
            } else if (precisionStr != null && !precisionStr.isEmpty()) {
                precision = Integer.parseInt(precisionStr);
            }

            // Get the value to format
            if (argIndex >= list.size()) {
                throw new PerlCompilerException("Missing argument for sprintf");
            }
            RuntimeScalar value = (RuntimeScalar) list.elements.get(argIndex++);

            // Format the value
            String formatted = formatValue(value, flags, width, precision, conversionChar);
            result.append(formatted);
        }

        // Append any remaining text
        result.append(format.substring(pos));

        return new RuntimeScalar(result.toString());
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
                return formatInteger(value.getInt(), flags, width, precision, "%d");

            case 'u':
                return formatUnsigned(value, flags, width, precision);

            case 'o':
            case 'O':  // Add support for uppercase O
                return formatInteger(value.getInt(), flags, width, precision, "%o");

            case 'x':
            case 'X':
                String hexFormat = conversion == 'x' ? "%x" : "%X";
                return formatInteger(value.getInt(), flags, width, precision, hexFormat);

            case 'b':
            case 'B':  // Add support for uppercase B
                return formatBinary(value.getInt(), width);

            case 'e':
            case 'E':
            case 'f':
            case 'F':
            case 'g':
            case 'G':
            case 'a':
            case 'A':
                return formatFloatingPoint(value.getDouble(), flags, width, precision,
                        "%" + conversion);

            case 'c':
                return formatCharacter(value);

            case 's':
                return formatString(value.toString(), flags, width, precision);

            case 'p':
                return String.format("%x", value.getInt());

            case 'n':
                throw new PerlCompilerException("%n specifier not supported");

            case 'v':
                // %v is handled separately in the main loop
                throw new PerlCompilerException("Internal error: %v should be handled separately");

            case 'D':  // Add support for D format
                return formatFloatingPoint(value.getDouble(), flags, width, precision, "%f");

            case 'U':  // Add support for U format
                return formatUnsigned(value, flags, width, precision);

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
                    result = String.format("%-" + width + "s", result);
                } else {
                    result = String.format("%" + width + "s", result);
                }
            }
        }

        return result;
    }

    private static String formatInteger(int value, String flags, int width,
                                        int precision, String format) {
        StringBuilder spec = new StringBuilder("%");
        spec.append(flags);
        if (width > 0) spec.append(width);
        if (precision >= 0) spec.append(".").append(precision);
        spec.append(format.charAt(format.length() - 1));

        return String.format(spec.toString(), value);
    }

    private static String formatUnsigned(RuntimeScalar value, String flags, int width,
                                         int precision) {
        double dValue = value.getDouble();

        // Handle 64-bit unsigned conversion
        if (dValue < 0) {
            // Convert negative to unsigned by adding 2^64
            dValue = 18446744073709551616.0 + dValue;
        }

        // Clamp to max uint64 if overflow
        if (dValue >= 18446744073709551616.0) {
            dValue = 18446744073709551615.0; // 2^64 - 1
        }

        // Build format specifier
        StringBuilder spec = new StringBuilder("%");
        spec.append(flags);
        if (width > 0) spec.append(width);
        if (precision >= 0) spec.append(".").append(precision);
        spec.append("d");

        // Format as long (Java long is 64-bit signed, which can represent uint64 values)
        return String.format(spec.toString(), (long)dValue);
    }

    private static String formatBinary(int value, int width) {
        String binary = Integer.toBinaryString(value);

        // Apply default width if not specified
        if (width == 0) {
            width = (value >= 0 && value <= 255) ? 8 : 32;
        }

        // Pad with zeros
        if (binary.length() < width) {
            binary = String.format("%" + width + "s", binary).replace(' ', '0');
        }

        return binary;
    }

    private static String formatFloatingPoint(double value, String flags, int width,
                                              int precision, String format) {
        StringBuilder spec = new StringBuilder("%");
        spec.append(flags);
        if (width > 0) spec.append(width);
        if (precision >= 0) spec.append(".").append(precision);
        spec.append(format.charAt(format.length() - 1));

        String result = String.format(spec.toString(), value);

        // Convert Java's "Infinity" to Perl's "Inf"
        result = result.replace("Infinity", "Inf");
        result = result.replace("INFINITY", "INF");

        return result;
    }

    private static String formatCharacter(RuntimeScalar value) {
        double dValue = value.getDouble();

        // Check for special values
        if (Double.isInfinite(dValue) || Double.isNaN(dValue)) {
            String special = Double.isNaN(dValue) ? "NaN" :
                    (dValue > 0 ? "Inf" : "-Inf");
            throw new PerlCompilerException("Cannot printf " + special + " with 'c'");
        }

        return String.valueOf((char)value.getInt());
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
            if (leftAlign) {
                value = String.format("%-" + width + "s", value);
            } else {
                value = String.format("%" + width + "s", value);
            }
        }

        return value;
    }
}