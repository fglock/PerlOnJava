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
                    // For %v, we need to match the full pattern including the format char after v
                    Pattern pattern = Pattern.compile("%([\\-\\+\\d\\s#]*)(\\*?)v(\\d*[a-zA-Z])");
                    Matcher vMatcher = pattern.matcher(format.substring(i));

                    if (vMatcher.find() && vMatcher.start() == 0) {
                        // This is a %v format
                        String fullSpecifier = vMatcher.group();
                        String modifiers = vMatcher.group(1);
                        boolean hasStar = !vMatcher.group(2).isEmpty();
                        String subFormat = vMatcher.group(3);

                        // Get the separator if * is specified
                        String separator = ".";
                        if (hasStar && argIndex < list.size()) {
                            separator = ((RuntimeScalar) list.elements.get(argIndex)).toString();
                            argIndex++;
                        }

                        // Get the value to format
                        if (argIndex < list.size()) {
                            RuntimeScalar element = (RuntimeScalar) list.elements.get(argIndex);
                            String formatted = formatVersionString(element, modifiers, subFormat, separator);
                            result.append(formatted);
                            argIndex++;
                        }

                        i += fullSpecifier.length() - 1;
                    } else {
                        // Try regular format pattern
                        pattern = Pattern.compile("%[\\-\\+\\d\\s#]*\\.?\\d*[a-zA-Z]");
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
     * @param element The value to format
     * @param modifiers Format modifiers (like 0 for zero padding)
     * @param subFormat The format to apply to each integer (like "d", "x", "8b")
     * @param separator The separator between values
     */
    private static String formatVersionString(RuntimeScalar element, String modifiers,
                                              String subFormat, String separator) {
        String value = element.toString();
        StringBuilder result = new StringBuilder();

        // Extract the format character and any width specification
        char formatChar = subFormat.charAt(subFormat.length() - 1);
        String widthStr = subFormat.substring(0, subFormat.length() - 1);

        // Build the format string for each integer
        String itemFormat = "%" + modifiers + widthStr + formatChar;

        // For version objects (like v5.42.0), parse the numeric parts
        if (value.startsWith("v") && value.matches("v[\\d.]+")) {
            // Remove the 'v' prefix and split by dots
            String[] parts = value.substring(1).split("\\.");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) result.append(separator);
                int num = Integer.parseInt(parts[i]);
                result.append(formatSingleInteger(num, itemFormat, formatChar));
            }
        } else {
            // Treat each character as a byte value
            byte[] bytes = value.getBytes();
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) result.append(separator);
                int num = bytes[i] & 0xFF;
                result.append(formatSingleInteger(num, itemFormat, formatChar));
            }
        }

        return result.toString();
    }

    private static String formatSingleInteger(int value, String format, char formatChar) {
        try {
            switch (Character.toLowerCase(formatChar)) {
                case 'd':
                case 'i':
                case 'u':
                case 'x':
                case 'o':
                    return String.format(format, value);
                case 'b':
                    // Binary format - extract width if specified
                    String binary = Integer.toBinaryString(value);
                    // Check if there's a width specification
                    Pattern p = Pattern.compile("%(\\d*)(\\d+)b");
                    Matcher m = p.matcher(format);
                    if (m.find() && !m.group(2).isEmpty()) {
                        int width = Integer.parseInt(m.group(2));
                        binary = String.format("%" + width + "s", binary).replace(' ', '0');
                    }
                    return binary;
                default:
                    return String.valueOf(value);
            }
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
