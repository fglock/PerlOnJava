package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.net.InetAddress;

/**
 * Sys::Hostname - Try every conceivable way to get hostname
 * <p>
 * This class provides the XS portion of Sys::Hostname, specifically the ghname()
 * function that returns the local hostname using Java's networking APIs.
 */
public class SysHostname extends PerlModuleBase {

    /**
     * Constructor for SysHostname.
     * Initializes the module with the name "Sys::Hostname".
     */
    public SysHostname() {
        super("Sys::Hostname");
    }

    /**
     * Static initializer to set up the Sys::Hostname module.
     */
    public static void initialize() {
        SysHostname sysHostname = new SysHostname();
        try {
            sysHostname.registerMethod("ghname", "");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Sys::Hostname method: " + e.getMessage());
        }
    }

    /**
     * Returns the local hostname.
     * <p>
     * This is the XS implementation that Sys::Hostname.pm tries to load first.
     * If successful, the hostname() function will use this instead of falling
     * back to syscall or external commands.
     *
     * @param args The arguments passed to the method (none expected).
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the hostname.
     */
    public static RuntimeList ghname(RuntimeArray args, int ctx) {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return new RuntimeScalar(hostname).getList();
        } catch (Exception e) {
            // Return undef on failure - Sys::Hostname.pm will try other methods
            return new RuntimeScalar().getList();
        }
    }
}
