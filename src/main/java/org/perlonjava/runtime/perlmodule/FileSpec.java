package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeEnvironment;
import org.perlonjava.runtime.runtimetypes.SystemUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for File::Spec operations in Perl.
 * This class provides methods that mimic the behavior of Perl's File::Spec module,
 * allowing for operations related to file path manipulation and environment-specific
 * path handling.
 *
 * <p>Extends {@link PerlModuleBase} to leverage module initialization and method registration.</p>
 *
 * <p>The bundled {@code File/Spec/Unix.pm} installs {@code canonpath}, {@code catdir} and
 * {@code catfile} with {@code *name = \&_pp_name unless defined &name}, so those three keep the
 * Java implementations below.  Every other sub in that file is defined unconditionally and
 * therefore shadows the Java method of the same name once {@code File::Spec} is loaded.</p>
 */
public class FileSpec extends PerlModuleBase {

    /**
     * Constructor for FileSpec.
     * Initializes the module with the name "File::Spec".
     */
    public FileSpec() {
        super("File::Spec", false);
    }

    private FileSpec(String packageName) {
        super(packageName, false);
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
        // Upstream File::Spec inherits its implementation from the selected
        // platform subclass. Install the Java-backed Unix defaults there so a
        // localized @File::Spec::ISA can select Win32/Mac methods normally.
        FileSpec implementation = new FileSpec("File::Spec::Unix");
        try {
            implementation.registerMethod("canonpath", "$");
            implementation.registerMethod("catdir", "@");
            implementation.registerMethod("catfile", "@");
            implementation.registerMethod("curdir", "");
            implementation.registerMethod("devnull", "");
            implementation.registerMethod("rootdir", "");
            implementation.registerMethod("tmpdir", "");
            implementation.registerMethod("updir", "");
            implementation.registerMethod("no_upwards", "@");
            implementation.registerMethod("case_tolerant", "");
            implementation.registerMethod("file_name_is_absolute", "$");
            implementation.registerMethod("path", "");
            implementation.registerMethod("join", "@");
            implementation.registerMethod("splitpath", "$;$");
            implementation.registerMethod("splitdir", "$");
            implementation.registerMethod("catpath", "$$$");
            implementation.registerMethod("abs2rel", "$;$");
            implementation.registerMethod("rel2abs", "$;$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing File::Spec method: " + e.getMessage());
        }
    }

    /**
     * Wraps a freshly built path in a {@link RuntimeList}, carrying over the taint of every
     * path component the caller supplied.
     *
     * <p>The Perl originals of these methods build their answer with {@code join}, {@code s///}
     * and string concatenation, all of which propagate taint, so a tainted component must taint
     * the result here too.  Only the caller's arguments are considered: none of these methods
     * consults the operating system, so none of them introduces taint of its own.  The invocant
     * in {@code args[0]} is a class name or object and is never part of the result.</p>
     *
     * @param result The path value produced by the Java implementation.
     * @param args   The full argument array, including the invocant at index 0.
     * @return A {@link RuntimeList} holding the possibly tainted result.
     */
    private static RuntimeList withArgumentTaint(RuntimeScalar result, RuntimeArray args) {
        int count = args.size() - 1;
        if (count <= 0) {
            return result.getList();
        }
        RuntimeScalar[] inputs = new RuntimeScalar[count];
        for (int i = 0; i < count; i++) {
            inputs[i] = args.get(i + 1);
        }
        // propagateTaint() may hand back a different scalar, so use its return value.
        return result.propagateTaint(inputs).getList();
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
            return withArgumentTaint(new RuntimeScalar(""), args);
        }

        // These Java methods are installed in File::Spec::Unix. Platform
        // subclasses (including File::Spec::Win32) override them in Perl, so
        // their behavior must remain Unix-specific even on a Windows host.
        String canonPath = path.replaceAll("/{2,}", "/");
        canonPath = canonPath.replaceAll("(?:/\\.)+(?:/|$)", "/");
        if (!canonPath.equals("./")) {
            canonPath = canonPath.replaceFirst("^(?:\\./)+", "");
        }
        canonPath = canonPath.replaceFirst("^/(?:\\.\\./)+", "/");
        canonPath = canonPath.replaceFirst("^/\\.\\.$", "/");
        if (!canonPath.equals("/") && canonPath.endsWith("/")) {
            canonPath = canonPath.substring(0, canonPath.length() - 1);
        }
        
