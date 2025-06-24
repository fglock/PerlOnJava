package org.perlonjava.operators;

import org.perlonjava.regex.RuntimeRegex;
import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

public class Operator {

    public static RuntimeScalar xor(RuntimeScalar left, RuntimeScalar right) {
        return getScalarBoolean(left.getBoolean() ^ right.getBoolean());
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

        RuntimeIO fh;
        String mode = runtimeList.getFirst().toString();
        if (runtimeList.size() > 1) {
            // 3-argument open
            String fileName = runtimeList.elements.get(1).toString();
            fh = RuntimeIO.open(fileName, mode);
        } else {
            // 2-argument open
            fh = RuntimeIO.open(mode);
        }
        if (fh == null) {
            return new RuntimeScalar();
        }
        fileHandle.type = RuntimeScalarType.GLOB;
        fileHandle.value = fh;
        return new RuntimeScalar(1); // success
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

    public static RuntimeScalar fileno(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.fileno();
    }

    public static RuntimeScalar truncate(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        long length = runtimeList.getFirst().getLong();
        if (fileHandle.type == RuntimeScalarType.STRING) {
            // Handle as filename
            String filename = fileHandle.toString();
            Path filePath = Paths.get(filename);
            try (FileChannel channel1 = FileChannel.open(filePath, StandardOpenOption.WRITE)) {
                channel1.truncate(length);
                return scalarTrue;
            } catch (IOException e) {
                return RuntimeIO.handleIOException(e, "truncate failed");
            }
        } else if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                return runtimeIO.ioHandle.truncate(length);
            } else {
                return RuntimeIO.handleIOError("No file handle available for truncate");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for truncate");
        }
    }

    public static RuntimeScalar binmode(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        String arg = runtimeList.getFirst().toString();
        if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                // return runtimeIO.ioHandle.binmode(arg);

                // TODO - add IO layer (binmode) as needed
                //
                // // Wrap the IOHandle before returning
                // IOHandle handle = /* create CustomFileChannel or other handle */;
                // IOHandle wrappedHandle = new LayeredIOHandle(handle);
                //
                // RuntimeIO runtimeIO = new RuntimeIO();
                // runtimeIO.ioHandle = wrappedHandle;
                // return runtimeIO;

                return fileHandle;
            } else {
                return RuntimeIO.handleIOError("No file handle available for binmode");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for binmode");
        }
    }

    public static RuntimeScalar seek(RuntimeScalar fileHandle, RuntimeList runtimeList) {
        long length = runtimeList.getFirst().getLong();
        if (fileHandle.type == RuntimeScalarType.GLOB || fileHandle.type == RuntimeScalarType.GLOBREFERENCE) {
            // File handle
            RuntimeIO runtimeIO = fileHandle.getRuntimeIO();
            if (runtimeIO.ioHandle != null) {
                return runtimeIO.ioHandle.seek(length);  // TODO - "whence" argument
            } else {
                return RuntimeIO.handleIOError("No file handle available for seek");
            }
        } else {
            return RuntimeIO.handleIOError("Unsupported scalar type for seek");
        }
    }

    /**
     * Prints the elements to the specified file handle according to the format string.
     *
     * @param runtimeList
     * @param fileHandle  The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public static RuntimeScalar printf(RuntimeList runtimeList, RuntimeScalar fileHandle) {
        RuntimeScalar format = (RuntimeScalar) runtimeList.elements.removeFirst(); // Extract the format string from elements

        // Use sprintf to get the formatted string
        String formattedString = SprintfOperator.sprintf(format, runtimeList).toString();

        // Write the formatted content to the file handle
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.write(formattedString);
    }

    public static RuntimeScalar select(RuntimeList runtimeList, int ctx) {
        if (runtimeList.isEmpty()) {
            return new RuntimeScalar(RuntimeIO.lastSelectedHandle);
        }
        RuntimeScalar fh = new RuntimeScalar(RuntimeIO.lastSelectedHandle);
        RuntimeIO.lastSelectedHandle = runtimeList.getFirst().getRuntimeIO();
        return fh;
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

        try {
            // Write the content to the file handle
            RuntimeIO fh = fileHandle.getRuntimeIO();
            return fh.write(sb.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
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

        try {
            // Write the content to the file handle
            RuntimeIO fh = fileHandle.getRuntimeIO();
            return fh.write(sb.toString());
        } catch (Exception e) {
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarFalse;
        }
    }

    public static RuntimeScalar getc(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        if (fh.ioHandle != null) {
            return fh.ioHandle.getc();
        }
        throw new PerlCompilerException("No input source available");
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

    public static RuntimeScalar eof(RuntimeList runtimeList, RuntimeScalar fileHandle) {
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
        return new RuntimeSubstrLvalue(runtimeScalar, result, originalOffset, originalLength);
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
        RuntimeList removedElements = new RuntimeList();

        int size = runtimeArray.elements.size();

        int offset;
        if (!list.isEmpty()) {
            RuntimeDataProvider value = list.elements.removeFirst();
            offset = value.scalar().getInt();
        } else {
            offset = 0;
        }

        int length;
        if (!list.elements.isEmpty()) {
            RuntimeDataProvider value = list.elements.removeFirst();
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

        return removedElements;
    }

    /**
     * Deletes a list of files specified in the RuntimeList.
     *
     * @param value The list of files to be deleted.
     * @return A RuntimeScalar indicating the result of the unlink operation.
     */
    public static RuntimeDataProvider unlink(RuntimeDataProvider value, int ctx) {

        boolean allDeleted = true;
        RuntimeList fileList = value.getList();
        if (fileList.isEmpty()) {
            fileList.elements.add(GlobalVariable.getGlobalVariable("main::_"));
        }
        Iterator<RuntimeScalar> iterator = fileList.iterator();

        while (iterator.hasNext()) {
            RuntimeScalar fileScalar = iterator.next();
            String fileName = fileScalar.toString();
            java.io.File file = new java.io.File(fileName);

            if (!file.delete()) {
                allDeleted = false;
                getGlobalVariable("main::!").set("Failed to delete file: " + fileName);
            }
        }

        return getScalarBoolean(allDeleted);
    }

    public static RuntimeDataProvider reverse(RuntimeDataProvider value, int ctx) {
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
            RuntimeList result = new RuntimeList();
            List<RuntimeBaseEntity> outElements = result.elements;
            Iterator<RuntimeScalar> iterator = value.iterator();

            // First, collect all elements
            List<RuntimeScalar> tempList = new ArrayList<>();
            while (iterator.hasNext()) {
                tempList.add(iterator.next());
            }

            // Then add them in reverse order
            for (int i = tempList.size() - 1; i >= 0; i--) {
                outElements.add(tempList.get(i));
            }

            return result;
        }
    }

    public static RuntimeDataProvider repeat(RuntimeDataProvider value, RuntimeScalar timesScalar, int ctx) {
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
            List<RuntimeBaseEntity> outElements = result.elements;
            for (int i = 0; i < times; i++) {
                Iterator<RuntimeScalar> iterator = value.iterator();
                while (iterator.hasNext()) {
                    outElements.add(iterator.next());
                }
            }
            return result;
        }
    }
}
