package org.perlonjava.runtime.nativ;

import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;

/**
 * POSIX library wrapper providing native system call access.
 * 
 * <p>This class uses Java's Foreign Function & Memory (FFM) API for native access,
 * which was finalized in Java 22. This approach eliminates the sun.misc.Unsafe
 * warnings that appeared on Java 24+ with the previous JNR-POSIX dependency.</p>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * FFMPosixInterface posix = PosixLibrary.getFFM();
 * int result = posix.kill(pid, signal);
 * }</pre>
 * 
 * @see FFMPosix
 * @see FFMPosixInterface
 */
public class PosixLibrary {
    
    /**
     * Check if the FFM implementation is enabled.
     * FFM is now enabled by default.
     * @return true if FFM is enabled
     */
    public static boolean isFFMEnabled() {
        return FFMPosix.isEnabled();
    }
    
    /**
     * Get the FFM POSIX implementation.
     * @return Platform-specific FFM POSIX implementation
     */
    public static FFMPosixInterface getFFM() {
        return FFMPosix.get();
    }
}
