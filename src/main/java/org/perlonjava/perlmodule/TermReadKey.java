package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import org.perlonjava.terminal.LinuxTerminalHandler;
import org.perlonjava.terminal.MacOSTerminalHandler;
import org.perlonjava.terminal.TerminalHandler;
import org.perlonjava.terminal.WindowsTerminalHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.RuntimeScalarType.INTEGER;

/**
 * The TermReadKey class provides functionalities similar to the Perl Term::ReadKey module.
 * This is a wrapper that delegates to platform-specific implementations.
 */
public class TermReadKey extends PerlModuleBase {

    private static final Map<String, Integer> terminalModes = new HashMap<>();
    private static final TerminalHandler handler;

    static {
        // Initialize terminal mode mappings
        terminalModes.put("restore", TerminalHandler.RESTORE_MODE);
        terminalModes.put("normal", TerminalHandler.NORMAL_MODE);
        terminalModes.put("noecho", TerminalHandler.NOECHO_MODE);
        terminalModes.put("cbreak", TerminalHandler.CBREAK_MODE);
        terminalModes.put("raw", TerminalHandler.RAW_MODE);
        terminalModes.put("ultra-raw", TerminalHandler.ULTRA_RAW_MODE);

        // Detect platform and create appropriate handler
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            handler = new WindowsTerminalHandler();
        } else if (osName.contains("mac")) {
            handler = new MacOSTerminalHandler();
        } else {
            handler = new LinuxTerminalHandler();
        }

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (handler != null) {
                handler.cleanup();
            }
        }));
    }

    /**
     * Constructor initializes the module.
     */
    public TermReadKey() {
        super("Term::ReadKey");
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        TermReadKey readkey = new TermReadKey();

        // Initialize as an Exporter module
        readkey.initializeExporter();

        // Define EXPORT array with commonly exported functions
        readkey.defineExport("EXPORT",
                "ReadMode", "ReadKey", "ReadLine", "GetTerminalSize",
                "SetTerminalSize", "GetSpeed", "GetControlChars", "SetControlChars");

        // Define EXPORT_OK array with all exportable functions
        readkey.defineExport("EXPORT_OK");

        try {
            readkey.registerMethod("ReadMode", "readMode", "$;$");
            readkey.registerMethod("ReadKey", "readKey", ";$");
            readkey.registerMethod("ReadLine", "readLine", ";$");
            readkey.registerMethod("GetTerminalSize", "getTerminalSize", ";$");
            readkey.registerMethod("SetTerminalSize", "setTerminalSize", "$;$");
            readkey.registerMethod("GetSpeed", "getSpeed", ";$");
            readkey.registerMethod("GetControlChars", "getControlChars", ";$");
            readkey.registerMethod("SetControlChars", "setControlChars", "\\@;$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Term::ReadKey method: " + e.getMessage());
        }
    }

    /**
     * Sets the terminal input mode.
     * ReadMode(mode, [filehandle])
     */
    public static RuntimeList readMode(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            return new RuntimeList(scalarUndef);
        }

        RuntimeScalar modeArg = args.get(0);
        int mode;

        if (modeArg.type == INTEGER) {
            mode = modeArg.getInt();
        } else {
            String modeStr = modeArg.toString().toLowerCase();
            mode = terminalModes.getOrDefault(modeStr, TerminalHandler.NORMAL_MODE);
        }

        // Get filehandle (defaults to STDIN)
        RuntimeIO fh = RuntimeIO.stdin;
        if (args.size() > 1) {
            RuntimeScalar fileHandle = args.get(1);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        handler.setTerminalMode(mode, fh);
        return new RuntimeList();
    }

    /**
     * Reads a single character from the keyboard.
     * ReadKey([timeout], [filehandle])
     */
    public static RuntimeList readKey(RuntimeArray args, int ctx) {
        double timeout = 0; // Default is blocking
        RuntimeIO fh = RuntimeIO.stdin;

        if (!args.isEmpty() && args.get(0).getDefinedBoolean()) {
            timeout = args.get(0).getDouble();
        }

        if (args.size() > 1) {
            RuntimeScalar fileHandle = args.get(1);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        // Flush output handles before blocking on input
        RuntimeIO.flushFileHandles();

        try {
            char ch = handler.readSingleChar(timeout, fh);
            if (ch == 0) {
                return new RuntimeList(scalarUndef);
            }
            return new RuntimeList(new RuntimeScalar(String.valueOf(ch)));
        } catch (IOException e) {
            return new RuntimeList(scalarUndef);
        }
    }

    /**
     * Reads a line of input with timeout.
     * ReadLine([timeout], [filehandle])
     */
    public static RuntimeList readLine(RuntimeArray args, int ctx) {
        double timeout = 0; // Default is blocking
        RuntimeIO fh = RuntimeIO.stdin;

        if (!args.isEmpty() && args.get(0).getDefinedBoolean()) {
            timeout = args.get(0).getDouble();
        }

        if (args.size() > 1) {
            RuntimeScalar fileHandle = args.get(1);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        // Flush output handles before blocking on input
        RuntimeIO.flushFileHandles();

        try {
            String line = handler.readLineWithTimeout(timeout, fh);
            if (line == null) {
                return new RuntimeList(scalarUndef);
            }
            return new RuntimeList(new RuntimeScalar(line));
        } catch (IOException e) {
            return new RuntimeList(scalarUndef);
        }
    }

    /**
     * Gets the terminal size.
     * GetTerminalSize([filehandle])
     * Returns (width, height, xpixels, ypixels)
     */
    public static RuntimeList getTerminalSize(RuntimeArray args, int ctx) {
        RuntimeIO fh = RuntimeIO.stdout;
        if (!args.isEmpty()) {
            RuntimeScalar fileHandle = args.get(0);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        int[] size = handler.getTerminalSize(fh);
        if (size == null) {
            return new RuntimeList(); // Empty array for unsupported
        }

        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(size[0])); // width (columns)
        result.add(new RuntimeScalar(size[1])); // height (rows)
        result.add(new RuntimeScalar(size[2])); // xpixels
        result.add(new RuntimeScalar(size[3])); // ypixels

        return result;
    }

    /**
     * Sets the terminal size.
     * SetTerminalSize(width, height, xpixels, ypixels, [filehandle])
     */
    public static RuntimeList setTerminalSize(RuntimeArray args, int ctx) {
        if (args.size() < 4) {
            return new RuntimeList(new RuntimeScalar(-1));
        }

        int width = args.get(0).getInt();
        int height = args.get(1).getInt();
        int xpixels = args.get(2).getInt();
        int ypixels = args.get(3).getInt();

        RuntimeIO fh = RuntimeIO.stdout;
        if (args.size() > 4) {
            RuntimeScalar fileHandle = args.get(4);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        boolean success = handler.setTerminalSize(width, height, xpixels, ypixels, fh);
        return new RuntimeList(new RuntimeScalar(success ? 0 : -1));
    }

    /**
     * Gets the terminal speed.
     * GetSpeed([filehandle])
     * Returns (input_speed, output_speed)
     */
    public static RuntimeList getSpeed(RuntimeArray args, int ctx) {
        RuntimeIO fh = RuntimeIO.stdin;
        if (!args.isEmpty()) {
            RuntimeScalar fileHandle = args.get(0);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        int[] speeds = handler.getTerminalSpeed(fh);
        if (speeds == null) {
            return new RuntimeList(); // Empty array for unsupported
        }

        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(speeds[0])); // input speed
        result.add(new RuntimeScalar(speeds[1])); // output speed

        return result;
    }

    /**
     * Gets the terminal control characters.
     * GetControlChars([filehandle])
     * Returns an array containing key/value pairs suitable for a hash
     */
    public static RuntimeList getControlChars(RuntimeArray args, int ctx) {
        RuntimeIO fh = RuntimeIO.stdin;
        if (!args.isEmpty()) {
            RuntimeScalar fileHandle = args.get(0);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        Map<String, String> controlChars = handler.getControlChars(fh);
        RuntimeList result = new RuntimeList();

        // Return as array of key/value pairs suitable for hash
        for (Map.Entry<String, String> entry : controlChars.entrySet()) {
            result.add(new RuntimeScalar(entry.getKey()));
            result.add(new RuntimeScalar(entry.getValue()));
        }

        return result;
    }

    /**
     * Sets control characters.
     * SetControlChars(array_ref, [filehandle])
     */
    public static RuntimeList setControlChars(RuntimeArray args, int ctx) {
        if (args.isEmpty()) {
            throw new PerlCompilerException("SetControlChars requires array reference");
        }

        RuntimeScalar arrayRef = args.get(0);
        if (arrayRef.type != RuntimeScalarType.ARRAYREFERENCE) {
            throw new PerlCompilerException("First argument to SetControlChars must be array reference");
        }

        RuntimeArray controlArray = (RuntimeArray) arrayRef.value;

        RuntimeIO fh = RuntimeIO.stdin;
        if (args.size() > 1) {
            RuntimeScalar fileHandle = args.get(1);
            fh = RuntimeIO.getRuntimeIO(fileHandle);
        }

        // Convert array to map
        Map<String, String> controlChars = new HashMap<>();
        for (int i = 0; i < controlArray.size() - 1; i += 2) {
            String key = controlArray.get(i).toString();
            RuntimeScalar valueScalar = controlArray.get(i + 1);
            String value;

            if (valueScalar.type == INTEGER) {
                int charCode = valueScalar.getInt();
                if (charCode < 0 || charCode > 255) {
                    throw new PerlCompilerException("Control character value must be 0-255");
                }
                value = String.valueOf((char) charCode);
            } else {
                value = valueScalar.toString();
                if (value.length() != 1) {
                    throw new PerlCompilerException("Control character must be single character");
                }
            }

            controlChars.put(key, value);
        }

        handler.setControlChars(controlChars, fh);
        return new RuntimeList();
    }
}
