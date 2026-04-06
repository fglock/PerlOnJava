package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

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
            // Register all methods - use null prototypes to match Perl 5's XS subs
            // (Perl 5's IO.xs subs have no prototypes; adding prototypes would force
            // scalar context on array args like @_, breaking callers)
            ioHandle.registerMethod("ungetc", null);
            ioHandle.registerMethod("_error", null);
            ioHandle.registerMethod("_clearerr", null);
            ioHandle.registerMethod("_sync", null);
            ioHandle.registerMethod("_blocking", null);
            ioHandle.registerMethod("_setbuf", null);
            ioHandle.registerMethod("_setvbuf", null);
            ioHandle.registerMethod("_untaint", null);
            ioHandle.registerMethod("_set_input_line_number", null);
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
        // int c = arg1.toString().codePointAt(0);
        int c = arg1.getInt();
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
     * Sync file's in-memory state with physical medium (fsync).
     *
     * <p>This synchronizes a file's in-memory state with that on the physical medium.
     * It operates at the file descriptor level (like sysread, sysseek), not at the
     * perlio API level. Data buffered at the perlio level must be flushed first
     * with flush().</p>
     *
     * <p>Returns "0 but true" on success, undef on error or invalid handle.</p>
     *
     * @see <a href="https://perldoc.perl.org/IO::Handle#$io-%3Esync">IO::Handle->sync</a>
     */
    public static RuntimeList _sync(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _sync");
        }

        RuntimeIO fh = RuntimeIO.getRuntimeIO(args.get(0));
        if (fh == null || fh.ioHandle == null) {
            return new RuntimeList(); // undef for invalid handle
        }

        try {
            // First flush any perlio-level buffered data
            fh.flush();

            // Now call sync() to force fsync on the file descriptor
            RuntimeScalar result = fh.ioHandle.sync();
            
            if (result.getBoolean()) {
                // Return "0 but true" on success per Perl convention
                return new RuntimeList(new RuntimeScalar("0 but true"));
            } else {
                return new RuntimeList(); // undef on error
            }
        } catch (Exception e) {
            RuntimeIO.handleIOError("sync failed: " + e.getMessage());
            return new RuntimeList(); // undef on error
        }
    }

    /**
     * Get/set blocking mode.
     * For sockets with NIO channels, this actually configures non-blocking I/O.
     * For other handles, non-blocking mode is not supported.
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

        // Get current blocking status
        boolean currentBlocking = true;
        if (fh.ioHandle instanceof org.perlonjava.runtime.io.SocketIO socketIO) {
            currentBlocking = socketIO.isBlocking();
        } else if (fh.ioHandle instanceof org.perlonjava.runtime.io.InternalPipeHandle pipeHandle) {
            currentBlocking = pipeHandle.isBlocking();
        }

        if (args.size() == 2) {
            boolean newBlocking = args.get(1).getBoolean();
            if (fh.ioHandle instanceof org.perlonjava.runtime.io.SocketIO socketIO) {
                // For sockets, actually set blocking mode via NIO channel
                socketIO.setBlocking(newBlocking);
            } else if (fh.ioHandle instanceof org.perlonjava.runtime.io.InternalPipeHandle pipeHandle) {
                // For internal pipes, set blocking mode
                pipeHandle.setBlocking(newBlocking);
            } else if (fh.ioHandle instanceof org.perlonjava.runtime.io.DupIOHandle dupHandle) {
                // For dup'd handles, unwrap and set on the delegate
                org.perlonjava.runtime.io.IOHandle delegate = dupHandle.getDelegate();
                if (delegate instanceof org.perlonjava.runtime.io.InternalPipeHandle ph) {
                    ph.setBlocking(newBlocking);
                } else if (delegate instanceof org.perlonjava.runtime.io.SocketIO si) {
                    si.setBlocking(newBlocking);
                }
            } else if (!newBlocking) {
                // Non-blocking I/O not supported for other handle types
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