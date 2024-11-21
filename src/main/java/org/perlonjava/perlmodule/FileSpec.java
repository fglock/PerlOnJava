package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for File::Spec operations in Perl.
 * Extends PerlModuleBase to leverage module initialization and method registration.
 */
public class FileSpec extends PerlModuleBase {

    /**
     * Constructor for FileSpec.
     * Initializes the module with the name "File::Spec".
     */
    public FileSpec() {
        super("File::Spec");
    }

    /**
     * Static initializer to set up the File::Spec module.
     * This method initializes the exporter and defines the symbols that can be exported.
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
            fileSpec.registerMethod("splitpath", "$$");
            fileSpec.registerMethod("splitdir", "$");
            fileSpec.registerMethod("catpath", "$$$");
            fileSpec.registerMethod("abs2rel", "$$");
            fileSpec.registerMethod("rel2abs", "$$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing File::Spec method: " + e.getMessage());
        }
    }

    public static RuntimeList canonpath(RuntimeArray args, int ctx) {
        if (args.size() != 2) { // Expecting class name + 1 argument
            throw new IllegalStateException("Bad number of arguments for canonpath() method");
        }
        String path = args.get(1).toString(); // Ignore the first argument
        String canonPath = path.replaceAll("/+", "/").replaceAll("/\\./", "/");
        return new RuntimeScalar(canonPath).getList();
    }

    public static RuntimeList catdir(RuntimeArray args, int ctx) {
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < args.size(); i++) { // Start from index 1
            if (i > 1) path.append(File.separator);
            path.append(args.get(i).toString());
        }
        return new RuntimeScalar(path.toString()).getList();
    }

    public static RuntimeList catfile(RuntimeArray args, int ctx) {
        return catdir(args, ctx);
    }

    public static RuntimeList curdir(RuntimeArray args, int ctx) {
        return new RuntimeScalar(".").getList();
    }

    public static RuntimeList devnull(RuntimeArray args, int ctx) {
        return new RuntimeScalar("/dev/null").getList();
    }

    public static RuntimeList rootdir(RuntimeArray args, int ctx) {
        return new RuntimeScalar("/").getList();
    }

    public static RuntimeList tmpdir(RuntimeArray args, int ctx) {
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir == null || tmpDir.isEmpty()) {
            tmpDir = "/tmp";
        }
        return new RuntimeScalar(tmpDir).getList();
    }

    public static RuntimeList updir(RuntimeArray args, int ctx) {
        return new RuntimeScalar("..").getList();
    }

    public static RuntimeList no_upwards(RuntimeArray args, int ctx) {
        List<RuntimeScalar> filtered = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) { // Start from index 1
            String dir = args.get(i).toString();
            if (!dir.equals(".") && !dir.equals("..")) {
                filtered.add(args.get(i));
            }
        }
        return new RuntimeList(filtered);
    }

    public static RuntimeList case_tolerant(RuntimeArray args, int ctx) {
        return new RuntimeScalar(false).getList(); // Assuming Unix-like case sensitivity
    }

    public static RuntimeList file_name_is_absolute(RuntimeArray args, int ctx) {
        if (args.size() != 2) { // Expecting class name + 1 argument
            throw new IllegalStateException("Bad number of arguments for file_name_is_absolute() method");
        }
        String path = args.get(1).toString(); // Ignore the first argument
        boolean isAbsolute = Paths.get(path).isAbsolute();
        return new RuntimeScalar(isAbsolute).getList();
    }

    public static RuntimeList path(RuntimeArray args, int ctx) {
        String path = System.getenv("PATH");
        String[] paths = path != null ? path.split(File.pathSeparator) : new String[0];
        List<RuntimeScalar> pathList = new ArrayList<>();
        for (String p : paths) {
            pathList.add(new RuntimeScalar(p));
        }
        return new RuntimeList(pathList);
    }

    public static RuntimeList join(RuntimeArray args, int ctx) {
        return catfile(args, ctx);
    }

    public static RuntimeList splitpath(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) { // Expecting class name + 1 or 2 arguments
            throw new IllegalStateException("Bad number of arguments for splitpath() method");
        }
        String path = args.get(1).toString(); // Ignore the first argument
        boolean noFile = args.size() == 3 && args.get(2).getBoolean();
        String volume = "";
        String directory = path;
        String file = "";
        if (!noFile) {
            int lastSeparator = path.lastIndexOf(File.separator);
            if (lastSeparator != -1) {
                directory = path.substring(0, lastSeparator);
                file = path.substring(lastSeparator + 1);
            }
        }
        return new RuntimeList(
                List.of(new RuntimeScalar(volume), new RuntimeScalar(directory), new RuntimeScalar(file)));
    }

    public static RuntimeList splitdir(RuntimeArray args, int ctx) {
        if (args.size() != 2) { // Expecting class name + 1 argument
            throw new IllegalStateException("Bad number of arguments for splitdir() method");
        }
        String directories = args.get(1).toString(); // Ignore the first argument
        String[] dirs = directories.split(File.separator);
        List<RuntimeScalar> dirList = new ArrayList<>();
        for (String dir : dirs) {
            dirList.add(new RuntimeScalar(dir));
        }
        return new RuntimeList(dirList);
    }

    public static RuntimeList catpath(RuntimeArray args, int ctx) {
        if (args.size() != 4) { // Expecting class name + 3 arguments
            throw new IllegalStateException("Bad number of arguments for catpath() method");
        }
        String volume = args.get(1).toString(); // Ignore the first argument
        String directory = args.get(2).toString();
        String file = args.get(3).toString();
        String fullPath = directory + (directory.endsWith(File.separator) ? "" : File.separator) + file;
        return new RuntimeScalar(fullPath).getList();
    }

    public static RuntimeList abs2rel(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) { // Expecting class name + 1 or 2 arguments
            throw new IllegalStateException("Bad number of arguments for abs2rel() method");
        }
        String path = args.get(1).toString(); // Ignore the first argument
        String base = args.size() == 3 ? args.get(2).toString() : Cwd.getcwd(new RuntimeArray(), ctx).elements.get(0).toString();
        String relPath = Paths.get(base).relativize(Paths.get(path)).toString();
        return new RuntimeScalar(relPath).getList();
    }

    public static RuntimeList rel2abs(RuntimeArray args, int ctx) {
        if (args.size() < 2 || args.size() > 3) { // Expecting class name + 1 or 2 arguments
            throw new IllegalStateException("Bad number of arguments for rel2abs() method");
        }
        String path = args.get(1).toString(); // Ignore the first argument
        String base = args.size() == 3 ? args.get(2).toString() : Cwd.getcwd(new RuntimeArray(), ctx).elements.get(0).toString();
        String absPath = Paths.get(base, path).toAbsolutePath().normalize().toString();
        return new RuntimeScalar(absPath).getList();
    }
}