        // If we reduced to empty string from a non-empty input, return "."
        if (canonPath.isEmpty()) {
            canonPath = ".";
        }

        return withArgumentTaint(new RuntimeScalar(canonPath), args);
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
        String separator = "/";
        boolean isFirst = true;

        for (int i = 1; i < args.size(); i++) {
            String part = args.get(i).toString();

            // Empty first element represents root directory on Unix
            if (part.isEmpty()) {
                if (isFirst) {
                    // First empty element = absolute path (root)
                    result.append(separator);
                }
                isFirst = false;
                continue;
            }
            isFirst = false;

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
        String canonical = canonpath(canonArgs, ctx).elements.get(0).toString();
        // The intermediate scalars above are clean, so the taint of the caller's
        // components has to be re-applied to the final answer.
        return withArgumentTaint(new RuntimeScalar(canonical), args);
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
            return withArgumentTaint(new RuntimeScalar(""), args);
        }

        // Last real arg is the file component; everything before is directories
        RuntimeScalar file = args.get(args.size() - 1);
        
        // Build directory portion using catdir
        RuntimeArray dirArgs = new RuntimeArray();
        for (int i = 0; i < args.size() - 1; i++) {
            // catdir only consumes the string value.  Do not put the caller's
            // scalar into this short-lived container: push() acquires a tracked
            // reference, and Java temporaries are not guaranteed to receive
            // Perl scope cleanup.  Retaining an overloaded File::Temp::Dir here
            // postpones its deterministic DESTROY (and upload cleanup) until
            // JVM shutdown.
            dirArgs.push(new RuntimeScalar(args.get(i).toString()));
        }
        String dir = catdir(dirArgs, ctx).elements.get(0).toString();
        
        // Canonpath the file part
        RuntimeArray fileCanonArgs = new RuntimeArray();
        fileCanonArgs.push(new RuntimeScalar("dummy"));
        fileCanonArgs.push(new RuntimeScalar(file.toString()));
        String filePart = canonpath(fileCanonArgs, ctx).elements.get(0).toString();
        
        // Combine: if dir is empty, just return the file
        // The catdir/canonpath calls above ran on clean copies, so the caller's
        // taint is re-applied to whichever combination is returned.
        if (dir.isEmpty()) {
            return withArgumentTaint(new RuntimeScalar(filePart), args);
        }

