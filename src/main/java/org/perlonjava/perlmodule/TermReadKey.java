package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.RuntimeScalarType.INTEGER;

/**
 * The TermReadKey class provides functionalities similar to the Perl Term::ReadKey module.
 * This implementation provides cross-platform terminal control and keyboard input functions.
 */
public class TermReadKey extends PerlModuleBase {

    // Terminal mode constants
    public static final int NORMAL_MODE = 0;
    public static final int CBREAK_MODE = 1;
    public static final int RAW_MODE = 2;
    public static final int ULTRA_RAW_MODE = 3;

    private static Map<String, Integer> terminalModes = new HashMap<>();
    private static boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    private static Process sttyProcess = null;
    private static String originalSttySettings = null;

    static {
        terminalModes.put("restore", NORMAL_MODE);
        terminalModes.put("normal", NORMAL_MODE);
        terminalModes.put("cbreak", CBREAK_MODE);
        terminalModes.put("raw", RAW_MODE);
        terminalModes.put("ultra-raw", ULTRA_RAW_MODE);
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
                "ReadMode", "ReadKey", "ReadLine", "GetTerminalSize");

        // Define EXPORT_OK array with all exportable functions
        readkey.defineExport("EXPORT_OK",
                "ReadMode", "ReadKey", "ReadLine", "GetTerminalSize", 
                "SetTerminalSize", "GetSpeed", "GetControlChars");

        try {
            readkey.registerMethod("ReadMode", "readMode", "$;$");
            readkey.registerMethod("ReadKey", "readKey", ";$$");
            readkey.registerMethod("ReadLine", "readLine", ";$$");
            readkey.registerMethod("GetTerminalSize", "getTerminalSize", ";$");
            readkey.registerMethod("SetTerminalSize", "setTerminalSize", "$$$$;$");
            readkey.registerMethod("GetSpeed", "getSpeed", ";$");
            readkey.registerMethod("GetControlChars", "getControlChars", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Term::ReadKey method: " + e.getMessage());
        }

        // Save original terminal settings on startup
        saveOriginalTerminalSettings();
        
        // Register shutdown hook to restore terminal
        Runtime.getRuntime().addShutdownHook(new Thread(TermReadKey::restoreTerminalMode));
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
            mode = terminalModes.getOrDefault(modeStr, NORMAL_MODE);
        }

        // Optional filehandle parameter (currently ignored, defaults to STDIN)
        // FileHandle fh = args.size() > 1 ? args.get(1) : null;

