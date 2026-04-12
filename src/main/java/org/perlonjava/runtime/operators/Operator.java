package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.nativ.NativeUtils;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.regex.RegexTimeoutCharSequence;
import org.perlonjava.runtime.regex.RegexTimeoutException;
import org.perlonjava.runtime.regex.RuntimeRegex;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeArray.*;
import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

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
            // Not enough arguments - return 0 (compatible with Perl behavior)
            return new RuntimeScalar(0);
        }

        int mode = runtimeList.elements.getFirst().scalar().getInt();
        int successCount = 0;

        // Detect platform
        boolean isWindows = NativeUtils.IS_WINDOWS;

        // Process each file in the list
        for (int i = 1; i < runtimeList.size(); i++) {
            String fileName = runtimeList.elements.get(i).toString();
            Path resolved = RuntimeIO.resolvePath(fileName, "chmod");
            if (resolved == null) {
                continue;
            }
            String path = resolved.toString();

            boolean success;

            if (isWindows) {
                try {
                    boolean readOnly = (mode & 0200) == 0;
                    java.nio.file.Files.setAttribute(resolved, "dos:readonly", readOnly);
                    success = true;
                } catch (Exception ex) {
                    success = false;
                }
            } else {
                int result = FFMPosix.get().chmod(path, mode);
                success = (result == 0);
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

        // Special case: splitting an empty string always returns an empty list
        if (inputStr.isEmpty()) {
            if (ctx == SCALAR) {
                return getScalarInt(0).getList();
            }
            return result;
        }

        if (quotedRegex.type != RuntimeScalarType.REGEX) {
            String patternStr = quotedRegex.toString();
            if (patternStr.equals(" ")) {
                quotedRegex = RuntimeRegex.getQuotedRegex(new RuntimeScalar("\\s+"), new RuntimeScalar(""));
                inputStr = inputStr.replaceAll("^\\s+", "");
            } else {
                quotedRegex = RuntimeRegex.getQuotedRegex(quotedRegex, new RuntimeScalar(""));
            }
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
                    // Add trailing empty field when limit < 0
                    if (limit < 0) {
                        splitElements.add(new RuntimeScalar(""));
                    }
                }
            } else {
                CharSequence matchInput = new RegexTimeoutCharSequence(inputStr);
                Matcher matcher = pattern.matcher(matchInput);
                int lastEnd = 0;
                int splitCount = 0;

                try {
                    while (matcher.find() && (limit <= 0 || splitCount < limit - 1)) {
                        // Add the part before the match

                        // System.out.println("matcher lastend " + lastEnd + " start " + matcher.start() + " end " + matcher.end() + " length " + inputStr.length());
                        if (lastEnd == 0 && matcher.end() == 0) {
                            // if (lastEnd == 0 && matchStr.isEmpty()) {
                            // A zero-width match at the beginning of EXPR never produces an empty field
                            // System.out.println("matcher skip first");
                        } else if (matcher.start() == matcher.end() && matcher.start() == lastEnd) {
                            // Skip consecutive zero-width matches at the same position
                            // This handles patterns like / */ that can match zero spaces
                            continue;
                        } else {
                            splitElements.add(new RuntimeScalar(inputStr.substring(lastEnd, matcher.start())));
                        }

                        // Add captured groups if any (but skip code block captures)
                        Pattern p = matcher.pattern();
                        Map<String, Integer> namedGroups = p.namedGroups();
                        for (int i = 1; i <= matcher.groupCount(); i++) {
                            // Check if this is a code block capture (starts with "cb")
                            boolean isCodeBlockCapture = false;
                            if (namedGroups != null) {
                                for (Map.Entry<String, Integer> entry : namedGroups.entrySet()) {
                                    if (entry.getValue() == i && entry.getKey().startsWith("cb")) {
                                        isCodeBlockCapture = true;
                                        break;
                                    }
                                }
                            }

                            // Only add non-code-block captures to split results
                            if (!isCodeBlockCapture) {
                                String group = matcher.group(i);
                                splitElements.add(group != null ? new RuntimeScalar(group) : scalarUndef);
                            }
                        }

                        lastEnd = matcher.end();
                        splitCount++;
                    }
                } catch (RegexTimeoutException e) {
                    WarnDie.warn(new RuntimeScalar(e.getMessage() + "\n"), RuntimeScalarCache.scalarEmptyString);
                }

                // Add the remaining part of the string
                if (lastEnd <= inputStr.length()) {
                    splitElements.add(new RuntimeScalar(inputStr.substring(lastEnd)));
                }

                // Handle trailing empty strings based on limit:
                // - limit > 0: keep all fields (including trailing empty ones)
                // - limit == 0: remove trailing empty fields
                // - limit < 0: keep all fields (including trailing empty ones)
                if (limit == 0) {
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

        // Preserve UTF-8 flag semantics: split results should only have the UTF-8 flag
        // (STRING type) if the input string had it. When input is BYTE_STRING, INTEGER,
        // DOUBLE, UNDEF, etc., the results should be BYTE_STRING (no UTF-8 flag).
        // This matches Perl's behavior where split doesn't spontaneously add UTF-8 flag.
        if (string.type != RuntimeScalarType.STRING) {
            for (RuntimeBase element : splitElements) {
                if (element instanceof RuntimeScalar rs && rs.type == RuntimeScalarType.STRING) {
                    rs.type = RuntimeScalarType.BYTE_STRING;
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
     * Warns when offset is outside of string (when warnings enabled at compile time).
     *
     * @param ctx  The context of the operation.
     * @param args The original string, the offset and optionally the length.
     * @return A RuntimeSubstrLvalue representing the extracted substring, which can be used for further operations.
     */
    public static RuntimeScalar substr(int ctx, RuntimeBase... args) {
        return substrImpl(ctx, true, args);
    }

    /**
     * Extracts a substring without warnings (for when 'no warnings "substr"' is in effect).
     *
     * @param ctx  The context of the operation.
     * @param args The original string, the offset and optionally the length.
     * @return A RuntimeSubstrLvalue representing the extracted substring.
     */
    public static RuntimeScalar substrNoWarn(int ctx, RuntimeBase... args) {
        return substrImpl(ctx, false, args);
    }

    /**
     * Internal implementation of substr with configurable warning behavior.
     */
    private static RuntimeScalar substrImpl(int ctx, boolean warnEnabled, RuntimeBase... args) {
        String str = args[0].toString();
        int strLength = str.codePointCount(0, str.length());

        int size = args.length;
        int offset = ((RuntimeScalar) args[1]).getInt();
        // If length is not provided, use the rest of the string
        boolean hasExplicitLength = size > 2;
        int length = hasExplicitLength ? ((RuntimeScalar) args[2]).getInt() : strLength - offset;
        String replacement = (size > 3) ? args[3].toString() : null;
        RuntimeScalar replacementScalar = (size > 3) ? (RuntimeScalar) args[3] : null;

        // Handle negative offsets (count from the end of the string)
        if (offset < 0) {
            offset = strLength + offset;
            // When computed offset goes negative (before string start):
            // - If adjusted length is negative, warn and return undef (too much overshoot)
            // - If adjusted length is >= 0, clip offset to 0 and return substring (no warning)
            // Example: substr("hello", -10, 1) -> offset=-5, adjustedLen=-4 -> warn + undef
            // Example: substr("a", -2, 1) -> offset=-1, adjustedLen=0 -> "" (no warning)
            // Example: substr("a", -2, 2) -> offset=-1, adjustedLen=1, returns "a" (no warning)
            if (offset < 0) {
                // Adjust length by the overshoot (negative offset value)
                int adjustedLength = length + offset;
                if (adjustedLength < 0) {
                    // Adjusted length is negative - warn and return undef
                    if (warnEnabled) {
                        WarnDie.warn(new RuntimeScalar("substr outside of string"),
                                RuntimeScalarCache.scalarEmptyString);
                    }
                    if (replacement != null) {
                        return new RuntimeScalar();
                    }
                    var lvalue = new RuntimeSubstrLvalue((RuntimeScalar) args[0], "", 0, 0);
                    lvalue.setOutOfBounds();
                    lvalue.type = RuntimeScalarType.UNDEF;
                    lvalue.value = null;
                    return lvalue;
                }
                if (adjustedLength == 0) {
                    // Adjusted length is exactly zero - return empty string (defined), no warning
                    if (replacement != null) {
                        return new RuntimeScalar("");
                    }
                    return new RuntimeSubstrLvalue((RuntimeScalar) args[0], "", 0, 0);
                }
                // Reduce length by the overshoot, no warning
                length = adjustedLength;
                offset = 0;
            }
        }

        // Only warn/error for positive offsets that exceed string length
        if (offset > strLength) {
            if (warnEnabled) {
                WarnDie.warn(new RuntimeScalar("substr outside of string"),
                        RuntimeScalarCache.scalarEmptyString);
            }
            if (replacement != null) {
                return new RuntimeScalar();
            }
            var lvalue = new RuntimeSubstrLvalue((RuntimeScalar) args[0], "", offset, length);
            lvalue.setOutOfBounds();
            lvalue.type = RuntimeScalarType.UNDEF;
            lvalue.value = null;
            return lvalue;
        }

        // Ensure offset is within bounds
        offset = Math.max(0, Math.min(offset, strLength));

        // Handle negative lengths (count from the end of the string)
        if (length < 0) {
            length = strLength + length - offset;
        }

        // Ensure length is non-negative and within bounds
        length = Math.max(0, Math.min(length, strLength - offset));

        // If length is zero or negative after all adjustments, return empty string
        if (length <= 0) {
            if (replacement != null) {
                // With replacement, still need to handle the replacement at position 0
                var lvalue = new RuntimeSubstrLvalue((RuntimeScalar) args[0], "", offset, 0);
                lvalue.set(replacementScalar);
                RuntimeScalar retVal = new RuntimeScalar("");
                if (((RuntimeScalar) args[0]).type == RuntimeScalarType.BYTE_STRING) {
                    retVal.type = RuntimeScalarType.BYTE_STRING;
                }
                return retVal;
            }
            return new RuntimeSubstrLvalue((RuntimeScalar) args[0], "", offset, 0);
        }

        // Extract the substring (offset/length are in Unicode code points)
        int startIndex = str.offsetByCodePoints(0, offset);
        int endIndex = str.offsetByCodePoints(startIndex, length);
        String result = str.substring(startIndex, endIndex);

        // Return an LValue "RuntimeSubstrLvalue" that can be used to assign to the original string
        // This allows for in-place modification of the original string if needed
        // Pass the adjusted offset and length, not the originals
        var lvalue = new RuntimeSubstrLvalue((RuntimeScalar) args[0], result, offset, length);

        if (replacement != null) {
            // When replacement is provided, save the extracted substring before modifying
            String extractedSubstring = result;
            lvalue.set(replacementScalar);
            // Return the extracted substring, not the lvalue (which now contains the replacement)
            RuntimeScalar retVal = new RuntimeScalar(extractedSubstring);
            // Preserve BYTE_STRING type from parent
            if (((RuntimeScalar) args[0]).type == RuntimeScalarType.BYTE_STRING) {
                retVal.type = RuntimeScalarType.BYTE_STRING;
            }
            return retVal;
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

                // Remove elements — defer refCount decrement for tracked blessed refs.
                // Only decrement if the array owns the elements' refCounts
                // (elementsOwned == true). For @_ arrays (populated via setArrayOfAlias),
                // elementsOwned is false because the elements are aliases to the caller's
                // variables. Decrementing their refCounts would incorrectly destroy the
                // caller's objects. This matches the guard used by shift() and pop().
                for (int i = 0; i < length && offset < runtimeArray.size(); i++) {
                    RuntimeBase removed = runtimeArray.elements.remove(offset);
                    if (removed != null) {
                        if (runtimeArray.elementsOwned && removed instanceof RuntimeScalar rs) {
                            MortalList.deferDecrementIfTracked(rs);
                        }
                        removedElements.elements.add(removed);
                    } else {
                        removedElements.elements.add(new RuntimeScalar());
                    }
                }

                // Add new elements.
                // Note: we do NOT set runtimeArray.elementsOwned = true here, even though
                // the inserted elements may have refCountOwned = true (from push's
                // incrementRefCountForContainerStore). Setting elementsOwned = true would
                // be incorrect for @_ arrays because remaining alias elements would then
                // be subject to spurious DEC by subsequent shift/pop. The per-element
                // refCountOwned flag handles cleanup when the array is cleared/destroyed.
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
        // CRITICAL: Check for control flow markers FIRST before any processing
        // If any argument is a RuntimeControlFlowList, it's a non-local control flow that should propagate
        for (RuntimeBase arg : args) {
            if (arg instanceof RuntimeControlFlowList) {
                return arg;  // Propagate control flow marker immediately
            }
        }

        if (ctx == SCALAR) {
            StringBuilder sb = new StringBuilder();
            boolean isByteString = false;
            if (args.length == 0) {
                // In scalar context, reverse($_) if no arguments are provided.
                RuntimeScalar defaultVar = GlobalVariable.getGlobalVariable("main::_");
                sb.append(defaultVar);
                isByteString = (defaultVar.type == RuntimeScalarType.BYTE_STRING);
            } else {
                isByteString = true;
                for (RuntimeBase arg : args) {
                    sb.append(arg.toString());
                    if (arg instanceof RuntimeScalar rs && rs.type != RuntimeScalarType.BYTE_STRING) {
                        isByteString = false;
                    }
                }
            }
            RuntimeScalar result = new RuntimeScalar(sb.reverse().toString());
            if (isByteString) {
                result.type = RuntimeScalarType.BYTE_STRING;
            }
            return result;
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
        if (args.length == 1 && args[0] instanceof PerlRange range) {
            RuntimeList list = range.getList();
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : list) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeList argument (e.g., from range operator)
        if (args.length == 1 && args[0] instanceof RuntimeList list) {
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : list) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeHash argument (e.g., from %hash expansion)
        if (args.length == 1 && args[0] instanceof RuntimeHash hash) {
            RuntimeList hashList = hash.getList(); // Get key-value pairs as RuntimeList
            List<RuntimeBase> listElements = new ArrayList<>();
            for (RuntimeScalar scalar : hashList) {
                listElements.add(scalar);
            }
            Collections.reverse(listElements);
            return new RuntimeList(listElements.toArray(new RuntimeBase[0]));
        }

        // Handle single RuntimeArray argument
        if (args.length == 1 && args[0] instanceof RuntimeArray array) {
            if (array.type == TIED_ARRAY) {
                return reverseTiedArray(array);
            } else {
                return reversePlainArray(array);
            }
        }

        // For multiple arguments or other cases, flatten any RuntimeList/RuntimeArray/PerlRange arguments first
        // This handles cases like: reverse(1, ('A', 'B', 'C')) or reverse(1, @array) or reverse(1..3, @array)
        // where ('A', 'B', 'C') becomes a RuntimeList, @array is a RuntimeArray, and 1..3 is a PerlRange
        List<RuntimeBase> flattenedArgs = new ArrayList<>();
        for (RuntimeBase arg : args) {
            if (arg instanceof PerlRange range) {
                // Flatten PerlRange into individual elements
                RuntimeList rangeList = range.getList();
                for (RuntimeScalar scalar : rangeList) {
                    flattenedArgs.add(scalar);
                }
            } else if (arg instanceof RuntimeList list) {
                // Flatten RuntimeList into individual elements
                for (RuntimeScalar scalar : list) {
                    flattenedArgs.add(scalar);
                }
            } else if (arg instanceof RuntimeArray array) {
                // Flatten RuntimeArray into individual elements
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
        // Preserve null elements (deleted array elements) so that
        // @a = reverse @a maintains sparse array structure.
        // null = deleted (exists returns false), undef = defined but undef
        RuntimeArray result = new RuntimeArray();
        for (RuntimeBase element : array.elements) {
            if (element != null) {
                result.elements.add(new RuntimeScalar((RuntimeScalar) element));
            } else {
                result.elements.add(null);
            }
        }
        Collections.reverse(result.elements);
        RuntimeList list = new RuntimeList();
        list.add(result);
        return list;
    }

    public static RuntimeBase repeat(RuntimeBase value, RuntimeScalar timesScalar, int ctx) {
        // Check for uninitialized values and generate warnings
        // Use getDefinedBoolean() to handle tied scalars correctly
        if (value instanceof RuntimeScalar && !value.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in string repetition (x)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
        }
        if (!timesScalar.getDefinedBoolean()) {
            WarnDie.warnWithCategory(new RuntimeScalar("Use of uninitialized value in string repetition (x)"),
                    RuntimeScalarCache.scalarEmptyString, "uninitialized");
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
            RuntimeScalar rv = new RuntimeScalar(scalarValue.toString().repeat(Math.max(0, times)));
            if (scalarValue.type == RuntimeScalarType.BYTE_STRING) {
                rv.type = RuntimeScalarType.BYTE_STRING;
            }
            return rv;
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

    public static RuntimeScalar setpriority(int ctx, RuntimeBase... args) {
        // setpriority(WHICH, WHO, PRIORITY) - set process priority
        // Not available on the JVM; return false and set $!
        GlobalVariable.setGlobalVariable("main::!", "setpriority() not supported on this platform (Java/JVM)");
        return RuntimeScalarCache.scalarUndef;
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
                return RuntimeScalar.undef();
            }

            if (Files.isSymbolicLink(linkPath)) {
                Path targetPath = Files.readSymbolicLink(linkPath);
                return new RuntimeScalar(targetPath.toString());
            } else {
                getGlobalVariable("main::!").set("Invalid argument");
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
                getGlobalVariable("main::!").set(2); // ENOENT
                return scalarFalse;
            }

            // Perform the rename/move operation
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            // Success
            return scalarTrue;

        } catch (AccessDeniedException e) {
            getGlobalVariable("main::!").set(13); // EACCES
            return scalarFalse;
        } catch (FileAlreadyExistsException e) {
            getGlobalVariable("main::!").set(17); // EEXIST
            return scalarFalse;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set(2); // ENOENT
            return scalarFalse;
        } catch (DirectoryNotEmptyException e) {
            getGlobalVariable("main::!").set(39); // ENOTEMPTY
            return scalarFalse;
        } catch (IOException e) {
            // Handle other IO errors
            String errorMessage = e.getMessage();
            if (errorMessage != null) {
                if (errorMessage.contains("cross-device link")) {
                    getGlobalVariable("main::!").set(18); // EXDEV
                } else if (errorMessage.contains("directory not empty")) {
                    getGlobalVariable("main::!").set(39); // ENOTEMPTY
                } else {
                    getGlobalVariable("main::!").set(5); // EIO
                }
            } else {
                getGlobalVariable("main::!").set(5); // EIO
            }
            return scalarFalse;
        } catch (Exception e) {
            // Generic error
            getGlobalVariable("main::!").set(5); // EIO
            return scalarFalse;
        }
    }
}
