package org.perlonjava.runtime.operators;

import org.perlonjava.runtime.runtimetypes.*;

import java.io.IOException;
import java.nio.file.*;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarBoolean;

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
            Path path = RuntimeIO.resolvePath(fileName, "unlink");
            if (path == null) {
                return false;
            }

            // Avoid native/JNA unlink implementations.
            // Some environments (including perl5 test runs) may not have JNA available,
            // and loading com.sun.jna classes can crash the test harness during cleanup.
            Files.delete(path);
            return true;
        } catch (NoSuchFileException e) {
            getGlobalVariable("main::!").set("No such file or directory");
            return false;
        } catch (AccessDeniedException e) {
            getGlobalVariable("main::!").set("Permission denied");
            return false;
        } catch (DirectoryNotEmptyException e) {
            getGlobalVariable("main::!").set("Directory not empty");
            return false;
        } catch (FileSystemException e) {
            // Common case on some platforms when trying to unlink a directory
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("directory")) {
                getGlobalVariable("main::!").set("Is a directory");
                return false;
            }
            getGlobalVariable("main::!").set(msg != null ? msg : "File system error");
            return false;
        } catch (IOException e) {
            String errorMessage = e.getMessage();
            getGlobalVariable("main::!").set(errorMessage != null ? errorMessage : "I/O error");
            return false;
        }
    }

    /**
     * Set error message and errno based on platform-specific error code
     */
    private static void setUnlinkError(int errorCode) {
        String message;
        int errno;

        // Best-effort mapping; we only set $! message here.
        // We intentionally do not depend on native/JNA errno plumbing.
        switch (errorCode) {
            case 2:
                message = "No such file or directory";
                errno = 2;
                break;
            case 13:
                message = "Permission denied";
                errno = 13;
                break;
            case 16:
                message = "Resource busy";
                errno = 16;
                break;
            case 21:
                message = "Is a directory";
                errno = 21;
                break;
            case 39:
                message = "Directory not empty";
                errno = 39;
                break;
            default:
                message = "Error " + errorCode;
                errno = errorCode;
        }

        getGlobalVariable("main::!").set(message);
    }
}
