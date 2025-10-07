package org.perlonjava.operators;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import org.perlonjava.nativ.NativeUtils;
import org.perlonjava.nativ.PosixLibrary;
import org.perlonjava.regex.RuntimeRegex;
import org.perlonjava.runtime.*;
import org.perlonjava.runtime.TieArray;
import org.perlonjava.runtime.PerlRange;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeArray.*;
import static org.perlonjava.runtime.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class Operator {

    public static RuntimeScalar xor(RuntimeScalar left, RuntimeScalar right) {
        return getScalarBoolean(left.getBoolean() ^ right.getBoolean());
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

        int mode = runtimeList.elements.getFirst().scalar().getInt();
        int successCount = 0;

        // Detect platform
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

        // Process each file in the list
        for (int i = 1; i < runtimeList.size(); i++) {
            String fileName = runtimeList.elements.get(i).toString();
            String path = RuntimeIO.resolvePath(fileName).toString();

            boolean success;

            if (isWindows) {
                // Windows: use File attributes
                int attributes = 0;
                if ((mode & 0200) == 0) { // Write bit not set
                    attributes |= WinNT.FILE_ATTRIBUTE_READONLY;
                }

                success = Kernel32.INSTANCE.SetFileAttributes(path, new WinDef.DWORD(attributes));
            } else {
                // POSIX systems
                try {
                    int result = PosixLibrary.INSTANCE.chmod(path, mode);
                    success = (result == 0);
                } catch (LastErrorException e) {
                    success = false;
                }
            }

            if (success) {
                successCount++;
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
    public static RuntimeList split(RuntimeScalar quotedRegex, RuntimeList args, int ctx) {
        Iterator<RuntimeScalar> iterator = args.iterator();
        RuntimeScalar string = iterator.hasNext() ? iterator.next() : getGlobalVariable("main::_");
        RuntimeScalar limitArg = iterator.hasNext() ? iterator.next() : new RuntimeScalar(0);

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

        if (ctx == SCALAR) {
            int size = result.elements.size();
            return getScalarInt(size).getList();
        }
        return result;
    }

    /**
     * Extracts a substring from a given RuntimeScalar based on the provided offset and length.
     * This method mimics Perl's substr function, handling negative offsets and lengths.
     *
     * @param ctx  The context of the operation.
     * @param args The original string, the offset and optionally the length.
     * @return A RuntimeSubstrLvalue representing the extracted substring, which can be used for further operations.
     */
    public static RuntimeScalar substr(int ctx, RuntimeBase... args) {
        String str = args[0].toString();
        int strLength = str.length();

        int size = args.length;
        int offset = ((RuntimeScalar) args[1]).getInt();
        // If length is not provided, use the rest of the string
        int length = (size > 2) ? ((RuntimeScalar) args[2]).getInt() : strLength - offset;
        String replacement = (size > 3) ? args[3].toString() : null;

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
        var lvalue = new RuntimeSubstrLvalue((RuntimeScalar) args[0], result, originalOffset, originalLength);

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

                yield removedElements;
            }
            case AUTOVIVIFY_ARRAY -> {
                AutovivificationArray.vivify(runtimeArray);
                yield splice(runtimeArray, list); // Recursive call after vivification
            }
            case TIED_ARRAY -> TieArray.tiedSplice(runtimeArray, list);
            default -> throw new IllegalStateException("Unknown array type: " + runtimeArray.type);
        };


    }

    public static RuntimeBase reverse(int ctx, RuntimeBase... args) {
        
        if (ctx == SCALAR) {
            StringBuilder sb = new StringBuilder();
            if (args.length == 0) {
                // In scalar context, reverse($_) if no arguments are provided.
                sb.append(GlobalVariable.getGlobalVariable("main::_").toString());
            } else {
                for (RuntimeBase arg : args) {
                    sb.append(arg.toString());
                }
            }
            return new RuntimeScalar(sb.reverse().toString());
        }

        // List context - avoid unnecessary copying to preserve element references
        
        // Create a RuntimeList from args to validate autovivification (like sort does)
        RuntimeList argsList = new RuntimeList();
        for (RuntimeBase arg : args) {
            argsList.add(arg);
        }
        
        // Check for autovivification arrays that should throw errors (like sort does)
        argsList.validateNoAutovivification();
        
        // Handle single PerlRange argument (e.g., from 1..5)
        if (args.length == 1 && args[0] instanceof PerlRange) {
            PerlRange range = (PerlRange) args[0];
            RuntimeList list = range.getList();
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : list) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeList argument (e.g., from range operator)
        if (args.length == 1 && args[0] instanceof RuntimeList) {
            RuntimeList list = (RuntimeList) args[0];
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : list) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeHash argument (e.g., from %hash expansion)
        if (args.length == 1 && args[0] instanceof RuntimeHash) {
            RuntimeHash hash = (RuntimeHash) args[0];
            RuntimeList hashList = hash.getList(); // Get key-value pairs as RuntimeList
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : hashList) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeArray argument
        if (args.length == 1 && args[0] instanceof RuntimeArray) {
            RuntimeArray array = (RuntimeArray) args[0];
            if (array.type == RuntimeArray.TIED_ARRAY) {
                return reverseTiedArray(array);
            } else {
                return reversePlainArray(array);
            }
        }

        // For multiple arguments or other cases, flatten any RuntimeList/RuntimeArray arguments first
        // This handles cases like: reverse(1, ('A', 'B', 'C')) or reverse(1, @array)
        // where ('A', 'B', 'C') becomes a RuntimeList and @array is a RuntimeArray
        List<RuntimeBase> flattenedArgs = new ArrayList<>();
        for (RuntimeBase arg : args) {
            if (arg instanceof RuntimeList) {
                // Flatten RuntimeList into individual elements
                RuntimeList list = (RuntimeList) arg;
                for (RuntimeScalar scalar : list) {
                    flattenedArgs.add(scalar);
                }
            } else if (arg instanceof RuntimeArray) {
                // Flatten RuntimeArray into individual elements
                RuntimeArray array = (RuntimeArray) arg;
                for (RuntimeBase element : array.elements) {
                    // Handle null elements (deleted array elements)
                    if (element != null) {
                        flattenedArgs.add(element);
                    } else {
                        flattenedArgs.add(new RuntimeScalar());
                    }
                }
            } else {
                flattenedArgs.add(arg);
            }
        }
        
        // Now reverse the flattened list
        Collections.reverse(flattenedArgs);
        return new RuntimeList(flattenedArgs.toArray(new RuntimeBase[0]));
    }

    private static RuntimeList reverseTiedArray(RuntimeArray tiedArray) {
        int size = TieArray.tiedFetchSize(tiedArray).getInt();
        List<RuntimeBase> reversedElements = new ArrayList<>(Collections.nCopies(size, null));
        int targetIndex = size - 1;
        for (int i = 0; i < size; i++) {
            if (TieArray.tiedExists(tiedArray, getScalarInt(i)).getBoolean()) {
                reversedElements.set(targetIndex, TieArray.tiedFetch(tiedArray, getScalarInt(i)));
            } else {
                // For deleted tied array elements, set an undef RuntimeScalar
                reversedElements.set(targetIndex, new RuntimeScalar());
            }
            targetIndex--;
        }
        return new RuntimeList(reversedElements.toArray(new RuntimeBase[0]));
    }

    private static RuntimeList reversePlainArray(RuntimeArray array) {
        List<RuntimeBase> newElements = new ArrayList<>();
        // Handle null elements (deleted array elements)
        for (RuntimeBase element : array.elements) {
            if (element != null) {
                newElements.add(element);
            } else {
                // Preserve undef for deleted elements
                newElements.add(new RuntimeScalar());
            }
        }
        Collections.reverse(newElements);
        return new RuntimeList(newElements.toArray(new RuntimeBase[0]));
    }

    public static RuntimeBase repeat(RuntimeBase value, RuntimeScalar timesScalar, int ctx) {
        // Check for uninitialized values and generate warnings
        if (value instanceof RuntimeScalar && ((RuntimeScalar) value).type == RuntimeScalarType.UNDEF) {
            WarnDie.warn(new RuntimeScalar("Use of uninitialized value in string repetition (x)"),
                    RuntimeScalarCache.scalarEmptyString);
        }
        if (timesScalar.type == RuntimeScalarType.UNDEF) {
            WarnDie.warn(new RuntimeScalar("Use of uninitialized value in string repetition (x)"),
                    RuntimeScalarCache.scalarEmptyString);
        }
        
        // Check for non-finite values first
        if (timesScalar.type == RuntimeScalarType.DOUBLE) {
            double d = timesScalar.getDouble();
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                // Return empty string in scalar context or empty list in list context
                if (ctx == SCALAR || value instanceof RuntimeScalar) {
                    return new RuntimeScalar("");
                } else {
                    return new RuntimeList();
                }
            }
        }

        int times = timesScalar.getInt();
        if (ctx == SCALAR || value instanceof RuntimeScalar) {
            // In scalar context, convert value to scalar first
            RuntimeScalar scalarValue;
            if (value instanceof RuntimeScalar) {
                scalarValue = (RuntimeScalar) value;
            } else {
                // Convert to scalar (gets count for arrays, etc.)
                scalarValue = value.scalar();
            }
            return new RuntimeScalar(scalarValue.toString().repeat(Math.max(0, times)));
        } else {
            RuntimeList result = new RuntimeList();
            List<RuntimeBase> outElements = result.elements;
            for (int i = 0; i < times; i++) {
                for (RuntimeScalar runtimeScalar : value) {
                    outElements.add(runtimeScalar);
                }
            }
            return result;
        }
    }

    public static RuntimeScalar undef() {
        return scalarUndef;
    }

    public static RuntimeScalar wantarray(int ctx) {
        return ctx == RuntimeContextType.VOID ? scalarUndef : new RuntimeScalar(ctx == RuntimeContextType.LIST ? scalarTrue : scalarFalse);
    }

    // Process-related operators
    public static RuntimeScalar getppid(int ctx) {
        // Delegate to NativeUtils which has the platform-specific implementation
        return NativeUtils.getppid(ctx);
    }

    public static RuntimeScalar getpgrp(int ctx, RuntimeBase... args) {
        // getpgrp([PID]) - get process group
        // If no PID given, returns process group of current process
        int pid = 0;
        if (args.length > 0 && args[0] != null) {
            pid = ((RuntimeScalar) args[0]).getInt();
        }
        // For now, return a stub value
        // TODO: Implement proper getpgrp via JNA/JNI
        return new RuntimeScalar(0);
    }

    public static RuntimeScalar setpgrp(int ctx, RuntimeBase... args) {
        // setpgrp(PID, PGRP) - set process group
        // For now, return 0 (failure) as this requires native implementation
        // TODO: Implement proper setpgrp via JNA/JNI
        return new RuntimeScalar(0);
    }

    public static RuntimeScalar getpriority(int ctx, RuntimeBase... args) {
        // getpriority(WHICH, WHO) - get process priority
        // For now, return 0 as this requires native implementation
        // TODO: Implement proper getpriority via JNA/JNI
        return new RuntimeScalar(0);
    }

    public static RuntimeList reset(RuntimeList args, int ctx) {
        if (args.isEmpty()) {
            RuntimeRegex.reset();
        } else {
            // Parse the character range expression
            String expr = args.getFirst().toString();
            Set<Character> resetChars = parseResetExpression(expr);

            // Get current package from caller information
            String currentPackage = RuntimeCode.getCurrentPackage();

            // Reset global variables that start with matching characters
            GlobalVariable.resetGlobalVariables(resetChars, currentPackage);
        }
        return getScalarInt(1).getList();
    }

    /**
     * Parses a reset expression like "a-z" or "XYZ" into a set of characters
     *
     * @param expr The reset expression
     * @return Set of characters that variables should start with to be reset
     */
    private static Set<Character> parseResetExpression(String expr) {
        Set<Character> chars = new HashSet<>();

        for (int i = 0; i < expr.length(); i++) {
            char start = expr.charAt(i);

            // Check for range pattern like "a-z"
            if (i + 2 < expr.length() && expr.charAt(i + 1) == '-') {
                char end = expr.charAt(i + 2);

                // Add all characters in the range
                for (char ch = start; ch <= end; ch++) {
                    chars.add(ch);
                }
                i += 2; // Skip the '-' and end character
            } else {
                // Single character
                chars.add(start);
            }
        }

        return chars;
    }

    public static RuntimeScalar repeat(RuntimeScalar runtimeScalar, RuntimeScalar arg) {
        return (RuntimeScalar) repeat(runtimeScalar, arg, SCALAR);
    }

    /**
     * Read the value of a symbolic link
     *
     * @param args RuntimeBase array: [filename] or empty (uses $_)
     * @return RuntimeScalar with link target or undef on error
     */
    public static RuntimeScalar readlink(int ctx, RuntimeBase... args) {
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

    /**
     * Rename a file (Perl's rename operator)
     *
     * @param args RuntimeBase array: [oldname, newname]
     * @return RuntimeScalar with 1 on success, 0 on failure
     */
    public static RuntimeScalar rename(int ctx, RuntimeBase... args) {
        if (args.length < 2) {
            throw new PerlCompilerException("Not enough arguments for rename");
        }

        String oldName = args[0].getFirst().toString();
        String newName = args[1].getFirst().toString();

        try {
            Path oldPath = RuntimeIO.resolvePath(oldName);
            Path newPath = RuntimeIO.resolvePath(newName);

            // Check if source file exists
            if (!Files.exists(oldPath)) {
                getGlobalVariable("main::!").set("No such file or directory");
                Native.setLastError(2); // ENOENT
                return scalarFalse;
            }

            // Perform the rename/move operation
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            // Success
            return scalarTrue;

        } catch (AccessDeniedException e) {
            getGlobalVariable("main::!").set("Permission denied");
            Native.setLastError(13); // EACCES
            return scalarFalse;
        } catch (FileAlreadyExistsException e) {
            getGlobalVariable("main::!").set("File exists");
            Native.setLastError(17); // EEXIST
            return scalarFalse;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set("No such file or directory");
            Native.setLastError(2); // ENOENT
            return scalarFalse;
        } catch (IOException e) {
            // Handle other IO errors
            String errorMessage = e.getMessage();
            if (errorMessage != null) {
                if (errorMessage.contains("cross-device link")) {
                    getGlobalVariable("main::!").set("Invalid cross-device link");
                    Native.setLastError(18); // EXDEV
                } else if (errorMessage.contains("directory not empty")) {
                    getGlobalVariable("main::!").set("Directory not empty");
                    Native.setLastError(39); // ENOTEMPTY
                } else {
                    getGlobalVariable("main::!").set(errorMessage);
                }
            } else {
                getGlobalVariable("main::!").set("I/O error");
            }
            return scalarFalse;
        } catch (Exception e) {
            // Generic error
            getGlobalVariable("main::!").set(e.getMessage() != null ? e.getMessage() : "Unknown error");
            return scalarFalse;
        }
    }
}
