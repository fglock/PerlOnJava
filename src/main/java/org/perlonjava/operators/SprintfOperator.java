package org.perlonjava.operators;

import org.perlonjava.runtime.*;

/**
 * Implements Perl's sprintf operator for formatted string output.
 *
 * This class serves as the main entry point for sprintf operations,
 * coordinating between the format parser and various formatters.
 *
 * @see SprintfFormatParser for format string parsing
 * @see SprintfValueFormatter for value formatting logic
 */
public class SprintfOperator {

    /**
     * Formats the elements according to the specified format string.
     *
     * This method implements Perl's sprintf function, supporting:
     * - Positional parameters (e.g., %2$d)
     * - Various conversion specifiers (d, s, f, x, etc.)
     * - Flags (-, +, space, #, 0)
     * - Width and precision specifications
     * - Vector formats (e.g., %vd)
     *
     * @param runtimeScalar The format string
     * @param list          The list of elements to be formatted
     * @return A RuntimeScalar containing the formatted string
     */
    private static int charsWritten = 0;

    public static RuntimeScalar sprintf(RuntimeScalar runtimeScalar, RuntimeList list) {
        charsWritten = 0;  // Reset counter
        // Expand the list to ensure all elements are available
        list = new RuntimeList((RuntimeBase) list);
        String format = runtimeScalar.toString();

        StringBuilder result = new StringBuilder();
        int argIndex = 0;

        // Parse the format string into literals and format specifiers
        SprintfFormatParser.ParseResult parsed = SprintfFormatParser.parse(format);

        // Create formatter instance for value formatting
        SprintfValueFormatter formatter = new SprintfValueFormatter();

        for (Object element : parsed.elements) {
            if (element instanceof String) {
                String literal = (String) element;
                result.append(literal);
                charsWritten += literal.length();
            } else if (element instanceof SprintfFormatParser.FormatSpecifier spec) {
                // Check if this is an overlapping specifier (warning only, no output)
                if (spec.isOverlapping) {
                    // Only generate warning, don't add to output
                    if (!spec.isValid) {
                        // Just generate the warning, don't add to result
                        handleInvalidSpecifier(spec);
                    }
                    continue; // Skip adding to result and updating argIndex
                }

                // Check if spec is invalid FIRST
                if (!spec.isValid) {
                    String formatted = processFormatSpecifier(spec, list, argIndex, formatter);
                    result.append(formatted);
                    charsWritten += formatted.length();
                    continue;
                }

                if (spec.conversionChar == 'n') {
                    // %n doesn't produce output, but does consume an argument
                    handlePercentN(spec, list, argIndex);

                    // Update argument index
                    if (spec.parameterIndex == null) {
                        argIndex++;  // %n does consume an argument
                        if (spec.widthFromArg && spec.widthArgIndex == null) argIndex++;
                        if (spec.precisionFromArg && spec.precisionArgIndex == null) argIndex++;
                    }

                    // Don't add anything to result or charsWritten
                    continue;  // Skip to next format element
                } else {
                    String formatted = processFormatSpecifier(spec, list, argIndex, formatter);
                    result.append(formatted);
                    charsWritten += formatted.length();

                    // Update argument index if not using positional parameters
                    if (spec.parameterIndex == null && spec.conversionChar != '%') {
                        argIndex = updateArgIndex(spec, argIndex);
                    }
                }
            }
        }

        return new RuntimeScalar(result.toString());
    }

    private static void handlePercentN(SprintfFormatParser.FormatSpecifier spec,
                                       RuntimeList list, int argIndex) {
        int targetIndex = spec.parameterIndex != null ? spec.parameterIndex - 1 : argIndex;
        if (targetIndex < list.size()) {
            RuntimeScalar target = (RuntimeScalar) list.elements.get(targetIndex);
            // In Perl, %n modifies the original variable, not a copy
            target.set(new RuntimeScalar(charsWritten));
        }
    }

