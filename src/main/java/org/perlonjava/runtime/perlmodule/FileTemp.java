package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.SystemUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java backend for File::Temp module.
 * Provides core functionality for creating temporary files and directories.
 */
public class FileTemp extends PerlModuleBase {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMPLATE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";
    private static final int MAX_TRIES = 256;

    // Track files/dirs for cleanup
    private static final Map<Long, Set<Path>> TEMP_FILES = new ConcurrentHashMap<>();
    private static final Map<Long, Set<Path>> TEMP_DIRS = new ConcurrentHashMap<>();

    static {
        // Register shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(FileTemp::cleanupAll));
    }

    public FileTemp() {
        super("File::Temp", false);
    }

    public static void initialize() {
        FileTemp fileTemp = new FileTemp();
        fileTemp.initializeExporter();
        try {
            fileTemp.registerMethod("_mkstemp", "$");
            fileTemp.registerMethod("_mkstemps", "$$");
            fileTemp.registerMethod("_mkdtemp", "$");
            fileTemp.registerMethod("_tmpdir", "");
            fileTemp.registerMethod("_cleanup", "");
            fileTemp.registerMethod("_register_temp_file", "$");
            fileTemp.registerMethod("_register_temp_dir", "$");
            fileTemp.registerMethod("_unregister_temp_file", "$");
            fileTemp.registerMethod("_unregister_temp_dir", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing File::Temp method: " + e.getMessage());
        }
    }

    /**
     * Create a temporary file from template
     */
    public static RuntimeList _mkstemp(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _mkstemp");
        }

        String template = args.get(1).toString();
        return createTempFile(template, "", false);
    }

    /**
     * Create a temporary file with suffix
     */
    public static RuntimeList _mkstemps(RuntimeArray args, int ctx) {
        if (args.size() != 3) {
            throw new IllegalStateException("Bad number of arguments for _mkstemps");
        }

        String template = args.get(1).toString();
        String suffix = args.get(2).toString();
        return createTempFile(template, suffix, false);
    }

    /**
     * Create a temporary directory
     */
    public static RuntimeList _mkdtemp(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _mkdtemp");
        }

