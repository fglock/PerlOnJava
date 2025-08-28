package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

/**
 * Java::System - Perl module for accessing IO::Handle internals
 */
public class IOHandle extends PerlModuleBase {

    public IOHandle() {
        super("IO::Handle", false);
    }

    public static void initialize() {
        IOHandle ioHandle = new IOHandle();

        try {
            // Register all methods
            ioHandle.registerMethod("ungetc", null);
            ioHandle.registerMethod("_error", "*");
            ioHandle.registerMethod("_clearerr", "*");
            ioHandle.registerMethod("_sync", "*");
            ioHandle.registerMethod("_blocking", "*;$");
            ioHandle.registerMethod("_setbuf", "*$");
            ioHandle.registerMethod("_setvbuf", "*$$$");
            ioHandle.registerMethod("_untaint", "*");
            ioHandle.registerMethod("_set_input_line_number", "*$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IOHandle method: " + e.getMessage());
        }
    }

    /**
     * Call internal ungetc
     */
    public static RuntimeList ungetc(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("ungetc requires 2 arguments");
        }
        RuntimeIO fh = args.get(0).getRuntimeIO();
        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("can't ungetc on tied handle");
        }
        RuntimeScalar arg1 = args.get(1);
        int c = arg1.toString().codePointAt(0);
        fh.ioHandle.ungetc(c);
        return arg1.getList();
    }

    /**
     * Check if handle has experienced errors
     */
    public static RuntimeList _error(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _error");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            return new RuntimeList(new RuntimeScalar(1)); // Invalid handle has error
        }

        // Check if there's an error in $!
        String error = GlobalVariable.getGlobalVariable("main::!").toString();
        return new RuntimeList(new RuntimeScalar(error.isEmpty() ? 0 : 1));
    }

    /**
     * Clear error indicator
     */
    public static RuntimeList _clearerr(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _clearerr");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            return new RuntimeList(new RuntimeScalar(-1));
        }

        // Clear $!
        GlobalVariable.getGlobalVariable("main::!").set("");
        return new RuntimeList(new RuntimeScalar(0));
    }

    /**
     * Sync file's in-memory state with physical medium
     */
    public static RuntimeList _sync(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _sync");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            return new RuntimeList();
        }

        try {
            // First flush any buffered data
            fh.flush();

            // For file handles, we've done what we can with flush()
            // The JVM doesn't provide a portable way to force fsync
            // Most implementations will sync on flush anyway

            // Note: If you need true fsync behavior, you would need to:
            // 1. Add a sync() method to the IOHandle interface
            // 2. Implement it in CustomFileChannel using FileChannel.force(true)
            // 3. Call it here: fh.ioHandle.sync();

            return new RuntimeList(new RuntimeScalar("0 but true"));
        } catch (Exception e) {
            RuntimeIO.handleIOError("sync failed: " + e.getMessage());
            return new RuntimeList();
        }
    }

    /**
     * Get/set blocking mode
     */
    public static RuntimeList _blocking(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for _blocking");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            RuntimeIO.handleIOError("Bad filehandle");
            return new RuntimeList();
        }

        // Get current blocking status (always true in JVM)
        boolean currentBlocking = true;

        if (args.size() == 2) {
            // Setting blocking mode
            boolean newBlocking = args.get(1).getBoolean();
            if (!newBlocking) {
                // Non-blocking I/O is not easily supported in JVM
                RuntimeIO.handleIOError("Non-blocking I/O not supported");
                return new RuntimeList();
            }
        }

        return new RuntimeList(new RuntimeScalar(currentBlocking ? 1 : 0));
    }

    /**
     * Set buffer (not implemented in JVM)
     */
    public static RuntimeList _setbuf(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _setbuf");
        }

        RuntimeIO.handleIOError("setbuf not implemented");
        return new RuntimeList();
    }

    /**
     * Set buffer with type (not implemented in JVM)
     */
    public static RuntimeList _setvbuf(RuntimeArray args, int ctx) {
        if (args.size() != 4) {
            throw new IllegalStateException("Bad number of arguments for _setvbuf");
        }

        RuntimeIO.handleIOError("setvbuf not implemented");
        return new RuntimeList();
    }

    /**
     * Mark handle as taint-clean
     */
    public static RuntimeList _untaint(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _untaint");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            return new RuntimeList(new RuntimeScalar(-1));
        }

        // In JVM, we don't have real taint checking, so just return success
        return new RuntimeList(new RuntimeScalar(0));
    }

    /**
     * Set input line number for handle
     */
    public static RuntimeList _set_input_line_number(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _set_input_line_number");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        int lineNum = args.get(1).getInt();

        if (fh != null) {
            fh.currentLineNumber = lineNum;
        }

        return new RuntimeList();
    }
}