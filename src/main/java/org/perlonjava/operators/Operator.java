package org.perlonjava.operators;

import com.sun.jna.Native;
import org.perlonjava.regex.RuntimeRegex;
import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeArray.*;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class Operator {

    public static RuntimeScalar xor(RuntimeScalar left, RuntimeScalar right) {
        return getScalarBoolean(left.getBoolean() ^ right.getBoolean());
    }

    public static RuntimeScalar join(RuntimeScalar runtimeScalar, RuntimeBase list) {
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
     * Changes file permissions.
     *
     * @param runtimeList The list containing mode and filenames
     * @return A RuntimeScalar with the number of files successfully changed
     */
    public static RuntimeScalar chmod(RuntimeList runtimeList) {
        // chmod MODE, LIST

        if (runtimeList.size() < 2) {
            throw new PerlCompilerException("Not enough arguments for chmod");
        }

        int mode = (int) runtimeList.elements.getFirst().scalar().getInt();
        int successCount = 0;

        // Process each file in the list
        for (int i = 1; i < runtimeList.size(); i++) {
            String fileName = runtimeList.elements.get(i).toString();
            Path path = RuntimeIO.resolvePath(fileName);

            if (Files.exists(path)) {
                if (IOOperator.applyFilePermissions(path, mode)) {
                    successCount++;
                }
            }
        }

        return new RuntimeScalar(successCount);
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
        List<RuntimeBase> splitElements = result.elements;

        // Special case: if the pattern is a single space character, treat it as /\s+/
        if (quotedRegex.type != RuntimeScalarType.REGEX && quotedRegex.toString().equals(" ")) {
            quotedRegex = RuntimeRegex.getQuotedRegex(new RuntimeScalar("\\s+"), new RuntimeScalar(""));
            // Remove leading whitespace from the input string
            inputStr = inputStr.replaceAll("^\\s+", "");
        }

        if (quotedRegex.type == RuntimeScalarType.REGEX) {
            RuntimeRegex regex = (RuntimeRegex) quotedRegex.value;
            Pattern pattern = regex.pattern;

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
                    while (!splitElements.isEmpty() && splitElements.getLast().toString().isEmpty()) {
                        splitElements.removeLast();
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

    /**
     * Extracts a substring from a given RuntimeScalar based on the provided offset and length.
     * This method mimics Perl's substr function, handling negative offsets and lengths.
     *
     * @param runtimeScalar The RuntimeScalar containing the original string.
     * @param list          A RuntimeList containing the offset and optionally the length.
     * @return A RuntimeSubstrLvalue representing the extracted substring, which can be used for further operations.
     */
    public static RuntimeScalar substr(RuntimeScalar runtimeScalar, RuntimeList list) {
        String str = runtimeScalar.toString();
        int strLength = str.length();

        int size = list.elements.size();
        int offset = Integer.parseInt(list.getFirst().toString());
        // If length is not provided, use the rest of the string
        int length = (size > 1) ? Integer.parseInt(list.elements.get(1).toString()) : strLength - offset;
        String replacement = (size > 2) ? list.elements.get(2).toString() : null;

        // Store original offset and length for LValue creation
        int originalOffset = offset;
        int originalLength = length;

        // Handle negative offsets (count from the end of the string)
        if (offset < 0) {
            offset = strLength + offset;
        }

        // Ensure offset is within bounds
        offset = Math.max(0, Math.min(offset, strLength));

        // Handle negative lengths (count from the end of the string)
        if (length < 0) {
            length = strLength + length - offset;
        }

        // Ensure length is non-negative and within bounds
        length = Math.max(0, Math.min(length, strLength - offset));

        // Extract the substring
        String result = str.substring(offset, offset + length);

        // Return an LValue "RuntimeSubstrLvalue" that can be used to assign to the original string
        // This allows for in-place modification of the original string if needed
        var lvalue = new RuntimeSubstrLvalue(runtimeScalar, result, originalOffset, originalLength);

        if (replacement != null) {
            lvalue.set(replacement);
        }

        return lvalue;
    }

    /**
     * Splices the array based on the parameters provided in the RuntimeList.
     * The RuntimeList should contain the following elements in order:
     * - OFFSET: The starting position for the splice operation (int).
     * - LENGTH: The number of elements to be removed (int).
     * - LIST: The list of elements to be inserted at the splice position (RuntimeList).
     * <p>
     * If OFFSET is not provided, it defaults to 0.
     * If LENGTH is not provided, it defaults to the size of the array.
     * If LIST is not provided, no elements are inserted.
     *
     * @param runtimeArray
     * @param list         the RuntimeList containing the splice parameters and elements
     * @return a RuntimeList containing the elements that were removed
     */
    public static RuntimeList splice(RuntimeArray runtimeArray, RuntimeList list) {
        return switch (runtimeArray.type) {
            case PLAIN_ARRAY -> {
                RuntimeList removedElements = new RuntimeList();

                int size = runtimeArray.elements.size();

                int offset;
                if (!list.isEmpty()) {
                    RuntimeBase value = list.elements.removeFirst();
                    offset = value.scalar().getInt();
                } else {
                    offset = 0;
                }

                int length;
                if (!list.elements.isEmpty()) {
                    RuntimeBase value = list.elements.removeFirst();
                    length = value.scalar().getInt();
                } else {
                    length = size;
                }

                // Handle negative offset
                if (offset < 0) {
                    offset = size + offset;
                }

                // Ensure offset is within bounds
                if (offset > size) {
                    offset = size;
                }

                // Handle negative length
                if (length < 0) {
                    length = size - offset + length;
                }

                // Ensure length is within bounds
                length = Math.min(length, size - offset);

                // Remove elements
                for (int i = 0; i < length && offset < runtimeArray.size(); i++) {
                    removedElements.elements.add(runtimeArray.elements.remove(offset));
                }

                // Add new elements
                if (!list.elements.isEmpty()) {
                    RuntimeArray arr = new RuntimeArray();
                    RuntimeArray.push(arr, list);
                    runtimeArray.elements.addAll(offset, arr.elements);
                }

                yield  removedElements;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield splice(runtimeArray, list); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedSplice(runtimeArray, list);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };



    }

    /**
     * Deletes a list of files specified in the RuntimeList.
     *
     * @param value The list of files to be deleted.
     * @return A RuntimeScalar indicating the result of the unlink operation.
     */
    public static RuntimeBase unlink(RuntimeBase value, int ctx) {
        boolean allDeleted = true;
        RuntimeList fileList = value.getList();
        if (fileList.isEmpty()) {
            fileList.elements.add(GlobalVariable.getGlobalVariable("main::_"));
        }

        for (RuntimeScalar fileScalar : fileList) {
            String fileName = fileScalar.toString();

            try {
                Path path = RuntimeIO.resolvePath(fileName);
                Files.delete(path);
            } catch (NoSuchFileException e) {
                allDeleted = false;
                getGlobalVariable("main::!").set("No such file or directory");
                Native.setLastError(2); // ENOENT
            } catch (AccessDeniedException e) {
                allDeleted = false;
                getGlobalVariable("main::!").set("Permission denied");
                Native.setLastError(13); // EACCES
            } catch (DirectoryNotEmptyException e) {
                allDeleted = false;
                getGlobalVariable("main::!").set("Directory not empty");
                Native.setLastError(39); // ENOTEMPTY
            } catch (IOException e) {
                allDeleted = false;
                String errorMessage = e.getMessage();
                getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
                // Try to set appropriate errno based on the exception
                if (errorMessage != null && errorMessage.contains("in use")) {
                    Native.setLastError(16); // EBUSY
                }
            }
        }

        return getScalarBoolean(allDeleted);
    }

    public static RuntimeBase reverse(RuntimeBase value, int ctx) {
        if (ctx == RuntimeContextType.SCALAR) {
            StringBuilder sb = new StringBuilder();

            RuntimeList list = value.getList();
            if (list.isEmpty()) {
                list.elements.add(GlobalVariable.getGlobalVariable("main::_"));
            }

            Iterator<RuntimeScalar> iterator = list.iterator();
            while (iterator.hasNext()) {
                sb.append(iterator.next().toString());
            }
            return new RuntimeScalar(sb.reverse().toString());
        } else {
            // Get the list first to validate it
            RuntimeList inputList = value.getList();

            // Check for autovivification before processing
            inputList.validateNoAutovivification();

            RuntimeList result = new RuntimeList();

            // Collect all elements into the result list
            Iterator<RuntimeScalar> iterator = inputList.iterator();
            while (iterator.hasNext()) {
                result.elements.add(iterator.next());
            }

            // Use Java's built-in reverse
            Collections.reverse(result.elements);

            return result;
        }
    }

    public static RuntimeBase repeat(RuntimeBase value, RuntimeScalar timesScalar, int ctx) {
        int times = timesScalar.getInt();
        if (ctx == RuntimeContextType.SCALAR || value instanceof RuntimeScalar) {
            StringBuilder sb = new StringBuilder();
            Iterator<RuntimeScalar> iterator = value.iterator();
            while (iterator.hasNext()) {
                sb.append(iterator.next().toString());
            }
            return new RuntimeScalar(sb.toString().repeat(Math.max(0, times)));
        } else {
            RuntimeList result = new RuntimeList();
            List<RuntimeBase> outElements = result.elements;
            for (int i = 0; i < times; i++) {
                Iterator<RuntimeScalar> iterator = value.iterator();
                while (iterator.hasNext()) {
                    outElements.add(iterator.next());
                }
            }
            return result;
        }
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? scalarUndef : new RuntimeScalar(ctx == RuntimeContextType.LIST ? scalarOne : scalarZero);
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        if (args.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            throw new PerlCompilerException("not implemented: reset(args)");
        }
        return getScalarInt(1).getList();
    }

    public static RuntimeScalar repeat(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return (RuntimeScalar) repeat(runtimeScalar, arg, RuntimeContextType.SCALAR);
    }

    /**
     * Read the value of a symbolic link
     * @param args RuntimeBase array: [filename] or empty (uses $_)
     * @return RuntimeScalar with link target or undef on error
     */
    public static RuntimeScalar readlink(RuntimeBase... args) {
        String path = args[0].getFirst().toString();
        try {
            Path linkPath = RuntimeIO.resolvePath(path);

            // Check if file exists first
            if (!Files.exists(linkPath)) {
                // Set $! to "No such file or directory"
                getGlobalVariable("main::!").set("No such file or directory");
                Native.setLastError(2); // ENOENT
                return RuntimeScalar.undef();
            }

            if (Files.isSymbolicLink(linkPath)) {
                Path targetPath = Files.readSymbolicLink(linkPath);
                return new RuntimeScalar(targetPath.toString());
            } else {
                // Not a symbolic link - set $! to appropriate error
                getGlobalVariable("main::!").set("Invalid argument");
                Native.setLastError(22); // EINVAL
                return RuntimeScalar.undef();
            }
        } catch (UnsupportedOperationException e) {
            // Symbolic links not supported on this platform
            throw new RuntimeException("Symbolic links are not implemented on this platform");
        } catch (IOException e) {
            // Set $! based on the specific IOException
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("Access is denied")) {
                getGlobalVariable("main::!").set("Permission denied");
                Native.setLastError(13); // EACCES
            } else {
                getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
            }
            return RuntimeScalar.undef();
        } catch (Exception e) {
            // Generic error - set $!
            getGlobalVariable("main::!").set(e.getMessage() != null ? e.getMessage() : "Unknown error");
            return RuntimeScalar.undef();
        }
    }
}
