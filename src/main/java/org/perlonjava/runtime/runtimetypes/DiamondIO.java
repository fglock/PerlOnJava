package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.operators.Readline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalArray;
import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalIO;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * The DiamondIO class manages reading from multiple input files,
 * similar to Perl's diamond operator (<>). It also supports in-place
 * editing with backup creation, akin to Perl's -i switch.
 */
public class DiamondIO {
    public static final class State {
        RuntimeIO currentReader;
        RuntimeIO currentWriter;
        boolean eofReached;
        boolean readingStarted;
        boolean argvWasInitiallyEmpty;
        int accumulatedLineNumber;
        String inPlaceExtension;
        boolean inPlaceEdit;
        Path tempFilePath;
        RuntimeIO selectedHandleBeforeInPlace;

        void clear() {
            currentReader = null;
            currentWriter = null;
            eofReached = false;
            readingStarted = false;
            argvWasInitiallyEmpty = false;
            accumulatedLineNumber = 0;
            inPlaceExtension = null;
            inPlaceEdit = false;
            tempFilePath = null;
            selectedHandleBeforeInPlace = null;
        }
    }

    private static State state() {
        return PerlRuntime.current().diamondIOState;
    }

    public static void initialize(CompilerOptions compilerOptions) {
        // Reset all static variables to ensure clean state between compiler runs
        reset();

        state().inPlaceExtension = compilerOptions.inPlaceExtension;
        state().inPlaceEdit = compilerOptions.inPlaceEdit;
    }

