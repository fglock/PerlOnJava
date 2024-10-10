package org.perlonjava.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.perlonjava.runtime.GlobalContext.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

public class DiamondIO extends RuntimeIO {
    // List of files to read from, simulating the behavior of Perl's @ARGV
    private final List<String> fileList;
    // Current BufferedReader for the file being read
    private BufferedReader currentReader;
    // Index of the current file being read from fileList
    private int currentFileIndex = -1;
    // Flag to indicate if EOF has been reached across all files
    private boolean eofReached = false;

    /**
     * Constructor for DiamondIO.
     * Initializes the file list. If no files are provided, defaults to reading from standard input.
     *
     * @param files List of file names to read from.
     */
    public DiamondIO(List<String> files) {
        super();
        // If no files are provided, default to reading from standard input ("-")
        this.fileList = files.isEmpty() ? Collections.singletonList("-") : new ArrayList<>(files);
    }

    /**
     * Reads a line from the current file or standard input.
     * If EOF is reached, attempts to open the next file in the list.
     *
     * @return A RuntimeScalar containing the line read, or scalarUndef if EOF is reached.
     */
    public RuntimeScalar readline() {
        if (eofReached) {
            return scalarUndef; // Return undef if EOF was already reached
        }

        try {
            // Loop to handle switching to the next file when EOF is reached
            while (currentReader == null || isEOF()) {
                if (!openNextFile()) {
                    eofReached = true; // Mark EOF reached across all files
                    return scalarUndef; // All files have been read
                }
            }

            // Read a line from the current file
            String line = currentReader.readLine();
            if (line == null) {
                return readline(); // Try reading from the next file if current file is exhausted
            }
            return new RuntimeScalar(line); // Return the line read as a RuntimeScalar
        } catch (IOException e) {
            // Set a global variable with the error message
            getGlobalVariable("main::!").set("File operation failed: " + e.getMessage());
            return scalarUndef;
        }
    }

    /**
     * Opens the next file in the fileList for reading.
     *
     * @return true if a file was successfully opened, false if there are no more files.
     */
    private boolean openNextFile() {
        closeCurrentReader(); // Close the current reader before opening a new file
        currentFileIndex++;
        if (currentFileIndex >= fileList.size()) {
            return false; // No more files to read
        }

        String fileName = fileList.get(currentFileIndex);
        try {
            // Open standard input if the file name is "-", otherwise open the specified file
            if ("-".equals(fileName)) {
                currentReader = new BufferedReader(new InputStreamReader(System.in));
            } else {
                currentReader = Files.newBufferedReader(Paths.get(fileName));
            }
        } catch (IOException e) {
            // Set a global variable with the error message
            getGlobalVariable("main::!").set("Failed to open file: " + fileName);
            return false;
        }
        return true;
    }

    /**
     * Closes the current BufferedReader.
     */
    private void closeCurrentReader() {
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (IOException e) {
                // Log or handle the exception if necessary
            }
            currentReader = null;
        }
    }

    /**
     * Checks if the current reader has reached EOF.
     *
     * @return true if EOF is reached, false otherwise.
     */
    private boolean isEOF() {
        try {
            return currentReader != null && !currentReader.ready();
        } catch (IOException e) {
            return true; // Assume EOF if an exception occurs
        }
    }
}