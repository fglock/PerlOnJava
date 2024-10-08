package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The DiamondIO class manages reading from multiple input files,
 * similar to Perl's diamond operator (<>).
 */
public class DiamondIO {

    // Static variable to hold the current file reader
    static RuntimeIO currentReader;

    // Flag to indicate if the end of all files has been reached
    static boolean eofReached = false;

    // Flag to indicate if the reading process has started
    static boolean readingStarted = false;

    /**
     * Reads a line from the current file. If the end of the file is reached,
     * it attempts to open the next file. If all files are exhausted, it returns
     * an undefined scalar.
     *
     * @param arg An unused parameter, kept for compatibility with other readline methods
     * @return A RuntimeScalar representing the line read from the file, or an
     * undefined scalar if EOF is reached for all files.
     */
    public static RuntimeDataProvider readline(RuntimeScalar arg, int ctx) {
        if (ctx == RuntimeContextType.LIST) {
            // Handle LIST context
            RuntimeList lines = new RuntimeList();
            RuntimeScalar line;
            while ((line = (RuntimeScalar) readline(arg, RuntimeContextType.SCALAR)).type != RuntimeScalarType.UNDEF) {
                lines.elements.add(line);
            }
            return lines;
        } else {
            // Handle SCALAR context
            // Initialize the reading process if it hasn't started yet
            if (!readingStarted) {
                readingStarted = true;
                // If no files are specified, use standard input (represented by "-")
                if (getGlobalArray("main::ARGV").size() == 0) {
                    getGlobalArray("main::ARGV").push(new RuntimeScalar("-"));
                }
            }

            while (true) {
                // If there's no current reader, try to open the next file
                if (currentReader == null) {
                    if (!openNextFile()) {
                        eofReached = true;
                        return scalarUndef;
                    }
                }

                // Attempt to read a line from the current file
                RuntimeScalar line = currentReader.readline();
                if (line.type != RuntimeScalarType.UNDEF) {
                    return line;
                }

                // If we reach here, we've hit EOF for the current file
                currentReader = null;
            }
        }
    }

    /**
     * Opens the next file in the list and sets it as the current reader.
     * Updates the global variables to reflect the current file being read.
     *
     * @return true if a new file was successfully opened, false if no more files are available.
     */
    private static boolean openNextFile() {
        // Close the current reader if it exists
        if (currentReader != null) {
            currentReader.close();
            currentReader = null;
        }

        // Get the next file name from the global ARGV array
        RuntimeScalar fileName = getGlobalArray("main::ARGV").shift();

        // Return false if no more files are available
        if (fileName.type == RuntimeScalarType.UNDEF) {
            return false;
        }

        // Set the current filename in the global $main::ARGV variable
        getGlobalVariable("main::ARGV").set(fileName);

        // Open the file and set it as the current reader
        currentReader = RuntimeIO.open(fileName.toString());

        // Set the current handle in the global main::ARGV handle
        getGlobalIO("main::ARGV").set(currentReader);

        // Return true if the current reader is successfully set
        return currentReader != null;
    }
}