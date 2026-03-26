package org.perlonjava.runtime.nativ.ffm;

/**
 * Factory for creating platform-specific FFM POSIX implementations.
 * 
 * <p>This class detects the current operating system and returns the appropriate
 * implementation of {@link FFMPosixInterface}.</p>
 * 
 * <h2>Feature Flag</h2>
 * <p>The FFM implementation is controlled by the system property {@code perlonjava.ffm.enabled}.
 * When set to {@code true}, the FFM implementation is used instead of JNR-POSIX.
 * Default is {@code false} (use JNR-POSIX).</p>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * // Check if FFM is enabled
 * if (FFMPosix.isEnabled()) {
 *     FFMPosixInterface posix = FFMPosix.get();
 *     int result = posix.kill(pid, signal);
 * }
 * }</pre>
 * 
 * <h2>Supported Platforms</h2>
 * <ul>
 *   <li>Linux (x86_64, aarch64)</li>
 *   <li>macOS (x86_64, aarch64/Apple Silicon)</li>
 *   <li>Windows (x86_64) - limited POSIX emulation</li>
 * </ul>
 */
public final class FFMPosix {
    
    /**
     * System property to enable FFM implementation.
     * Set to "true" to use FFM instead of JNR-POSIX.
     */
    public static final String FFM_ENABLED_PROPERTY = "perlonjava.ffm.enabled";
    
    /**
     * Environment variable alternative to enable FFM.
     */
    public static final String FFM_ENABLED_ENV = "PERLONJAVA_FFM_ENABLED";
    
    private static final FFMPosixInterface INSTANCE;
    private static final boolean ENABLED;
    private static final String OS_NAME;
    private static final String OS_ARCH;
    
    static {
        OS_NAME = System.getProperty("os.name", "").toLowerCase();
        OS_ARCH = System.getProperty("os.arch", "").toLowerCase();
        
        // FFM is now enabled by default (JNR-POSIX migration complete)
        // Can be disabled via system property or environment variable for testing
        String sysProp = System.getProperty(FFM_ENABLED_PROPERTY);
        String envVar = System.getenv(FFM_ENABLED_ENV);
        // Default to true unless explicitly set to false
        ENABLED = !"false".equalsIgnoreCase(sysProp) && 
                  (sysProp != null || !"false".equalsIgnoreCase(envVar));
        
        INSTANCE = createInstance();
    }
    
    private FFMPosix() {
        // Prevent instantiation
    }
    
    /**
     * Check if FFM implementation is enabled.
     * @return true if FFM is enabled via system property or environment variable
     */
    public static boolean isEnabled() {
        return ENABLED;
    }
    
    /**
     * Get the platform-specific FFM POSIX implementation.
     * @return FFMPosixInterface implementation for the current platform
     */
    public static FFMPosixInterface get() {
        return INSTANCE;
    }
    
    /**
     * Get the detected operating system name.
     * @return Operating system name (lowercase)
     */
    public static String getOsName() {
        return OS_NAME;
    }
    
    /**
     * Get the detected CPU architecture.
     * @return CPU architecture (lowercase)
     */
    public static String getOsArch() {
        return OS_ARCH;
    }
    
    /**
     * Check if running on Windows.
     * @return true if Windows
     */
    public static boolean isWindows() {
        return OS_NAME.contains("win");
    }
    
    /**
     * Check if running on macOS.
     * @return true if macOS
     */
    public static boolean isMacOS() {
        return OS_NAME.contains("mac");
    }
    
    /**
     * Check if running on Linux.
     * @return true if Linux
     */
    public static boolean isLinux() {
        return OS_NAME.contains("linux");
    }
    
    private static FFMPosixInterface createInstance() {
        if (isWindows()) {
            return new FFMPosixWindows();
        } else if (isMacOS()) {
            return new FFMPosixMacOS();
        } else {
            // Default to Linux for Unix-like systems
            return new FFMPosixLinux();
        }
    }
}
