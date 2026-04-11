package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.SystemUtils;

import java.io.File;
import java.nio.file.Path;
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
            fileSpec.registerMethod("splitpath", "$;$");
            fileSpec.registerMethod("splitdir", "$");
            fileSpec.registerMethod("catpath", "$$$");
            fileSpec.registerMethod("abs2rel", "$;$");
            fileSpec.registerMethod("rel2abs", "$;$");
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
        
        // Empty string stays empty (Perl 5 behavior)
        if (path.isEmpty()) {
            return new RuntimeScalar("").getList();
        }
        
        // Implement Perl 5's File::Spec::Unix::canonpath logic:
        // 1. Collapse multiple slashes into one
        // 2. Collapse /./  and also /. at end of string
        // 3. Remove leading ./  (unless path is exactly "./")
        // 4. Remove trailing /  (unless path is exactly "/")
        String sep = File.separator;
        String quotedSep = Pattern.quote(sep);
        String replSep = Matcher.quoteReplacement(sep);
        
        // Collapse multiple separators into one
        String canonPath = path.replaceAll("[/\\\\]+", replSep);
        
        // Collapse /./ and /. at end: (?:/\.)+(?:/|$) -> /
        // This handles both /./bar -> /bar and foo/. -> foo
        canonPath = canonPath.replaceAll("(?:" + quotedSep + "\\.)+(?=" + quotedSep + "|$)", "");
        
        // Remove leading ./ unless the path is exactly "./" or "."
        if (!canonPath.equals("." + sep) && !canonPath.equals(".")) {
            while (canonPath.startsWith("." + sep)) {
                canonPath = canonPath.substring(1 + sep.length());
            }
        }
        
        // Remove trailing / unless the path is exactly "/"
        if (!canonPath.equals(sep) && canonPath.endsWith(sep)) {
            canonPath = canonPath.substring(0, canonPath.length() - sep.length());
        }
        
        // If we reduced to empty string from a non-empty input, return "."
        if (canonPath.isEmpty()) {
            canonPath = ".";
        }
        
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
        boolean isFirst = true;

        for (int i = 1; i < args.size(); i++) {
            String part = args.get(i).toString();

            // Empty first element represents root directory on Unix
            if (part.isEmpty()) {
                if (isFirst && !isWindows) {
                    // First empty element = absolute path (root)
                    result.append(separator);
                }
                isFirst = false;
                continue;
            }
            isFirst = false;

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

        // Apply canonpath to the result, matching Perl's File::Spec::Unix behavior
        // where catdir calls canonpath(join('/', @_, ''))
        RuntimeArray canonArgs = new RuntimeArray();
        canonArgs.push(new RuntimeScalar("dummy"));
        canonArgs.push(new RuntimeScalar(result.toString()));
        return canonpath(canonArgs, ctx);
    }

    /**
     * Concatenates multiple file names into a single path.
     * Uses catdir for the directory components and canonpath for the file component.
     *
     * @param args The arguments passed from the Perl environment, representing file names.
     * @param ctx  The context in which the method is called.
     * @return A {@link RuntimeList} containing the concatenated file path.
     */
    public static RuntimeList catfile(RuntimeArray args, int ctx) {
        if (args.size() <= 2) {
            // 0 or 1 real args (first is invocant) — just canonpath the single arg
            if (args.size() == 2) {
                return canonpath(args, ctx);
            }
            return new RuntimeScalar("").getList();
        }

        // Last real arg is the file component; everything before is directories
        RuntimeScalar file = args.get(args.size() - 1);
        
        // Build directory portion using catdir
        RuntimeArray dirArgs = new RuntimeArray();
        for (int i = 0; i < args.size() - 1; i++) {
            dirArgs.push(args.get(i));
        }
        String dir = catdir(dirArgs, ctx).elements.get(0).toString();
        
        // Canonpath the file part
        RuntimeArray fileCanonArgs = new RuntimeArray();
        fileCanonArgs.push(new RuntimeScalar("dummy"));
        fileCanonArgs.push(file);
        String filePart = canonpath(fileCanonArgs, ctx).elements.get(0).toString();
        
        // Combine: if dir is empty, just return the file
        if (dir.isEmpty()) {
            return new RuntimeScalar(filePart).getList();
        }
        
        // Ensure proper separator between dir and file
        String separator = File.separator;
        char lastChar = dir.charAt(dir.length() - 1);
        if (lastChar == '/' || lastChar == '\\') {
            return new RuntimeScalar(dir + filePart).getList();
        }
        return new RuntimeScalar(dir + separator + filePart).getList();
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
        // PerlOnJava: Also recognize jar: paths as absolute
        if (path.startsWith("jar:")) {
            return new RuntimeScalar(true).getList();
        }
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
        // Read PATH from Perl's %ENV (not Java's System.getenv) so that
        // modifications to $ENV{PATH} in Perl code are respected.
        RuntimeHash perlEnv = GlobalVariable.getGlobalHash("main::ENV");
        RuntimeScalar pathScalar = perlEnv.get(new RuntimeScalar("PATH"));
        String path = pathScalar.getDefinedBoolean() ? pathScalar.toString() : System.getenv("PATH");
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
        String directory = "";
        String file = "";

        if (SystemUtils.osIsWindows()) {
            int colonIndex = path.indexOf(':');
            if (colonIndex != -1) {
                volume = path.substring(0, colonIndex + 1);
                path = path.substring(colonIndex + 1);
            }
        }

        if (noFile) {
            // If noFile is true, entire path is directory
            directory = path;
        } else {
            int lastSeparator = path.lastIndexOf(File.separator);
            if (lastSeparator != -1) {
                directory = path.substring(0, lastSeparator + 1);
                file = path.substring(lastSeparator + 1);
            } else {
                // No separator - entire path is the filename
                file = path;
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
        // Empty string returns empty list (Perl 5 behavior)
        if (directories.isEmpty()) {
            return new RuntimeList(new ArrayList<>());
        }
        String[] dirs = directories.split(Pattern.quote(File.separator), -1);
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
        
        // Ensure both paths are absolute before relativizing (like Perl does)
        // Note: We use user.dir explicitly because Java's Path.toAbsolutePath() 
        // doesn't respect System.setProperty("user.dir", ...) set by chdir()
        Path pathObj = Paths.get(path);
        Path baseObj = Paths.get(base);
        String userDir = System.getProperty("user.dir");
        
        if (!pathObj.isAbsolute()) {
            pathObj = Paths.get(userDir).resolve(pathObj).normalize();
        }
        if (!baseObj.isAbsolute()) {
            baseObj = Paths.get(userDir).resolve(baseObj).normalize();
        }
        
        String relPath = baseObj.relativize(pathObj).toString();
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

        // PerlOnJava: jar: paths are already absolute, return as-is
        if (path.startsWith("jar:")) {
            return new RuntimeScalar(path).getList();
        }

        // If the path is already absolute, return it as-is (normalized)
        if (Paths.get(path).isAbsolute()) {
            String absPath = Paths.get(path).toAbsolutePath().normalize().toString();
            return new RuntimeScalar(absPath).getList();
        }

        // If base is relative, resolve it against current working directory first
        Path basePath = Paths.get(base);
        if (!basePath.isAbsolute()) {
            basePath = Paths.get(System.getProperty("user.dir")).resolve(basePath);
        }

        // For relative paths, resolve against the base directory
        String absPath = basePath.resolve(path).normalize().toString();
        return new RuntimeScalar(absPath).getList();
    }
}
