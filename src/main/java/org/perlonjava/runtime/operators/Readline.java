package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

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

        if (fh == null) {
            // Check for <> overload before warning about unopened filehandle
            int blessId = RuntimeScalarType.blessedId(fileHandle);
            if (blessId < 0) {
                OverloadContext overloadCtx = OverloadContext.prepare(blessId);
                if (overloadCtx != null) {
                    RuntimeScalar result = overloadCtx.tryOverload("(<>", new RuntimeArray(fileHandle));
                    if (result != null) {
                        return result;
                    }
                }
            }

            // Perl warns and returns undef for unopened filehandle, doesn't die
            WarnDie.warn(new RuntimeScalar("readline() on unopened filehandle"), new RuntimeScalar("\n"));
            return ctx == RuntimeContextType.LIST ? new RuntimeList() : scalarUndef;
        }

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

        // Set this as the last accessed handle for $. (INPUT_LINE_NUMBER) special variable
        RuntimeIO.lastAccesseddHandle = runtimeIO;

        // Get the input record separator (equivalent to Perl's $/)
        RuntimeScalar rsScalar = getGlobalVariable("main::/");

        // Check if we're dealing with an InputRecordSeparator instance
        InputRecordSeparator rs = null;
        if (rsScalar instanceof InputRecordSeparator) {
            rs = (InputRecordSeparator) rsScalar;
        }

        // Handle different modes of $/
        boolean isSlurp = (rs != null && rs.isSlurpMode()) ||
                (rs == null && rsScalar.type == RuntimeScalarType.UNDEF);
        if (isSlurp) {
            StringBuilder content = new StringBuilder();
            boolean isByteData = true;
            RuntimeScalar chunk;
            while (true) {
                chunk = runtimeIO.ioHandle.read(8192);
                String chunkStr = chunk.toString();
                if (chunkStr.isEmpty()) break;
                if (chunk.type != RuntimeScalarType.BYTE_STRING) isByteData = false;
                content.append(chunkStr);
            }

            if (content.length() > 0) {
                String contentStr = content.toString();
                // In Perl 5, slurp mode increments $. by 1 (not per line)
                runtimeIO.currentLineNumber++;
                RuntimeScalar result = new RuntimeScalar(contentStr);
                if (isByteData) {
                    result.type = RuntimeScalarType.BYTE_STRING;
                }
                return result;
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
        boolean isByteMode = runtimeIO.isByteMode();
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

        // Increment the line number counter once per paragraph read.
        // In Perl 5, $. counts paragraphs (not lines) in paragraph mode.
        if (inParagraph) {
            runtimeIO.currentLineNumber++;
        }

        RuntimeScalar result = new RuntimeScalar(paragraph.toString());
        if (isByteMode) {
            result.type = RuntimeScalarType.BYTE_STRING;
        }
        return result;
    }

    private static RuntimeScalar readFixedLength(RuntimeIO runtimeIO, int length) {
        boolean isByteMode = runtimeIO.isByteMode();
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

        RuntimeScalar rslt = new RuntimeScalar(result.toString());
        if (isByteMode) {
            rslt.type = RuntimeScalarType.BYTE_STRING;
        }
        return rslt;
    }

    private static RuntimeScalar readUntilCharacter(RuntimeIO runtimeIO, char separator) {
        boolean isByteMode = runtimeIO.isByteMode();
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

        RuntimeScalar result = new RuntimeScalar(line.toString());
        if (isByteMode) {
            result.type = RuntimeScalarType.BYTE_STRING;
        }
        return result;
    }

    private static RuntimeScalar readUntilString(RuntimeIO runtimeIO, String separator) {
        boolean isByteMode = runtimeIO.isByteMode();
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

        // Increment the line number counter once per record read.
        // In Perl, $. counts records (not newlines) regardless of the value of $/.
        if (!line.isEmpty()) {
            runtimeIO.currentLineNumber++;
        }

        // Return undef if we've reached EOF and no characters were read
        if (line.isEmpty() && runtimeIO.eof().getBoolean()) {
            return scalarUndef;
        }

        RuntimeScalar result = new RuntimeScalar(line.toString());
        if (isByteMode) {
            result.type = RuntimeScalarType.BYTE_STRING;
        }
        return result;
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

        // Handle zero-length read
        if (lengthValue == 0) {
            String currentValue = scalar.toString();
            StringBuilder scalarValue = new StringBuilder(currentValue);

            // Truncate the buffer at the offset
            if (offsetValue < 0) {
                offsetValue = scalarValue.length() + offsetValue;
                if (offsetValue < 0) {
                    offsetValue = 0;
                }
            }
            scalarValue.setLength(offsetValue);
            scalar.set(scalarValue.toString());
            return new RuntimeScalar(0);
        }

        RuntimeScalar readResult = fh.ioHandle.read(lengthValue);
        boolean isByteData = readResult.type == RuntimeScalarType.BYTE_STRING;
        String readData = readResult.toString();
        int charsRead = readData.length();

        if (charsRead == 0) {
            if (offsetValue != 0) {
                StringBuilder scalarValue = new StringBuilder(scalar.toString());
                if (offsetValue < 0) {
                    offsetValue = scalarValue.length() + offsetValue;
                    if (offsetValue < 0) {
                        offsetValue = 0;
                    }
                }
                while (scalarValue.length() < offsetValue) {
                    scalarValue.append('\0');
                }
                scalarValue.setLength(offsetValue);
                scalar.set(scalarValue.toString());
            } else {
                scalar.set("");
            }
            return new RuntimeScalar(0);
        }

        StringBuilder scalarValue = new StringBuilder(scalar.toString());

        if (offsetValue < 0) {
            offsetValue = scalarValue.length() + offsetValue;
            if (offsetValue < 0) {
                offsetValue = 0;
            }
        }

        int newLength = offsetValue + charsRead;
        while (scalarValue.length() < offsetValue) {
            scalarValue.append('\0');
        }
        scalarValue.replace(offsetValue, scalarValue.length(), readData);
        scalarValue.setLength(newLength);

        if (isByteData && scalar.type != RuntimeScalarType.STRING) {
            String s = scalarValue.toString();
            boolean safe = true;
            for (int i = 0; safe && i < s.length(); i++) {
                if (s.charAt(i) > 255) {
                    safe = false;
                    break;
                }
            }
            if (safe) {
                scalar.set(new RuntimeScalar(s.getBytes(StandardCharsets.ISO_8859_1)));
            } else {
                scalar.set(s);
            }
        } else {
            scalar.set(scalarValue.toString());
        }

        // Return the number of characters read
        return new RuntimeScalar(charsRead);
    }
}
