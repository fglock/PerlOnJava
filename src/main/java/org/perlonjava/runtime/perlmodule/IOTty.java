package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.io.FileDescriptorTable;
import org.perlonjava.runtime.io.NativeFdIOHandle;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;
import org.perlonjava.runtime.nativ.ffm.FFMPosixLinux;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Java implementation of IO::Tty XS functions.
 *
 * <p>Provides PTY allocation, tty device operations, and terminal window size
 * packing/unpacking. Loaded via {@code XSLoader::load('IO::Tty')}.</p>
 *
 * <p>Registered methods span two Perl packages:</p>
 * <ul>
 *   <li>{@code IO::Pty::pty_allocate} — allocate master+slave pty pair</li>
 *   <li>{@code IO::Tty::_open_tty} — open a tty device by name</li>
 *   <li>{@code IO::Tty::ttyname} — get device name for a tty fd</li>
 *   <li>{@code IO::Tty::pack_winsize} — pack struct winsize</li>
 *   <li>{@code IO::Tty::unpack_winsize} — unpack struct winsize</li>
 * </ul>
 */
public class IOTty extends PerlModuleBase {

    public IOTty() {
        super("IO::Tty", false);
    }

    public static void initialize() {
        IOTty module = new IOTty();
        try {
            // IO::Tty methods
            module.registerMethod("ttyname", null);
            module.registerMethod("pack_winsize", null);
            module.registerMethod("unpack_winsize", null);
            module.registerMethod("_open_tty", "openTty", null);

            // IO::Pty methods (registered in IO::Pty namespace)
            module.registerMethod("IO::Pty::pty_allocate", "ptyAllocate", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing IOTty method: " + e.getMessage());
        }

        // Set $IO::Tty::CONFIG to describe platform capabilities
        String config = buildConfigString();
        GlobalVariable.getGlobalVariable("IO::Tty::CONFIG").set(config);

        // Register terminal constants in IO::Tty::Constant namespace
        registerConstants();
    }

    /**
     * Build the $IO::Tty::CONFIG string that describes platform pty capabilities.
     * This mimics what the upstream Makefile.PL/xssubs.c generates.
     */
    private static String buildConfigString() {
        StringBuilder sb = new StringBuilder();
        sb.append("-DHAVE_POSIX_OPENPT ");
        sb.append("-DHAVE_PTSNAME ");
        sb.append("-DHAVE_GRANTPT ");
        sb.append("-DHAVE_UNLOCKPT ");
        sb.append("-DHAVE_TTYNAME ");

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            sb.append("-DHAVE_DEV_PTMX ");
        } else if (osName.contains("linux")) {
            sb.append("-DHAVE_DEV_PTMX ");
            sb.append("-DHAVE_DEV_PTS ");
        }

        return sb.toString().trim();
    }

    /**
     * Register terminal ioctl constants in IO::Tty::Constant namespace.
     * Only the constants actually used by IO::Pty methods are registered here.
     * More can be added incrementally.
     */
    private static void registerConstants() {
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");

        // ioctl request codes
        setConstant("TIOCGWINSZ", isMac ? 0x40087468L : 0x5413L);
        setConstant("TIOCSWINSZ", isMac ? 0x80087467L : 0x5414L);
        setConstant("TIOCSCTTY", isMac ? 0x20007461L : 0x540EL);
        setConstant("TIOCNOTTY", isMac ? 0x20007471L : 0x5422L);

        // macOS also has TCSETCTTY
        if (isMac) {
            setConstant("TCSETCTTY", 0x20007461L);  // Same as TIOCSCTTY on macOS
        }

        // Open flags
        setConstant("O_RDWR", 0x0002);
        setConstant("O_NOCTTY", isMac ? 0x20000 : 0x0100);

        // Termios action constants
        setConstant("TCSANOW", 0);
        setConstant("TCSADRAIN", 1);
        setConstant("TCSAFLUSH", 2);

        // Common termios c_iflag bits
        setConstant("IGNBRK", 0x00000001);
        setConstant("BRKINT", 0x00000002);
        setConstant("IGNPAR", 0x00000004);
        setConstant("PARMRK", 0x00000008);
        setConstant("INPCK", 0x00000010);
        setConstant("ISTRIP", 0x00000020);
        setConstant("INLCR", 0x00000040);
        setConstant("IGNCR", 0x00000080);
        setConstant("ICRNL", 0x00000100);
        setConstant("IXON", isMac ? 0x00000200 : 0x00000400);
        setConstant("IXOFF", isMac ? 0x00000400 : 0x00001000);
        setConstant("IXANY", isMac ? 0x00000800 : 0x00000800);
        setConstant("IMAXBEL", isMac ? 0x00002000 : 0x00002000);

        // Common termios c_oflag bits
        setConstant("OPOST", 0x00000001);

        // Common termios c_cflag bits
        setConstant("CS8", isMac ? 0x00000300 : 0x00000030);
        setConstant("CREAD", isMac ? 0x00000800 : 0x00000080);
        setConstant("PARENB", isMac ? 0x00001000 : 0x00000100);
        setConstant("HUPCL", isMac ? 0x00004000 : 0x00000400);
        setConstant("CLOCAL", isMac ? 0x00008000 : 0x00000800);

        // Common termios c_lflag bits
        setConstant("ECHO", isMac ? 0x00000008 : 0x00000008);
        setConstant("ECHOE", isMac ? 0x00000002 : 0x00000010);
        setConstant("ECHOK", isMac ? 0x00000004 : 0x00000020);
        setConstant("ECHONL", isMac ? 0x00000010 : 0x00000040);
        setConstant("ICANON", isMac ? 0x00000100 : 0x00000002);
        setConstant("IEXTEN", isMac ? 0x00000400 : 0x00008000);
        setConstant("ISIG", isMac ? 0x00000080 : 0x00000001);
        setConstant("NOFLSH", isMac ? 0x80000000L : 0x00000080);
        setConstant("TOSTOP", isMac ? 0x00400000 : 0x00000100);

        // VMIN and VTIME indices in c_cc array
        setConstant("VMIN", isMac ? 16 : 6);
        setConstant("VTIME", isMac ? 17 : 5);
    }

