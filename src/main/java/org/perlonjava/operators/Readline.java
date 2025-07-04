package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class Readline {
    /**
     * Reads a line from a file handle.
     *
     * @param fileHandle The file handle.
     * @param ctx        The context (SCALAR or LIST).
     * @return A RuntimeDataProvider with the line(s).
     */
    public static RuntimeDataProvider readline(RuntimeScalar fileHandle, int ctx) {
        RuntimeIO fh = fileHandle.getRuntimeIO();
        if (ctx == RuntimeContextType.LIST) {
            // Handle LIST context
            RuntimeList lines = new RuntimeList();
            RuntimeScalar line;
            while ((line = readline(fh)).type != RuntimeScalarType.UNDEF) {
                lines.elements.add(line);
            }
            return lines;
        } else {
            // Handle SCALAR context (original behavior)
            return readline(fh);
        }
    }

    public static RuntimeScalar readline(RuntimeIO runtimeIO) {
        // Flush stdout and stderr before reading, in case we are displaying a prompt
        RuntimeIO.flushFileHandles();

        // Check if the IO object is set up for reading
        if (runtimeIO.ioHandle == null) {
            throw new PerlCompilerException("readline is not supported for output streams");
        }

        // Get the input record separator (equivalent to Perl's $/)
        String sep = getGlobalVariable("main::/").toString();
        boolean hasSeparator = !sep.isEmpty();
        int separator = hasSeparator ? sep.charAt(0) : '\n';

        StringBuilder line = new StringBuilder();

        String readChar;
        while (!(readChar = runtimeIO.ioHandle.read(1).toString()).isEmpty()) {
            char c = readChar.charAt(0);
            line.append(c);
            // Break if we've reached the separator (if defined)
            if (hasSeparator && c == separator) {
                break;
            }
        }

        // Increment the line number counter if a line was read
        if (!line.isEmpty()) {
            runtimeIO.currentLineNumber++;
        }

        // Return undef if we've reached EOF and no characters were read
        if (line.isEmpty() && runtimeIO.eof().getBoolean()) {
            return scalarUndef;
        }

        // Return the read line as a RuntimeScalar
        return new RuntimeScalar(line.toString());
    }

    /**
     * Reads a specified number of characters from a file handle into a scalar.
     *
     * @param args A RuntimeList containing fileHandle, scalar, length, and offset.
     * @return The number of characters read, or 0 at EOF, or undef on error.
     */
    public static RuntimeScalar read(RuntimeList args) {
        // Extract arguments from the list
        RuntimeScalar fileHandle = (RuntimeScalar) args.elements.get(0);
        RuntimeScalar scalar = ((RuntimeScalar) args.elements.get(1)).scalarDeref();
        RuntimeScalar length = (RuntimeScalar) args.elements.get(2);
        RuntimeScalar offset = args.elements.size() > 3
                ? (RuntimeScalar) args.elements.get(3)
                : new RuntimeScalar(0);

        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh == null) {
            getGlobalVariable("main::!").set("read file handle is closed");
            return scalarFalse;
        }

        // Check if the IO object is set up for reading
        if (fh.ioHandle == null) {
            getGlobalVariable("main::!").set("read is not open for input");
            return scalarFalse;
        }

        // Convert length and offset to integers
        int lengthValue = length.getInt();
        int offsetValue = offset.getInt();

        // Read data using the new API
        String readData = fh.ioHandle.read(lengthValue).toString();
        int bytesRead = readData.length();

        if (bytesRead == 0) {
            // EOF or error - clear the scalar when reading 0 bytes
            scalar.set("");
            return new RuntimeScalar(0);
        }

        // Handle offset
        StringBuilder scalarValue = new StringBuilder(scalar.toString());

        // Special case: if offset is 0 and no offset was explicitly provided,
        // replace the entire scalar content
        if (offsetValue == 0 && args.elements.size() <= 3) {
            scalar.set(readData);
            return new RuntimeScalar(bytesRead);
        }

        if (offsetValue < 0) {
            offsetValue = scalarValue.length() + offsetValue;
        }
        if (offsetValue > scalarValue.length()) {
            // Pad with null characters if offset is greater than current length
            while (scalarValue.length() < offsetValue) {
                scalarValue.append('\0');
            }
        }

        // Insert the read data at the specified offset
        if (offsetValue + readData.length() <= scalarValue.length()) {
            // Replace within existing string
            scalarValue.replace(offsetValue, offsetValue + readData.length(), readData);
        } else {
            // Extend the string
            scalarValue.setLength(offsetValue);
            scalarValue.append(readData);
        }

        // Update the scalar with the new value
        scalar.set(scalarValue.toString());

        // Return the number of characters read
        return new RuntimeScalar(bytesRead);
    }
}