    /**
     * Process a single format specifier.
     *
     * @param spec      The parsed format specifier
     * @param list      The argument list
     * @param argIndex  Current argument index
     * @param formatter The value formatter instance
     * @return The formatted string
     */
    private static String processFormatSpecifier(
            SprintfFormatParser.FormatSpecifier spec,
            RuntimeList list,
            int argIndex,
            SprintfValueFormatter formatter) {

        System.err.println("DEBUG processFormatSpecifier: raw='" + spec.raw +
                      "', isValid=" + spec.isValid + ", errorMessage=" + spec.errorMessage);

        // Handle invalid specifiers FIRST (before %% check)
        if (!spec.isValid) {
            return handleInvalidSpecifier(spec);
        }

        // Handle %% - literal percent sign
        if (spec.conversionChar == '%') {
            if (spec.widthFromArg) {
                FormatArguments args = extractFormatArguments(spec, list, argIndex);
                // Consume the width argument but still return %
            }
            return "%";
        }

        // Check if conversion character is missing
        if (spec.conversionChar == '\0') {
            return handleInvalidSpecifier(spec);
        }

        // Process width, precision, and value arguments
        FormatArguments args = extractFormatArguments(spec, list, argIndex);

        // Check for missing value argument
        if (args.valueArgIndex >= list.size()) {
            return handleMissingArgument(spec, args);
        }

        // Get the value to format
        RuntimeScalar value = (RuntimeScalar) list.elements.get(args.valueArgIndex);

        // Special handling for %*v formats where * is the separator
        if (spec.vectorFlag) {
            // Check if this is %*v format (custom separator)
            if (spec.widthFromArg && spec.raw.matches(".*\\*v.*")) {
                // %*v format - * is for separator
                String separator = ".";
                int sepArgIndex = argIndex;

                if (sepArgIndex < list.size()) {
                    separator = ((RuntimeScalar) list.elements.get(sepArgIndex)).toString();
                }

                int actualWidth = 0;
                int valueIndex;

                if (spec.precisionFromArg) {
                    // %*v*d format - second * is for width
                    int widthArgIndex = sepArgIndex + 1;
                    if (widthArgIndex < list.size()) {
                        actualWidth = ((RuntimeScalar) list.elements.get(widthArgIndex)).getInt();
                    }
                    valueIndex = widthArgIndex + 1;
                } else {
                    // %*vd or %*v2d format
                    actualWidth = spec.width != null ? spec.width : 0;
                    valueIndex = sepArgIndex + 1;
                }

                if (valueIndex >= list.size()) {
                    return handleMissingArgument(spec, args);
                }
                value = (RuntimeScalar) list.elements.get(valueIndex);

                return formatter.formatVectorString(value, spec.flags, actualWidth,
                        args.precision, spec.conversionChar, separator);
            } else if (spec.widthFromArg) {
                // %v*d format - * is for width, not separator
                // Use default separator and get width from args
                return formatter.formatVectorString(value, spec.flags, args.width,
                        args.precision, spec.conversionChar);
            } else {
                // Regular vector format
                return formatter.formatVectorString(value, spec.flags, args.width,
                        args.precision, spec.conversionChar);
            }
        } else {
            // Non-vector format
            return formatter.formatValue(value, spec.flags, args.width,
                    args.precision, spec.conversionChar);
        }
    }

    /**
     * Container for format arguments (width, precision, value index).
     */
    private static class FormatArguments {
        int width = 0;
        int precision = -1;
        int valueArgIndex;
        int consumedArgs = 0;
    }

    /**
     * Extract width, precision, and value index from the format specifier.
     */
    private static FormatArguments extractFormatArguments(
            SprintfFormatParser.FormatSpecifier spec,
            RuntimeList list,
            int argIndex) {

        FormatArguments args = new FormatArguments();
        int currentArgIndex = argIndex;

        // Process width
        if (spec.widthFromArg) {
            int widthArgIndex;
            if (spec.widthArgIndex != null) {
                // Explicit index like %*2$d
                widthArgIndex = spec.widthArgIndex - 1;
            } else if (spec.parameterIndex != null) {
                // When we have %2$*d, the width comes from the current position
                widthArgIndex = currentArgIndex;
                currentArgIndex++;
                args.consumedArgs++;
            } else {
                // Regular %*d - width comes from current position
                widthArgIndex = currentArgIndex;
                currentArgIndex++;
                args.consumedArgs++;
            }

            if (widthArgIndex < list.size()) {
                args.width = ((RuntimeScalar) list.elements.get(widthArgIndex)).getInt();
                if (args.width < 0) {
                    spec.flags += "-";
                    args.width = -args.width;
                }
            } else {
                WarnDie.warn(new RuntimeScalar("Missing argument in sprintf"),
                        new RuntimeScalar(""));
            }
        } else if (spec.width != null) {
            args.width = spec.width;
        }

        // Process precision
        if (spec.precisionFromArg) {
            int precArgIndex;
            if (spec.precisionArgIndex != null) {
                // Explicit index like %.*2$d
                precArgIndex = spec.precisionArgIndex - 1;
            } else if (spec.parameterIndex != null) {
                // When we have %2$.*d, precision comes from current position
                precArgIndex = currentArgIndex;
                currentArgIndex++;
                args.consumedArgs++;
            } else {
                // Regular %.*d - precision comes from current position
                precArgIndex = currentArgIndex;
                currentArgIndex++;
                args.consumedArgs++;
            }

            if (precArgIndex < list.size()) {
                args.precision = ((RuntimeScalar) list.elements.get(precArgIndex)).getInt();
                if (args.precision < 0) {
                    args.precision = -1;  // Negative precision is ignored
                }
            } else {
                WarnDie.warn(new RuntimeScalar("Missing argument in sprintf"),
                        new RuntimeScalar(""));
            }
        } else if (spec.precision != null) {
            args.precision = spec.precision;
        }

        // Determine value argument index
        if (spec.parameterIndex != null) {
            args.valueArgIndex = spec.parameterIndex - 1;
        } else {
            args.valueArgIndex = argIndex + args.consumedArgs;
        }

        return args;
    }

