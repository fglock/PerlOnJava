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
        byte[] buffer = new byte[1]; // Buffer to read one byte at a time

        int bytesRead;
        while ((bytesRead = runtimeIO.ioHandle.read(buffer).getInt()) != -1) {
            char c = (char) buffer[0];
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
}