    /**
     * Reset all static variables to their default values.
     * This ensures clean state between compiler runs and prevents state leakage.
     */
    public static void reset() {
        state().clear();
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
        State state = state();
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
            if (!state.readingStarted) {
                state.readingStarted = true;
                // Check if @ARGV was initially empty to determine STDIN fallback behavior
                state.argvWasInitiallyEmpty = getGlobalArray("main::ARGV").isEmpty();

                RuntimeIO argv = getGlobalIO("main::ARGV").getRuntimeIO();
                // Only use ARGV filehandle directly if @ARGV is empty (handles aliased filehandles like *ARGV = *DATA)
                if (argv != null && !(argv.ioHandle instanceof ClosedIOHandle) && getGlobalArray("main::ARGV").isEmpty()) {
                    state.currentReader = argv;
                } else if (state.argvWasInitiallyEmpty) {
                    RuntimeIO stdin = getGlobalIO("main::STDIN").getRuntimeIO();
                    if (stdin == null || stdin.ioHandle instanceof ClosedIOHandle) {
                        state.eofReached = true;
                        return scalarUndef;
                    }
                    // Only use STDIN if @ARGV was initially empty, not if it became empty after processing files
                    RuntimeArray.push(getGlobalArray("main::ARGV"), new RuntimeScalar("-"));
                }
            }

            while (true) {
                // If there's no current reader, try to open the next file
                if (state.currentReader == null) {
                    if (!openNextFile()) {
                        finishInPlaceEditing();
                        state.eofReached = true;
                        return scalarUndef;
                    }
                    // Carry over accumulated line number (Perl's $. continues across <> files)
                    state.currentReader.currentLineNumber = state.accumulatedLineNumber;
                }

                // Attempt to read a line from the current file
                RuntimeScalar line = Readline.readline(state.currentReader);
                if (line.type != RuntimeScalarType.UNDEF) {
                    state.accumulatedLineNumber = state.currentReader.currentLineNumber;
                    return line;
                }

                // EOF for current file — save accumulated line count before discarding reader
                state.accumulatedLineNumber = state.currentReader.currentLineNumber;
                state.currentReader = null;
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
        State state = state();
        // Close the current reader and writer if they exist
        if (state.currentReader != null) {
            state.currentReader.close();
            state.currentReader = null;
        }
        if (state.currentWriter != null) {
            state.currentWriter.close();
            state.currentWriter = null;
        }

        // Get the next file name from the global ARGV array
        RuntimeScalar fileName = RuntimeArray.shift(getGlobalArray("main::ARGV"));

        // Return false if no more files are available
        if (fileName.type == RuntimeScalarType.UNDEF) {
            return false;
        }

        String originalFileName = fileName.toString();
        String backupFileName = null;

        // Check if in-place editing is enabled (either via -i switch or $^I variable)
        boolean isInPlaceEnabled = state.inPlaceEdit;
        String extension = state.inPlaceExtension;

        // Also check $^I variable for runtime in-place editing
        if (!isInPlaceEnabled) {
            try {
                RuntimeScalar inPlaceVar = GlobalVariable.getGlobalVariable(GlobalContext.encodeSpecialVar("I"));
                if (inPlaceVar.getDefinedBoolean()) {
                    isInPlaceEnabled = true;
                    extension = inPlaceVar.toString();
                }
            } catch (Exception e) {
                // If $^I is not accessible, continue without in-place editing
            }
        }

        if (isInPlaceEnabled) {
            // Use RuntimeIO's existing path resolution methods for consistency
            Path originalPath = RuntimeIO.resolvePath(originalFileName);

            if (extension == null || extension.isEmpty()) {
                // Create a temporary file for the original file
                try {
                    state.tempFilePath = Files.createTempFile("temp_", null);
                    backupFileName = state.tempFilePath.toString();

                    Files.move(originalPath, state.tempFilePath, StandardCopyOption.REPLACE_EXISTING);

                    // Schedule the file for deletion on JVM exit
                    state.tempFilePath.toFile().deleteOnExit();

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

                // Use RuntimeIO's existing path resolution for consistency
                Path backupPath = RuntimeIO.resolvePath(backupFileName);

                // Rename the original file to the backup file if needed
                try {
                    // Check if original file exists and is readable
                    if (!Files.exists(originalPath)) {
                        System.err.println("Error: Original file does not exist: " + originalPath.toAbsolutePath());
                        return false;
                    }
                    if (!Files.isReadable(originalPath)) {
                        System.err.println("Error: Original file is not readable: " + originalFileName);
                        return false;
                    }

                    // Check if backup file already exists
                    if (Files.exists(backupPath)) {
                        System.err.println("Warning: Backup file already exists, will overwrite: " + backupFileName);
                    }

                    Files.move(originalPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.err.println("Error: Unable to create backup file " + backupFileName + ": " + e.getMessage());
                    e.printStackTrace();
                    return false;
                }
            }

            // Open the original file for writing (this is the ARGVOUT equivalent)
            // Use the resolved path to ensure we write to the correct location
            state.currentWriter = RuntimeIO.open(originalPath.toString(), ">");
            getGlobalIO("main::ARGVOUT").set(state.currentWriter);
            RuntimeIO.setLastAccessedHandle(state.currentWriter);

            // CRITICAL: Update selectedHandle so print statements without explicit filehandle
            // write to the original file during in-place editing
            if (state.selectedHandleBeforeInPlace == null) {
                state.selectedHandleBeforeInPlace = RuntimeIO.getSelectedHandle();
            }
            RuntimeIO.setSelectedHandle(state.currentWriter);
        }

        // Open the renamed file for reading
        String readerPath = state.tempFilePath != null ? state.tempFilePath.toString() : (backupFileName != null ? backupFileName : originalFileName);
        state.currentReader = RuntimeIO.open(readerPath);
        getGlobalIO("main::ARGV").set(state.currentReader);

        return state.currentReader != null;
    }

    /** Restore the handle selected before diamond in-place editing began. */
    private static void finishInPlaceEditing() {
        State state = state();
        if (state.selectedHandleBeforeInPlace != null) {
            RuntimeIO.setSelectedHandle(state.selectedHandleBeforeInPlace);
            state.selectedHandleBeforeInPlace = null;
        }
    }
}
