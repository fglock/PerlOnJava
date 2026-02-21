package org.perlonjava.terminal;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Base class for Unix-like terminal handlers (Linux and macOS).
 */
public abstract class UnixTerminalHandler implements TerminalHandler {
    // Control character name mappings
    private static final Map<String, String> STTY_TO_READKEY = new HashMap<>();
    private static final Map<String, String> READKEY_TO_STTY = new HashMap<>();

    static {
        // Initialize mappings
        STTY_TO_READKEY.put("INTR", "INTERRUPT");
        STTY_TO_READKEY.put("QUIT", "QUIT");
        STTY_TO_READKEY.put("ERASE", "ERASE");
        STTY_TO_READKEY.put("KILL", "KILL");
        STTY_TO_READKEY.put("EOF", "EOF");
        STTY_TO_READKEY.put("EOL", "EOL");
        STTY_TO_READKEY.put("EOL2", "EOL2");
        STTY_TO_READKEY.put("START", "START");
        STTY_TO_READKEY.put("STOP", "STOP");
        STTY_TO_READKEY.put("SUSP", "SUSPEND");
        STTY_TO_READKEY.put("DSUSP", "DSUSPEND");
        STTY_TO_READKEY.put("RPRNT", "REPRINT");
        STTY_TO_READKEY.put("WERASE", "ERASEWORD");
        STTY_TO_READKEY.put("LNEXT", "QUOTENEXT");
        STTY_TO_READKEY.put("DISCARD", "DISCARD");
        STTY_TO_READKEY.put("MIN", "MIN");
        STTY_TO_READKEY.put("TIME", "TIME");
        STTY_TO_READKEY.put("STATUS", "STATUS");
        STTY_TO_READKEY.put("SWITCH", "SWITCH");

        // Create reverse mapping
        for (Map.Entry<String, String> entry : STTY_TO_READKEY.entrySet()) {
            READKEY_TO_STTY.put(entry.getValue(), entry.getKey().toLowerCase());
        }
    }

    protected Map<RuntimeIO, String> originalSettings = new HashMap<>();
    protected Map<RuntimeIO, Integer> currentModes = new HashMap<>();
    protected InputStream rawInputStream;
    protected String sttyCmd;

    @Override
    public void saveOriginalSettings(RuntimeIO fh) {
        try {
            ProcessBuilder pb = new ProcessBuilder(sttyCmd, "-g");
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String settings = reader.readLine();
            if (settings != null && !settings.isEmpty()) {
                originalSettings.put(fh, settings);
            }
            reader.close();
            proc.waitFor();
        } catch (Exception e) {
            // Ignore errors - we'll work without saving settings
        }
    }

