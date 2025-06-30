package org.perlonjava.terminal;

import org.perlonjava.runtime.RuntimeIO;
import java.io.*;
import java.util.*;

/**
 * macOS-specific terminal handler implementation.
 */
public class MacOSTerminalHandler extends UnixTerminalHandler {

    public MacOSTerminalHandler() {
        // macOS requires full path to stty
        this.sttyCmd = "/bin/stty";
    }

    @Override
    public void setTerminalMode(int mode, RuntimeIO fh) {
        // Save original settings on first use
        if (mode != RESTORE_MODE && !originalSettings.containsKey(fh)) {
            saveOriginalSettings(fh);
        }

        if (mode == RESTORE_MODE) {
            if (originalSettings.containsKey(fh)) {
                restoreTerminal(fh);
                originalSettings.remove(fh);
            }
            return;
        }

        currentModes.put(fh, mode);
        setUnixTerminalMode(mode, fh);
    }

    @Override
    protected void setUnixTerminalMode(int mode, RuntimeIO fh) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(sttyCmd);

            switch (mode) {
                case NORMAL_MODE:
                    cmd.addAll(Arrays.asList("sane", "echo", "icanon", "isig", "ixon"));
                    break;
                case NOECHO_MODE:
                    cmd.addAll(Arrays.asList("sane", "-echo", "icanon", "isig", "ixon"));
                    break;
                case CBREAK_MODE:
                    // macOS specific cbreak mode settings
                    cmd.addAll(Arrays.asList("-icanon", "echo", "min", "1", "time", "0"));
                    break;
                case RAW_MODE:
                    cmd.addAll(Arrays.asList("raw", "-echo"));
                    break;
                case ULTRA_RAW_MODE:
                    // macOS doesn't support -iexten flag
                    cmd.addAll(Arrays.asList("raw", "-echo", "-isig", "cs8", "-parenb"));
                    break;
                default:
                    return;
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process proc = pb.start();
            int exitCode = proc.waitFor();

            if (exitCode != 0) {
                System.err.println("Warning: stty exited with code " + exitCode);
            }

            // For cbreak/raw modes, create a new unbuffered input stream
            if (mode == CBREAK_MODE || mode == RAW_MODE || mode == ULTRA_RAW_MODE) {
                // Force creation of a new FileInputStream from stdin file descriptor
                // This bypasses any buffering that System.in might have
                rawInputStream = new FileInputStream(FileDescriptor.in);
            } else {
                if (rawInputStream != null && rawInputStream != System.in) {
                    try {
                        rawInputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                rawInputStream = null;
            }

        } catch (Exception e) {
            System.err.println("Error setting terminal mode: " + e.getMessage());
        }
    }
}