        // Ensure proper separator between dir and file
        String separator = "/";
        char lastChar = dir.charAt(dir.length() - 1);
        if (lastChar == '/' || lastChar == '\\') {
            return withArgumentTaint(new RuntimeScalar(dir + filePart), args);
        }
        return withArgumentTaint(new RuntimeScalar(dir + separator + filePart), args);
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
        RuntimeHash perlEnv = GlobalVariable.getGlobalHash("main::ENV");
        List<String> candidates = new ArrayList<>();
        if (SystemUtils.osIsWindows()) {
            candidates.add(perlEnvValue(perlEnv, "TMPDIR", System.getenv("TMPDIR")));
            candidates.add(perlEnvValue(perlEnv, "TEMP", System.getenv("TEMP")));
            candidates.add(perlEnvValue(perlEnv, "TMP", System.getenv("TMP")));
        } else {
            candidates.add(perlEnvValue(perlEnv, "TMPDIR", System.getenv("TMPDIR")));
            candidates.add("/tmp");
        }

        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            File dir = new File(candidate);
            if (dir.isDirectory() && dir.canWrite()) {
                return new RuntimeScalar(candidate).getList();
            }
        }
        return new RuntimeScalar(".").getList();
    }

    private static String perlEnvValue(RuntimeHash perlEnv, String key, String fallback) {
        RuntimeScalar value = perlEnv.elements.get(key);
        return value != null && value.getDefinedBoolean() ? value.toString() : fallback;
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
        return new RuntimeScalar(caseTolerant ? 1 : 0).getList();
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
            // In scalar context, return count (0) — mirrors Perl's split behaviour
            if (ctx == RuntimeContextType.SCALAR) {
                return new RuntimeScalar(0).getList();
            }
            return new RuntimeList(new ArrayList<>());
        }
        // On Windows, File::Spec::Win32::splitdir splits on both '/' and '\'.
        // On Unix, File::Spec::Unix::splitdir splits on '/'.
        String splitPattern = File.separator.equals("\\") ? "[/\\\\]" : Pattern.quote(File.separator);
        String[] dirs = directories.split(splitPattern, -1);
        // In scalar context, return the count — mirrors Perl's `split` returning
        // the number of fields when evaluated in scalar context (perlop "split").
        if (ctx == RuntimeContextType.SCALAR) {
            return new RuntimeScalar(dirs.length).getList();
        }
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
        if (args.size() > 4) {
            throw new IllegalStateException("Bad number of arguments for catpath() method");
        }

        String volume = args.size() > 1 ? args.get(1).toString() : "";
        String directory = args.size() > 2 ? args.get(2).toString() : "";
        String file = args.size() > 3 ? args.get(3).toString() : "";

        if (SystemUtils.osIsWindows()) {
            StringBuilder fullPath = new StringBuilder(volume);

            if (isUncVolume(volume) && startsWithoutSeparator(directory)) {
                fullPath.append(volume.charAt(0));
            }

            fullPath.append(directory);

            if (!isDriveVolumeOnly(fullPath.toString())
                    && endsWithoutSeparator(fullPath)
                    && containsNonSeparator(file)) {
                fullPath.append(firstSeparatorOrDefault(fullPath, '\\'));
            }

            fullPath.append(file);
            return new RuntimeScalar(fullPath.toString()).getList();
        }

        if (!directory.isEmpty()
                && !file.isEmpty()
                && directory.charAt(directory.length() - 1) != '/'
                && file.charAt(0) != '/') {
            directory += "/" + file;
        } else {
            directory += file;
        }

        return new RuntimeScalar(directory).getList();
    }

    private static boolean isUncVolume(String volume) {
        return volume.matches("^[\\\\/][\\\\/][^\\\\/]+[\\\\/][^\\\\/]+$");
    }

    private static boolean startsWithoutSeparator(String value) {
        return !value.isEmpty() && !isSeparator(value.charAt(0));
    }

    private static boolean endsWithoutSeparator(CharSequence value) {
        return value.length() > 0 && !isSeparator(value.charAt(value.length() - 1));
    }

    private static boolean containsNonSeparator(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isSeparator(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static char firstSeparatorOrDefault(CharSequence value, char defaultSeparator) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (isSeparator(ch)) {
                return ch;
            }
        }
        return defaultSeparator;
    }

    private static boolean isDriveVolumeOnly(String value) {
        return value.length() == 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':';
    }

    private static boolean isSeparator(char ch) {
        return ch == '/' || ch == '\\';
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
        String base = args.size() == 3 ? args.get(2).toString() : RuntimeEnvironment.currentDirectory();
        
        // Ensure both paths are absolute before relativizing (like Perl does)
        // Note: We use user.dir explicitly because Java's Path.toAbsolutePath() 
        // doesn't respect System.setProperty("user.dir", ...) set by chdir()
        Path pathObj = Paths.get(path);
        Path baseObj = Paths.get(base);
        String userDir = RuntimeEnvironment.currentDirectory();
        
        if (!pathObj.isAbsolute()) {
            pathObj = Paths.get(userDir).resolve(pathObj).normalize();
        }
        if (!baseObj.isAbsolute()) {
            baseObj = Paths.get(userDir).resolve(baseObj).normalize();
        }
        
        String relPath = baseObj.relativize(pathObj).toString();
        // Perl's File::Spec->abs2rel returns "." (curdir) when path equals base,
        // but Java's Path.relativize returns an empty string in that case.
        if (relPath.isEmpty()) {
            relPath = ".";
        }
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
        String base = args.size() == 3 ? args.get(2).toString() : RuntimeEnvironment.currentDirectory();

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
            basePath = Paths.get(RuntimeEnvironment.currentDirectory()).resolve(basePath);
        }

        // For relative paths, resolve against the base directory
        String absPath = basePath.resolve(path).normalize().toString();
        return new RuntimeScalar(absPath).getList();
    }
}
