package org.perlonjava.runtime.perlmodule;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java XS provider for the CPAN POSIX::strptime 0.13 wrapper. */
public class POSIXstrptime extends PerlModuleBase {

    public static final String XS_VERSION = "0.13";
    private static final int TM_SIZE = 56;
    private static final int UNSET = -1901;
    private static final long[] TM_FIELD_OFFSETS = {0, 4, 8, 12, 16, 20, 24, 28, 32};
    private static volatile MethodHandle strptimeHandle;

    public POSIXstrptime() {
        super("POSIX::strptime", false);
    }

    public static void initialize() {
        POSIXstrptime module = new POSIXstrptime();
        try {
            module.registerMethod("strptime", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing POSIX::strptime method", e);
        }
    }

    /** Return the nine struct tm fields produced by libc strptime(). */
    public static RuntimeList strptime(RuntimeArray args, int ctx) {
        if (!isSupportedPlatform()) {
            return WarnDie.die(
                    new RuntimeScalar("POSIX::strptime is not supported on this platform"),
                    new RuntimeScalar("\n")).getList();
        }

        String input = args.size() > 0 ? args.get(0).toString() : "";
        String format = args.size() > 1 ? args.get(1).toString() : "";
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tm = arena.allocate(TM_SIZE, 8);
            for (int i = 0; i < TM_FIELD_OFFSETS.length; i++) {
                tm.set(ValueLayout.JAVA_INT, TM_FIELD_OFFSETS[i], i == 6 || i == 7 ? 0 : UNSET);
            }
            MethodHandle handle = strptimeHandle();
            MemorySegment unparsed = (MemorySegment) handle.invokeExact(
                    arena.allocateFrom(input), arena.allocateFrom(format), tm);

            RuntimeArray result = new RuntimeArray();
            for (long offset : TM_FIELD_OFFSETS) {
                int value = tm.get(ValueLayout.JAVA_INT, offset);
                RuntimeArray.push(result, value == UNSET ? new RuntimeScalar() : new RuntimeScalar(value));
            }
            return result.getList();
        } catch (Throwable e) {
            return WarnDie.die(
                    new RuntimeScalar("POSIX::strptime failed: " + e.getMessage()),
                    new RuntimeScalar("\n")).getList();
        }
    }

    private static boolean isSupportedPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String archWidth = System.getProperty("sun.arch.data.model", "64");
        return (os.contains("linux") || os.contains("mac")) && "64".equals(archWidth);
    }

    private static MethodHandle strptimeHandle() {
        MethodHandle handle = strptimeHandle;
        if (handle != null) return handle;
        synchronized (POSIXstrptime.class) {
            handle = strptimeHandle;
            if (handle == null) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = linker.defaultLookup();
                handle = linker.downcallHandle(
                        lookup.find("strptime").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                strptimeHandle = handle;
            }
        }
        return handle;
    }
}