    /**
     * Set a constant in IO::Tty::Constant namespace as a subroutine.
     */
    private static void setConstant(String name, long value) {
        GlobalVariable.getGlobalVariable("IO::Tty::Constant::" + name).set(value);
    }

    // ==================== XS Function Implementations ====================

    /**
     * IO::Pty::pty_allocate() — Allocate a pty master+slave pair.
     *
     * Returns ($masterFd, $slaveFd, $slaveName) in list context.
     */
    public static RuntimeList ptyAllocate(RuntimeArray args, int ctx) {
        FFMPosixInterface ffm = FFMPosix.get();

        int flags = FFMPosixLinux.O_RDWR | FFMPosixLinux.O_NOCTTY;

        // 1. Open master pty
        int masterFd = ffm.posix_openpt(flags);
        if (masterFd == -1) {
            throw new PerlCompilerException("posix_openpt failed: " + ffm.strerror(ffm.errno()));
        }

        try {
            // 2. Grant access to slave
            if (ffm.grantpt(masterFd) == -1) {
                ffm.nativeClose(masterFd);
                throw new PerlCompilerException("grantpt failed: " + ffm.strerror(ffm.errno()));
            }

            // 3. Unlock slave
            if (ffm.unlockpt(masterFd) == -1) {
                ffm.nativeClose(masterFd);
                throw new PerlCompilerException("unlockpt failed: " + ffm.strerror(ffm.errno()));
            }

            // 4. Get slave name
            String slaveName = ffm.ptsname(masterFd);
            if (slaveName == null) {
                ffm.nativeClose(masterFd);
                throw new PerlCompilerException("ptsname failed: " + ffm.strerror(ffm.errno()));
            }

            // 5. Open slave
            int slaveFd = ffm.nativeOpen(slaveName, flags);
            if (slaveFd == -1) {
                ffm.nativeClose(masterFd);
                throw new PerlCompilerException("open slave failed: " + ffm.strerror(ffm.errno()));
            }

            // 6. Make fds safe (>= 3) to avoid collision with stdin/stdout/stderr
            masterFd = makeSafeFd(ffm, masterFd);
            slaveFd = makeSafeFd(ffm, slaveFd);

            // 7. Register both fds in the I/O system
            NativeFdIOHandle masterHandle = new NativeFdIOHandle(masterFd);
            masterHandle.registerInIOSystem();

            NativeFdIOHandle slaveHandle = new NativeFdIOHandle(slaveFd);
            slaveHandle.registerInIOSystem();

            // 8. Return ($masterFd, $slaveFd, $slaveName)
            RuntimeArray result = new RuntimeArray();
            result.push(new RuntimeScalar(masterFd));
            result.push(new RuntimeScalar(slaveFd));
            result.push(new RuntimeScalar(slaveName));
            return result.getList();

        } catch (PerlCompilerException e) {
            throw e;
        } catch (Exception e) {
            ffm.nativeClose(masterFd);
            throw new PerlCompilerException("pty_allocate failed: " + e.getMessage());
        }
    }

