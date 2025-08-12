package org.perlonjava.io;

import org.perlonjava.runtime.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarTrue;

/**
 * The {@code DirectoryIO} class provides methods for directory operations such as opening,
 * reading, seeking, and closing directories. It wraps a {@link DirectoryStream} to iterate
 * over directory entries and maintains the current position within the directory.
 */
public class DirectoryIO {
    private final String directoryPath;
    private final Path absoluteDirectoryPath;
    public DirectoryStream<Path> directoryStream;
    private List<String> allEntries; // Cache all directory entries
    private int currentPosition = 0;
    private boolean entriesLoaded = false;

    /**
     * Constructs a {@code DirectoryIO} object with the specified directory stream and path.
     *
     * @param directoryStream the directory stream to be used for reading directory entries
     * @param directoryPath   the path of the directory
     */
    public DirectoryIO(DirectoryStream<Path> directoryStream, String directoryPath) {
        this.directoryStream = directoryStream;
        this.directoryPath = directoryPath;

        // Resolve and store absolute path
        Path path = Paths.get(directoryPath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir"), directoryPath);
        }
        this.absoluteDirectoryPath = path.toAbsolutePath().normalize();
    }

    /**
     * Load all directory entries into memory for consistent seeking
     */
    private void loadAllEntries() {
        if (entriesLoaded) {
            return;
        }

        allEntries = new ArrayList<>();
        allEntries.add("."); // Always add special entries first
        allEntries.add("..");

        // Add all actual directory entries
        for (Path entry : directoryStream) {
            allEntries.add(entry.getFileName().toString());
        }

        entriesLoaded = true;
    }

    /**
     * Returns the current position within the directory stream.
     *
     * @return the current directory position as an integer
     */
    public RuntimeScalar telldir() {
        return new RuntimeScalar(currentPosition);
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

        // Make sure all entries are loaded
        loadAllEntries();

        // Set the current position
        if (position < 0 || position > allEntries.size()) {
            currentPosition = -1; // Out of range
        } else {
            currentPosition = position;
        }

        return scalarTrue;
    }

    /**
     * Resets the directory stream to the beginning.
     */
    public void rewinddir() {
        seekdir(0);
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
        // Make sure all entries are loaded
        loadAllEntries();

        if (ctx == RuntimeContextType.SCALAR) {
            // Check if we're at a valid position
            if (currentPosition < 0 || currentPosition >= allEntries.size()) {
                return RuntimeScalarCache.scalarFalse;
            }

            // Get the entry at current position and advance
            String entry = allEntries.get(currentPosition);
            currentPosition++;
            return new RuntimeScalar(entry);

        } else {
            // List context - return all remaining entries
            RuntimeList result = new RuntimeList();

            while (currentPosition >= 0 && currentPosition < allEntries.size()) {
                result.elements.add(new RuntimeScalar(allEntries.get(currentPosition)));
                currentPosition++;
            }

            return result.scalar();
        }
    }
}