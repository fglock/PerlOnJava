package org.perlonjava.perlmodule;

import org.perlonjava.operators.Operator;
import org.perlonjava.runtime.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.stream.Stream;

import static org.perlonjava.runtime.GlobalVariable.getGlobalIO;

/**
 * Java backend for File::Path module.
 * Provides core functionality for creating and removing directory trees.
 */
public class FilePath extends PerlModuleBase {

    public FilePath() {
        super("File::Path", false);
    }

    public static void initialize() {
        FilePath filePath = new FilePath();
        filePath.initializeExporter();
        try {
            filePath.registerMethod("make_path", "@");
            filePath.registerMethod("remove_tree", "@");
            filePath.registerMethod("mkpath", "@");
            filePath.registerMethod("rmtree", "@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing File::Path method: " + e.getMessage());
        }
    }

    // make_path implementation
    public static RuntimeList make_path(RuntimeArray args, RuntimeScalar ctx) {
        // Extract paths and options
        List<String> paths = new ArrayList<>();
        Map<String, Object> options = extractOptionsNative(args, paths);

        // Get options with defaults
        int mode = getIntOption(options, "mode", 0777);
        boolean verbose = getBoolOption(options, "verbose", false);
        List<Map<String, String>> errors = new ArrayList<>();
        RuntimeArray errorRef = (RuntimeArray) options.get("errorRef");

        // Track created directories
        List<String> created = new ArrayList<>();

        // Process each path
        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isEmpty()) continue;

            Path path = Paths.get(pathStr);

