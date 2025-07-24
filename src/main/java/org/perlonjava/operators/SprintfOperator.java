package org.perlonjava.operators;

import org.perlonjava.runtime.PerlCompilerException;
import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.util.Arrays;
import java.util.IllegalFormatException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // The format string that specifies how the elements should be formatted
        String format = runtimeScalar.toString();

        StringBuilder result = new StringBuilder();
        int argIndex = 0;

        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);

            if (c == '%' && i + 1 < format.length()) {
                char next = format.charAt(i + 1);

                if (next == '%') {
                    // %% -> literal %
                    result.append('%');
                    i++; // Skip the second %
                } else {
                    // Find the complete format specifier
                    Pattern pattern = Pattern.compile("%[\\-\\+\\d\\s#]*\\.?\\d*[a-zA-Z]");
                    Matcher matcher = pattern.matcher(format.substring(i));

                    if (matcher.find() && matcher.start() == 0 && argIndex < list.size()) {
                        String specifier = matcher.group();
                        RuntimeScalar element = (RuntimeScalar) list.elements.get(argIndex);

                        // Format this single element
                        String formatted = formatSingleElement(specifier, element);
                        result.append(formatted);

                        argIndex++;
                        i += specifier.length() - 1; // Move past the entire specifier
                    } else {
                        // Not a valid format specifier or no more arguments
                        result.append(c);
                    }
                }
            } else {
                // Regular character
                result.append(c);
            }
        }

        return new RuntimeScalar(result.toString());
    }

    private static String formatSingleElement(String specifier, RuntimeScalar element) {
        char formatChar = specifier.charAt(specifier.length() - 1);

        try {
            switch (Character.toLowerCase(formatChar)) {
                case 'd':
                case 'i':
                case 'u':
                    return String.format(specifier, element.getInt());
                case 'x':
                    return String.format(specifier, element.getInt());
                case 'o':
                    return String.format(specifier, element.getInt());
                case 'b':
                    // Special handling for binary format
                    int intValue = element.getInt();
                    String binary = (intValue >= 0 && intValue <= 255)
                            ? String.format("%8s", Integer.toBinaryString(intValue)).replace(' ', '0')
                            : String.format("%32s", Integer.toBinaryString(intValue)).replace(' ', '0');
                    return binary;
                case 'f':
                case 'e':
                case 'g':
                case 'a':
                    return String.format(specifier, element.getDouble());
                case 'c':
                    return String.format(specifier, (char) element.getInt());
                case 'p':
                    return String.format("%x", element.getInt());
                case 'v':
                    // Version string format: treats string as vector of integers
                    return formatVersionString(specifier, element);
                case 'n':
                    throw new PerlCompilerException("%n specifier not supported");
                default: // 's' and others
                    return String.format(specifier, element.toString());
            }
        } catch (Exception e) {
            // If formatting fails, fall back to string representation
            // return element.toString();
            throw new PerlCompilerException("sprintf() error: " + e.getMessage());
        }
    }

    /**
     * Format a version string according to %v specification
     * %vd means use dots as separator and format as decimal
     * %vx means use dots as separator and format as hex
     * %v8b means use dots as separator and format as 8-bit binary
     */
    private static String formatVersionString(String specifier, RuntimeScalar element) {
        String value = element.toString();
        StringBuilder result = new StringBuilder();

        // Extract the format character after v (default is 'd' for decimal)
        char subFormat = 'd';
        if (specifier.length() > 2) {
            subFormat = specifier.charAt(specifier.length() - 1);
        }

        // For version objects (like v5.42.0), parse the numeric parts
        if (value.startsWith("v") && value.matches("v[\\d.]+")) {
            // Remove the 'v' prefix and split by dots
            String[] parts = value.substring(1).split("\\.");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) result.append(".");
                int num = Integer.parseInt(parts[i]);
                result.append(formatVersionPart(num, subFormat));
            }
        } else {
            // Treat each character as a byte value
            byte[] bytes = value.getBytes();
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) result.append(".");
                int num = bytes[i] & 0xFF;
                result.append(formatVersionPart(num, subFormat));
            }
        }

        return result.toString();
    }

    private static String formatVersionPart(int value, char format) {
        switch (Character.toLowerCase(format)) {
            case 'x':
                return String.format("%x", value);
            case 'o':
                return String.format("%o", value);
            case 'b':
                return Integer.toBinaryString(value);
            case 'd':
            default:
                return String.valueOf(value);
        }
    }
}
