package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.SystemUtils;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for File::Spec operations in Perl.
 * This class provides methods that mimic the behavior of Perl's File::Spec module,
 * allowing for operations related to file path manipulation and environment-specific
 * path handling.
 *
 * <p>Extends {@link PerlModuleBase} to leverage module initialization and method registration.</p>
 */
public class FileSpec extends PerlModuleBase {

    /**
     * Constructor for FileSpec.
     * Initializes the module with the name "File::Spec".
     */
    public FileSpec() {
        super("File::Spec", false);
    }

    /**
     * Static initializer to set up the File::Spec module.
     * This method initializes the exporter and defines the symbols that can be exported.
     * It also registers methods that can be called from the Perl environment.
     */
    public static void initialize() {
        FileSpec fileSpec = new FileSpec();
        fileSpec.initializeExporter();
        fileSpec.defineExport("EXPORT_OK", "canonpath", "catdir", "catfile", "curdir", "devnull", "rootdir", "tmpdir",
                "updir", "no_upwards", "case_tolerant", "file_name_is_absolute", "path", "join", "splitpath", "splitdir",
                "catpath", "abs2rel", "rel2abs");
        try {
            fileSpec.registerMethod("canonpath", "$");
            fileSpec.registerMethod("catdir", "@");
            fileSpec.registerMethod("catfile", "@");
            fileSpec.registerMethod("curdir", "");
            fileSpec.registerMethod("devnull", "");
            fileSpec.registerMethod("rootdir", "");
            fileSpec.registerMethod("tmpdir", "");
            fileSpec.registerMethod("updir", "");
            fileSpec.registerMethod("no_upwards", "@");
            fileSpec.registerMethod("case_tolerant", "");
            fileSpec.registerMethod("file_name_is_absolute", "$");
            fileSpec.registerMethod("path", "");
            fileSpec.registerMethod("join", "@");
            fileSpec.registerMethod("splitpath", "$");
            fileSpec.registerMethod("splitdir", "$");
            fileSpec.registerMethod("catpath", "$$");
            fileSpec.registerMethod("abs2rel", "$");
            fileSpec.registerMethod("rel2abs", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing File::Spec method: " + e.getMessage());
        }
    }

    /**
     * Converts a path to a canonical form, removing redundant separators and up-level references.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the path.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the canonical path.
     */
    public static RuntimeList canonpath(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for canonpath() method");
        }
        String path = args.get(1).toString();
        String quotedSeparator = Matcher.quoteReplacement(File.separator);
        String canonPath = path.replaceAll("[/\\\\]+", quotedSeparator)
                .replaceAll(Pattern.quote(File.separator) + "\\." + Pattern.quote(File.separator), quotedSeparator);
        return new RuntimeScalar(canonPath).getList();
    }

    /**
     * Concatenates multiple directory names into a single path.
     *
     * @param args The arguments passed from the Perl environment, representing directory names.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the concatenated directory path.
     */
    public static RuntimeList catdir(RuntimeArray args, int ctx) {
        if (args.size() == 1) {
            return new RuntimeScalar("").getList();
        }

        StringBuilder result = new StringBuilder();
        boolean isWindows = SystemUtils.osIsWindows();
        String separator = File.separator;

        for (int i = 1; i < args.size(); i++) {
            String part = args.get(i).toString();

            // Skip empty parts
            if (part.isEmpty()) {
                continue;
            }

            // For Windows, normalize slashes to the system separator
            if (isWindows) {
                part = part.replace('/', '\\');
            }

            if (result.length() == 0) {
                // First component
                result.append(part);
            } else {
                // Check if we need to add a separator
                char lastChar = result.charAt(result.length() - 1);
                char firstChar = part.charAt(0);

                boolean lastHasSep = (lastChar == '/' || lastChar == '\\');
                boolean firstHasSep = (firstChar == '/' || firstChar == '\\');

                if (!lastHasSep && !firstHasSep) {
                    // Neither has separator, add one
                    result.append(separator);
                } else if (lastHasSep && firstHasSep) {
                    // Both have separator, skip the first char of part
                    part = part.substring(1);
                }
                // else: exactly one has separator, just append

                result.append(part);
            }
        }

        return new RuntimeScalar(result.toString()).getList();
    }