            try {
                if (!Files.exists(path)) {
                    // Create all parent directories
                    createDirectoryTree(path, created, verbose, mode, errors);
                }
            } catch (Exception e) {
                addErrorNative(errors, pathStr, e.getMessage());
            }
        }

        // Update error reference if provided
        if (errorRef != null && !errors.isEmpty()) {
            updateErrorRef(errorRef, errors);
        }

        // Return created directories as RuntimeList
        RuntimeList result = new RuntimeList();
        for (String dir : created) {
            result.add(new RuntimeScalar(dir));
        }
        return result;
    }

    // remove_tree implementation
    public static RuntimeScalar remove_tree(RuntimeArray args, RuntimeScalar ctx) {
        // Extract paths and options
        List<String> paths = new ArrayList<>();
        Map<String, Object> options = extractOptionsNative(args, paths);

        // Get options with defaults
        boolean verbose = getBoolOption(options, "verbose", false);
        boolean safe = getBoolOption(options, "safe", false);
        boolean keepRoot = getBoolOption(options, "keep_root", false);
        List<Map<String, String>> errors = new ArrayList<>();
        RuntimeArray errorRef = (RuntimeArray) options.get("errorRef");
        RuntimeArray resultRef = (RuntimeArray) options.get("resultRef");

        List<String> removed = new ArrayList<>();
        int count = 0;

        // Process each path
        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isEmpty()) continue;

            Path path = Paths.get(pathStr);

            try {
                if (Files.exists(path)) {
                    if (Files.isDirectory(path)) {
                        count += removeDirectoryTree(path, keepRoot, safe, verbose, removed, errors);
                    } else {
                        // It's a file
                        if (removeFile(path, safe, errors)) {
                            count++;
                            removed.add(path.toString());
                            if (verbose) {
                                Operator.say(
                                        new RuntimeList(new RuntimeScalar("unlink " + path )),
                                        getGlobalIO("main::STDERR"));
                            }
                        }
                    }
                } else {
                    addErrorNative(errors, pathStr, "No such file or directory");
                }
            } catch (Exception e) {
                addErrorNative(errors, pathStr, e.getMessage());
            }
        }

        // Update references if provided
        if (errorRef != null && !errors.isEmpty()) {
            updateErrorRef(errorRef, errors);
        }
        if (resultRef != null && !removed.isEmpty()) {
            updateResultRef(resultRef, removed);
        }

        return new RuntimeScalar(count);
    }

    // mkpath - legacy interface
    public static RuntimeList mkpath(RuntimeArray args, RuntimeScalar ctx) {
        if (args.size() == 0) {
            return new RuntimeList();
        }

        // Check if last argument is a hashref (new style)
        RuntimeScalar lastArg = args.get(args.size() - 1);
        if (lastArg.type == RuntimeScalarType.HASHREFERENCE) {
            return make_path(args, ctx);
        }

        // Old style mkpath
        RuntimeArray newArgs = new RuntimeArray();
        RuntimeScalar paths = args.get(0);

        // Convert paths to array
        if (paths.type == RuntimeScalarType.ARRAYREFERENCE) {
            RuntimeArray pathArray = (RuntimeArray) paths.value;
            for (int i = 0; i < pathArray.size(); i++) {
                RuntimeArray.push(newArgs, pathArray.get(i));
            }
        } else {
            RuntimeArray.push(newArgs, paths);
        }

        // Build options hash
        RuntimeHash options = new RuntimeHash();
        if (args.size() > 1) {
            options.put("verbose", args.get(1));
        }
        if (args.size() > 2) {
            options.put("mode", args.get(2));
        }

        // Add options as hashref
        RuntimeScalar optionsRef = new RuntimeScalar();
        optionsRef.type = RuntimeScalarType.HASHREFERENCE;
        optionsRef.value = options;
        RuntimeArray.push(newArgs, optionsRef);

        return make_path(newArgs, ctx);
    }

    // rmtree - legacy interface
    public static RuntimeScalar rmtree(RuntimeArray args, RuntimeScalar ctx) {
        if (args.size() == 0) {
            return new RuntimeScalar(0);
        }

        // Check if last argument is a hashref (new style)
        RuntimeScalar lastArg = args.get(args.size() - 1);
        if (lastArg.type == RuntimeScalarType.HASHREFERENCE) {
            return remove_tree(args, ctx);
        }

        // Old style rmtree
        RuntimeArray newArgs = new RuntimeArray();
        RuntimeScalar paths = args.get(0);

        // Convert paths to array
        if (paths.type == RuntimeScalarType.ARRAYREFERENCE) {
            RuntimeArray pathArray = (RuntimeArray) paths.value;
            for (int i = 0; i < pathArray.size(); i++) {
                RuntimeArray.push(newArgs, pathArray.get(i));
            }
        } else {
            RuntimeArray.push(newArgs, paths);
        }

        // Build options hash
        RuntimeHash options = new RuntimeHash();
        if (args.size() > 1) {
            options.put("verbose", args.get(1));
        }
        if (args.size() > 2) {
            options.put("safe", args.get(2));
        }

        // Add options as hashref
        RuntimeScalar optionsRef = new RuntimeScalar();
        optionsRef.type = RuntimeScalarType.HASHREFERENCE;
        optionsRef.value = options;
        RuntimeArray.push(newArgs, optionsRef);

        return remove_tree(newArgs, ctx);
    }

    // Helper methods using native Java collections

    private static Map<String, Object> extractOptionsNative(RuntimeArray args, List<String> paths) {
        Map<String, Object> options = new HashMap<>();

        // Check if last argument is options hashref
        boolean hasOptions = false;
        if (args.size() > 0) {
            RuntimeScalar last = args.get(args.size() - 1);
            if (last.type == RuntimeScalarType.HASHREFERENCE) {
                hasOptions = true;
                RuntimeHash optHash = (RuntimeHash) last.value;

                // Extract standard options
                if (optHash.exists("mode").getBoolean()) {
                    options.put("mode", optHash.get("mode"));
                }
                if (optHash.exists("verbose").getBoolean()) {
                    options.put("verbose", optHash.get("verbose"));
                }
                if (optHash.exists("safe").getBoolean()) {
                    options.put("safe", optHash.get("safe"));
                }
                if (optHash.exists("keep_root").getBoolean()) {
                    options.put("keep_root", optHash.get("keep_root"));
                }

                // Handle reference options
                if (optHash.exists("error").getBoolean()) {
                    RuntimeScalar err = optHash.get("error");
                    if (err.type == RuntimeScalarType.REFERENCE) {
                        err.scalarDeref().set(new RuntimeArray());
                        options.put("errorRef", err.scalarDeref().arrayDeref());
                    }
                }
                if (optHash.exists("result").getBoolean()) {
                    RuntimeScalar res = optHash.get("result");
                    if (res.type == RuntimeScalarType.REFERENCE) {
                        res.scalarDeref().set(new RuntimeArray());
                        options.put("resultRef", res.scalarDeref().arrayDeref());
                    }
                }
            }
        }

        // Extract paths (all args except options)
        int endIndex = hasOptions ? args.size() - 1 : args.size();
        for (int i = 0; i < endIndex; i++) {
            paths.add(args.get(i).toString());
        }

        return options;
    }

    private static int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        Object value = options.get(key);
        if (value instanceof RuntimeScalar) {
            RuntimeScalar scalar = (RuntimeScalar) value;
            if (scalar.getDefinedBoolean()) {
                String str = scalar.toString();
                try {
                    // Handle octal notation
                    if (str.startsWith("0") && str.length() > 1 && !str.startsWith("0x")) {
                        return Integer.parseInt(str, 8);
                    }
                    return scalar.getInt();
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static boolean getBoolOption(Map<String, Object> options, String key, boolean defaultValue) {
        Object value = options.get(key);
        if (value instanceof RuntimeScalar) {
            RuntimeScalar scalar = (RuntimeScalar) value;
            if (scalar.getDefinedBoolean()) {
                return scalar.getBoolean();
            }
        }
        return defaultValue;
    }

    private static void createDirectoryTree(Path path, List<String> created,
                                            boolean verbose, int mode, List<Map<String, String>> errors) {
        try {
            if (!Files.exists(path)) {
                // Create parent directories first
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    createDirectoryTree(parent, created, verbose, mode, errors);
                }

                // Create this directory
                Files.createDirectory(path);
                setPermissions(path, mode);
                created.add(path.toString());

                if (verbose) {
                    Operator.say(
                            new RuntimeList(new RuntimeScalar("mkdir " + path )),
                            getGlobalIO("main::STDERR"));

                }
            }
        } catch (IOException e) {
            addErrorNative(errors, path.toString(), e.getMessage());
        }
    }

    private static int removeDirectoryTree(Path path, boolean keepThisDir, boolean safe,
                                           boolean verbose, List<String> removed, List<Map<String, String>> errors) {
        int count = 0;

        try {
            // First, remove all contents
            try (Stream<Path> stream = Files.list(path)) {
                for (Path entry : stream.toArray(Path[]::new)) {
                    if (Files.isDirectory(entry)) {
                        count += removeDirectoryTree(entry, false, safe, verbose, removed, errors);
                    } else {
                        if (removeFile(entry, safe, errors)) {
                            count++;
                            removed.add(entry.toString());
                            if (verbose) {
                                Operator.say(
                                        new RuntimeList(new RuntimeScalar("unlink " + path )),
                                        getGlobalIO("main::STDERR"));
                            }
                        }
                    }
                }
            }

            // Then remove the directory itself unless keepThisDir is true
            if (!keepThisDir) {
                if (!safe && !Files.isWritable(path)) {
                    makeWritable(path);
                }

                Files.delete(path);
                count++;
                removed.add(path.toString());
                if (verbose) {
                    Operator.say(
                            new RuntimeList(new RuntimeScalar("rmdir " + path )),
                            getGlobalIO("main::STDERR"));
                }
            }
        } catch (IOException e) {
            addErrorNative(errors, path.toString(), "cannot remove directory: " + e.getMessage());
        }

        return count;
    }

    private static boolean removeFile(Path path, boolean safe, List<Map<String, String>> errors) {
        try {
            if (!safe && !Files.isWritable(path)) {
                makeWritable(path);
            }

            Files.delete(path);
            return true;
        } catch (IOException e) {
            addErrorNative(errors, path.toString(), "cannot unlink file: " + e.getMessage());
            return false;
        }
    }

    private static void setPermissions(Path path, int mode) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Windows doesn't support Unix-style permissions
            return;
        }

        try {
            Set<PosixFilePermission> perms = new HashSet<>();

            // Convert octal mode to PosixFilePermissions
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);

            Files.setPosixFilePermissions(path, perms);
        } catch (IOException | UnsupportedOperationException e) {
            // Ignore permission errors
        }
    }

    private static void makeWritable(Path path) {
        try {
            File file = path.toFile();
            file.setWritable(true);
        } catch (Exception e) {
            // Ignore
        }
    }

    private static void addErrorNative(List<Map<String, String>> errors, String file, String message) {
        Map<String, String> error = new HashMap<>();
        error.put(file.isEmpty() ? "" : file, message);
        errors.add(error);
    }

    private static void updateErrorRef(RuntimeArray errorRef, List<Map<String, String>> errors) {
        for (Map<String, String> error : errors) {
            RuntimeHash errorHash = new RuntimeHash();
            for (Map.Entry<String, String> entry : error.entrySet()) {
                errorHash.put(entry.getKey(), new RuntimeScalar(entry.getValue()));
            }
            RuntimeScalar errorScalar = new RuntimeScalar();
            errorScalar.type = RuntimeScalarType.HASHREFERENCE;
            errorScalar.value = errorHash;
            RuntimeArray.push(errorRef, errorScalar);
        }
    }

    private static void updateResultRef(RuntimeArray resultRef, List<String> removed) {
        for (String path : removed) {
            RuntimeArray.push(resultRef, new RuntimeScalar(path));
        }
    }
}
