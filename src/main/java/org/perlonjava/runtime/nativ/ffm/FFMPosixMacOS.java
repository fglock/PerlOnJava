package org.perlonjava.runtime.nativ.ffm;

/**
 * macOS implementation of FFM POSIX interface.
 * 
 * <p>macOS is POSIX-compliant, so most functions are inherited from the Linux
 * implementation. This class overrides functions where macOS differs:</p>
 * <ul>
 *   <li>Different struct layouts (stat, passwd have different field sizes/order)</li>
 *   <li>BSD-specific extensions (pw_change, pw_expire in passwd)</li>
 *   <li>Different library names (libc vs glibc)</li>
 * </ul>
 * 
 * <p><b>Note:</b> This is a stub implementation for Phase 1. Overrides will be
 * added in subsequent phases as needed.</p>
 */
public class FFMPosixMacOS extends FFMPosixLinux {
    
    /**
     * Creates a new macOS FFM POSIX implementation.
     */
    public FFMPosixMacOS() {
        super();
    }
    
    // ==================== macOS-Specific Overrides ====================
    
    // Most POSIX functions are the same on macOS and Linux.
    // Overrides will be added here when struct layouts differ.
    
    // TODO Phase 3: Override stat() with macOS-specific struct layout
    // macOS uses a different stat structure with:
    // - st_dev is int32_t (not dev_t = uint64_t)
    // - Different field ordering
    // - Additional fields like st_flags, st_gen
    
    // TODO Phase 3: Override getpwnam/getpwuid/getpwent for BSD passwd struct
    // BSD passwd has additional fields:
    // - pw_change (password change time)
    // - pw_class (user access class)
    // - pw_expire (account expiration time)
}
