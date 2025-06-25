package org.perlonjava.operators;

import org.perlonjava.runtime.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
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
     * @param fileHandle The file handle.
     * @param scalar     The scalar to read data into.
     * @param length     The number of characters to read (as RuntimeScalar).
     * @param offset     The offset to start writing in the scalar (as RuntimeScalar).
     * @return The number of characters read, or 0 at EOF, or undef on error.
     */
    public static RuntimeScalar read(RuntimeScalar fileHandle, RuntimeScalar scalar, RuntimeScalar length, RuntimeScalar offset) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        // Check if the IO object is set up for reading
        if (fh.ioHandle == null) {
            throw new PerlCompilerException("read is not supported for output streams");
        }

        // Convert length and offset to integers
        int lengthValue = length.getInt();
        int offsetValue = offset.getInt();

        // Read data using the new API
        String readData = fh.ioHandle.read(lengthValue).toString();
        int bytesRead = readData.length();

        if (bytesRead == 0) {
            // EOF or error
            return new RuntimeScalar(0);
        }

        // Handle offset
        StringBuilder scalarValue = new StringBuilder(scalar.toString());
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

    // Overloaded method without offset
    public static RuntimeScalar read(RuntimeScalar fileHandle, RuntimeScalar scalar, RuntimeScalar length) {
        return read(fileHandle, scalar, length, new RuntimeScalar(0));
    }
}
