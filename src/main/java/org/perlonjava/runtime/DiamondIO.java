package org.perlonjava.runtime;

import org.perlonjava.ArgumentParser;
import org.perlonjava.operators.Readline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.perlonjava.runtime.GlobalVariable.getGlobalArray;
import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * The DiamondIO class manages reading from multiple input files,
 * similar to Perl's diamond operator (<>). It also supports in-place
 * editing with backup creation, akin to Perl's -i switch.
 */
public class DiamondIO {

    // Static variable to hold the current file reader
    static RuntimeIO currentReader;

    // Static variable to hold the current file writer (for in-place editing)
    static RuntimeIO currentWriter;

    // Flag to indicate if the end of all files has been reached
    static boolean eofReached = false;

    // Flag to indicate if the reading process has started
    static boolean readingStarted = false;

    // Static field to store the in-place extension for the -i switch
    static String inPlaceExtension = null;
    static boolean inPlaceEdit = false;

    // Path to the temporary file to be deleted on exit
    static Path tempFilePath = null;

    public static void initialize(ArgumentParser.CompilerOptions compilerOptions) {
        inPlaceExtension = compilerOptions.inPlaceExtension;
        inPlaceEdit = compilerOptions.inPlaceEdit;
    }

    /**
     * Reads a line from the current file. If the end of the file is reached,
     * it attempts to open the next file. If all files are exhausted, it returns
     * an undefined scalar.
     *
     * @param arg An unused parameter, kept for compatibility with other readline methods
     * @param ctx The context in which the method is called (SCALAR or LIST)
     * @return A RuntimeScalar representing the line read from the file, or an
     * undefined scalar if EOF is reached for all files.
     */
    public static RuntimeBase readline(RuntimeScalar arg, int ctx) {
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
                if (getGlobalArray("main::ARGV").isEmpty()) {
                    RuntimeArray.push(getGlobalArray("main::ARGV"), new RuntimeScalar("-"));
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
                RuntimeScalar line = Readline.readline(currentReader);
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
     * If in-place editing is enabled, it also sets up the writer for the
     * output file. Updates the global variables to reflect the current file
     * being read and written.
     *
     * @return true if a new file was successfully opened, false if no more files are available.
     */
    private static boolean openNextFile() {
        // Close the current reader and writer if they exist
        if (currentReader != null) {
            currentReader.close();
            currentReader = null;
        }
        if (currentWriter != null) {
            currentWriter.close();
            currentWriter = null;
        }

        // Get the next file name from the global ARGV array
        RuntimeScalar fileName = RuntimeArray.shift(getGlobalArray("main::ARGV"));

        // Return false if no more files are available
        if (fileName.type == RuntimeScalarType.UNDEF) {
            return false;
        }

        String originalFileName = fileName.toString();
        String backupFileName = null;

        // Check if in-place editing is enabled
        if (inPlaceEdit) {
            String extension = inPlaceExtension;
            if (extension == null || extension.isEmpty()) {
                // Create a temporary file for the original file
                try {
                    tempFilePath = Files.createTempFile("temp_", null);
                    backupFileName = tempFilePath.toString();

                    Files.move(Paths.get(originalFileName), tempFilePath, StandardCopyOption.REPLACE_EXISTING);

                    // Schedule the file for deletion on JVM exit
                    tempFilePath.toFile().deleteOnExit();

                } catch (IOException e) {
                    System.err.println("Error: Unable to create temporary file for " + originalFileName + ": " + e);
                    return false;
                }
            } else {
                if (extension.contains("*")) {
                    backupFileName = extension.replace("*", originalFileName);
                } else {
                    backupFileName = originalFileName + extension;
                }
                // Rename the original file to the backup file if needed
                try {
                    Files.move(Paths.get(originalFileName), Paths.get(backupFileName));
                } catch (IOException e) {
                    System.err.println("Error: Unable to create backup file " + backupFileName);
                    return false;
                }
            }

            // Open the original file for writing (this is the ARGVOUT equivalent)
            currentWriter = RuntimeIO.open(originalFileName, ">");
            getGlobalIO("main::ARGVOUT").set(currentWriter);
            RuntimeIO.lastAccesseddHandle = currentWriter;
        }

        // Open the renamed file for reading
        currentReader = RuntimeIO.open(tempFilePath != null ? tempFilePath.toString() : (backupFileName != null ? backupFileName : originalFileName));
        getGlobalIO("main::ARGV").set(currentReader);

        return currentReader != null;
    }
}