        String template = args.get(1).toString();
        Path dir = createTempDir(template);
        return new RuntimeList(new RuntimeScalar(dir.toString()));
    }

    /**
     * Get temporary directory
     */
    public static RuntimeList _tmpdir(RuntimeArray args, int ctx) {
        String tmpDir = getTempDir();
        return new RuntimeList(new RuntimeScalar(tmpDir));
    }

    /**
     * Clean up registered temporary files and directories
     */
    public static RuntimeList _cleanup(RuntimeArray args, int ctx) {
        cleanupAll();
        return new RuntimeList();
    }

    /**
     * Register a temporary file for cleanup
     */
    public static RuntimeList _register_temp_file(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _register_temp_file");
        }

        String path = args.get(1).toString();
        long pid = ProcessHandle.current().pid();
        TEMP_FILES.computeIfAbsent(pid, k -> new HashSet<>()).add(Paths.get(path));
        return new RuntimeList();
    }

    /**
     * Register a temporary directory for cleanup
     */
    public static RuntimeList _register_temp_dir(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _register_temp_dir");
        }

        String path = args.get(1).toString();
        long pid = ProcessHandle.current().pid();
        TEMP_DIRS.computeIfAbsent(pid, k -> new HashSet<>()).add(Paths.get(path));
        return new RuntimeList();
    }

    /**
     * Unregister a temporary file
     */
    public static RuntimeList _unregister_temp_file(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _unregister_temp_file");
        }

        String path = args.get(1).toString();
        long pid = ProcessHandle.current().pid();
        Set<Path> files = TEMP_FILES.get(pid);
        if (files != null) {
            files.remove(Paths.get(path));
        }
        return new RuntimeList();
    }

    /**
     * Unregister a temporary directory
     */
    public static RuntimeList _unregister_temp_dir(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for _unregister_temp_dir");
        }

        String path = args.get(1).toString();
        long pid = ProcessHandle.current().pid();
        Set<Path> dirs = TEMP_DIRS.get(pid);
        if (dirs != null) {
            dirs.remove(Paths.get(path));
        }
        return new RuntimeList();
    }

    // Helper methods

    private static RuntimeList createTempFile(String template, String suffix, boolean openFile) {
        try {
            // Parse template to find X's
            int xCount = 0;
            int xStart = template.length();
            for (int i = template.length() - 1; i >= 0; i--) {
                if (template.charAt(i) == 'X') {
                    xCount++;
                    xStart = i;
                } else {
                    break;
                }
            }

            if (xCount < 4) {
                throw new IllegalArgumentException("Template must end with at least 4 'X' characters");
            }

            String prefix = template.substring(0, xStart);
            Path templatePath = Paths.get(prefix);
            Path dir = templatePath.getParent();
            String namePrefix = templatePath.getFileName() != null ?
                    templatePath.getFileName().toString() : "";

            if (dir == null) {
                dir = Paths.get(getTempDir());
            }

            // Try to create temp file
            IOException lastException = null;
            for (int i = 0; i < MAX_TRIES; i++) {
                String randomPart = generateRandomString(xCount);
                String fileName = namePrefix + randomPart + suffix;
                Path filePath = dir.resolve(fileName);

                try {
                    // Create file with restrictive permissions
                    Set<OpenOption> options = new HashSet<>();
                    options.add(StandardOpenOption.CREATE_NEW);
                    options.add(StandardOpenOption.WRITE);
                    options.add(StandardOpenOption.READ);

                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                    FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);

                    // Try to create with POSIX permissions, fall back if not supported
                    try {
                        Files.newByteChannel(filePath, options, attr).close();
                    } catch (UnsupportedOperationException e) {
                        // Windows doesn't support POSIX permissions
                        Files.newByteChannel(filePath, options).close();
                    }

                    // Register for cleanup
                    long pid = ProcessHandle.current().pid();
                    TEMP_FILES.computeIfAbsent(pid, k -> new HashSet<>()).add(filePath);

                    // Return file descriptor and path
                    int fd = openFile ? openFileDescriptor(filePath) : -1;
                    return new RuntimeList(
                            new RuntimeScalar(fd),
                            new RuntimeScalar(filePath.toString())
                    );

                } catch (FileAlreadyExistsException e) {
                    // Try again with different random string
                    lastException = e;
                } catch (IOException e) {
                    lastException = e;
                }
            }

            throw new IOException("Could not create temp file after " + MAX_TRIES + " attempts", lastException);

        } catch (Exception e) {
            throw new RuntimeException("Error creating temp file: " + e.getMessage(), e);
        }
    }

    private static Path createTempDir(String template) {
        try {
            // Parse template to find X's
            int xCount = 0;
            int xStart = template.length();
            for (int i = template.length() - 1; i >= 0; i--) {
                if (template.charAt(i) == 'X') {
                    xCount++;
                    xStart = i;
                } else {
                    break;
                }
            }

            if (xCount < 4) {
                throw new IllegalArgumentException("Template must end with at least 4 'X' characters");
            }

            String prefix = template.substring(0, xStart);
            Path templatePath = Paths.get(prefix);
            Path parentDir = templatePath.getParent();
            String namePrefix = templatePath.getFileName() != null ?
                    templatePath.getFileName().toString() : "";

            if (parentDir == null) {
                parentDir = Paths.get(getTempDir());
            }

            // Try to create temp directory
            IOException lastException = null;
            for (int i = 0; i < MAX_TRIES; i++) {
                String randomPart = generateRandomString(xCount);
                String dirName = namePrefix + randomPart;
                Path dirPath = parentDir.resolve(dirName);

                try {
                    // Create directory with restrictive permissions
                    try {
                        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
                        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
                        Files.createDirectory(dirPath, attr);
                    } catch (UnsupportedOperationException e) {
                        // Windows doesn't support POSIX permissions
                        Files.createDirectory(dirPath);
                    }

                    // Register for cleanup
                    long pid = ProcessHandle.current().pid();
                    TEMP_DIRS.computeIfAbsent(pid, k -> new HashSet<>()).add(dirPath);

                    return dirPath;

                } catch (FileAlreadyExistsException e) {
                    // Try again with different random string
                    lastException = e;
                } catch (IOException e) {
                    lastException = e;
                }
            }

            throw new IOException("Could not create temp directory after " + MAX_TRIES + " attempts", lastException);

        } catch (Exception e) {
            throw new RuntimeException("Error creating temp directory: " + e.getMessage(), e);
        }
    }

    private static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TEMPLATE_CHARS.charAt(RANDOM.nextInt(TEMPLATE_CHARS.length())));
        }
        return sb.toString();
    }

    private static String getTempDir() {
        // Check environment variables first
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir != null && !tmpDir.isEmpty()) {
            return tmpDir;
        }

        if (SystemUtils.osIsWindows()) {
            tmpDir = System.getenv("TEMP");
            if (tmpDir != null && !tmpDir.isEmpty()) {
                return tmpDir;
            }
            tmpDir = System.getenv("TMP");
            if (tmpDir != null && !tmpDir.isEmpty()) {
                return tmpDir;
            }
        }

        // Use system property
        return System.getProperty("java.io.tmpdir");
    }

    private static int openFileDescriptor(Path path) throws IOException {
        // This is a simplified version - in real implementation would need JNI
        // For now, return a dummy file descriptor
        return path.hashCode() & 0x7FFFFFFF;
    }

    private static void cleanupAll() {
        long pid = ProcessHandle.current().pid();

        // Clean up files
        Set<Path> files = TEMP_FILES.get(pid);
        if (files != null) {
            for (Path file : files) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // Ignore errors during cleanup
                }
            }
            TEMP_FILES.remove(pid);
        }

        // Clean up directories
        Set<Path> dirs = TEMP_DIRS.get(pid);
        if (dirs != null) {
            for (Path dir : dirs) {
                try {
                    deleteDirectory(dir);
                } catch (IOException e) {
                    // Ignore errors during cleanup
                }
            }
            TEMP_DIRS.remove(pid);
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }
}