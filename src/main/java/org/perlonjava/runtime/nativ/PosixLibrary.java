package org.perlonjava.runtime.nativ;

import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.perlonjava.runtime.nativ.ffm.FFMPosix;
import org.perlonjava.runtime.nativ.ffm.FFMPosixInterface;

/**
 * POSIX library wrapper providing native system call access.
 * 
 * <p>This class currently uses JNR-POSIX for native access. A migration to Java's
 * Foreign Function & Memory (FFM) API is in progress to eliminate sun.misc.Unsafe
 * warnings on Java 24+.</p>
 * 
 * <h2>FFM Migration</h2>
 * <p>To enable the FFM implementation (experimental), set the system property:</p>
 * <pre>{@code -Dperlonjava.ffm.enabled=true}</pre>
 * <p>Or set the environment variable:</p>
 * <pre>{@code PERLONJAVA_FFM_ENABLED=true}</pre>
 * 
 * <p>When FFM is enabled, use {@link #getFFM()} to access the FFM implementation.
 * Check {@link #isFFMEnabled()} before calling FFM methods.</p>
 * 
 * @see FFMPosix
 * @see FFMPosixInterface
 */
public class PosixLibrary {
    
    /**
     * JNR-POSIX instance for native POSIX operations.
     * This will be deprecated once FFM migration is complete.
     */
    public static final POSIX INSTANCE = POSIXFactory.getNativePOSIX();
    
    /**
     * Check if the FFM implementation is enabled.
     * @return true if FFM is enabled via system property or environment variable
     */
    public static boolean isFFMEnabled() {
        return FFMPosix.isEnabled();
    }
    
    /**
     * Get the FFM POSIX implementation.
     * Only call this when {@link #isFFMEnabled()} returns true.
     * @return Platform-specific FFM POSIX implementation
     */
    public static FFMPosixInterface getFFM() {
        return FFMPosix.get();
    }
}