    /**
     * Concatenates multiple file names into a single path.
     * This method is an alias for {@link #catdir(RuntimeArray, int)}.
     *
     * @param args The arguments passed from the Perl environment, representing file names.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the concatenated file path.
     */
    public static RuntimeList catfile(RuntimeArray args, int ctx) {
        return catdir(args, ctx);
    }

    /**
     * Returns the current directory symbol.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the current directory symbol.
     */
    public static RuntimeList curdir(RuntimeArray args, int ctx) {
        return new RuntimeScalar(".").getList();
    }

    /**
     * Returns the null device for the current operating system.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the null device path.
     */
    public static RuntimeList devnull(RuntimeArray args, int ctx) {
        String devNull = SystemUtils.osIsWindows() ? "NUL" : "/dev/null";
        return new RuntimeScalar(devNull).getList();
    }

    /**
     * Returns the root directory for the current operating system.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the root directory path.
     */
    public static RuntimeList rootdir(RuntimeArray args, int ctx) {
        String rootDir = SystemUtils.osIsWindows() ? "\\" : "/";
        return new RuntimeScalar(rootDir).getList();
    }

    /**
     * Returns the temporary directory path for the current operating system.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the temporary directory path.
     */
    public static RuntimeList tmpdir(RuntimeArray args, int ctx) {
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir == null || tmpDir.isEmpty()) {
            tmpDir = SystemUtils.osIsWindows() ? System.getenv("TEMP") : "/tmp";
        }
        return new RuntimeScalar(tmpDir).getList();
    }

    /**
     * Returns the parent directory symbol.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the parent directory symbol.
     */
    public static RuntimeList updir(RuntimeArray args, int ctx) {
        return new RuntimeScalar("..").getList();
    }

    /**
     * Filters out the current and parent directory symbols from a list of directory names.
     *
     * @param args The arguments passed from the Perl environment, representing directory names.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the filtered directory names.
     */
    public static RuntimeList no_upwards(RuntimeArray args, int ctx) {
        List<RuntimeScalar> filtered = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            String dir = args.get(i).toString();
            if (!dir.equals(".") && !dir.equals("..")) {
                filtered.add(args.get(i));
            }
        }
        return new RuntimeList(filtered);
    }

    /**
     * Determines if the current file system is case-tolerant.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing a boolean indicating case tolerance.
     */
    public static RuntimeList case_tolerant(RuntimeArray args, int ctx) {
        boolean caseTolerant = SystemUtils.osIsWindows();
        return new RuntimeScalar(caseTolerant).getList();
    }

    /**
     * Checks if a given file name is an absolute path.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the file name.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing a boolean indicating if the path is absolute.
     */
    public static RuntimeList file_name_is_absolute(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for file_name_is_absolute() method");
        }
        String path = args.get(1).toString();
        boolean isAbsolute = Paths.get(path).isAbsolute();
        return new RuntimeScalar(isAbsolute).getList();
    }

    /**
     * Retrieves the system's PATH environment variable as a list of directories.
     *
     * @param args The arguments passed from the Perl environment.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the directories in the PATH.
     */
    public static RuntimeList path(RuntimeArray args, int ctx) {
        String path = System.getenv("PATH");
        String[] paths = path != null ? path.split(File.pathSeparator) : new String[0];
        List<RuntimeScalar> pathList = new ArrayList<>();
        for (String p : paths) {
            pathList.add(new RuntimeScalar(p));
        }
        return new RuntimeList(pathList);
    }

    /**
     * Joins multiple path components into a single path.
     * This method is an alias for {@link #catfile(RuntimeArray, int)}.
     *
     * @param args The arguments passed from the Perl environment, representing path components.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the joined path.
     */
    public static RuntimeList join(RuntimeArray args, int ctx) {
        return catfile(args, ctx);
    }

    /**
     * Splits a path into volume, directory, and file components.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the path and args[2] is optional.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the volume, directory, and file components.
     */
    public static RuntimeList splitpath(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) {
            throw new IllegalStateException("Bad number of arguments for splitpath() method");
        }
        String path = args.get(1).toString();
        boolean noFile = args.size() == 3 && args.get(2).getBoolean();
        String volume = "";
        String directory = path;
        String file = "";

        if (SystemUtils.osIsWindows()) {
            int colonIndex = path.indexOf(':');
            if (colonIndex != -1) {
                volume = path.substring(0, colonIndex + 1);
                path = path.substring(colonIndex + 1);
            }
        }

        if (!noFile) {
            int lastSeparator = path.lastIndexOf(File.separator);
            if (lastSeparator != -1) {
                directory = path.substring(0, lastSeparator);
                file = path.substring(lastSeparator + 1);
            }
        }

        return new RuntimeList(
                new RuntimeScalar(volume), new RuntimeScalar(directory), new RuntimeScalar(file));
    }

    /**
     * Splits a directory path into its individual components.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the directory path.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the directory components.
     */
    public static RuntimeList splitdir(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for splitdir() method");
        }
        String directories = args.get(1).toString();
        String[] dirs = directories.split(Pattern.quote(File.separator));
        List<RuntimeScalar> dirList = new ArrayList<>();
        for (String dir : dirs) {
            dirList.add(new RuntimeScalar(dir));
        }
        return new RuntimeList(dirList);
    }

    /**
     * Constructs a complete path from volume, directory, and file components.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the volume, args[2] is the directory, and args[3] is the file.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the constructed path.
     */
    public static RuntimeList catpath(RuntimeArray args, int ctx) {
        if (args.size() != 4) {
            throw new IllegalStateException("Bad number of arguments for catpath() method");
        }

        String volume = args.get(1).toString();
        String directory = args.get(2).toString();
        String file = args.get(3).toString();

        StringBuilder fullPath = new StringBuilder();

        // Add volume (for Windows drive letters)
        if (!volume.isEmpty()) {
            fullPath.append(volume);
            // Ensure volume ends with colon on Windows
            if (SystemUtils.osIsWindows() && !volume.endsWith(":")) {
                fullPath.append(":");
            }
        }

        // Add directory
        if (!directory.isEmpty()) {
            fullPath.append(directory);
            // Ensure directory ends with separator if file is provided
            if (!file.isEmpty()) {
                char lastChar = directory.charAt(directory.length() - 1);
                if (lastChar != '/' && lastChar != '\\') {
                    fullPath.append(File.separator);
                }
            }
        }

        // Add file
        fullPath.append(file);

        // Clean up the path
        String result = canonpath(new RuntimeArray(
                new RuntimeScalar("dummy"),
                new RuntimeScalar(fullPath.toString())
        ), 0).elements.get(0).toString();

        return new RuntimeScalar(result).getList();
    }

    /**
     * Converts an absolute path to a relative path based on a given base path.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the absolute path and args[2] is optional base path.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the relative path.
     */
    public static RuntimeList abs2rel(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) {
            throw new IllegalStateException("Bad number of arguments for abs2rel() method");
        }
        String path = args.get(1).toString();
        String base = args.size() == 3 ? args.get(2).toString() : System.getProperty("user.dir");
        String relPath = Paths.get(base).relativize(Paths.get(path)).toString();
        return new RuntimeScalar(relPath).getList();
    }

    /**
     * Converts a relative path to an absolute path based on a given base path.
     *
     * @param args The arguments passed from the Perl environment, where args[1] is the relative path and args[2] is optional base path.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the absolute path.
     */
    public static RuntimeList rel2abs(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) {
            throw new IllegalStateException("Bad number of arguments for rel2abs() method");
        }
        String path = args.get(1).toString();
        String base = args.size() == 3 ? args.get(2).toString() : System.getProperty("user.dir");

        // If the path is already absolute, return it as-is (normalized)
        if (Paths.get(path).isAbsolute()) {
            String absPath = Paths.get(path).toAbsolutePath().normalize().toString();
            return new RuntimeScalar(absPath).getList();
        }

        // For relative paths, resolve against the base directory
        String absPath = Paths.get(base, path).toAbsolutePath().normalize().toString();
        return new RuntimeScalar(absPath).getList();
    }
}