    /**
     * Ensure fd >= 3 to avoid collision with stdin/stdout/stderr.
     * Uses fcntl(F_DUPFD, 3) to get a new fd >= 3, then closes the old one.
     */
    private static int makeSafeFd(FFMPosixInterface ffm, int fd) {
        if (fd >= 3) return fd;
        int newFd = ffm.fcntlDupFd(fd, 3);
        if (newFd == -1) {
            // Can't dup, just use the original
            return fd;
        }
        ffm.nativeClose(fd);
        return newFd;
    }

    /**
     * IO::Tty::_open_tty($name) — Open a tty device by name.
     *
     * Returns the file descriptor, or dies on error.
     */
    public static RuntimeList openTty(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Usage: IO::Tty::_open_tty(TTYNAME)");
        }
        String name = args.get(0).toString();

        FFMPosixInterface ffm = FFMPosix.get();
        int flags = FFMPosixLinux.O_RDWR | FFMPosixLinux.O_NOCTTY;

        int fd = ffm.nativeOpen(name, flags);
        if (fd == -1) {
            throw new PerlCompilerException("open_tty: open('" + name + "') failed: " + ffm.strerror(ffm.errno()));
        }

        fd = makeSafeFd(ffm, fd);

        // Register in I/O system
        NativeFdIOHandle handle = new NativeFdIOHandle(fd);
        handle.registerInIOSystem();

        return new RuntimeScalar(fd).getList();
    }

    /**
     * IO::Tty::ttyname($fh) — Get the terminal device name for a filehandle.
     *
     * Returns the device name string, or undef if not a terminal.
     */
    public static RuntimeList ttyname(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Usage: IO::Tty::ttyname(FILEHANDLE)");
        }

        // Get the fd from the filehandle argument
        RuntimeScalar fhArg = args.get(0);
        int fd;

        // Try to get fd: could be a number or a filehandle object
        RuntimeIO rio = null;
        try {
            rio = fhArg.getRuntimeIO();
        } catch (Exception e) {
            // Not a filehandle, try as integer
        }

        if (rio != null) {
            RuntimeScalar filenoResult = rio.fileno();
            fd = filenoResult.getInt();
        } else {
            fd = fhArg.getInt();
        }

        FFMPosixInterface ffm = FFMPosix.get();
        String name = ffm.ttyname(fd);

        if (name == null) {
            return new RuntimeScalar().getList();  // undef
        }
        return new RuntimeScalar(name).getList();
    }

    /**
     * IO::Tty::pack_winsize($row, $col, $xpixel, $ypixel) — Pack struct winsize.
     *
     * Returns an 8-byte binary string (4 unsigned shorts in native byte order).
     */
    public static RuntimeList pack_winsize(RuntimeArray args, int ctx) {
        int row = args.size() > 0 ? args.get(0).getInt() : 0;
        int col = args.size() > 1 ? args.get(1).getInt() : 0;
        int xpixel = args.size() > 2 ? args.get(2).getInt() : 0;
        int ypixel = args.size() > 3 ? args.get(3).getInt() : 0;

        // struct winsize: 4 unsigned shorts (ws_row, ws_col, ws_xpixel, ws_ypixel)
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        buf.putShort((short) row);
        buf.putShort((short) col);
        buf.putShort((short) xpixel);
        buf.putShort((short) ypixel);

        // Convert to ISO-8859-1 string (binary safe)
        byte[] bytes = buf.array();
        return new RuntimeScalar(new String(bytes, StandardCharsets.ISO_8859_1)).getList();
    }

    /**
     * IO::Tty::unpack_winsize($buf) — Unpack struct winsize.
     *
     * Returns ($row, $col, $xpixel, $ypixel).
     */
    public static RuntimeList unpack_winsize(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new PerlCompilerException("Usage: IO::Tty::unpack_winsize(WINSIZE_BUF)");
        }
        String packed = args.get(0).toString();
        byte[] bytes = packed.getBytes(StandardCharsets.ISO_8859_1);

        if (bytes.length < 8) {
            throw new PerlCompilerException("unpack_winsize: buffer too short (need 8 bytes, got " + bytes.length + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        int row = buf.getShort() & 0xFFFF;
        int col = buf.getShort() & 0xFFFF;
        int xpixel = buf.getShort() & 0xFFFF;
        int ypixel = buf.getShort() & 0xFFFF;

        RuntimeArray result = new RuntimeArray();
        result.push(new RuntimeScalar(row));
        result.push(new RuntimeScalar(col));
        result.push(new RuntimeScalar(xpixel));
        result.push(new RuntimeScalar(ypixel));
        return result.getList();
    }
}
