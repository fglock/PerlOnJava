package org.perlonjava.runtime.io;

import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * Stand-in for a descriptor that is closed or was never opened.
 *
 * <p>Every operation fails the way Perl fails on a closed descriptor: the
 * operator returns false and {@code $!} is set to {@code EBADF} ("Bad file
 * descriptor"). Setting the real errno matters because wrapper modules read
 * {@code $!} numerically — {@code IO::Die}, for example, reports
 * {@code OS_ERROR} from {@code $!} and {@code EXTENDED_OS_ERROR} from
 * {@code $^E}, both of which were empty while these errors carried only a
 * free-form message with errno 0.
 */
public class ClosedIOHandle implements IOHandle {

    /** errno EBADF — the same value on every platform PerlOnJava targets. */
    private static final int EBADF = 9;

    @Override
    public ThreadInheritancePolicy threadInheritancePolicy() {
        return ThreadInheritancePolicy.CLOSED;
    }

    @Override
    public boolean canRead() {
        return false;
    }

    @Override
    public boolean canWrite() {
        return false;
    }

    @Override
    public RuntimeScalar write(String string) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar close() {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar flush() {
        // return RuntimeIO.handleIOError("Cannot flush a closed handle.");
        return scalarFalse;

    }

    @Override
    public RuntimeScalar fileno() {
        // Perl 5: fileno() on a closed handle returns undef (not false)
        return scalarUndef;
    }

    @Override
    public RuntimeScalar eof() {
        // In Perl 5, eof() on a closed handle returns true (1)
        return scalarTrue;
    }

    @Override
    public RuntimeScalar doRead(int maxBytes, java.nio.charset.Charset charset) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar tell() {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar bind(String address, int port) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar connect(String address, int port) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar listen(int backlog) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar accept() {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar seek(long pos) {
        return RuntimeIO.handleIOError(EBADF);
    }

    /**
     * seek() with an explicit whence. Overridden as well as the one-argument
     * form: the interface default reported "Seek operation is not supported"
     * with errno 0, which is what {@code seek($closed, 0, 0)} used to leave
     * in {@code $!}.
     */
    @Override
    public RuntimeScalar seek(long pos, int whence) {
        clearUngetBuffer();
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar truncate(long length) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar sysread(int length) {
        return RuntimeIO.handleIOError(EBADF);
    }

    @Override
    public RuntimeScalar syswrite(String data) {
        return RuntimeIO.handleIOError(EBADF);
    }
}
