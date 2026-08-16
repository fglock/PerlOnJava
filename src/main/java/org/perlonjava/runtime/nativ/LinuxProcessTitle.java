package org.perlonjava.runtime.nativ;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Updates the Linux argv area used by {@code ps -f} after assignment to Perl's {@code $0}. */
public final class LinuxProcessTitle {
    private static final boolean LINUX = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("linux");
    private static final Path SELF_STAT = Path.of("/proc/self/stat");

    private LinuxProcessTitle() {}

    /**
     * Replace the process argv bytes, truncating to the space supplied by the launcher.
     * Failure is deliberately non-fatal: Perl documents process-title mutation as
     * operating-system dependent, and hardened Linux configurations may deny access.
     */
    public static synchronized void set(String title) {
        if (!LINUX || title == null) return;
        try {
            long[] bounds = argvBounds(Files.readString(SELF_STAT));
            long capacity = bounds[1] - bounds[0];
            if (capacity <= 0 || capacity > Integer.MAX_VALUE) return;

            MemorySegment argv = MemorySegment.ofAddress(bounds[0]).reinterpret(capacity);
            argv.fill((byte) 0);
            byte[] bytes = title.getBytes(StandardCharsets.UTF_8);
            int length = (int) Math.min(bytes.length, capacity - 1);
            if (length > 0) {
                argv.asSlice(0, length).copyFrom(MemorySegment.ofArray(bytes).asSlice(0, length));
            }
        } catch (IOException | RuntimeException ignored) {
            // Keep ordinary scalar assignment working when native title mutation is unavailable.
        }
    }

    static long[] argvBounds(String stat) {
        // /proc/PID/stat field 2 is parenthesized and may itself contain spaces.
        // After its closing ')', token zero is field 3; arg_start/end are 48/49.
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) {
            throw new IllegalArgumentException("Malformed /proc/self/stat");
        }
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        if (fields.length <= 46) throw new IllegalArgumentException("Incomplete /proc/self/stat");
        return new long[] {
                Long.parseUnsignedLong(fields[45]),
                Long.parseUnsignedLong(fields[46])
        };
    }
}
