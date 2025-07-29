package org.perlonjava.nativ;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.*;

/**
 * Extended Windows API interface for operations not in jna-platform
 */
public interface WindowsLibrary extends StdCallLibrary {
    WindowsLibrary INSTANCE = Native.load("kernel32", WindowsLibrary.class);

    // Additional Windows-specific functions not in Kernel32
    boolean CreateProcessW(
        WString applicationName,
        WString commandLine,
        WinBase.SECURITY_ATTRIBUTES processAttributes,
        WinBase.SECURITY_ATTRIBUTES threadAttributes,
        boolean inheritHandles,
        int creationFlags,
        Pointer environment,
        WString currentDirectory,
        WinBase.STARTUPINFO startupInfo,
        WinBase.PROCESS_INFORMATION processInformation
    );
}