    /**
     * Determine the actual argument index for width/precision arguments.
     */
    private static int determineArgIndex(Integer specifiedIndex,
                                         Integer parameterIndex, int currentArgIndex) {
        if (specifiedIndex != null) {
            return specifiedIndex - 1;  // Convert to 0-based
        } else if (parameterIndex != null) {
            return currentArgIndex;  // Use current position
        } else {
            return currentArgIndex;
        }
    }

    /**
     * Update argument index after processing a format specifier.
     */
    private static int updateArgIndex(SprintfFormatParser.FormatSpecifier spec,
                                      int currentIndex) {
        int consumed = 1;  // The value argument

        if (spec.widthFromArg && spec.widthArgIndex == null) {
            consumed++;
        }
        if (spec.precisionFromArg && spec.precisionArgIndex == null) {
            consumed++;
        }

        return currentIndex + consumed;
    }

    /**
     * Handle invalid format specifiers.
     */
    private static String handleInvalidSpecifier(SprintfFormatParser.FormatSpecifier spec) {
        System.err.println("DEBUG handleInvalidSpecifier: raw='" + spec.raw +
                      "', errorMessage='" + spec.errorMessage + "'");

        String formatOnly = spec.raw;
        String trailing = "";

        // Check for trailing non-format characters
        if (spec.errorMessage != null && spec.errorMessage.equals("INVALID")) {
            // Find where the actual format ends
            int formatEnd = spec.raw.length();
            for (int i = 1; i < spec.raw.length(); i++) {
                char c = spec.raw.charAt(i);
                if (Character.isLetter(c) && "diouxXeEfFgGaAbBcspn%".indexOf(c) == -1) {
                    formatEnd = i;
                    formatOnly = spec.raw.substring(0, formatEnd);
                    trailing = spec.raw.substring(formatEnd);
                    break;
                }
            }
        }

        // Generate warning
        if (spec.errorMessage != null) {
            String formatForWarning = spec.raw;
            if (spec.raw.contains(" ")) {
                int spaceIndex = spec.raw.indexOf(" ");
                formatForWarning = spec.raw.substring(0, spaceIndex + 1);
            }

            String warningMessage = "Invalid conversion in sprintf: \"" + formatForWarning + "\"";
            System.err.println("DEBUG: About to warn: " + warningMessage);
            WarnDie.warn(new RuntimeScalar(warningMessage), new RuntimeScalar(""));
        }

        // Don't consume any arguments for invalid specifiers
        System.err.println("DEBUG: Returning from handleInvalidSpecifier: '" + formatOnly + trailing + "'");
        return formatOnly + trailing;
    }

    /**
     * Handle missing argument for a format specifier.
     */
    private static String handleMissingArgument(SprintfFormatParser.FormatSpecifier spec,
                                                FormatArguments args) {
        // Generate warning
        WarnDie.warn(new RuntimeScalar("Missing argument in sprintf"), new RuntimeScalar(""));

        // Return appropriate default value based on conversion type
        return switch (spec.conversionChar) {
            case 'f', 'F' -> {
                int prec = args.precision >= 0 ? args.precision : 6;
                yield String.format("%." + prec + "f", 0.0);
            }
            case 'g', 'G' -> "0";
            case 'd', 'i', 'u', 'o', 'x', 'X' -> "0";
            case 's' -> "";
            case 'c' -> "\0";
            default -> "";
        };
    }
}
