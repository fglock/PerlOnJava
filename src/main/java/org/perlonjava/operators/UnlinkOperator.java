package org.perlonjava.operators;

import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import org.perlonjava.nativ.NativeUtils;
import org.perlonjava.nativ.PosixLibrary;
import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.getScalarBoolean;

/**
 * Implementation of Perl's unlink operator for PerlOnJava
 */
public class UnlinkOperator {

    /**
     * Deletes a list of files specified in the RuntimeBase arguments.
     * Follows Perl's unlink operator behavior.
     *
     * @param ctx  The context (SCALAR or LIST)
     * @param args The files to be deleted
     * @return A RuntimeScalar indicating the number of files successfully deleted.
     */
    public static RuntimeBase unlink(int ctx, RuntimeBase... args) {
        int successCount = 0;
        RuntimeList fileList = new RuntimeList();

        // If no arguments provided, use $_
        if (args.length == 0) {
            fileList.elements.add(GlobalVariable.getGlobalVariable("main::_"));
        } else {
            // Convert args to RuntimeList
            for (RuntimeBase arg : args) {
                arg.addToList(fileList);
            }
        }

        for (RuntimeScalar fileScalar : fileList) {
            String fileName = fileScalar.toString();

            if (deleteFile(fileName)) {
                successCount++;
            }
        }

        // In scalar context, return true if all files were deleted
        // In list context, return the count of successfully deleted files
        if (ctx == RuntimeContextType.SCALAR) {
            return getScalarBoolean(successCount == fileList.size());
        } else {
            return new RuntimeScalar(successCount);
        }
    }

    /**
     * Helper method to delete a single file using native calls when available
     */
    private static boolean deleteFile(String fileName) {
        try {
            Path path = RuntimeIO.resolvePath(fileName);

            // Try native deletion first for better platform compatibility
            if (NativeUtils.IS_WINDOWS) {
                // Use Windows API directly
                boolean result = Kernel32.INSTANCE.DeleteFile(path.toString());
                if (!result) {
                    int error = Kernel32.INSTANCE.GetLastError();
                    setUnlinkError(error);
                    return false;
                }
                return true;
            } else if (Platform.isLinux() || Platform.isMac()) {
                // Use POSIX unlink
                try {
                    int result = PosixLibrary.INSTANCE.unlink(path.toString());
                    if (result != 0) {
                        setUnlinkError(Native.getLastError());
                        return false;
                    }
                    return true;
                } catch (LastErrorException e) {
                    setUnlinkError(e.getErrorCode());
                    return false;
                }
            } else {
                // Fallback to Java NIO for other platforms
                Files.delete(path);
                return true;
            }
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set("No such file or directory");
            Native.setLastError(2); // ENOENT
            return false;
        } catch (AccessDeniedException e) {
            getGlobalVariable("main::!").set("Permission denied");
            Native.setLastError(13); // EACCES
            return false;
        } catch (DirectoryNotEmptyException e) {
            getGlobalVariable("main::!").set("Directory not empty");
            Native.setLastError(39); // ENOTEMPTY
            return false;
        } catch (IOException e) {
            String errorMessage = e.getMessage();
            getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
            // Try to set appropriate errno based on the exception
            if (errorMessage != null && errorMessage.contains("in use")) {
                Native.setLastError(16); // EBUSY
            }
            return false;
        }
    }

    /**
     * Set error message and errno based on platform-specific error code
     */
    private static void setUnlinkError(int errorCode) {
        String message;
        int errno;

        if (NativeUtils.IS_WINDOWS) {
            // Map Windows error codes to errno and messages
            switch (errorCode) {
                case 2:   // ERROR_FILE_NOT_FOUND
                case 3:   // ERROR_PATH_NOT_FOUND
                    message = "No such file or directory";
                    errno = 2; // ENOENT
                    break;
                case 5:   // ERROR_ACCESS_DENIED
                    message = "Permission denied";
                    errno = 13; // EACCES
                    break;
                case 32:  // ERROR_SHARING_VIOLATION
                case 33:  // ERROR_LOCK_VIOLATION
                    message = "Resource busy";
                    errno = 16; // EBUSY
                    break;
                case 145: // ERROR_DIR_NOT_EMPTY
                    message = "Directory not empty";
                    errno = 39; // ENOTEMPTY
                    break;
                default:
                    message = "Error " + errorCode;
                    errno = errorCode;
            }
        } else {
            // POSIX errno values
            switch (errorCode) {
                case 2:   // ENOENT
                    message = "No such file or directory";
                    errno = 2;
                    break;
                case 13:  // EACCES
                    message = "Permission denied";
                    errno = 13;
                    break;
                case 16:  // EBUSY
                    message = "Resource busy";
                    errno = 16;
                    break;
                case 21:  // EISDIR
                    message = "Is a directory";
                    errno = 21;
                    break;
                case 39:  // ENOTEMPTY
                    message = "Directory not empty";
                    errno = 39;
                    break;
                default:
                    message = "Error " + errorCode;
                    errno = errorCode;
            }
        }

        getGlobalVariable("main::!").set(message);
        Native.setLastError(errno);
    }
}
