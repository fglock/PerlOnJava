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
     * @return A RuntimeBase with the line(s).
     */
    public static RuntimeBase readline(RuntimeScalar fileHandle, int ctx) {
        RuntimeIO fh = fileHandle.getRuntimeIO();

        if (fh instanceof TieHandle tieHandle) {
            return TieHandle.tiedReadline(tieHandle, ctx);
        }

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
        RuntimeScalar rsScalar = getGlobalVariable("main::/");

        // Check if we're dealing with an InputRecordSeparator instance
        InputRecordSeparator rs = null;
        if (rsScalar instanceof InputRecordSeparator) {
            rs = (InputRecordSeparator) rsScalar;
        }

        // Handle different modes of $/
        if (rs != null && rs.isSlurpMode()) {
            // Handle slurp mode when $/ = undef
            StringBuilder content = new StringBuilder();
            String readChar;
            while (!(readChar = runtimeIO.ioHandle.read(1).toString()).isEmpty()) {
                content.append(readChar.charAt(0));
            }

            if (content.length() > 0) {
                // Count newlines for line number tracking
                String contentStr = content.toString();
                for (int i = 0; i < contentStr.length(); i++) {
                    if (contentStr.charAt(i) == '\n') {
                        runtimeIO.currentLineNumber++;
                    }
                }
                return new RuntimeScalar(contentStr);
            } else if (runtimeIO.eof().getBoolean()) {
                return scalarUndef;
            }
            return new RuntimeScalar(content.toString());
        }

        if (rs != null && rs.isParagraphMode()) {
            // Handle paragraph mode when $/ = ''
            return readParagraphMode(runtimeIO);
        }

        if (rs != null && rs.isRecordLengthMode()) {
            // Handle record length mode when $/ = \N
            int recordLength = rs.getRecordLength();
            return readFixedLength(runtimeIO, recordLength);
        }

        // Handle normal string separator mode
        String sep = rsScalar.toString();

        if (sep.isEmpty()) {
            // Handle paragraph mode when $/ = '' (fallback if not InputRecordSeparator)
            return readParagraphMode(runtimeIO);
        }

        // Handle multi-character or single character separators
        if (sep.length() == 1) {
            // Single character separator (optimized path)
            return readUntilCharacter(runtimeIO, sep.charAt(0));
        } else {
            // Multi-character separator
            return readUntilString(runtimeIO, sep);
        }
    }

    private static RuntimeScalar readParagraphMode(RuntimeIO runtimeIO) {
        StringBuilder paragraph = new StringBuilder();
        boolean inParagraph = false;
        boolean lastWasNewline = false;

        String readChar;
        while (!(readChar = runtimeIO.ioHandle.read(1).toString()).isEmpty()) {
            char c = readChar.charAt(0);

            if (c == '\n') {
                if (!inParagraph) {
                    // Skip leading newlines
                    continue;
                }
                paragraph.append(c);
                if (lastWasNewline) {
                    // Found blank line (two consecutive newlines) - end of paragraph
                    break;
                }
                lastWasNewline = true;
            } else {
                inParagraph = true;
                lastWasNewline = false;
                paragraph.append(c);
            }
        }

        // Return undef if we've reached EOF and no characters were read (excluding skipped newlines)
        if (!inParagraph && runtimeIO.eof().getBoolean()) {
            return scalarUndef;
        }

        // Increment the line number counter if a paragraph was read
        if (inParagraph) {
            // Count the number of lines in the paragraph
            String paragraphStr = paragraph.toString();
            for (int i = 0; i < paragraphStr.length(); i++) {
                if (paragraphStr.charAt(i) == '\n') {
                    runtimeIO.currentLineNumber++;
                }
            }
        }

        return new RuntimeScalar(paragraph.toString());
    }

    private static RuntimeScalar readFixedLength(RuntimeIO runtimeIO, int length) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < length; i++) {
            String readChar = runtimeIO.ioHandle.read(1).toString();
            if (readChar.isEmpty()) {
                break; // EOF reached
            }
            result.append(readChar.charAt(0));
        }

        // Return undef if we've reached EOF and no characters were read
        if (result.length() == 0 && runtimeIO.eof().getBoolean()) {
            return scalarUndef;
        }

        // Don't increment line numbers for fixed-length reads
        // (this matches Perl behavior for record-length mode)

        return new RuntimeScalar(result.toString());
    }

    private static RuntimeScalar readUntilCharacter(RuntimeIO runtimeIO, char separator) {
        StringBuilder line = new StringBuilder();

        String readChar;
        while (!(readChar = runtimeIO.ioHandle.read(1).toString()).isEmpty()) {
            char c = readChar.charAt(0);
            line.append(c);
            // Break if we've reached the separator
            if (c == separator) {
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

        return new RuntimeScalar(line.toString());
    }

    private static RuntimeScalar readUntilString(RuntimeIO runtimeIO, String separator) {
        StringBuilder line = new StringBuilder();
        StringBuilder buffer = new StringBuilder();

        String readChar;
        while (!(readChar = runtimeIO.ioHandle.read(1).toString()).isEmpty()) {
            char c = readChar.charAt(0);
            line.append(c);
            buffer.append(c);

            // Keep only the last separator.length() characters in buffer
            if (buffer.length() > separator.length()) {
                buffer.deleteCharAt(0);
            }

            // Check if buffer ends with separator
            if (buffer.toString().equals(separator)) {
                break;
            }
        }

        // Increment the line number counter if a line was read and contains newlines
        if (!line.isEmpty()) {
            String lineStr = line.toString();
            for (int i = 0; i < lineStr.length(); i++) {
                if (lineStr.charAt(i) == '\n') {
                    runtimeIO.currentLineNumber++;
                }
            }
        }

        // Return undef if we've reached EOF and no characters were read
        if (line.isEmpty() && runtimeIO.eof().getBoolean()) {
            return scalarUndef;
        }

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
        RuntimeScalar fileHandle = (RuntimeScalar) args.elements.getFirst();
        RuntimeIO fh = fileHandle.getRuntimeIO();

        RuntimeScalar scalar = ((RuntimeScalar) args.elements.get(1)).scalarDeref();
        RuntimeScalar length = (RuntimeScalar) args.elements.get(2);
        RuntimeScalar offset = args.elements.size() > 3
                ? (RuntimeScalar) args.elements.get(3)
                : new RuntimeScalar(0);

        if (fh instanceof TieHandle tieHandle) {
            args = args.elements.size() > 3
                    ? new RuntimeList(scalar, length, offset)
                    : new RuntimeList(scalar, length);
            return TieHandle.tiedRead(tieHandle, args);
        }

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
