package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

public class Operator {
    /**
     * Formats the elements according to the specified format string.
     *
     * @param runtimeScalar
     * @param list          The list of elements to be formatted.
     * @return A RuntimeScalar containing the formatted string.
     */
    public static RuntimeScalar sprintf(RuntimeScalar runtimeScalar, RuntimeList list) {
        // The format string that specifies how the elements should be formatted
        String format = runtimeScalar.toString();

        // Create an array to hold the arguments for the format string
        Object[] args = new Object[list.elements.size()];

        // Regular expression to find format specifiers in the format string
        // Example of format specifiers: %d, %f, %s, etc.
        Pattern pattern = Pattern.compile("%[\\-\\+\\d\\s]*\\.?\\d*[a-zA-Z]");
        Matcher matcher = pattern.matcher(format);

        int index = 0;

        // Iterate through the format string and map each format specifier
        // to the corresponding element in the list
        while (matcher.find() && index < list.elements.size()) {
            // Get the current format specifier (e.g., %d, %f, %s)
            String specifier = matcher.group();
            // Get the corresponding element from the list
            RuntimeScalar element = (RuntimeScalar) list.elements.get(index);
            Object arg;

            // Determine the type of argument based on the format specifier
            if (specifier.endsWith("d") || specifier.endsWith("i")) {
                // Integer specifiers: convert the element to an integer
                arg = element.getInt();
            } else if (specifier.endsWith("f") || specifier.endsWith("e") || specifier.endsWith("g")) {
                // Floating-point specifiers: convert the element to a double
                arg = element.getDouble();
            } else {
                // For other specifiers (e.g., %s), convert the element to a string
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

    public static RuntimeScalar join(RuntimeScalar runtimeScalar, RuntimeDataProvider list) {
        String delimiter = runtimeScalar.toString();
        // Join the list into a string
        StringBuilder sb = new StringBuilder();

        Iterator<RuntimeScalar> iterator = list.iterator();
        boolean start = true;
        while (iterator.hasNext()) {
            if (start) {
                start = false;
            } else {
                sb.append(delimiter);
            }
            sb.append(iterator.next().toString());
        }
        return new RuntimeScalar(sb.toString());
    }

    /**
     * Opens a file and initialize a file handle.
     *
     * @param runtimeList
     * @param fileHandle  The file handle.
     * @return A RuntimeScalar indicating the result of the open operation.
     */
    public static RuntimeScalar open(RuntimeList runtimeList, RuntimeScalar fileHandle) {
//        open FILEHANDLE,MODE,EXPR
//        open FILEHANDLE,MODE,EXPR,LIST
//        open FILEHANDLE,MODE,REFERENCE
//        open FILEHANDLE,EXPR
//        open FILEHANDLE

        // fetch parameters - we are assuming the usual 3-argument open
        String mode = runtimeList.elements.get(0).toString();
        String fileName = runtimeList.elements.get(1).toString();

        RuntimeIO fh = RuntimeIO.open(fileName, mode);
        if (fh == null) {
            return new RuntimeScalar();
        }
        fileHandle.type = RuntimeScalarType.GLOB;
        fileHandle.value = fh;
        return new RuntimeScalar(1); // success
    }

    /**
     * Sorts the elements of this RuntimeArray using a Perl comparator subroutine.
     *
     * @param runtimeList
     * @param perlComparatorClosure A RuntimeScalar representing the Perl comparator subroutine.
     * @return A new RuntimeList with the elements sorted according to the Perl comparator.
     * @throws RuntimeException If the Perl comparator subroutine throws an exception.
     */
    public static RuntimeList sort(RuntimeList runtimeList, RuntimeScalar perlComparatorClosure) {
        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = new RuntimeArray();
        runtimeList.setArrayOfAlias(array);

        RuntimeScalar varA = getGlobalVariable("main::a");
        RuntimeScalar varB = getGlobalVariable("main::b");
        RuntimeArray comparatorArgs = new RuntimeArray();

        // Sort the new array using the Perl comparator subroutine
        array.elements.sort((a, b) -> {
            try {
                // Create $a, $b arguments for the comparator
                varA.set((RuntimeScalar) a);
                varB.set((RuntimeScalar) b);

                // Apply the Perl comparator subroutine with the arguments
                RuntimeList result = perlComparatorClosure.apply(comparatorArgs, RuntimeContextType.SCALAR);

                // Retrieve the comparison result and return it as an integer
                return result.elements.get(0).scalar().getInt();
            } catch (Exception e) {
                // Wrap any exceptions thrown by the comparator in a RuntimeException
                throw new RuntimeException(e);
            }
        });

        // Create a new RuntimeList to hold the sorted elements
        RuntimeList sortedList = new RuntimeList();
        sortedList.elements = array.elements;

        // Return the sorted RuntimeList
        return sortedList;
    }

    /**
     * Filters the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeList with the elements that match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public static RuntimeList grep(RuntimeList runtimeList, RuntimeScalar perlFilterClosure) {
        RuntimeArray array = new RuntimeArray();
        runtimeList.setArrayOfAlias(array);

        // Create a new list to hold the filtered elements
        List<RuntimeBaseEntity> filteredElements = new ArrayList<>();

        RuntimeScalar var_ = getGlobalVariable("main::_");
        RuntimeArray filterArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeBaseEntity element : array.elements) {
            try {
                // Create $_ argument for the filter subroutine
                var_.set((RuntimeScalar) element);

                // Apply the Perl filter subroutine with the argument
                RuntimeList result = perlFilterClosure.apply(filterArgs, RuntimeContextType.SCALAR);

                // Check the result of the filter subroutine
                if (result.elements.get(0).scalar().getBoolean()) {
                    // If the result is non-zero, add the element to the filtered list
                    // We need to clone, otherwise we would be adding an alias to the original element
                    filteredElements.add(((RuntimeScalar) element).clone());
                }
            } catch (Exception e) {
                // Wrap any exceptions thrown by the filter subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        // Create a new RuntimeList to hold the filtered elements
        RuntimeList filteredList = new RuntimeList();
        filteredList.elements = filteredElements;

        // Return the filtered RuntimeList
        return filteredList;
    }

    /**
     * Transforms the elements of this RuntimeArray using a Perl subroutine.
     *
     * @param runtimeList
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public static RuntimeList map(RuntimeList runtimeList, RuntimeScalar perlMapClosure) {
        RuntimeArray array = new RuntimeArray();
        runtimeList.setArrayOfAlias(array);

        // Create a new list to hold the transformed elements
        List<RuntimeBaseEntity> transformedElements = new ArrayList<>();

        RuntimeScalar var_ = getGlobalVariable("main::_");
        RuntimeArray mapArgs = new RuntimeArray();

        // Iterate over each element in the current RuntimeArray
        for (RuntimeBaseEntity element : array.elements) {
            try {
                // Create $_ argument for the map subroutine
                var_.set((RuntimeScalar) element);

                // Apply the Perl map subroutine with the argument
                RuntimeList result = perlMapClosure.apply(mapArgs, RuntimeContextType.LIST);

                // `result` list contains aliases to the original array;
                // We need to make copies of the result elements
                RuntimeArray arr = new RuntimeArray();
                result.addToArray(arr);

                // Add all elements of the result list to the transformed list
                transformedElements.addAll(arr.elements);
            } catch (Exception e) {
                // Wrap any exceptions thrown by the map subroutine in a RuntimeException
                throw new RuntimeException(e);
            }
        }

        // Create a new RuntimeList to hold the transformed elements
        RuntimeList transformedList = new RuntimeList();
        transformedList.elements = transformedElements;

        // Return the transformed RuntimeList
        return transformedList;
    }

    /**
     * Close a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the result of the close operation.
     */
    public static RuntimeScalar close(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.close();
    }

    /**
     * Prints the elements to the specified file handle according to the format string.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar printf(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeScalar format = (RuntimeScalar) runtimeList.elements.remove(0); // Extract the format string from elements

        // Use sprintf to get the formatted string
        String formattedString = sprintf(format, runtimeList).toString();

        // Write the formatted content to the file handle
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.write(formattedString);
    }

    /**
     * Prints the elements to the specified file handle with a separator and newline.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar print(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        String newline = getGlobalVariable("main::\\").toString();  // fetch $\
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBaseEntity element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append(newline);

        // Write the content to the file handle
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.write(sb.toString());
    }

    /**
     * Prints the elements to the specified file handle with a separator and a newline at the end.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar say(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBaseEntity element : runtimeList.elements) {
            if (!first) {
                sb.append(separator);
            }
            sb.append(element.toString());
            first = false;
        }

        // Append the newline character
        sb.append("\n");

        // Write the content to the file handle
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.write(sb.toString());
    }

    /**
     * Reads a line from a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the line.
     */
    public static RuntimeScalar readline(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.readline();
    }

    /**
     * Reads EOF flag from a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the flag.
     */
    public static RuntimeScalar eof(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.eof();
    }

    /**
     * Splits a string based on a regex pattern or a literal string, similar to Perl's split function.
     *
     * @param quotedRegex The regex pattern object, created by getQuotedRegex(), or a literal string.
     * @param args        Argument list.
     * @return A RuntimeList containing the split parts of the string.
     */
    public static RuntimeList split(RuntimeScalar quotedRegex, RuntimeList args) {
        int size = args.size();
        RuntimeScalar string = size > 0 ? (RuntimeScalar) args.elements.get(0) : getGlobalVariable("main::_");  // The string to be split.
        RuntimeScalar limitArg = size > 1 ? (RuntimeScalar) args.elements.get(1) : new RuntimeScalar(0);   // The maximum number of splits (optional).

        int limit = limitArg.getInt();
        String inputStr = string.toString();
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> splitElements = result.elements;

        // Special case: if the pattern is a single space character, treat it as /\s+/
        if (quotedRegex.type != RuntimeScalarType.REGEX && quotedRegex.toString().equals(" ")) {
            quotedRegex = RuntimeRegex.getQuotedRegex(new RuntimeScalar(" "), new RuntimeScalar(""));
        }

        if (quotedRegex.type == RuntimeScalarType.REGEX) {
            RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
            Pattern pattern = regex.pattern;

            // Special case: if the pattern is omitted or a single space character, treat it as /\s+/
            if (pattern == null || pattern.pattern().equals(" ")) {
                pattern = Pattern.compile("\\s+");
                // Remove leading whitespace from the input string
                inputStr = inputStr.replaceAll("^\\s+", "");
            }

            // Special case: if the pattern is "/^/", treat it as if it used the multiline modifier
            if (pattern.pattern().equals("^")) {
                pattern = Pattern.compile("^", Pattern.MULTILINE);
            }

            if (pattern.pattern().isEmpty()) {
                // Special case: if the pattern matches the empty string, split between characters
                if (limit > 0) {
                    for (int i = 0; i < inputStr.length() && splitElements.size() < limit - 1; i++) {
                        splitElements.add(new RuntimeScalar(String.valueOf(inputStr.charAt(i))));
                    }
                    if (splitElements.size() < limit) {
                        splitElements.add(new RuntimeScalar(inputStr.substring(splitElements.size())));
                    }
                } else {
                    for (int i = 0; i < inputStr.length(); i++) {
                        splitElements.add(new RuntimeScalar(String.valueOf(inputStr.charAt(i))));
                    }
                }
            } else {
                Matcher matcher = pattern.matcher(inputStr);
                int lastEnd = 0;
                int splitCount = 0;

                while (matcher.find() && (limit <= 0 || splitCount < limit - 1)) {
                    // Add the part before the match

                    // System.out.println("matcher lastend " + lastEnd + " start " + matcher.start() + " end " + matcher.end() + " length " + inputStr.length());
                    if (lastEnd == 0 && matcher.end() == 0) {
                        // if (lastEnd == 0 && matchStr.isEmpty()) {
                        // A zero-width match at the beginning of EXPR never produces an empty field
                        // System.out.println("matcher skip first");
                    } else {
                        splitElements.add(new RuntimeScalar(inputStr.substring(lastEnd, matcher.start())));
                    }

                    // Add captured groups if any
                    for (int i = 1; i <= matcher.groupCount(); i++) {
                        String group = matcher.group(i);
                        splitElements.add(new RuntimeScalar(group != null ? group : "undef"));
                    }

                    lastEnd = matcher.end();
                    splitCount++;
                }

                // Add the remaining part of the string
                if (lastEnd < inputStr.length()) {
                    splitElements.add(new RuntimeScalar(inputStr.substring(lastEnd)));
                }

                // Handle trailing empty strings if no capturing groups and limit is zero or negative
                if (matcher.groupCount() == 0 && limit <= 0) {
                    while (!splitElements.isEmpty() && splitElements.get(splitElements.size() - 1).toString().isEmpty()) {
                        splitElements.remove(splitElements.size() - 1);
                    }
                }
            }
        } else {
            // Treat quotedRegex as a literal string
            String literalPattern = quotedRegex.toString();

            if (literalPattern.isEmpty()) {
                // Special case: if the pattern is an empty string, split between characters
                if (limit > 0) {
                    for (int i = 0; i < inputStr.length() && splitElements.size() < limit - 1; i++) {
                        splitElements.add(new RuntimeScalar(String.valueOf(inputStr.charAt(i))));
                    }
                    if (splitElements.size() < limit) {
                        splitElements.add(new RuntimeScalar(inputStr.substring(splitElements.size())));
                    }
                } else {
                    for (int i = 0; i < inputStr.length(); i++) {
                        splitElements.add(new RuntimeScalar(String.valueOf(inputStr.charAt(i))));
                    }
                }
            } else {
                String[] parts = inputStr.split(Pattern.quote(literalPattern), limit);
                for (String part : parts) {
                    splitElements.add(new RuntimeScalar(part));
                }
            }
        }

        return result;
    }

    public static RuntimeScalar substr(RuntimeScalar runtimeScalar, RuntimeList list) {
        String str = runtimeScalar.toString();
        int strLength = str.length();

        int size = list.elements.size();
        int offset = Integer.parseInt(list.elements.get(0).toString());
        int length = (size > 1) ? Integer.parseInt(list.elements.get(1).toString()) : strLength - offset;

        // Handle negative offsets
        if (offset < 0) {
            offset = strLength + offset;
        }

        // Ensure offset is within bounds
        if (offset < 0) {
            offset = 0;
        }
        if (offset > strLength) {
            offset = strLength;
        }

        // Handle negative lengths
        if (length < 0) {
            length = strLength + length - offset;
        }

        // Ensure length is non-negative and within bounds
        if (length < 0) {
            length = 0;
        }
        if (offset + length > strLength) {
            length = strLength - offset;
        }

        String result = str.substring(offset, offset + length);
        return new RuntimeScalar(result);
    }
}