package org.perlonjava.io;

import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;

import static org.perlonjava.runtime.RuntimeIO.handleIOException;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * The {@code DirectoryIO} class provides methods for directory operations such as opening,
 * reading, seeking, and closing directories. It wraps a {@link DirectoryStream} to iterate
 * over directory entries and maintains the current position within the directory.
 */
public class DirectoryIO {
    private final String directoryPath;
    private final ArrayList<RuntimeScalar> directorySpecialEntries = new ArrayList<>();
    private DirectoryStream<Path> directoryStream;
    private Iterator<Path> directoryIterator;
    private int currentDirPosition = 0;

    /**
     * Constructs a {@code DirectoryIO} object with the specified directory stream and path.
     *
     * @param directoryStream the directory stream to be used for reading directory entries
     * @param directoryPath   the path of the directory
     */
    public DirectoryIO(DirectoryStream<Path> directoryStream, String directoryPath) {
        this.directoryStream = directoryStream;
        this.directoryPath = directoryPath;
    }

    /**
     * Opens a directory specified by the arguments and returns a {@code RuntimeScalar} indicating
     * success or failure.
     *
     * @param args a {@code RuntimeList} containing the directory handle and path
     * @return {@code RuntimeScalarCache.scalarTrue} if the directory is successfully opened,
     * otherwise {@code RuntimeScalarCache.scalarFalse}
     */
    public static RuntimeScalar openDir(RuntimeList args) {
        RuntimeScalar dirHandle = (RuntimeScalar) args.elements.get(0);
        String dirPath = args.elements.get(1).toString();

        try {
            // Fix: Check if path is absolute
            Path fullDirPath;
            Path path = Paths.get(dirPath);
            if (path.isAbsolute()) {
                fullDirPath = path;
            } else {
                fullDirPath = Paths.get(System.getProperty("user.dir")).resolve(dirPath);
            }

            DirectoryStream<Path> stream = Files.newDirectoryStream(fullDirPath);
            DirectoryIO dirIO = new DirectoryIO(stream, dirPath);
            dirHandle.type = RuntimeScalarType.GLOBREFERENCE;
            dirHandle.value = new RuntimeGlob(null).setIO(new RuntimeIO(dirIO));

            return scalarTrue;
        } catch (IOException e) {
            handleIOException(e, "Directory operation failed");
            return RuntimeScalarCache.scalarFalse;
        }
    }

    /**
     * Closes the directory stream if it is open and returns a {@code RuntimeScalar} indicating
     * success or failure.
     *
     * @return {@code RuntimeScalarCache.scalarTrue} if the directory stream is successfully closed,
     * otherwise {@code RuntimeScalarCache.scalarFalse}
     */
    public RuntimeScalar closedir() {
        try {
            if (directoryStream != null) {
                directoryStream.close();
                directoryStream = null;
                return scalarTrue;
            }
            return RuntimeScalarCache.scalarFalse;
        } catch (IOException e) {
            return handleIOException(e, "Directory operation failed");
        }
    }

    /**
     * Returns the current position within the directory stream.
     *
     * @return the current directory position as an integer
     */
    public RuntimeScalar telldir() {
        return new RuntimeScalar(currentDirPosition);
    }

    /**
     * Seeks to the specified position within the directory stream.
     *
     * @param position the position to seek to
     * @throws PerlCompilerException if seeking is not supported or an I/O error occurs
     */
    public RuntimeScalar seekdir(int position) {
        if (directoryStream == null) {
            throw new PerlCompilerException("seekdir is not supported for non-directory streams");
        }

        try {
            directoryStream.close();
            directoryStream = Files.newDirectoryStream(Paths.get(directoryPath));
            directoryIterator = directoryStream.iterator();
            for (int i = 1; i < position && directoryIterator.hasNext(); i++) {
                directoryIterator.next();
            }
            currentDirPosition = position;
            return scalarTrue;
        } catch (IOException e) {
            throw new PerlCompilerException("Directory operation failed: " + e.getMessage());
        }
    }

    /**
     * Resets the directory stream to the beginning.
     */
    public void rewinddir() {
        seekdir(1);
    }

    /**
     * Reads the next entry from the directory stream and returns it as a {@code RuntimeScalar}.
     * If the context is scalar, it returns a single entry. If the context is list, it returns
     * all remaining entries.
     *
     * @param ctx the context type, either scalar or list
     * @return a {@code RuntimeScalar} representing the directory entry or entries
     */
    public RuntimeScalar readdir(int ctx) {
        if (directoryIterator == null) {
            directoryIterator = directoryStream.iterator();
            directorySpecialEntries.add(new RuntimeScalar("."));
            directorySpecialEntries.add(new RuntimeScalar(".."));
        }

        if (ctx == RuntimeContextType.SCALAR) {
            if (!directorySpecialEntries.isEmpty()) {
                return directorySpecialEntries.removeFirst();
            }

            if (directoryIterator.hasNext()) {
                Path entry = directoryIterator.next();
                return new RuntimeScalar(entry.getFileName().toString());
            } else {
                return RuntimeScalarCache.scalarFalse;
            }
        } else {
            RuntimeList result = new RuntimeList();
            result.elements.addAll(directorySpecialEntries);
            directorySpecialEntries.clear();

            while (directoryIterator.hasNext()) {
                Path entry = directoryIterator.next();
                result.elements.add(new RuntimeScalar(entry.getFileName().toString()));
            }

            return result.scalar();
        }
    }
}

