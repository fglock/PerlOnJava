package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.runtime.io.ClosedIOHandle;
import org.perlonjava.runtime.operators.Readline;
import org.perlonjava.runtime.operators.WarnDie;

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
        boolean doubleDiamond;
        RuntimeIO stdinReader;
        RuntimeIO lastDiamondReader;
        int accumulatedLineNumber;
        String inPlaceExtension;
        boolean inPlaceEdit;
        Path tempFilePath;
        Path inPlaceOriginalPath;
        RuntimeIO selectedHandleBeforeInPlace;

        void clear() {
            currentReader = null;
            currentWriter = null;
            eofReached = false;
            readingStarted = false;
            argvWasInitiallyEmpty = false;
            doubleDiamond = false;
            stdinReader = null;
            lastDiamondReader = null;
            accumulatedLineNumber = 0;
            inPlaceExtension = null;
            inPlaceEdit = false;
            tempFilePath = null;
            inPlaceOriginalPath = null;
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
        return readline(arg, ctx, arg != null && "<>".equals(arg.toString()));
    }

    /** Reads diamond input while preserving whether the source used <<>>. */
    public static RuntimeBase readline(RuntimeScalar arg, int ctx, boolean doubleDiamond) {
        State state = state();
        if (ctx == RuntimeContextType.LIST) {
            // Handle LIST context
            RuntimeList lines = new RuntimeList();
            RuntimeScalar line;
            while ((line = (RuntimeScalar) readline(arg, RuntimeContextType.SCALAR, doubleDiamond)).type != RuntimeScalarType.UNDEF) {
                lines.elements.add(line);
            }
            return lines;
        } else {
            RuntimeArray argv = getGlobalArray("main::ARGV");
            // A completed diamond loop is reusable after Perl code refills
            // @ARGV. In particular, `@ARGV = (...)` between separate `<>`
            // loops must begin a new traversal rather than retain EOF forever.
            RuntimeIO stdin = getGlobalIO("main::STDIN").getRuntimeIO();
            // A new non-empty @ARGV always starts a new traversal. An empty
            // @ARGV can also be intentional: reopening STDIN between diamond
            // loops must make the later loop consume the replacement handle.
            if (state.eofReached && (!argv.isEmpty()
                    || (stdin != null && stdin != state.stdinReader))) {
                resetTraversalState(state);
            }
            // Handle SCALAR context
            // Initialize the reading process if it hasn't started yet
            if (!state.readingStarted) {
                state.readingStarted = true;
                state.doubleDiamond = doubleDiamond;
                // Check if @ARGV was initially empty to determine STDIN fallback behavior
                state.argvWasInitiallyEmpty = argv.isEmpty();

                RuntimeIO argvHandle = getGlobalIO("main::ARGV").getRuntimeIO();
                // Only use ARGV filehandle directly if @ARGV is empty (handles aliased filehandles like *ARGV = *DATA)
                if (argvHandle != null && !(argvHandle.ioHandle instanceof ClosedIOHandle) && getGlobalArray("main::ARGV").isEmpty()) {
                    state.currentReader = argvHandle;
                } else if (state.argvWasInitiallyEmpty) {
                    if (stdin == null || stdin.ioHandle instanceof ClosedIOHandle) {
                        state.eofReached = true;
                        return scalarUndef;
                    }
                    // Only use STDIN if @ARGV was initially empty, not if it became empty after processing files
                    RuntimeArray.push(argv, new RuntimeScalar("-"));
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
                    state.lastDiamondReader = state.currentReader;
                    return line;
                }

                // EOF for current file — close it before discarding it.  The
                // ARGV glob otherwise retains an exhausted but still-open
                // handle, which incorrectly wins over a later reopened STDIN
                // when a new empty-@ARGV diamond loop begins.
                state.accumulatedLineNumber = state.currentReader.currentLineNumber;
                state.lastDiamondReader = state.currentReader;
                state.currentReader.close();
                state.lastDiamondReader.currentLineNumber = state.accumulatedLineNumber;
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

        // Unlike the zero-argument diamond fallback, an explicit empty @ARGV
        // entry is a filename and must fail immediately.  Passing it to the
        // generic path opener resolves it as a directory on some platforms,
        // which turns `while (<>)` into a non-terminating read attempt.
        if (originalFileName.isEmpty()) {
            GlobalVariable.getGlobalVariable("main::!").set("No such file or directory");
            return dieCannotOpen(originalFileName);
        }

        if (!state.doubleDiamond && isForkLikeOpen(originalFileName)) {
            WarnDie.warn(new RuntimeScalar("Forked open '" + originalFileName
                    + "' not meaningful in <>"), new RuntimeScalar("\n"));
            return false;
        }

        // $ARGV shares its spelling with the ARGV typeglob, but it must remain
        // a scalar slot. Some script entry paths leave the scalar map pointing
        // at that glob; assigning through it performs a typeglob assignment
        // and corrupts subsequent diamond reads. Replace that placeholder with
        // the actual scalar value instead.
        RuntimeScalar argvName = GlobalVariable.getGlobalVariable("main::ARGV");
        if (argvName instanceof RuntimeGlob) {
            GlobalVariable.globalVariables.put("main::ARGV", new RuntimeScalar(originalFileName));
        } else {
            argvName.set(originalFileName);
        }

        // In ordinary diamond mode, '-' always denotes the current STDIN
        // handle.  It is not a path that in-place editing may rename; after a
        // nested local @ARGV loop, the outer reader can legitimately resume
        // from this synthetic fallback entry.
        if ("-".equals(originalFileName) && !state.doubleDiamond) {
            state.currentReader = getGlobalIO("main::STDIN").getRuntimeIO();
            state.stdinReader = state.currentReader;
            getGlobalIO("main::ARGV").set(state.currentReader);
            return state.currentReader != null;
        }

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

            if (extension == null || extension.isEmpty() || "*".equals(extension)) {
                // A lone '*' is Perl's extensionless form.  It must use a
                // temporary backup rather than substitute the source name
                // into the backup path and move a file onto itself.
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

            state.inPlaceOriginalPath = originalPath;

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
        if ("-".equals(readerPath) && (!state.doubleDiamond || state.argvWasInitiallyEmpty)) {
            // A diamond '-' is the current Perl STDIN handle, which can have
            // been reopened by the program; it is not necessarily System.in.
            // With <<>>, a synthetic '-' created solely because @ARGV was
            // empty retains that fallback; an explicit '-' argument remains a
            // literal filename as Perl requires.
            state.currentReader = getGlobalIO("main::STDIN").getRuntimeIO();
            state.stdinReader = state.currentReader;
        } else {
            // The explicit two-argument form intentionally does not apply
            // RuntimeIO.open(String)'s special empty-string and "-" handling:
            // double diamond treats both as ordinary filenames.
            state.currentReader = RuntimeIO.open(readerPath, "<");
        }
        if (state.currentReader == null) {
            return dieCannotOpen(originalFileName);
        }
        getGlobalIO("main::ARGV").set(state.currentReader);

        return state.currentReader != null;
    }

    private static boolean dieCannotOpen(String fileName) {
        RuntimeScalar error = GlobalVariable.getGlobalVariable("main::!");
        WarnDie.die(new RuntimeScalar("Can't open " + fileName + ": " + error),
                new RuntimeScalar(WarnDie.getPerlLocationFromStack()));
        return false; // unreachable; keeps the compiler's flow analysis explicit
    }

    private static boolean isForkLikeOpen(String fileName) {
        String normalized = fileName.replaceAll("\\s+", "");
        return "|-".equals(normalized) || "-|".equals(normalized);
    }

    /** Restore the handle selected before diamond in-place editing began. */
    private static void finishInPlaceEditing() {
        State state = state();
        if (state.selectedHandleBeforeInPlace != null) {
            RuntimeIO.setSelectedHandle(state.selectedHandleBeforeInPlace);
            state.selectedHandleBeforeInPlace = null;
        }
    }

    /**
     * Restore the current extensionless in-place source after an unhandled
     * exception.  Perl's -i uses a temporary backup in this form and leaves
     * the source intact when the implicit loop aborts before completion.
     */
    public static void abortInPlaceEditing() {
        State state = state();
        if (state.currentReader != null) {
            state.currentReader.close();
            state.currentReader = null;
        }
        if (state.currentWriter != null) {
            state.currentWriter.close();
            state.currentWriter = null;
        }
        if (state.tempFilePath != null && state.inPlaceOriginalPath != null) {
            try {
                Files.move(state.tempFilePath, state.inPlaceOriginalPath,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Preserve the original Perl exception; failed restoration is
                // reported by the already-active in-place edit diagnostic.
            }
        }
        state.tempFilePath = null;
        state.inPlaceOriginalPath = null;
        finishInPlaceEditing();
    }

    /** Reset only per-traversal state while retaining command-line -i settings. */
    private static void resetTraversalState(State state) {
        if (state.currentReader != null) {
            state.currentReader.close();
        }
        if (state.currentWriter != null) {
            state.currentWriter.close();
        }
        state.currentReader = null;
        state.currentWriter = null;
        state.eofReached = false;
        state.readingStarted = false;
        state.argvWasInitiallyEmpty = false;
        state.stdinReader = null;
        state.lastDiamondReader = null;
        state.accumulatedLineNumber = 0;
        state.tempFilePath = null;
        state.inPlaceOriginalPath = null;
        finishInPlaceEditing();
    }

    /** True when {@code handle} is the active diamond reader. */
    public static boolean isCurrentReader(RuntimeIO handle) {
        State state = state();
        return state.readingStarted && handle != null && handle == state.currentReader;
    }

    /** True once <> or <<>> has established its per-runtime traversal state. */
    public static boolean hasActiveTraversal() {
        return state().readingStarted;
    }

    /** True for a reader used by <> or <<>>, including its final EOF reader. */
    public static boolean isDiamondReader(RuntimeIO handle) {
        State state = state();
        return handle != null && (handle == state.currentReader || handle == state.lastDiamondReader);
    }

    /**
     * Implements argumentless eof() for the diamond handle. Perl considers it
     * true only when the current source is exhausted and no later @ARGV entry
     * remains; individual file boundaries are not final EOF.
     */
    public static RuntimeScalar eof() {
        State state = state();
        RuntimeArray argv = getGlobalArray("main::ARGV");
        RuntimeIO argvHandle = getGlobalIO("main::ARGV").getRuntimeIO();
        // A closed ARGV handle with queued entries is an explicit `close ARGV`
        // request.  With an empty @ARGV it is commonly just the exhausted
        // reader that diamond itself closed, so STDIN must take precedence.
        if (!argv.isEmpty() && argvHandle != null && argvHandle.ioHandle instanceof ClosedIOHandle) {
            return RuntimeScalarCache.scalarTrue;
        }

        // Pending @ARGV entries guarantee that argumentless eof() is false;
        // this is what lets eof() look ahead across diamond file boundaries.
        if (!argv.isEmpty()) {
            return RuntimeScalarCache.scalarFalse;
        }

        // With an empty @ARGV, diamond is defined in terms of the current
        // STDIN handle. It may have been reopened since a previous traversal.
        RuntimeIO stdin = getGlobalIO("main::STDIN").getRuntimeIO();
        if (stdin != null && !(stdin.ioHandle instanceof ClosedIOHandle)) {
            return stdin.eof();
        }
        return state.eofReached ? RuntimeScalarCache.scalarTrue : RuntimeScalarCache.scalarFalse;
    }
}
