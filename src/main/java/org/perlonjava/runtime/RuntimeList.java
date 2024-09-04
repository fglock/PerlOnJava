package org.perlonjava.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;

/**
 * The RuntimeList class simulates a Perl list.
 */
public class RuntimeList extends RuntimeBaseEntity implements RuntimeDataProvider {
    public List<RuntimeBaseEntity> elements;

    // Constructor
    public RuntimeList() {
        this.elements = new ArrayList<>();
    }

    public RuntimeList(RuntimeScalar value) {
        this.elements = new ArrayList<>();
        this.elements.add(value);
    }

    public RuntimeList(RuntimeList value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeArray value) {
        this.elements = value.elements;
    }

    public RuntimeList(RuntimeHash value) {
        this.elements = value.entryArray().elements;
    }

    // Method to generate a list of RuntimeScalar objects
    public static RuntimeList generateList(int start, int end) {
        RuntimeList list = new RuntimeList();
        for (int i = start; i <= end; i++) {
            list.add(new RuntimeScalar(i));
        }
        return list;
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
        RuntimeScalar string = size > 0 ? (RuntimeScalar) args.elements.get(0) : GlobalContext.getGlobalVariable("main::_");  // The string to be split.
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
                    } else  {
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

    // Add itself to a RuntimeList.
    public void addToList(RuntimeList list) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            list.add(this.elements.get(i));
        }
        this.elements.clear();    // consume the list
    }

    // Add itself to a RuntimeArray.
    public void addToArray(RuntimeArray array) {
        int size = this.size();
        for (int i = 0; i < size; i++) {
            this.elements.get(i).addToArray(array);
        }
        this.elements.clear();    // consume the list
    }

    /**
     * Add itself to a RuntimeScalar.
     *
     * @param scalar The RuntimeScalar object
     */
    public RuntimeScalar addToScalar(RuntimeScalar scalar) {
        return scalar.set(this.scalar());
    }

    // Add an element to the list
    public void add(RuntimeBaseEntity value) {
        this.elements.add(value);
    }

    // When adding a List into a List they are merged
    public void add(RuntimeList value) {
        int size = value.size();
        for (int i = 0; i < size; i++) {
            this.elements.add(value.elements.get(i));
        }
    }

    // Get the size of the list
    public int size() {
        return elements.size();
    }

    public int countElements() {
        int count = 0;
        for (RuntimeBaseEntity elem : elements) {
            count = count + elem.countElements();
        }
        return count;
    }

    // Get the array value of the List as aliases into an Array
    public RuntimeArray setArrayOfAlias(RuntimeArray arr) {
        for (RuntimeBaseEntity elem : elements) {
            elem.setArrayOfAlias(arr);
        }
        return arr;
    }

    // Get the list value of the list
    public RuntimeList getList() {
        return this;
    }

    // keys() operator
    public RuntimeArray keys() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // values() operator
    public RuntimeArray values() {
        throw new IllegalStateException("Type of arg 1 to values must be hash or array");
    }

    // Get the scalar value of the list
    public RuntimeScalar scalar() {
        if (elements.isEmpty()) {
            return new RuntimeScalar(); // Return undefined if empty
        }
        // XXX expand the last element
        return elements.get(elements.size() - 1).scalar();
    }

    public RuntimeScalar createReference() {
        // TODO
        throw new IllegalStateException("TODO - create reference of list not implemented");
    }

    public RuntimeList createListReference() {
        RuntimeList result = new RuntimeList();
        List<RuntimeBaseEntity> resultList = result.elements;
        Iterator<RuntimeScalar> iterator = this.iterator();
        while (iterator.hasNext()) {
            resultList.add(iterator.next().createReference());
        }
        return result;
    }

    // Set the items in the list to the values in another list
    // (THIS LIST) = (ARG LIST)
    //
    // In LIST context returns the ARG LIST
    // In SCALAR context returns the number of elements in ARG LIST
    //
    public RuntimeArray set(RuntimeList value) {

        // flatten the right side
        RuntimeArray original = new RuntimeArray();
        value.addToArray(original);

        // retrieve the list
        RuntimeArray arr = new RuntimeArray();
        original.addToArray(arr);

        for (RuntimeBaseEntity elem : elements) {
            if (elem instanceof RuntimeScalar) {
                ((RuntimeScalar) elem).set(arr.shift());
            } else if (elem instanceof RuntimeArray) {
                ((RuntimeArray) elem).elements = arr.elements;
                arr.elements = new ArrayList<>();
            } else if (elem instanceof RuntimeHash) {
                RuntimeHash hash = RuntimeHash.fromArray(arr);
                ((RuntimeHash) elem).elements = hash.elements;
                arr.elements = new ArrayList<>();
            }
        }
        return original;
    }

    // Convert the list to a string
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (RuntimeBaseEntity element : elements) {
            sb.append(element.toString());
        }
        return sb.toString();
    }

    // Method to return an iterator
    public Iterator<RuntimeScalar> iterator() {
        return new RuntimeListIterator(elements);
    }

    // undefine the elements of the list
    public RuntimeList undefine() {
        for (RuntimeBaseEntity elem : elements) {
            elem.undefine();
        }
        return this;
    }

    // Operators

    /**
     * Prints the elements to the specified file handle according to the format string.
     *
     * @param fileHandle The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public RuntimeScalar printf(RuntimeScalar fileHandle) {
        RuntimeScalar format = (RuntimeScalar) elements.remove(0); // Extract the format string from elements

        // Use sprintf to get the formatted string
        String formattedString = format.sprintf(this).toString();

        // Write the formatted content to the file handle
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.write(formattedString);
    }

    /**
     * Prints the elements to the specified file handle with a separator and newline.
     *
     * @param fileHandle The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public RuntimeScalar print(RuntimeScalar fileHandle) {
        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        String newline = getGlobalVariable("main::\\").toString();  // fetch $\
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBaseEntity element : elements) {
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
     * @param fileHandle The file handle to write to.
     * @return A RuntimeScalar indicating the result of the write operation.
     */
    public RuntimeScalar say(RuntimeScalar fileHandle) {
        StringBuilder sb = new StringBuilder();
        String separator = getGlobalVariable("main::,").toString(); // fetch $,
        boolean first = true;

        // Iterate through elements and append them with the separator
        for (RuntimeBaseEntity element : elements) {
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
    public RuntimeScalar readline(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.readline();
    }

    /**
     * Reads EOF flag from a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the flag.
     */
    public RuntimeScalar eof(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.eof();
    }

    /**
     * Close a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar with the result of the close operation.
     */
    public RuntimeScalar close(RuntimeScalar fileHandle) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        return fh.close();
    }

    /**
     * Opens a file and initialize a file handle.
     *
     * @param fileHandle The file handle.
     * @return A RuntimeScalar indicating the result of the open operation.
     */
    public RuntimeScalar open(RuntimeScalar fileHandle) {
//        open FILEHANDLE,MODE,EXPR
//        open FILEHANDLE,MODE,EXPR,LIST
//        open FILEHANDLE,MODE,REFERENCE
//        open FILEHANDLE,EXPR
//        open FILEHANDLE

        // fetch parameters - we are assuming the usual 3-argument open
        String mode = elements.get(0).toString();
        String fileName = elements.get(1).toString();

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
     * @param perlComparatorClosure A RuntimeScalar representing the Perl comparator subroutine.
     * @return A new RuntimeList with the elements sorted according to the Perl comparator.
     * @throws RuntimeException If the Perl comparator subroutine throws an exception.
     */
    public RuntimeList sort(RuntimeScalar perlComparatorClosure) {
        // Create a new list from the elements of this RuntimeArray
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

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
     * @param perlFilterClosure A RuntimeScalar representing the Perl filter subroutine.
     * @return A new RuntimeList with the elements that match the filter criteria.
     * @throws RuntimeException If the Perl filter subroutine throws an exception.
     */
    public RuntimeList grep(RuntimeScalar perlFilterClosure) {
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

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
     * @param perlMapClosure A RuntimeScalar representing the Perl map subroutine.
     * @return A new RuntimeList with the transformed elements.
     * @throws RuntimeException If the Perl map subroutine throws an exception.
     */
    public RuntimeList map(RuntimeScalar perlMapClosure) {
        RuntimeArray array = new RuntimeArray();
        this.setArrayOfAlias(array);

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

    private static class RuntimeListIterator implements Iterator<RuntimeScalar> {
        private final List<RuntimeBaseEntity> elements;
        private int currentIndex = 0;
        private Iterator<RuntimeScalar> currentIterator;

        public RuntimeListIterator(List<RuntimeBaseEntity> elements) {
            this.elements = elements;
            if (!elements.isEmpty()) {
                currentIterator = elements.get(0).iterator();
            }
        }

        @Override
        public boolean hasNext() {
            while (currentIterator != null) {
                if (currentIterator.hasNext()) {
                    return true;
                }
                // Move to the next element's iterator
                currentIndex++;
                if (currentIndex < elements.size()) {
                    currentIterator = elements.get(currentIndex).iterator();
                } else {
                    currentIterator = null; // No more elements
                }
            }
            return false;
        }

        @Override
        public RuntimeScalar next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentIterator.next();
        }
    }
}
