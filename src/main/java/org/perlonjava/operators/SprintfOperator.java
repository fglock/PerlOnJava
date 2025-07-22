package org.perlonjava.operators;

import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

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

        // Create an array to hold the arguments for the format string
        Object[] args = new Object[list.size()];

        // Regular expression to find format specifiers in the format string
        // Example of format specifiers: %d, %f, %s, etc.
        Pattern pattern = Pattern.compile("%[\\-\\+\\d\\s]*\\.?\\d*[a-zA-Z]");
        Matcher matcher = pattern.matcher(format);

        int index = 0;

        // Iterate through the format string and map each format specifier
        // to the corresponding element in the list
        while (matcher.find() && index < list.size()) {
            // Get the current format specifier (e.g., %d, %f, %s)
            String specifier = matcher.group();
            // Get the corresponding element from the list
            RuntimeScalar element = (RuntimeScalar) list.elements.get(index);
            Object arg;

            // Determine the type of argument based on the format specifier
            char formatChar = specifier.charAt(specifier.length() - 1);
            switch (Character.toLowerCase(formatChar)) {
                case 'd':
                case 'i':
                case 'u':
                case 'x':
                case 'o':
                    arg = element.getInt();
                    break;
                case 'b':
                    // Special handling for binary format
                    int intValue = element.getInt();
                    // Use 8 bits for values that fit in a byte, 32 bits otherwise
                    arg = (intValue >= 0 && intValue <= 255)
                            ? String.format("%8s", Integer.toBinaryString(intValue)).replace(' ', '0')
                            : String.format("%32s", Integer.toBinaryString(intValue)).replace(' ', '0');
                    // Modify the format string to use %s instead of %b
                    format = format.replace(specifier, "%s");
                    break;
                case 'f':
                case 'e':
                case 'g':
                case 'a':
                    arg = element.getDouble();
                    break;
                case 'c':
                    arg = (char) element.getInt();
                    break;
                case 'p':
                    arg = String.format("%x", element.getInt());
                    break;
                case 'n':
                    // Special case: need to handle this separately
                    throw new UnsupportedOperationException("%n specifier not supported");
                default:
                    arg = element.toString();
            }

            // Store the converted argument in the args array
            args[index] = arg;
            index++;
        }

        // Format the string using the format string and the arguments array
        String formattedString;
        try {
            formattedString = String.format(format, args);
        } catch (IllegalFormatException e) {
            // If the format string is invalid, throw a runtime exception
            throw new RuntimeException("Invalid format string: " + format, e);
        }

        // Return the formatted string wrapped in a RuntimeScalar
        return new RuntimeScalar(formattedString);
    }
}