    @Override
    public void restoreTerminal(RuntimeIO fh) {
        if (originalSettings.containsKey(fh)) {
            try {
                String settings = originalSettings.get(fh);
                ProcessBuilder pb = new ProcessBuilder(sttyCmd, settings);
                pb.inheritIO();
                Process proc = pb.start();
                proc.waitFor();

                // Close the raw input stream if it exists
                if (rawInputStream != null && rawInputStream != System.in) {
                    try {
                        rawInputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                    rawInputStream = null;
                }
            } catch (Exception e) {
                // Ignore errors
            }
        }
        currentModes.remove(fh);
    }

    @Override
    public char readSingleChar(double timeoutSeconds, RuntimeIO fh) throws IOException {
        if (fh != RuntimeIO.stdin) {
            RuntimeScalar result = fh.ioHandle.read(1);
            if (!result.getDefinedBoolean()) {
                return 0;
            }
            String str = result.toString();
            return str.isEmpty() ? 0 : str.charAt(0);
        }

        InputStream inputStream = (rawInputStream != null) ? rawInputStream : System.in;

        if (timeoutSeconds == 0) {
            // Blocking read
            int ch = inputStream.read();
            return ch == -1 ? 0 : (char) ch;
        } else if (timeoutSeconds < 0) {
            // Non-blocking read
            if (inputStream.available() == 0) {
                return 0;
            }
            int ch = inputStream.read();
            return ch == -1 ? 0 : (char) ch;
        } else {
            // Timed read
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Integer> future = executor.submit(() -> {
                try {
                    return inputStream.read();
                } catch (IOException e) {
                    return -1;
                }
            });

            try {
                int ch = future.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
                return ch == -1 ? 0 : (char) ch;
            } catch (TimeoutException e) {
                future.cancel(true);
                return 0;
            } catch (Exception e) {
                return 0;
            } finally {
                executor.shutdown();
            }
        }
    }

    @Override
    public String readLineWithTimeout(double timeoutSeconds, RuntimeIO fh) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                fh == RuntimeIO.stdin ? System.in : new ByteArrayInputStream(new byte[0])));

        if (timeoutSeconds < 0) {
            // Non-blocking read
            if (!reader.ready()) {
                return null;
            }
            return reader.readLine();
        } else if (timeoutSeconds == 0) {
            // Normal blocking read
            return reader.readLine();
        } else {
            // Timed read
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    return null;
                }
            });

            try {
                return future.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                executor.shutdown();
            }
        }
    }

    @Override
    public int[] getTerminalSize(RuntimeIO fh) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{sttyCmd, "size"});
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

    @Override
    public boolean setTerminalSize(int width, int height, int xpixels, int ypixels, RuntimeIO fh) {
        // This is rarely supported
        return false;
    }

    @Override
    public int[] getTerminalSpeed(RuntimeIO fh) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{sttyCmd, "-a"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("speed") && line.contains("baud")) {
                    String[] parts = line.split("[;\\s]+");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("speed") && i + 1 < parts.length) {
                            try {
                                int speed = Integer.parseInt(parts[i + 1]);
                                return new int[]{speed, speed};
                            } catch (NumberFormatException e) {
                                // Continue searching
                            }
                        }
                    }
                }
            }
            reader.close();
            proc.waitFor();
        } catch (Exception e) {
            // Ignore errors
        }
        return null;
    }

    @Override
    public Map<String, String> getControlChars(RuntimeIO fh) {
        Map<String, String> controlChars = new HashMap<>();

        try {
            Process proc = Runtime.getRuntime().exec(new String[]{sttyCmd, "-a"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                // Parse stty -a output for control characters
                // Format: "intr = ^C; quit = ^\; erase = ^?; kill = ^U;"
                String[] parts = line.split(";");
                for (String part : parts) {
                    String[] kvPair = part.trim().split("=");
                    if (kvPair.length == 2) {
                        String key = kvPair[0].trim().toUpperCase();
                        String value = kvPair[1].trim();

                        // Convert ^X notation to actual character
                        if (value.startsWith("^") && value.length() == 2) {
                            char ch = value.charAt(1);
                            if (ch == '?') {
                                value = "\u007F"; // DEL
                            } else {
                                value = String.valueOf((char) (ch - '@'));
                            }
                        }

                        // Map stty names to Term::ReadKey names
                        String mappedKey = STTY_TO_READKEY.getOrDefault(key, key);
                        controlChars.put(mappedKey, value);
                    }
                }
            }
            reader.close();
            proc.waitFor();
        } catch (Exception e) {
            // Return default control characters
            controlChars.put("INTERRUPT", "\u0003");  // Ctrl-C
            controlChars.put("QUIT", "\u001C");       // Ctrl-\
            controlChars.put("ERASE", "\u007F");      // DEL
            controlChars.put("KILL", "\u0015");       // Ctrl-U
            controlChars.put("EOF", "\u0004");        // Ctrl-D
            controlChars.put("EOL", "\n");            // Newline
            controlChars.put("START", "\u0011");      // Ctrl-Q
            controlChars.put("STOP", "\u0013");       // Ctrl-S
            controlChars.put("SUSPEND", "\u001A");    // Ctrl-Z
        }

        return controlChars;
    }

    @Override
    public void setControlChars(Map<String, String> controlChars, RuntimeIO fh) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(sttyCmd);

            for (Map.Entry<String, String> entry : controlChars.entrySet()) {
                String sttyName = READKEY_TO_STTY.get(entry.getKey());
                if (sttyName != null) {
                    String value = entry.getValue();
                    if (value.length() == 1) {
                        char ch = value.charAt(0);
                        if (ch < 32) {
                            // Convert control character to ^X notation
                            value = "^" + (char) (ch + '@');
                        } else if (ch == 127) {
                            value = "^?";
                        }
                    }
                    cmd.add(sttyName);
                    cmd.add(value);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process proc = pb.start();
            proc.waitFor();
        } catch (Exception e) {
            throw new PerlCompilerException("Failed to set control characters: " + e.getMessage());
        }
    }

    @Override
    public void cleanup() {
        // Restore all file handles
        for (RuntimeIO fh : new ArrayList<>(currentModes.keySet())) {
            restoreTerminal(fh);
        }
    }

    /**
     * Platform-specific method to set terminal mode using stty commands.
     * Must be implemented by Linux and macOS handlers.
     */
    protected abstract void setUnixTerminalMode(int mode, RuntimeIO fh);
}
