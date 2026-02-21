package org.perlonjava.terminal;

import org.perlonjava.runtime.runtimetypes.PerlCompilerException;
import org.perlonjava.runtime.runtimetypes.RuntimeIO;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Windows-specific terminal handler implementation.
 */
public class WindowsTerminalHandler implements TerminalHandler {
    private final Console console = System.console();
    private final Map<RuntimeIO, Integer> currentModes = new HashMap<>();

    @Override
    public void setTerminalMode(int mode, RuntimeIO fh) {
        if (mode == RESTORE_MODE) {
            restoreTerminal(fh);
            return;
        }

        currentModes.put(fh, mode);

        // Windows terminal mode setting is limited without JNI
        // We can use Console for some functionality
        if (console != null) {
            switch (mode) {
                case NORMAL_MODE:
                    // Echo on, buffered - default console mode
                    break;
                case NOECHO_MODE:
                    // Echo off - we can simulate this for password reading
                    break;
                case CBREAK_MODE:
                case RAW_MODE:
                case ULTRA_RAW_MODE:
                    // These require native code for proper implementation
                    break;
            }
        }
    }

    @Override
    public void saveOriginalSettings(RuntimeIO fh) {
        // Windows doesn't need to save settings as we don't modify them
    }

    @Override
    public void restoreTerminal(RuntimeIO fh) {
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

        InputStream inputStream = System.in;

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
        // As documented: "This call is currently not available under Windows."
        throw new PerlCompilerException("ReadLine is not available under Windows");
    }

    @Override
    public int[] getTerminalSize(RuntimeIO fh) {
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

    @Override
    public boolean setTerminalSize(int width, int height, int xpixels, int ypixels, RuntimeIO fh) {
        // Not supported on Windows
        return false;
    }

    @Override
    public int[] getTerminalSpeed(RuntimeIO fh) {
        // As documented: "No speeds are reported under Windows."
        return null;
    }

    @Override
    public Map<String, String> getControlChars(RuntimeIO fh) {
        // As documented: "This call does nothing under Windows."
        return new HashMap<>();
    }

    @Override
    public void setControlChars(Map<String, String> controlChars, RuntimeIO fh) {
        // As documented: "This call does nothing under Windows."
    }

    @Override
    public void cleanup() {
        // Restore all file handles
        for (RuntimeIO fh : new ArrayList<>(currentModes.keySet())) {
            restoreTerminal(fh);
        }
    }
}