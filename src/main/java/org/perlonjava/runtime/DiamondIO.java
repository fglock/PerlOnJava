package org.perlonjava.runtime;

import static org.perlonjava.runtime.GlobalContext.*;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class DiamondIO {

    // Static variable to hold the current file reader
    static RuntimeIO currentReader;

    // Flag to indicate if the end of all files has been reached
    static boolean eofReached = false;

    /**
     * Reads a line from the current file. If the end of the file is reached,
     * it attempts to open the next file. If all files are exhausted, it returns
     * an undefined scalar.
     *
     * @return A RuntimeScalar representing the line read from the file, or an
     *         undefined scalar if EOF is reached for all files.
     */
    static RuntimeScalar readline() {
        // Check if EOF has been reached for all files
        if (eofReached) {
            return scalarUndef;
        }

        // Loop until a valid line is read or all files are exhausted
        while (currentReader == null || !currentReader.eof().getBoolean()) {
            // Try to open the next file if the current reader is null or EOF is reached
            if (!openNextFile()) {
                eofReached = true;
                return scalarUndef;
            }
        }

        // Read a line from the current file
        RuntimeScalar line = currentReader.readline();

        // If the line is undefined, recursively call readline to get the next line
        if (line.type == RuntimeScalarType.UNDEF) {
            return readline();
        }

        // Return the line read
        return line;
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