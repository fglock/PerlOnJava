package org.perlonjava.terminal;

import org.perlonjava.runtime.RuntimeIO;
import java.io.IOException;
import java.util.Map;

/**
 * Interface for platform-specific terminal operations.
 */
public interface TerminalHandler {
    // Terminal mode constants
    int RESTORE_MODE = 0;
    int NORMAL_MODE = 1;
    int NOECHO_MODE = 2;
    int CBREAK_MODE = 3;
    int RAW_MODE = 4;
    int ULTRA_RAW_MODE = 5;

    /**
     * Sets the terminal mode for the given file handle.
     */
    void setTerminalMode(int mode, RuntimeIO fh);

    /**
     * Saves the original terminal settings for later restoration.
     */
    void saveOriginalSettings(RuntimeIO fh);

    /**
     * Restores the terminal to its original settings.
     */
    void restoreTerminal(RuntimeIO fh);

    /**
     * Reads a single character with optional timeout.
     * @param timeoutSeconds timeout in seconds (0 = blocking, <0 = non-blocking)
     * @return the character read, or 0 if timeout/EOF
     */
    char readSingleChar(double timeoutSeconds, RuntimeIO fh) throws IOException;

    /**
     * Reads a line with optional timeout.
     * @return the line read, or null if timeout/EOF
     */
    String readLineWithTimeout(double timeoutSeconds, RuntimeIO fh) throws IOException;

    /**
     * Gets the terminal size.
     * @return array of [width, height, xpixels, ypixels], or null if unsupported
     */
    int[] getTerminalSize(RuntimeIO fh);

    /**
     * Sets the terminal size.
     * @return true if successful
     */
    boolean setTerminalSize(int width, int height, int xpixels, int ypixels, RuntimeIO fh);

    /**
     * Gets the terminal speed.
     * @return array of [input_speed, output_speed], or null if unsupported
     */
    int[] getTerminalSpeed(RuntimeIO fh);

    /**
     * Gets control characters.
     * @return map of control character names to values
     */
    Map<String, String> getControlChars(RuntimeIO fh);

    /**
     * Sets control characters.
     */
    void setControlChars(Map<String, String> controlChars, RuntimeIO fh);

    /**
     * Cleanup resources when shutting down.
     */
    void cleanup();
}