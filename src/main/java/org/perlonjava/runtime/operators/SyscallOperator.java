package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.RuntimeBase;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.net.InetAddress;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;

/**
 * Implementation of Perl's syscall operator for PerlOnJava.
 * 
 * Note: syscall numbers are platform-specific. This implementation provides
 * Java-based emulation for commonly used syscalls.
 */
public class SyscallOperator {

    // Common syscall numbers (platform-dependent, these are Linux x86_64 values)
    // On macOS, gethostname is a library function, not a syscall
    private static final int SYS_GETHOSTNAME_LINUX = 74;  // Linux
    
    /**
     * Implements Perl's syscall() function.
     * 
     * syscall NUMBER, LIST
     * 
     * Calls the system call specified by NUMBER with the arguments in LIST.
     * Returns the result of the syscall, or -1 on error with $! set.
     *
     * @param ctx  Context
     * @param args Arguments: syscall number followed by arguments
     * @return Result of the syscall
     */
    public static RuntimeScalar syscall(int ctx, RuntimeBase... args) {
        if (args.length < 1) {
            getGlobalVariable("main::!").set(22); // EINVAL
            return new RuntimeScalar(-1);
        }

        int syscallNum = args[0].scalar().getInt();
        
        // Handle known syscalls with Java implementations
        switch (syscallNum) {
            case SYS_GETHOSTNAME_LINUX:
                return sysGethostname(args);
            default:
                // For unknown syscalls, check if it might be gethostname on another platform
                // by looking at the argument pattern (buffer, length)
                if (args.length >= 3) {
                    // Heuristic: if we have 3 args and arg[2] is a reasonable buffer size,
                    // assume it might be gethostname
                    int possibleLen = args[2].scalar().getInt();
                    if (possibleLen >= 64 && possibleLen <= 256) {
                        return sysGethostname(args);
                    }
                }
                
                // Unsupported syscall
                getGlobalVariable("main::!").set(38); // ENOSYS - Function not implemented
                return new RuntimeScalar(-1);
        }
    }
    
    /**
     * Emulates gethostname syscall.
     * 
     * gethostname(char *name, size_t len)
     * 
     * Perl usage: syscall(&SYS_gethostname, $host, 65)
     * where $host is pre-allocated with "\0" x 65
     */
    private static RuntimeScalar sysGethostname(RuntimeBase... args) {
        if (args.length < 3) {
            getGlobalVariable("main::!").set(22); // EINVAL
            return new RuntimeScalar(-1);
        }
        
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            int maxLen = args[2].scalar().getInt();
            
            // Truncate hostname if necessary (like real gethostname)
            if (hostname.length() >= maxLen) {
                hostname = hostname.substring(0, maxLen - 1);
            }
            
            // Pad with nulls to expected length (Perl pre-allocates the buffer)
            StringBuilder result = new StringBuilder(hostname);
            while (result.length() < maxLen) {
                result.append('\0');
            }
            
            // Modify the buffer argument in place
            // args[1] should be the scalar that receives the hostname
            if (args[1] instanceof RuntimeScalar) {
                ((RuntimeScalar) args[1]).set(result.toString());
            } else {
                args[1].scalar().set(result.toString());
            }
            
            return new RuntimeScalar(0); // Success
            
        } catch (Exception e) {
            getGlobalVariable("main::!").set(1); // EPERM or general error
            return new RuntimeScalar(-1);
        }
    }
}
