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
            "%(\\d+\\$)?([-+ #0]*)(\\*(?:\\d+\\$)?)?([*]?)(\\d*)(?:\\.(\\*(?:\\d+\\$)?|\\d*))?([*]?)(?:(hh|h|ll|l|t|z|q|L|V)?)(v?)([diouxXeEfFgGaAcspnvDUOBb%])"
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

            // Handle invalid combinations
            if (vectorFlag && !"diouxXbB".contains(String.valueOf(conversionChar))) {
                throw new PerlCompilerException("Invalid vector format %" + matcher.group(9) + conversionChar);
            }

            // Get width from argument if needed
            int width = 0;
            if (widthFromArg || widthFromArgSpec != null) {
                if (argIndex >= list.size()) {
                    throw new PerlCompilerException("Missing argument for sprintf");
                }
                width = ((RuntimeScalar) list.elements.get(argIndex++)).getInt();
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
                if (argIndex >= list.size()) {
                    throw new PerlCompilerException("Missing argument for sprintf");
                }
                precision = ((RuntimeScalar) list.elements.get(argIndex++)).getInt();
                if (precision < 0) {
                    precision = -1; // Negative precision is ignored
                }
            } else if (precisionSpec != null && !precisionSpec.startsWith("*")) {
                precision = precisionSpec.isEmpty() ? 0 : Integer.parseInt(precisionSpec);
            }

            // Get the value to format
            if (argIndex >= list.size()) {
                // Add undefined value if missing
                list.elements.add(scalarUndef);
            }
            RuntimeScalar value = (RuntimeScalar) list.elements.get(argIndex++);

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

    private static String formatVectorString(RuntimeScalar value, String flags, int width,
                                             int precision, char conversionChar) {
        String str = value.toString();
        if (str.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        byte[] bytes = str.getBytes();

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                result.append(".");
            }

            // Convert byte to unsigned int (0-255)
            int byteValue = bytes[i] & 0xFF;

            // Format according to conversion character
            String formatted;
            switch (conversionChar) {
                case 'd':
                case 'i':
                    formatted = String.valueOf(byteValue);
                    break;
                case 'o':
                    formatted = Integer.toOctalString(byteValue);
                    break;
                case 'x':
                    formatted = Integer.toHexString(byteValue);
                    break;
                case 'X':
                    formatted = Integer.toHexString(byteValue).toUpperCase();
                    break;
                case 'b':
                    formatted = Integer.toBinaryString(byteValue);
                    break;
                case 'B':
                    formatted = Integer.toBinaryString(byteValue);
                    break;
                default:
                    formatted = String.valueOf(byteValue);
            }

            // Apply precision (zero-padding)
            if (precision > 0 && formatted.length() < precision) {
                formatted = String.format("%0" + precision + "s", formatted);
            }

            result.append(formatted);
        }

        // Apply width formatting if specified
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
            case 'X':
                return formatInteger(value.getLong(), flags, width, precision, 16, flags.contains("#"));

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
                return formatCharacter(value);

            case 's':
                return formatString(value.toString(), flags, width, precision);

            case 'p':
                return String.format("%x", value.getLong());

            case 'n':
                throw new PerlCompilerException("%n specifier not supported");

            case 'D':  // Synonym for %ld
                return formatInteger(value.getLong(), flags, width, precision, 10, false);

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
                result = flags.contains("X") ? Long.toHexString(absValue).toUpperCase()
                        : Long.toHexString(absValue);
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
                result = String.format("%0" + precision + "s", result);
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
                result = String.format("%-" + width + "s", result);
            } else if (zeroPad) {
                // Zero padding goes after sign/prefix but before number
                String sign = "";
                String prefix = "";
                String number = result;

                if (result.startsWith("-") || result.startsWith("+") || result.startsWith(" ")) {
                    sign = result.substring(0, 1);
                    number = result.substring(1);
                }
                if (number.startsWith("0x") || number.startsWith("0X")) {
                    prefix = number.substring(0, 2);
                    number = number.substring(2);
                } else if (base == 8 && number.startsWith("0") && number.length() > 1) {
                    prefix = "0";
                    number = number.substring(1);
                }

                int padLength = width - sign.length() - prefix.length();
                if (padLength > number.length()) {
                    number = String.format("%0" + padLength + "s", number);
                }
                result = sign + prefix + number;
            } else {
                result = String.format("%" + width + "s", result);
            }
        }

        return result;
    }

    private static String formatUnsigned(RuntimeScalar value, String flags, int width,
                                         int precision) {
        long longValue = value.getLong();

        // Convert to unsigned representation
        String result;
        if (longValue >= 0) {
            result = Long.toString(longValue);
        } else {
            // For negative values, add 2^64
            result = Long.toUnsignedString(longValue);
        }

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && longValue == 0) {
                result = "";
            } else if (result.length() < precision) {
                result = String.format("%0" + precision + "s", result);
            }
        }

        // Apply width
        if (width > 0 && result.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && precision < 0 && !leftAlign;

            if (leftAlign) {
                result = String.format("%-" + width + "s", result);
            } else if (zeroPad) {
                result = String.format("%0" + width + "s", result);
            } else {
                result = String.format("%" + width + "s", result);
            }
        }

        return result;
    }

    private static String formatBinary(long value, String flags, int width, int precision) {
        boolean negative = value < 0;
        long absValue = negative ? -value : value;
        String result = Long.toBinaryString(absValue);

        // Apply precision (zero-padding)
        if (precision >= 0) {
            if (precision == 0 && value == 0) {
                result = "";
                // But # flag still shows prefix
                if (flags.contains("#")) {
                    result = "0";
                }
            } else if (result.length() < precision) {
                result = String.format("%0" + precision + "s", result);
            }
        }

        // Add prefix if needed
        if (flags.contains("#") && value != 0 && !result.isEmpty()) {
            String prefix = flags.contains("B") ? "0B" : "0b";
            result = prefix + result;
        }

        // Add sign for negative values (shouldn't happen with unsigned, but just in case)
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
                result = String.format("%-" + width + "s", result);
            } else if (zeroPad) {
                result = String.format("%0" + width + "s", result);
            } else {
                result = String.format("%" + width + "s", result);
            }
        }

        return result;
    }

    private static String formatFloatingPoint(double value, String flags, int width,
                                              int precision, char conversion) {
        // Set default precision if not specified
        if (precision < 0) {
            precision = (conversion == 'g' || conversion == 'G') ? 6 : 6;
        }

        StringBuilder spec = new StringBuilder("%");
        spec.append(flags);
        if (width > 0) spec.append(width);
        spec.append(".").append(precision);
        spec.append(conversion);

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

        return String.valueOf((char)value.getLong());
    }

    private static String formatString(String value, String flags, int width,
                                       int precision) {
        // Apply precision (truncate string)
        if (precision >= 0 && value.length() > precision) {
            value = value.substring(0, precision);
        }

        // Apply width - manual padding to avoid Java formatter flag conflicts
        if (width > 0 && value.length() < width) {
            boolean leftAlign = flags.contains("-");
            boolean zeroPad = flags.contains("0") && !leftAlign;

            int padCount = width - value.length();
            StringBuilder padded = new StringBuilder();

            if (leftAlign) {
                padded.append(value);
                for (int i = 0; i < padCount; i++) {
                    padded.append(' ');
                }
            } else if (zeroPad) {
                // Zero padding for strings
                for (int i = 0; i < padCount; i++) {
                    padded.append('0');
                }
                padded.append(value);
            } else {
                for (int i = 0; i < padCount; i++) {
                    padded.append(' ');
                }
                padded.append(value);
            }
            value = padded.toString();
        }

        return value;
    }
}