        setTerminalMode(mode);
        return new RuntimeList();
    }

    /**
     * Reads a single character from the keyboard.
     * ReadKey([timeout], [filehandle])
     */
    public static RuntimeList readKey(RuntimeArray args, int ctx) {
        int timeout = -1; // -1 means no timeout
        
        if (!args.isEmpty() && args.get(0).getDefinedBoolean()) {
            timeout = args.get(0).getInt();
        }

        // Optional filehandle parameter (currently ignored, defaults to STDIN)
        // FileHandle fh = args.size() > 1 ? args.get(1) : null;

        try {
            char ch = readSingleChar(timeout);
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
        int timeout = -1;
        
        if (!args.isEmpty() && args.get(0).getDefinedBoolean()) {
            timeout = args.get(0).getInt();
        }

        try {
            String line = readLineWithTimeout(timeout);
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
        // Optional filehandle parameter (currently ignored)
        
        int[] size = getTermSize();
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
            return new RuntimeList(scalarFalse);
        }

        int width = args.get(0).getInt();
        int height = args.get(1).getInt();
        int xpixels = args.get(2).getInt();
        int ypixels = args.get(3).getInt();

        // This is typically not supported on most systems
        // Return false to indicate operation not supported
        return new RuntimeList(scalarFalse);
    }

    /**
     * Gets the terminal speed.
     * GetSpeed([filehandle])
     * Returns (input_speed, output_speed)
     */
    public static RuntimeList getSpeed(RuntimeArray args, int ctx) {
        // Most modern systems use the same speed for input and output
        // Return typical modern terminal speed
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar(38400)); // input speed
        result.add(new RuntimeScalar(38400)); // output speed
        
        return result;
    }

    /**
     * Gets the terminal control characters.
     * GetControlChars([filehandle])
     * Returns hash reference with control character mappings
     */
    public static RuntimeList getControlChars(RuntimeArray args, int ctx) {
        // Return typical Unix control characters
        Map<String, String> controlChars = new HashMap<>();
        controlChars.put("INTERRUPT", "\u0003");  // Ctrl-C
        controlChars.put("QUIT", "\u001C");       // Ctrl-\
        controlChars.put("ERASE", "\u0008");      // Backspace
        controlChars.put("KILL", "\u0015");       // Ctrl-U
        controlChars.put("EOF", "\u0004");        // Ctrl-D
        controlChars.put("EOL", "\n");            // Newline
        controlChars.put("START", "\u0011");      // Ctrl-Q
        controlChars.put("STOP", "\u0013");       // Ctrl-S
        controlChars.put("SUSPEND", "\u001A");    // Ctrl-Z

        // Convert to RuntimeHash equivalent
        RuntimeScalar hashRef = new RuntimeScalar(controlChars);
        return new RuntimeList(hashRef);
    }

    // Private helper methods

    private static void saveOriginalTerminalSettings() {
        if (!isWindows) {
            try {
                Process proc = Runtime.getRuntime().exec(new String[]{"stty", "-g"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                originalSttySettings = reader.readLine();
                proc.waitFor();
                reader.close();
            } catch (Exception e) {
                // Ignore errors - we'll work without saving settings
            }
        }
    }

    private static void setTerminalMode(int mode) {
        if (isWindows) {
            setWindowsTerminalMode(mode);
        } else {
            setUnixTerminalMode(mode);
        }
    }

    private static void setWindowsTerminalMode(int mode) {
        // Windows terminal mode setting would require JNI or external tools
        // For now, we'll use a simplified approach
        try {
            switch (mode) {
                case NORMAL_MODE:
                    // Restore normal mode - no special handling needed
                    break;
                case CBREAK_MODE:
                case RAW_MODE:
                case ULTRA_RAW_MODE:
                    // These modes would require platform-specific implementation
                    break;
            }
        } catch (Exception e) {
            // Ignore errors in terminal mode setting
        }
    }

    private static void setUnixTerminalMode(int mode) {
        try {
            String[] cmd;
            switch (mode) {
                case NORMAL_MODE:
                    if (originalSttySettings != null) {
                        cmd = new String[]{"stty", originalSttySettings};
                    } else {
                        cmd = new String[]{"stty", "sane"};
                    }
                    break;
                case CBREAK_MODE:
                    cmd = new String[]{"stty", "cbreak", "-echo"};
                    break;
                case RAW_MODE:
                    cmd = new String[]{"stty", "raw", "-echo"};
                    break;
                case ULTRA_RAW_MODE:
                    cmd = new String[]{"stty", "raw", "-echo", "-isig", "-iexten"};
                    break;
                default:
                    return;
            }

            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
        } catch (Exception e) {
            // Ignore errors in terminal mode setting
        }
    }

    private static void restoreTerminalMode() {
        setTerminalMode(NORMAL_MODE);
    }

    private static char readSingleChar(int timeoutSeconds) throws IOException {
        if (isWindows) {
            return readSingleCharWindows(timeoutSeconds);
        } else {
            return readSingleCharUnix(timeoutSeconds);
        }
    }

    private static char readSingleCharWindows(int timeoutSeconds) throws IOException {
        // Windows implementation - simplified approach
        // In a full implementation, this would use JNI to call Windows console APIs
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        if (timeoutSeconds > 0) {
            // Simple timeout implementation - not perfect but functional
            long startTime = System.currentTimeMillis();
            while (!reader.ready()) {
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                    return 0; // timeout
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return 0;
                }
            }
        }
        
        int ch = reader.read();
        return ch == -1 ? 0 : (char) ch;
    }

    private static char readSingleCharUnix(int timeoutSeconds) throws IOException {
        // Unix implementation using System.in
        InputStream in = System.in;
        
        if (timeoutSeconds > 0) {
            long startTime = System.currentTimeMillis();
            while (in.available() == 0) {
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                    return 0; // timeout
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return 0;
                }
            }
        }
        
        int ch = in.read();
        return ch == -1 ? 0 : (char) ch;
    }

    private static String readLineWithTimeout(int timeoutSeconds) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        if (timeoutSeconds > 0) {
            long startTime = System.currentTimeMillis();
            while (!reader.ready()) {
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                    return null; // timeout
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }
        
        return reader.readLine();
    }

    private static int[] getTermSize() {
        int[] size = {80, 24, 0, 0}; // default: 80x24, no pixel info
        
        if (isWindows) {
            size = getTermSizeWindows();
        } else {
            size = getTermSizeUnix();
        }
        
        return size;
    }

    private static int[] getTermSizeWindows() {
        // Windows terminal size detection
        // This would typically require JNI or external commands
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"cmd", "/c", "mode", "con"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            int cols = 80, rows = 24;
            
            while ((line = reader.readLine()) != null) {
                if (line.contains("Columns:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        cols = Integer.parseInt(parts[1].trim());
                    }
                } else if (line.contains("Lines:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        rows = Integer.parseInt(parts[1].trim());
                    }
                }
            }
            reader.close();
            proc.waitFor();
            
            return new int[]{cols, rows, 0, 0};
        } catch (Exception e) {
            return new int[]{80, 24, 0, 0};
        }
    }

    private static int[] getTermSizeUnix() {
        // Unix terminal size detection using stty
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"stty", "size"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line = reader.readLine();
            reader.close();
            proc.waitFor();
            
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    int rows = Integer.parseInt(parts[0]);
                    int cols = Integer.parseInt(parts[1]);
                    return new int[]{cols, rows, 0, 0};
                }
            }
        } catch (Exception e) {
            // Try environment variables as fallback
            try {
                String cols = System.getenv("COLUMNS");
                String rows = System.getenv("LINES");
                if (cols != null && rows != null) {
                    return new int[]{Integer.parseInt(cols), Integer.parseInt(rows), 0, 0};
                }
            } catch (Exception e2) {
                // Ignore
            }
        }
        
        return new int[]{80, 24, 0, 0};
    }
}

