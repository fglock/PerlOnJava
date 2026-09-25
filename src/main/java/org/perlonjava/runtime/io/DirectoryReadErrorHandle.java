package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.nio.charset.Charset;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Filehandle returned by {@code open FH, '<', $directory}.
 *
 * <p>Unix permits opening a directory as a file and reports {@code EISDIR} at
 * read time. Windows' {@code FileChannel.open} instead rejects that open with
 * access denied. Keep Perl's observable filehandle behavior independent of
 * that host distinction: opening succeeds and attempts to use the handle as a
 * byte stream report {@code EISDIR}. This is deliberately not a dirhandle;
 * {@code opendir} remains the operation which enables {@code readdir}.</p>
 */
public final class DirectoryReadErrorHandle implements IOHandle {
    private boolean closed;

    @Override
    public ThreadInheritancePolicy threadInheritancePolicy() {
        return ThreadInheritancePolicy.SHARED_TRANSPORT;
    }

    private static RuntimeScalar eisdir() {
        getGlobalVariable("main::!").set(21);
        return scalarFalse;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, Charset charset) {
        getGlobalVariable("main::!").set(21);
        return scalarUndef;
    }

    @Override
    public RuntimeScalar write(String string) {
        return eisdir();
    }

    @Override
    public RuntimeScalar close() {
        closed = true;
        return scalarTrue;
    }

    @Override
    public RuntimeScalar flush() {
        return closed ? eisdir() : scalarTrue;
    }

    @Override
    public RuntimeScalar eof() {
        return scalarFalse;
    }

    @Override
    public RuntimeScalar tell() {
        eisdir();
        return new RuntimeScalar(-1);
    }

    @Override
    public RuntimeScalar seek(long pos, int whence) {
        return eisdir();
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return eisdir();
    }

    @Override
    public RuntimeScalar flock(int operation) {
        return eisdir();
    }
}
