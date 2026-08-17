package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.io.CustomFileChannel;
import org.perlonjava.runtime.io.SocketIO;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/** Portable Java implementation of the Sys::Sendfile API. */
public class SysSendfile extends PerlModuleBase {
    // Let the socket/channel report its natural partial-write boundary. A
    // 64 KiB userspace cap makes IO::Async reschedule thousands of Perl
    // callbacks for modest files and is much slower than native sendfile.
    private static final int CHUNK_SIZE = 1024 * 1024;

    public SysSendfile() {
        super("Sys::Sendfile", false);
    }

    public static void initialize() {
        SysSendfile module = new SysSendfile();
        try {
            module.registerMethod("sendfile", "sendfile", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static RuntimeList sendfile(RuntimeArray args, int ctx) {
        if (args.size() < 2) return new RuntimeList();
        RuntimeIO output = RuntimeIO.getRuntimeIO(args.get(0));
        RuntimeIO input = RuntimeIO.getRuntimeIO(args.get(1));
        if (output == null || input == null) {
            GlobalVariable.getGlobalVariable("main::!").set(9); // EBADF
            return new RuntimeList();
        }

        boolean hasCount = args.size() > 2 && args.get(2).getDefinedBoolean();
        long remaining = hasCount ? Math.max(0L, args.get(2).getLong()) : Long.MAX_VALUE;
        boolean hasOffset = args.size() > 3 && args.get(3).getDefinedBoolean();
        if (hasOffset && !input.seek(args.get(3).getLong()).getBoolean()) {
            return new RuntimeList();
        }
        if (remaining == 0) return new RuntimeScalar(0).getList();

        // FileChannel.transferTo maps closely to sendfile(2) and avoids
        // round-tripping binary data through Java and Perl string objects.
        if (input.ioHandle instanceof CustomFileChannel file
                && output.ioHandle instanceof SocketIO socket) {
            long position = input.tell().getLong();
            try {
                SocketChannel target = socket.channelForTransfer();
                if (target == null) return new RuntimeList();
                long transferred = file.transferTo(position, remaining, target);
                if (transferred > 0) {
                    // Preserve the existing bridge's file-position behavior
                    // for both implicit and explicit offsets.
                    input.seek(position + transferred);
                    return new RuntimeScalar(transferred).getList();
                }
                // transferTo may return zero for a supported, writable Unix
                // domain socket on macOS. Fall through to the portable path.
            } catch (IOException ignored) {
                // Some channel pairs do not support zero-copy transfer. The
                // buffered implementation below retains portable semantics.
            }
        }

        long writtenTotal = 0;
        while (remaining > 0) {
            int requested = (int) Math.min(CHUNK_SIZE, remaining);
            RuntimeScalar data = input.ioHandle.read(requested);
            if (!data.getDefinedBoolean()) return writtenTotal == 0
                    ? new RuntimeList() : new RuntimeScalar(writtenTotal).getList();
            String bytes = data.toString();
            if (bytes.isEmpty()) break;

            int written = output.ioHandle.writeSome(bytes);
            if (written < 0) return writtenTotal == 0
                    ? new RuntimeList() : new RuntimeScalar(writtenTotal).getList();
            writtenTotal += written;
            remaining -= written;
            if (written < bytes.length()) {
                // The input read advanced farther than the non-blocking output
                // accepted.  Rewind the unread tail for calls without an
                // explicit positional offset.
                if (!hasOffset) {
                    RuntimeScalar position = input.tell();
                    if (position.getDefinedBoolean()) {
                        input.seek(position.getLong() - (bytes.length() - written));
                    }
                }
                break;
            }
        }
        return new RuntimeScalar(writtenTotal).getList();
    }
}
